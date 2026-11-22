package io.github.a1175815821.chesspilot

import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/** Identify the live chess interfaces from the installed APK. */
internal class DexRemapper(private val dex: DexKitBridge) {
    private val classes = mutableMapOf<String, String>()
    private val methods = mutableMapOf<String, String>()
    private val fields = mutableMapOf<String, String>()

    fun discover(): JSONObject {
        val fen = model("chessFen", "ChessFen(piecePlacementString=")
        val setup = model("setupModel", "ChessGameSetupModel(fenNotation=")
        val state = model("chessGameState", "ChessGameState(fen=")
        val square = model("square", "ChessCoordinate(row=")
        val move = model("move", "ChessMove(from=")
        val reset = markedClass("ResetArrows")

        // The state reader uses these field layouts. A changed layout must be
        // understood before we use its position for a search or a tap.
        fen.requireFields(
            "a" to STRING, "b" to COLOR, "c" to "boolean", "d" to "boolean",
            "e" to "boolean", "f" to "boolean", "g" to STRING, "h" to "int", "i" to "int",
        )
        setup.requireFields("a" to STRING, "b" to LIST, "c" to "boolean")
        state.requireFields("a" to fen.name, "c" to setup.name, "d" to COLOR, "e" to COLOR, "f" to LIST)
        square.requireFields("a" to "int", "b" to "int")
        check(square.methods.any { it.isConstructor && it.paramTypeNames == listOf("int", "int") }) {
            "chess square constructor changed"
        }

        remember("fullFen", fen.methods.filter { it.matches(STRING) && it.name != "toString" })
        remember("moveUci", move.methods.filter { it.matches(STRING) && it.name != "toString" })

        val draw = dex.findMethod {
            matcher {
                returnType("void")
                paramTypes(square.name, square.name, "boolean", ARROW_TYPE, "int", ARROW_COLOR)
            }
        }.filter { candidate ->
            candidate.declaredClass?.methods?.any { it.matches(state.name) && "gameUseCase" in it.usingStrings } == true
        }.unique("board arrow method")
        val controller = requireNotNull(draw.declaredClass)
        classes["boardController"] = controller.name
        classes["arrowType"] = ARROW_TYPE
        classes["arrowColor"] = ARROW_COLOR
        remember("drawArrow", listOf(draw))
        val live = remember("liveState", controller.methods.filter { it.matches(state.name) && "gameUseCase" in it.usingStrings })
        val opening = remember("setupBoard", controller.methods.filter { it.matches("void", setup.name) })
        remember("clearArrows", controller.methods.filter { method ->
            method.matches("void") && method.usingFields.any { it.field.typeName == reset.name }
        })
        discoverBoardEnd(controller, state, live)
        discoverResults(controller.name, fen.name, opening.descriptor)
        val mapping = JSONObject().put("classes", JSONObject(classes)).put("methods", JSONObject(methods))
            .put("fields", JSONObject(fields))
        discoverPromotion(controller, state, move, square)?.let { mapping.put("promotion", it) }
        return mapping
    }

    private fun discoverBoardEnd(controller: ClassData, state: ClassData, live: MethodData) {
        val king = markedClass("KingColorAndSpecialState(color=", ", specialState=")
        rememberField("kingColor", king, COLOR)
        rememberField("kingSpecialState", king, MODEL + "KingSpecialState")
        remember("kingState", state.methods.filter { method ->
            method.matches(king.name) && method.usingFields.any {
                it.field.className == MODEL + "KingSpecialState" && it.field.name == "CHECKMATE"
            }
        })
        val useCase = live.usingFields.map { it.field }
            .filter { it.className == controller.name && !Modifier.isStatic(it.modifiers) }
            .distinctBy { it.descriptor }.unique("live game use case")
        fields["boardUseCase"] = useCase.descriptor
        val owner = requireNotNull(useCase.type)
        remember("commitBoardState", owner.methods.filter { method ->
            method.isMethod && !Modifier.isStatic(method.modifiers) && method.returnTypeName == "void" &&
                method.paramTypeNames.size == 1 && method.invokes.any {
                    it.className == state.name && it.returnTypeName == state.name && Modifier.isStatic(it.modifiers)
                }
        })
    }

    /** An unmatched picker leaves the rest of the board adapter available. */
    private fun discoverPromotion(controller: ClassData, state: ClassData, move: ClassData, square: ClassData): JSONObject? {
        val choose = controller.methods.singleOrNull { method ->
            method.matches("void", INTEGER, INTEGER, INTEGER) &&
                method.invokes.any { it.className == PIECE_TYPE && it.name == "getRiveValue" } &&
                method.usingFields.any { it.field.className == MODEL + "ChessHaptic" && it.field.name == "PROMOTED_PAWN" }
        } ?: return null
        val moves = choose.usingFields.map { it.field }
            .filter { it.className == state.name && it.typeName == LIST }.distinctBy { it.descriptor }
            .singleOrNull() ?: return null
        val destination = controller.methods.singleOrNull { method ->
            method.matches("void", INTEGER, INTEGER) && method.usingFields.any { it.field.descriptor == moves.descriptor }
        } ?: return null
        val preview = destination.invokes.singleOrNull {
            it.className == controller.name && it.matches("void", move.name, "boolean")
        } ?: return null
        val event = controller.methods.singleOrNull { method ->
            method.usingFields.any { it.field.className == MODEL + "rive.ChessRiveEvent" && it.field.name == "PROMOTION_EVENT" } &&
                method.invokes.any { it.descriptor == choose.descriptor }
        } ?: return null
        val reset = event.invokes.singleOrNull { method ->
            method.className == controller.name && method.matches("void") && method.usingFields.any { use ->
                use.field.type?.methods?.any { it.name == "toString" && "ResetInputs" in it.usingStrings } == true
            }
        } ?: return null
        val parse = state.methods.singleOrNull { it.matches(move.name, STRING) } ?: return null
        val from = move.fields.singleOrNull { it.name == "a" && it.typeName == square.name } ?: return null
        val pieceAt = state.methods.singleOrNull { method ->
            method.isMethod && !Modifier.isStatic(method.modifiers) && method.paramTypeNames == listOf(square.name) &&
                method.returnType?.methods?.any { it.name == "toString" && "ChessPiece(id=" in it.usingStrings } == true
        } ?: return null
        val pieceId = pieceAt.returnType?.fields?.singleOrNull {
            it.typeName == STRING && !Modifier.isStatic(it.modifiers)
        } ?: return null
        val dbRenderer = dex.findMethod {
            matcher { usingStrings("pieces", "promotion_box_db_trig") }
        }.singleOrNull()
        val dbIndices = dbRenderer?.let { renderer ->
            renderer.usingFields.map { it.field }.distinctBy { it.descriptor }.singleOrNull {
                it.className == renderer.className && it.typeName == "java.util.Map" && Modifier.isStatic(it.modifiers)
            }
        }
        val mapping = JSONObject()
            .put("preview", preview.descriptor)
            .put("choose", choose.descriptor)
            .put("reset", reset.descriptor)
            .put("parseMove", parse.descriptor)
            .put("selectedMoves", moves.descriptor)
            .put("moveFrom", from.descriptor)
            .put("pieceAt", pieceAt.descriptor)
            .put("pieceId", pieceId.descriptor)
        dbIndices?.let { mapping.put("dbPieceIndices", it.descriptor) }
        return mapping
    }

    private fun discoverResults(controller: String, fen: String, setup: String) {
        val botFinish = dex.findMethod {
            matcher {
                modifiers(Modifier.STATIC)
                returnType(OBJECT)
                paramTypes(null, END_OUTCOME, "java.lang.Integer", "boolean", END_REASON, CLIENT_END, null, "int")
            }
        }.filter { it.paramTypeNames.first() == it.className }.unique("bot result")
        val bot = requireNotNull(botFinish.declaredClass)
        val session = requireNotNull(bot.superClass) { "live chess session missing" }
        classes["botSession"] = bot.name
        classes["chessSession"] = session.name
        rememberField("botPlayerColor", bot, COLOR)
        remember("botGameEnd", listOf(botFinish))
        remember("botExit", bot.methods.filter { it.matches("void", CLIENT_END) })
        remember("sessionController", session.methods.filter { it.matches(controller) })
        remember("sessionCleared", session.methods.filter { it.name == "onCleared" && it.matches("void") })

        val siblings = dex.findClass { matcher { superClass(session.name) } }
        val pvpPayload = markedClass("GameEnd(outcome=", ", delayBeforeTakingActionMs=")
        pvpPayload.requireFields("a" to "${MATCH}network.ChessMatchOutcome", "b" to END_REASON)
        val pvpEnd = remember("pvpGameEnd", siblings.flatMap { it.methods }.filter {
            it.isMethod && !Modifier.isStatic(it.modifiers) && it.returnTypeName == OBJECT &&
                it.paramTypeNames.size == 2 && it.paramTypeNames.first() == pvpPayload.name
        })
        val pvp = requireNotNull(pvpEnd.declaredClass)
        rememberField("pvpPlayerColor", pvp, "${MATCH}network.ChessMatchPlayerColor")
        classes["pvpSession"] = pvp.name

        val roundEnd = remember("miniGameEnd", dex.findMethod {
            matcher {
                returnType("void")
                paramTypes(END_OUTCOME, END_REASON)
                usingStrings(" game is ended outcome :", "matchParams")
            }
        }.filter { it.isMethod && !Modifier.isStatic(it.modifiers) })
        val miniGame = requireNotNull(roundEnd.declaredClass)
        val params = markedClass("MatchParams(matchId=", ", fromLanguage=", ", userId=")
        params.requireFields("a" to STRING, "b" to fen)
        miniGame.requireFields("a" to controller, "g" to params.name)
        classes["miniGame"] = miniGame.name
        remember("miniGameStart", miniGame.methods.filter { method ->
            method.isMethod && !Modifier.isStatic(method.modifiers) && method.returnTypeName == "void" &&
                method.paramTypeNames.size == 1 && method.invokes.any { it.descriptor == setup }
        })

        val miniSession = siblings.filter { type ->
            type.methods.any { it.isConstructor && MINI_TYPE in it.paramTypeNames }
        }.unique("mini match session")
        val clock = miniSession.fields.singleOrNull { it.name == "z" }?.type
            ?: error("mini match clock missing")
        clock.requireFields("j" to "int")
        classes["miniSession"] = miniSession.name
        remember("miniSessionEnd", miniSession.methods.filter { method ->
            method.matches("void") && method.name != "onCleared" &&
                method.usingFields.any { it.field.className == miniSession.name && it.field.name == "z" }
        })
    }

    private fun model(role: String, marker: String): ClassData = markedClass(marker).also {
        classes[role] = it.name
    }

    private fun markedClass(vararg markers: String): ClassData {
        val method = dex.findMethod {
            matcher {
                name("toString")
                returnType(STRING)
                paramTypes()
                usingStrings(*markers)
            }
        }.unique(markers.first())
        return requireNotNull(method.declaredClass)
    }

    private fun remember(role: String, candidates: List<MethodData>): MethodData =
        candidates.unique(role).also { methods[role] = it.descriptor }

    private fun rememberField(role: String, owner: ClassData, type: String) {
        val field = owner.fields.filter { it.typeName == type && !Modifier.isStatic(it.modifiers) }
            .unique(role)
        fields[role] = field.descriptor
    }

    private fun ClassData.requireFields(vararg expected: Pair<String, String>) {
        val actual = fields.associate { it.name to it.typeName }
        check(expected.all { (name, type) -> actual[name] == type }) { "$name field layout changed" }
    }

    private fun MethodData.matches(result: String, vararg params: String): Boolean =
        isMethod && !Modifier.isStatic(modifiers) && returnTypeName == result && paramTypeNames == params.asList()

    private fun <T> List<T>.unique(role: String): T = singleOrNull()
        ?: error("$role found $size matches")

    companion object {
        private const val STRING = "java.lang.String"
        private const val INTEGER = "java.lang.Integer"
        private const val OBJECT = "java.lang.Object"
        private const val LIST = "java.util.List"
        private const val MODEL = "com.duolingo.chess.model."
        internal const val PIECE_TYPE = MODEL + "ChessPieceType"
        private const val COLOR = MODEL + "ChessPlayerColor"
        private const val ARROW_TYPE = MODEL + "rive.ArrowType"
        private const val ARROW_COLOR = MODEL + "rive.ArrowColor"
        private const val MATCH = "com.duolingo.data.chess.match."
        private const val END_OUTCOME = MATCH + "domain.ChessMatchEndOutcome"
        private const val END_REASON = MATCH + "network.ChessMatchEndCondition"
        private const val CLIENT_END = MATCH + "domain.ChessMatchClientEndCondition"
        private const val MINI_TYPE = "com.duolingo.data.path.common.ChessMiniMatchType"
    }
}
