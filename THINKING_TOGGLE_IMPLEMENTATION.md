# Thinking ON/OFF

Local GGUF translation has a persistent `PlayerSettings.aiThinkingEnabled` option.

- OFF (default): asks the model to translate directly without a `<think>` block.
- ON: allows internal reasoning inside `<think>...</think>` and strips that block before the subtitle is shown.
- Preference key: `pref_ai_thinking_enabled`.
- The bundled JNI runtime has no universal native reasoning-budget parameter, so this implementation intentionally uses a model-agnostic prompt-level switch rather than pretending to expose a native llama.cpp reasoning-token budget.
