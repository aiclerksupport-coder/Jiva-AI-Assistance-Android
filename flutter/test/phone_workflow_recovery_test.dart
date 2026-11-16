import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/phone_workflow.dart';
import 'package:jiva_assistance/domain/offline_phone_planner.dart';

void main() {
  test('checkpoint restores safe paused progress', () {
    final flow = PhoneWorkflow.fromJson([
      {'action': 'read'},
      {'action': 'back'},
    ]);
    final session = PhoneWorkflowSession(flow)..beginStep();
    session.acceptResult(
      success: true,
      status: 'Screen read locally.',
      resultOperationId: session.operationId,
    );
    final recovered = PhoneWorkflowSession.fromCheckpoint(session.checkpoint());
    expect(recovered.index, 1);
    expect(recovered.uncertainAfterRestart, false);
    expect(recovered.nextStep?.kind, PhoneStepKind.back);
  });

  test('in-flight action locks resumed session', () {
    final flow = PhoneWorkflow.fromJson([
      {'action': 'click', 'label': 'Settings'},
    ]);
    final beforeCrash = PhoneWorkflowSession(flow)..beginStep();
    final restored = PhoneWorkflowSession.fromCheckpoint(
      beforeCrash.checkpoint(),
    );
    expect(restored.uncertainAfterRestart, true);
    expect(() => restored.beginStep(), throwsStateError);
  });

  test('malformed checkpoints rejected', () {
    expect(
      () => PhoneWorkflowSession.fromCheckpoint({'schema': 2}),
      throwsFormatException,
    );
    expect(
      () => PhoneWorkflowSession.fromCheckpoint({
        'schema': 1,
        'steps': [
          {'action': 'read'},
        ],
        'index': 9,
        'inFlight': false,
      }),
      throwsFormatException,
    );
  });

  test('typing always requires approval and is bounded', () {
    final type = PhoneWorkflow.fromJson([
      {'action': 'type', 'label': 'Hello Jiva'},
    ]);
    expect(type.steps.single.needsExtraApproval, true);
    expect(type.steps.single.kind, PhoneStepKind.typeText);
    expect(
      () => PhoneWorkflow.fromJson([
        {'action': 'type', 'label': 'a' * 101},
      ]),
      throwsFormatException,
    );
  });

  test('offline planner supports only simple explicit actions', () {
    const planner = OfflinePhonePlanner();
    expect(
      planner.plan('scroll down')!.steps.single.kind,
      PhoneStepKind.scrollForward,
    );
    expect(planner.plan('screen padho')!.steps.single.kind, PhoneStepKind.read);
    expect(planner.plan('send payment'), isNull);
    expect(planner.plan('click login'), isNull);
    expect(planner.plan('find nearest store'), isNull);
  });
}
