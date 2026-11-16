import 'dart:convert';
import 'dart:async';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../domain/deferred_task.dart';

/// Crash-safe encrypted local journal. This is NOT an Android background worker.
/// A task is dispatched only after visible user approval; ambiguous leases
/// become needsReview on restart. Storage IO failures must block dispatch.
class DeferredTaskStore {
  DeferredTaskStore(this.storage);
  final FlutterSecureStorage storage;
  static const _key = 'jiva_safe_deferred_jobs_v1';
  Future<void> _pending = Future<void>.value();

  Future<T> serialized<T>(Future<T> Function() action) async {
    final previous = _pending;
    final completed = Completer<void>();
    _pending = completed.future;
    await previous.catchError((Object _) {});
    try {
      return await action();
    } finally {
      completed.complete();
    }
  }

  Future<DeferredTaskJournal> load() => serialized(() async {
    final journal = DeferredTaskJournal();
    final raw = await storage.read(key: _key);
    if (raw == null) {
      return journal;
    }
    final decoded = jsonDecode(raw);
    if (decoded is! List) {
      throw const FormatException('Invalid deferred task journal');
    }
    journal.restore(decoded);
    return journal;
  });

  Future<void> save(DeferredTaskJournal journal) => serialized(() async {
    final value = jsonEncode(journal.snapshot());
    if (value.length > 12000) {
      throw StateError('Deferred task journal too large');
    }
    await storage.write(key: _key, value: value);
  });

  Future<DeferredTask?> leaseApproved(
    String id, {
    required bool appVisible,
    required bool userApproved,
  }) => serialized(() async {
    if (!appVisible || !userApproved) {
      return null;
    }
    final journal = DeferredTaskJournal();
    final raw = await storage.read(key: _key);
    if (raw != null) {
      journal.restore(jsonDecode(raw) as List);
    }
    final leased = journal.lease(
      id,
      appVisible: appVisible,
      userApproved: userApproved,
    );
    if (leased != null) {
      await storage.write(key: _key, value: jsonEncode(journal.snapshot()));
    }
    return leased;
  });
}
