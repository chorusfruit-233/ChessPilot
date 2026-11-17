package com.chessassist

import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors

/** Framework service belongs to the normal app process, never the injected module. */
object ModuleConnection {
    @Volatile private var service: XposedService? = null
    @Volatile private var apiLevel = 0
    @Volatile var status: String = "未连接，请在支持 API 102 的框架中启用 ChessPilot"
        private set
    @Volatile var busy = false
        private set
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "module-service").apply { isDaemon = true }
    }

    fun initialize() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                val api = bound.apiVersion
                if (api < apiLevel) return
                service = bound
                apiLevel = api
                val reload = if (api >= 102) "自动热重载已启用" else "请升级到支持 API 102 的框架"
                status = "${bound.frameworkName} · API $api · $reload"
            }
            override fun onServiceDied(dead: XposedService) {
                if (service === dead) {
                    service = null
                    apiLevel = 0
                    status = "框架连接已断开"
                    busy = false
                }
            }
        })
    }

    fun canReload(): Boolean = apiLevel >= 102 && service != null && !busy

    @Synchronized
    fun reloadUpdatedModule() {
        val current = service ?: return
        if (busy || apiLevel < 102) return
        busy = true
        status = "正在检查多邻国模块更新…"
        worker.execute {
            try {
                val targets = current.runningTargets.filter { it.processName == "com.duolingo" }
                val pending = targets.filter { it.state == HookedTarget.State.STALE || it.state == HookedTarget.State.FAILED }
                if (pending.isEmpty()) {
                    status = if (targets.isEmpty()) "多邻国尚未加载本模块，请确认作用域后打开多邻国"
                        else "多邻国已使用当前版本，无需热重载"
                    busy = false
                } else {
                    val remaining = java.util.concurrent.atomic.AtomicInteger(pending.size)
                    for (target in pending) current.hotReloadModule(target, null) { _, result ->
                        status = when (result.status()) {
                            HotReloadResult.Status.SUCCEEDED -> "模块热重载成功"
                            HotReloadResult.Status.UNSUPPORTED -> "框架不支持此目标的热重载，请重启多邻国"
                            HotReloadResult.Status.IN_PROGRESS -> "模块正在热重载，请稍后查看"
                            HotReloadResult.Status.PROCESS_DIED -> "多邻国进程已退出，重新打开即可加载新版"
                            HotReloadResult.Status.FAILED -> "热重载失败：${result.message() ?: "旧模块未能完成清理，请重启多邻国"}"
                        }
                        if (remaining.decrementAndGet() == 0) busy = false
                    }
                }
            } catch (error: Exception) {
                status = "热重载请求失败：${error.message?.take(100)}"
                busy = false
            }
        }
    }
}
