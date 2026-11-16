import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/phone_workflow.dart';

void main() {
  test('local LLM cannot inject unsupported shell or send action', () {
    expect(
      () => PhoneWorkflow.fromJson([
        {'action': 'shell', 'label': 'rm -rf'},
      ]),
      throwsFormatException,
    );
    expect(
      () => PhoneWorkflow.fromJson([
        {'action': 'send', 'label': 'Hello'},
      ]),
      throwsFormatException,
    );
  });

  test('local planner is limited to eight verified actions', () {
    expect(
      () => PhoneWorkflow.fromJson(List.generate(9, (_) => {'action': 'read'})),
      throwsFormatException,
    );
    final plan = PhoneWorkflow.fromJson([
      {'action': 'read'},
      {'action': 'scroll_forward'},
      {'action': 'back'},
    ]);
    expect(plan.steps.length, 3);
  });

  test('planned click remains unexecuted until a matching native result', () {
    final session = PhoneWorkflowSession(
      PhoneWorkflow.fromJson([
        {'action': 'click', 'label': 'Settings'},
      ]),
    );
    expect(session.index, 0);
    session.beginStep();
    session.acceptResult(
      success: true,
      status: 'stale',
      resultOperationId: '0' * 32,
    );
    expect(session.index, 0);
    session.acceptResult(
      success: true,
      status: 'verified',
      resultOperationId: session.operationId,
    );
    expect(session.index, 1);
  });
}
