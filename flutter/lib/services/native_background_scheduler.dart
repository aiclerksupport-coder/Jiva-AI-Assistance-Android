import 'package:flutter/services.dart';

/// WorkManager owns the lifetime; Dart code is not kept alive by this bridge.
/// Only safe app-private maintenance is eligible, never UI/voice automation.
class NativeBackgroundScheduler {
  static const _channel = MethodChannel('jiva/safe_background_scheduler');

  Future<bool> enableSafeMaintenance() async =>
      await _channel.invokeMethod<bool>('enableMaintenance') ?? false;
  Future<bool> disableSafeMaintenance() async =>
      await _channel.invokeMethod<bool>('disableMaintenance') ?? false;
  Future<Map<Object?, Object?>> status() async =>
      await _channel.invokeMethod<Map<Object?, Object?>>('maintenanceStatus') ??
      {};
}
