package com.chessassist.engine

import com.chessassist.BridgeServer
import com.chessassist.BridgeSettings
import io.github.a1175815821.chesspilot.BridgeClient
import io.github.a1175815821.chesspilot.BridgePublisher
import io.github.a1175815821.chesspilot.GameEnd
import io.github.a1175815821.chesspilot.StateBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Execute the real worker and inspect the actual command sent to the hook. */
class PonderAutoplayTest {
    private val source = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    private val target = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
    private val history = listOf("e2e4")
    private val main = FakeEngine()
    private val ponder = FakeEngine()
    private var worker: Thread? = null

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun set(owner: Any, name: String, value: Any?) = field(owner, name).set(owner, value)
    @Suppress("UNCHECKED_CAST")
    private fun <T> get(owner: Any, name: String): T = field(owner, name).get(owner) as T

    @Before fun setup() {
        EngineLoop.stop()
        BridgeSettings.autoPlayEnabled = true
        BridgeSettings.ponderEnabled = true
        BridgeSettings.backend = BridgeSettings.BACKEND_LOCAL
        set(EngineLoop, "client", main)
        set(EngineLoop, "ponderClient", ponder)
        set(EngineLoop, "settleFen", null)
        set(EngineLoop, "lastPonderHit", null)
        set(EngineLoop, "lastResultFromPonder", false)
        set(EngineLoop, "lastPonderMove", "e7e5")
        val initialPosition = get<Any>(EngineLoop, "pending").javaClass.getDeclaredConstructor()
            .apply { isAccessible = true }.newInstance()
        set(EngineLoop, "pending", initialPosition)
        set(EngineLoop, "analysingClient", null)
        val session = PonderSession(source, "e7e5", history)
        session.thread = Thread {} // Completed ponder still owns a prepared PV.
        session.update(BestMove("g1f3", "b8c6", 25, 18, positionFen = target))
        set(EngineLoop, "ponderSession", session)
        synchronized(get<Any>(BridgeServer, "sendLock")) {
            get<ArrayDeque<JSONObject>>(BridgeServer, "pendingQueue").clear()
        }
        BridgePublisher.resetPosition()
    }

    @After fun cleanup() {
        EngineLoop.stop()
        worker?.join(2000)
        assertFalse("Engine worker leaked", worker?.isAlive == true)
        BridgeSettings.autoPlayEnabled = false
        BridgeSettings.ponderEnabled = false
    }

    private fun startWorker() {
        get<AtomicBoolean>(EngineLoop, "running").set(true)
        val generation = get<Long>(EngineLoop, "workerGen")
        val loop = EngineLoop.javaClass.getDeclaredMethod("runLoop", java.lang.Long.TYPE).apply { isAccessible = true }
        worker = Thread { loop.invoke(EngineLoop, generation) }
        set(EngineLoop, "worker", worker)
        worker!!.start()
    }

    private fun waitForCommand(): JSONObject {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            val command = synchronized(get<Any>(BridgeServer, "sendLock")) {
                get<ArrayDeque<JSONObject>>(BridgeServer, "pendingQueue")
                    .firstOrNull { it.optString("t") == "bestmove" }
            }
            if (command != null) return command
            Thread.sleep(10)
        }
        fail("No autoplay command; events=${EngineLoop.recentEvents()}")
        error("unreachable")
    }

    private fun commands(): List<JSONObject> = synchronized(get<Any>(BridgeServer, "sendLock")) {
        get<ArrayDeque<JSONObject>>(BridgeServer, "pendingQueue").toList()
    }

    private fun deliver(snapshot: JSONObject) {
        val reader = BridgeServer.javaClass.getDeclaredMethod("handleLine", String::class.java)
            .apply { isAccessible = true }
        reader.invoke(BridgeServer, snapshot.toString())
    }

    @Test fun assignedBlackWaitsForWhiteDespiteTheOpeningModelsOrientation() {
        val board = StateBridge()
        val controller = Any()
        val setup = NativeSetup(target, emptyList(), true)
        board.onPlayerColor(controller, NativeColor.BLACK)
        board.onBoardSetup(
            controller, model = setup,
            liveState = NativeState(NativeFen(target), setup, NativeColor.WHITE, NativeColor.WHITE, emptyList()),
        )
        assertEquals("b", board.lastUserColor)
        assertNull(board.tapOrientation(board.gameId, target))
        deliver(get(BridgeClient, "lastSnapshot"))
        main.analysisResult = BestMove("e7e5", null, 20, 16)
        startWorker()
        Thread.sleep(550)
        assertEquals("b", EngineLoop.snapshot().userColor)
        assertEquals(0, main.analyses.get())
        assertTrue(commands().none { it.optString("t") == "bestmove" })

        val saved = HashMap<String, Any>()
        board.saveForReload(saved)
        val restored = StateBridge()
        restored.restoreAfterReload(saved, controller)
        restored.onGameState(
            NativeState(NativeFen(source), setup, NativeColor.WHITE, NativeColor.BLACK, listOf(NativeMove("e2e4"))),
        )
        assertEquals("b", restored.lastUserColor)
        assertEquals("w", restored.tapOrientation(restored.gameId, source))
        deliver(get(BridgeClient, "lastSnapshot"))
        val command = waitForCommand()
        assertEquals(source, command.getString("fen"))
        assertEquals("e7e5", command.getString("move"))
        assertEquals("1", command.getString("autoplay"))
        assertEquals("b", EngineLoop.lastScoreUser)
    }

    @Test fun colorCorrectionCancelsAWhiteSearchEvenWhenTheFenDoesNotChange() {
        val board = StateBridge()
        val controller = Any()
        val setup = NativeSetup(target, emptyList(), true)
        board.onBoardSetup(controller, model = setup)
        assertEquals("w", board.tapOrientation(board.gameId, target))
        deliver(get(BridgeClient, "lastSnapshot"))
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val returned = CountDownLatch(1)
        main.onAnalyse = {
            entered.countDown()
            check(cancelled.await(3, TimeUnit.SECONDS))
            returned.countDown()
        }
        main.onStopEngine = { cancelled.countDown() }
        startWorker()
        try {
            assertTrue("White analysis never started", entered.await(3, TimeUnit.SECONDS))
            board.onPlayerColor(controller, NativeColor.BLACK)
            assertNull(board.tapOrientation(board.gameId, target))
            deliver(get(BridgeClient, "lastSnapshot"))
            assertTrue("Correction did not stop the old search", returned.await(2, TimeUnit.SECONDS))
            assertEquals("b", EngineLoop.snapshot().userColor)
            EngineLoop.playPendingBestmove()
            assertTrue(commands().none { it.optString("t") in listOf("bestmove", "play") })
        } finally {
            cancelled.countDown()
        }
    }

    @Test fun aFenWithoutTheAssignedColorCannotTriggerEitherPlayMode() {
        val board = StateBridge()
        board.onBoardSetup(Any())
        board.acceptExternalFen(target)
        assertNull(board.tapOrientation(board.gameId, target))
        deliver(get(BridgeClient, "lastSnapshot"))
        startWorker()
        assertEquals("0", waitForCommand().getString("autoplay"))
        EngineLoop.playPendingBestmove()
        assertNull(EngineLoop.snapshot().userColor)
        assertTrue(commands().none { it.optString("t") == "play" })
    }

    @Test fun openingSurvivesNativeSetupAndReachesTheEngineOverTheSocket() {
        val board = StateBridge()
        val model = NativeSetup(null, listOf(NativeMove("e2e4"), NativeMove("e7e5")), true)
        val controller = Any()
        // Duolingo constructs the live state inside setup, before the after hook.
        board.onGameState(NativeState(NativeFen(target), model, NativeColor.WHITE, NativeColor.WHITE, model.b))
        val provisionalId = board.gameId
        assertEquals(target, board.lastFen)
        board.onBoardSetup(controller, model = model) // The live getter did not return a state.
        assertNotEquals(provisionalId, board.gameId)
        assertTrue(board.canPlay(board.gameId, target))
        val opening = get<JSONObject>(BridgeClient, "lastSnapshot")
        assertEquals(board.gameId, opening.getString("game"))
        assertEquals(target, opening.getString("fen"))
        assertEquals("e2e4 e7e5", opening.getString("moves"))

        BridgeServer.start()
        try {
            BridgeClient.sendAdapterStatus("多邻国 6.106.3 · 棋盘接口已匹配")
            BridgeClient.ensureStarted()
            startWorker()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (main.analyses.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(BridgeServer.hookConnected())
            assertTrue(EngineLoop.snapshot().hookLine.contains("多邻国 6.106.3"))
            assertEquals(target, EngineLoop.snapshot().fen)
            assertEquals(1, main.analyses.get())
        } finally {
            val disconnected = BridgeClient.stopForReload()
            BridgeServer.stop()
            assertTrue("Hook bridge client did not stop", disconnected)
        }
    }

    @Test fun renamed1063GettersPublishFenAndHistory() {
        val board = StateBridge(fenGetter = "c", moveGetter = "d")
        val model = NativeSetup(null, emptyList(), true)
        val state = NativeState(
            NativeFen1063(target), model, NativeColor.WHITE, NativeColor.WHITE,
            listOf(NativeMove1063("e2e4"), NativeMove1063("e7e5")),
        )
        board.onBoardSetup(Any(), model = model, liveState = state)
        val position = get<JSONObject>(BridgeClient, "lastSnapshot")
        assertEquals(target, position.getString("fen"))
        assertEquals("e2e4 e7e5", position.getString("moves"))
        deliver(position)
        assertEquals(target, EngineLoop.snapshot().fen)
    }

    @Test fun openingWithoutFenDoesNotMarkAPositionAsReceived() {
        EngineLoop.onPosition(source, "w", "b", history, "has-position")
        assertTrue(EngineLoop.lastPositionAt > 0)
        deliver(JSONObject().put("t", "position").put("game", "opening").put("fen", ""))
        assertEquals(0L, EngineLoop.lastPositionAt)
        assertNull(EngineLoop.currentFen)
        assertNull(EngineLoop.snapshot().staleMinOrNull)
    }

    @Test fun setupModelSuppliesAnOpeningWhenNoStateCallbackRuns() {
        val board = StateBridge()
        val controller = Any()
        val model = NativeSetup(source, listOf(NativeMove("e2e4")), false)
        board.onBoardSetup(controller, model = model)
        val opening = get<JSONObject>(BridgeClient, "lastSnapshot")
        assertEquals(source, opening.getString("fen"))
        assertEquals("b", opening.getString("turn"))
        assertEquals("w", opening.getString("user"))
        assertEquals("e2e4", opening.getString("moves"))
        board.onGameState(NativeState(NativeFen(source), model, NativeColor.WHITE, NativeColor.BLACK, model.b))
        board.acceptExternalFen(target)
        board.onBoardSetup(controller, model = model)
        assertEquals(target, board.lastFen)

        val newController = Any()
        board.onBoardSetup(newController, model = NativeSetup(null, emptyList(), true))
        val startpos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertEquals(startpos, board.lastFen)
        assertEquals("w", board.lastUserColor)
        assertFalse(board.onBoardSetup(controller, model = NativeSetup(source, emptyList(), true)))
        assertEquals(startpos, board.lastFen)
    }

    @Test fun timeoutWithoutFenChangeCancelsAnalysisAndDiscardsItsLateReply() {
        val board = StateBridge()
        val controller = Any()
        board.onBoardSetup(controller)
        board.acceptExternalFen(target)
        assertTrue(board.canPlay(board.gameId, target))
        deliver(get(BridgeClient, "lastSnapshot"))
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val returned = CountDownLatch(1)
        main.onAnalyse = {
            entered.countDown()
            check(cancelled.await(3, TimeUnit.SECONDS))
            returned.countDown()
        }
        main.onStopEngine = { cancelled.countDown() }
        startWorker()
        try {
            assertTrue("Analysis never began", entered.await(3, TimeUnit.SECONDS))
            board.onGameEnd(controller, GameEnd("TIMEOUT", "WIN"))
            assertFalse(board.canPlay(board.gameId, target))
            val ended = get<JSONObject>(BridgeClient, "lastSnapshot")
            assertEquals(target, ended.getString("fen"))
            assertEquals("TIMEOUT", ended.getString("end"))
            deliver(ended)
            assertTrue("Terminal message did not stop the active search", returned.await(2, TimeUnit.SECONDS))

            // An unchanged live frame and an attempted manual replay cannot
            // clear the sticky result or revive the discarded recommendation.
            EngineLoop.onPosition(target, "w", "w", history + "e7e5", board.gameId)
            val after = EngineLoop.lastLoopAt
            val deadline = System.nanoTime() + 1_000_000_000L
            while (EngineLoop.lastLoopAt <= after && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Worker did not tick after cancellation", EngineLoop.lastLoopAt > after)
            EngineLoop.playPendingBestmove()
            assertEquals("胜利 · 超时", EngineLoop.snapshot().gameResult)
            assertEquals("本局已停止", EngineLoop.snapshot().autoLine)
            assertTrue(commands().none { it.optString("t") in listOf("bestmove", "play", "ponder") })
            assertEquals(1, main.analyses.get())
        } finally {
            cancelled.countDown()
        }
    }

    @Test fun terminalSnapshotClearsQueuedAutoplayAndAnIdenticalFenInTheNextGameCanPlay() {
        EngineLoop.onPosition(target, "w", "w", emptyList(), "first-game")
        startWorker()
        assertEquals("first-game", waitForCommand().getString("game"))
        EngineLoop.onPosition(target, "w", "w", emptyList(), "first-game", GameEnd("RESIGNATION", "WIN"))
        assertTrue(commands().none { it.optString("t") == "bestmove" })
        assertNull(EngineLoop.snapshot().bestMove)

        EngineLoop.onPosition(target, "w", "w", emptyList(), "second-game")
        EngineLoop.onPosition(target, "w", "w", emptyList(), "first-game", GameEnd("CHECKMATE", "LOSS"))
        val command = waitForCommand()
        assertEquals("second-game", command.getString("game"))
        assertEquals("1", command.getString("autoplay"))
        assertEquals(2, main.analyses.get())
        assertNull(EngineLoop.snapshot().gameStatus)
    }

    @Test fun endingDuringPonderClearsItsCacheAndFutureGuesses() {
        EngineLoop.onPosition(source, "w", "b", history, "ponder-game")
        ponder.start()
        set(EngineLoop, "ponderClient", ponder)
        set(EngineLoop, "lastPonderMove", "e7e5")
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        ponder.onPonder = { flag, update ->
            update(BestMove("g1f3", null, 20, 18, positionFen = target))
            entered.countDown()
            val limit = System.nanoTime() + 3_000_000_000L
            while (!flag.get() && System.nanoTime() < limit) Thread.sleep(5)
            if (flag.get()) finished.countDown()
        }
        startWorker()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        EngineLoop.onPosition(source, "w", "b", history, "ponder-game", GameEnd("AGREEMENT", "DRAW"))
        assertTrue("Ponder flag was not cancelled", finished.await(2, TimeUnit.SECONDS))
        assertEquals("和棋 · 协议和棋", EngineLoop.snapshot().gameResult)
        assertEquals("本局已停止", EngineLoop.ponderLine())
        assertNull(get<PonderSession?>(EngineLoop, "ponderSession"))
        assertTrue(commands().none { it.optString("t") == "ponder" })
    }

    @Test fun gameOverSurvivesResyncAndReloadButANewMiniMatchCanPlay() {
        val controller = Any()
        val original = StateBridge()
        original.onBoardSetup(controller, "mini-one")
        original.acceptExternalFen(target)
        val oldGameId = original.gameId
        assertTrue(original.canPlay(oldGameId, target))
        original.onGameEnd(Any(), GameEnd("TIMEOUT"))
        assertTrue(original.canPlay(oldGameId, target))
        original.onGameEnd(controller, GameEnd("REPETITION", "DRAW"))
        original.onGameEnd(controller, GameEnd("LEFT_BOARD"))
        original.onBoardSetup(controller) // Same-controller resync.
        original.acceptExternalFen(source)
        assertFalse(original.canPlay(oldGameId, target))
        val saved = HashMap<String, Any>()
        original.saveForReload(saved)

        val reloaded = StateBridge()
        reloaded.restoreAfterReload(saved, controller)
        val reconnect = get<JSONObject>(BridgeClient, "lastSnapshot")
        assertEquals(oldGameId, reconnect.getString("game"))
        assertEquals("REPETITION", reconnect.getString("end"))
        assertEquals("DRAW", reconnect.getString("outcome"))
        assertFalse(reloaded.canPlay(oldGameId, target))
        val model = NativeSetup(null, listOf(NativeMove("e2e4"), NativeMove("e7e5")), true)
        reloaded.onGameState(NativeState(NativeFen(target), model, NativeColor.WHITE, NativeColor.WHITE, model.b))
        reloaded.onBoardSetup(controller, model = model) // Initial setup is still the ended controller.
        assertFalse(reloaded.canPlay(oldGameId, target))
        reloaded.onBoardSetup(controller, "mini-two")
        reloaded.onEngineGameEnd(oldGameId, target, GameEnd("NO_LEGAL_MOVES"))
        assertNotEquals(oldGameId, reloaded.gameId)
        assertFalse(reloaded.canPlay(oldGameId, target))
        assertTrue(reloaded.canPlay(reloaded.gameId, target))
    }

    @Test fun engineTerminalPositionSendsTheEndToTheHookForTheSameGame() {
        main.analysisResult = BestMove("(none)", null, 0, isMate = true)
        EngineLoop.onPosition(target, "w", "w", emptyList(), "engine-terminal")
        startWorker()
        val deadline = System.nanoTime() + 2_000_000_000L
        var end = commands().firstOrNull { it.optString("t") == "game_end" }
        while (end == null && System.nanoTime() < deadline) {
            Thread.sleep(10)
            end = commands().firstOrNull { it.optString("t") == "game_end" }
        }
        assertNotNull(end)
        assertEquals("CHECKMATE", end!!.getString("end"))
        assertEquals("engine-terminal", end.getString("game"))
        assertEquals(target, end.getString("fen"))
        assertTrue(commands().none { it.optString("t") == "bestmove" })
    }

    private fun assertPreparedAutoplay(command: JSONObject, fen: String = target) {
        assertEquals("g1f3", command.getString("move"))
        assertEquals(fen, command.getString("fen"))
        assertEquals("1", command.getString("autoplay"))
        assertEquals("ponder", command.getString("source"))
        assertEquals("A prepared hit started a fresh main search", 0, main.analyses.get())
        assertTrue(get<Boolean>(EngineLoop, "lastResultFromPonder"))
    }

    @Test fun correctedHistoryReachesTheWorkerAndUsesThePreparedAutoplay() {
        // The FEN constructor fires before ChessGameState owns the new history.
        BridgePublisher.publishSnapshot(target, "w", "w", history)
        BridgePublisher.publishSnapshot(target, "w", "w", history + "e7e5")
        val message = get<JSONObject>(BridgeClient, "lastSnapshot")
        assertEquals("e2e4 e7e5", message.getString("moves"))
        EngineLoop.onPosition(message.getString("fen"), "w", "w", message.getString("moves").split(' '))
        startWorker()
        assertPreparedAutoplay(waitForCommand())
    }

    @Test fun exactTargetWithHistoryStillOnePlyBehindUsesPreparedAutoplay() {
        EngineLoop.onPosition(target, "w", "w", history)
        startWorker()
        assertPreparedAutoplay(waitForCommand())
    }

    @Test fun staleTurnMetadataCannotReplaceThePreparedSession() {
        EngineLoop.onPosition(target, "w", "b", history + "e7e5")
        assertEquals("w", get<String?>(EngineLoop, "lastTurnColor"))
        startWorker()
        Thread.sleep(500) // Correction arrives after the settle interval.
        EngineLoop.onPosition(target, "w", "w", history + "e7e5")
        assertPreparedAutoplay(waitForCommand())
        assertEquals("Ponder was restarted on our own turn", 0, ponder.ponders.get())
    }

    @Test fun aRealPredictionMissUsesMainSearchAndNeverPlaysTheCachedReply() {
        val missed = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
        EngineLoop.onPosition(missed, "w", "w", history + "c7c5")
        startWorker()
        val command = waitForCommand()
        assertEquals("b1c3", command.getString("move"))
        assertEquals(missed, command.getString("fen"))
        assertEquals("search", command.getString("source"))
        assertEquals(1, main.analyses.get())
        assertFalse(get<Boolean>(EngineLoop, "lastResultFromPonder"))
    }

    @Test fun completingTheSourceHistoryKeepsTheExistingPreparedSession() {
        val session = PonderSession(source, "e7e5", emptyList())
        session.thread = Thread {}
        session.update(BestMove("g1f3", "b8c6", 25, 18, positionFen = target))
        set(EngineLoop, "ponderSession", session)
        EngineLoop.onPosition(source, "w", "b", emptyList())
        startWorker()
        EngineLoop.onPosition(source, "w", "b", history)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!session.matches(target, history + "e7e5") && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Source history correction was ignored", session.matches(target, history + "e7e5"))
        EngineLoop.onPosition(target, "w", "w", history + "e7e5")
        assertPreparedAutoplay(waitForCommand())
        assertEquals(0, ponder.ponders.get())
    }

    @Test fun correctedFenDuringFinalizationKeepsThePreparedReplyForTheNextTick() {
        val release = CountDownLatch(1)
        val session = PonderSession(source, "e7e5", history)
        val waiting = Thread { check(release.await(3, TimeUnit.SECONDS)) }.apply { start() }
        session.thread = waiting
        set(EngineLoop, "ponderSession", session)
        val corrected = target.replace(" - 0 2", " e6 0 2")
        ponder.onHit = {
            session.update(BestMove("g1f3", "b8c6", 25, 18, positionFen = target))
            EngineLoop.onPosition(corrected, "w", "w", history + "e7e5")
            release.countDown()
        }
        try {
            EngineLoop.onPosition(target, "w", "w", history + "e7e5")
            startWorker()
            assertPreparedAutoplay(waitForCommand(), corrected)
        } finally {
            release.countDown()
            waiting.join(2000)
        }
    }

    @Test fun duplicateFramesStayDeduplicatedButHistoryMutationPublishesACorrection() {
        val mutableHistory = history.toMutableList()
        BridgePublisher.publishSnapshot(target, "w", "w", mutableHistory)
        val initial = get<JSONObject>(BridgeClient, "lastSnapshot")
        BridgePublisher.publishSnapshot(target, "w", "w", mutableHistory)
        assertSame(initial, get<JSONObject>(BridgeClient, "lastSnapshot"))
        mutableHistory.add("e7e5")
        BridgePublisher.publishSnapshot(target, "w", "w", mutableHistory)
        val corrected = get<JSONObject>(BridgeClient, "lastSnapshot")
        assertNotSame(initial, corrected)
        assertEquals("e2e4 e7e5", corrected.getString("moves"))
    }

    @Test fun correctionWhileStoppingAPreparedReaderDoesNotPublishTheOldFenOrResearch() {
        val release = CountDownLatch(1)
        val session = get<PonderSession>(EngineLoop, "ponderSession")
        val reader = Thread { check(release.await(5, TimeUnit.SECONDS)) }.apply { start() }
        session.thread = reader
        val corrected = target.replace(" - 0 2", " e6 0 2")
        ponder.onStop = {
            EngineLoop.onPosition(corrected, "w", "w", history + "e7e5")
            release.countDown()
        }
        try {
            EngineLoop.onPosition(target, "w", "w", history + "e7e5")
            startWorker()
            assertPreparedAutoplay(waitForCommand(), corrected)
        } finally {
            release.countDown()
            reader.join(2000)
        }
    }

    // Layout read from the mapped Duolingo classes by StateBridge.
    private class NativeSetup(val a: String?, val b: List<NativeMove>, val c: Boolean)
    private class NativeFen(private val fen: String) { fun b() = fen }
    private class NativeMove(private val uci: String) { fun b() = uci }
    private class NativeFen1063(private val fen: String) { fun c() = fen }
    private class NativeMove1063(private val uci: String) { fun d() = uci }
    private enum class NativeColor(private val symbol: String) {
        WHITE("W"), BLACK("B");
        fun getSymbol() = symbol
    }
    private class NativeState(
        val a: Any, val c: NativeSetup, val d: NativeColor, val e: NativeColor, val f: List<Any>,
    )

    private class FakeEngine : EngineClient {
        val analyses = AtomicInteger()
        val ponders = AtomicInteger()
        var onHit: (() -> Unit)? = null
        var onStop: (() -> Unit)? = null
        var onAnalyse: (() -> Unit)? = null
        var onStopEngine: (() -> Unit)? = null
        var onPonder: ((AtomicBoolean, (BestMove) -> Unit) -> Unit)? = null
        var analysisResult = BestMove("b1c3", null, 10, 16) // Differs from cached g1f3.
        private var live = true
        override fun isRunning() = live
        override fun start() { live = true }
        override fun stop() { live = false; onStopEngine?.invoke() }
        override fun analyse(fen: String, movetimeMs: Long, depth: Int): BestMove {
            analyses.incrementAndGet()
            onAnalyse?.invoke()
            return analysisResult
        }
        override fun ponder(fen: String, ponderMove: String, movetimeMs: Long, depth: Int,
                            stopFlag: AtomicBoolean, onUpdate: (BestMove) -> Unit): BestMove? {
            ponders.incrementAndGet()
            onPonder?.invoke(stopFlag, onUpdate)
            return null
        }
        override fun signalPonderHit() { onHit?.invoke() }
        override fun stopPonder() { onStop?.invoke() }
        override val loadedNnue = "fake"
        override val lastThreads = 1
        override val lastHashMb = 16
        override val lastBerserk = false
    }
}
