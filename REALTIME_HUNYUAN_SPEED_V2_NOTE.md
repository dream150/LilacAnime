# HY-MT realtime speed v2

- Keeps HY-MT1.5-1.8B translation quality/model.
- Removes the excessive minimum 192 output-token budget for every request.
- Output token budget is estimated from actual subtitle length and capped at 384.
- Uses up to 8 CPU threads on capable devices.
- Realtime subtitle prefetch batches are reduced to 2 cues so urgent current-cue translation is not blocked by a long batch.
- A current-cue miss cancels the prefetch job and immediately requests the current cue, then resumes prefetching.
- Prefetch is limited to a 75-second look-ahead from the current playback position.
- mpv `sub-text` events remain the realtime trigger; cached translations are displayed immediately.

The 1.25-bit AngelSlim/STQ path is not substituted into this build because its custom llama.cpp STQ1_0 kernel is required; the official deployment instructions use a dedicated llama.cpp PR branch.
