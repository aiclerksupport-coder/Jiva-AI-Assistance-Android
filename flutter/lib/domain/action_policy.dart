import 'assistant_models.dart';

class ActionPolicy {
  static bool requiresConfirmation(AssistantAction action) =>
      action != AssistantAction.chat;

  static bool canDispatch(IntentPlan plan) {
    switch (plan.action) {
      case AssistantAction.chat:
        return false;
      case AssistantAction.call:
      case AssistantAction.sms:
      case AssistantAction.whatsappShare:
        return plan.phoneNumber?.trim().isNotEmpty ?? false;
      case AssistantAction.openApp:
        return plan.appName?.trim().isNotEmpty ?? false;
      case AssistantAction.reminder:
      case AssistantAction.openSettings:
      case AssistantAction.camera:
        return true;
      case AssistantAction.phoneNavigation:
        return supportedPhoneNavigationActions.contains(plan.phoneAction);
    }
  }
}
