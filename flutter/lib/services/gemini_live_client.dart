import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:web_socket_channel/web_socket_channel.dart';

import '../domain/voice_preset.dart';
import 'gemini_retry_policy.dart';

class GeminiLiveReply {
  const GeminiLiveReply({required this.transcript, required this.pcm});

  final String transcript;
  final Uint8List pcm;

  Uint8List toWav() {
    final header = ByteData(44);
    final dataLength = pcm.length;
    final byteRate = sampleRate * channels * bitsPerSample ~/ 8;
    void writeText(int offset, String value) {
      for (var index = 0; index < value.length; index++) {
        header.setUint8(offset + index, value.codeUnitAt(index));
      }
    }

    writeText(0, 'RIFF');
    header.setUint32(4, 36 + dataLength, Endian.little);
    writeText(8, 'WAVE');
    writeText(12, 'fmt ');
    header.setUint32(16, 16, Endian.little);
    header.setUint16(20, 1, Endian.little);
    header.setUint16(22, channels, Endian.little);
    header.setUint32(24, sampleRate, Endian.little);
    header.setUint32(28, byteRate, Endian.little);
    header.setUint16(32, channels * bitsPerSample ~/ 8, Endian.little);
    header.setUint16(34, bitsPerSample, Endian.little);
    writeText(36, 'data');
    header.setUint32(40, dataLength, Endian.little);
    return Uint8List.fromList([...header.buffer.asUint8List(), ...pcm]);
  }

  static const sampleRate = 24000;
  static const channels = 1;
  static const bitsPerSample = 16;
}

class GeminiLiveClient {
  WebSocketChannel? _activeChannel;
  var _requestGeneration = 0;

  Future<void> cancel() async {
    _requestGeneration++;
    final channel = _activeChannel;
    _activeChannel = null;
    if (channel != null) await channel.sink.close();
  }

  Future<GeminiLiveReply> respond({
    required String apiKey,
    required String text,
    required String voiceName,
    void Function(int attempt, Object error)? onRetry,
  }) async {
    if (apiKey.trim().isEmpty) {
      throw ArgumentError('Add your Gemini API key in Settings.');
    }
    if (text.trim().isEmpty) {
      throw ArgumentError('There is no text to send to Gemini Live.');
    }
    final voice = voicePresetFor(voiceName).name;
    final generation = _requestGeneration;
    return GeminiRetryPolicy.execute(() async {
      if (_requestGeneration != generation) {
        throw StateError('Gemini request cancelled.');
      }
      final channel = WebSocketChannel.connect(
        Uri(
          scheme: 'wss',
          host: 'generativelanguage.googleapis.com',
          path: '/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent',
          queryParameters: {'key': apiKey},
        ),
      );
      _activeChannel = channel;
      if (_requestGeneration != generation) {
        _activeChannel = null;
        await channel.sink.close();
        throw StateError('Gemini request cancelled.');
      }
      final reply = Completer<GeminiLiveReply>();
      final audio = BytesBuilder(copy: false);
      final transcript = StringBuffer();
      var sentText = false;
      final subscription = channel.stream.listen(
        (event) {
          try {
            if (event is! String) {
              throw const FormatException('Gemini Live returned an unsupported frame.');
            }
            final message = jsonDecode(event) as Map<String, dynamic>;
            if (message.containsKey('setupComplete') && !sentText) {
              sentText = true;
              channel.sink.add(
                jsonEncode({
                  'clientContent': {
                    'turns': [
                      {
                        'role': 'user',
                        'parts': [
                          {'text': text}
                        ],
                      }
                    ],
                    'turnComplete': true,
                  }
                }),
              );
            }
            final error = message['error'];
            if (error is Map<String, dynamic>) {
              final statusCode = error['code'];
              if (statusCode is int) {
                throw GeminiApiException(
                  statusCode,
                  error['message']?.toString() ?? 'Gemini Live request failed.',
                );
              }
              final status = error['status'];
              if (status == 'UNAVAILABLE' ||
                  status == 'RESOURCE_EXHAUSTED' ||
                  status == 'DEADLINE_EXCEEDED') {
                throw RetryableGeminiException(
                  error['message']?.toString() ?? 'Gemini Live is temporarily unavailable.',
                );
              }
              throw FormatException(
                error['message']?.toString() ?? 'Gemini Live request failed.',
              );
            }
            final content = message['serverContent'];
            if (content is! Map<String, dynamic>) return;
            final outputTranscription = content['outputTranscription'];
            if (outputTranscription is Map<String, dynamic> &&
                outputTranscription['text'] is String) {
              transcript.write(outputTranscription['text']);
            }
            final modelTurn = content['modelTurn'];
            final parts = modelTurn is Map<String, dynamic>
                ? modelTurn['parts']
                : null;
            if (parts is List) {
              for (final part in parts) {
                if (part is Map<String, dynamic> &&
                    part['inlineData'] is Map<String, dynamic>) {
                  final data =
                      (part['inlineData'] as Map<String, dynamic>)['data'];
                  if (data is String && data.isNotEmpty) {
                    audio.add(base64Decode(data));
                  }
                }
              }
            }
            if (content['interrupted'] == true) audio.clear();
            if (content['turnComplete'] == true && !reply.isCompleted) {
              final pcm = audio.takeBytes();
              if (pcm.isEmpty) {
                throw const FormatException('Gemini Live returned no speech audio.');
              }
              reply.complete(
                GeminiLiveReply(
                  transcript: transcript.toString(),
                  pcm: pcm,
                ),
              );
            }
          } catch (error, stackTrace) {
            if (!reply.isCompleted) reply.completeError(error, stackTrace);
          }
        },
        onError: (Object error, StackTrace stackTrace) {
          if (!reply.isCompleted) {
            reply.completeError(
              RetryableGeminiException(
                'Gemini Live connection failed. Check your key, model access, and network.',
              ),
              stackTrace,
            );
          }
        },
        onDone: () {
          if (!reply.isCompleted) {
            reply.completeError(
              const RetryableGeminiException(
                'Gemini Live closed before finishing the reply.',
              ),
            );
          }
        },
        cancelOnError: false,
      );
      try {
        await channel.ready.timeout(const Duration(seconds: 20));
        channel.sink.add(
          jsonEncode({
            'setup': {
              'model': 'models/gemini-3.8-live',
              'responseModalities': ['AUDIO'],
              'systemInstruction': {
                'parts': [
                  {
                    'text': 'You are Jiva, a helpful Hindi and multilingual phone assistant. Speak naturally and briefly. Never claim an action was completed unless the user has confirmed it in the app.'
                  }
                ]
              },
              'generationConfig': {
                'speechConfig': {
                  'voiceConfig': {
                    'prebuiltVoiceConfig': {'voiceName': voice}
                  }
                }
              }
            }
          }),
        );
        return await reply.future.timeout(const Duration(seconds: 75));
      } finally {
        await subscription.cancel();
        await channel.sink.close();
        if (identical(_activeChannel, channel)) _activeChannel = null;
      }
    }, onRetry: (attempt, error) {
      if (_requestGeneration != generation) {
        throw StateError('Gemini request cancelled.');
      }
      onRetry?.call(attempt, error);
    });
  }
}
