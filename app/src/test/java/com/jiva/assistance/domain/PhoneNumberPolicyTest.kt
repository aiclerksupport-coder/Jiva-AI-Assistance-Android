package com.jiva.assistance.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberPolicyTest {
    @Test fun acceptsOrdinaryContactNumbers() {
        for (value in listOf("9876543210", "+91 98765 43210", "(020) 123-4567")) {
            assertTrue(value, PhoneNumberPolicy.isSafe(value))
        }
    }
    @Test fun rejectsUSSDOrUriPayloadOrEmptyNumber() {
        for (value in listOf("", " ", "*21*123#", "#06#", "tel:123456",
            "123;phone-context=bad", "sms:123", "hello", "12", "1".repeat(40))) {
            assertFalse(value, PhoneNumberPolicy.isSafe(value))
        }
    }
}
