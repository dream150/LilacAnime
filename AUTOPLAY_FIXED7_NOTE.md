# Autoplay Fixed7

- Keeps the original offline OP/ED analysis implementation unchanged.
- Keeps manual previous/next episode switching via mpv `loadfile ... replace`.
- Autoplay now listens to a single de-duplicated playback completion signal.
- Normal completion: `MPV_EVENT_END_FILE`.
- HLS fallback: `eof-reached` property.
- EOF and END_FILE for the same mpv load generation cannot advance twice.
- END_FILE/EOF emitted while replacing an episode are suppressed until `START_FILE`.
- New episode playback remains generation-checked and starts after `FILE_LOADED`.

Build verification in this environment could not reach services.gradle.org to download Gradle 9.0.0.
