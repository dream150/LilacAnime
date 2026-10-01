# HY-MT realtime v3

This revision keeps the Q4_K_M model for the currently bundled llama-android runtime and focuses on keeping the high-quality model warm while making subtitle translation lead playback.

- Realtime subtitle events remain the display trigger.
- Subtitle files are parsed ahead of playback and translated in groups of 6 cues.
- Prefetch window is 120 seconds.
- A cache miss no longer cancels the existing prefetch worker.
- The worker is not duplicated while an existing prefetch job is active.
- Model context is 4096 tokens.
- The runtime exposes a benchmark helper for warm inference measurements.

Tencent/AngelSlim's 1.25-bit STQ1_0 model is not falsely bundled as a normal llama-android model. Tencent states that it requires llama.cpp PR #22836. The 2-bit model similarly requires the Q2_0c kernel/PR #19357. A real native STQ build must therefore replace the bundled llama-android native runtime rather than only swapping a GGUF file.
