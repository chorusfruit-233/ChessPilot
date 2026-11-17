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
                            } else stateBridge.onFen(state, loader)
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
        for (method in type.declaredMethods) {
            if (stateType != null && method.returnType == stateType && method.parameterTypes.isEmpty()) {
                try {
                    HookRuntime.after(method, "render") { chain, result ->
                        BridgeAccess.boardController = chain.thisObject
                        if (result != null) {
                            stateBridge.onRenderedState(result)
                            arrows.onRenderedState(result)
                        }
                    }
                } catch (e: Exception) { log("hook controller getter failed: $e") }
            }
        }
        for (constructor in type.declaredConstructors) {
            try {
                HookRuntime.after(constructor, "controller") { chain, _ ->
                    val controller = chain.thisObject
                    if (controller != null) {
                        BridgeAccess.boardController = controller
                        BridgePublisher.resetPublishState()
                        RiveViewFinder.onControllerCreated(controller)
                    }
                }
            } catch (e: Exception) { log("hook controller constructor failed: $e") }
        }
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
                if (controller != null && remap?.resolveClass(loader, "boardController")?.isInstance(controller) == true) {
                    BridgeAccess.boardController = controller
                    RiveViewFinder.onControllerCreated(controller)
                }
                (saved?.get("view") as? View)?.let { RiveViewFinder.noteCtorInstance("hot-reload", it) }
                val state = saved?.get("state")
                if (state != null && remap?.resolveClass(loader, "chessGameState")?.isInstance(state) == true) {
                    bridge?.onRenderedState(state)
                } else if (saved != null) bridge?.restoreAfterReload(saved)
            }
            log("hot reload complete; initialized=${initialized.get()}, waiting for companion reconnect")
        } finally {
            // Stable ids replace surviving hooks atomically. Also retire removed
            // hooks after a partial initialization failure so they cannot retain old code.
            for (old in param.oldHookHandles) if (!HookRuntime.owns(old.id)) old.unhook()
        }
    }
}
