package io.github.a1175815821.chesspilot

import java.util.UUID
import java.util.WeakHashMap

/**
 * Reads the live chess position out of (obfuscated) model instances and publishes it as
 * a normalized position snapshot to the companion app via the loopback TCP bridge.
 *
 * All field access uses Reflect.getObjectField against the R8-minified names from
 * docs/REVERSE_ENGINEERING.md. Every accessor validates defensively — if Duolingo changes
 * a layout, we degrade to the FEN-only secondary hook instead of crashing the host app.
 */
class StateBridge {

    private data class Board(val id: String = UUID.randomUUID().toString(), val fen: String? = null, val end: GameEnd? = null)
    private data class ConstructedState(val setup: Any, val state: Any)

    @Volatile private var board = Board()
    val lastFen: String? get() = board.fen
    val gameId: String get() = board.id
    private var controller: Any? = null
    private var setup: Any? = null
    private var constructedState: ConstructedState? = null
    private var miniMatchId: String? = null
    private val retiredControllers = WeakHashMap<Any, Unit>()

    @Volatile var lastUserColor: String? = null   // "w" | "b"
        private set

    @Volatile var lastTurnColor: String? = null
        private set

    @Volatile var lastMoves: List<String> = emptyList()   // uci history, e.g. ["e2e4","e7e5"]
        private set

    @Volatile var lastUpdateAt: Long = 0L
        private set

    private val lock = Any()

    /** Commit the board after Duolingo completes setup, using the position it just built. */
    fun onBoardSetup(
        owner: Any, matchId: String? = null, model: Any? = null, liveState: Any? = null,
    ): Boolean = synchronized(lock) {
        if (retiredControllers.containsKey(owner)) return@synchronized false
        val sameController = controller === owner
        val newGame = !sameController || (matchId != null && matchId != miniMatchId)
        val boardSetup = model ?: setup.takeIf { sameController }
        if (newGame) {
            if (!sameController) controller?.let { retiredControllers[it] = Unit }
            controller = owner
            miniMatchId = matchId
            board = Board()
            lastUserColor = null
            lastTurnColor = null
            lastMoves = emptyList()
            lastRenderProcessAt = 0
            BridgeAccess.lastState = null
            BridgeAccess.lastUserColor = null
            BridgeAccess.boardController = owner
            CommandLoop.cancelPendingMove()
            BridgePublisher.resetPosition()
        }
        setup = boardSetup
        if (board.end == null) {
            // The state constructor runs inside native setup, before this hook.
            val state = liveState ?: constructedState?.takeIf {
                board.fen == null && it.setup === boardSetup
            }?.state
            if (state != null) onGameState(state, "render")
            if (board.fen == null) {
                if (boardSetup != null) seedFromSetup(boardSetup)
                if (newGame || board.fen != null) publishSnapshot()
            }
        }
        true
    }

    fun canPlay(game: String?, fen: String): Boolean {
        val current = board
        return current.id == game && current.fen == fen && current.end == null
    }

    /** Order bridge commands against end/setup events before scheduling UI work. */
    fun onPlayableBoard(game: String?, fen: String, action: () -> Unit) = synchronized(lock) {
        if (canPlay(game, fen)) action()
    }

    fun onGameEnd(owner: Any?, end: GameEnd, onlyIfPlaying: Boolean = false) = synchronized(lock) {
        if (owner != null && owner === controller && (!onlyIfPlaying || board.end == null)) finishGame(end)
    }

    /** Engine fallback for boards that reach a terminal FEN without a match event. */
    fun onEngineGameEnd(game: String?, fen: String, end: GameEnd) = synchronized(lock) {
        if (board.id == game && board.fen == fen) finishGame(end)
    }

    private fun finishGame(end: GameEnd) {
        val previous = board.end
        val result = previous?.followedBy(end) ?: end
        if (result == previous) return
        board = board.copy(end = result)
        if (previous == null) {
            CommandLoop.cancelPendingMove()
            BridgePublisher.resetPosition()
            CommandLoop.clearArrowsAfterGame()
        }
        publishSnapshot()
        MainHook.log("game ended: ${result.reason} outcome=${result.outcome ?: "-"}")
    }

    private fun publishSnapshot() {
        BridgePublisher.publishSnapshot(board.fen, lastUserColor, lastTurnColor, lastMoves, board.id, board.end)
    }

    /** Called from the ChessGameState constructor hook. */
    fun onGameState(state: Any, src: String = "ctor") = synchronized(lock) {
        BridgePublisher.noteTrigger(src)
        try {
            val stateSetup = Reflect.getObjectField(state, "c")
            if (src == "ctor" && stateSetup != null) constructedState = ConstructedState(stateSetup, state)
            if (board.end != null) return@synchronized
            if (src != "render" && setup != null && setup !== stateSetup) return@synchronized
            val fen = extractFen(state) ?: return@synchronized
            val user = extractColor(state, "d")   // d = userColor
            val turn = extractColor(state, "e")   // e = turnColor
            val moves = extractMoves(state)       // f = List<Move>
            if (src == "render") setup = stateSetup
            BridgeAccess.lastState = state
            board = board.copy(fen = fen)
            lastUserColor = user ?: lastUserColor
            lastTurnColor = turn
            lastMoves = moves
            lastUpdateAt = System.currentTimeMillis()
            if (user != null) BridgeAccess.lastUserColor = user
            publishSnapshot()
        } catch (t: Throwable) {
            MainHook.log("onGameState failed: $t")
        }
    }

    /** Flatten fallback state into bootstrap-owned values, never module data classes. */
    fun saveForReload(saved: MutableMap<String, Any>) = synchronized(lock) {
        saved["game"] = board.id
        board.end?.let {
            saved["end"] = it.reason
            it.outcome?.let { outcome -> saved["outcome"] = outcome }
        }
        miniMatchId?.let { saved["miniMatchId"] = it }
        lastFen?.let { saved["fen"] = it }
        lastUserColor?.let { saved["user"] = it }
        lastTurnColor?.let { saved["turn"] = it }
        saved["moves"] = java.util.ArrayList(lastMoves)
    }

    /** FEN-only courses may have no retained game-state object to re-extract. */
    fun restoreAfterReload(saved: Map<*, *>, owner: Any?) = synchronized(lock) {
        controller = owner
        miniMatchId = saved["miniMatchId"] as? String
        val end = (saved["end"] as? String)?.let { GameEnd(it, saved["outcome"] as? String) }
        board = Board(saved["game"] as? String ?: board.id, saved["fen"] as? String, end)
        lastUserColor = saved["user"] as? String
        lastTurnColor = saved["turn"] as? String
        lastMoves = (saved["moves"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        lastUpdateAt = System.currentTimeMillis()
        BridgeAccess.lastUserColor = lastUserColor
        publishSnapshot()
    }

    /** Called from the ChessFen constructor hook (secondary path). */
    fun onFen(fenObj: Any) = synchronized(lock) {
        if (board.end != null) return@synchronized
        BridgePublisher.noteTrigger("fen")
        try {
            val fen = extractFenFrom(fenObj) ?: return@synchronized
            val turn = fen.split(" ").getOrNull(1)?.lowercase()
            board = board.copy(fen = fen)
            lastTurnColor = turn
            lastUpdateAt = System.currentTimeMillis()
            publishSnapshot()
        } catch (t: Throwable) {
            MainHook.log("onFen failed: $t")
        }
    }

    private fun extractFenFrom(fenObj: Any): String? {
        // Preferred: the app's own full-FEN getter qh4.b().
        try {
            val full = Reflect.callMethod(fenObj, "b") as? String
            if (full != null && full.count { it == ' ' } >= 5) return full
        } catch (_: Throwable) { }
        val placement = Reflect.getObjectField(fenObj, "a") as? String ?: return null
        if (!placement.contains('/')) return null
        val turn = colorSymbol(Reflect.getObjectField(fenObj, "b") ?: return null)
        val castling = buildString {
            if (bool(fenObj, "c")) append('K')
            if (bool(fenObj, "d")) append('Q')
            if (bool(fenObj, "e")) append('k')
            if (bool(fenObj, "f")) append('q')
            if (isEmpty()) append('-')
        }
        val ep = (Reflect.getObjectField(fenObj, "g") as? String).orEmpty().ifEmpty { "-" }
        val half = Reflect.getIntField(fenObj, "h")
        val fullmove = Reflect.getIntField(fenObj, "i")
        return "$placement $turn $castling $ep $half $fullmove"
    }

    @Volatile private var lastRenderProcessAt: Long = 0

    /** Called with the board controller's current live state. */
    fun onRenderedState(owner: Any, state: Any): Boolean = synchronized(lock) {
        if (controller == null) return@synchronized onBoardSetup(owner, liveState = state)
        if (controller !== owner || board.end != null) return@synchronized false
        // Render polling can fire dozens of times per second on the UI thread, each
        // doing full reflection extraction. Throttle the heavy part: states change
        // far slower than frames (ctor path is unthrottled and covers new states).
        val now = System.currentTimeMillis()
        if (now - lastRenderProcessAt < 200) return@synchronized board.end == null
        lastRenderProcessAt = now
        onGameState(state, "render") // re-publish: rendered state is authoritative for the UI
        board.end == null
    }

    /** The setup contains an explicit FEN, or defaults to startpos for an empty history. */
    private fun seedFromSetup(model: Any) {
        try {
            val history = Reflect.getObjectField(model, "b") as? List<*> ?: return
            val fen = Reflect.getObjectField(model, "a") as? String
                ?: if (history.isEmpty()) STARTPOS else return
            val parts = fen.split(' ')
            if (parts.size != 6 || parts[0].count { it == '/' } != 7) return
            val turn = parts[1].lowercase()
            if (turn != "w" && turn != "b") return
            val user = if (Reflect.getBooleanField(model, "c")) turn else if (turn == "w") "b" else "w"
            board = board.copy(fen = fen)
            lastUserColor = user
            lastTurnColor = turn
            lastMoves = moveUcis(history)
            lastUpdateAt = System.currentTimeMillis()
            BridgeAccess.lastUserColor = user
        } catch (t: Throwable) {
            MainHook.log("board setup position unavailable: $t")
        }
    }

    /** Called by ViewModelFallback when a FEN-shaped string is mined from kept-name classes. */
    fun acceptExternalFen(fen: String) = synchronized(lock) {
        if (board.end != null || fen == lastFen) return@synchronized
        BridgePublisher.noteTrigger("fb")
        board = board.copy(fen = fen)
        lastTurnColor = fen.split(" ").getOrNull(1)?.lowercase()
        lastUpdateAt = System.currentTimeMillis()
        publishSnapshot()
        MainHook.log("fen(fallback): $fen")
    }

    // ------------------------------------------------------------ extractors

    /** ChessGameState.a (fen: ChessFen) → full FEN string. Same extraction as [extractFenFrom]. */
    private fun extractFen(state: Any): String? {
        val fenObj = Reflect.getObjectField(state, "a") ?: return null
        return extractFenFrom(fenObj)
    }

    private fun extractColor(state: Any, field: String): String? {
        return try {
            colorSymbol(Reflect.getObjectField(state, field) ?: return null)
        } catch (_: Throwable) { null }
    }

    // Live 6.85.7 returns UPPERCASE "B"/"W" here (docs said lowercase — wrong),
    // while FEN-derived turns are lowercase. Normalize everything to lowercase at
    // the boundary so engine-side "b"=="B" style comparisons can never mis-skip.
    private fun colorSymbol(colorObj: Any): String? = try {
        (Reflect.callMethod(colorObj, "getSymbol") as? String)?.lowercase()
    } catch (_: Throwable) { null }

    /** ChessGameState.f = List<Move>; each Move.b() = uci "e2e4". */
    private fun extractMoves(state: Any): List<String> {
        return try {
            val list = Reflect.getObjectField(state, "f") as? List<*> ?: return emptyList()
            moveUcis(list)
        } catch (_: Throwable) { emptyList() }
    }

    private fun moveUcis(moves: List<*>): List<String> = moves.mapNotNull { move ->
        if (move == null) return@mapNotNull null
        try { Reflect.callMethod(move, "b") as? String } catch (_: Throwable) { null }
    }

    private fun bool(obj: Any, field: String): Boolean = try {
        Reflect.getBooleanField(obj, field)
    } catch (_: Throwable) { false }

    companion object {
        private const val STARTPOS = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }
}
