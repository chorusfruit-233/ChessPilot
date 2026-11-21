package io.github.a1175815821.chesspilot

import android.view.MotionEvent
import android.view.View
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional auto-play: executes Stockfish's move on the real board by dispatching
 * MotionEvent down/move/up on the Rive board view, running **inside** the Duolingo
 * process — no root-shell input injection needed.
 *
 * Coordinate mapping (CONFIRMED from bytecode, see docs/REVERSE_ENGINEERING.md):
 *   - `cl5.f(col: 1..8, row: 1..8)` maps a tap to a Square:
 *       WHITE: U(col-1, row-1)  → tap(1,1)=a1, tap(2,5)=e2   (row counted from rank 1)
 *       BLACK: U(8-col, 8-row)  → board is ROTATED 180°, not mirrored (a1 at top-right)
 *   - Therefore, screen position of square (file, rank):
 *       white: x = left + file*cell,            y = top + (7-(rank-1))*cell
 *       black: x = left + (7-file)*cell,        y = top + (rank-1)*cell
 *
 * For a promotion, wait for the native board to accept the destination, then
 * submit the requested piece through the picker callback.
 */
class AutoPlayer internal constructor(
    private val bridge: StateBridge, remap: Remap, loader: ClassLoader,
) {

    private val mainHandler = GenerationHandler(android.os.Looper.getMainLooper())

    private data class PromotionAttempt(
        val key: String, val gameId: String?, val fen: String, val uci: String,
        val flipped: Boolean, val controller: Any, val view: View,
    )

    private val pendingPromotion = AtomicReference<PromotionAttempt?>()
    private val promotion = try {
        remap.promotionPicker(loader)?.also { picker ->
            HookRuntime.before(picker.choose, "promotion-choice") { chain ->
                val pending = pendingPromotion.get()
                if (pending != null && chain.thisObject === pending.controller) {
                    pendingPromotion.compareAndSet(pending, null)
                }
            }
            HookRuntime.after(picker.preview, "promotion-preview") { chain, _ ->
                val pending = pendingPromotion.get() ?: return@after
                val move = chain.getArg(0) ?: return@after
                if (chain.thisObject === pending.controller && chain.getArg(1) == true &&
                    picker.matchesPreview(move, pending.uci)
                ) {
                    // Run after the native tap handler has finished showing the picker.
                    mainHandler.post { HookRuntime.runIfActive { selectPromotion(picker, pending) } }
                }
            }
        }
    } catch (e: Exception) {
        MainHook.log("native promotion unavailable: $e")
        null
    }

    fun close() {
        mainHandler.close()
        cancelPending()
    }

    @Synchronized fun cancelPending() {
        activeKey = null
        inFlightKey = null
        attemptKey = null
        pendingPromotion.set(null)
        attempts.set(0)
        mainHandler.cancelPending()
    }
    private val rand = java.util.Random()

    companion object {
        const val DEFAULT_GAP_MS = 450L
        /** Max tap attempts per (fen, move). Bounded: a systematically-wrong geometry
         * must degrade to asking the user, never to an endless tap storm. */
        const val MAX_ATTEMPTS = 3
        private val UCI_RE = Regex("[a-h][1-8][a-h][1-8][qrbn]?")

        /** Real UCI only (e2e4 / e7e8q): rejects engine markers like "(none)". */
        fun isLegalUci(u: String): Boolean = UCI_RE.matches(u)
    }

    // Cross-thread budget: performMove (bridge reader thread) checks/resets, while
    // tapMove and the applied-check (main thread) increment/read. The old plain Int
    // had no visibility guarantees — the budget could silently overspend or underspend.
    @Volatile private var attemptKey: String? = null
    private val attempts = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Generation of the currently-live tap chain. A new (fen,move) command supersedes
     * everything still queued from the old one: every delayed step re-checks this
     * (plus the board fen) and aborts instead of tapping stale squares into a board
     * that has already moved on — that cross-firing is what wedged games.
     */
    @Volatile private var activeKey: String? = null

    /**
     * Chain currently executing taps. A second play tap for the SAME pair while the
     * first is still in flight (double-tap on the banner button) is ignored instead
     * of interleaving a second gesture stream into the board — interleaved taps
     * select garbage and the move silently fails ("tap twice, first does nothing").
     */
    @Volatile private var inFlightKey: String? = null

    /**
     * @param cmdFen the FEN this move was computed for; if the board has moved on
     * (latest published fen differs), the tap is skipped — never play a stale move.
     * @param gapMs base gap select-tap → destination-tap; each move gets ±20% jitter.
     */
    @Synchronized
    fun performMove(uci: String, cmdFen: String, gameId: String?, gapMs: Long? = null) {
        val orientation = bridge.tapOrientation(gameId, cmdFen)
        if (!HookRuntime.active || orientation == null) {
            MainHook.log("autoplay skipped: position is no longer playable ($uci)")
            return
        }
        if (!isLegalUci(uci)) {
            MainHook.log("autoplay refused: not a UCI move: $uci")
            return
        }
        val promotes = uci.length == 5
        if (promotes && promotion == null) {
            BridgeClient.sendHelp("promotion", uci)
            return
        }
        val flipped = orientation == "b"
        val gap = (gapMs ?: DEFAULT_GAP_MS).coerceIn(150L, 1500L)
        // Per-position attempt budget (resets on a new fen/move pair).
        val key = "$gameId|$cmdFen|$uci"
        // Never repeat pawn taps into a picker waiting for a choice.
        val budgetKey = if (promotes) "$gameId|$cmdFen|promotion" else key
        if (attemptKey != budgetKey) {
            attemptKey = budgetKey
            attempts.set(0)
        }
        val maxAttempts = if (promotes) 1 else MAX_ATTEMPTS
        if (attempts.get() >= maxAttempts) {
            MainHook.log("autoplay: attempt budget spent for $uci, staying quiet")
            return
        }
        // Claim the flight synchronously: a second tap for the same pair arriving
        // while the first is still resolving the view must not start a parallel
        // gesture stream (check-and-set here; benign worst case is one overlap).
        if (inFlightKey == key) {
            MainHook.log("autoplay: chain already in flight for $uci, ignoring duplicate tap")
            return
        }
        pendingPromotion.set(null)
        inFlightKey = key
        activeKey = key

        mainHandler.post {
            if (isSuperseded(key, gameId, cmdFen, uci, flipped)) return@post
            // Resolve the view on the main thread (layout bounds valid here), with a
            // lazy rescan if the constructor hook missed it.
            val view = RiveViewFinder.bestRiveView() ?: RiveViewFinder.ensure() ?: run {
                MainHook.log(
                    "autoplay: no board view yet " +
                        "(controller=${BridgeAccess.boardController?.javaClass?.name})"
                )
                finishFlight(key)
                return@post
            }
            if (!view.isShown || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
                MainHook.log("autoplay: board view not laid out yet (${view.width}x${view.height})")
                finishFlight(key)
                return@post
            }
            try {
                if (promotes && !canPreparePromotion(uci, cmdFen)) {
                    finishFlight(key)
                    if (!isSuperseded(key, gameId, cmdFen, uci, flipped)) BridgeClient.sendHelp("promotion", uci)
                    return@post
                }
                tapMove(view, uci, flipped, gap, gameId, cmdFen, key)
            } catch (t: Throwable) {
                finishFlight(key)
                MainHook.log("autoplay down failed: $t")
            }
        }
    }

    private fun tapMove(
        view: View, uci: String, flipped: Boolean, gap: Long,
        gameId: String?, cmdFen: String, key: String,
    ) {
        if (isSuperseded(key, gameId, cmdFen, uci, flipped)) return
        if (!view.isShown || !view.isAttachedToWindow) {
            finishFlight(key)
            return
        }
        val from = uci.substring(0, 2)
        val to = uci.substring(2, 4)

        attempts.incrementAndGet()
        // Human-like gap with ±20% jitter; Duolingo highlights the selected square here.
        val jittered = (gap * (0.8 + rand.nextDouble() * 0.4)).toLong()
        tapSquare(view, from, flipped)
        mainHandler.postDelayed({
            try {
                if (isSuperseded(key, gameId, cmdFen, uci, flipped)) return@postDelayed
                if (!view.isShown || !view.isAttachedToWindow) {
                    finishFlight(key)
                    return@postDelayed
                }
                if (uci.length == 5) {
                    val controller = BridgeAccess.boardController ?: run {
                        finishFlight(key)
                        return@postDelayed
                    }
                    pendingPromotion.set(PromotionAttempt(key, gameId, cmdFen, uci, flipped, controller, view))
                }
                tapSquare(view, to, flipped)
                if (!isSuperseded(key, gameId, cmdFen, uci, flipped)) {
                    scheduleAppliedCheck(view, uci, flipped, gap, gameId, cmdFen, key)
                }
            } catch (t: Throwable) {
                // Release the flight: without this a throwing tapSquare()
                // wedges `key` in flight and every later play for the same
                // pair is silently swallowed ("chain already in flight").
                finishFlight(key)
                MainHook.log("autoplay release failed: $t")
            }
        }, jittered)
        MainHook.log("autoplay performed: $uci (attempt ${attempts.get()}/$MAX_ATTEMPTS, gap ${jittered}ms)")
    }

    /** True when this chain is no longer current: superseded, or board moved on. */
    private fun isSuperseded(key: String, gameId: String?, fen: String, uci: String, flipped: Boolean): Boolean {
        val orientation = if (flipped) "b" else "w"
        if (activeKey == key && HookRuntime.active && bridge.tapOrientation(gameId, fen) == orientation) return false
        MainHook.log("autoplay: dropping obsolete tap for $uci")
        finishFlight(key)
        return true
    }

    @Synchronized private fun finishFlight(key: String) {
        if (inFlightKey == key) inFlightKey = null
        pendingPromotion.get()?.takeIf { it.key == key }?.let { pendingPromotion.compareAndSet(it, null) }
    }

    private fun canPreparePromotion(uci: String, fen: String): Boolean {
        val picker = promotion ?: return false
        val controller = BridgeAccess.boardController ?: return false
        val state = picker.readState(controller) ?: return false
        return bridge.extractFen(state) == fen && picker.canPrepare(state, uci)
    }

    private fun selectPromotion(picker: PromotionPicker, pending: PromotionAttempt) {
        if (pendingPromotion.get() !== pending) return
        try {
            bridge.onPlayableBoard(pending.gameId, pending.fen) {
                if (BridgeAccess.boardController !== pending.controller ||
                    !pending.view.isShown || !pending.view.isAttachedToWindow
                ) return@onPlayableBoard
                val state = picker.readState(pending.controller) ?: return@onPlayableBoard
                if (bridge.extractFen(state) != pending.fen || !picker.canChoose(state, pending.uci) ||
                    isSuperseded(pending.key, pending.gameId, pending.fen, pending.uci, pending.flipped)
                ) return@onPlayableBoard
                if (pendingPromotion.compareAndSet(pending, null)) {
                    picker.select(pending.controller, pending.uci, pending.flipped)
                    MainHook.log("autoplay: submitted native promotion ${pending.uci}")
                }
            }
        } catch (e: Exception) {
            MainHook.log("autoplay: native promotion failed: $e")
        }
    }

    /** Verify the move and retry only ordinary board taps on an unchanged position. */
    private fun scheduleAppliedCheck(
        view: View, uci: String, flipped: Boolean, gap: Long,
        gameId: String?, cmdFen: String, key: String,
    ) {
        mainHandler.postDelayed({
            try {
                val promotes = uci.length == 5
                if (promotes && bridge.gameId == gameId && bridge.lastFen != cmdFen &&
                    uci in bridge.lastMoves.takeLast(3)
                ) {
                    MainHook.log("autoplay applied: $uci")
                    finishFlight(key)
                    return@postDelayed
                }
                if (isSuperseded(key, gameId, cmdFen, uci, flipped)) return@postDelayed
                if (promotes) {
                    finishFlight(key)
                    BridgeClient.sendHelp("promotion", uci)
                    return@postDelayed
                }
                val uci4 = uci.take(4)
                val applied = bridge.lastMoves.takeLast(3).any { it.take(4) == uci4 }
                if (applied) {
                    MainHook.log("autoplay applied: $uci")
                    finishFlight(key)
                    return@postDelayed
                }
                if (attempts.get() < MAX_ATTEMPTS) {
                    MainHook.log("autoplay: move not detected, retrying (${attempts.get()}/$MAX_ATTEMPTS): $uci")
                    tapMove(view, uci, flipped, (gap * 1.2).toLong(), gameId, cmdFen, key)
                    return@postDelayed
                }
                MainHook.log("autoplay: giving up after $attempts attempts: $uci")
                finishFlight(key)
                BridgeClient.sendHelp("stuck", uci)
            } catch (t: Throwable) {
                // Same leak as the release path: never leave the flight
                // claimed behind an exception with no user feedback.
                finishFlight(key)
                MainHook.log("autoplay verify failed: $t")
            }
        }, gap + 1500)
    }

    /** Send a full tap gesture (DOWN → 2 MOVEs → UP) centered on `squareName`. */
    private fun tapSquare(view: View, squareName: String, flipped: Boolean) {
        val file = squareName[0] - 'a'
        val rank = squareName[1] - '1'
        tapSquareAt(view, file, rank, flipped)
    }

    private fun tapSquareAt(view: View, file: Int, rank: Int, flipped: Boolean) {
        val (bx, by) = squareToScreen(view, file, rank, flipped)
        // Humanize: land slightly off-center (±8% of a cell) instead of pixel-perfect.
        val cell = minOf(view.width, view.height).toFloat() / 8f
        val x = bx + (rand.nextFloat() - 0.5f) * 0.16f * cell
        val y = by + (rand.nextFloat() - 0.5f) * 0.16f * cell
        tapViewAt(view, x, y, "${'a' + file}${rank + 1} flipped=$flipped")
    }

    /**
     * Send a full tap gesture (DOWN → 2 MOVEs → UP) at target-local coordinates.
     * Must be local, not screen-absolute (see [squareToScreen]): the framework
     * only translates coordinates when descending a hierarchy.
     */
    private fun tapViewAt(target: View, x: Float, y: Float, label: String) {
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        // Deliberate press (~150-230 ms): instant down/up taps sometimes don't register
        // and the piece snaps back.
        val press = 150L + rand.nextInt(80)
        val downTime = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, ex: Float, ey: Float, t: Long): MotionEvent {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = ex; this.y = ey; pressure = 1f; size = 1f
            })
            return MotionEvent.obtain(downTime, t, action, 1, props, coords, 0, 0, 1f, 1f,
                0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        val downRet = target.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x, y, downTime))
        val moveRet = target.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x, y, downTime + press / 3))
        target.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x, y, downTime + 2 * press / 3))
        val upRet = target.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x, y, downTime + press))
        MainHook.log(
            "tap $label -> (${x.toInt()},${y.toInt()}) " +
                "view=${target.javaClass.name.substringAfterLast('.')} " +
                "${target.width}x${target.height}@(${loc[0]},${loc[1]}) " +
                "shown=${target.isShown} ret=$downRet/$moveRet/$upRet"
        )
    }

    /**
     * Square (file 0-7, rank 0-7 from white's side) → VIEW-LOCAL coordinates.
     * White: a1 bottom-left. Black: board rotated 180°.
     *
     * Must be local, not screen-absolute: View.dispatchTouchEvent consumes the event
     * as-is (the framework only translates coordinates when descending a hierarchy).
     * Passing screen coords to a view at (0,650) shifted every tap ~5 ranks down —
     * out of bounds, accepted (true) but ignored. That silently disabled autoplay.
     */
    private fun squareToScreen(view: View, file: Int, rank: Int, flipped: Boolean): Pair<Float, Float> {
        val boardSize = minOf(view.width, view.height).toFloat()
        val left = (view.width - boardSize) / 2f
        val top = (view.height - boardSize) / 2f
        val cell = boardSize / 8f

        val col = if (flipped) 7 - file else file
        val rowFromTop = if (flipped) rank else 7 - rank
        return (left + col * cell + cell / 2f) to (top + rowFromTop * cell + cell / 2f)
    }
}
