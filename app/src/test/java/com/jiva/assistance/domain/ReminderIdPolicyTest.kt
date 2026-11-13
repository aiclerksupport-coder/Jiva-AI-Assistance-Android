package com.jiva.assistance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReminderIdPolicyTest {
    @Test fun collidingIdsCannotOverwriteEarlierPendingAlarm() {
        val samples = ArrayDeque(listOf(42, 42, 88))
        assertEquals(88, ReminderIdPolicy.next(setOf(42)) { samples.removeFirst() })
    }

    @Test fun emptyAndNegativeDrawsIgnored() {
        val samples = ArrayDeque(listOf(0, -10, 19))
        assertEquals(19, ReminderIdPolicy.next(emptySet()) { samples.removeFirst() })
    }

    @Test fun repeatedCollisionUsesFirstAvailableFallback() {
        assertEquals(2, ReminderIdPolicy.next(setOf(1, 7)) { 7 })
        assertNotEquals(1, ReminderIdPolicy.next(setOf(1)) { 0 })
    }
}
