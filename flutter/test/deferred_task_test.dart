import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/deferred_task.dart';

void main() {
  test('no unattended job lease', () {
    final queue = DeferredTaskJournal();
    queue.add(
      DeferredTask(
        id: 'task_0001',
        type: DeferredTaskType.localCacheValidation,
        status: DeferredTaskStatus.pending,
        createdAtUtc: DateTime.utc(2026, 11, 17),
      ),
    );
    expect(
      queue.lease('task_0001', appVisible: false, userApproved: true),
      isNull,
    );
    expect(
      queue.lease('task_0001', appVisible: true, userApproved: false),
      isNull,
    );
    expect(
      queue.lease('task_0001', appVisible: true, userApproved: true),
      isNotNull,
    );
    expect(queue.complete('task_0001'), isTrue);
    expect(queue.complete('task_0001'), isFalse);
  });
  test('restart retains uncertain work for review', () {
    final queue = DeferredTaskJournal();
    queue.restore([
      {
        'id': 'task_0002',
        'type': 'localReminderRefresh',
        'status': 'leased',
        'createdAtUtc': '2026-11-17T00:00:00.000Z',
        'attempts': 1,
      },
    ]);
    expect(queue.tasks.single.status, DeferredTaskStatus.needsReview);
  });
}
