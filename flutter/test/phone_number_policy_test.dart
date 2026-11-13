import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/phone_number_policy.dart';

void main() {
  test('ordinary dialer and SMS numbers are accepted', () {
    for (final n in ['9876543210', '+91 98765 43210', '(020) 123-4567']) {
      expect(PhoneNumberPolicy.isSafe(n), isTrue, reason: n);
    }
  });
  test('USSD/MMI, empty, URI payloads and malformed numbers rejected', () {
    for (final n in ['', ' ', '*21*123#', '#06#', 'tel:123456',
      '123;phone-context=bad', 'sms:123', 'ab12', '12']) {
      expect(PhoneNumberPolicy.isSafe(n), isFalse, reason: n);
    }
  });
}
