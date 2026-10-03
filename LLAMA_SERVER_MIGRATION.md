# Local AI llama-server migration

The app now runs `llama-server` as a child process from the installed Snapdragon runtime pack and talks to `http://127.0.0.1:<port>/v1/chat/completions`.

The existing Snapdragon libraries are reused unchanged. The runtime pack must contain `bin/llama-server` from the Android Snapdragon build plus the existing `libggml-*`, `libllama*`, and HTP libraries.

Thinking is selected per request:
- `off` -> `chat_template_kwargs: {"enable_thinking": false}`
- `on` -> `chat_template_kwargs: {"enable_thinking": true}`
- `auto` -> omit the kwarg

Changing prompts, thinking mode, sampler settings, or model selection no longer requires rebuilding the JNI bridge. A runtime rebuild is only needed when updating the actual llama.cpp/server binary or Snapdragon libraries.

Build/install:
```bash
LLAMA_ROOT=/home/gongiainr/src/llama.cpp ./tools/build_snapdragon_runtime.sh
```
The script uses `pkg-adb` first and `pkg-android` second, and expects `pkg-android/llama.cpp/bin/llama-server`.
