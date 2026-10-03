# 방법 A — Snapdragon llama.cpp runtime 재빌드

이 프로젝트의 로컬 AI native bridge는 APK의 일반 Kotlin 빌드 대상이 아니라 별도 runtime pack으로 배포됩니다. 따라서 native JNI를 수정했으면 runtime을 다시 빌드해야 합니다.

## 1. 요구사항

- Linux 환경
- Docker
- Snapdragon toolchain image 접근
- `$HOME/src/llama.cpp`에 llama.cpp checkout
- llama.cpp의 `pkg-adb/llama.cpp/lib` 또는 `pkg-android/llama.cpp/lib`에 Snapdragon runtime `.so` 파일

## 2. 빌드

프로젝트 루트에서:

```bash
./tools/build_snapdragon_runtime.sh
```

다른 llama.cpp 위치:

```bash
LLAMA_ROOT=/home/gongiainr/src/llama.cpp ./tools/build_snapdragon_runtime.sh
```

생성물:

```text
build/snapdragon-runtime/lilac-llama-gpu-npu-runtime.zip
```

ZIP 안에는 최소한 다음이 들어갑니다.

```text
liblilac_local_ai_jni.so
libggml-base.so
libggml-cpu.so
libggml-opencl.so
libggml-hexagon.so
libggml.so
libllama-common.so
libllama.so
runtime.json
lilac_local_ai_jni.bridge-revision
```

## 3. 앱에 적용

앱의 Local AI runtime 설치 UI에서 새 ZIP을 설치합니다. 설치기는 같은 runtime `id`의 기존 디렉터리를 삭제한 후 새 runtime을 설치하므로 이전 `liblilac_local_ai_jni.so`가 남지 않습니다.

설치 후 앱을 완전히 종료했다가 다시 실행하세요. 이미 로드된 native library는 프로세스가 살아있는 동안 교체할 수 없습니다.

## 4. Thinking OFF 확인

번역 시작 시 logcat에서:

```text
LocalAiNative: JNI_BRIDGE_LOADED
LilacLocalAiJNI: THINKING_OFF_PREFILL applied
LilacLocalAiJNI: PROMPT_FORMAT ... thinking=off
```

를 확인합니다.

`THINKING_OFF_PREFILL`이 보이면 수정된 JNI bridge가 실행되고 있는 것입니다.


## 패키지 경로 자동 선택

빌드 스크립트는 다음 순서로 native runtime 라이브러리를 찾습니다.

1. `$LLAMA_ROOT/pkg-adb/llama.cpp/lib`
2. `$LLAMA_ROOT/pkg-android/llama.cpp/lib`

따라서 현재 환경에서는 별도 옵션 없이:

```bash
LLAMA_ROOT=/home/gongiainr/src/llama.cpp ./tools/build_snapdragon_runtime.sh
```

를 실행하면 됩니다.
