cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -fPIC")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fPIC")

set(LLAMA_ROOT_DIR ${CMAKE_SOURCE_DIR}/llama)
set(GGML_ROOT_DIR  ${LLAMA_ROOT_DIR}/ggml)

# ---------------------------------------------------------
# build-info 用の値を CMake 側で定義（テンプレートに合わせる）
# build-info.cpp.in の中身：
#   @LLAMA_BUILD_NUMBER@
#   @LLAMA_BUILD_COMMIT@
#   @BUILD_COMPILER@
#   @BUILD_TARGET@
# ---------------------------------------------------------
set(LLAMA_BUILD_NUMBER 0         CACHE STRING "")
set(LLAMA_BUILD_COMMIT "unknown" CACHE STRING "")
set(BUILD_COMPILER     "Clang"   CACHE STRING "")
set(BUILD_TARGET       "android" CACHE STRING "")

# ggml.c expects these string macros to be defined at compile time.
set(GGML_VERSION      "0.9.7"   CACHE STRING "")
set(GGML_BUILD_COMMIT "unknown" CACHE STRING "")

# ---------------------------------------------------------
# build-info.cpp を生成
# ---------------------------------------------------------
configure_file(
    ${LLAMA_ROOT_DIR}/common/build-info.cpp.in
    ${CMAKE_CURRENT_BINARY_DIR}/build-info.cpp
)

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
    ${CMAKE_CURRENT_BINARY_DIR}/build-info.cpp
)

# ---------------------------------------------------------
# GGML sources (updated for llama.cpp b8204)
# ---------------------------------------------------------
set(GGML_SOURCES
    ${GGML_ROOT_DIR}/src/ggml.c
    ${GGML_ROOT_DIR}/src/ggml.cpp
    ${GGML_ROOT_DIR}/src/ggml-alloc.c
    ${GGML_ROOT_DIR}/src/ggml-quants.c
    ${GGML_ROOT_DIR}/src/ggml-opt.cpp
    ${GGML_ROOT_DIR}/src/ggml-threading.cpp
    ${GGML_ROOT_DIR}/src/gguf.cpp
    ${GGML_ROOT_DIR}/src/ggml-backend.cpp
    ${GGML_ROOT_DIR}/src/ggml-backend-reg.cpp

    ${GGML_ROOT_DIR}/src/ggml-cpu/ggml-cpu.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/ggml-cpu.cpp
    ${GGML_ROOT_DIR}/src/ggml-cpu/ops.cpp
    ${GGML_ROOT_DIR}/src/ggml-cpu/quants.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/repack.cpp
    ${GGML_ROOT_DIR}/src/ggml-cpu/traits.cpp
    ${GGML_ROOT_DIR}/src/ggml-cpu/unary-ops.cpp
    ${GGML_ROOT_DIR}/src/ggml-cpu/vec.cpp
    ${GGML_ROOT_DIR}/src/ggml-cpu/binary-ops.cpp
    ${GGML_ROOT_DIR}/src/ggml-cpu/hbm.cpp

    # ARM architecture support
    ${GGML_ROOT_DIR}/src/ggml-cpu/arch/arm/quants.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/arch/arm/repack.cpp
    ${GGML_ROOT_DIR}/src/ggml-cpu/arch/arm/cpu-feats.cpp
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
)

# ---------------------------------------------------------
# compile definitions
# ---------------------------------------------------------
target_compile_definitions(
    llama_jni
    PRIVATE
    GGML_USE_K_QUANTS
    GGML_VERSION="${GGML_VERSION}"
    GGML_COMMIT="${GGML_BUILD_COMMIT}"
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
    ${GGML_ROOT_DIR}/src/ggml-cpu/arch/arm

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

add_library(mbedx509 STATIC IMPORTED)
set_target_properties(mbedx509 PROPERTIES
    IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/third_party/mbedtls/lib/libmbedx509.a
)

# ---------------------------------------------------------
# link
# NOTE: Use -Wl,-z,max-page-size=16384 and -Wl,-z,common-page-size=16384 for
# 16KB page-size support on Android (required for Android 15+ devices).
# Do NOT use -Wl,-align-segments=0x4000: that flag is unsupported by the
# Android NDK's ld.lld and causes: error: unknown argument '-align-segments=0x4000'.
# ---------------------------------------------------------
target_link_options(
    llama_jni
    PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
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
