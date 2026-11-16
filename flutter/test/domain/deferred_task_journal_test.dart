import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/deferred_task.dart';

void main() {
  final created = DateTime.utc(2026, 11, 17);
  DeferredTask task(String id) => DeferredTask(
    id: id,
    type: DeferredTaskType.localCacheValidation,
    status: DeferredTaskStatus.pending,
    createdAtUtc: created,
  );

  test('background task cannot lease without foreground approval', () {
    final j = DeferredTaskJournal()..add(task('task_0001'));
    expect(j.lease('task_0001', appVisible: false, userApproved: true), isNull);
    expect(j.lease('task_0001', appVisible: true, userApproved: false), isNull);
    expect(j.tasks.single.status, DeferredTaskStatus.pending);
  });
  test('uncertain lease never replays after process restart', () {
    final j = DeferredTaskJournal()..add(task('task_0002'));
    expect(
      j.lease('task_0002', appVisible: true, userApproved: true),
      isNotNull,
    );
    final restored = DeferredTaskJournal()..restore(j.snapshot());
    expect(restored.tasks.single.status, DeferredTaskStatus.needsReview);
    expect(
      restored.lease('task_0002', appVisible: true, userApproved: true),
      isNull,
    );
  });
  test('duplicate ids rejected', () {
    final j = DeferredTaskJournal()..add(task('task_0003'));
    expect(() => j.add(task('task_0003')), throwsStateError);
  });
}
