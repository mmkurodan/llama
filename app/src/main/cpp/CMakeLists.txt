cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fPIC")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fPIC -std=c++17")

# llama.cpp のルート
set(LLAMA_SRC_DIR ${CMAKE_SOURCE_DIR}/llama/src)
set(LLAMA_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/llama/include)

# ggml のルート
set(GGML_SRC_DIR  ${CMAKE_SOURCE_DIR}/llama/ggml/src)
set(GGML_CPU_DIR  ${CMAKE_SOURCE_DIR}/llama/ggml/src/ggml-cpu)

add_library(
    llama_jni
    SHARED
    jni/jni_llama.cpp

    # llama.cpp 本体
    ${LLAMA_SRC_DIR}/llama.cpp
    ${LLAMA_SRC_DIR}/models/llama.cpp

    # ggml core
    ${GGML_SRC_DIR}/ggml.c
    ${GGML_SRC_DIR}/ggml-alloc.c
    ${GGML_SRC_DIR}/ggml-quants.c

    # ggml CPU backend
    ${GGML_CPU_DIR}/ggml-cpu.c
)

target_include_directories(
    llama_jni
    PRIVATE
    ${LLAMA_INCLUDE_DIR}
    ${LLAMA_SRC_DIR}
    ${LLAMA_SRC_DIR}/models
    ${GGML_SRC_DIR}
    ${GGML_CPU_DIR}
    ${CMAKE_SOURCE_DIR}/third_party/libcurl/include
    ${CMAKE_SOURCE_DIR}/third_party/mbedtls/include
)

# libcurl (mbedTLS version)
add_library(curl STATIC IMPORTED)
set_target_properties(curl PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/third_party/libcurl/lib/libcurl.a
)

# mbedTLS
add_library(mbedtls STATIC IMPORTED)
set_target_properties(mbedtls PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/third_party/mbedtls/lib/libmbedtls.a
)

add_library(mbedcrypto STATIC IMPORTED)
set_target_properties(mbedcrypto PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/third_party/mbedtls/lib/libmbedcrypto.a
)

add_library(mbedx509 STATIC IMPORTED)
set_target_properties(mbedx509 PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/third_party/mbedtls/lib/libmbedx509.a
)

target_link_libraries(
    llama_jni
    PRIVATE
    curl
    mbedtls
    mbedcrypto
    mbedx509
    log
    android
    jnigraphics
    m
    atomic
)
