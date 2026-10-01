# Hunyuan-MT Android integration

LilacAnime uses Tencent HY-MT1.5-1.8B through the GGUF build and the bundled llama-android AAR.

- Model: `HY-MT1.5-1.8B-Q4_K_M.gguf`
- Runtime: `dev.ffmpegkit-maintained:llama-android:0.1.1`
- Model storage: `files/models/`
- Default provider: `local`
- Automatic subtitle translation uses the existing subtitle parser/cache and preserves subtitle timing.
- ASS/VTT/SRT and the other existing subtitle formats are rendered back into their original format.
- The player starts without waiting for the complete model translation. A completed translated subtitle file is attached to mpv when ready.
- The model is downloaded on demand and is not packaged into the APK.
