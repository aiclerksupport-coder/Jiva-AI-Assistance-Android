import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:jiva_assistance/domain/voice_preset.dart';
import 'package:jiva_assistance/services/gemini_live_client.dart';

void main() {
  test('provides ten selectable voice presets in two five-voice groups', () {
    expect(voicePresets, hasLength(10));
    expect(
      voicePresets.where((voice) => voice.perceivedVoice == 'Male-sounding'),
      hasLength(5),
    );
    expect(
      voicePresets.where((voice) => voice.perceivedVoice == 'Female-sounding'),
      hasLength(5),
    );
    expect(voicePresetFor('unknown').name, defaultVoiceName);
  });

  test('wraps Gemini PCM in a valid mono 24 kHz WAV header', () {
    final wav = GeminiLiveReply(
      transcript: 'Hello',
      pcm: Uint8List.fromList([1, 2, 3, 4]),
    ).toWav();
    final header = ByteData.sublistView(wav);

    expect(String.fromCharCodes(wav.sublist(0, 4)), 'RIFF');
    expect(String.fromCharCodes(wav.sublist(8, 12)), 'WAVE');
    expect(header.getUint32(4, Endian.little), 40);
    expect(header.getUint16(22, Endian.little), 1);
    expect(header.getUint32(24, Endian.little), 24000);
    expect(header.getUint16(34, Endian.little), 16);
    expect(header.getUint32(40, Endian.little), 4);
    expect(wav.sublist(44), [1, 2, 3, 4]);
  });
}
