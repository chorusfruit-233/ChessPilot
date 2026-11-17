package io.github.a1175815821.chesspilot

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CallbackGateTest {
    @Test fun retirementRejectsNewWorkAndRefusesWhileAnOldCallbackIsRunning() {
        val gate = CallbackGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            gate.run {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(gate.closeAndAwait(20))
            gate.run { fail("A retired generation accepted another callback") }
        } finally {
            release.countDown()
            worker.join(2000)
        }
        assertFalse(worker.isAlive)
        assertTrue(gate.closeAndAwait(100))
        gate.run { fail("Draining old work reopened the generation") }
    }

    @Test fun throwingCallbacksAlsoReleaseTheirRetirementClaim() {
        val gate = CallbackGate()
        try {
            gate.run { throw IllegalStateException("target callback failed") }
            fail("Callback failure was swallowed")
        } catch (_: IllegalStateException) { }
        assertTrue(gate.closeAndAwait(0))
    }
}
