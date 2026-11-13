package com.jiva.assistance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCapabilityPolicyTest {
    @Test fun callAndMessagesAlwaysRequireExplicitUserConfirmation() {
        assertFalse(PhoneCapabilityPolicy.mayDispatch(PhoneCapability.START_CALL_IN_DIALER, userConfirmed = false))
        assertFalse(PhoneCapabilityPolicy.mayDispatch(PhoneCapability.OPEN_MESSAGE_COMPOSER, userConfirmed = false))
        assertTrue(PhoneCapabilityPolicy.mayDispatch(PhoneCapability.START_CALL_IN_DIALER, userConfirmed = true))
    }

    @Test fun silentOrArbitraryControlIsUnavailableEvenAfterConfirmation() {
        listOf(
            PhoneCapability.PLACE_CALL_SILENTLY,
            PhoneCapability.SEND_MESSAGE_SILENTLY,
            PhoneCapability.READ_OTHER_APP_PRIVATE_DATA,
            PhoneCapability.CONTROL_ANY_APP_ARBITRARILY,
            PhoneCapability.CHANGE_PROTECTED_SETTING_DIRECTLY,
        ).forEach { capability ->
            assertEquals(CapabilityOutcome.UNAVAILABLE_BY_DESIGN, PhoneCapabilityPolicy.decision(capability).outcome)
            assertFalse(PhoneCapabilityPolicy.mayDispatch(capability, userConfirmed = true))
        }
    }

    @Test fun reminderAvailabilityDoesNotPromiseDelivery() {
        val decision = PhoneCapabilityPolicy.decision(PhoneCapability.SCHEDULE_LOCAL_REMINDER)
        assertEquals(CapabilityOutcome.AVAILABLE_WITH_SYSTEM_PERMISSION, decision.outcome)
        assertTrue(decision.explanation.contains("depends"))
    }
}
