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
        remember("liveState", controller.methods.filter { it.matches(state.name) && "gameUseCase" in it.usingStrings })
        val opening = remember("setupBoard", controller.methods.filter { it.matches("void", setup.name) })
        remember("clearArrows", controller.methods.filter { method ->
            method.matches("void") && method.usingFields.any { it.field.typeName == reset.name }
        })
        discoverResults(controller.name, fen.name, opening.descriptor)
        return JSONObject().put("classes", JSONObject(classes)).put("methods", JSONObject(methods))
            .put("fields", JSONObject(fields))
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
        rememberPlayerColor("botPlayerColor", bot, COLOR)
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
        rememberPlayerColor("pvpPlayerColor", pvp, "${MATCH}network.ChessMatchPlayerColor")
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

    private fun rememberPlayerColor(role: String, session: ClassData, colorType: String) {
        val field = session.fields.filter { it.typeName == colorType && !Modifier.isStatic(it.modifiers) }
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
        private const val OBJECT = "java.lang.Object"
        private const val LIST = "java.util.List"
        private const val MODEL = "com.duolingo.chess.model."
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
