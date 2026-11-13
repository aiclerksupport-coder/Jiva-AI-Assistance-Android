import 'assistant_models.dart';

class LocalCommandParser {
  IntentPlan? parse(String raw) {
    final text = raw.trim().toLowerCase();
    if (text.isEmpty) return null;

    final phoneAction = _phoneAction(text);
    if (phoneAction != null) {
      return IntentPlan(
        action: AssistantAction.phoneNavigation,
        reply: 'Open the ${phoneAction.replaceAll('_', ' ')} panel or screen?',
        phoneAction: phoneAction,
      );
    }

    if (_contains(text, const [
      'remind',
      'reminder',
      'याद दिला',
      'याद दिलाना',
      'रिमाइंडर',
      'recordatorio',
      'rappel',
      '提醒',
      'リマインダー',
    ])) {
      return const IntentPlan(
        action: AssistantAction.reminder,
        reply: "Let's set a reminder. / Reminder ka time set karein.",
      );
    }

    if (_contains(text, const ['whatsapp', 'व्हाट्सएप', 'واتساب']) &&
        _contains(text, const [
          'message',
          'send',
          'मैसेज',
          'भेज',
          'mensaje',
          'enviar',
          '发',
          '送',
        ])) {
      return IntentPlan(
        action: AssistantAction.whatsappShare,
        reply:
            'Choose the contact and review the message in WhatsApp before sending.',
        recipientQuery: _extractRecipient(raw),
        message: _extractMessage(raw),
      );
    }

    if (_contains(text, const [
      'call',
      'phone',
      'कॉल',
      'फोन',
      'llama',
      'llamar',
      'anrufen',
      '打电话',
    ])) {
      return IntentPlan(
        action: AssistantAction.call,
        reply: 'Enter a phone number. Jiva will open the dialer for your review.',
        recipientQuery: _extractRecipient(raw),
      );
    }

    if (_contains(text, const [
      'message',
      'sms',
      'मैसेज',
      'संदेश',
      'भेजो',
      'mensaje',
      '短信',
      'send text',
    ])) {
      return IntentPlan(
        action: AssistantAction.sms,
        reply: 'Enter a phone number and review the message before sending.',
        recipientQuery: _extractRecipient(raw),
        message: _extractMessage(raw),
      );
    }

    if (_contains(text, const [
      'settings',
      'setting',
      'सेटिंग',
      'configuración',
      'paramètres',
      '设置',
      '設定',
    ])) {
      final target = _contains(text, const ['wifi', 'wi-fi', 'वाईफाई', 'वाइफाइ'])
          ? 'wifi'
          : _contains(text, const ['bluetooth', 'ब्लूटूथ'])
              ? 'bluetooth'
              : _contains(text, const ['display', 'screen', 'डिस्प्ले', 'स्क्रीन'])
                  ? 'display'
                  : _contains(text, const ['sound', 'volume', 'आवाज़', 'ध्वनि'])
                      ? 'sound'
                      : 'general';
      return IntentPlan(
        action: AssistantAction.openSettings,
        reply:
            'I can open Android settings. You approve any changes there.',
        settingsTarget: target,
      );
    }

    if (_contains(text, const ['camera', 'कैमरा', 'cámara', '相机'])) {
      return const IntentPlan(
        action: AssistantAction.camera,
        reply: 'Open the camera app?',
      );
    }

    if (_startsWith(text, const [
      'open ',
      'launch ',
      'start ',
      'खोलो ',
      'खोल ',
      'abre ',
      'abrir ',
      '開く ',
      '打开 ',
    ])) {
      final app = raw
          .trim()
          .replaceFirst(
            RegExp(
              r'^(open|launch|start|खोलो|खोल|abre|abrir|開く|打开)\s+',
              caseSensitive: false,
            ),
            '',
          )
          .trim();
      if (app.isEmpty) return null;
      return IntentPlan(
        action: AssistantAction.openApp,
        reply: "I'll look for that installed app.",
        appName: app,
      );
    }
    return null;
  }

  static bool _contains(String text, List<String> phrases) =>
      phrases.any(text.contains);

  static bool _startsWith(String text, List<String> phrases) =>
      phrases.any(text.startsWith);

  static String? _phoneAction(String text) {
    if ({'home', 'go home', 'home screen'}.contains(text) ||
        _contains(text, const ['go to home screen', 'होम स्क्रीन', 'होम पर जाओ'])) {
      return 'home';
    }
    if ({'back', 'go back'}.contains(text) ||
        _contains(text, const ['navigate back', 'वापस जाओ', 'पीछे जाओ'])) {
      return 'back';
    }
    if ({'recents', 'recent apps', 'show recent apps'}.contains(text) ||
        _contains(text, const ['हाल के ऐप', 'हालिया ऐप'])) {
      return 'recents';
    }
    if ({
          'notifications',
          'show notifications',
          'open notifications',
          'notification panel',
        }.contains(text) ||
        _contains(text, const [
          'notification shade',
          'notification panel',
          'सूचना पैनल',
          'नोटिफिकेशन',
        ])) {
      return 'notifications';
    }
    if ({'quick settings', 'open quick settings', 'control center'}
            .contains(text) ||
        _contains(text, const ['क्विक सेटिंग', 'कंट्रोल सेंटर'])) {
      return 'quick_settings';
    }
    return null;
  }

  static String? _extractRecipient(String raw) {
    final match = RegExp(
      r"""\b(?:to|call|message|text|send|फोन|कॉल|मैसेज|संदेश|भेजो|भेज)\s+([\p{L}][\p{L} .'-]{0,40})""",
      caseSensitive: false,
      unicode: true,
    ).firstMatch(raw);
    final value = match?.group(1)?.trim();
    return value == null || value.isEmpty ? null : value;
  }

  static String? _extractMessage(String raw) {
    final match = RegExp(
      r"""(?:saying|message|text|that|लिखो|लिखना|मैसेज)\s*[:：]?\s*["'](.+?)["']""",
      caseSensitive: false,
      unicode: true,
    ).firstMatch(raw);
    final value = match?.group(1)?.trim();
    return value == null || value.isEmpty ? null : value;
  }
}
