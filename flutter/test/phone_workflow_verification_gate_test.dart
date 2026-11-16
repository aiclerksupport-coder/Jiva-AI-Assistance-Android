import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/phone_workflow.dart';

void main() {
  test(
    'unverified action does not advance and accepts only matching operation ID',
    () {
      final flow = PhoneWorkflow.fromJson([
        {'action': 'click', 'label': 'Settings'},
        {'action': 'scroll_forward'},
      ]);
      final session = PhoneWorkflowSession(flow);
      session.beginStep();
      final id = session.operationId;
      session.acceptResult(
        success: true,
        status: 'old result',
        resultOperationId: '0' * 32,
      );
      expect(session.index, 0);
      expect(session.awaitingResult, true);
      session.acceptResult(
        success: false,
        status: 'Action outcome unverified.',
        resultOperationId: id,
      );
      expect(session.index, 0);
      expect(session.awaitingResult, false);
      session.beginStep();
      session.acceptResult(
        success: true,
        status: 'Verified target screen changed.',
        resultOperationId: session.operationId,
      );
      expect(session.index, 1);
    },
  );

  test('sensitive actions are never automatic', () {
    final flow = PhoneWorkflow.fromJson([
      {'action': 'click', 'label': 'Delete'},
      {'action': 'type', 'label': 'some text'},
      {'action': 'scroll_forward'},
    ]);
    expect(flow.steps[0].needsExtraApproval, isTrue);
    expect(flow.steps[1].needsExtraApproval, isTrue);
    expect(flow.steps[2].needsExtraApproval, isFalse);
  });
}
