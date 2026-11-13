enum AssistantAction {
  chat,
  openApp,
  call,
  sms,
  whatsappShare,
  reminder,
  openSettings,
  camera,
  phoneNavigation,
}

const supportedPhoneNavigationActions = <String>{
  'home',
  'back',
  'recents',
  'notifications',
  'quick_settings',
};

AssistantAction actionFromName(String? value) {
  return switch (value?.toUpperCase()) {
    'OPEN_APP' => AssistantAction.openApp,
    'CALL' => AssistantAction.call,
    'SMS' => AssistantAction.sms,
    'WHATSAPP_SHARE' => AssistantAction.whatsappShare,
    'REMINDER' => AssistantAction.reminder,
    'OPEN_SETTINGS' => AssistantAction.openSettings,
    'CAMERA' => AssistantAction.camera,
    'PHONE_NAVIGATION' => AssistantAction.phoneNavigation,
    _ => AssistantAction.chat,
  };
}

class IntentPlan {
  const IntentPlan({
    required this.action,
    required this.reply,
    this.appName,
    this.recipientQuery,
    this.phoneNumber,
    this.message,
    this.settingsTarget,
    this.phoneAction,
  });

  final AssistantAction action;
  final String reply;
  final String? appName;
  final String? recipientQuery;
  final String? phoneNumber;
  final String? message;
  final String? settingsTarget;
  final String? phoneAction;

  IntentPlan copyWith({
    String? appName,
    String? phoneNumber,
    String? message,
    String? phoneAction,
  }) =>
      IntentPlan(
        action: action,
        reply: reply,
        appName: appName ?? this.appName,
        recipientQuery: recipientQuery,
        phoneNumber: phoneNumber ?? this.phoneNumber,
        message: message ?? this.message,
        settingsTarget: settingsTarget,
        phoneAction: phoneAction ?? this.phoneAction,
      );
}

class ReminderItem {
  const ReminderItem({
    required this.id,
    required this.text,
    required this.triggerAt,
  });

  final int id;
  final String text;
  final DateTime triggerAt;
}
