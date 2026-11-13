package com.jiva.assistance.domain

/** Actions listed here always go through a visible review/confirmation flow before dispatch. */
object ActionPolicy {
    fun requiresConfirmation(action: AssistantAction): Boolean = action != AssistantAction.CHAT

    fun canDispatch(plan: IntentPlan): Boolean = when (plan.action) {
        AssistantAction.CHAT -> false
        AssistantAction.CALL -> PhoneNumberPolicy.isSafe(plan.phoneNumber)
        AssistantAction.SMS -> PhoneNumberPolicy.isSafe(plan.phoneNumber)
        // Share sheets choose the destination inside the target app.
        // They do not use the optional contact number from the plan.
        AssistantAction.WHATSAPP_SHARE -> !plan.message.isNullOrBlank()
        AssistantAction.OPEN_APP -> !plan.appName.isNullOrBlank()
        AssistantAction.OPEN_SETTINGS, AssistantAction.CAMERA, AssistantAction.REMINDER -> true
        AssistantAction.PHONE_NAVIGATION ->
            plan.phoneAction?.let(PhoneNavigationActions.supported::contains) == true
    }
}
