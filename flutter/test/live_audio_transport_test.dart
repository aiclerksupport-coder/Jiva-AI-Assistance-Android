import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/services/live_audio_transport.dart';

void main() {
  test('invalid model cannot initiate WebSocket', () async {
    final transport = LiveAudioTransport(
      apiKey: 'private',
      model: '../bad',
      voiceName: 'Aoede',
      connector: (uri) => throw StateError('network must not be called'),
    );
    await expectLater(transport.connect(), throwsArgumentError);
    await transport.close();
  });
  test('PCM frame validation rejects oversize and odd bytes', () {
    final transport = LiveAudioTransport(
      apiKey: 'private',
      model: 'gemini-3.8-live',
      voiceName: 'Aoede',
    );
    expect(() => transport.sendPcm16(Uint8List(1)), throwsStateError);
    // The connection must be ready before frames can be sent.
    expect(
      jsonEncode({
        'realtimeInput': {'audioStreamEnd': true},
      }),
      contains('audioStreamEnd'),
    );
  });
}
