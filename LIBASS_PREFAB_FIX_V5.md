# Libass Prefab JNI fix

The app does not build libass itself. `io.github.peerless2012:ass:0.5.1` supplies the prebuilt libass library through Android Prefab, while `lilac_libass_jni` is only a small JNI adapter for the custom in-memory translation overlay.

Changes:
- enabled Android Prefab in `app/build.gradle.kts`;
- CMake uses `find_package(ass REQUIRED CONFIG)` and links `ass::ass`;
- removed `dlopen("libass.so")` and runtime symbol lookup;
- removed the copied local `ass/ass.h`;
- removed the `dl` linker dependency;
- retained `ass-kt:0.5.1` dependency for the Kotlin wrapper.

The translated subtitle events are still inserted with `ass_process_chunk()` and rendered with `ass_render_frame()`. No translated subtitle file is created and mpv subtitle tracks are not reloaded.
