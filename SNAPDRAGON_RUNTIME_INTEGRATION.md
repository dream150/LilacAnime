# Snapdragon llama.cpp runtime integration

This project keeps `dev.ffmpegkit-maintained:llama-android:0.1.1` as the universal CPU fallback and adds a user-installable Snapdragon runtime pack.

The runtime pack is built from the current `~/src/llama.cpp/pkg-adb/llama.cpp/lib` output produced by `scripts/snapdragon/build.py --target adb`.

## Build the runtime pack

From the Chromebook Linux terminal:

```bash
cd ~/src/LilacAnime
chmod +x tools/build_snapdragon_runtime.sh
./tools/build_snapdragon_runtime.sh
```

If the project is in another directory, run the script from that project directory. The script expects the llama.cpp checkout at `~/src/llama.cpp`; override it with `LLAMA_ROOT=/path/to/llama.cpp` if necessary.

The output is:

```text
build/snapdragon-runtime/lilac-llama-snapdragon-runtime.zip
```

Install that ZIP from **AI 설정 → Runtime 추가**.

## Runtime behavior

- Qualcomm device + installed Snapdragon pack: AUTO selects the Snapdragon runtime.
- Snapdragon runtime exposes llama.cpp's CPU, Adreno OpenCL and Hexagon HTP backends.
- AUTO passes all available devices to llama.cpp; the backend scheduler can select supported acceleration and fall back to CPU per operation.
- HTP libraries are kept as DSP-side files and are not loaded with `System.load()`.
- `ADSP_LIBRARY_PATH` points to the app-private runtime directory so Hexagon can locate the matching `libggml-htp-vNN.so`.
- If the Snapdragon runtime cannot initialize or load the model, AUTO falls back to the bundled llama-android CPU runtime.
- Non-Qualcomm devices do not select the Snapdragon runtime.

The native bridge keeps one model/context alive for the translation session and clears the KV memory between individual subtitle requests. It therefore does not recreate the model for every cue.
