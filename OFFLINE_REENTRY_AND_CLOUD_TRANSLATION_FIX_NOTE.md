# Offline re-entry / cloud translation fix

This revision changes two areas:

## Offline player re-entry
- Foreground `PlayerScreen` no longer reuses a stopped process-wide libmpv instance.
- `MpvPlaybackManager.foregroundEngine()` creates a fresh libmpv session for each foreground player entry.
- The foreground engine is released after the player Surface is detached.
- Background-audio/PiP continuation keeps the existing process-wide engine.

This is intended to fix local/offline playback failing after leaving the player and opening the same downloaded episode again.

## Cloud translation
The Android OpenAI/Gemini request flow is aligned more closely with the desktop translator:
- OpenAI Responses API: JSON Schema -> JSON object -> unrestricted fallback.
- OpenAI reasoning effort is low for o-series/GPT-5 models.
- Gemini generateContent: structured JSON schema -> structured JSON without thinking config -> JSON MIME fallback.
- Gemini model discovery follows pagination and filters non-text generation models.
- Gemini 2.5 uses a zero/low thinking budget suitable for subtitle translation.
- Existing Qwen/DeepL providers and translation response parser remain compatible.

The project was not built with CodeAssist in this environment; the final ZIP was checked for archive integrity and the modified provider files were syntax-checked with the available Kotlin compiler (dependency-resolution errors are expected outside the Android build environment).
