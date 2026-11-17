package io.github.a1175815821.chesspilot

/** Match result carried by a board snapshot, even when its FEN is unchanged. */
data class GameEnd(val reason: String, val outcome: String? = null) {
    fun followedBy(result: GameEnd): GameEnd = GameEnd(
        if (specificity(reason) > specificity(result.reason)) reason else result.reason,
        result.outcome ?: outcome,
    )

    companion object {
        private fun specificity(reason: String) = when (reason) {
            "LEFT_BOARD" -> 0
            "COMPLETED" -> 1
            "NO_LEGAL_MOVES" -> 2
            else -> 3
        }
    }
}
