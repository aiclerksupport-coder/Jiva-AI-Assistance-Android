import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/phone_workflow.dart';

void main() {
  test('validates the strict allowlist and upper bound', () {
    expect(() => PhoneWorkflow.fromJson([]), throwsFormatException);
    expect(
      () => PhoneWorkflow.fromJson(List.generate(9, (_) => {'action': 'read'})),
      throwsFormatException,
    );
    expect(
      () => PhoneWorkflow.fromJson([
        {'action': 'type'},
      ]),
      throwsFormatException,
    );
    expect(
      () => PhoneWorkflow.fromJson([
        {'action': 'click'},
      ]),
      throwsFormatException,
    );
    expect(
      () => PhoneWorkflow.fromJson([
        {'action': 'read', 'label': 'secret'},
      ]),
      throwsFormatException,
    );
    expect(
      () => PhoneWorkflow.fromJson([
        {'action': 'click', 'label': 'a\nb'},
      ]),
      throwsFormatException,
    );
  });

  test('requires verified native result before advancing', () {
    final flow = PhoneWorkflow.fromJson([
      {'action': 'read'},
      {'action': 'click', 'label': 'Settings'},
    ]);
    final session = PhoneWorkflowSession(flow);
    session.beginStep();
    expect(session.index, 0);
    expect(() => session.beginStep(), throwsStateError);
    session.acceptResult(
      success: false,
      status: 'No target',
      resultOperationId: session.operationId,
    );
    expect(session.index, 0);
    session.beginStep();
    session.acceptResult(
      success: true,
      status: 'Screen read locally.',
      resultOperationId: session.operationId,
    );
    expect(session.index, 1);
    session.beginStep();
    session.acceptResult(
      success: true,
      status: 'Visible control clicked.',
      resultOperationId: session.operationId,
    );
    expect(session.isComplete, true);
  });

  test('cancel never advances and sensitive actions are flagged', () {
    final flow = PhoneWorkflow.fromJson([
      {'action': 'click', 'label': 'Pay now'},
    ]);
    expect(flow.steps.single.needsExtraApproval, true);
    final session = PhoneWorkflowSession(flow)..beginStep();
    session.cancel();
    session.acceptResult(
      success: true,
      status: 'late result',
      resultOperationId: session.operationId,
    );
    expect(session.index, 0);
    expect(session.nextStep, isNull);
  });
}
