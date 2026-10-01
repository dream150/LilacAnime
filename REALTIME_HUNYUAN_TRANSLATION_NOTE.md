# Real-time Hunyuan subtitle translation

HY-MT1.5 local translation now uses mpv `sub-text` events for the visible subtitle and a background prefetch queue for the selected subtitle file.

The local provider no longer translates the entire subtitle file before playback. The source subtitle remains loaded in mpv, its visibility is disabled while local auto translation is enabled, and the translated text is rendered by the player overlay.

The prefetch worker parses SRT, VTT, and ASS subtitles, translates upcoming cues in batches of 8, and stores translations by normalized subtitle text. When mpv emits a new `sub-text`, the player first uses the prefetched result and only falls back to an immediate single-cue translation when the cache has not reached that cue yet.

The existing non-local translation providers continue to use the previous whole-file translation path.

The Android Gradle build could not be executed in this environment because the Gradle wrapper attempted to download Gradle while outbound network access was unavailable.
