# Bundled Whisper.cpp native sources

This app's `module.toml` build does not run Gradle or CMake. The Whisper.cpp
and GGML CPU implementation sources needed for the `lilac_whisper` JNI library
are therefore vendored under `app/src/main/native-whisper/` (headers) and `app/src/main/cpp/whisper_engine/compile/` (compile units).

- `app/src/main/cpp/whisper_engine/compile/whisper/`: Whisper inference implementation and ANEForge no-op stub.
- `app/src/main/cpp/whisper_engine/compile/ggml-base/`: GGML core, threading, quantization, backend and GGUF sources.
- `app/src/main/cpp/whisper_engine/compile/ggml-cpu/`: CPU backend, common kernels and ARM64 quantization/repack sources.
- `include/`, `src/`, `ggml/include/`, `ggml/src/`: upstream public/internal headers.
- `ggml/src/ggml-version.h`: generated-header equivalent for the source ZIP version
  (GGML 0.27.0).

The NDK configuration in `app/module.toml` compiles these local source directories
and links them into `liblilac_whisper.so`; no build-time Git download is required.
The model `.bin` files remain separate runtime downloads and are not part of this
source bundle.

Source upstream: https://github.com/ggml-org/whisper.cpp
