package com.chessassist.engine

import com.chessassist.PonderLimits
import java.util.concurrent.atomic.AtomicBoolean

/** One prediction and its completed search iterations, isolated from later rounds. */
internal class PonderSession(
    val fen: String,
    val prediction: String,
    history: List<String>,
    val limits: PonderLimits = PonderLimits(),
) {
    @Volatile private var history = history.toList()
    val stopFlag = AtomicBoolean(false)
    @Volatile private var cancelled = false
    @Volatile var thread: Thread? = null
    @Volatile var result: BestMove? = null
        private set

    @Synchronized fun update(best: BestMove) {
        if (!stopFlag.get()) result = best
    }

    @Synchronized fun cancel() {
        cancelled = true
        stopFlag.set(true)
        result = null
    }

    /** Freeze completed work while stopping its reader, without discarding the cache. */
    @Synchronized fun stopSearch() { stopFlag.set(true) }

    /** The full game state may complete the source FEN's history after we started. */
    @Synchronized fun updateSourceHistory(fen: String, moves: List<String>) {
        if (!cancelled && this.fen == fen && moves.size > history.size &&
            history.indices.all { moves[it].equals(history[it], ignoreCase = true) }) {
            history = moves.toList()
        }
    }

    fun canKeep(fen: String, prediction: String, limits: PonderLimits = this.limits): Boolean =
        !cancelled && this.fen == fen && this.prediction == prediction && this.limits == limits

    @Synchronized fun matches(fen: String, moves: List<String>): Boolean {
        if (cancelled) return false
        val target = result?.positionFen
        val targetMatches = target != null && samePosition(target, fen)
        if (target != null && !targetMatches) return false
        // When a lesson omits move history, the engine's exact target position
        // can still confirm the hit. Otherwise require exactly one new move.
        if (moves.isEmpty() && targetMatches) return true
        // A raw FEN update can arrive before the opponent's move is appended.
        // Only accept unchanged source history when the engine's complete target
        // FEN independently confirms the prediction. Conflicting histories still fail.
        if (targetMatches && moves.size == history.size &&
            history.indices.all { moves[it].equals(history[it], ignoreCase = true) }) return true
        if (moves.size != history.size + 1 ||
            history.indices.any { !moves[it].equals(history[it], ignoreCase = true) } ||
            !moves.last().equals(prediction, ignoreCase = true)) return false
        return true
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
