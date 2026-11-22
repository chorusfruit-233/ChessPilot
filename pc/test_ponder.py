"""Real UCI/remote regressions: STOCKFISH_BIN=/path/to/stockfish python -m unittest discover -s pc -v."""
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading
import time
import unittest

from pc_engine import Engine, handle_conn


SOURCE = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
TARGET = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
BINARY = os.environ.get("STOCKFISH_BIN")


@unittest.skipUnless(BINARY, "set STOCKFISH_BIN to run real-engine tests")
class PonderIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.engine = Engine(BINARY, 1, 16, 5000, 0)
        self.engine.start()
        self.commands = []
        send = self.engine._send

        def trace(command):
            self.commands.append(command)
            send(command)

        self.engine._send = trace

    def tearDown(self):
        self.engine.stop()

    def prepare(self, rid=7, prediction="e7e5", depth=0):
        updates = []
        ready = threading.Event()

        def update(info):
            updates.append(info)
            if info["depth"] >= 6:
                ready.set()

        self.assertIsNone(self.engine.ponder(rid, SOURCE, prediction, 5000, depth, update))
        self.assertTrue(ready.wait(10), "no prepared reply before the opponent moved")
        return updates

    def test_reply_is_available_before_hit_and_hit_skips_the_time_budget(self):
        updates = self.prepare()
        self.assertEqual(TARGET, updates[-1]["fen"])
        self.assertRegex(updates[-1]["uci"], r"^[a-h][1-8][a-h][1-8][qrbn]?$")
        self.assertEqual(7, updates[-1]["id"])
        before = len(self.commands)
        started = time.monotonic()
        result = self.engine.ponderhit(8)
        elapsed = time.monotonic() - started
        self.assertEqual("result", result["t"])
        self.assertEqual(7, result["id"])
        self.assertEqual(TARGET, result["fen"])
        self.assertLess(elapsed, 1.5, f"hit spent {elapsed:.2f}s instead of using prepared work")
        self.assertEqual(["stop"], self.commands[before:])
        print(f"prepared depth {updates[-1]['depth']}; hit reused depth {result['depth']} in {elapsed*1000:.0f} ms")

    def test_cancel_then_new_prediction_does_not_reuse_the_old_position(self):
        self.prepare()
        # Repeated immediate switches expose readyok arriving before the old
        # search's bestmove, rather than relying on one favourable schedule.
        for round_no in range(10):
            self.engine.cancel()
            rid = 17 + round_no
            prediction = "c7c5" if round_no % 2 == 0 else "e7e5"
            updates = self.prepare(rid, prediction)
            self.assertTrue(all(info["id"] == rid for info in updates))
            self.assertEqual(prediction == "e7e5", updates[-1]["fen"] == TARGET)
        result = self.engine.ponderhit(99)
        self.assertEqual(rid, result["id"])
        self.assertEqual(updates[-1]["fen"], result["fen"])

    def test_fixed_depth_prepared_reply_also_finishes_on_hit(self):
        updates = self.prepare(depth=6)
        self.assertEqual(6, updates[-1]["depth"])
        result = self.engine.ponderhit(8)
        self.assertEqual("result", result["t"])
        self.assertEqual(6, result["depth"])

    def test_bounded_time_finishes_while_waiting_even_with_a_server_depth_default(self):
        self.engine.depth = 40
        self.assertIsNone(self.engine.ponder(7, SOURCE, "e7e5", 250, 0, bounded=True))
        self.assertIn("go movetime 250", self.commands)
        self.assertTrue(self.engine.ponder_event.wait(3), "Search did not stop at its time limit")
        result = self.engine.ponderhit(8)
        self.assertEqual(7, result["id"])
        self.assertEqual(TARGET, result["fen"])

    def test_bounded_depth_finishes_at_the_requested_depth_before_hit(self):
        self.assertIsNone(self.engine.ponder(7, SOURCE, "e7e5", 250, 6, bounded=True))
        self.assertIn("go depth 6", self.commands)
        self.assertTrue(self.engine.ponder_event.wait(3), "Search did not stop at fixed depth")
        self.assertEqual(6, self.engine.ponderhit(8)["depth"])

    def test_engine_restart_keeps_progress_reader_alive(self):
        self.prepare()
        self.engine.restart()
        self.prepare(27)
        self.assertEqual(27, self.engine.ponderhit(28)["id"])

    def test_a_closed_phone_connection_retires_its_active_analysis(self):
        server, phone = socket.socketpair()
        worker = threading.Thread(target=handle_conn, args=(server, ("test", 0), self.engine), daemon=True)
        worker.start()
        try:
            request = {"t": "analyse", "id": 42, "fen": SOURCE, "depth": 40}
            phone.sendall((json.dumps(request) + "\n").encode())
            deadline = time.monotonic() + 5
            while "go depth 40" not in self.commands and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertIn("go depth 40", self.commands)
            phone.close()
            worker.join(3)
            self.assertFalse(worker.is_alive(), "search kept running after the phone disconnected")
        finally:
            phone.close()
            if worker.is_alive():
                server.close()


@unittest.skipUnless(BINARY, "set STOCKFISH_BIN to run real-engine tests")
class RemoteIsolationTests(unittest.TestCase):
    def test_analysis_on_another_connection_cannot_stop_pondering(self):
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            port = reserved.getsockname()[1]
        server = subprocess.Popen([
            sys.executable, str(Path(__file__).with_name("pc_engine.py")),
            "--host", "127.0.0.1", "--port", str(port), "--engine", BINARY,
            "--threads", "1", "--hash", "16", "--movetime", "5000",
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        clients = []
        try:
            for _ in range(2):
                deadline = time.monotonic() + 10
                while True:
                    try:
                        conn = socket.create_connection(("127.0.0.1", port), timeout=2)
                        break
                    except ConnectionRefusedError:
                        if time.monotonic() >= deadline:
                            raise
                        time.sleep(0.05)
                conn.settimeout(10)
                stream = conn.makefile("r", encoding="utf-8")
                clients.append((conn, stream))

            def send(index, obj):
                clients[index][0].sendall((json.dumps(obj) + "\n").encode())

            def receive(index, kind):
                while True:
                    line = clients[index][1].readline()
                    self.assertTrue(line, "server disconnected")
                    obj = json.loads(line)
                    self.assertNotEqual("error", obj.get("t"), obj)
                    if obj.get("t") == kind:
                        return obj

            send(1, {"t": "ponder", "id": 71, "fen": SOURCE, "ponderMove": "e7e5", "movetimeMs": 5000})
            preview = receive(1, "ponder_info")
            self.assertEqual(TARGET, preview["fen"])
            send(0, {"t": "analyse", "id": 81, "fen": SOURCE, "depth": 6})
            self.assertEqual(81, receive(0, "result")["id"])
            send(1, {"t": "ponderhit", "id": 72})
            hit = receive(1, "result")
            self.assertEqual(71, hit["id"])
            self.assertEqual(TARGET, hit["fen"])
        finally:
            for conn, stream in clients:
                conn.shutdown(socket.SHUT_RDWR)
                stream.close()
                conn.close()
            server.send_signal(signal.SIGINT)
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()


if __name__ == "__main__":
    unittest.main()
