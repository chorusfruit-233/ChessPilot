package com.chessassist.engine

import com.chessassist.BridgeServer
import com.chessassist.BridgeSettings
import io.github.a1175815821.chesspilot.BridgeClient
import io.github.a1175815821.chesspilot.BridgePublisher
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
        val session = PonderSession(source, "e7e5", history)
        session.thread = Thread {} // Completed ponder still owns a prepared PV.
        session.update(BestMove("g1f3", "b8c6", 25, 18, positionFen = target))
        set(EngineLoop, "ponderSession", session)
        synchronized(get<Any>(BridgeServer, "sendLock")) {
            get<ArrayDeque<JSONObject>>(BridgeServer, "pendingQueue").clear()
        }
        BridgePublisher.resetForNewGame()
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

    private class FakeEngine : EngineClient {
        val analyses = AtomicInteger()
        val ponders = AtomicInteger()
        var onHit: (() -> Unit)? = null
        var onStop: (() -> Unit)? = null
        private var live = true
        override fun isRunning() = live
        override fun start() { live = true }
        override fun stop() { live = false }
        override fun analyse(fen: String, movetimeMs: Long, depth: Int): BestMove {
            analyses.incrementAndGet()
            return BestMove("b1c3", null, 10, 16) // Deliberately differs from cached g1f3.
        }
        override fun ponder(fen: String, ponderMove: String, movetimeMs: Long, depth: Int,
                            stopFlag: AtomicBoolean, onUpdate: (BestMove) -> Unit): BestMove? {
            ponders.incrementAndGet()
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
