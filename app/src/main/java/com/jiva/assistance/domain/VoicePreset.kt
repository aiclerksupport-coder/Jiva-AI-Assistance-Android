package com.jiva.assistance.domain

data class VoicePreset(
    val voiceName: String,
    val label: String,
    val perceivedVoice: String,
)

object VoicePresets {
    val all = listOf(
        VoicePreset("Charon", "Charon · Informative", "Male-sounding"),
        VoicePreset("Fenrir", "Fenrir · Excitable", "Male-sounding"),
        VoicePreset("Orus", "Orus · Firm", "Male-sounding"),
        VoicePreset("Alnilam", "Alnilam · Firm", "Male-sounding"),
        VoicePreset("Gacrux", "Gacrux · Mature", "Male-sounding"),
        VoicePreset("Kore", "Kore · Firm", "Female-sounding"),
        VoicePreset("Leda", "Leda · Youthful", "Female-sounding"),
        VoicePreset("Aoede", "Aoede · Breezy", "Female-sounding"),
        VoicePreset("Despina", "Despina · Smooth", "Female-sounding"),
        VoicePreset("Laomedeia", "Laomedeia · Upbeat", "Female-sounding"),
    )

    val default = all.first { it.voiceName == "Kore" }

    fun find(voiceName: String): VoicePreset =
        all.firstOrNull { it.voiceName == voiceName } ?: default
}
