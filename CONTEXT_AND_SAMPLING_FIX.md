# Context input and sampling controls

- File translation and real-time translation each invoke the local model once per current cue. The previous `pref_ai_context_cues` cues are supplied as source-language reference text; they are not mapped into the output cue.
- The default English prompt now uses the official HY-MT contextual-translation layout: context first, explicit instruction not to translate/repeat the context, then the current subtitle.
- `LocalTranslator` emits `PROMPT_INPUT`, `MODEL_RAW_OUTPUT`, and `PARSED_OUTPUT` debug logs (truncated) so the exact context/current subtitle input can be checked in Logcat.
- Temperature, top-p, top-k, and repetition penalty are persisted and passed to the native bridge. The default values follow HY-MT1.5 recommendations: 0.7, 0.6, 20, 1.05.
- **Rebuild the APK JNI bridge before expecting sampling controls to affect inference.** `app/src/main/jniLibs/arm64-v8a/liblilac_hunyuan_jni.so` in this archive is the prior compiled bridge; it does not yet read the new sampling arguments. Rebuild it with `tools/build_app_bridge.sh` and the matching llama.cpp checkout `b11312-10-g3aa0ce9bc`, then build the Android app. The native source is updated, but this environment did not contain the llama.cpp headers needed to rebuild the `.so`.
- Sampling controls currently apply to the native llama.cpp runtime. The bundled `llama-android` API does not expose those sampling options through its current `Llama.complete` call.
