import 'dart:async';
import 'package:flutter/services.dart';

/// User-initiated session; never acquires microphone from background.
class LiveAudioIo {
  static const _commands = MethodChannel('com.jiva/live_audio');
  static const _pcm = EventChannel('com.jiva/live_audio_pcm');
  Stream<Uint8List> get microphone =>
      _pcm.receiveBroadcastStream().map((event) {
        if (event is! Uint8List) {
          throw StateError('Unexpected microphone frame');
        }
        return event;
      });
  Future<void> start() => _commands.invokeMethod<void>('start');
  Future<void> stop() => _commands.invokeMethod<void>('stop');
  Future<void> interrupt() => _commands.invokeMethod<void>('interrupt');
  Future<void> play(Uint8List pcm) =>
      _commands.invokeMethod<void>('play', {'pcm': pcm});
}
