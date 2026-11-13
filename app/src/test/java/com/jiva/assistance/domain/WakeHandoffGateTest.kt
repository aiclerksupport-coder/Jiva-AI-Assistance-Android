package com.jiva.assistance.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeHandoffGateTest {
    @Test fun duplicateDetectionsOnlyClaimOnceUntilResume() {
        val gate = WakeHandoffGate()
        assertTrue(gate.claim())
        assertFalse(gate.claim())
        gate.arm()
        assertTrue(gate.claim())
        assertFalse(gate.claim())
    }

    @Test fun stoppingOrPausingDisarmsUntilExplicitStart() {
        val gate = WakeHandoffGate()
        gate.disarm()
        assertFalse(gate.claim())
        gate.arm()
        assertTrue(gate.claim())
    }

    @Test fun simultaneousCallbacksCannotBothClaim() {
        val gate = WakeHandoffGate()
        val start = java.util.concurrent.CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = (0 until 12).map {
            Thread {
                start.await()
                results.add(gate.claim())
            }.also(Thread::start)
        }
        start.countDown()
        threads.forEach { it.join(3_000) }
        assertTrue(threads.none { it.isAlive })
        assertTrue(results.size == 12)
        assertTrue(results.count { it } == 1)
    }
}
