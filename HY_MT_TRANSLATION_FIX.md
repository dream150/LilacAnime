# HY-MT1.5 translation runtime fix

The Snapdragon runtime package supplied separately is kept unchanged. The APK JNI bridge is responsible for chat formatting and tokenization.

The bridge detects the official HY-MT1.5 Hunyuan chat template and reproduces its Jinja semantics directly. In particular, `add_generation_prompt=false` ends the prompt with `<｜hy_place▁holder▁no▁8｜>`, matching the model template instead of using llama.cpp's heuristic built-in formatter.

The resulting prompt is tokenized with `parse_special=true`. Because the official template already contains the Hunyuan BOS control token, this path uses `add_special=false` to avoid adding another BOS token.

Sampling uses temperature 0.7, top-k 20, top-p 0.8, and the existing fixed seed. These values match the model's published generation configuration where applicable.

The existing `llama-snapdragon-master` v1 runtime can remain installed. The app attempts the APK bridge first and uses the runtime package only for the llama/ggml backend libraries.

Build the APK bridge with `tools/build_app_bridge.sh` against the exact llama.cpp revision shown by the supplied runtime (`b11312-10-g3aa0ce9bc`). The supplied runtime ZIP does not contain llama.cpp headers, so it is not possible to build the replacement bridge from the runtime ZIP alone.
