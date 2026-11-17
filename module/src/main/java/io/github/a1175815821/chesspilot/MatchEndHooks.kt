package io.github.a1175815821.chesspilot

import io.github.libxposed.api.XposedInterface

/** Observe the handlers used by the live match, without modifying its results. */
internal class MatchEndHooks(
    private val table: Remap, private val loader: ClassLoader, private val bridge: StateBridge,
) {
    private val controllerGetter = table.methodNames("sessionController").single()

    fun install() {
        before("botSession", "botGameEnd", 8) { chain ->
            val mask = chain.getArg(7) as Int
            val serverReason = if (mask and 8 == 0) name(chain.getArg(4)) else null
            val clientReason = if (mask and 16 == 0) name(chain.getArg(5)) else null
            val owner = chain.getArg(0)?.let { Reflect.callMethod(it, controllerGetter) }
            bridge.onGameEnd(owner, GameEnd(serverReason ?: clientReason ?: "COMPLETED", name(chain.getArg(1))))
        }
        before("botSession", "botExit", 1) { chain ->
            val reason = name(chain.getArg(0)) ?: return@before
            bridge.onGameEnd(controllerOf(chain), GameEnd(reason))
        }
        before("pvpSession", "pvpGameEnd", 2) { chain ->
            val data = chain.getArg(0) ?: return@before
            val reason = name(Reflect.getObjectField(data, "b")) ?: "COMPLETED"
            val winner = name(Reflect.getObjectField(data, "a"))
            val player = chain.thisObject?.let { name(Reflect.getObjectField(it, "L")) }
            val outcome = when {
                winner == "DRAW" -> "DRAW"
                winner in listOf("WHITE", "BLACK") && player != null -> if (winner == player) "WIN" else "LOSS"
                else -> winner
            }
            bridge.onGameEnd(controllerOf(chain), GameEnd(reason, outcome))
        }
        before("miniGame", "miniGameEnd", 2) { chain ->
            val owner = chain.thisObject?.let { Reflect.getObjectField(it, "a") }
            bridge.onGameEnd(owner, GameEnd(name(chain.getArg(1)) ?: "COMPLETED", name(chain.getArg(0))))
        }
        before("miniSession", "miniSessionEnd", 0) { chain ->
            val clock = chain.thisObject?.let { Reflect.getObjectField(it, "z") }
            val reason = if (clock != null && Reflect.getIntField(clock, "j") <= 0) "TIMEOUT" else "COMPLETED"
            bridge.onGameEnd(controllerOf(chain), GameEnd(reason), onlyIfPlaying = true)
        }
        before("chessSession", "sessionCleared", 0) { chain ->
            bridge.onGameEnd(controllerOf(chain), GameEnd("LEFT_BOARD"))
        }
    }

    private fun before(key: String, method: String, args: Int, callback: (XposedInterface.Chain) -> Unit) {
        try {
            val type = table.resolveClass(loader, key) ?: error("class $key unavailable")
            val name = table.methodNames(method).single()
            val executable = type.declaredMethods.single { it.name == name && it.parameterCount == args }
            HookRuntime.before(executable, method, callback)
        } catch (e: Exception) {
            MainHook.log("match lifecycle hook $method unavailable: $e")
        }
    }

    private fun controllerOf(chain: XposedInterface.Chain): Any? =
        chain.thisObject?.let { Reflect.callMethod(it, controllerGetter) }

    private fun name(value: Any?): String? = (value as? Enum<*>)?.name
}
