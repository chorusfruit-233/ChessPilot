package io.github.a1175815821.chesspilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RivePromotionMenuTest {
    class Switch(var value: Boolean = false)
    class Trigger {
        var count = 0
        fun fire() { count++ }
        fun trigger() { count++ }
    }
    class NumberProperty(var value: Any = 0f)

    class Pawn {
        val open = Switch()
        val complete = Trigger()
        fun getBooleanProperty(name: String) = open.takeIf { name == "promotion_in_progress_db_bool" }
        fun getTriggerProperty(name: String) = complete.takeIf { name == "complete_promotion_db_trig" }
    }

    class PieceList(private val pawns: List<Pawn>) {
        fun get(index: Int) = pawns[index]
    }

    class Bindings {
        val pawns = listOf(Pawn(), Pawn())
        val choice = NumberProperty()
        fun getListProperty(name: String) = PieceList(pawns).takeIf { name == "pieces" }
        fun getNumberProperty(name: String) = choice.takeIf { name == "promoting_piece_type_db_num" }
    }

    class Machine(val viewModelInstance: Bindings?) {
        fun getName() = "chess_statemachine"
    }

    class Artboard(val viewModelInstance: Bindings?) {
        fun input(name: String, path: String) = Trigger().takeIf { name == "promote_trigger_n" && path == "W_P_2" }
    }

    class Controller(val activeArtboard: Artboard)

    class View(bindings: Bindings? = null) {
        val controller = Controller(Artboard(bindings))
        val stateMachines = listOf(Machine(bindings))
        val queued = mutableListOf<Pair<String, String>>()
        fun fireStateAtPath(name: String, path: String) {
            queued.add(name to path)
        }
    }

    @Test fun legacyFiresTheRequestedChoiceOnTheSelectedPawn() {
        val view = View()
        val menu = RivePromotionMenu(null)
        val choice = menu.choice(view, "W_P_2", 'n') as RivePromotionMenu.Choice.Legacy
        choice.select()
        assertEquals(listOf("promote_trigger_n" to "W_P_2"), view.queued)
        assertNull(menu.choice(view, "W_P_1", 'n'))
    }

    @Test fun dataBindingDismissesOnlyTheOpenedPawnMenuAndKeepsItsPieceValue() {
        val bindings = Bindings()
        val view = View(bindings)
        val menu = RivePromotionMenu(mapOf("W_P_1" to 0, "W_P_2" to 1))
        bindings.pawns[0].open.value = true
        assertNull(menu.choice(view, "W_P_2", 'n'))
        bindings.pawns[1].open.value = true
        val choice = menu.choice(view, "W_P_2", 'n') as RivePromotionMenu.Choice.DataBound
        choice.finish(2)
        assertEquals(2f, bindings.choice.value)
        assertEquals(0, bindings.pawns[0].complete.count)
        assertEquals(1, bindings.pawns[1].complete.count)
    }
}
