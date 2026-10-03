cmake_minimum_required(VERSION 3.22)
project(lilac_local_ai_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LLAMA_ROOT "" CACHE PATH "llama.cpp checkout")

add_library(lilac_local_ai_jni SHARED
    "${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp/lilac_local_ai/lilac_local_ai_jni.cpp"
)

target_include_directories(lilac_local_ai_jni PRIVATE
    "${LLAMA_ROOT}/include"
    "${LLAMA_ROOT}/ggml/include"
)

target_link_libraries(lilac_local_ai_jni PRIVATE log android)
set_target_properties(lilac_local_ai_jni PROPERTIES OUTPUT_NAME "lilac_local_ai_jni")
