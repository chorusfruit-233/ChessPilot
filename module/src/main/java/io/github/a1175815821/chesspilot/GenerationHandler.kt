package io.github.a1175815821.chesspilot

import android.os.Handler
import android.os.Looper
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Serializes posting with retirement so old generations cannot enqueue late work. */
internal class GenerationHandler(looper: Looper) {
    private val handler = Handler(looper)
    private var closed = false

    @Synchronized fun post(task: Runnable): Boolean = !closed && handler.post(task)
    @Synchronized fun postDelayed(task: Runnable, delay: Long): Boolean =
        !closed && handler.postDelayed(task, delay)

    @Synchronized fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        fun onMainAndWait(action: () -> Unit): Boolean {
            if (Looper.myLooper() == Looper.getMainLooper()) { action(); return true }
            val handler = Handler(Looper.getMainLooper())
            val task = FutureTask { action() }
            handler.post(task)
            return try { task.get(2, TimeUnit.SECONDS); true }
            catch (_: Exception) { handler.removeCallbacks(task); task.cancel(false); false }
        }
    }
}
