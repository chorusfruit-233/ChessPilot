package io.github.a1175815821.chesspilot

import java.lang.reflect.Field
import java.lang.reflect.Method

/** Uses the callbacks that the Rive picker calls when the player selects a piece. */
internal class PromotionPicker(
    val preview: Method,
    val choose: Method,
    private val reset: Method,
    private val parseMove: Method,
    private val selectedMoves: Field,
    private val moveUci: Method,
    private val liveState: Method,
    pieceType: Class<*>,
) {
    private val pieceValues: Map<Char, Int>

    init {
        val symbol = pieceType.getMethod("getSymbol")
        val riveValue = pieceType.getMethod("getRiveValue")
        pieceValues = requireNotNull(pieceType.enumConstants).mapNotNull { piece ->
            val suffix = (symbol.invoke(piece) as String).single().lowercaseChar()
            if (suffix in "qrbn") suffix to (riveValue.invoke(piece) as Int) else null
        }.toMap()
        check(pieceValues.size == 4 && pieceValues.values.toSet().size == 4) {
            "incomplete promotion piece mapping"
        }
    }

    fun readState(controller: Any): Any? = liveState.invoke(controller)

    /** Some boards represent every pending promotion as a queen until selection. */
    fun canPrepare(state: Any, uci: String): Boolean {
        val move = parseMove.invoke(state, uci)
            ?: uci.takeIf { it[4] != 'q' }?.let { parseMove.invoke(state, it.take(4) + "q") }
            ?: return false
        return matchesPreview(move, uci)
    }

    fun matchesPreview(move: Any, uci: String): Boolean =
        (moveUci.invoke(move) as? String)?.let {
            it.length == 5 && it[4] in pieceValues && it.regionMatches(0, uci, 0, 4)
        } == true

    fun canChoose(state: Any, uci: String): Boolean {
        val moves = selectedMoves.get(state) as? List<*> ?: return false
        // The native callback uses the first move to the destination square.
        val move = moves.asSequence().filterNotNull().firstOrNull {
            (moveUci.invoke(it) as? String)?.regionMatches(2, uci, 2, 2) == true
        } ?: return false
        return matchesPreview(move, uci)
    }

    fun select(controller: Any, uci: String, flipped: Boolean) {
        val rank = uci[3] - '0'
        val file = uci[2] - 'a' + 1
        val piece = pieceValues.getValue(uci[4])
        choose.invoke(controller, if (flipped) 9 - rank else rank, if (flipped) 9 - file else file, piece)
        reset.invoke(controller)
    }
}
