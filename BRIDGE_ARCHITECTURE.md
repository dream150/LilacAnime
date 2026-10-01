# Local AI native bridge architecture

The Snapdragon runtime owns llama.cpp and the HTP/GPU/CPU backend libraries. The JNI bridge is packaged with the APK and loads those libraries from the selected runtime directory at runtime.

The APK bridge is loaded first. Existing runtime packs that still contain `liblilac_hunyuan_jni.so` remain compatible as a fallback.

Build the APK bridge with `tools/build_app_bridge.sh` using the same llama.cpp checkout used for the Snapdragon runtime. The resulting library is placed in `app/src/main/jniLibs/arm64-v8a/` and is packaged by the Android app module.

The bridge does not statically link llama.cpp. It resolves the llama/ggml C API from the selected runtime directory.
