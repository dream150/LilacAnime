# Snapdragon JNI Fix 2

Fixes observed on-device after the first successful native build.

1. Removes the hard JNI dependency on `llama_version`, which is not required for inference and was missing from the loaded shared library at runtime.
2. Opens `libggml.so` and `libllama.so` by absolute runtime paths instead of relying on the Android linker search path.
3. Adds explicit AUTO device preference: Hexagon HTP -> Adreno/OpenCL GPU -> CPU.
4. Logs the selected backend with `BACKEND_SELECTED`.
5. Declares Qualcomm's vendor `libOpenCL.so` as an optional native library in the Android manifest. This is required for apps targeting Android 12+ to access vendor-provided non-NDK native libraries when available, while keeping non-Qualcomm installation possible.

The subtitle translation architecture is unchanged: one current cue per model request with configurable preceding context.
