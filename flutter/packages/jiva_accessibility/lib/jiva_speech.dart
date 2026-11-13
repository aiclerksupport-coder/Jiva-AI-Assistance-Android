import 'package:flutter/services.dart';

typedef JivaSpeechTextHandler = void Function(String text);
typedef JivaSpeechStatusHandler = void Function(String status);

class JivaSpeech {
  JivaSpeech._();

  static const _channel = MethodChannel('com.jiva/speech');
  static JivaSpeechTextHandler? _onResult;
  static JivaSpeechTextHandler? _onPartialResult;
  static JivaSpeechTextHandler? _onError;
  static JivaSpeechTextHandler? _onFallback;
  static JivaSpeechStatusHandler? _onStatus;
  static JivaSpeechStatusHandler? _onAudioProcessingStatus;

  static void setCallbacks({
    required JivaSpeechTextHandler onResult,
    required JivaSpeechTextHandler onPartialResult,
    required JivaSpeechTextHandler onError,
    required JivaSpeechTextHandler onFallback,
    required JivaSpeechStatusHandler onStatus,
    required JivaSpeechStatusHandler onAudioProcessingStatus,
  }) {
    _onResult = onResult;
    _onPartialResult = onPartialResult;
    _onError = onError;
    _onFallback = onFallback;
    _onStatus = onStatus;
    _onAudioProcessingStatus = onAudioProcessingStatus;
    _channel.setMethodCallHandler((call) async {
      final value = call.arguments is String ? call.arguments as String : '';
      switch (call.method) {
        case 'speechResult':
          _onResult?.call(value);
          break;
        case 'speechPartial':
          _onPartialResult?.call(value);
          break;
        case 'speechError':
          _onError?.call(value);
          break;
        case 'speechFallback':
          _onFallback?.call(value);
          break;
        case 'speechStatus':
          _onStatus?.call(value);
          break;
        case 'audioProcessingStatus':
          _onAudioProcessingStatus?.call(value);
          break;
      }
    });
  }

  static void clearCallbacks() {
    _onResult = null;
    _onPartialResult = null;
    _onError = null;
    _onFallback = null;
    _onStatus = null;
    _onAudioProcessingStatus = null;
    _channel.setMethodCallHandler(null);
  }

  static Future<bool> start({
    required bool patientListening,
  }) async =>
      await _channel.invokeMethod<bool>(
        'startListening',
        {'patientListening': patientListening},
      ) ??
      false;

  static Future<void> stop() =>
      _channel.invokeMethod<void>('stopListening');
}
