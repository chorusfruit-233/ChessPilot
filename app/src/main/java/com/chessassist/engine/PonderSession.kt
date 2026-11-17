package com.chessassist.engine

import java.util.concurrent.atomic.AtomicBoolean

/** One prediction and its completed search iterations, isolated from later rounds. */
internal class PonderSession(
    val fen: String,
    val prediction: String,
    history: List<String>,
) {
    private val history = history.toList()
    val stopFlag = AtomicBoolean(false)
    @Volatile var thread: Thread? = null
    @Volatile var result: BestMove? = null
        private set

    @Synchronized fun update(best: BestMove) {
        if (!stopFlag.get()) result = best
    }

    @Synchronized fun cancel() {
        stopFlag.set(true)
        result = null
    }

    fun canKeep(fen: String, prediction: String): Boolean =
        !stopFlag.get() && this.fen == fen && this.prediction == prediction &&
            (thread?.isAlive == true || result != null)

    fun matches(fen: String, moves: List<String>): Boolean {
        if (stopFlag.get()) return false
        val target = result?.positionFen
        // When a lesson omits move history, the engine's exact target position
        // can still confirm the hit. Otherwise require exactly one new move.
        if (moves.isEmpty() && target != null) return samePosition(target, fen)
        if (moves.size != history.size + 1 ||
            history.indices.any { !moves[it].equals(history[it], ignoreCase = true) } ||
            !moves.last().equals(prediction, ignoreCase = true)) return false
        return target == null || samePosition(target, fen)
    }

    companion object {
        /** Stockfish omits en-passant targets that no adjacent pawn can use. */
        internal fun samePosition(a: String, b: String): Boolean {
            fun key(fen: String): List<String>? {
                val parts = fen.trim().split(Regex("\\s+")).toMutableList()
                if (parts.size != 6) return null
                val rows = parts[0].split('/')
                if (rows.size != 8) return null
                val ep = parts[3]
                if (ep.matches(Regex("[a-h][36]"))) {
                    val file = ep[0] - 'a'
                    val sourceRank = (ep[1] - '0') + if (parts[1] == "w") -1 else 1
                    val row = rows[8 - sourceRank].flatMap { ch ->
                        if (ch in '1'..'8') List(ch - '0') { '.' } else listOf(ch)
                    }
                    val pawn = if (parts[1] == "w") 'P' else 'p'
                    if (row.getOrNull(file - 1) != pawn && row.getOrNull(file + 1) != pawn) parts[3] = "-"
                }
                return parts
            }
            val first = key(a) ?: return false
            return first == key(b)
        }
    }
}
