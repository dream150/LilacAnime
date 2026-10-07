# Offline / Streaming / Autoplay / Gemini fix

- Foreground PlayerScreen now releases its libmpv engine immediately when leaving instead of waiting for Compose disposal.
- Added bounded playback watchdog to re-assert play after FILE_LOADED/READY when a new Surface or HLS replacement leaves mpv paused.
- Gemini provider now discovers models available to the API key first and uses a compatibility-first GenerateContent request (plain marker response first, JSON MIME fallback) instead of requiring responseSchema/thinking settings on every model.
- Gemini errors retain HTTP status and API error message.
