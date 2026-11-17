package com.chessassist.engine

import org.junit.Assert.*
import org.junit.Test

class PonderInfoTest {
    private val fen = "target position"

    @Test fun parsesReplyAndNextPredictionFromCompletedIteration() {
        val best = PonderInfo.parse("info depth 18 seldepth 25 multipv 1 score cp 32 nodes 12500 pv g1f3 b8c6 f1b5", fen)!!
        assertEquals("g1f3", best.uci)
        assertEquals("b8c6", best.ponder)
        assertEquals(18, best.depth)
        assertEquals(32, best.scoreCp)
        assertEquals(fen, best.positionFen)
    }

    @Test fun parsesPromotionAndMateScore() {
        val best = PonderInfo.parse("info depth 12 score mate -2 pv e7e8q", fen)!!
        assertTrue(best.isMate)
        assertEquals(-20000, best.scoreCp)
        assertEquals("e7e8q", best.uci)
        assertNull(best.ponder)
    }

    @Test fun rejectsBoundsAndOutputBeforeTargetFen() {
        val line = "info depth 19 score cp 44 pv g1f3"
        assertNull(PonderInfo.parse(line.replace(" pv", " lowerbound pv"), fen))
        assertNull(PonderInfo.parse(line.replace(" pv", " upperbound pv"), fen))
        assertNull(PonderInfo.parse(line, null))
    }

    @Test fun acceptsWhitespaceBetweenPvMoves() {
        val best = PonderInfo.parse("info depth 8 score cp 5 pv g1f3   b8c6", fen)!!
        assertEquals("b8c6", best.ponder)
    }

    @Test fun ignoresProgressWithoutAValidMove() {
        assertNull(PonderInfo.parse("info depth 12 currmove g1f3", fen))
        assertNull(PonderInfo.parse("info depth 12 score cp 0 pv (none)", fen))
    }
}
