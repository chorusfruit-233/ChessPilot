package io.github.a1175815821.chesspilot

import org.junit.Assert.*
import org.junit.Test

class ReflectTest {
    private open class Parent {
        private val payload = "fen"
        private val number = 2497
        private val ready = true
        private fun symbol() = "W"
    }
    private class Child : Parent()
    private class Square private constructor(private val row: Int, private val col: Int)
    private class Constants {
        companion object { @JvmField val COLOR = "GRAY" }
    }

    @Test fun privateInheritedStateAndMethodsRemainReadableWithoutLegacyHelpers() {
        val state = Child()
        assertEquals("fen", Reflect.getObjectField(state, "payload"))
        assertEquals(2497, Reflect.getIntField(state, "number"))
        assertTrue(Reflect.getBooleanField(state, "ready"))
        assertEquals("W", Reflect.callMethod(state, "symbol"))
        Reflect.clear()
        assertEquals("fen", Reflect.getObjectField(state, "payload"))
    }

    @Test fun mappedSquaresAndStaticConstantsAreResolvedWithTheirActualTypes() {
        val square = Reflect.newInstance(Square::class.java, 4, 7)
        assertEquals(4, Reflect.getIntField(square, "row"))
        assertEquals(7, Reflect.getIntField(square, "col"))
        assertEquals("GRAY", Reflect.getStaticObjectField(Constants::class.java, "COLOR"))
        assertEquals(Square::class.java, Reflect.findClass(Square::class.java.name, javaClass.classLoader!!))
    }

    @Test(expected = NoSuchFieldException::class)
    fun missingMappingsFailInsteadOfReadingAnUnrelatedField() {
        Reflect.getObjectField(Child(), "oldObfuscatedName")
    }
}
