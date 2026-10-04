# LilacAnime Local AI Architecture

## Goal

The local AI stack is split into three independent layers:

1. **Runtime** — llama.cpp + Snapdragon/CPU/GPU backend. It must not contain model-family rules.
2. **Model adapter** — model-family/template quirks such as Qwen thinking control.
3. **Translation/application** — subtitle prompts, context and output validation.

## Adding a model family

Add a `LocalAiModelAdapter` in `data/subtitle/translation/localai/LocalAiModelAdapter.kt` and register it in `LocalAiAdapterRegistry`. The adapter should only implement behavior that is actually model-specific. The runtime should not be changed for prompt/template quirks.

## Runtime packs

`runtime.json` can declare:

- `nativeLibraries`: libraries required by the pack
- `backendOrder`: preferred GGML backends, e.g. `npu`, `gpu`, `cpu`
- `supportedArchitectures`: optional GGUF architecture allow-list
- `supportedQuantizations`: optional tensor/quantization allow-list

The JNI bridge receives the backend order and tries the declared devices in order. The default fallback remains NPU → GPU → CPU.

## Qwen

Qwen-specific `enable_thinking` handling belongs to `QwenModelAdapter`. The native bridge only applies the supplied chat template; it does not know what Qwen, Gemma, Llama or another model family is.

## Model profiles (v2)

The first supported families are intentionally small and explicit:

- `hy-mt` — translation-first HY-MT family
- `qwen` — Qwen family, including Qwen thinking-template handling
- `gemma` — Gemma family
- `generic` — safe fallback for other llama.cpp-compatible GGUFs

`LocalAiModelProfiles.resolve()` detects the profile from repository/file name,
GGUF architecture and chat-template metadata. A profile declares capabilities
and generation defaults; the adapter implements only family-specific prompt or
output behavior.

Adding another family should normally require only:

1. Add a `LocalAiModelProfile`.
2. Add a `LocalAiModelAdapter` when template/output behavior differs.
3. Register the adapter.

The native runtime must remain unaware of the family. Runtime compatibility is
still determined separately from the GGUF architecture/quantization metadata.
