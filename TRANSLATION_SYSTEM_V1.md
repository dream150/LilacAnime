# Translation System V1

- Default provider: TranslateGemma 4B Japanese -> Korean on-device translation via LiteRT-LM CPU. The model is downloaded on first use and cached in app-private storage.
- Optional providers: OpenAI Responses API, DeepL text translation API, Qwen OpenAI-compatible API.
- API keys are encrypted with AES-GCM using an Android Keystore key; plaintext keys are not persisted or logged.
- Translation cache is keyed from SHA-256(source subtitle bytes) + provider + target + anime/episode identity.
- Subtitle cues are batched into ~20-second windows (maximum 24 cues) before translation.
- Supported format-preserving translation: ASS/SSA, SRT, VTT, SMI/SAMI, SBV.
- ASS header/style data is retained and leading inline override tags are retained.
- Player can automatically translate a selected Jimaku subtitle when automatic translation is enabled.
- Jimaku remains manual/user-selectable; no personal server is used.


## TranslateGemma 4B

- Android uses the CPU-compatible INT4 TranslateGemma 4B LiteRT-LM bundle.
- The model is downloaded on first translation and stored under the app's private `files/models/` directory.
- The model is not packaged inside the APK because the bundle is about 2 GB.
- Translation after the initial model download is local/offline.
