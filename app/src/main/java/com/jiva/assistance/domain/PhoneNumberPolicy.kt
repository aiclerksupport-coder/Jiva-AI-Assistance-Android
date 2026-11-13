package com.jiva.assistance.domain

/** Only ordinary telephone numbers, not MMI/USSD sequences or URI payloads. */
object PhoneNumberPolicy {
    fun isSafe(value: String?): Boolean {
        val number = value?.trim().orEmpty()
        if (number.isEmpty() || number.length > 32) return false
        if (!Regex("^\\+?[0-9 ()\\-.]+$").matches(number)) return false
        val digits = number.count(Char::isDigit)
        return digits in 3..15 && number.count { it == '+' } <= 1
    }
}
