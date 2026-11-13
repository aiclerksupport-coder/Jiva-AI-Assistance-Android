package com.jiva.assistance.domain

/** Explicitly models the boundary between supported Android hand-offs and unavailable silent control. */
enum class PhoneCapability {
    OPEN_INSTALLED_APP,
    OPEN_CAMERA,
    OPEN_SETTINGS_SCREEN,
    START_CALL_IN_DIALER,
    OPEN_MESSAGE_COMPOSER,
    WHATSAPP_SHARE_HANDOFF,
    SCHEDULE_LOCAL_REMINDER,
    VIEW_BASIC_DEVICE_INFO,
    CHANGE_PROTECTED_SETTING_DIRECTLY,
    READ_OTHER_APP_PRIVATE_DATA,
    PLACE_CALL_SILENTLY,
    SEND_MESSAGE_SILENTLY,
    CONTROL_ANY_APP_ARBITRARILY,
}

enum class CapabilityOutcome { AVAILABLE_WITH_CONFIRMATION, AVAILABLE_WITH_SYSTEM_PERMISSION, UNAVAILABLE_BY_DESIGN }

data class CapabilityDecision(
    val outcome: CapabilityOutcome,
    val explanation: String,
)

/**
 * Central product policy for phone features. This does not grant Android permissions; it prevents
 * Jiva from representing a hand-off as full control or treating a model-generated request as consent.
 */
object PhoneCapabilityPolicy {
    fun decision(capability: PhoneCapability): CapabilityDecision = when (capability) {
        PhoneCapability.OPEN_INSTALLED_APP -> CapabilityDecision(CapabilityOutcome.AVAILABLE_WITH_CONFIRMATION, "Open a user-selected app using its Android launcher intent.")
        PhoneCapability.OPEN_CAMERA -> CapabilityDecision(CapabilityOutcome.AVAILABLE_WITH_CONFIRMATION, "Open the camera app; the user operates it.")
        PhoneCapability.OPEN_SETTINGS_SCREEN -> CapabilityDecision(CapabilityOutcome.AVAILABLE_WITH_CONFIRMATION, "Open a public Android Settings screen; the user applies protected changes.")
        PhoneCapability.START_CALL_IN_DIALER -> CapabilityDecision(CapabilityOutcome.AVAILABLE_WITH_CONFIRMATION, "Open the system dialer; the user verifies the number and taps Call.")
        PhoneCapability.OPEN_MESSAGE_COMPOSER -> CapabilityDecision(CapabilityOutcome.AVAILABLE_WITH_CONFIRMATION, "Open a messaging composer; the user reviews and sends.")
        PhoneCapability.WHATSAPP_SHARE_HANDOFF -> CapabilityDecision(CapabilityOutcome.AVAILABLE_WITH_CONFIRMATION, "Hand off a draft; the user selects a chat and taps Send.")
        PhoneCapability.SCHEDULE_LOCAL_REMINDER -> CapabilityDecision(CapabilityOutcome.AVAILABLE_WITH_SYSTEM_PERMISSION, "Reminder delivery depends on notification permission and Android power-management behavior.")
        PhoneCapability.VIEW_BASIC_DEVICE_INFO -> CapabilityDecision(CapabilityOutcome.AVAILABLE_WITH_SYSTEM_PERMISSION, "Display basic documented device/OS information locally; disclose before sharing it.")
        PhoneCapability.CHANGE_PROTECTED_SETTING_DIRECTLY,
        PhoneCapability.READ_OTHER_APP_PRIVATE_DATA,
        PhoneCapability.PLACE_CALL_SILENTLY,
        PhoneCapability.SEND_MESSAGE_SILENTLY,
        PhoneCapability.CONTROL_ANY_APP_ARBITRARILY -> CapabilityDecision(CapabilityOutcome.UNAVAILABLE_BY_DESIGN, "A normal Android app cannot silently bypass Android, other apps, or the user's confirmation. Use supported system UI and permissions instead.")
    }

    /** The caller must pass explicit UI confirmation; model output is never consent. */
    fun mayDispatch(capability: PhoneCapability, userConfirmed: Boolean): Boolean {
        val result = decision(capability)
        return when (result.outcome) {
            CapabilityOutcome.AVAILABLE_WITH_CONFIRMATION -> userConfirmed
            CapabilityOutcome.AVAILABLE_WITH_SYSTEM_PERMISSION -> userConfirmed
            CapabilityOutcome.UNAVAILABLE_BY_DESIGN -> false
        }
    }
}
