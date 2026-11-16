import 'dart:convert';
import 'package:flutter/services.dart';

typedef ScreenAssistResultHandler =
    void Function(
      String type,
      List<String> text,
      String message,
      String operationId,
    );

class JivaAccessibility {
  JivaAccessibility._();

  static const _channel = MethodChannel('com.jiva/accessibility');
  static ScreenAssistResultHandler? _resultHandler;
  static void Function(Uint8List jpeg, String packageName)? _screenshotHandler;

  static void setScreenshotHandler(
    void Function(Uint8List jpeg, String packageName)? handler,
  ) {
    _screenshotHandler = handler;
  }

  static void setResultHandler(ScreenAssistResultHandler handler) {
    _resultHandler = handler;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'reviewedScreenshot') {
        final values = Map<Object?, Object?>.from(call.arguments as Map);
        final base64Jpeg = values['jpeg'];
        if (base64Jpeg is String && base64Jpeg.length <= 850000) {
          final image = base64Decode(base64Jpeg);
          if (image.length <= 600000) {
            _screenshotHandler?.call(
              image,
              values['sourcePackage'] as String? ?? '',
            );
          }
        }
        return;
      }
      if (call.method != 'screenAssistResult') {
        return;
      }
      final result = Map<Object?, Object?>.from(call.arguments as Map);
      final rawText = result['text'];
      _resultHandler?.call(
        result['type'] as String? ?? '',
        rawText is List
            ? rawText.whereType<String>().toList()
            : const <String>[],
        result['message'] as String? ?? '',
        result['operationId'] as String? ?? '',
      );
    });
  }

  static void clearResultHandler() {
    _resultHandler = null;
    _screenshotHandler = null;
    _channel.setMethodCallHandler(null);
  }

  static Future<bool> isEnabled() async =>
      await _channel.invokeMethod<bool>('isEnabled') ?? false;

  static Future<void> openSettings() =>
      _channel.invokeMethod<void>('openSettings');

  static Future<void> setJivaEnabled(bool enabled) =>
      _channel.invokeMethod<void>('setJivaEnabled', {'enabled': enabled});

  static Future<void> setFloatingControls(bool enabled) =>
      _channel.invokeMethod<void>('setFloatingControls', {'enabled': enabled});

  static Future<bool> areFloatingControlsEnabled() async =>
      await _channel.invokeMethod<bool>('areFloatingControlsEnabled') ?? false;

  static Future<void> cancelPendingScreenAction() =>
      _channel.invokeMethod<void>('cancelPendingScreenAction');

  static Future<void> requestReviewedScreenshot() =>
      _channel.invokeMethod<void>('requestReviewedScreenshot');

  static Future<void> launchReviewedApp(
    String packageName, {
    required String operationId,
  }) => _channel.invokeMethod<void>('launchReviewedApp', {
    'packageName': packageName,
    'operationId': operationId,
  });

  static Future<void> verifyVisibleGoal(
    String expectedText, {
    required String operationId,
  }) => _channel.invokeMethod<void>('verifyVisibleGoal', {
    'text': expectedText,
    'operationId': operationId,
  });

  static Future<void> requestVisibleScreenRead({String operationId = ''}) =>
      _channel.invokeMethod<void>('requestVisibleScreenRead', {
        'operationId': operationId,
      });

  static Future<void> queueVisibleTextClick(
    String text, {
    String operationId = '',
  }) => _channel.invokeMethod<void>('queueVisibleTextClick', {
    'text': text,
    'operationId': operationId,
  });

  static Future<void> queueFocusedTextInput(
    String text, {
    String operationId = '',
  }) => _channel.invokeMethod<void>('queueFocusedTextInput', {
    'text': text,
    'operationId': operationId,
  });

  static Future<void> queueScreenAction(
    String action, {
    String operationId = '',
  }) => _channel.invokeMethod<void>('queueScreenAction', {
    'action': action,
    'operationId': operationId,
  });

  static Future<bool> performPhoneNavigation(String action) async =>
      await _channel.invokeMethod<bool>('performPhoneNavigation', {
        'action': action,
      }) ??
      false;
}
