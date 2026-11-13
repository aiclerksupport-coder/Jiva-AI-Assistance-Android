import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/reminder_id_policy.dart';

void main() {
  test('colliding IDs cannot replace an existing notification', () {
    final samples = [12, 12, 87].iterator;
    final id = ReminderIdPolicy.next({12}, () {
      samples.moveNext();
      return samples.current;
    });
    expect(id, 87);
  });

  test('negative and zero ID candidates fail safely', () {
    var attempt = 0;
    expect(ReminderIdPolicy.next({}, () {
      attempt++;
      return attempt == 1 ? -7 : attempt == 2 ? 0 : 70;
    }), 70);
  });

  test('repeated collision deterministically finds free ID', () {
    expect(ReminderIdPolicy.next({1, 3}, () => 3), 2);
  });
}
