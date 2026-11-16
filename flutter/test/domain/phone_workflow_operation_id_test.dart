import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/phone_workflow.dart';

void main() {
  final data = [
    {'action': 'click', 'label': 'Settings'},
  ];
  test('stale result never completes the current action', () {
    final s = PhoneWorkflowSession(PhoneWorkflow.fromJson(data));
    s.beginStep();
    final first = s.operationId!;
    expect(first.length, 32);
    s.acceptResult(
      success: true,
      status: 'Clicked',
      resultOperationId: List.filled(32, '0').join(),
    );
    expect(s.index, 0);
    expect(s.awaitingResult, isTrue);
    s.acceptResult(success: true, status: 'Clicked', resultOperationId: first);
    expect(s.index, 1);
    expect(s.awaitingResult, isFalse);
  });
  test('restart with in-flight ID is uncertain and cannot auto-retry', () {
    final s = PhoneWorkflowSession(PhoneWorkflow.fromJson(data));
    s.beginStep();
    final saved = s.checkpoint();
    final restored = PhoneWorkflowSession.fromCheckpoint(saved);
    expect(restored.uncertainAfterRestart, isTrue);
    expect(() => restored.beginStep(), throwsStateError);
  });
  test('cancel invalidates previously pending action', () {
    final s = PhoneWorkflowSession(PhoneWorkflow.fromJson(data));
    s.beginStep();
    final id = s.operationId;
    s.cancel();
    s.acceptResult(success: true, status: 'Clicked', resultOperationId: id);
    expect(s.index, 0);
  });
}
