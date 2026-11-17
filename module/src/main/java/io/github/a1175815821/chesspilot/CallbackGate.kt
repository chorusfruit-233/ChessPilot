package io.github.a1175815821.chesspilot

import java.util.concurrent.TimeUnit

/** Stops new callbacks and drains already running module work before retiring code. */
internal class CallbackGate {
    private val monitor = Object()
    private var accepting = true
    private var inFlight = 0

    fun run(action: () -> Unit) {
        synchronized(monitor) {
            if (!accepting) return
            inFlight++
        }
        try { action() }
        finally {
            synchronized(monitor) {
                inFlight--
                if (inFlight == 0) monitor.notifyAll()
            }
        }
    }

    fun closeAndAwait(timeoutMs: Long): Boolean = synchronized(monitor) {
        accepting = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (inFlight > 0) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return@synchronized false
            try { TimeUnit.NANOSECONDS.timedWait(monitor, left) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@synchronized false
            }
        }
        true
    }
}
