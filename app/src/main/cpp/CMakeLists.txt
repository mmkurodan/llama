cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -fPIC")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fPIC")

# llama.cpp root
set(LLAMA_ROOT_DIR ${CMAKE_SOURCE_DIR}/llama)
set(GGML_ROOT_DIR  ${LLAMA_ROOT_DIR}/ggml)

# ---------------------------------------------------------
# llama core sources
# ---------------------------------------------------------
file(GLOB LLAMA_ROOT_FILES
    ${LLAMA_ROOT_DIR}/*.cpp
)

file(GLOB LLAMA_COMMON_FILES
    ${LLAMA_ROOT_DIR}/common/*.cpp
)

file(GLOB LLAMA_SRC_FILES
    ${LLAMA_ROOT_DIR}/src/*.cpp
)

set(LLAMA_SOURCES
    ${LLAMA_ROOT_FILES}
    ${LLAMA_COMMON_FILES}
    ${LLAMA_SRC_FILES}
)

# ---------------------------------------------------------
# GGML sources (gguf-v0.17.x 完全対応)
# ---------------------------------------------------------
set(GGML_SOURCES
    ${GGML_ROOT_DIR}/src/ggml.c
    ${GGML_ROOT_DIR}/src/ggml-quants.c
    ${GGML_ROOT_DIR}/src/ggml-alloc.c
    ${GGML_ROOT_DIR}/src/ggml-backend.c

    ${GGML_ROOT_DIR}/src/ggml-cpu/ggml-cpu.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/ggml-cpu-impl.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/ggml-cpu-quants.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/ggml-cpu-gemm.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/ggml-cpu-gemv.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/ggml-cpu-opt.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/ggml-cpu-quantize.c
)

# ---------------------------------------------------------
# vendor sources (xxhash など)
# ---------------------------------------------------------
file(GLOB VENDOR_SOURCES
    ${LLAMA_ROOT_DIR}/vendor/xxhash/*.c
)

# ---------------------------------------------------------
# JNI shared library
# ---------------------------------------------------------
add_library(
    llama_jni
    SHARED

    jni/jni_llama.cpp

    ${LLAMA_SOURCES}
    ${GGML_SOURCES}
    ${VENDOR_SOURCES}
)

# ---------------------------------------------------------
# include directories
# ---------------------------------------------------------
target_include_directories(
    llama_jni
    PRIVATE
    ${LLAMA_ROOT_DIR}/include
    ${LLAMA_ROOT_DIR}/src
    ${LLAMA_ROOT_DIR}/common
    ${LLAMA_ROOT_DIR}/vendor

    ${GGML_ROOT_DIR}/include
    ${GGML_ROOT_DIR}/src
    ${GGML_ROOT_DIR}/src/ggml-cpu

    ${CMAKE_SOURCE_DIR}/third_party/libcurl/include
    ${CMAKE_SOURCE_DIR}/third_party/mbedtls/include
)

# ---------------------------------------------------------
# third-party libraries
# ---------------------------------------------------------
add_library(curl STATIC IMPORTED)
set_target_properties(curl PROPERTIES
    IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/third_party/libcurl/lib/libcurl.a
)

add_library(mbedtls STATIC IMPORTED)
set_target_properties(mbedtls PROPERTIES
    IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/third_party/mbedtls/lib/libmbedtls.a
)

add_library(mbedcrypto STATIC IMPORTED)
set_target_properties(mbedcrypto PROPERTIES
    IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/third_party/mbedtls/lib/libmbedcrypto.a
)

# ★ 修正：mbedx509 は libmbedx509.a を指す（以前は libmbedtls.a で誤り）
add_library(mbedx509 STATIC IMPORTED)
set_target_properties(mbedx509 PROPERTIES
    IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/third_party/mbedtls/lib/libmbedx509.a
)

# ---------------------------------------------------------
# link
# ---------------------------------------------------------
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
