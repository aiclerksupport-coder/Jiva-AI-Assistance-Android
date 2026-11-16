/// Safe, user-approved deferred work. Never use this queue to automate
/// arbitrary third-party UI, wake a microphone or send a message unattended.
enum DeferredTaskType { localReminderRefresh, localCacheValidation }

enum DeferredTaskStatus { pending, leased, completed, needsReview }

class DeferredTask {
  const DeferredTask({
    required this.id,
    required this.type,
    required this.status,
    required this.createdAtUtc,
    this.attempts = 0,
  });
  final String id;
  final DeferredTaskType type;
  final DeferredTaskStatus status;
  final DateTime createdAtUtc;
  final int attempts;

  Map<String, dynamic> toJson() => {
    'id': id,
    'type': type.name,
    'status': status.name,
    'createdAtUtc': createdAtUtc.toIso8601String(),
    'attempts': attempts,
  };

  factory DeferredTask.fromJson(Map<String, dynamic> item) {
    final id = item['id'];
    final rawType = item['type'];
    final rawStatus = item['status'];
    final time = DateTime.tryParse(item['createdAtUtc']?.toString() ?? '');
    final attempts = item['attempts'];
    if (id is! String ||
        !RegExp(r'^[0-9a-zA-Z_-]{8,64}$').hasMatch(id) ||
        rawType is! String ||
        rawStatus is! String ||
        time == null ||
        attempts is! int ||
        attempts < 0 ||
        attempts > 3) {
      throw const FormatException('Invalid deferred task record');
    }
    final type = DeferredTaskType.values
        .where((v) => v.name == rawType)
        .firstOrNull;
    final status = DeferredTaskStatus.values
        .where((v) => v.name == rawStatus)
        .firstOrNull;
    if (type == null || status == null) {
      throw const FormatException('Unknown deferred task');
    }
    return DeferredTask(
      id: id,
      type: type,
      status: status,
      createdAtUtc: time.toUtc(),
      attempts: attempts,
    );
  }

  DeferredTask withStatus(DeferredTaskStatus next) => DeferredTask(
    id: id,
    type: type,
    status: next,
    createdAtUtc: createdAtUtc,
    attempts: next == DeferredTaskStatus.leased ? attempts + 1 : attempts,
  );
}

/// All leased tasks return to needsReview on restart; no blind retry.
class DeferredTaskJournal {
  DeferredTaskJournal();
  final List<DeferredTask> _tasks = [];
  List<DeferredTask> get tasks => List.unmodifiable(_tasks);

  void restore(List<dynamic> raw) {
    if (raw.length > 20) {
      throw const FormatException('Too many deferred tasks');
    }
    final parsed = raw
        .map((e) => DeferredTask.fromJson(Map<String, dynamic>.from(e as Map)))
        .toList();
    if (parsed.map((e) => e.id).toSet().length != parsed.length) {
      throw const FormatException('Duplicate deferred task ID');
    }
    _tasks
      ..clear()
      ..addAll(
        parsed.map(
          (t) => t.status == DeferredTaskStatus.leased
              ? t.withStatus(DeferredTaskStatus.needsReview)
              : t,
        ),
      );
  }

  void add(DeferredTask task) {
    if (_tasks.length >= 20) {
      throw StateError('Deferred task queue is full');
    }
    if (_tasks.any((t) => t.id == task.id)) {
      throw StateError('Duplicate task');
    }
    if (task.status != DeferredTaskStatus.pending) {
      throw StateError('Only new pending tasks can be queued');
    }
    _tasks.add(task);
  }

  DeferredTask? lease(
    String id, {
    required bool appVisible,
    required bool userApproved,
  }) {
    if (!appVisible || !userApproved) {
      return null;
    }
    final index = _tasks.indexWhere(
      (t) => t.id == id && t.status == DeferredTaskStatus.pending,
    );
    if (index < 0) {
      return null;
    }
    final old = _tasks[index];
    if (old.attempts >= 3) {
      return null;
    }
    final next = old.withStatus(DeferredTaskStatus.leased);
    _tasks[index] = next;
    return next;
  }

  bool complete(String id) {
    final index = _tasks.indexWhere(
      (t) => t.id == id && t.status == DeferredTaskStatus.leased,
    );
    if (index < 0) {
      return false;
    }
    _tasks[index] = _tasks[index].withStatus(DeferredTaskStatus.completed);
    return true;
  }

  List<Map<String, dynamic>> snapshot() => [for (final t in _tasks) t.toJson()];
}
