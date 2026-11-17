package com.chessassist.engine

import org.junit.Assert.*
import org.junit.Test

class PonderSessionTest {
    private val source = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    private val target = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
    private fun session() = PonderSession(source, "e7e5", listOf("e2e4"))
    private fun prepared() = BestMove("g1f3", "b8c6", 25, 18, positionFen = target)

    @Test fun finishedReplySurvivesRepeatedOpponentTicks() {
        val job = session()
        job.thread = Thread {} // A finished/not-alive thread still has useful work.
        job.update(prepared())
        repeat(20) {
            assertTrue(job.canKeep(source, "e7e5"))
            assertEquals("g1f3", job.result?.uci)
        }
    }

    @Test fun completedIterationsImproveThePreparedReply() {
        val job = session()
        job.update(prepared().copy(depth = 8))
        job.update(prepared().copy(uci = "b1c3", depth = 19))
        assertEquals(19, job.result?.depth)
        assertEquals("b1c3", job.result?.uci)
    }

    @Test fun exactPredictionHits() {
        val job = session()
        job.update(prepared())
        assertTrue(job.matches(target, listOf("e2e4", "e7e5")))
    }

    @Test fun differentOpponentMoveMisses() {
        val job = session()
        job.update(prepared())
        assertFalse(job.matches(target, listOf("e2e4", "c7c5")))
    }

    @Test fun sameLastMoveWithDifferentHistoryOrPositionMisses() {
        val job = session()
        job.update(prepared())
        assertFalse(job.matches(target, listOf("d2d4", "e7e5")))
        assertFalse(job.matches(source, listOf("e2e4", "e7e5")))
        assertFalse(job.matches(target, listOf("e2e4", "e7e5", "g1f3", "e7e5")))
    }

    @Test fun uncapturableEnPassantDifferenceStillHits() {
        val job = session()
        job.update(prepared())
        assertTrue(job.matches(target.replace(" - 0 2", " e6 0 2"), listOf("e2e4", "e7e5")))
        assertFalse(PonderSession.samePosition(
            "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2",
            "4k3/8/8/3pP3/8/8/8/4K3 w - - 0 2"))
    }

    @Test fun lessonWithoutHistoryMustMatchEngineTarget() {
        val job = session()
        job.update(prepared())
        assertTrue(job.matches(target, emptyList()))
        assertFalse(job.matches(source, emptyList()))
    }

    @Test fun promotionSuffixMustMatch() {
        val job = PonderSession(source, "e7e8q", emptyList())
        assertFalse(job.matches(target, listOf("e7e8n")))
        assertFalse(job.matches(target, listOf("e7e8")))
    }

    @Test fun cancelledWorkerCannotPublishIntoItsOldOrNextRound() {
        val old = session()
        old.update(prepared())
        old.cancel()
        old.update(prepared().copy(depth = 30))
        val next = session()
        assertNull(old.result)
        assertNull(next.result)
        assertFalse(old.canKeep(source, "e7e5"))
        assertFalse(old.matches(target, listOf("e2e4", "e7e5")))
    }
}
