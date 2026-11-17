package com.chessassist.engine

/** Only completed, exact principal variations are usable as prepared replies. */
internal object PonderInfo {
    private val move = Regex("[a-h][1-8][a-h][1-8][qrbn]?")
    private val whitespace = Regex("\\s+")

    fun parse(line: String, fen: String?): BestMove? {
        if (fen == null || !line.startsWith("info depth ") ||
            "lowerbound" in line || "upperbound" in line || " pv " !in line) return null
        val words = line.trim().split(whitespace)
        val depth = words.getOrNull(2)?.toIntOrNull() ?: return null
        val pv = words.indexOf("pv")
        val uci = words.getOrNull(pv + 1)?.takeIf(move::matches) ?: return null
        val score = words.indexOf("score")
        if (score < 0) return null
        val value = words.getOrNull(score + 2)?.toIntOrNull() ?: return null
        val mate = words.getOrNull(score + 1) == "mate"
        if (!mate && words.getOrNull(score + 1) != "cp") return null
        return BestMove(uci, words.getOrNull(pv + 2)?.takeIf(move::matches),
            if (mate) value * 10000 else value, depth, mate, fen)
    }
}
