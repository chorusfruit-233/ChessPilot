package io.github.a1175815821.chesspilot

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import java.util.HashMap
import java.util.concurrent.atomic.AtomicBoolean

/** API 102 entry; this single entry is also the unit of hot reload. */
class MainHook : XposedModule() {
    companion object {
        private const val TARGET = "com.duolingo"
        fun log(message: String) = HookRuntime.log(message)
    }

    private val initialized = AtomicBoolean(false)
    private var targetLoader: ClassLoader? = null
    private var bridge: StateBridge? = null
    private var painter: ArrowPainter? = null
    private var remap: Remap? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.processName != TARGET || param.isSystemServer) { detach(); return }
        HookRuntime.activate(this)
        log("loaded ${param.processName}, libxposed API $apiVersion")
        hookApplicationAttach()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET || !param.isFirstPackage) return
        HookRuntime.runIfActive {
            targetLoader = param.classLoader
            currentApplication()?.let { initialize(it, param.classLoader) }
        }
    }

    private fun hookApplicationAttach() {
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        HookRuntime.after(attach, "attach") { chain, _ ->
            val context = chain.getArg(0) as Context
            initialize(context, targetLoader ?: context.classLoader)
        }
    }

    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            .invoke(null) as? Application
    } catch (_: Exception) { null }

    private fun initialize(context: Context, loader: ClassLoader) {
        if (!initialized.compareAndSet(false, true)) return
        BridgeAccess.context = context.applicationContext ?: context
        targetLoader = loader
        val table = Remap.fromBaked().also { remap = it }
        val stateBridge = StateBridge().also { bridge = it }
        val arrowPainter = ArrowPainter(table).also { painter = it }
        log("remap table v${RemapTable.APP_VERSION}")
        hookModels(loader, table, stateBridge, arrowPainter)
        hookController(loader, table, stateBridge, arrowPainter)
        MatchEndHooks(table, loader, stateBridge).install()
        hookRiveViews(loader)
        HookRuntime.after(Activity::class.java.getDeclaredMethod("onPause"), "pause") { _, _ ->
            OverlayFallback.remove()
        }
        ViewModelFallback.hook(loader, stateBridge)
        CommandLoop.configure(table, stateBridge, arrowPainter)
    }

    private fun hookModels(loader: ClassLoader, table: Remap, stateBridge: StateBridge, arrows: ArrowPainter) {
        for (key in listOf("chessGameState", "chessFen")) {
            val type = table.resolveClass(loader, key)
            if (type == null) { log("!! $key not found — check remap.json"); continue }
            for (constructor in type.declaredConstructors) {
                try {
                    HookRuntime.after(constructor, key) { chain, _ ->
                        val state = chain.thisObject
                        if (state != null) {
                            if (key == "chessGameState") {
                                stateBridge.onGameState(state)
                                arrows.onGameState(state)
                            } else stateBridge.onFen(state)
                        }
                    }
                } catch (e: Exception) { log("hook $key constructor failed: $e") }
            }
            log("hooked $key = ${type.name}")
        }
    }

    private fun hookController(loader: ClassLoader, table: Remap, stateBridge: StateBridge, arrows: ArrowPainter) {
        val type = table.resolveClass(loader, "boardController")
        if (type == null) { log("!! BoardController not found"); return }
        val stateType = table.resolveClass(loader, "chessGameState")
        val liveGetter = table.methodNames("liveState").single()
        for (method in type.declaredMethods) {
            if (method.name == liveGetter && method.returnType == stateType && method.parameterCount == 0) {
                try {
                    HookRuntime.after(method, "render") { chain, result ->
                        val owner = chain.thisObject
                        if (owner != null && result != null && stateBridge.onRenderedState(owner, result)) {
                            arrows.onRenderedState(result)
                        }
                    }
                } catch (e: Exception) { log("hook controller getter failed: $e") }
            }
        }
        fun setupBoard(controller: Any, model: Any? = null, miniMatchId: String? = null) {
            val state = readLiveState(controller, liveGetter)
            if (stateBridge.onBoardSetup(controller, miniMatchId, model, state)) {
                RiveViewFinder.onControllerCreated(controller)
            }
        }
        try {
            val setupName = table.methodNames("setupBoard").single()
            val setup = table.resolveClass(loader, "setupModel") ?: return
            HookRuntime.after(type.getDeclaredMethod(setupName, setup), "board-setup") { chain, _ ->
                chain.thisObject?.let { setupBoard(it, model = chain.getArg(0)) }
            }
            val miniGame = table.resolveClass(loader, "miniGame") ?: return
            val miniStart = table.methodNames("miniGameStart").single()
            HookRuntime.after(miniGame.declaredMethods.single { it.name == miniStart && it.parameterCount == 1 },
                "mini-game-start") { chain, _ ->
                val game = chain.thisObject ?: return@after
                val params = Reflect.getObjectField(game, "g") ?: return@after
                val id = Reflect.getObjectField(params, "a") as? String ?: return@after
                val controller = Reflect.getObjectField(game, "a") ?: return@after
                setupBoard(controller, miniMatchId = id)
            }
        } catch (e: Exception) { log("hook board setup failed: $e") }
    }

    private fun readLiveState(controller: Any, getter: String): Any? = try {
        Reflect.callMethod(controller, getter)
    } catch (t: Throwable) {
        log("live board read failed: $t")
        null
    }

    private fun hookRiveViews(loader: ClassLoader) {
        for (name in listOf("app.rive.runtime.kotlin.RiveAnimationView",
            "com.duolingo.core.rive.RiveWrapperView2\$CallbackRiveAnimationView")) {
            try {
                for (constructor in Reflect.findClass(name, loader).declaredConstructors) {
                    HookRuntime.after(constructor, "rive") { chain, _ ->
                        (chain.thisObject as? View)?.let { RiveViewFinder.noteCtorInstance("ctor", it) }
                    }
                }
            } catch (e: Exception) { log("Rive view unavailable: $name (${e.javaClass.simpleName})") }
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val callbacksStopped = HookRuntime.retireAndAwait()
        CommandLoop.stop()
        painter?.close()
        val scanStopped = ViewModelFallback.stop()
        val bridgeStopped = BridgeClient.stopForReload()
        val overlayStopped = OverlayFallback.closeForReload()
        // Only bootstrap/framework/target objects may cross generation boundaries.
        val state = HashMap<String, Any>()
        bridge?.saveForReload(state)
        BridgeAccess.context?.let { state["context"] = it }
        targetLoader?.let { state["loader"] = it }
        BridgeAccess.boardController?.let { state["controller"] = it }
        BridgeAccess.lastState?.let { state["state"] = it }
        RiveViewFinder.bestRiveView()?.let { state["view"] = it }
        RiveViewFinder.clear()
        BridgeAccess.clear()
        Reflect.clear()
        if (!callbacksStopped || !scanStopped || !bridgeStopped || !overlayStopped) {
            log("hot reload refused: old tasks have not stopped; restart Duolingo")
            return false
        }
        param.setSavedInstanceState(state)
        log("hot reload: generation retired")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        if (param.processName != TARGET || param.isSystemServer) { detach(); return }
        HookRuntime.activate(this)
        try {
            val saved = param.savedInstanceState as? Map<*, *>
            val context = saved?.get("context") as? Context ?: currentApplication()
            targetLoader = saved?.get("loader") as? ClassLoader ?: context?.classLoader
            hookApplicationAttach()
            if (context != null && targetLoader != null) {
                initialize(context, targetLoader!!)
                val loader = targetLoader!!
                val controller = saved?.get("controller")
                val validController = controller?.takeIf { remap?.resolveClass(loader, "boardController")?.isInstance(it) == true }
                if (saved != null) bridge?.restoreAfterReload(saved, validController)
                if (validController != null) {
                    BridgeAccess.boardController = controller
                    RiveViewFinder.onControllerCreated(validController)
                }
                (saved?.get("view") as? View)?.let { RiveViewFinder.noteCtorInstance("hot-reload", it) }
                if (validController != null) {
                    val getter = remap?.methodNames("liveState")?.singleOrNull()
                    val state = getter?.let { readLiveState(validController, it) } ?: saved?.get("state")
                    if (state != null && remap?.resolveClass(loader, "chessGameState")?.isInstance(state) == true) {
                        bridge?.onRenderedState(validController, state)
                    }
                }
            }
            log("hot reload complete; initialized=${initialized.get()}, waiting for companion reconnect")
        } finally {
            // Stable ids replace surviving hooks atomically. Also retire removed
            // hooks after a partial initialization failure so they cannot retain old code.
            for (old in param.oldHookHandles) if (!HookRuntime.owns(old.id)) old.unhook()
        }
    }
}
