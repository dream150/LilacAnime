# Japanese -> Korean VN 7B local translation

The local subtitle translator now uses `hell0ks/ja-ko-vn-7b-v1-gguf` Q4_K_M through the prebuilt llama.cpp Android AAR.

Model: `model-Q4_K_M.gguf`
Approximate model size: 4.63 GB
Model source: Hugging Face `hell0ks/ja-ko-vn-7b-v1-gguf`
Runtime: `dev.ffmpegkit-maintained:llama-android:0.1.1`

The model is downloaded on first use into the app private `files/models` directory, or can be imported from a local GGUF file in Settings.

The model remains loaded while translation is in use so subtitle windows do not reload the 7B weights for every group.
