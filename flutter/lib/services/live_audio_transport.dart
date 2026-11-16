import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:web_socket_channel/web_socket_channel.dart';

/// Streaming protocol layer only. The mic/audio I/O is intentionally separate.
/// Never cache microphone PCM, API keys or model output in persistent storage.
class LiveAudioTransport {
  LiveAudioTransport({
    required this.apiKey,
    required this.model,
    required this.voiceName,
    WebSocketChannel Function(Uri)? connector,
  }) : _connector = connector ?? WebSocketChannel.connect;

  final String apiKey;
  final String model;
  final String voiceName;
  final WebSocketChannel Function(Uri) _connector;
  WebSocketChannel? _channel;
  StreamSubscription<dynamic>? _subscription;
  final _events = StreamController<LiveAudioEvent>.broadcast(sync: true);
  Stream<LiveAudioEvent> get events => _events.stream;
  bool _ready = false;
  bool _closed = false;

  bool get isReady => _ready && !_closed;

  Future<void> connect() async {
    if (_closed || _channel != null) {
      throw StateError('Live session already used.');
    }
    if (apiKey.trim().isEmpty) {
      throw ArgumentError('AI key is required.');
    }
    if (!RegExp(r'^[a-zA-Z0-9._-]{1,100}$').hasMatch(model)) {
      throw ArgumentError('Invalid model ID.');
    }
    if (!RegExp(r'^[a-zA-Z]{2,32}$').hasMatch(voiceName)) {
      throw ArgumentError('Invalid voice preset.');
    }
    final channel = _connector(
      Uri(
        scheme: 'wss',
        host: 'generativelanguage.googleapis.com',
        path:
            '/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent',
        queryParameters: {'key': apiKey},
      ),
    );
    _channel = channel;
    _subscription = channel.stream.listen(
      _onMessage,
      onError: (Object error, StackTrace trace) {
        _ready = false;
        if (!_events.isClosed) {
          _events.add(LiveAudioEvent.error('Live connection interrupted.'));
        }
      },
      onDone: () {
        _ready = false;
        if (!_events.isClosed) {
          _events.add(const LiveAudioEvent.closed());
        }
      },
    );
    try {
      await channel.ready.timeout(const Duration(seconds: 20));
      if (_closed) {
        return;
      }
      channel.sink.add(
        jsonEncode({
          'setup': {
            'model': 'models/$model',
            'generationConfig': {
              'responseModalities': ['AUDIO'],
              'speechConfig': {
                'voiceConfig': {
                  'prebuiltVoiceConfig': {'voiceName': voiceName},
                },
              },
            },
            'inputAudioTranscription': {},
            'outputAudioTranscription': {},
          },
        }),
      );
    } catch (_) {
      await close();
      rethrow;
    }
  }

  /// Supply PCM16 little-endian MONO 16 kHz from a user-authorized recorder.
  /// Frame <= 100 ms (3200 bytes); odd-sized or excessive frames rejected.
  void sendPcm16(Uint8List pcm) {
    if (!isReady) {
      throw StateError('Live setup has not completed.');
    }
    if (pcm.isEmpty || pcm.length > 3200 || pcm.length.isOdd) {
      throw ArgumentError('Expected 1-100ms even-length PCM16 input.');
    }
    _channel!.sink.add(
      jsonEncode({
        'realtimeInput': {
          'audio': {
            'mimeType': 'audio/pcm;rate=16000',
            'data': base64Encode(pcm),
          },
        },
      }),
    );
  }

  void finishMicrophoneTurn() {
    if (!isReady) {
      throw StateError('Live setup has not completed.');
    }
    _channel!.sink.add(
      jsonEncode({
        'realtimeInput': {'audioStreamEnd': true},
      }),
    );
  }

  void _onMessage(dynamic raw) {
    if (_closed || raw is! String) {
      return;
    }
    try {
      final value = jsonDecode(raw);
      if (value is! Map) {
        return;
      }
      if (value.containsKey('setupComplete')) {
        _ready = true;
        _events.add(const LiveAudioEvent.ready());
        return;
      }
      if (value['error'] != null) {
        _events.add(LiveAudioEvent.error('Live model rejected a request.'));
        return;
      }
      final content = value['serverContent'];
      if (content is! Map) {
        return;
      }
      if (content['interrupted'] == true) {
        _events.add(const LiveAudioEvent.interrupted());
      }
      for (final key in ['inputTranscription', 'outputTranscription']) {
        final transcript = content[key];
        if (transcript is Map && transcript['text'] is String) {
          _events.add(
            LiveAudioEvent.text(
              (transcript['text'] as String).substring(
                0,
                ((transcript['text'] as String).length > 4096
                    ? 4096
                    : (transcript['text'] as String).length),
              ),
            ),
          );
        }
      }
      final parts = (content['modelTurn'] is Map)
          ? (content['modelTurn'] as Map)['parts']
          : null;
      if (parts is List) {
        for (final part in parts) {
          final inline = part is Map ? part['inlineData'] : null;
          if (inline is Map && inline['data'] is String) {
            final encoded = inline['data'] as String;
            if (encoded.length > 128000) {
              continue;
            }
            final bytes = base64Decode(encoded);
            if (bytes.length <= 96000) {
              _events.add(LiveAudioEvent.audio(bytes));
            }
          }
        }
      }
      if (content['turnComplete'] == true) {
        _events.add(const LiveAudioEvent.turnComplete());
      }
    } on FormatException {
      _events.add(LiveAudioEvent.error('Invalid Live response.'));
    }
  }

  Future<void> close() async {
    if (_closed) {
      return;
    }
    _closed = true;
    _ready = false;
    final sub = _subscription;
    _subscription = null;
    if (sub != null) {
      await sub.cancel();
    }
    final channel = _channel;
    _channel = null;
    if (channel != null) {
      await channel.sink.close();
    }
    await _events.close();
  }
}

enum LiveAudioEventType {
  ready,
  audio,
  text,
  interrupted,
  turnComplete,
  closed,
  error,
}

class LiveAudioEvent {
  const LiveAudioEvent._(this.type, {this.pcm, this.transcript, this.message});
  const LiveAudioEvent.ready() : this._(LiveAudioEventType.ready);
  const LiveAudioEvent.interrupted() : this._(LiveAudioEventType.interrupted);
  const LiveAudioEvent.turnComplete() : this._(LiveAudioEventType.turnComplete);
  const LiveAudioEvent.closed() : this._(LiveAudioEventType.closed);
  LiveAudioEvent.audio(Uint8List pcm)
    : this._(LiveAudioEventType.audio, pcm: pcm);
  LiveAudioEvent.text(String text)
    : this._(LiveAudioEventType.text, transcript: text);
  LiveAudioEvent.error(String message)
    : this._(LiveAudioEventType.error, message: message);
  final LiveAudioEventType type;
  final Uint8List? pcm;
  final String? transcript;
  final String? message;
}
