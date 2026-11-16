import 'dart:math';

/// A reviewed, bounded phone workflow. This layer never performs Android actions.
/// The UI must confirm every step and the native service remains the final gate.
enum PhoneStepKind {
  read,
  click,
  typeText,
  scrollForward,
  scrollBackward,
  back,
  openApp,
  verifyText,
}

class PhoneStep {
  const PhoneStep(this.kind, {this.label = ''});
  final PhoneStepKind kind;
  final String label;

  bool get needsExtraApproval =>
      kind == PhoneStepKind.typeText ||
      (kind == PhoneStepKind.click &&
          RegExp(
            r'\b(send|delete|pay|purchase|transfer|confirm|submit|post|buy|remove|share|allow|install|uninstall)\b',
            caseSensitive: false,
          ).hasMatch(label));

  String get description => switch (kind) {
    PhoneStepKind.read => 'Read visible screen',
    PhoneStepKind.click => 'Click visible label: $label',
    PhoneStepKind.typeText => 'Type into the unique focused field: $label',
    PhoneStepKind.scrollForward => 'Scroll down',
    PhoneStepKind.scrollBackward => 'Scroll up',
    PhoneStepKind.back => 'Go back',
    PhoneStepKind.openApp => 'Open exact installed app: $label',
    PhoneStepKind.verifyText => 'Verify exact final visible result: $label',
  };
}

class PhoneWorkflow {
  PhoneWorkflow._(this.steps);

  List<Map<String, String>> toJson() => [
    for (final step in steps)
      {
        'action': switch (step.kind) {
          PhoneStepKind.read => 'read',
          PhoneStepKind.click => 'click',
          PhoneStepKind.typeText => 'type',
          PhoneStepKind.scrollForward => 'scroll_forward',
          PhoneStepKind.scrollBackward => 'scroll_backward',
          PhoneStepKind.back => 'back',
          PhoneStepKind.openApp => 'open_app',
          PhoneStepKind.verifyText => 'verify_text',
        },
        if (step.kind == PhoneStepKind.click ||
            step.kind == PhoneStepKind.typeText ||
            step.kind == PhoneStepKind.openApp ||
            step.kind == PhoneStepKind.verifyText)
          'label': step.label,
      },
  ];
  final List<PhoneStep> steps;
  static const maxSteps = 8;
  static const maxLabelLength = 100;

  static PhoneWorkflow fromJson(Object? input) {
    if (input is! List || input.isEmpty || input.length > maxSteps) {
      throw const FormatException('Workflow must have 1-8 steps.');
    }
    final steps = <PhoneStep>[];
    for (final item in input) {
      if (item is! Map) {
        throw const FormatException('Invalid workflow step.');
      }
      final action = item['action'];
      final label = item['label'];
      if (action is! String || (label != null && label is! String)) {
        throw const FormatException('Invalid action or label.');
      }
      final kind = switch (action.toLowerCase()) {
        'read' => PhoneStepKind.read,
        'click' => PhoneStepKind.click,
        'type' => PhoneStepKind.typeText,
        'scroll_forward' => PhoneStepKind.scrollForward,
        'scroll_backward' => PhoneStepKind.scrollBackward,
        'back' => PhoneStepKind.back,
        'open_app' => PhoneStepKind.openApp,
        'verify_text' => PhoneStepKind.verifyText,
        _ => throw const FormatException('Unsupported phone step.'),
      };
      final normalized = (label as String? ?? '').trim();
      if (normalized.length > maxLabelLength ||
          normalized.contains(RegExp(r'[\x00-\x1f]'))) {
        throw const FormatException('Invalid screen label.');
      }
      if ((kind == PhoneStepKind.click ||
              kind == PhoneStepKind.typeText ||
              kind == PhoneStepKind.openApp ||
              kind == PhoneStepKind.verifyText) &&
          normalized.isEmpty) {
        throw const FormatException('A click needs a visible label.');
      }
      if (kind != PhoneStepKind.click &&
          kind != PhoneStepKind.typeText &&
          kind != PhoneStepKind.openApp &&
          kind != PhoneStepKind.verifyText &&
          normalized.isNotEmpty) {
        throw const FormatException('Unexpected step label.');
      }
      steps.add(PhoneStep(kind, label: normalized));
    }
    // Cross-app completion requires an explicit final user-reviewed observable goal.
    if (steps.any((e) => e.kind == PhoneStepKind.openApp) &&
        steps.last.kind != PhoneStepKind.verifyText) {
      throw const FormatException('Cross-app plans must end with verify_text.');
    }
    if (steps.where((e) => e.kind == PhoneStepKind.verifyText).length > 1 ||
        steps
            .take(steps.length - 1)
            .any((e) => e.kind == PhoneStepKind.verifyText)) {
      throw const FormatException('Goal verification must be the final step.');
    }
    return PhoneWorkflow._(List.unmodifiable(steps));
  }
}

/// Fail-closed single-flight state machine: no step is completed before a
/// verified native success result. A failed step must be explicitly retried.
class PhoneWorkflowSession {
  PhoneWorkflowSession(this.workflow);
  final PhoneWorkflow workflow;
  int index = 0;
  bool awaitingResult = false;
  bool cancelled = false;
  bool uncertainAfterRestart = false;
  bool goalVerified = false;
  String? operationId;

  static String _newOperationId() {
    final random = Random.secure();
    return List<int>.generate(
      16,
      (_) => random.nextInt(256),
    ).map((v) => v.toRadixString(16).padLeft(2, '0')).join();
  }

  Map<String, Object> checkpoint() => {
    'schema': 1,
    'steps': workflow.toJson(),
    'index': index,
    'inFlight': awaitingResult || uncertainAfterRestart,
    'goalVerified': goalVerified,
    'operationId': ?operationId,
  };

  /// In-flight actions could have happened before a crash. Never auto-replay.
  static PhoneWorkflowSession fromCheckpoint(Object? decoded) {
    if (decoded is! Map || decoded['schema'] != 1) {
      throw const FormatException('Unsupported task checkpoint');
    }
    final flow = PhoneWorkflow.fromJson(decoded['steps']);
    final position = decoded['index'];
    if (position is! int || position < 0 || position > flow.steps.length) {
      throw const FormatException('Invalid checkpoint position');
    }
    final id = decoded['operationId'];
    if (id != null &&
        (id is! String || !RegExp(r'^[0-9a-f]{32}$').hasMatch(id))) {
      throw const FormatException('Invalid operation ID');
    }
    final inFlight = decoded['inFlight'];
    if (inFlight is! bool) {
      throw const FormatException('Invalid checkpoint state');
    }
    final session = PhoneWorkflowSession(flow);
    session.index = position;
    session.goalVerified = decoded['goalVerified'] == true;
    session.operationId = id as String?;
    session.uncertainAfterRestart = inFlight;
    session.lastStatus = inFlight
        ? 'Last action may have executed. Inspect target app; cancel and re-plan if uncertain.'
        : 'Recovered paused workflow. Review before each next action.';
    return session;
  }

  String lastStatus = 'Review each step before execution.';

  bool get isComplete => index >= workflow.steps.length;
  PhoneStep? get nextStep =>
      cancelled || isComplete ? null : workflow.steps[index];

  void beginStep() {
    if (cancelled || isComplete || awaitingResult || uncertainAfterRestart) {
      throw StateError('Workflow is not ready for another action.');
    }
    operationId = _newOperationId();
    awaitingResult = true;
    lastStatus = 'Waiting for target app and native verification.';
  }

  void acceptResult({
    required bool success,
    required String status,
    required String? resultOperationId,
  }) {
    if (cancelled ||
        !awaitingResult ||
        operationId == null ||
        resultOperationId != operationId) {
      return;
    }
    awaitingResult = false;
    operationId = null;
    lastStatus = status;
    if (success) {
      if (nextStep?.kind == PhoneStepKind.verifyText) {
        goalVerified = true;
      }
      index++;
      if (isComplete && !goalVerified) {
        lastStatus = 'Actions finished. Final goal not independently verified.';
      } else if (isComplete) {
        lastStatus = 'Final visible result verified on target app.';
      }
    }
  }

  void cancel() {
    cancelled = true;
    operationId = null;
    uncertainAfterRestart = false;
    awaitingResult = false;
    lastStatus =
        'Workflow cancelled. Pending Android request may still expire.';
  }
}
