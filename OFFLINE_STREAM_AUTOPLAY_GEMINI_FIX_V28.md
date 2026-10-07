# v28 fix

- Foreground libmpv session is recreated for each PlayerScreen entry; background audio retains its engine.
- Offline MP4, Re:Anime HLS, and next-episode autoplay now share the same libmpv completion signal.
- PlayerScreen collects `playbackEndedEvents` instead of polling only `endFileEventGeneration`.
- `eof-reached` now emits the completion signal once a real media generation has loaded.
- Gemini no longer assumes a hard-coded model. It lists the API key's `generateContent` models and prioritizes Gemini Flash models.
- Gemini thinking configuration is only sent when the model reports thinking support. Gemini 3 uses MINIMAL; earlier thinking models use a zero budget.
- Gemini uses documented `?key=` authentication, JSON response mode without a fragile response schema first, then fallback requests.
- Gemini API errors now include HTTP status and Google's error message.
