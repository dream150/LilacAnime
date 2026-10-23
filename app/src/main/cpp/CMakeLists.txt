cmake_minimum_required(VERSION 3.22.1)
project(lilac_whisper LANGUAGES C CXX)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_definitions(_GNU_SOURCE)

set(NATIVE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../native-whisper")
set(WHISPER_ROOT "${NATIVE_ROOT}")
set(GGML_INC "${NATIVE_ROOT}/ggml/include")
set(GGML_SRC "${NATIVE_ROOT}/ggml/src")
set(WHISPER_INC "${NATIVE_ROOT}/include")
set(WHISPER_SRC "${NATIVE_ROOT}/src")
set(COMPILE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/whisper_engine/compile")

find_package(Threads REQUIRED)

add_library(ggml-base STATIC
    "${COMPILE_ROOT}/ggml-base/ggml_src_ggml.c"
    "${COMPILE_ROOT}/ggml-base/ggml_src_ggml.cpp"
    "${COMPILE_ROOT}/ggml-base/ggml_src_ggml-alloc.c"
    "${COMPILE_ROOT}/ggml-base/ggml_src_ggml-backend.cpp"
    "${COMPILE_ROOT}/ggml-base/ggml_src_ggml-backend-meta.cpp"
    "${COMPILE_ROOT}/ggml-base/ggml_src_ggml-opt.cpp"
    "${COMPILE_ROOT}/ggml-base/ggml_src_ggml-threading.cpp"
    "${COMPILE_ROOT}/ggml-base/ggml_src_ggml-quants.c"
    "${COMPILE_ROOT}/ggml-base/ggml_src_gguf.cpp")
target_include_directories(ggml-base PUBLIC "${GGML_INC}" PRIVATE "${GGML_SRC}")
target_link_libraries(ggml-base PUBLIC Threads::Threads)

add_library(ggml STATIC
    "${COMPILE_ROOT}/ggml-base/ggml_src_ggml-backend-dl.cpp"
    "${COMPILE_ROOT}/ggml-base/ggml_src_ggml-backend-reg.cpp")
target_include_directories(ggml PUBLIC "${GGML_INC}" PRIVATE "${GGML_SRC}")
target_compile_definitions(ggml PRIVATE GGML_USE_CPU)
target_link_libraries(ggml PUBLIC ggml-base PRIVATE dl)

add_library(ggml-cpu STATIC
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_ggml-cpu.c"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_ggml-cpu.cpp"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_hbm.cpp"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_quants.c"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_traits.cpp"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_amx_amx.cpp"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_amx_mmq.cpp"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_tiled_tiled.cpp"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_tiled_tiled-kernel.cpp"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_binary-ops.cpp"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_unary-ops.cpp"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_vec.cpp"
    "${COMPILE_ROOT}/ggml-cpu/ggml_src_ggml-cpu_ops.cpp")
target_include_directories(ggml-cpu PUBLIC "${GGML_INC}" PRIVATE
    "${GGML_SRC}" "${GGML_SRC}/ggml-cpu" "${GGML_SRC}/ggml-cpu/amx"
    "${GGML_SRC}/ggml-cpu/tiled")
target_link_libraries(ggml-cpu PUBLIC ggml-base)

add_library(whisper STATIC
    "${COMPILE_ROOT}/whisper/src_whisper.cpp"
    "${COMPILE_ROOT}/whisper/src_aneforge_whisper-aneforge.cpp")
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="1.9.5-dev")
target_include_directories(whisper PUBLIC "${WHISPER_INC}" PRIVATE
    "${WHISPER_SRC}" "${WHISPER_SRC}/aneforge" "${GGML_INC}" "${GGML_SRC}")
target_link_libraries(whisper PUBLIC ggml ggml-base ggml-cpu Threads::Threads)

add_library(lilac_whisper SHARED whisper_jni.cpp)
target_include_directories(lilac_whisper PRIVATE "${WHISPER_INC}" "${GGML_INC}")
target_link_libraries(lilac_whisper PRIVATE whisper ggml ggml-base ggml-cpu log android dl m)
