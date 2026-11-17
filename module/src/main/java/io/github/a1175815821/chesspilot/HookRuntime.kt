package io.github.a1175815821.chesspilot

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap

/** A generation owns its interceptors. Stable ids enable API 102 atomic replacement. */
internal object HookRuntime {
    @Volatile var active = false
        private set
    var api: XposedInterface? = null
        private set
    private val installedIds = ConcurrentHashMap.newKeySet<String>()
    private val callbacks = CallbackGate()

    fun activate(framework: XposedInterface) { api = framework; active = true }

    fun retireAndAwait(): Boolean {
        active = false
        return callbacks.closeAndAwait(1500)
    }

    fun runIfActive(action: () -> Unit) {
        if (active) callbacks.run(action)
    }

    fun after(executable: Executable, role: String, action: (XposedInterface.Chain, Any?) -> Unit) =
        install(executable, role, before = false, action)

    fun before(executable: Executable, role: String, action: (XposedInterface.Chain) -> Unit) =
        install(executable, role, before = true) { chain, _ -> action(chain) }

    private fun install(
        executable: Executable, role: String, before: Boolean, action: (XposedInterface.Chain, Any?) -> Unit,
    ) {
        val id = "chesspilot:$role:$executable"
        api!!.hook(executable).setId(id)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                fun invoke(result: Any?) {
                    runIfActive {
                        try { action(chain, result) }
                        catch (e: Exception) { MainHook.log("$role callback failed: $e") }
                    }
                }
                if (before) invoke(null)
                val result = chain.proceed()
                if (!before) invoke(result)
                result
            }
        installedIds.add(id)
    }

    fun owns(id: String?): Boolean = id != null && id in installedIds
    fun log(message: String) { api?.log(Log.INFO, "ChessPilot", message) }
}
