import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../domain/phone_workflow.dart';

/// Stores only a bounded, user-reviewed plan: never screenshots, UI text or keys.
/// Uses Android-backed secure storage; failure prevents a new action dispatch.
class WorkflowCheckpointStore {
  WorkflowCheckpointStore(this.storage);
  final FlutterSecureStorage storage;
  static const key = 'jiva_phone_workflow_checkpoint_v1';
  Future<void> _pending = Future<void>.value();

  Future<void> _ordered(Future<void> Function() action) {
    final operation = _pending.catchError((Object _) {}).then((_) => action());
    _pending = operation;
    return operation;
  }

  Future<void> save(PhoneWorkflowSession session) {
    // Capture exact state before enqueueing asynchronous storage IO.
    final data = jsonEncode(session.checkpoint());
    return _ordered(() => storage.write(key: key, value: data));
  }

  Future<PhoneWorkflowSession?> load() async {
    await _pending.catchError((Object _) {});
    final value = await storage.read(key: key);
    if (value == null) return null;
    return PhoneWorkflowSession.fromCheckpoint(jsonDecode(value));
  }

  Future<void> clear() => _ordered(() => storage.delete(key: key));
}
