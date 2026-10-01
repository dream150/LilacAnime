#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LLAMA_ROOT="${LLAMA_ROOT:-$HOME/src/llama.cpp}"
OUT="${OUT:-$ROOT/build/snapdragon-runtime}"
IMAGE="ghcr.io/snapdragon-toolchain/arm64-android:v0.7"

if [[ ! -d "$LLAMA_ROOT/pkg-adb/llama.cpp/lib" ]]; then
  echo "llama.cpp package not found: $LLAMA_ROOT/pkg-adb/llama.cpp/lib" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/src" "$OUT/build" "$OUT/runtime"
cp "$ROOT/app/src/main/cpp/lilac_hunyuan/lilac_hunyuan_jni.cpp" "$OUT/src/"

if [[ "${INCLUDE_LEGACY_BRIDGE:-0}" == "1" ]]; then
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
      cmake --build /out/build --target lilac_hunyuan_jni -j"$(nproc)"
    '
  cp "$OUT/build/liblilac_hunyuan_jni.so" "$OUT/runtime/"
fi

LIB="$LLAMA_ROOT/pkg-adb/llama.cpp/lib"
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
  "id": "llama-snapdragon-master",
  "name": "llama.cpp Snapdragon CPU/GPU/NPU",
  "version": "$VERSION",
  "abi": "arm64-v8a",
  "jniContract": "lilac-local-ai-v2",
  "libraryFile": "liblilac_hunyuan_jni.so",
  "supportedArchitectures": [],
  "supportedQuantizations": []
}
JSON

(cd "$OUT/runtime" && zip -q -9 -r "$OUT/lilac-llama-snapdragon-runtime.zip" .)
echo "Runtime pack: $OUT/lilac-llama-snapdragon-runtime.zip"
