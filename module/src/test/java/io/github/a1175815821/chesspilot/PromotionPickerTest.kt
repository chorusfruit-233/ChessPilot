package io.github.a1175815821.chesspilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionPickerTest {
    enum class Piece(val symbol: String, val riveValue: Int) {
        QUEEN("Q", 41), BISHOP("B", 39), KNIGHT("N", 18), PAWN("P", 5), ROOK("R", 33), KING("K", 2),
    }

    class Move(val uci: String)

    class BoardState(private val legal: List<Move>, @JvmField val selected: List<Move> = legal) {
        fun parse(uci: String): Move? = legal.firstOrNull { it.uci == uci }
    }

    class Controller(val state: BoardState) {
        val calls = mutableListOf<Any>()

        fun preview(move: Move, userMove: Boolean) = calls.add(move.uci to userMove)
        fun choose(rank: Int?, file: Int?, piece: Int?) {
            calls.add(listOf(rank, file, piece))
        }
        fun reset() {
            calls.add("reset")
        }
    }

    private val picker = PromotionPicker(
        preview = Controller::class.java.getMethod("preview", Move::class.java, Boolean::class.javaPrimitiveType),
        choose = Controller::class.java.getMethod(
            "choose", Int::class.javaObjectType, Int::class.javaObjectType, Int::class.javaObjectType,
        ),
        reset = Controller::class.java.getMethod("reset"),
        parseMove = BoardState::class.java.getMethod("parse", String::class.java),
        selectedMoves = BoardState::class.java.getField("selected"),
        moveUci = Move::class.java.getMethod("getUci"),
        liveState = Controller::class.java.getMethod("getState"),
        pieceType = Piece::class.java,
    )

    @Test fun preparesUnderpromotionUsingNativeQueenCandidate() {
        val state = BoardState(listOf(Move("a7b8q")))
        for (suffix in "qrbn") {
            assertTrue(picker.canPrepare(state, "a7b8$suffix"))
            assertTrue(picker.canChoose(state, "a7b8$suffix"))
        }
        assertFalse(picker.canPrepare(state, "a7a8q"))
        assertFalse(picker.canChoose(state, "a7a8q"))
    }

    @Test fun rejectsDifferentPawnAndOrdinaryMovesWithPromotionSuffix() {
        val state = BoardState(listOf(Move("c7b8q"), Move("a7b8q"), Move("e2e4")))
        assertTrue(picker.canPrepare(state, "a7b8n"))
        assertFalse(picker.canChoose(state, "a7b8n"))
        assertFalse(picker.matchesPreview(Move("c7b8q"), "a7b8n"))
        assertFalse(picker.matchesPreview(Move("e2e4"), "e2e4q"))
        assertFalse(picker.canPrepare(state, "e2e4q"))
    }

    @Test fun sendsExactPieceValuesThenResetsNativeInput() {
        val controller = Controller(BoardState(emptyList()))
        val pieces = mapOf('q' to Piece.QUEEN, 'r' to Piece.ROOK, 'b' to Piece.BISHOP, 'n' to Piece.KNIGHT)
        for ((suffix, piece) in pieces) {
            picker.select(controller, "g7h8$suffix", flipped = false)
            picker.select(controller, "b2a1$suffix", flipped = true)
            assertEquals(
                listOf(listOf(8, 8, piece.riveValue), "reset", listOf(8, 8, piece.riveValue), "reset"),
                controller.calls,
            )
            controller.calls.clear()
        }
        picker.select(controller, "a2b1n", flipped = false)
        picker.select(controller, "a7a8r", flipped = true)
        assertEquals(
            listOf(listOf(1, 2, Piece.KNIGHT.riveValue), "reset", listOf(1, 8, Piece.ROOK.riveValue), "reset"),
            controller.calls,
        )
    }
}
