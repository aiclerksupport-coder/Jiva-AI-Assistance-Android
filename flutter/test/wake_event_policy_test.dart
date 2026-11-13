import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/wake_event_policy.dart';

void main() {
  const valid = {
    'enabled': true,
    'active': true,
    'pending': false,
    'busy': false,
    'listening': false,
    'hasActionPlan': false,
  };

  bool allowed(Map<String, bool> fields) => WakeEventPolicy.shouldQueue(
        enabled: fields['enabled']!,
        active: fields['active']!,
        pending: fields['pending']!,
        busy: fields['busy']!,
        listening: fields['listening']!,
        hasActionPlan: fields['hasActionPlan']!,
      );

  test('accepts one idle armed wake callback', () {
    expect(allowed(valid), isTrue);
  });

  test('rejects duplicate, busy, listening and pending-confirmation wakes', () {
    for (final key in ['pending', 'busy', 'listening', 'hasActionPlan']) {
      expect(allowed({...valid, key: true}), isFalse, reason: key);
    }
  });

  test('disabled or stopped wake listener cannot create a capture', () {
    expect(allowed({...valid, 'enabled': false}), isFalse);
    expect(allowed({...valid, 'active': false}), isFalse);
  });
}
