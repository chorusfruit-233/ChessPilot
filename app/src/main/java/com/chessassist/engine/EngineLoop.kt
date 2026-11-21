package com.chessassist.engine

import android.content.Context
import android.util.Log
import com.chessassist.BridgeServer
import com.chessassist.BridgeSettings
import com.chessassist.OverlayManager
import io.github.a1175815821.chesspilot.GameEnd
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real-time loop:
 *
 *   hook publishes position ──► [onPosition] ──► dedupe ──► Stockfish analyse
 *        ▲                                                        │
 *        │                                                        ▼
 *   hook draws/autoplays ◄── BridgeServer pushes bestmove ──► draw + optional autoplay
 *
 * Transport is loopback TCP (BridgeServer/BridgeClient): Duolingo cannot resolve our
 * ContentProvider authority on Android 11+ (package visibility), so the provider is
 * diagnostics-only.
 *
 * Reliability rules implemented here:
 *  - Analysis is skipped when it's not our turn (FEN side to move != userColor) or the
 *    position didn't change — keeps CPU and battery at zero while idle.
 *  - A single analysis slot; a newer position cancels interest in the older one.
 *  - Engine crashes are contained: restart with backoff, request the hook to re-send.
 *  - Arrow + autoplay only ever target the FEN they were computed for (staleness guard).
 */
object EngineLoop {

    private const val TAG = "EngineLoop"
    private const val MAX_RESTARTS = 5
    private const val MAX_EVENTS = 30

    const val STATE_STOPPED = "stopped"
    const val STATE_STARTING = "starting"
    const val STATE_RUNNING = "running"
    const val STATE_FAILED = "failed"

    private val running = AtomicBoolean(false)
    @Volatile private var client: EngineClient? = null
    /**
     * Ponder's own engine process (P0 isolation): ponder search/ponderhit/stop
     * traffic NEVER touches [client]'s stdin/stdout. Lifecycle mirrors the
     * main client (lazy start on first ponder, dropped on stop/restart/kill).
     * Guarded by worker-thread confinement, like [client].
     */
    @Volatile private var ponderClient: EngineClient? = null
    private var appContext: Context? = null
    private var worker: Thread? = null

    /**
     * Lifecycle generation (B4): start()/stop() run on the main thread, but an old
     * worker stuck in blocking I/O (readLine is not interruptible) can outlive
     * stop(). Without this, a quick stop→start lets the stale worker observe
     * running==true again and continue as a DUPLICATE loop — second engine process,
     * duplicate bestmove pushes, battery drain. Every loop/checkpoint verifies it
     * still owns the current generation.
     */
    @Volatile private var workerGen = 0L

    // Latest position, guarded by worker-thread confinement (only worker reads/writes).
    @Volatile var currentFen: String? = null
        private set

    /** Engine lifecycle for the status screen: stopped|starting|running|failed. */
    @Volatile var engineState: String = STATE_STOPPED
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastBestMove: String? = null
        private set
    @Volatile var lastBestMoveAt: Long = 0
        private set
    @Volatile var lastScoreCp: Int? = null
        private set
    @Volatile var lastScoreStm: String? = null
        private set
    @Volatile var lastScoreUser: String? = null
        private set

    /** Final search depth of the last analysis (0 = unknown): engine-health readout. */
    @Volatile var lastDepth: Int = 0
        private set

    /** Last time the hook published a position (0 = never: LSPosed side not working). */
    @Volatile var lastPositionAt: Long = 0
        private set
    @Volatile var lastPositionFen: String? = null
        private set
    @Volatile private var hookAdapterStatus: String? = null

    /**
     * Loop heartbeat (updated every iteration + after every analysis). If the worker
     * ever wedges, the status screen shows a stale heartbeat instead of a
     * plausible-looking frozen state — that distinction is the whole diagnosis.
     */
    @Volatile var lastLoopAt: Long = 0
        private set

    /** Stale-position annotation throttle (so a stuck game diagnoses itself). */
    @Volatile private var lastStaleBannerAt: Long = 0
    @Volatile private var lastStaleNoteAt: Long = 0

    /** No new position for this long => banner/status say so explicitly. */
    private const val STALE_AFTER_MS = 45_000L

    private data class Position(
        val fen: String? = null, val user: String? = null, val turn: String? = null,
        val moves: List<String> = emptyList(), val gameId: String? = null, val end: GameEnd? = null,
    )

    @Volatile private var pending = Position()
    private val pendingLock = Any()
    private val retiredGames = LinkedHashSet<String>()
    private var analysingClient: EngineClient? = null

    private fun isPlayable(position: Position): Boolean {
        val latest = pending
        return latest.gameId == position.gameId && latest.fen == position.fen &&
            latest.user == position.user && latest.end == null
    }

    // ---------------------------------------------------------- ponder -----
    // Think on the opponent's time: while it's their turn, search our reply to
    // the engine's predicted move (BestMove.ponder from the last analysis). If
    // they play it, reuse a completed iteration without a new timed search;
    // anything else (or any failure) falls back to a normal search.
    //
    // ISOLATION (P0 fix for the wrong-move/freeze saga): ponder runs on a
    // DEDICATED engine process (ponderClient) with its own stdout reader. The
    // old design shared one BufferedReader between analyse() and ponder() with
    // no mutual exclusion — whichever thread woke first stole the line. A
    // ponder bestmove (computed for a position that never happened) landed in
    // analyse() and got auto-played as our move; conversely a stolen analyse
    // bestmove stalled the worker for movetime+grace per cycle (frozen banner).
    // With separate processes a stuck ponder can only waste one ponder — the
    // analysis path is unreachable to it by construction.
    //
    // All ponder reads still block, so the ponder search owns a second thread;
    // the worker only ever joins it with timeouts, and a thread that survives
    // the join gets its whole PROCESS killed (never left as a zombie reader).
    /** A hit finalizes already-searched work; it never waits out a new time budget. */
    private const val PONDERHIT_JOIN_MS = 1000L
    private const val PONDER_STOP_JOIN_MS = 1500L

    /** Engine's predicted opponent reply from the last analysis (null = none). */
    @Volatile private var lastPonderMove: String? = null
    @Volatile private var ponderSession: PonderSession? = null
    @Volatile private var lastPonderHit: String? = null
    @Volatile private var lastResultFromPonder = false
    /**
     * Last (fen|prediction) pushed to the hook's red arrow. Change-gated so the
     * opponent-turn branch (every 200 ms) doesn't spam the bridge; null clears.
     */
    @Volatile private var lastPushedPonderKey: String? = null
    /** Last failed ponder-engine start: cooldown so a missing binary doesn't respawn every 200 ms. */
    @Volatile private var lastPonderStartFailAt: Long = 0
    private const val PONDER_START_RETRY_MS = 30_000L

    private val eventsLock = Any()
    private val events = ArrayDeque<String>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        appContext = context.applicationContext
        synchronized(pendingLock) {
            val end = pending.end
            engineState = if (end == null) STATE_STARTING else STATE_RUNNING
            if (end != null) OverlayManager.showStatus(endTitle(end), endDetail(end))
        }
        lastError = null
        note("正在启动引擎…")
        val gen = ++workerGen
        worker = Thread({ runLoop(gen) }, "engine-loop").apply { start() }
    }

    fun stop() {
        running.set(false)
        // Bump FIRST: a stale worker must fail their next generation check even if
        // someone restarts the service before they finish unwinding.
        workerGen++
        note("正在停止…")
        ensurePonderStopped()
        lastPushedPonderKey = null
        try { ponderClient?.stop() } catch (_: Throwable) {}
        ponderClient = null
        try { client?.stop() } catch (_: Throwable) {}
        client = null
        try { worker?.interrupt() } catch (_: Throwable) {}
        worker = null
        engineState = STATE_STOPPED
    }

    /**
     * Ponder helpers. Everything here runs on the worker thread EXCEPT
     * ensurePonderStopped(), which stop() also calls from main (signal only,
     * no join — main must never block on a socket reader).
     */

    /** Abort any in-flight ponder (idempotent, fast). */
    private fun ensurePonderStopped() {
        val session = ponderSession
        ponderSession = null
        session?.cancel()
        stopPonderSearch(session)
    }

    /** Retire the reader separately from the cached reply's eventual consumption. */
    private fun stopPonderSearch(session: PonderSession?) {
        session?.stopSearch()
        val t = session?.thread
        if (t == null) return
        // FIRST: tell the reader loop to exit on its next line. WITHOUT this the
        // thread parks in readLine forever on its (now dead-end) reader.
        if (!t.isAlive) return
        // Signal the PONDER process only — the main client must never see a
        // ponder stop (cross-talk on a shared stream was the old
        // wrong-move vector; separate processes make it impossible).
        val engine = ponderClient
        try { engine?.stopPonder() } catch (_: Throwable) {}
        // stop finalizes the search, waking the reader to see its stop flag.
        if (Thread.currentThread() !== t) {
            try { t.join(PONDER_STOP_JOIN_MS) } catch (_: Throwable) {}
            if (t.isAlive) {
                // P0: NEVER leave a zombie reader behind (the old code did —
                // "leaving it (daemon)" — and the zombie then stole bestmove
                // lines from analyse(), the exact wrong-move/freeze mechanism).
                // Killing the ponder process unblocks its reader (EOF/throw)
                // AND guarantees no stale ponder output can ever surface.
                // Main analysis continues untouched.
                Log.w(TAG, "ponder thread parked after stop; killing ponder engine")
                note("极限思考线程卡住，已丢弃重建（对局不受影响）")
                try { engine?.stop() } catch (_: Throwable) {}
                if (ponderClient === engine) ponderClient = null
            }
        }
    }

    /**
     * Push the red prediction arrow (or retire it) on change only. The bridge
     * is latest-only, so a dropped push just means "same as displayed" — the
     * hook repaints from its stored pair on every render anyway.
     */
    private fun pushPonderGuessOrClear(position: Position, fen: String, pred: String?) = synchronized(pendingLock) {
        if (!isPlayable(position)) return@synchronized
        val key = pred?.let { "$fen|$it" }
        if (key == lastPushedPonderKey) return@synchronized
        lastPushedPonderKey = key
        if (pred != null) BridgeServer.pushPonderGuess(pred, fen, position.gameId)
        else BridgeServer.pushClearPonder(position.gameId)
    }

    /**
     * True when this fen's ponder guess already went out. The bridge is
     * latest-only: a clear pushed AFTER the ponder push wins the race and
     * eats the prediction (the "shows once at game start, then never" bug),
     * so the turn-flip clear must skip fens with a live guess — the
     * prediction draw resets arrows itself before painting.
     */
    private fun isPonderPushedFor(fen: String): Boolean =
        lastPushedPonderKey?.startsWith("$fen|") == true

    /**
     * Opponent's turn: start/keep pondering our reply to the predicted move.
     * Per-fen gated (a ponder job is one position); disabling the switch or a
     * missing prediction stops any running job. Never throws.
     */
    /**
     * @param gen the worker generation that called us; the ponder-engine
     *   handshake blocks for seconds, and stop() (or a newer worker) may run
     *   mid-handshake — creating the process without this check orphans it
     *   (nobody ever stops it again). Mirrors the main-client guard in runLoop.
     */
    private fun maybePonder(position: Position, fen: String, gen: Long) {
        val moves = position.moves
        try {
            if (!isPlayable(position)) return
            if (!BridgeSettings.ponderEnabled) {
                ensurePonderStopped()
                pushPonderGuessOrClear(position, fen, null)
                return
            }
            val r = lastPonderMove
            if (r == null) {
                ensurePonderStopped()
                pushPonderGuessOrClear(position, fen, null)
                return
            }
            // Push the guess FIRST, before the (possibly slow: 1-3 s first-time
            // engine handshake) ponder job spins up. The prediction is known
            // already; the red arrow must not wait for the search. Key-gated,
            // so this costs one message per (fen, prediction).
            pushPonderGuessOrClear(position, fen, r)
            // A finished result is still useful. Do not restart and erase it
            // every 200 ms while the opponent is deciding.
            val existing = ponderSession
            if (existing?.canKeep(fen, r) == true) {
                existing.updateSourceHistory(fen, moves)
                return
            }
            ensurePonderStopped()
            // P0 isolation: ponder gets its OWN engine (process or remote
            // connection). A ponder-engine failure only costs one ponder
            // round — the main client (and the analysis path) is never
            // touched here.
            var pc = ponderClient
            if (pc == null || !pc.isRunning()) {
                if (System.currentTimeMillis() - lastPonderStartFailAt < PONDER_START_RETRY_MS) return
                pc = createPonderClient(gen) ?: return
            }
            val c = pc
            val session = PonderSession(fen, r, moves)
            val movetime = if (c is RemoteEngineClient) BridgeSettings.remoteMovetimeMs
                else BridgeSettings.movetimeMs
            val depth = if (c is RemoteEngineClient) BridgeSettings.remoteDepth
                else BridgeSettings.searchDepth
            Log.i(TAG, "ponder start (opp to move, our reply to $r)")
            session.thread = Thread({
                try {
                    val res = c.ponder(fen, r, movetime, depth, session.stopFlag, session::update)
                    if (res != null) {
                        session.update(res)
                        Log.i(TAG, "ponder finished: ${res.uci} (depth ${res.depth})")
                    } else {
                        Log.i(TAG, "ponder thread exit without result (stopped/deadline/eof)")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "ponder thread died: ${t.message?.take(80)}")
                }
            }, "ponder").apply { isDaemon = true }
            val started = synchronized(pendingLock) {
                if (!running.get() || gen != workerGen || pending.gameId != position.gameId || pending.end != null) {
                    false
                } else {
                    // Our turn may have arrived during the handshake. Starting the
                    // prepared search still helps when the prediction just landed.
                    ponderClient = c
                    ponderSession = session
                    lastPonderHit = null
                    session.thread?.start()
                    true
                }
            }
            if (!started) c.stop()
        } catch (_: Throwable) {
        }
    }

    /**
     * Our turn: if the opponent played the predicted move and the ponder result
     * is ready, take it (no new search). Returns the result to publish, or null
     * to fall through to a normal analyse. A newer snapshot defers consumption
     * to the next tick and keeps the frozen cache for revalidation.
     *
     * Completed PVs are cached before bestmove; hits use them immediately.
     */
    private fun tryConsumePonder(
        position: Position, fen: String, moves: List<String>,
    ): BestMove? {
        try {
            val session = ponderSession ?: return null
            val t = session.thread ?: return null
            val r = session.prediction
            if (!BridgeSettings.ponderEnabled) {
                ensurePonderStopped()
                return null
            }
            if (!t.isAlive && session.result == null) {
                // Died with nothing (engine crash mid-ponder): nothing to take.
                ensurePonderStopped()
                return null
            }
            if (!session.matches(fen, moves)) {
                Log.i(TAG, "ponder miss (opp played ${moves.lastOrNull()}, expected $r)")
                ensurePonderStopped()
                return null
            }
            // Ponder-hit goes to the PONDER process only. The main client keeps
            // whatever state it has (usually idle) — a hit must never disturb
            // an analysis or force a main-engine restart.
            val startedAt = System.currentTimeMillis()
            var res = session.result
            val cc = ponderClient
            if (res == null && (cc == null || !cc.isRunning())) {
                // Ponder engine died/was dropped mid-ponder: nothing to hit, search fresh.
                ensurePonderStopped()
                return null
            }
            Log.i(TAG, "ponderhit! ($r)")
            if (res == null) {
                try { cc?.signalPonderHit() } catch (_: Throwable) {}
                try { t.join(PONDERHIT_JOIN_MS) } catch (_: Throwable) {}
                res = session.result
            }
            val hung = t.isAlive
            // Recheck against the engine's target FEN if it arrived during join.
            val matched = session.matches(fen, moves)
            if (res != null && matched) stopPonderSearch(session)
            // Check again AFTER stopping/joining: a correction can arrive there
            // too. The cache survives a stopped reader until explicitly consumed.
            if (!isPlayable(position)) {
                Log.i(TAG, "ponder handoff deferred to newer position")
                return null
            }
            ensurePonderStopped()
            if (res == null) {
                // Silence past budget+grace means the engine is genuinely
                // wedged — never trust it for the fallback search: drop it so
                // the loop recreates fresh.
                if (hung) {
                    // P0: only the PONDER engine dies here. The old code dropped
                    // the MAIN client — a ponder incident restarted analysis too.
                    Log.w(TAG, "ponderhit hung ponder engine; dropping it (main engine untouched)")
                    try { ponderClient?.stop() } catch (_: Throwable) {}
                    ponderClient = null
                } else {
                    Log.w(TAG, "ponderhit produced nothing, falling back to search")
                }
                return null
            }
            if (!matched) return null
            lastPonderHit = "命中 $r → ${res.uci} · 深度${res.depth} · 取用${System.currentTimeMillis() - startedAt}ms"
            return res
        } catch (_: Throwable) {
            return null
        }
    }

    // ------------------------------------------------- backend policy -----
    // Auto backend (remote preferred, local fallback): remote is tried first;
    // ANY failure sticks to local until the next game (remoteDownSticky) with
    // a 15 s retry floor (remoteCoolUntil) so a dead PC doesn't hammer
    // reconnects every loop iteration. A fresh game re-prefers remote.
    private const val REMOTE_RETRY_MS = 15_000L
    @Volatile private var remoteDownSticky: Boolean = false
    @Volatile private var remoteCoolUntil: Long = 0L

    /** Which engine actually served the last analysis (status + overlay livery). */
    @Volatile var lastBackendUsed: String = "本地"
        private set

    private fun useRemote(): Boolean =
        BridgeSettings.backend != BridgeSettings.BACKEND_LOCAL &&
            !remoteDownSticky &&
            System.currentTimeMillis() >= remoteCoolUntil

    /** Remote failed: stick to local, note once (per game) why. */
    private fun markRemoteDown(cause: String?) {
        remoteCoolUntil = System.currentTimeMillis() + REMOTE_RETRY_MS
        if (!remoteDownSticky) {
            remoteDownSticky = true
            lastBackendUsed = "本地"
            val why = cause?.take(100) ?: "连接中断"
            note("远程算力不可用，已切本地：$why（下局重连）")
            Log.w(TAG, "remote down, local fallback: $why")
        } else {
            lastBackendUsed = "本地"
        }
    }

    /**
     * Main engine factory (worker thread): remote first when wanted, local
     * fallback that never throws out of here for remote causes (a throwing
     * local start still propagates to the loop's restart machinery).
     */
    private fun createMainClient(): EngineClient {
        if (useRemote()) {
            try {
                val rc = RemoteEngineClient().also { it.start() }
                lastBackendUsed = "远程"
                note("远程算力已连接（${BridgeSettings.pcHost}:${BridgeSettings.pcPort}，" +
                    "${rc.loadedNnue ?: ""}）")
                return rc
            } catch (e: Throwable) {
                markRemoteDown(e.message)
            }
        }
        lastBackendUsed = "本地"
        return StockfishClient(appContext!!).also { it.start() }
    }

    /**
     * Ponder engine factory (worker thread): separate connection when remote
     * (reader discipline: one reader per socket, same as separate processes
     * locally), local dedicated process as fallback. Null = skip this round.
     */
    private fun createPonderClient(gen: Long): EngineClient? {
        if (useRemote()) {
            try {
                val rc = RemoteEngineClient().also { it.start() }
                if (!running.get() || gen != workerGen) {
                    try { rc.stop() } catch (_: Throwable) {}
                    return null
                }
                Log.i(TAG, "remote ponder up")
                return rc
            } catch (e: Throwable) {
                markRemoteDown(e.message)
                // fall through to the local ponder engine below
            }
        }
        return try {
            val lc = StockfishClient(appContext!!).also { it.start() }
            // Same orphan guard as the old inline code (v0.2 Bug 2).
            if (!running.get() || gen != workerGen) {
                try { lc.stop() } catch (_: Throwable) {}
                return null
            }
            Log.i(TAG, "ponder engine up (dedicated process)")
            lc
        } catch (e: Throwable) {
            lastPonderStartFailAt = System.currentTimeMillis()
            Log.w(TAG, "ponder engine start failed: ${e.message?.take(80)}")
            null
        }
    }

    /** One-line backend state for the status screen. */
    fun backendLine(): String {
        val want = BridgeSettings.backendShort()
        return if (want == "本地") "本地引擎"
        else "$want（当前$lastBackendUsed）"
    }

    /**
     * Test the PC connection (call on a background thread): connects, runs
     * the info+ping handshake, reports engine name + RTT. Never throws.
     */
    fun testRemote(): String {
        if (BridgeSettings.pcHost.trim().isEmpty()) return "先填电脑IP（高级设置→算力后端）"
        return try {
            val rc = RemoteEngineClient().also { it.start() }
            val msg = "远程可用：${rc.loadedNnue ?: "?"} · " +
                "${rc.lastThreads}线 ${rc.lastHashMb}MB · RTT ${rc.lastRttMs}ms"
            try { rc.stop() } catch (_: Throwable) {}
            note("测试远程连接：$msg")
            msg
        } catch (e: Throwable) {
            val msg = "远程不通：${e.message?.take(120)}"
            note("测试远程连接：$msg")
            msg
        }
    }

    /**
     * Apply changed engine settings (e.g. berserk toggle) live: drop the client
     * and let the same-generation loop recreate it on the next iteration —
     * no duplicate worker/engine (B4), 1~2 s gap, game untouched. No-op while
     * stopped (settings take effect on the next start instead).
     */
    fun restartEngine(reason: String) {
        if (!running.get()) return
        note("引擎设置变更（$reason），重启引擎…")
        // Settings changed: lift the remote quarantine too — a fresh attempt
        // is cheap and this is exactly when the user changed something.
        remoteDownSticky = false
        remoteCoolUntil = 0L
        // Ponder process FIRST: stop() destroys it, which unblocks a parked
        // ponder reader instantly — the join inside ensurePonderStopped() then
        // returns fast even though this runs on the main thread (settings UI).
        // Also clears any pre-restart ponder result so it can never "hit" on
        // the fresh engine (stale-hit wrong-move vector, now closed).
        try { ponderClient?.stop() } catch (_: Throwable) {}
        ponderClient = null
        ensurePonderStopped()
        try { client?.stop() } catch (_: Throwable) {}
        client = null
    }

    /** Last known colors (for the stale banner: whose turn are we waiting on?). */
    @Volatile private var lastUserColor: String? = null
    @Volatile private var lastTurnColor: String? = null

    /**
     * Settle filter: hooks (and Duolingo itself during animations/retries) can emit
     * a burst of micro-different states within milliseconds. Analysing each burns
     * 700 ms+ and the resulting command/tap chains cross-fire into a board that has
     * already moved on — the wedge users see as a frozen game. A fen must survive
     * this long before it reaches analysis.
     */
    private const val SETTLE_MS = 250L
    @Volatile private var settleFen: String? = null
    @Volatile private var settleSince: Long = 0

    /** A terminal snapshot applies to its entire game, including later render updates. */
    fun onPosition(
        fen: String?, userColor: String?, turnColor: String?, moves: List<String>,
        gameId: String? = null, end: GameEnd? = null,
    ) = receivePosition(Position(fen, userColor, turnColor, moves, gameId, end))

    private fun receivePosition(position: Position, fromEngine: Boolean = false) {
        val enginesToStop = synchronized(pendingLock) {
            val previous = pending
            val gameId = position.gameId
            val newGame = gameId != previous.gameId
            if (fromEngine && !isPlayable(position)) return
            if ((gameId == null && previous.gameId != null) || gameId in retiredGames) return
            if (newGame) {
                previous.gameId?.let(retiredGames::add)
                if (retiredGames.size > 64) retiredGames.remove(retiredGames.first())
            }

            // Legacy senders have no identity. Their only explicit game boundary
            // is a return to startpos. Current modules always send a game ID.
            val legacyStart = gameId == null && position.fen == STARTPOS_FEN && previous.fen != STARTPOS_FEN
            val priorEnd = if (newGame || legacyStart) null else previous.end
            val end = position.end?.let { priorEnd?.followedBy(it) ?: it } ?: priorEnd
            val fen = position.fen ?: previous.fen.takeUnless { newGame }
            val user = chessColor(position.user) ?: previous.user.takeUnless { newGame || legacyStart }
            val turn = chessColor(fen?.split(' ')?.getOrNull(1)) ?: chessColor(position.turn)
            val colorChanged = previous.fen != null && !newGame && !legacyStart && previous.user != user
            pending = position.copy(fen = fen, user = user, turn = turn, end = end)
            if (position.fen != null) lastPositionAt = System.currentTimeMillis()
            else if (newGame) lastPositionAt = 0
            lastPositionFen = fen
            lastUserColor = user
            lastTurnColor = turn

            val endedNow = end != null && priorEnd == null
            val cancelled = if (newGame || endedNow || colorChanged) {
                clearRecommendation()
                currentFen = fen
                ponderSession?.cancel()
                ponderSession = null
                BridgeServer.discardQueuedMoves()
                if (colorChanged) BridgeServer.pushClear(gameId)
                val active = analysingClient
                analysingClient = null
                if (client === active) client = null
                listOfNotNull(active, ponderClient).also { ponderClient = null }
            } else emptyList()

            if (end != null) {
                currentFen = fen
                if (running.get()) engineState = STATE_RUNNING
                if (end != priorEnd) {
                    val title = endTitle(end)
                    val detail = endDetail(end)
                    note(listOf(title, detail).filter(String::isNotEmpty).joinToString(" · "))
                    OverlayManager.showStatus(title, detail)
                    if (fromEngine && fen != null) BridgeServer.pushGameEnd(fen, gameId, end)
                    BridgeServer.pushClear(gameId)
                }
            } else if (newGame && previous.end != null) {
                OverlayManager.showStatus("ChessPilot", "等待新对局…")
            }
            cancelled
        }
        // Socket/process teardown can block. Keep snapshot and command gates live.
        for (engine in enginesToStop) {
            try { engine.stop() } catch (e: Exception) { Log.w(TAG, "game end cleanup: $e") }
        }
    }

    private fun chessColor(color: String?) = color?.lowercase()?.takeIf { it == "w" || it == "b" }

    private fun clearRecommendation() {
        analysedFen = null
        lastPushedMove = null
        lastPushedFen = null
        lastBestMove = null
        lastBestMoveAt = 0
        lastScoreCp = null
        lastScoreStm = null
        lastScoreUser = null
        lastDepth = 0
        lastPonderMove = null
        lastPonderHit = null
        lastPushedPonderKey = null
        lastResultFromPonder = false
        lastError = null
    }

    private fun endTitle(end: GameEnd) = if (end.reason == "LEFT_BOARD") "已离开对局" else "对局结束"

    private fun endDetail(end: GameEnd): String {
        val reason = when (end.reason) {
            "CHECKMATE" -> "将死"
            "TIMEOUT" -> "超时"
            "TIMEOUT_VS_INSUFFICIENT_MATERIAL" -> "超时且对方子力不足"
            "RESIGNATION" -> "认输"
            "ABANDONED" -> "弃局"
            "DISCONNECTION" -> "断线"
            "AGREEMENT" -> "协议和棋"
            "STALEMATE" -> "逼和"
            "REPETITION" -> "局面重复"
            "INSUFFICIENT_MATERIAL" -> "子力不足"
            "FIFTY_MOVES" -> "五十回合规则"
            "SEVENTY_FIVE_MOVES" -> "七十五回合规则"
            "NO_LEGAL_MOVES" -> "无合法着法"
            "COMPLETED", "LEFT_BOARD" -> ""
            else -> end.reason
        }
        val outcome = when (end.outcome) {
            "WIN" -> "胜利"
            "LOSS" -> "失败"
            "DRAW" -> "和棋"
            "RESIGN" -> "认输"
            "WHITE" -> "白方胜"
            "BLACK" -> "黑方胜"
            else -> ""
        }
        return listOf(outcome, reason).filter(String::isNotEmpty).distinct().joinToString(" · ")
    }

    fun onArrowAck(uci: String) {
        Log.d(TAG, "hook confirmed arrow drawn: $uci")
        note("对局端已画出箭头：$uci")
    }

    /** Called by [BridgeServer] when the hook (re)connects over loopback TCP. */
    fun onHookConnected() = synchronized(pendingLock) {
        note("对局端已连接（socket 在线）")
        // Hook process restarted (or first connect): its paint/confirm state is
        // gone while our dedupe keys persist. Force-resend so current arrows
        // repaint instead of staying blank until the next move. REPAINT ONLY:
        // autoplay is forced OFF here — a stale auto-tap into a restarted
        // Duolingo (menus, not the board) would tap blind. The next real
        // analysis re-arms autoplay normally.
        try {
            lastPushedPonderKey = null
            val m = lastPushedMove
            val f = lastPushedFen
            if (pending.end == null && m != null && f != null && f == analysedFen && f == pending.fen) {
                BridgeServer.pushBestmove(m, f, false, BridgeSettings.autoPlayDelayMs, lastResultFromPonder, pending.gameId)
            }
        } catch (_: Throwable) {
        }
    }

    /** Called by [BridgeServer] on fatal bridge errors (e.g. port occupied). */
    fun onBridgeError(msg: String) {
        lastError = msg
        note(msg)
    }

    fun onHookAdapterStatus(status: String) {
        if (status.isBlank() || hookAdapterStatus == status) return
        hookAdapterStatus = status
        note(status)
    }

    private fun hookConnectionLine(): String {
        if (BridgeServer.hookConnected()) {
            return listOfNotNull("已连接（socket 在线）", hookAdapterStatus).joinToString("\n")
        }
        return if (lastPositionAt > 0) "上次收到局面：${ago(lastPositionAt)}"
        else "未连接（LSPosed 模块未上报局面）"
    }

    /**
     * The hook gave up auto-playing and asks the user to finish manually.
     * Shown DIRECTLY on the floating overlay (title = the plea itself) — there
     * is no separate help-banner state: help only ever fires on the user's turn
     * with a static board, where neither clear() (opponent's turn only) nor the
     * stale annotator (silent on the user's turn) can overwrite it. The next
     * fresh analysis replaces it naturally. Event log kept for diagnostics.
     */
    fun onNeedHelp(code: String, uci: String) = synchronized(pendingLock) {
        if (pending.end != null || lastPushedMove != uci || lastPushedFen != pending.fen) return@synchronized
        if (code == "promotion") {
            val title = when (uci.lastOrNull()) {
                'q' -> "请手动升变为后"
                'r' -> "请手动升变为车"
                'b' -> "请手动升变为象"
                'n' -> "请手动升变为马"
                else -> "请手动升变"
            }
            note("$title · $uci")
            OverlayManager.showStatus(title, uci)
        } else {
            note("请手动走棋：$uci")
            OverlayManager.showStatus("请手动走棋", uci)
        }
    }

    /** Recent lifecycle events for the in-app status screen (newest last). */
    fun recentEvents(): List<String> = synchronized(eventsLock) { events.toList() }

    private fun note(msg: String) {
        Log.i(TAG, msg)
        // SimpleDateFormat is not thread-safe: note() is called from the engine
        // worker, bridge and main threads — serialize the format call.
        val line = synchronized(timeFmt) { "${timeFmt.format(Date())} $msg" }
        synchronized(eventsLock) {
            events.addLast(line)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    /**
     * One-line ponder state for the status screen. Ponder used to be a black box
     * here, which is exactly why the wrong-move/freeze saga took so long to
     * localize — never again: any future ponder incident shows its face here.
     */
    fun ponderLine(): String {
        if (!BridgeSettings.ponderEnabled) return "关"
        if (pending.end != null) return "本局已停止"
        val session = ponderSession
        val best = session?.result
        val hit = lastPonderHit
        return when {
            session != null && best != null -> "预测 ${session.prediction} → 应招 ${best.uci} · 深度${best.depth}" +
                if (session.thread?.isAlive == true) "（已备好，继续深算）" else "（已备好）"
            session?.thread?.isAlive == true -> "预测 ${session.prediction} · 预计算应招中…"
            hit != null -> hit
            ponderClient?.isRunning() == true -> "待机（引擎在线）"
            else -> "开·引擎未启动"
        }
    }

    /** Engine resource line for the status screen: mode + actual threads/hash + depth. */
    private fun engineResLine(c: EngineClient?): String {
        val res = if (c != null && c.lastThreads > 0) {
            "${c.lastThreads}线 · ${c.lastHashMb}MB"
        } else "—"
        val depth = if (lastDepth > 0) " · 深度$lastDepth" else ""
        // Remote resources come from the PC: label them remote instead of mixing
        // the local berserk switch with PC threads/hash on one line.
        val mode = if (c is RemoteEngineClient) "远程" else BridgeSettings.modeShort()
        val cpu = (c as? StockfishClient)?.cpuVariant?.let {
            " · " + if (it == "armv8-dotprod") "NEON DotProd" else "ARMv8 NEON"
        } ?: ""
        return "$mode · $res$depth$cpu"
    }

    /**
     * Publish one analysed bestmove: shared by the normal search and the
     * ponder-hit fast path (which skips searching entirely). Also stores the
     * engine's predicted reply ([BestMove.ponder]) for the next ponder round.
     * Caller owns generation checks around this (see loop).
     */
    private fun publishBest(position: Position, fen: String, stm: String?, best: BestMove, ponderHit: Boolean) {
        val user = position.user
        if (best.uci == "(none)") {
            val outcome = if (best.isMate && user != null && user.equals(stm, ignoreCase = true)) "LOSS" else null
            val end = GameEnd(if (best.isMate) "CHECKMATE" else "NO_LEGAL_MOVES", outcome)
            receivePosition(position.copy(end = end), fromEngine = true)
            return
        }
        synchronized(pendingLock) {
            if (!isPlayable(position)) return
            val autoplay = BridgeSettings.autoPlayEnabled && user != null && user == stm
            analysedFen = fen
            lastPushedMove = best.uci
            lastPushedFen = fen
            lastBestMove = best.uci
            lastBestMoveAt = System.currentTimeMillis()
            lastScoreCp = best.scoreCp
            lastScoreStm = stm
            lastScoreUser = user
            lastDepth = best.depth
            lastPonderMove = best.ponder
            lastResultFromPonder = ponderHit
            lastError = null
            val wr = ChessMath.formatWinRate(best.scoreCp, stm, user)
            val ponderNote = if (best.ponder != null) "，ponder ${best.ponder}" else ""
            note("最佳招法 ${best.uci}（深度${best.depth}，cp=${best.scoreCp}，$wr$ponderNote）")
            if (ponderHit) note("极限思考${lastPonderHit.orEmpty()}，复用预计算结果")
            BridgeServer.pushBestmove(
                best.uci, fen, autoplay, BridgeSettings.autoPlayDelayMs, ponderHit, position.gameId,
            )
            OverlayManager.showBestMove(
                best.uci, fen, best.scoreCp, autoplay, user,
                best.depth, ponderHit = ponderHit,
            )
        }
    }

    /** Second banner line while waiting: keep the last conclusion visible. */
    private fun lastConclusionLine(): String {
        val m = lastBestMove ?: return ""
        return "$m · ${ChessMath.formatWinRate(lastScoreCp, lastScoreStm, lastScoreUser)}"
    }

    private fun ago(ts: Long): String {
        if (ts <= 0) return "从未"
        val s = (System.currentTimeMillis() - ts) / 1000
        return if (s < 60) "$s 秒前" else "${s / 60} 分钟前"
    }

    /** Minutes since the last published position, or -1 when fresh/never. */
    fun staleMinutes(): Long {
        if (pending.end != null) return -1
        if (lastPositionAt <= 0) return -1
        val idle = System.currentTimeMillis() - lastPositionAt
        return if (idle > STALE_AFTER_MS) idle / 60_000 else -1
    }

    /**
     * Called from the idle branch: if no NEW position arrived for a while while a
     * game was active, say so on the banner/event instead of showing a frozen
     * "waiting" line that looks identical to healthy waiting. Throttled.
     */
    private fun maybeNoteStale() = synchronized(pendingLock) {
        if (pending.end != null || lastPositionAt <= 0 || !running.get()) return@synchronized
        val idle = System.currentTimeMillis() - lastPositionAt
        if (idle <= STALE_AFTER_MS) return@synchronized
        val now = System.currentTimeMillis()
        if (now - lastStaleBannerAt > 30_000) {
            lastStaleBannerAt = now
            // No nagging: when it's the user's turn we stay silent on the banner
            // (the last analysis stays visible) — demanding taps was rightly called
            // out. Only annotate opponent-side/unknown stalls, which otherwise look
            // identical to a healthy wait.
            val waitingOurs = lastTurnColor != null && lastTurnColor == lastUserColor
            if (!waitingOurs) {
                val m = idle / 60_000
                OverlayManager.showStatus(
                    "局面停滞", "${m}分无新局面" + lastConclusionLine().let {
                        if (it.isEmpty()) "" else "（上步$it）"
                    }
                )
            }
        }
        if (now - lastStaleNoteAt > 300_000) {
            lastStaleNoteAt = now
            note("局面停滞：${idle / 60_000} 分钟无新局面（对局结束/切后台/钩子上报停止其一）")
        }
    }

    /** Structured status for the control screen (single source of truth). */
    data class StatusSnapshot(
        val engineLabel: String,
        val engineColor: Int,
        val hookLine: String,
        val nnue: String,
        val engineResLine: String,
        val ponderLine: String,
        val backendLine: String,
        val fen: String,
        val userColor: String?,
        val bestMove: String?,
        val bestAgo: String,
        val winText: String,
        val winRate01: Double?,
        val settingsLine: String,
        val autoLine: String,
        val heartbeatAgo: String,
        val staleMinOrNull: Long?,
        val oursTurnWaiting: Boolean,
        val error: String?,
        val gameStatus: String?,
        val gameResult: String,
    )

    fun snapshot(): StatusSnapshot {
        val c = client
        val end = pending.end
        val hookState = hookConnectionLine()
        val engineLabel = when (engineState) {
            STATE_RUNNING -> "运行中"
            STATE_STARTING -> "启动中…"
            STATE_FAILED -> "启动失败"
            else -> "已停止"
        }
        val engineColor = when (engineState) {
            STATE_RUNNING -> ChessMath.C_WIN
            STATE_STARTING -> ChessMath.C_MID
            STATE_FAILED -> ChessMath.C_LOSE
            else -> ChessMath.C_DIM
        }
        return StatusSnapshot(
            engineLabel = engineLabel,
            engineColor = engineColor,
            hookLine = hookState,
            nnue = c?.loadedNnue ?: "-",
            engineResLine = engineResLine(c),
            ponderLine = ponderLine(),
            backendLine = backendLine(),
            fen = currentFen ?: "-",
            userColor = pending.user,
            bestMove = lastBestMove.takeIf { end == null },
            bestAgo = if (lastBestMove != null) ago(lastBestMoveAt) else "",
            winText = if (lastBestMove != null) {
                ChessMath.formatWinRate(lastScoreCp, lastScoreStm, lastScoreUser)
            } else "",
            winRate01 = if (lastBestMove != null) {
                ChessMath.userWinRate01(lastScoreCp, lastScoreStm, lastScoreUser)
            } else null,
            settingsLine = BridgeSettings.describe(),
            autoLine = if (end != null && BridgeSettings.autoPlayEnabled) "本局已停止"
                else if (BridgeSettings.autoPlayEnabled) {
                "开（${BridgeSettings.autoPlayDelayMs}ms）"
            } else "关",
            heartbeatAgo = ago(lastLoopAt),
            staleMinOrNull = staleMinutes().let { if (it >= 0) it else null },
            oursTurnWaiting = (staleMinutes() >= 0) &&
                lastTurnColor != null && lastTurnColor == lastUserColor,
            error = lastError,
            gameStatus = end?.let(::endTitle),
            gameResult = end?.let(::endDetail).orEmpty(),
        )
    }

    /** Last (move, fen) pair pushed to the hook — the only pair play may replay. */
    @Volatile private var lastPushedMove: String? = null
    @Volatile private var lastPushedFen: String? = null

    /** Mirror of the loop-local lastAnalysedFen for cross-thread freshness checks. */
    @Volatile private var analysedFen: String? = null
        private set

    /**
     * Semi-auto entry: play the latest pushed bestmove on user tap (banner button).
     * Same freshness rules as autoplay — never plays a stale move.
     */
    fun playPendingBestmove() = synchronized(pendingLock) {
        val m = lastPushedMove
        val f = lastPushedFen
        if (pending.end != null || pending.user == null || pending.user != pending.turn ||
            m == null || f == null || f != analysedFen || f != pending.fen) {
            note("点走棋：当前无可走招法")
            return@synchronized
        }
        BridgeServer.pushPlay(m, f, BridgeSettings.autoPlayDelayMs, pending.gameId)
        note("手动走棋已发送：$m")
    }

    fun statusText(): String {
        val c = client
        val hookState = hookConnectionLine()
        val engineLabel = when (engineState) {
            STATE_RUNNING -> "运行中"
            STATE_STARTING -> "启动中…"
            STATE_FAILED -> "启动失败"
            else -> "已停止"
        }
        return buildString {
            appendLine("引擎状态：$engineLabel")
            appendLine("Hook 连接：$hookState")
            appendLine("NNUE：${c?.loadedNnue ?: "-"}")
            appendLine("引擎配置：${engineResLine(c)}")
            appendLine("算力后端：${backendLine()}")
            appendLine("极限思考：${ponderLine()}")
            appendLine("当前 FEN：${currentFen ?: "-"}")
            pending.end?.let { appendLine(listOf(endTitle(it), endDetail(it)).filter(String::isNotEmpty).joinToString(" · ")) }
            appendLine("最佳招法：${lastBestMove ?: "-"}${if (lastBestMove != null) "（${ago(lastBestMoveAt)}${if (lastDepth > 0) " · 深度$lastDepth" else ""}）" else ""}")
            if (lastBestMove != null) {
                appendLine("${ChessMath.formatWinRate(lastScoreCp, lastScoreStm, lastScoreUser)}")
            }
            appendLine("思考设置：${BridgeSettings.describe()}")
            appendLine(
                "自动走棋：${if (BridgeSettings.autoPlayEnabled) "开（${BridgeSettings.autoPlayDelayMs}ms）" else "关"}"
            )
            appendLine("循环心跳：${ago(lastLoopAt)}（停住不动=工作线程卡死）")
            val stale = staleMinutes()
            if (stale >= 0) appendLine("局面停滞：${stale}分无新局面")
            if (stale >= 0 && lastTurnColor != null && lastTurnColor == lastUserColor) {
                appendLine("轮到你走棋")
            }
            if (lastError != null) appendLine("最近错误：$lastError")
        }
    }

    // ------------------------------------------------------------------ loop

    /** Exact initial position: consecutive lessons often restart here. */
    private const val STARTPOS_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private fun runLoop(gen: Long) {
        var restarts = 0
        var lastAnalysedFen: String? = null
        // A color correction can change whether this same FEN belongs to us.
        var lastAnalysedUser: String? = null
        var prevFen: String? = null
        var previousGameId: String? = null
        var lastSkippedFen: String? = null
        var lastWaitingDetail: String? = null
        fun isStale(): Boolean = gen != workerGen
        while (running.get() && !isStale()) {
            lastLoopAt = System.currentTimeMillis()
            val position = pending
            try {
                if (position.end != null) {
                    Thread.sleep(60)
                    continue
                }
                // Local snapshot: stop() (main thread) nulls `client` and destroys
                // the process concurrently — `client!!` here raced that and threw
                // a misleading NPE that flashed STATE_FAILED on every normal stop.
                // isRunning() also covers the watchdog kill (stopped but non-null):
                // without it the loop would spin analyse()->null forever.
                var c = client
                if (c == null || !c.isRunning()) {
                    if (isStale()) return
                    if (c != null) {
                        try { c.stop() } catch (_: Throwable) {}
                        client = null
                    }
                    val starting = synchronized(pendingLock) {
                        if (pending.end != null || pending.gameId != position.gameId) false
                        else { engineState = STATE_STARTING; true }
                    }
                    if (!starting) continue
                    c = createMainClient()
                    // Lost the race with a newer generation while starting (handshake
                    // takes seconds): shut our engine down, don't clobber its client.
                    val installed = synchronized(pendingLock) {
                        if (isStale() || pending.end != null || pending.gameId != position.gameId) false
                        else {
                            client = c
                            engineState = STATE_RUNNING
                            note("引擎已启动，等待对局局面…")
                            OverlayManager.showStatus("ChessPilot", "等待对局…")
                            true
                        }
                    }
                    if (!installed) {
                        try { c.stop() } catch (_: Throwable) {}
                        continue
                    }
                    restarts = 0
                }
                val (fen, user, turn, moves, gameId) = position
                if (fen == null) {
                    maybeNoteStale()
                    Thread.sleep(60)
                    continue
                }
                // Board identity handles rematches with an identical FEN, including
                // mini matches that start from a custom position.
                val legacyStart = gameId == null && fen == STARTPOS_FEN && prevFen != STARTPOS_FEN
                if (gameId != previousGameId || legacyStart) {
                    lastAnalysedFen = null
                    lastAnalysedUser = null
                    lastSkippedFen = null
                    lastWaitingDetail = null
                    settleFen = null
                    if (legacyStart) {
                        synchronized(pendingLock) { if (isPlayable(position)) clearRecommendation() }
                        ensurePonderStopped()
                    }
                    // New game lifts the remote quarantine: if the previous
                    // game fell back to local, retry remote now. Drop local
                    // clients so the loop recreates through the policy.
                    remoteDownSticky = false
                    remoteCoolUntil = 0L
                    if (BridgeSettings.backend != BridgeSettings.BACKEND_LOCAL) {
                            if (client is StockfishClient) {
                            try { client?.stop() } catch (_: Throwable) {}
                            client = null
                        }
                        if (ponderClient is StockfishClient) {
                            try { ponderClient?.stop() } catch (_: Throwable) {}
                            ponderClient = null
                        }
                    }
                    note("新开局，已清空上局残留")
                }
                previousGameId = gameId
                prevFen = fen
                if (!isPlayable(position)) continue
                if (fen == lastAnalysedFen && user == lastAnalysedUser) {
                    maybeNoteStale()
                    Thread.sleep(60)
                    continue
                }
                // Let micro-flaps settle: a fen that reverts within SETTLE_MS never
                // reaches analysis (nor the tap chains downstream of it).
                val now = System.currentTimeMillis()
                if (fen != settleFen) {
                    settleFen = fen
                    settleSince = now
                    Thread.sleep(60)
                    continue
                }
                if (now - settleSince < SETTLE_MS) {
                    Thread.sleep(60)
                    continue
                }
                currentFen = fen

                val stm = turn
                // Only analyse when it's the user's move (their decision is what we assist).
                // Deliberately NOT marking lastAnalysedFen here: a skip decided on
                // lagging turn/user fields must be re-evaluated when corrected values
                // arrive (same fen). Marking it poisoned the position forever.
                if (user != null && stm != user) {
                    // Opponent's clock is free thinking time when ponder is on.
                    // Start the job BEFORE painting so the overlay suffix below
                    // reflects the live job, not last iteration's state.
                    maybePonder(position, fen, gen)
                    if (!isPlayable(position)) continue
                    if (fen != lastSkippedFen) {
                        lastSkippedFen = fen
                        // Turn flipped to the opponent: retire OUR yellow arrow
                        // from the board — BUT only when no ponder guess went
                        // out for this fen (see isPonderPushedFor): the bridge
                        // is latest-only and a clear would eat the prediction.
                        // Otherwise the lingering yellow reads as a "yellow
                        // prediction" through fast opponent turns. Text
                        // conclusion survives in the overlay either way.
                        if (!isPonderPushedFor(fen)) BridgeServer.pushClear(gameId)
                        lastWaitingDetail = null
                    }
                    // Update on new completed iterations too, not only a new FEN.
                    val conclusion = lastConclusionLine()
                    val guess = if (BridgeSettings.ponderEnabled) "极限思考：${ponderLine()}" else ""
                    val detail = listOf(conclusion, guess).filter(String::isNotEmpty).joinToString("\n")
                    if (detail != lastWaitingDetail) {
                        lastWaitingDetail = detail
                        synchronized(pendingLock) {
                            if (isPlayable(position)) OverlayManager.clear("等待对手走棋…", detail)
                        }
                    }
                    // Reachable even when positions keep arriving: a perpetual
                    // opponent-turn (bot stuck, lesson fetching) must still age
                    // into the stale annotation instead of looking like waiting.
                    maybeNoteStale()
                    Thread.sleep(200)
                    continue
                }

                // Ponder hit? The opponent played our predicted reply and the
                // answer is already searched — publish it instead of searching.
                // Anything unconsumed (miss/stale/failure) stops the job so the
                // normal search below owns the engine alone.
                // A prepared reply bypasses the normal timed search below.
                lastWaitingDetail = null
                val pondered = tryConsumePonder(position, fen, moves)
                if (pondered != null) {
                    lastLoopAt = System.currentTimeMillis()
                    if (isStale()) return
                    if (!isPlayable(position)) continue
                    lastAnalysedFen = fen
                    lastAnalysedUser = user
                    publishBest(position, fen, stm, pondered, ponderHit = true)
                    continue
                }
                // tryConsume may have taken seconds (ponderhit join) and may even
                // have dropped a hung engine: re-read everything — a newer
                // our-turn position must not be skipped for this stale snapshot,
                // and a dropped client must recreate instead of being analysed on.
                if (!isPlayable(position)) continue
                c = client
                if (c == null || !c.isRunning()) {
                    // Remote died mid-game (timeout streak / broken socket):
                    // quarantine it so the recreate above falls back to local
                    // instead of hammering reconnects every iteration.
                    if (c is RemoteEngineClient) markRemoteDown("连接中断")
                    try { c?.stop() } catch (_: Throwable) {}
                    client = null
                    continue
                }

                // Remote engine obeys the remote sliders (sent per request,
                // server honors them); local obeys the local ones.
                val mt = if (c is RemoteEngineClient) BridgeSettings.remoteMovetimeMs
                    else BridgeSettings.movetimeMs
                val dp = if (c is RemoteEngineClient) BridgeSettings.remoteDepth
                    else BridgeSettings.searchDepth
                val engine = c
                val started = synchronized(pendingLock) {
                    if (isStale() || !isPlayable(position)) false
                    else {
                        analysingClient = engine
                        OverlayManager.showThinking(BridgeSettings.autoPlayEnabled && user != null)
                        true
                    }
                }
                if (!started) continue
                val best = try {
                    if (!isPlayable(position) || isStale()) continue
                    engine.analyse(fen, mt, dp)
                } finally {
                    synchronized(pendingLock) {
                        if (analysingClient === engine) analysingClient = null
                    }
                }
                lastLoopAt = System.currentTimeMillis()
                // Stale generation waking from a blocking analyse: never publish —
                // the current worker already owns this position (or a newer one).
                if (isStale()) return
                if (!isPlayable(position)) continue
                if (best != null) {
                    lastAnalysedFen = fen
                    lastAnalysedUser = user
                    // Tripwire for stream cross-talk (the ponder saga class of bug):
                    // a bestmove from a search murdered at depth<=1 with a real
                    // time budget means something else talked to the engine mid-
                    // search (a racing stop/info leak). Loud in events, so the
                    // next incident localizes itself instead of showing up as a
                    // "mystery depth-1 + flipped winrate" banner.
                    // Terminal (none) reports depth 0 by construction — exempt, it
                    // has its own game-over path in publishBest, not this alarm.
                    // Fixed-depth searches are exempt too (depth 1 is legitimate
                    // when the user asked for it); only time-mode with a real
                    // budget trips. Budget is the EFFECTIVE one (remote sliders
                    // drive the PC engine, not the local slider).
                    if (best.uci != "(none)" && best.depth <= 1 && dp == 0 && mt >= 500) {
                        Log.w(TAG, "suspicious shallow result: depth=${best.depth} " +
                            "for ${mt}ms budget (uci=${best.uci})")
                        note("分析深度异常：${mt}ms 只搜到深度${best.depth}（${best.uci}），结果仍采用")
                    }
                    publishBest(position, fen, stm, best, ponderHit = false)
                } else {
                    // Transient failure (timeout/crash): do NOT mark as analysed or the
                    // position would never be retried until the next move. Back off
                    // briefly; the next tick reads the latest position.
                    Log.w(TAG, "analyse returned null for ${fen.take(30)}…, will retry")
                    Thread.sleep(500)
                }
            } catch (t: Throwable) {
                // Any failure after stop() is a shutdown race (stop() interrupted
                // our sleep or destroyed the engine process under us): exit
                // quietly instead of flashing ERROR + STATE_FAILED on every stop.
                // Same for a superseded generation after a quick stop→start.
                if (!running.get() || isStale()) {
                    Log.i(TAG, "loop stopped (${t.javaClass.simpleName})")
                    return
                }
                if (pending.end != null || pending.gameId != position.gameId) continue
                lastError = t.toString().take(200)
                engineState = STATE_FAILED
                Log.e(TAG, "loop error: $t")
                try { client?.stop() } catch (_: Throwable) {}
                client = null
                if (++restarts > MAX_RESTARTS) {
                    lastError = "引擎反复启动失败，已退避 30 秒（NNUE 缺失？）"
                    Log.e(TAG, "engine keeps failing; backing off 30 s")
                    try {
                        Thread.sleep(30_000)
                    } catch (ie: InterruptedException) {
                        if (!running.get() || isStale()) return
                    }
                    restarts = 0
                } else {
                    try {
                        Thread.sleep(1_000L * restarts)
                    } catch (ie: InterruptedException) {
                        if (!running.get() || isStale()) return
                    }
                }
                if (running.get()) engineState = STATE_STARTING
            }
        }
    }
}
