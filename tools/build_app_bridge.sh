#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LLAMA_ROOT="${LLAMA_ROOT:-$HOME/src/llama.cpp}"
OUT="${OUT:-$ROOT/build/app-bridge}"
IMAGE="ghcr.io/snapdragon-toolchain/arm64-android:v0.7"
EXPECTED_LLAMA_VERSION="b11312-10-g3aa0ce9bc"

if [[ ! -d "$LLAMA_ROOT/include" || ! -d "$LLAMA_ROOT/ggml/include" ]]; then
  echo "llama.cpp headers not found: $LLAMA_ROOT" >&2
  exit 1
fi
ACTUAL_LLAMA_VERSION="$(git -C "$LLAMA_ROOT" describe --tags --always 2>/dev/null || true)"
if [[ "$ACTUAL_LLAMA_VERSION" != *"$EXPECTED_LLAMA_VERSION"* ]]; then
  echo "llama.cpp version mismatch: expected $EXPECTED_LLAMA_VERSION, got ${ACTUAL_LLAMA_VERSION:-unknown}" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/build" "$ROOT/app/src/main/jniLibs/arm64-v8a"

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

cp "$OUT/build/liblilac_hunyuan_jni.so" "$ROOT/app/src/main/jniLibs/arm64-v8a/liblilac_hunyuan_jni.so"
echo "APK bridge: $ROOT/app/src/main/jniLibs/arm64-v8a/liblilac_hunyuan_jni.so"
