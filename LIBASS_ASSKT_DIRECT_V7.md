# Libass ass-kt direct integration v7

- Removed the app-local `NativeLibass` JNI bridge.
- Removed `lilac_libass_jni.cpp` and its CMake file.
- Uses `io.github.peerless2012:ass-kt:0.5.1` directly from Kotlin.
- `ass-kt` supplies its own `asskt` native bridge and wraps libass `Ass`, `AssTrack`, and `AssRender` APIs.
- The existing `io.github.peerless2012:ass:0.5.1` dependency supplies the libass Prefab/native dependency required by ass-kt.
- Translated ASS events are injected with `AssTrack.readChunk()` into the in-memory track.
- Frames are rendered with `AssRender.renderFrame(..., AssTexType.BITMAP_RGBA)` and composited into an Android Bitmap overlay.
- No translated subtitle file is created.
- mpv subtitle tracks are never reloaded during translation.
- The app's existing `[ndk]` block is intentionally retained because the project also uses NDK for the local AI JNI runtime.


## v8 runtime fixes
- `AssTrack.readChunk()` now receives the correct Matroska packet payload: `ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text`. Start/end are passed separately to `readChunk()`.
- mpv subtitle visibility is hard-disabled whenever translation mode is `korean`; it no longer falls back to the original mpv subtitle while waiting for translation.
- libass frame compositing no longer depends on `AssFrame.changed != 0`; every returned frame is composited when it contains textures.
- Added runtime diagnostics for injected packet payloads and rendered texture count.
