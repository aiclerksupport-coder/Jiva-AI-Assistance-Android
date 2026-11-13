import 'dart:convert';

import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:timezone/data/latest.dart' as tz_data;
import 'package:timezone/timezone.dart' as tz;

import '../domain/assistant_models.dart';

class ReminderService {
  ReminderService(this._storage);

  static const _itemsKey = 'jiva_reminders';
  final FlutterSecureStorage _storage;
  final FlutterLocalNotificationsPlugin _notifications =
      FlutterLocalNotificationsPlugin();
  bool _schedulePending = false;
  int _nextIdSeed = 1;

  Future<void> initialize() async {
    tz_data.initializeTimeZones();
    await _notifications.initialize(
      const InitializationSettings(
        android: AndroidInitializationSettings('@drawable/ic_jiva_notification'),
        iOS: DarwinInitializationSettings(
          requestAlertPermission: false,
          requestBadgePermission: false,
          requestSoundPermission: false,
        ),
      ),
    );
  }

  Future<List<ReminderItem>> list() async {
    final raw = await _storage.read(key: _itemsKey);
    if (raw == null) return const [];
    final values = jsonDecode(raw) as List<dynamic>;
    return values
        .map((item) => item as Map<String, dynamic>)
        .map(
          (item) => ReminderItem(
            id: item['id'] as int,
            text: item['text'] as String,
            triggerAt: DateTime.parse(item['triggerAt'] as String),
          ),
        )
        .where((item) => item.triggerAt.isAfter(DateTime.now()))
        .toList()
      ..sort((a, b) => a.triggerAt.compareTo(b.triggerAt));
  }

  int _allocateId(Set<int> used) {
    // No hash-based identity collisions; avoid the IDs already persisted.
    for (var attempts = 0; attempts < 10000; attempts++) {
      _nextIdSeed = (_nextIdSeed + 1) & 0x7fffffff;
      final id = (DateTime.now().microsecondsSinceEpoch + _nextIdSeed) &
          0x7fffffff;
      if (id > 0 && !used.contains(id)) return id;
    }
    throw StateError('Could not allocate a unique reminder ID.');
  }

  String _encode(List<ReminderItem> reminders) => jsonEncode(
        reminders.map((item) => {
          'id': item.id,
          'text': item.text,
          'triggerAt': item.triggerAt.toIso8601String(),
        }).toList(),
      );

  Future<ReminderItem> schedule(String text, DateTime triggerAt) async {
    if (text.trim().isEmpty) {
      throw ArgumentError('Reminder text cannot be empty.');
    }
    if (!triggerAt.isAfter(DateTime.now())) {
      throw ArgumentError('Choose a time in the future.');
    }
    if (text.trim().length > 2000) {
      throw ArgumentError('Reminder text is too long.');
    }
    if (_schedulePending) {
      throw StateError('A reminder is already being saved. Try again.');
    }
    _schedulePending = true;
    try {
      final reminders = await list();
      final used = reminders.map((item) => item.id).toSet();
      final id = _allocateId(used);
      final reminder = ReminderItem(
        id: id,
        text: text.trim(),
        triggerAt: triggerAt,
      );
      final oldValue = await _storage.read(key: _itemsKey);
      final updated = [...reminders, reminder];
      // Durable first: do not schedule a notification that we cannot
      // enumerate later. If OS rejects alarm scheduling, rollback the record.
      await _storage.write(key: _itemsKey, value: _encode(updated));
      try {
        await _notifications.zonedSchedule(
          id,
          'Jiva reminder',
          reminder.text,
          tz.TZDateTime.from(triggerAt, tz.local),
          const NotificationDetails(
            android: AndroidNotificationDetails(
              'jiva_reminders',
              'Jiva reminders',
              channelDescription: 'Scheduled Jiva reminders',
              importance: Importance.high,
              priority: Priority.high,
            ),
            iOS: DarwinNotificationDetails(),
          ),
          androidScheduleMode: AndroidScheduleMode.inexactAllowWhileIdle,
          uiLocalNotificationDateInterpretation:
              UILocalNotificationDateInterpretation.absoluteTime,
        );
      } catch (_) {
        if (oldValue == null) {
          await _storage.delete(key: _itemsKey);
        } else {
          await _storage.write(key: _itemsKey, value: oldValue);
        }
        rethrow;
      }
      return reminder;
    } finally {
      _schedulePending = false;
    }
  }
}
