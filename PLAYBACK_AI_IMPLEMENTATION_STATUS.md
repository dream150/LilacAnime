# Playback AI implementation status

## Implemented in this revision

- Added persisted `playbackAiMode` setting: `default`, `anime4k`, `whisper`, `animejanai`.
- Settings UI now lets the user select default mpv, Anime4K, or Whisper subtitle generation. AnimeJaNai remains disabled because no Android inference runtime/model integration exists in the supplied source.
- Added an on-device Whisper model download (`ggml-base.bin`) to app-private storage.
- Added JNI bridge to whisper.cpp. CMake FetchContent pins upstream whisper.cpp v1.7.6 and builds it for the Android ABIs declared by the app Gradle config.
- Added audio extraction and resampling to mono 16 kHz PCM WAV, then Japanese transcription to SRT.
- HLS `.m3u8` sources are first passed through Media3 Transformer to export an audio-only MP4, which is decoded to WAV.
- The generated SRT is attached to the current mpv player and handed to the existing `RealtimeSubtitleTranslator` by switching subtitle translation mode to Korean.
- Added logging under `LilacWhisper` for model/transcription stages and failures.

## Limitations that remain

- The video playback renderer itself is still mpv. Media3 is currently used for HLS audio extraction, not as the full playback engine. Replacing the renderer safely requires adapting the existing player control/state/subtitle surface, and is not claimed complete here.
- HLS sources requiring custom request headers may fail in Media3 Transformer because this version uses its default HTTP data source for the transform. Direct-media extraction passes the supplied headers.
- Native CMake uses FetchContent to retrieve whisper.cpp at build time, so the first native build needs network access. This environment has no GitHub network access; the native binary and a full Android build could not be produced or verified here.
- AnimeJaNai Android inference remains unimplemented; its UI option is disabled rather than silently using a different model.
