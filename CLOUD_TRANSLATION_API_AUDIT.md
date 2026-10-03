# Cloud translation API integration

- Added Gemini through the official `generateContent` REST endpoint, including the API key header, `system_instruction`, response parsing, configurable model ID, and HTTP error details.
- Updated OpenAI to use a public API model default (`gpt-4.1-mini`) rather than the invalid `gpt-5.6-luna` name; model ID is configurable.
- DeepL now chooses the Free endpoint for keys ending in `:fx`, uses one API text entry per subtitle cue, and validates response count.
- Qwen supports configurable model ID and China/International DashScope endpoints.
- Cloud subtitle-file translation uses batches of up to 10 cues to reduce API request overhead. Local translation keeps its existing per-cue contextual workflow.
- Playback-time Korean subtitle translation can use the selected cloud provider as well as local AI. Cloud playback requests one cue at a time and does not send local context cues.
- API failures are no longer silently converted into the original input in `TranslationManager.translateBatch`; errors are propagated to the caller. Realtime playback logs provider errors and limits retries for a cue.
- Translation cache keys include the selected Gemini/OpenAI/Qwen model so changing model does not reuse a result from a different model.
- API keys continue to be stored encrypted using Android Keystore.

## Verification limits

The marked-output parser was tested with multiple cues and a single cue without markers. Full Android compilation could not run in this environment because Gradle 9.0.0 could not be downloaded (network DNS unavailable). Real provider calls were not made because no user API keys are available; use the settings screen's API Key test action to verify each account/model/region.
