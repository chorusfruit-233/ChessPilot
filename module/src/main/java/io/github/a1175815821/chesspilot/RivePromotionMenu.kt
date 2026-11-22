package io.github.a1175815821.chesspilot

/** Select or dismiss the actual menu on the live Rive board. */
internal class RivePromotionMenu(private val dbPieceIndices: Map<*, *>?) {
    sealed interface Choice {
        class Legacy(val select: () -> Unit) : Choice
        class DataBound(val finish: (Int) -> Unit) : Choice
    }

    fun choice(view: Any, pawnId: String, suffix: Char): Choice? {
        val controller = Reflect.callMethod(view, "getController") ?: return null
        val artboard = Reflect.callMethod(controller, "getActiveArtboard") ?: return null
        val machines = Reflect.callMethod(view, "getStateMachines") as? List<*> ?: return null
        val chess = machines.filterNotNull().singleOrNull {
            Reflect.callMethod(it, "getName") == "chess_statemachine"
        } ?: return null
        val bindings = Reflect.callMethod(chess, "getViewModelInstance")
            ?: Reflect.callMethod(artboard, "getViewModelInstance")
        if (bindings != null) return dataBoundChoice(bindings, pawnId)

        val trigger = "promote_trigger_$suffix"
        val strings = arrayOf(String::class.java, String::class.java)
        val input = artboard.javaClass.getMethod("input", *strings).invoke(artboard, trigger, pawnId)
        if (input?.javaClass?.name != "app.rive.runtime.kotlin.core.SMITrigger") return null
        // This nested input follows the same Rive transition and promoted_event
        // as the corresponding menu button, including the closing animation.
        val fire = view.javaClass.getMethod("fireStateAtPath", *strings)
        return Choice.Legacy { fire.invoke(view, trigger, pawnId) }
    }

    private fun dataBoundChoice(bindings: Any, pawnId: String): Choice? {
        val index = (dbPieceIndices?.get(pawnId) as? Number)?.toInt() ?: return null
        val pieces = property(bindings, "getListProperty", "pieces") ?: return null
        val pawn = pieces.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(pieces, index) ?: return null
        val open = property(pawn, "getBooleanProperty", "promotion_in_progress_db_bool") ?: return null
        if (Reflect.callMethod(open, "getValue") != true) return null
        val pick = property(bindings, "getNumberProperty", "promoting_piece_type_db_num") ?: return null
        val complete = property(pawn, "getTriggerProperty", "complete_promotion_db_trig") ?: return null
        val setValue = pick.javaClass.getMethod("setValue", Any::class.java)
        return Choice.DataBound { piece ->
            setValue.invoke(pick, piece.toFloat())
            Reflect.callMethod(complete, "trigger")
        }
    }

    private fun property(model: Any, name: String, path: String): Any? =
        model.javaClass.getMethod(name, String::class.java).invoke(model, path)
}
