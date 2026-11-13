class VoicePreset {
  const VoicePreset({
    required this.name,
    required this.style,
    required this.perceivedVoice,
  });

  final String name;
  final String style;
  final String perceivedVoice;

  String get label => '$name · $style';
}

const voicePresets = <VoicePreset>[
  VoicePreset(name: 'Charon', style: 'Informative', perceivedVoice: 'Male-sounding'),
  VoicePreset(name: 'Fenrir', style: 'Excitable', perceivedVoice: 'Male-sounding'),
  VoicePreset(name: 'Orus', style: 'Firm', perceivedVoice: 'Male-sounding'),
  VoicePreset(name: 'Alnilam', style: 'Firm', perceivedVoice: 'Male-sounding'),
  VoicePreset(name: 'Gacrux', style: 'Mature', perceivedVoice: 'Male-sounding'),
  VoicePreset(name: 'Kore', style: 'Firm', perceivedVoice: 'Female-sounding'),
  VoicePreset(name: 'Leda', style: 'Youthful', perceivedVoice: 'Female-sounding'),
  VoicePreset(name: 'Aoede', style: 'Breezy', perceivedVoice: 'Female-sounding'),
  VoicePreset(name: 'Despina', style: 'Smooth', perceivedVoice: 'Female-sounding'),
  VoicePreset(name: 'Laomedeia', style: 'Upbeat', perceivedVoice: 'Female-sounding'),
];

const defaultVoiceName = 'Kore';

VoicePreset voicePresetFor(String name) => voicePresets.firstWhere(
      (voice) => voice.name == name,
      orElse: () => voicePresets.firstWhere(
        (voice) => voice.name == defaultVoiceName,
      ),
    );
