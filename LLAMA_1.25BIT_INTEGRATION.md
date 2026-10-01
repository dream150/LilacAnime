# LilacAnime Hunyuan-MT1.5 2-bit integration

This project keeps the original LilacAnime module layout and places llama.cpp 0.5.0 under app/src/main/cpp/llama.cpp-0.5.0.

The AngelSlim 2-bit model is downloaded as Hy-MT1.5-1.8B-1.25bit.gguf.

The native JNI bridge is under app/src/main/cpp/lilac_hunyuan and is intended to replace the prebuilt llama-android runtime after the native build is enabled in CodeAssist.

The bundled llama.cpp 0.5.0 contains TQ1_0 support, but the AngelSlim model documentation states that this particular GGUF requires the Q2_0c kernel from PR #19357. Therefore this source tree is the correct integration point, but it must not be described as a validated Q2_0c runtime until the native library is built and the model loads successfully.
