package com.lilac.anime.data.subtitle.whisper

internal object WhisperNative {
    init { System.loadLibrary("lilac_whisper") }
    external fun transcribeWav(modelPath: String, wavPath: String): String
}
