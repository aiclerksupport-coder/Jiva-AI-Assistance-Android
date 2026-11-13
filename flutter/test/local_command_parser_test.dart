import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/action_policy.dart';
import 'package:jiva_assistance/domain/assistant_models.dart';
import 'package:jiva_assistance/domain/local_command_parser.dart';

void main() {
  final parser = LocalCommandParser();

  test('recognizes supported command types and preserves message draft', () {
    expect(parser.parse('Remind me tomorrow')?.action, AssistantAction.reminder);
    expect(
      parser.parse('Call Alex')?.action,
      AssistantAction.call,
    );
    final sms = parser.parse('Send message saying "See you soon"');
    expect(sms?.action, AssistantAction.sms);
    expect(sms?.message, 'See you soon');
  });

  test('unknown installed app name containing Local does not trigger a call', () {
    final plan = parser.parse('Open My Local Bank App');
    expect(plan?.action, AssistantAction.openApp);
    expect(plan?.appName, 'My Local Bank App');
  });

  test('call requires a standalone English command word', () {
    expect(parser.parse('Call Alex')?.action, AssistantAction.call);
    expect(parser.parse('Open PhonePe')?.action, AssistantAction.openApp);
    expect(parser.parse('Locally stored documents'), isNull);
  });

  test('unknown text falls through for Gemini interpretation', () {
    expect(parser.parse('What is the weather tomorrow?'), isNull);
  });

  test('open command extracts app name', () {
    final plan = parser.parse('Open Maps');
    expect(plan?.action, AssistantAction.openApp);
    expect(plan?.appName, 'Maps');
  });

  test('phone navigation is allowlisted and remains confirmation-gated', () {
    const commands = {
      'Go home': 'home',
      'Go back': 'back',
      'Recent apps': 'recents',
      'Show notifications': 'notifications',
      'Open quick settings': 'quick_settings',
    };
    for (final entry in commands.entries) {
      final parsed = parser.parse(entry.key);
      expect(parsed?.action, AssistantAction.phoneNavigation);
      expect(parsed?.phoneAction, entry.value);
    }

    final plan = parser.parse('Show notifications');
    expect(plan?.action, AssistantAction.phoneNavigation);
    expect(plan?.phoneAction, 'notifications');
    expect(ActionPolicy.requiresConfirmation(plan!.action), isTrue);
    expect(ActionPolicy.canDispatch(plan), isTrue);
    expect(
      ActionPolicy.canDispatch(
        const IntentPlan(
          action: AssistantAction.phoneNavigation,
          reply: 'Navigate',
          phoneAction: 'install_app',
        ),
      ),
      isFalse,
    );
    expect(parser.parse('I need help with homework'), isNull);
  });


  test('phone handoff policy rejects USSD and accepts share drafts', () {
    expect(ActionPolicy.canDispatch(const IntentPlan(
      action: AssistantAction.call,
      reply: 'dial',
      phoneNumber: '*21*123#',
    )), isFalse);
    expect(ActionPolicy.canDispatch(const IntentPlan(
      action: AssistantAction.sms,
      reply: 'message',
      phoneNumber: '+91 98765 43210',
    )), isTrue);
    expect(ActionPolicy.canDispatch(const IntentPlan(
      action: AssistantAction.whatsappShare,
      reply: 'share',
      message: 'Namaste bhidu',
    )), isTrue);
    expect(ActionPolicy.canDispatch(const IntentPlan(
      action: AssistantAction.whatsappShare,
      reply: 'share',
      message: '  ',
    )), isFalse);
  });

  test('maps Gemini action names to safe local enum values', () {
    expect(actionFromName('OPEN_APP'), AssistantAction.openApp);
    expect(actionFromName('WHATSAPP_SHARE'), AssistantAction.whatsappShare);
    expect(actionFromName('unknown-action'), AssistantAction.chat);
  });
}
