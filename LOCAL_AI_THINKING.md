# Local AI Thinking OFF / Snapdragon runtime build

현재 로컬 AI는 `llama-server`가 아니라 Snapdragon용 runtime pack 안의 `liblilac_local_ai_jni.so`를 직접 로드합니다.

## 중요한 점

`app/src/main/cpp/lilac_local_ai/lilac_local_ai_jni.cpp`만 수정하고 일반 Android APK 빌드를 하면 실제 기기에서 실행되는 native bridge는 바뀌지 않습니다.

Thinking OFF 수정은 **방법 A: Snapdragon runtime 재빌드**로 적용합니다.

## 적용 순서

1. `llama.cpp` checkout을 준비합니다. 기본 경로는 `$HOME/src/llama.cpp`입니다.
2. 프로젝트 루트에서 다음을 실행합니다.

```bash
./tools/build_snapdragon_runtime.sh
```

필요하면 llama.cpp 경로를 직접 지정합니다.

```bash
LLAMA_ROOT=/path/to/llama.cpp ./tools/build_snapdragon_runtime.sh
```

3. 생성된

```text
build/snapdragon-runtime/lilac-llama-gpu-npu-runtime.zip
```

을 앱의 Local AI runtime 설치 기능으로 설치합니다.

4. 앱을 완전히 재시작한 뒤 로컬 AI 번역을 실행합니다. 기존 runtime과 같은 `id`라도 설치기가 기존 runtime 디렉터리를 삭제하고 새 ZIP으로 교체합니다.

## Thinking OFF 동작

현재 JNI bridge는 구형 `llama_chat_apply_template()` C API를 사용하므로 `chat_template_kwargs`를 직접 전달할 수 없습니다. Thinking OFF에서는 chat template이 assistant 영역에서 `<think>`를 열어 둔 경우 생성 경계에서 `</think>`를 prefill하여 reasoning 영역을 생성하지 않고 최종 답변 영역에서 바로 샘플링하도록 합니다.

이것은 단순히 결과에서 `<think>...</think>`를 삭제하는 것과 다릅니다. 모델이 reasoning을 생성한 뒤 숨기는 것이 아니라, OFF 상태에서는 reasoning 영역의 생성을 건너뛰는 목적입니다.

## 확인 로그

새 bridge가 실제로 로드되면 다음 로그를 확인할 수 있습니다.

```text
JNI_BRIDGE_LOADED ...
THINKING_OFF_PREFILL applied ...
PROMPT_FORMAT ... thinking=off
```

반대로 `THINKING_OFF_PREFILL applied`가 없다고 해서 무조건 실패는 아닙니다. 해당 모델의 chat template이 `<think>`를 생성 프롬프트에 넣지 않는 경우에는 prefill 자체가 필요하지 않습니다.

## Thinking ON

Thinking ON에서는 prefill을 적용하지 않습니다. 모델의 GGUF chat template이 thinking을 기본적으로 활성화하는 경우 기존 reasoning 동작을 그대로 사용합니다.

## 일반 Android build와의 관계

방법 A에서는 Android APK를 다시 빌드할 필요가 없습니다. native bridge를 변경한 경우에는 **runtime ZIP을 다시 빌드하고 앱에 새 runtime을 설치**해야 합니다. Kotlin/UI 변경이 함께 있을 때만 APK를 다시 빌드하면 됩니다.
