package io.github.a1175815821.chesspilot

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Target-object reflection only; never calls or reflects on the Xposed API. */
internal object Reflect {
    private data class Key(val type: Class<*>, val name: String)
    private val fields = ConcurrentHashMap<Key, Field>()
    private val methods = ConcurrentHashMap<Key, Method>()

    fun findClass(name: String, loader: ClassLoader): Class<*> = Class.forName(name, false, loader)

    private fun field(type: Class<*>, name: String): Field = fields.getOrPut(Key(type, name)) {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return@getOrPut current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) { current = current.superclass }
        }
        throw NoSuchFieldException("${type.name}.$name")
    }

    fun getObjectField(obj: Any, name: String): Any? = field(obj.javaClass, name).get(obj)
    fun getIntField(obj: Any, name: String): Int = field(obj.javaClass, name).getInt(obj)
    fun getBooleanField(obj: Any, name: String): Boolean = field(obj.javaClass, name).getBoolean(obj)
    fun getStaticObjectField(type: Class<*>, name: String): Any? = field(type, name).get(null)

    fun callMethod(obj: Any, name: String): Any? {
        val method = methods.getOrPut(Key(obj.javaClass, name)) {
            var current: Class<*>? = obj.javaClass
            while (current != null) {
                try {
                    return@getOrPut current.getDeclaredMethod(name).apply { isAccessible = true }
                } catch (_: NoSuchMethodException) { current = current.superclass }
            }
            throw NoSuchMethodException("${obj.javaClass.name}.$name()")
        }
        return method.invoke(obj)
    }

    fun newInstance(type: Class<*>, row: Int, col: Int): Any =
        type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(row, col)

    fun clear() { fields.clear(); methods.clear() }
}
