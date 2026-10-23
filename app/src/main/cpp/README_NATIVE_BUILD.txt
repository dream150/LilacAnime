LilacAnime Whisper native build notes

This project uses app/module.toml [ndk] (not Gradle externalNativeBuild) to request
a shared library named liblilac_whisper.so for arm64-v8a.

Important dependency requirement:
whisper_jni.cpp includes whisper.h and links the whisper / ggml implementation.
The repository currently contains only whisper_jni.cpp and CMakeLists.txt; the
upstream whisper.cpp and ggml source tree is not vendored here. CMakeLists.txt
uses FetchContent, but a compiler that only reads module.toml will not execute
CMakeLists.txt or FetchContent. Therefore the native compiler must have the
whisper.cpp sources supplied locally before this library can compile. Do not
treat a successful Kotlin/Java build as proof that liblilac_whisper.so exists.
