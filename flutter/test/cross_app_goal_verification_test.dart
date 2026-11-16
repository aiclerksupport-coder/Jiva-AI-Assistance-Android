import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/phone_workflow.dart';

void main() {
  test('cross-app workflows require explicit final observable goal', () {
    expect(
      () => PhoneWorkflow.fromJson([
        {'action': 'open_app', 'label': 'Calendar'},
        {'action': 'read'},
      ]),
      throwsFormatException,
    );
    expect(
      () => PhoneWorkflow.fromJson([
        {'action': 'verify_text', 'label': 'Saved'},
        {'action': 'read'},
      ]),
      throwsFormatException,
    );
    expect(
      () => PhoneWorkflow.fromJson([
        {'action': 'open_app', 'label': ''},
        {'action': 'verify_text', 'label': 'Done'},
      ]),
      throwsFormatException,
    );
  });

  test(
    'cross-app plan can only finish verified after matching operation IDs',
    () {
      final flow = PhoneWorkflow.fromJson([
        {'action': 'open_app', 'label': 'Calendar'},
        {'action': 'click', 'label': 'Today'},
        {'action': 'open_app', 'label': 'Notes'},
        {'action': 'verify_text', 'label': 'Task completed'},
      ]);
      final session = PhoneWorkflowSession(flow);
      for (var i = 0; i < 3; i++) {
        session.beginStep();
        final currentId = session.operationId;
        session.acceptResult(
          success: true,
          status: 'verified',
          resultOperationId: '00000000000000000000000000000000',
        );
        // Wrong operation ID never advances.
        if (session.operationId != '00000000000000000000000000000000') {
          expect(session.index, i);
        }
        session.acceptResult(
          success: true,
          status: 'verified',
          resultOperationId: currentId,
        );
      }
      expect(session.goalVerified, false);
      session.beginStep();
      final id = session.operationId;
      session.acceptResult(
        success: false,
        status: 'Not found',
        resultOperationId: id,
      );
      expect(session.isComplete, false);
      expect(session.goalVerified, false);
      session.beginStep();
      session.acceptResult(
        success: true,
        status: 'Verified final goal on target screen.',
        resultOperationId: session.operationId,
      );
      expect(session.isComplete, true);
      expect(session.goalVerified, true);
    },
  );

  test('crash during goal verification never blindly completes', () {
    final flow = PhoneWorkflow.fromJson([
      {'action': 'open_app', 'label': 'Notes'},
      {'action': 'verify_text', 'label': 'Done'},
    ]);
    final session = PhoneWorkflowSession(flow)..beginStep();
    final resumed = PhoneWorkflowSession.fromCheckpoint(session.checkpoint());
    expect(resumed.uncertainAfterRestart, true);
    expect(resumed.goalVerified, false);
    expect(() => resumed.beginStep(), throwsStateError);
  });
}
