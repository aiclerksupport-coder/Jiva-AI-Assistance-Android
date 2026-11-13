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

  Future<void> initialize() async {
    tz_data.initializeTimeZones();
    await _notifications.initialize(
      const InitializationSettings(
        android: AndroidInitializationSettings('@mipmap/ic_launcher'),
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

  Future<ReminderItem> schedule(String text, DateTime triggerAt) async {
    if (text.trim().isEmpty) {
      throw ArgumentError('Reminder text cannot be empty.');
    }
    if (!triggerAt.isAfter(DateTime.now())) {
      throw ArgumentError('Choose a time in the future.');
    }
    final id = DateTime.now().microsecondsSinceEpoch.remainder(0x7fffffff);
    final reminder = ReminderItem(
      id: id,
      text: text.trim(),
      triggerAt: triggerAt,
    );
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
    );
    final reminders = await list();
    reminders.removeWhere((item) => item.id == id);
    reminders.add(reminder);
    await _storage.write(
      key: _itemsKey,
      value: jsonEncode(
        reminders
            .map(
              (item) => {
                'id': item.id,
                'text': item.text,
                'triggerAt': item.triggerAt.toIso8601String(),
              },
            )
            .toList(),
      ),
    );
    return reminder;
  }
}
