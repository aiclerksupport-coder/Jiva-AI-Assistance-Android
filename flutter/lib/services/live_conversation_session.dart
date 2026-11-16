import 'dart:async';
import 'dart:typed_data';
import 'live_audio_io.dart';
import 'live_audio_transport.dart';

/// Handles session lifetime and explicit cancel. Never persists raw audio.
class LiveConversationSession {
  LiveConversationSession(this.transport, this.audioIo);
  final LiveAudioTransport transport;
  final LiveAudioIo audioIo;
  StreamSubscription<Uint8List>? _mic;
  StreamSubscription<LiveAudioEvent>? _responses;
  bool _active = false;
  bool get isActive => _active;
  Future<void> start({
    required void Function(String) onText,
    required void Function(String) onError,
  }) async {
    if (_active) {
      throw StateError('Already started');
    }
    _active = true;
    try {
      // Subscribe before starting audio to avoid losing first samples.
      _responses = transport.events.listen((event) {
        if (!_active) {
          return;
        }
        switch (event.type) {
          case LiveAudioEventType.text:
            if (event.transcript != null) {
              onText(event.transcript!);
            }
          case LiveAudioEventType.audio:
            if (event.pcm != null) {
              unawaited(audioIo.play(event.pcm!).catchError((Object _) {}));
            }
          case LiveAudioEventType.interrupted:
            unawaited(audioIo.interrupt());
          case LiveAudioEventType.error:
            onError(event.message ?? 'Live audio error');
          case LiveAudioEventType.closed:
            onError('Live audio connection closed');
          default:
            break;
        }
      });
      // Subscribe to setup before opening socket to avoid a lost ready event.
      final setup = transport.events
          .firstWhere(
            (e) =>
                e.type == LiveAudioEventType.ready ||
                e.type == LiveAudioEventType.error ||
                e.type == LiveAudioEventType.closed,
          )
          .timeout(const Duration(seconds: 20));
      await transport.connect();
      final ready = await setup;
      if (ready.type != LiveAudioEventType.ready) {
        throw StateError('Live setup rejected');
      }
      _mic = audioIo.microphone.listen(
        (bytes) {
          if (_active && transport.isReady) {
            try {
              transport.sendPcm16(bytes);
            } catch (error) {
              onError('Audio input interrupted');
            }
          }
        },
        onError: (Object error) {
          onError('Microphone stream failed');
        },
      );
      await audioIo.start();
    } catch (_) {
      await stop();
      rethrow;
    }
  }

  Future<void> stop() async {
    if (!_active) {
      return;
    }
    _active = false;
    try {
      await audioIo.stop();
    } catch (_) {}
    await _mic?.cancel();
    _mic = null;
    await _responses?.cancel();
    _responses = null;
    await transport.close();
  }
}
