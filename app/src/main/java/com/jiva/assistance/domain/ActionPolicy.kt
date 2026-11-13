package com.jiva.assistance.domain

/** Actions listed here always go through a visible review/confirmation flow before dispatch. */
object ActionPolicy {
    fun requiresConfirmation(action: AssistantAction): Boolean = action != AssistantAction.CHAT

    fun canDispatch(plan: IntentPlan): Boolean = when (plan.action) {
        AssistantAction.CHAT -> false
        AssistantAction.CALL -> !plan.phoneNumber.isNullOrBlank()
        AssistantAction.SMS, AssistantAction.WHATSAPP_SHARE -> !plan.phoneNumber.isNullOrBlank()
        AssistantAction.OPEN_APP -> !plan.appName.isNullOrBlank()
        AssistantAction.OPEN_SETTINGS, AssistantAction.CAMERA, AssistantAction.REMINDER -> true
    }
}
