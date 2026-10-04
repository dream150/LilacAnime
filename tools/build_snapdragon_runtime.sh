#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LLAMA_ROOT="${LLAMA_ROOT:-$HOME/src/llama.cpp}"
OUT="${OUT:-$ROOT/build/snapdragon-runtime}"
IMAGE="${IMAGE:-ghcr.io/snapdragon-toolchain/arm64-android:v0.7}"
BRIDGE_REV="${BRIDGE_REV:-3}"

# llama.cpp packaging layout differs between the adb and Android builds.
# Prefer pkg-adb when present, otherwise use pkg-android.
if [[ -d "$LLAMA_ROOT/pkg-adb/llama.cpp/lib" ]]; then
  LLAMA_LIB_ROOT="$LLAMA_ROOT/pkg-adb/llama.cpp"
elif [[ -d "$LLAMA_ROOT/pkg-android/llama.cpp/lib" ]]; then
  LLAMA_LIB_ROOT="$LLAMA_ROOT/pkg-android/llama.cpp"
else
  echo "llama.cpp package not found. Checked:" >&2
  echo "  $LLAMA_ROOT/pkg-adb/llama.cpp/lib" >&2
  echo "  $LLAMA_ROOT/pkg-android/llama.cpp/lib" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/src" "$OUT/build" "$OUT/runtime"
cp "$ROOT/app/src/main/cpp/lilac_local_ai/lilac_local_ai_jni.cpp" "$OUT/src/"

docker run --rm --platform linux/amd64 \
  -v "$ROOT:/project" \
  -v "$LLAMA_ROOT:/llama" \
  -v "$OUT:/out" \
  "$IMAGE" bash -lc '
    set -e
    NDK="$(find /opt -path "*/build/cmake/android.toolchain.cmake" -print -quit)"
    if [[ -z "$NDK" ]]; then echo "Android NDK toolchain not found" >&2; exit 2; fi
    cmake -S /project/tools/native-wrapper -B /out/build \
      -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="$NDK" \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-28 \
      -DCMAKE_BUILD_TYPE=Release \
      -DLLAMA_ROOT=/llama
    cmake --build /out/build --target lilac_local_ai_jni -j"$(nproc)"
  '

BRIDGE_SO="$OUT/build/liblilac_local_ai_jni.so"
[[ -f "$BRIDGE_SO" ]] || { echo "native bridge build failed: $BRIDGE_SO" >&2; exit 4; }
cp "$BRIDGE_SO" "$OUT/runtime/"
# Keep a small build marker next to the JNI bridge so it is obvious which
# native bridge revision is actually inside the installed runtime pack.
printf '%s\n' "${BRIDGE_REV}" > "$OUT/runtime/lilac_local_ai_jni.bridge-revision"
LIB="$LLAMA_LIB_ROOT/lib"
for f in \
  libggml-base.so \
  libggml-cpu.so \
  libggml-opencl.so \
  libggml-hexagon.so \
  libggml.so \
  libllama-common.so \
  libllama.so \
  libggml-htp-v73.so \
  libggml-htp-v75.so \
  libggml-htp-v79.so \
  libggml-htp-v81.so; do
  [[ -f "$LIB/$f" ]] || { echo "missing $f" >&2; exit 3; }
  cp "$LIB/$f" "$OUT/runtime/$f"
done

VERSION="$(cd "$LLAMA_ROOT" && git describe --tags --always 2>/dev/null || echo unknown)"
cat > "$OUT/runtime/runtime.json" <<JSON
{
  "id": "llama-snapdragon-gpu-npu",
  "name": "llama.cpp GPU/NPU",
  "version": "${VERSION}-bridge-${BRIDGE_REV}",
  "abi": "arm64-v8a",
  "jniContract": "lilac-local-ai-v3",
  "libraryFile": "liblilac_local_ai_jni.so",
  "nativeLibraries": [
    "libggml-base.so",
    "libggml-cpu.so",
    "libggml-opencl.so",
    "libggml-hexagon.so",
    "libggml.so",
    "libllama-common.so",
    "libllama.so",
    "libggml-htp-v73.so",
    "libggml-htp-v75.so",
    "libggml-htp-v79.so",
    "libggml-htp-v81.so"
  ],
  "backendOrder": ["npu", "gpu", "cpu"],
  "supportedArchitectures": [],
  "supportedQuantizations": []
}
JSON

(cd "$OUT/runtime" && zip -q -9 -r "$OUT/lilac-llama-gpu-npu-runtime.zip" .)
echo "Runtime pack: $OUT/lilac-llama-gpu-npu-runtime.zip"
echo "JNI bridge: $OUT/runtime/liblilac_local_ai_jni.so"
echo "Bridge revision: $BRIDGE_REV"
echo "Install this ZIP through the app's Local AI runtime installer; do not copy the .so into the APK."
