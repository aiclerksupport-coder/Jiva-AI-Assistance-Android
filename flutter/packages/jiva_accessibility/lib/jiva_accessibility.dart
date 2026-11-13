import 'package:flutter/services.dart';

typedef ScreenAssistResultHandler = void Function(
  String type,
  List<String> text,
  String message,
);

class JivaAccessibility {
  JivaAccessibility._();

  static const _channel = MethodChannel('com.jiva/accessibility');
  static ScreenAssistResultHandler? _resultHandler;

  static void setResultHandler(ScreenAssistResultHandler handler) {
    _resultHandler = handler;
    _channel.setMethodCallHandler((call) async {
      if (call.method != 'screenAssistResult') return;
      final result = Map<Object?, Object?>.from(call.arguments as Map);
      final rawText = result['text'];
      _resultHandler?.call(
        result['type'] as String? ?? '',
        rawText is List
            ? rawText.whereType<String>().toList()
            : const <String>[],
        result['message'] as String? ?? '',
      );
    });
  }

  static void clearResultHandler() {
    _resultHandler = null;
    _channel.setMethodCallHandler(null);
  }

  static Future<bool> isEnabled() async =>
      await _channel.invokeMethod<bool>('isEnabled') ?? false;

  static Future<void> openSettings() =>
      _channel.invokeMethod<void>('openSettings');

  static Future<void> setJivaEnabled(bool enabled) =>
      _channel.invokeMethod<void>('setJivaEnabled', {'enabled': enabled});

  static Future<void> requestVisibleScreenRead() =>
      _channel.invokeMethod<void>('requestVisibleScreenRead');

  static Future<void> queueVisibleTextClick(String text) =>
      _channel.invokeMethod<void>('queueVisibleTextClick', {'text': text});

  static Future<void> queueScreenAction(String action) =>
      _channel.invokeMethod<void>(
        'queueScreenAction',
        {'action': action},
      );

  static Future<bool> performPhoneNavigation(String action) async =>
      await _channel.invokeMethod<bool>(
        'performPhoneNavigation',
        {'action': action},
      ) ??
      false;
}
