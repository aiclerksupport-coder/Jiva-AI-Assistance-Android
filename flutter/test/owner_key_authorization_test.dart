import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/services/owner_key_authorization.dart';

void main() {
  test('save and remove each request fresh owner proof', () async {
    var count = 0;
    final guard = OwnerKeyAuthorization(
      authenticate: (action) async {
        count++;
        return true;
      },
    );
    expect(await guard.confirm('save'), isTrue);
    expect(await guard.confirm('remove'), isTrue);
    expect(count, 2);
  });

  test('device refusal and platform error fail closed', () async {
    final refused = OwnerKeyAuthorization(authenticate: (_) async => false);
    expect(await refused.confirm('save'), isFalse);
    final error = OwnerKeyAuthorization(authenticate: (_) async {
      throw StateError('Android biometric prompt unavailable');
    });
    expect(await error.confirm('remove'), isFalse);
  });

  test('simultaneous requests never share one approval', () async {
    final pending = Completer<bool>();
    var invocations = 0;
    final guard = OwnerKeyAuthorization(authenticate: (_) {
      invocations++;
      return pending.future;
    });
    final first = guard.confirm('save');
    expect(await guard.confirm('remove'), isFalse);
    pending.complete(true);
    expect(await first, isTrue);
    expect(invocations, 1);
  });

  test('unknown key action cannot open authentication', () async {
    final guard = OwnerKeyAuthorization(authenticate: (_) async => true);
    await expectLater(guard.confirm('read'), throwsArgumentError);
  });
}
