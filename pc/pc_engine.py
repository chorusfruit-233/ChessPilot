#!/usr/bin/env python3
"""ChessPilot-PC: desktop Stockfish server for the ChessPilot phone companion.

Standalone project (sibling of the ChessPilot phone repo, NOT part of it):
the phone sends FENs over LAN TCP, this PC crunches them with desktop
Stockfish 19 and sends back bestmove/score/depth. Zero third-party
dependencies — Python 3.8+ stdlib only.

Protocol (UTF-8 JSON, one object per line, phone -> this server):
  {"t":"analyse","id":N,"fen":"...","movetimeMs":M,"depth":D}
      -> {"t":"result","id":N,"uci":"e2e4","ponder":"e7e5",
          "scoreCp":35,"depth":28,"isMate":false}
      Think shape: the request's movetimeMs/depth WIN when > 0 (the phone's
      remote sliders); --movetime/--depth below are defaults for clients that
      omit them. Threads/hash stay server-side (need a process restart).
  {"t":"ponder","id":N,"fen":"...","ponderMove":"e7e5",
   "movetimeMs":M,"depth":D}
      -> starts `go ponder [movetime M | depth D]` and streams completed PVs:
         {"t":"ponder_info","id":N,"uci":"g1f3","ponder":"b8c6",
          "scoreCp":25,"depth":18,"isMate":false,"fen":"<predicted position>"}.
      The phone can reuse this prepared reply when the prediction is confirmed.
  {"t":"ponderhit","id":M}
      -> sends UCI stop to finalize searched work, then {"t":"result","id":N,...}.
      NOTE the id echo: the reply carries the PONDER command's id (N), not
      the hit's (M) — the phone matches replies to its ponder reader.
  {"t":"stop","id":N}   -> cancels the running search, {"t":"stopped","id":N}.
  {"t":"ping"}          -> {"t":"pong"}.
  {"t":"info"}          -> {"t":"info","name":"Stockfish 19",
                            "threads":16,"hash":2048,"movetime":3000,"depth":0}.
  Errors: {"t":"error","id":N,"message":"..."} (malformed FEN, engine dead...).

Engine binary: --engine PATH, else ./engine/*.exe, else auto-downloaded from
the official Stockfish site (SF 19 Windows x86-64 universal zip).

Usage:
  python pc_engine.py                      # 0.0.0.0:41718, auto engine, 16T/2G/3s
  python pc_engine.py --port 41718 --threads 8 --hash 1024 --movetime 2000
  python pc_engine.py --engine C:\\Tools\\stockfish.exe
  python pc_engine.py --selftest           # startpos smoke test, then exit
"""
import argparse
import json
import queue
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ENGINE_DIR = HERE / "engine"
SF19_WIN_URL = (
    "https://github.com/official-stockfish/Stockfish/releases/latest/download/"
    "stockfish-windows-x86-64-universal.zip"
)

# ---------------------------------------------------------------- logging ---

def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ------------------------------------------------------- engine download ---

def find_engine_binary():
    # Recursive: the official zip nests the exe one level down
    # (engine/stockfish/stockfish-windows-x86-64-universal.exe).
    exes = sorted(ENGINE_DIR.rglob("*.exe"))
    # Skip anything that is clearly not the engine (none known today, but a
    # source-tree layout ships alongside — match the stockfish naming).
    sf = [e for e in exes if "stockfish" in e.name.lower()] or exes
    if not sf:
        return None
    if len(sf) == 1:
        return sf[0]
    for e in sf:  # prefer AVX2 on multi-binary packs
        if "avx2" in e.name.lower():
            return e
    return sf[0]


def ensure_engine(explicit):
    if explicit:
        p = Path(explicit)
        if not p.is_file():
            sys.exit(f"engine not found: {explicit}")
        return p
    found = find_engine_binary()
    if found:
        log(f"engine: {found}")
        return found
    log("no engine in ./engine/, downloading Stockfish 19 (official site)...")
    ENGINE_DIR.mkdir(exist_ok=True)
    zpath = ENGINE_DIR / "stockfish-win.zip"
    try:
        urllib.request.urlretrieve(SF19_WIN_URL, zpath)
    except Exception as e:
        sys.exit(f"download failed ({e}); download manually from "
                 "https://stockfishchess.org/download/ and pass --engine PATH")
    try:
        with zipfile.ZipFile(zpath) as z:
            z.extractall(ENGINE_DIR)
    except Exception as e:
        sys.exit(f"unzip failed ({e})")
    try:
        zpath.unlink()
    except OSError:
        pass
    found = find_engine_binary()
    if not found:
        sys.exit(f"extracted but no .exe found in {ENGINE_DIR}")
    log(f"engine ready: {found}")
    return found


# ------------------------------------------------------------- engine UCI ---

class Engine:
    """One desktop Stockfish process. Single-job discipline (phone never has
    analyse+ponder in flight at once): every public method holds `lock`."""

    def __init__(self, binary, threads, hash_mb, movetime_ms, depth):
        self.binary = str(binary)
        self.threads = threads
        self.hash_mb = hash_mb
        self.movetime_ms = movetime_ms
        self.depth = depth
        self.lock = threading.RLock()
        self.stop_lock = threading.Lock()
        self.proc = None
        self.lines = queue.Queue()
        self.reader = None
        self.alive = False
        self.engine_name = "Stockfish ?"
        # Ponder state (set while a `go ponder` search runs).
        self.pondering = False
        self.ponder_result = None
        self.ponder_event = threading.Event()
        # The ponder COMMAND's id (not the later ponderhit's): replies echo
        # this so the phone can match them to its ponder reader. Without it
        # the phone's id check would skip its own hit reply and hang the
        # ponder thread into a false "hung engine" kill.
        self.ponder_req_id = None
        # Drain fence for the sniffer (see _sync).
        self.syncing = False
        # Ponder score accumulators (fed by the sniffer thread, read on
        # bestmove): a ponder-hit result needs score/depth too, otherwise the
        # phone shows "-" winrate and trips its shallow-depth alarm.
        self._sniff_score = None
        self._sniff_mate = False
        self._sniff_depth = 0
        self.ponder_update = None
        self.ponder_fen = None
        self.sniffer_stop = threading.Event()

    # -- lifecycle ------------------------------------------------------
    def start(self):
        self.sniffer_stop = threading.Event()
        self.proc = subprocess.Popen(
            [self.binary], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1,
        )
        self.alive = True
        self.reader = threading.Thread(target=self._read_loop, args=(self.proc, self.lines), daemon=True)
        self.reader.start()
        self._send("uci")
        if not self._wait_prefix("uciok", 15):
            raise RuntimeError("uciok timeout")
        self._send(f"setoption name Threads value {self.threads}")
        self._send(f"setoption name Hash value {self.hash_mb}")
        self._send("setoption name MultiPV value 1")
        self._send("isready")
        if not self._wait_prefix("readyok", 15):
            raise RuntimeError("readyok timeout")
        log(f"engine up: {self.engine_name} "
            f"(threads={self.threads} hash={self.hash_mb}MB "
            f"movetime={self.movetime_ms}ms depth={self.depth or 'time-based'})")
        threading.Thread(target=_ponder_sniffer, args=(self, self.sniffer_stop), daemon=True).start()

    def stop(self):
        # Disconnect cleanup and server shutdown can both stop this process.
        # Wait for the first cleanup to reap it before the server can exit.
        with self.stop_lock:
            self._stop()

    def _stop(self):
        self.sniffer_stop.set()
        self.alive = False
        proc = self.proc
        self.proc = None
        if proc is None:
            return
        try:
            if proc.stdin:
                proc.stdin.write("quit\n")
                proc.stdin.flush()
        except Exception:
            pass
        try:
            proc.wait(timeout=3)
        except Exception:
            try:
                proc.kill()
                proc.wait(timeout=3)
            except Exception:
                pass
        if self.reader is not threading.current_thread() and self.reader is not None:
            self.reader.join(timeout=1)
        for stream in (proc.stdin, proc.stdout):
            if stream is not None:
                stream.close()

    def restart(self):
        log("restarting engine...")
        try:
            self.stop()
        except Exception:
            pass
        self.lines = queue.Queue()
        self.pondering = False
        self.ponder_result = None
        self.ponder_event.clear()
        self.ponder_req_id = None
        self.syncing = False
        self.start()

    # -- IO -------------------------------------------------------------
    def _send(self, cmd):
        if not self.proc or not self.proc.stdin:
            raise RuntimeError("engine not running")
        self.proc.stdin.write(cmd + "\n")
        self.proc.stdin.flush()

    def _read_loop(self, proc, lines):
        try:
            for line in proc.stdout:
                line = line.strip()
                if not line:
                    continue
                if line.startswith("id name "):
                    self.engine_name = line[len("id name "):]
                    continue
                lines.put(line)
        except Exception:
            pass
        finally:
            lines.put(None)  # EOF belongs to this process, never its replacement.

    def _wait_prefix(self, prefix, timeout):
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = self.lines.get(timeout=max(0.1, end - time.time()))
            except queue.Empty:
                return False
            if line is None:
                return False
            if line.startswith(prefix):
                return True
        return False

    def _sync(self):
        """Stop anything running and drain to a clean readyok. Returns False
        when the engine is unresponsive (caller restarts it)."""
        # Fence the sniffer out while draining: it would otherwise steal the
        # readyok (stalling us 10 s) or attach a stale bestmove to fresh state.
        self.syncing = True
        try:
            try:
                self._send("stop")
                # SF19 answers isready even while its search is still stopping.
                # setoption waits for search completion, fencing old bestmove.
                self._send("setoption name MultiPV value 1")
                self._send("isready")
            except Exception:
                return False
            end = time.time() + 10
            while time.time() < end:
                try:
                    line = self.lines.get(timeout=max(0.1, end - time.time()))
                except queue.Empty:
                    return False
                if line is None:
                    return False
                if line.startswith("readyok"):
                    return True
                # else: stale bestmove/info from the killed search — discard.
            return False
        finally:
            self.syncing = False

    @staticmethod
    def _sane_fen(fen):
        try:
            return len(fen.strip().split()) == 6
        except Exception:
            return False

    @staticmethod
    def _go_cmd(movetime_ms, depth):
        if depth and depth > 0:
            return f"go depth {depth}"
        return f"go movetime {movetime_ms}"

    def _collect_result(self, req_id, timeout):
        """Read lines until bestmove (single-job: any bestmove is ours)."""
        depth = 0
        score = None
        is_mate = False
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = self.lines.get(timeout=max(0.1, end - time.time()))
            except queue.Empty:
                break
            if line is None:
                break
            if line.startswith("info depth "):
                try:
                    d = int(line[len("info depth "):].split()[0])
                    depth = max(depth, d)
                except (ValueError, IndexError):
                    pass
                if " score cp " in line and " pv " in line:
                    try:
                        score = int(line.split(" score cp ")[1].split()[0])
                        is_mate = False
                    except (ValueError, IndexError):
                        pass
                elif " score mate " in line:
                    try:
                        score = int(line.split(" score mate ")[1].split()[0]) * 10000
                        is_mate = True
                    except (ValueError, IndexError):
                        pass
            elif line.startswith("bestmove"):
                parts = line.split()
                uci = parts[1] if len(parts) > 1 else None
                ponder = None
                if " ponder " in line:
                    ponder = line.split(" ponder ", 1)[1].strip() or None
                if not uci:
                    break
                return {"t": "result", "id": req_id, "uci": uci,
                        "ponder": ponder, "scoreCp": score,
                        "depth": depth, "isMate": is_mate}
        return None

    # -- jobs (all hold `lock`: one job at a time) ----------------------
    def analyse(self, req_id, fen, movetime_ms=0, depth=0):
        with self.lock:
            if not self._sane_fen(fen):
                return {"t": "error", "id": req_id,
                        "message": "malformed FEN (need 6 fields)"}
            if not self._sync():
                self.restart()
                if not self._sync():
                    return {"t": "error", "id": req_id,
                            "message": "engine unresponsive"}
            # Request wins when present (phone remote sliders); server flags
            # are defaults for clients that omit them.
            use_mt = movetime_ms if movetime_ms > 0 else self.movetime_ms
            use_d = depth if depth > 0 else self.depth
            self.pondering = False
            self.ponder_result = None
            self.ponder_event.clear()
            self.ponder_req_id = None
            self._sniff_reset()
            try:
                self._send(f"position fen {fen}")
                self._send(self._go_cmd(use_mt, use_d))
            except Exception as e:
                return {"t": "error", "id": req_id, "message": f"send: {e}"}
            budget = 180 if use_d else (use_mt / 1000 + 30)
            t0 = time.time()
            res = self._collect_result(req_id, budget)
            dt = time.time() - t0
            if res is None:
                log(f"analyse #{req_id}: TIMEOUT after {dt:.1f}s, restarting engine")
                try:
                    self.restart()
                except Exception as e:
                    return {"t": "error", "id": req_id,
                            "message": f"engine died: {e}"}
                return {"t": "error", "id": req_id,
                        "message": "search timeout (engine restarted)"}
            log(f"analyse #{req_id}: {res['uci']} depth={res['depth']} "
                f"score={res['scoreCp']} ({dt:.2f}s)")
            return res

    def ponder(self, req_id, fen, ponder_move, movetime_ms=0, depth=0, on_update=None):
        with self.lock:
            if not self._sane_fen(fen):
                return {"t": "error", "id": req_id,
                        "message": "malformed FEN (need 6 fields)"}
            if not self._sync():
                self.restart()
                if not self._sync():
                    return {"t": "error", "id": req_id,
                            "message": "engine unresponsive"}
            # Stream completed PVs while Stockfish withholds bestmove. A hit
            # finalizes the current work with stop, without another time budget.
            use_d = depth if depth > 0 else self.depth
            use_mt = movetime_ms if movetime_ms > 0 else self.movetime_ms
            if use_d > 0:
                go = f"go ponder depth {use_d}"
            elif use_mt > 0:
                go = f"go ponder movetime {use_mt}"
            else:
                go = "go ponder"
            self.pondering = True
            self.ponder_result = None
            self.ponder_event.clear()
            self.ponder_req_id = req_id
            self.ponder_update = on_update
            self.ponder_fen = None
            self._sniff_reset()
            try:
                self._send(f"position fen {fen} moves {ponder_move}")
                self._send("d")
                self._send(go)
            except Exception as e:
                self.pondering = False
                self.ponder_req_id = None
                return {"t": "error", "id": req_id, "message": f"send: {e}"}
            log(f"ponder #{req_id}: reply to {ponder_move} ({go})")
            return None  # result arrives via ponderhit (or stop)

    def ponderhit(self, req_id):
        with self.lock:
            if not self.pondering:
                return {"t": "error", "id": req_id,
                        "message": "not pondering"}
            try:
                self._send("stop")
            except Exception as e:
                self.pondering = False
                return {"t": "error", "id": req_id, "message": f"send: {e}"}
        # The sniffer needs this lock to consume bestmove, so wait outside it.
        # stop finalizes the existing iteration; do not wait out another budget.
        if self.ponder_event.wait(timeout=3):
            self.pondering = False
            res = self.ponder_result
            self.ponder_result = None
            if res is not None:
                # Echo the PONDER command's id (kept in ponder_req_id), not
                # this hit's id — the phone matches replies to its reader.
                res["id"] = self.ponder_req_id if self.ponder_req_id is not None else req_id
                self.ponder_req_id = None
                log(f"ponderhit #{req_id}: {res['uci']} depth={res['depth']}")
                return res
        # Timeout or EOF: drain whatever came, resync, fail loud.
        self.pondering = False
        with self.lock:
            if not self._sync():
                try:
                    self.restart()
                except Exception:
                    pass
        return {"t": "error", "id": req_id,
                "message": "ponderhit produced nothing (engine resynced)"}

    def cancel(self):
        with self.lock:
            self.pondering = False
            self.ponder_result = None
            self.ponder_event.clear()
            self.ponder_req_id = None
            self.ponder_update = None
            try:
                self._send("stop")
            except Exception:
                pass

    def _sniff_reset(self):
        self._sniff_score = None
        self._sniff_mate = False
        self._sniff_depth = 0

    def _sniff_info(self, line):
        """Fold one ponder-time `info` line into the accumulators (same score
        discipline as the phone: last pv score wins, mate tracked)."""
        if self.ponder_fen is None:
            return
        try:
            d = int(line[len("info depth "):].split()[0])
            self._sniff_depth = max(self._sniff_depth, d)
        except (ValueError, IndexError):
            pass
        if " score cp " in line and " pv " in line:
            try:
                self._sniff_score = int(line.split(" score cp ")[1].split()[0])
                self._sniff_mate = False
            except (ValueError, IndexError):
                pass
        elif " score mate " in line:
            try:
                self._sniff_mate = True
                self._sniff_score = int(line.split(" score mate ")[1].split()[0]) * 10000
            except (ValueError, IndexError):
                pass
        if " pv " in line and "lowerbound" not in line and "upperbound" not in line:
            pv = line.split(" pv ", 1)[1].split()
            if pv and self.ponder_update is not None and self.ponder_fen is not None:
                self.ponder_update({"t": "ponder_info", "id": self.ponder_req_id,
                                    "uci": pv[0], "ponder": pv[1] if len(pv) > 1 else None,
                                    "scoreCp": self._sniff_score, "depth": self._sniff_depth,
                                    "isMate": self._sniff_mate, "fen": self.ponder_fen})

    def _sniff_bestmove(self, line):
        """Mid-ponder bestmove (mate found / depth-ponder done): capture it
        for the ponderhit fast path. Stale lines (not pondering) are dropped."""
        if not self.pondering or self.ponder_event.is_set() or self.ponder_fen is None:
            return
        parts = line.split()
        uci = parts[1] if len(parts) > 1 else None
        if not uci:
            return
        ponder = line.split(" ponder ", 1)[1].strip() or None if " ponder " in line else None
        self.ponder_result = {"t": "result", "id": -1, "uci": uci,
                              "ponder": ponder, "scoreCp": self._sniff_score,
                              "depth": self._sniff_depth,
                              "isMate": self._sniff_mate, "fen": self.ponder_fen}
        self.ponder_event.set()


# ---------------------------------------------------------------- server ---

def handle_conn(conn, addr, engine):
    log(f"phone connected: {addr[0]}:{addr[1]}")
    f = conn.makefile("r", encoding="utf-8", errors="replace")
    send_lock = threading.Lock()

    def respond(result):
        with send_lock:
            conn.sendall((json.dumps(result) + "\n").encode("utf-8"))

    try:
        for raw in f:
            raw = raw.strip()
            if not raw:
                continue
            try:
                o = json.loads(raw)
            except json.JSONDecodeError:
                continue
            t = o.get("t")
            rid = o.get("id", -1)
            try:
                if t == "analyse":
                    res = engine.analyse(rid, o.get("fen", ""),
                                         o.get("movetimeMs", 0) or 0,
                                         o.get("depth", 0) or 0)
                elif t == "ponder":
                    res = engine.ponder(rid, o.get("fen", ""),
                                        o.get("ponderMove", ""),
                                        o.get("movetimeMs", 0) or 0,
                                        o.get("depth", 0) or 0,
                                        on_update=respond)
                    if res is None:
                        continue  # ponder runs; answer comes via ponderhit
                elif t == "ponderhit":
                    res = engine.ponderhit(rid)
                elif t == "stop":
                    engine.cancel()
                    res = {"t": "stopped", "id": rid}
                elif t == "ping":
                    res = {"t": "pong"}
                elif t == "info":
                    res = {"t": "info", "name": engine.engine_name,
                           "threads": engine.threads, "hash": engine.hash_mb,
                           "movetime": engine.movetime_ms, "depth": engine.depth}
                else:
                    res = {"t": "error", "id": rid,
                           "message": f"unknown command: {t}"}
            except Exception as e:
                res = {"t": "error", "id": rid, "message": str(e)[:200]}
            try:
                respond(res)
            except OSError:
                break
    finally:
        engine.stop()
        f.close()
        try:
            conn.close()
        except OSError:
            pass
        log(f"phone disconnected: {addr[0]}:{addr[1]}")


def main():
    ap = argparse.ArgumentParser(description="ChessPilot-PC desktop engine")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=41718)
    ap.add_argument("--engine", default=None, help="stockfish binary path")
    ap.add_argument("--threads", type=int, default=16)
    ap.add_argument("--hash", type=int, default=2048, help="MB")
    ap.add_argument("--movetime", type=int, default=3000, help="ms per move")
    ap.add_argument("--depth", type=int, default=0,
                    help="fixed depth (0 = time-based)")
    ap.add_argument("--selftest", action="store_true",
                    help="startpos smoke test, then exit")
    a = ap.parse_args()

    binary = ensure_engine(a.engine)
    if a.selftest:
        engine = Engine(binary, a.threads, a.hash, a.movetime, a.depth)
        engine.start()
        startpos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        # Temporarily shorten the budget for the smoke test.
        engine.movetime_ms = min(engine.movetime_ms, 1000)
        res = engine.analyse(1, startpos)
        print(json.dumps(res, ensure_ascii=False))
        engine.stop()
        ok = isinstance(res, dict) and res.get("t") == "result"
        sys.exit(0 if ok else 1)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind((a.host, a.port))
    except OSError as e:
        sys.exit(f"bind {a.host}:{a.port} failed ({e}): port in use?")
    srv.listen(4)
    log(f"listening on {a.host}:{a.port} — enter this IP:port in the "
        f"ChessPilot app (backend settings). Ctrl+C to stop.")
    engines = set()
    engines_lock = threading.Lock()

    def serve(conn, addr):
        # Main analysis and pondering use separate phone connections. Sharing
        # a single process here allowed one connection to stop the other's job.
        engine = Engine(binary, a.threads, a.hash, a.movetime, a.depth)
        with engines_lock:
            engines.add(engine)
        try:
            engine.start()
            handle_conn(conn, addr, engine)
        except Exception as exc:
            log(f"connection failed: {exc}")
        finally:
            engine.stop()
            conn.close()
            with engines_lock:
                engines.discard(engine)
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=serve, args=(conn, addr),
                             daemon=True).start()
    except KeyboardInterrupt:
        log("shutting down...")
    finally:
        with engines_lock:
            for engine in engines:
                engine.stop()


def _ponder_sniffer(engine, stop_event):
    """Fold ponder-time engine output into the fast-path result.

    Polls gently (20 Hz) and only while a ponder job is open without a result
    yet; info lines accumulate score/depth, a bestmove completes the result.
    Everything else (readyok, stray chatter) is dropped — the next _sync()
    drains to a clean readyok anyway, so nothing stale survives.
    """
    while not stop_event.is_set():
        time.sleep(0.02)
        # Check state and consume under the same lock used by sync/cancel.
        # Drain a batch so info cannot queue up behind a 20-Hz single-line poll.
        with engine.lock:
            if stop_event.is_set():
                break
            if not engine.pondering or engine.syncing or engine.ponder_event.is_set():
                continue
            for _ in range(128):
                try:
                    line = engine.lines.get_nowait()
                except queue.Empty:
                    break
                if line is None:
                    engine.ponder_event.set()
                    break
                if line.startswith("Fen: "):
                    engine.ponder_fen = line[len("Fen: "):].strip()
                elif line.startswith("info depth "):
                    try:
                        engine._sniff_info(line)
                    except OSError:
                        engine.ponder_update = None
                elif line.startswith("bestmove"):
                    engine._sniff_bestmove(line)
                    if engine.ponder_event.is_set():
                        break
        # else: readyok/stray — drop (sync drains explicitly).


if __name__ == "__main__":
    main()
