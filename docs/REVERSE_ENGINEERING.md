# Duolingo Chess — Reverse-Engineering Map (v6.105.2)

## Current APK: 6.105.2 / versionCode 2497

Source: the fork's draft release asset `_6.105.2.apks` (base + arm64_v8a +
xxxhdpi). Bundle SHA-256:
`cfd396e2b50c91ac050bfe3e9e37bd88632171936f84f96be545f2dca39e7c51`.
The following interfaces were checked against its DEX definitions. This is static
verification plus a module build, not a device/LSPosed gameplay test.

| Role | 6.105.2 class | Evidence |
|---|---|---|
| Game state | `ox5` | `ChessGameState(fen=` |
| FEN | `dm5` | `ChessFen(piecePlacementString=` |
| Board | `x05` | `ChessBoardState(board=` |
| Square | `h55` | `ChessCoordinate(row=`, constructor `(int row, int col)` |
| Move | `gf6` | `ChessMove(from=`, `b()` returns UCI |
| Piece | `eh6` | third parameter of the ShowArrow constructor |
| Setup | `nx5` | `ChessGameSetupModel(fenNotation=` |
| Controller | `mz6` | state getters + exact six-argument arrow method |
| Rive point | `vbz` | `RiveCoordinate(x=` |
| Arrow coordinates | `d9z` | `RiveArrowCoordinates(start=` |
| Arrow event | `jaz` | `ShowArrow(arrowType=` |
| Hide-moves event | `u9z` | `HideValidMoves(gameState=`; historical key `boardUpdateEvent` |
| Resync | `cr6` | `ResyncGameState(fen=` |

`ox5.a/d/e/f` still hold FEN / user color / current player / move history.
`dm5.a..i` still hold placement, active color, four castling rights, en passant,
halfmove clock and fullmove number; `dm5.b()` assembles the full FEN. Thus
`StateBridge` can retain its field accessors. `gf6.b()` remains the UCI getter.
The generator now validates these field types and method signatures before
writing a table, so a future layout change requires a runtime update as well.

The controller changed more substantially:

```kotlin
mz6.I(): ox5  // displayed state, including move-history navigation
mz6.J(): ox5  // live state, read from the game use case's StateFlow
mz6.e0(from: h55, to: h55, animate: Boolean, type: ArrowType, mode: Int, color: ArrowColor)
mz6.c0()      // dispatches the ResetArrows singleton aaz.a via F(...)
mz6.H(row: Int, col: Int): h55  // 1-based; white subtracts 1, black uses 8-index
```

`MainHook` hooks the mapped live state getter and `ArrowPainter` discovers the
exact draw signature. Arrow clearing must
use the generated `methods.clearArrows` mapping: the old hard-coded `r()` is now
an unrelated four-argument method. Arrow generation still rotates both square
indices by `7-index` for Black; automatic taps retain the same orientation.
The controller no longer owns the Rive View directly; constructor hooks on the
kept `app.rive.runtime.kotlin.RiveAnimationView` remain the view discovery path.

For game lifecycle detection, the module reads the live getter `mz6.J()` and
observes `mz6.O(nx5)` board setup. PvP resync uses the same setup method, so the
same controller keeps its result. `k25.j(bd6)` supplies an explicit new mini
match ID and can rearm play on the same controller.

| Live handler | Result data |
|---|---|
| `com.duolingo.feature.chess.match.j.I(...)` | Bot result in argument 1, server and client end conditions in arguments 4/5. Kotlin default-mask bits 8/16 mark omitted conditions. |
| `com.duolingo.feature.chess.match.j.N(...)` | Bot timeout, resignation, or abandonment from the client end condition. |
| `tr6.G(ar6, pq8)` | Accepted PvP game-end event, including winning color and end condition in `ar6.a/b`. |
| `k25.m(...)`, `le6.F()` | Mini match result and its end condition, with the session clock as a fallback when the session finishes before a round result. |
| `r37.onCleared()` | The active board's session was closed. |

The session's `r37.l()` returns its board controller. End events are applied
only to the current controller. The loopback snapshot includes the game ID
and optional result, so reconnects replay the result even if the FEN stayed
the same. The remap generator validates the handler signatures and fields
against the APK before writing the mapping.

Regenerate and verify with the same bundle:

```sh
python -m pip install androguard==3.3.5
python tools/remap.py --apk /path/to/_6.105.2.apks
python tools/remap.py --apk /path/to/_6.105.2.apks --check
python -m unittest discover -s tools -v
sh gradlew.sh :app:assembleRelease :app:lintRelease :module:testDebugUnitTest
```

The old script counted Androguard's descriptor spaces as parameter types, so it
missed the controller/square signature, and passed `BytesIO` to `APK` without
`raw=True`, so version extraction failed. Both are fixed. Unresolved or ambiguous
interfaces now stop generation before writing; stale R8 names are not retained.
The unified build uses Gradle 9.4.1 / AGP 9.2.1 / compileSdk 37 and the official
libxposed API 102 (compile-only). `module` is an Android library packaged inside
the `com.chessassist` APK. Target-object reflection no longer uses legacy Xposed
helpers. See [API102_CPU.md](API102_CPU.md) for lifecycle and CPU dispatch details.

## Historical device findings: 6.85.7

All findings below were extracted from the provided APK (`多邻国_6.85.7.apks` → `base.apk`,
`split_config.arm64_v8a.apk`) by mining dex strings and running Androguard over
`classes6.dex`. The app ships R8-minified, so Kotlin data classes keep their property
names only in `toString()`, and their class names are short obfuscated identifiers.

## 1. What the chess course actually is

- **Not Unity.** `libil2cpp.so` / `global-metadata.dat` contain **zero** chess symbols.
  (They belong to a small embedded Unity ad/game component.)
- **Not a WebView game.** The board is a **Rive animation** rendered by
  `app.rive.runtime.kotlin.RiveAnimationView` (`librive-android.so`), driven from Kotlin.
- The chess course logic is **pure Kotlin inside the main APK**, in these packages:
  - `com.duolingo.chess.*` (feature/UI layer, keeps its name!)
  - `com.duolingo.data.chess.*` (domain + network layer, keeps its name!)
  - `com.duolingo.core.chess.*` (lesson/session models, keeps its name!)
  - everything else (engine, board, move models) got R8-renamed to `xx4`-style names.

- Lessons are JSON sessions: `assets/hardcoded_sessions/chess/chess_*.json`
  (`chess_castling`, `chess_normal_check`, `chess_promotion`, `chess_star_rook`).
  They contain `correctMoves` in **`c3e3` coordinate format**, plus
  `moveEvaluationsForPositions` keyed by FEN prefix like
  `4k3/2pp4/8/8/2P5/1KR5/8/8 w` with `wdl` scores — Duolingo grades moves with an
  engine **server-side** (`EvaluateMoveResponse`). No engine is embedded in the app
  (`stockfish` appears nowhere in any dex), so local Stockfish is genuinely additive.

## 2. Obfuscated-name resolution table

Resolution method: the Kotlin data-class `toString()` implementations are
`ChessGameState(fen=...`, `ChessFen(piecePlacementString=...`, etc. We located these
`const-string`s in `toString()` bodies of minified classes:

| Obfuscated | Original (from `toString()`) | Role |
|---|---|---|
| `wq4` | `ChessGameState` | The live game state. **Primary hook target.** |
| `qh4` | `ChessFen` | FEN container. Fields = FEN parts. |
| `vq4` | `ChessGameSetupModel` | Initial position (`fenNotation`) + config. |
| `se5` | `ResyncGameState` | Server resync message. |
| `m9s` | `RiveArrowCoordinates` | `start`/`end` pixel-ish (Dp) coords of an arrow. |
| `q45` | `ChessBulletMiniMatchModel` | Bullet minigame, has `fen`. |
| — | `GameStateInfoForPreviousMove(fen=` | string exists; class renamed; search at runtime |

## 3. The core model classes (fields are Kotlin properties, R8-minified)

### `wq4` = ChessGameState — THE hook target

```kotlin
// wq4 (ChessGameState) — constructed via copy-style static 'a(...)'
class ChessGameState {
  val a: qh4                       // fen: ChessFen
  val b: uc4                       // board: Board (piece lookup)
  val c: vq4                       // setup: ChessGameSetupModel
  val d: ChessPlayerColor          // userColor
  val e: ChessPlayerColor          // turnColor (colorToMove)
  val f: List<o65>                 // moves: history of Move
  val g: ge4                       // (selected/last square holder; see ge4)
  val h: List<...>                 // highlight list
  val i: List<...>                 // legal-move / indicator list
  fun e(): List<o65>               // legal moves for current position
  fun f(String): o65               // find move by uci string ("c3e3")
  fun g(color: ChessPlayerColor): Boolean   // e.g. isInCheck(color)
  fun h(color: ChessPlayerColor): Boolean   // e.g. isCheckmated(color)
  fun c(square: ge4): List<o65>    // legal moves from a square
  // static 'a(...)' = copy(...) with 9 args (fen, board, color, moves, square, lists, int)
}
```

### `qh4` = ChessFen — FEN parts (9-arg ctor + copy)

```kotlin
class ChessFen {
  val a: String            // piecePlacementString  e.g. "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
  val b: ChessPlayerColor  // turn
  val c: Boolean           // whiteKingsideCastle (castling right)
  val d: Boolean           // whiteQueensideCastle
  val e: Boolean           // blackKingsideCastle
  val f: Boolean           // blackQueensideCastle
  val g: String            // enPassantTarget ("" or "e3" etc.)
  val h: Int               // halfmoveClock
  val i: Int               // fullmoveNumber
  val j: String            // (extra field, e.g. cached full fen)
  fun b(): String          // probably full FEN string
}
```

> **FEN assembly:** `a + " " + b.symbol + " " + castlingString(c,d,e,f) + " " +
> (g ifPresent else "-") + " " + h + " " + i`. The castling string order is KQkq;
> `ChessPlayerColor.getSymbol()` returns `"w"`/`"b"`.

### Board & squares

```kotlin
// ge4 = Square  (CONFIRMED by disassembly of ge4.a() and zi3.U())
class Square {
  val a: Int   // row, 0-based FROM RANK 1 (white side): a=0 → rank 1, a=7 → rank 8
  val b: Int   // column, 0-based: b=0 → 'a'
  fun a(): String  // square name: ('a'+b) + (a+1)  → ge4(4,4).a() == "e5"
}

// zi3 = Square companion helpers (obfuscated)
zi3.U(row: Int, col: Int): Square      // interning factory; ge4(row, col)
zi3.P("e2"): Square                    // parse from 2-char coordinate string

// Board-side helpers on cl5 (CONFIRMED by disassembly):
//   cl5.f(col: Int, row: Int): Square   tap→square, indices are 1..8:
//       WHITE: U(col-1, row-1)          → tap(1,1)=a1, tap(2,5)=e2, tap(1,5)=e1
//       BLACK: U(8-col, 8-row)          → board is ROTATED 180° (a1 at top-right)
//   cl5.i(sq): Square                   flip a square for the current player's display
//   cl5.u(sq): String                   display name adjusted for flip
```

**Screen mapping for auto-play (derived from the above):**
```
white: screen(col=file, rowFromTop=7-(rank-1))
black: 180° rotation → screen(col=7-file, rowFromTop=rank-1)
```
(`AutoPlayer.squareToScreen` implements exactly this.)

// uc4 = Board
class Board {
  val a: List<...>           // pieces
  val b: Map<ge4, Ly65>      // pieceAt map
  fun a(Square, ChessPlayerColor, o65): List<o65>   // legalMovesFrom/similar
  fun b(ChessPlayerColor): ge4                      // kingSquare(color)
  fun c(ge4): Ly65           // pieceAt(square)
  fun d(ChessPlayerColor): Boolean                  // e.g. canCastle / inCheck
}

// Ly65 = Piece
class Piece {
  val a: String        // id (uuid-ish)
  val b: ChessPieceType  // PAWN..KING (com/duolingo/chess/model/ChessPieceType)
  val c: ChessPlayerColor
  val d: Boolean       // hasMoved
}

// Lo65 = Move
class Move {
  val a: ge4                    // from
  val b: ge4                    // to
  val c: ChessPieceType         // pieceType
  val d: ChessPlayerColor       // color
  val e: ge4                    // capturedAt (nullable)
  val f: Ly65                   // promotion piece? (nullable)
  val g: x65                    // annotation/eval enum (nullable)
  val h: Boolean                // isCastle? / isCheck?
  val i: Boolean
  fun b(): String               // uci coordinate string "c3e3"
}
```

### Rive board controller — `cl5` (all drawing + interaction)

```kotlin
class cl5 {                        // = ChessRiveBoardController (drives RiveAnimationView)
  val e: nh3                       // callback wrapper (RiveWrapperView2$CallbackRiveAnimationView)
  val f: we7                       // rive view model holder
  val g: wq4                       // current ChessGameState (updated on every render)
  val a: q42                       // rive view / binding
  val b: kc4                       // config booleans (9 flags: touch enabled, shows coords...)
  // ---- drawing ----
  fun s(from: ge4, to: ge4, animate: Boolean, type: ArrowType, mode: Int, color: ArrowColor)
      // draws an arrow on the board (this is what CheckmateHelper hint uses)
  fun j(sq: ge4, sq2: ge4, highlight: SquareHighlightType)  // highlight squares
  fun l(sq: ge4, on: Boolean)      // select/deselect square
  fun n(); fun o(); fun v(); fun r()  // clear/reset variants (ResetArrows, ResetPreviousMoveHighlights)
  // ---- state ----
  fun g(): wq4                     // current ChessGameState
  fun k(setup: vq4)                // start new game from ChessGameSetupModel
  fun f(col: Int, row: Int): ge4   // tap (col,row) -> Square  ← coordinate mapping for auto-play
  fun i(sq: ge4): ge4              // transform square (board flip)
  fun u(sq: ge4): String           // square name
  fun h(move: o65): List<...>      // squares crossed by move
  fun m(from: ge4, state: wq4, b: Boolean)  // on-tap handler core (piece selection)
}
```

### Hint-arrow model — `kas` (what CheckmateHelper builds, and what we build too)

```kotlin
class kas {                // = ChessRiveArrow (Dr). RiveUiState holds a List of these
  val a: ArrowType         // SOLID / DOTTED
  val b: String            // arrow id
  val c: Ly65              // piece being moved (nullable)
  val d: m9s               // coordinates (start/end as xbs = Dp point)
  val e: ArrowColor        // GRAY / RED / YELLOW
}

class m9s {                // = RiveArrowCoordinates
  val a: xbs               // start: (Double x, Double y)
  val b: xbs               // end
}
class xbs { val a: Double; val b: Double }   // = Point (Dp)
```

`x9s(ChessGameState)` is a data-holder class wrapping a fresh state (`x9s(wq4)`) —
likely the "board update" event; hook its constructor to observe every state change.

### Checkmate-helper (server hint) — proof the in-app arrow channel works

```kotlin
// com/duolingo/data/chess/match/domain/CheckmateHelperModel (kept name)
// fields: suggestedMoveArrow: kas, suggestionScores: Map, suggestionReason, ...
// com/duolingo/data/chess/match/network/response/CheckmateHelperConfig (kept name)
```
The Rive UI state class(es) (`ac4`, `cc4` in classes6.dex) carry
`suggestedMoveArrow`, `substeps`, etc. — hooking `cl5.s(...)` is equivalent and simpler.

### Matches / lesson layer (kept names, useful for context)

```
com/duolingo/chess/match/ChessMatchActivity + ChessMatchActivityViewModel
com/duolingo/feature/chess/match/ChessMatchSessionFragment + ChessMatchSessionViewModel
com/duolingo/feature/chess/minimatch/ChessMiniMatchViewModel / ChessMiniMatchSessionViewModel
com/duolingo/session/challenges/chess/ChessPuzzleFragment + ChessPuzzleViewModel
com/duolingo/data/chess/match/domain/ChessBotMatch   // fields: status, playerColor, moves(PVector), ...
com/duolingo/data/chess/match/domain/ChessBotMatchStatus$*  // NotStarted/AwaitingFirstUserMove/FirstMoveReceived/UpdateReceived/CheckMateHelperUpdateReceived/ErrorOrTimeout
com/duolingo/data/chess/domain/ChessMoveCorrectness / com/duolingo/core/chess/MoveCorrectness
com/duolingo/feature/debug/settings/chess/ChessMatchStartingFenDebugSettingsActivity
   // debug screen: "resetChessMatchStartingFenAndColor" + "updateChessMatchStartingFen(String)"
```

### Rive enum ordinals (needed to call `cl5.s(...)`)

```
com/duolingo/chess/model/rive/ArrowType         : SOLID, DOTTED            (getValue(): Int)
com/duolingo/chess/model/rive/ArrowColor        : GRAY, RED, YELLOW        (getValue(): Int)
com/duolingo/chess/model/rive/SquareHighlightType: OFF, GREEN, RED, YELLOW (getValue(): Int)
```

### CONFIRMED: how Duolingo itself draws an arrow (game-review controller `hq4.j`)

```smali
# hq4.j() parses a UCI string (e.g. "e2e4q" — 5th char = promotion piece), then:
from = zi3.P("e2")                 # Square from 2-char coordinate
vto   = zi3.P("e4")
promo = ChessPieceType.Companion.a("q")   # promotion char → type
cl5.s(from, to, /*animate=*/false, ArrowType.SOLID, /*mode=*/0, ArrowColor.YELLOW)
```
This is byte-for-byte the call convention `ArrowPainter` uses — verified from real
caller bytecode, not guessed. Promotion piece type is carried in the UCI string; the interactive lesson picker
is a separate Rive-drawn UI flow the assistant deliberately does not drive
(promotions are manual-only by design).

## 4. Hook-point strategy (in order of preference)

1. **`wq4` constructor (any ctor / static `a` copy factory)** → canonical position event.
   From the instance read `b.a` (fen placement string), `d` (user color), `e` (turn).
2. **`qh4` constructor** → raw FEN parts even when the state class changes between
   lesson/match modes (some modes build `ChessFen` directly, e.g. bullet minigame).
3. **`cl5.g()` return** → "board rendered" confirmation + square↔pixel context is in the
   same controller (use `f(col,row)` for auto-play mapping).
4. **`cl5.s(...)` call observation** → learn Duolingo's own arrow format (and, in the
   "draw hint" mode, we simply *call* `s` ourselves on the same controller).
5. **`com/duolingo/feature/chess/match/ChessMatchSessionViewModel`** (kept name,
   Compose state flows) → fallback: mine `StateFlow<ChessBotMatch>` etc. at runtime.

## 5. Auto-play: screen coordinates

- `cl5.f(col: Int, row: Int): ge4` converts a logical tap coordinate to a `Square` —
  the inverse mapping is what we need: get board View bounds from the Rive
  `RiveAnimationView` (attached in `cl5.a`/`e`), divide into 8×8, respect board flip
  (when `d == ChessPlayerColor.BLACK` the board is flipped).
- Injected taps go through the module's own `MotionEvent` dispatch on the main thread
  (`MotionEvent.obtain(...)` → `view.dispatchTouchEvent(...)`) — no root needed since
  we run **inside** the target process via LSPosed. The shell-input fallback exists for
  the non-module companion-only mode.

## 6. Re-mapping for future versions

Run `tools/remap.py --apk <new>.apk`: it re-runs exactly this procedure (string-mines
`ChessGameState(fen=` etc., resolves owner classes, prints the Kotlin side of the table)
and writes `module/src/main/assets/remap.json` which `XposedBridge` loads to resolve
current names at runtime — so the module survives R8 renames across app updates.
