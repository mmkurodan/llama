cmake_minimum_required(VERSION 3.22.1)
project(llama_android)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Threads REQUIRED)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -fPIC")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fPIC")

set(LLAMA_ROOT_DIR ${CMAKE_SOURCE_DIR}/llama)
set(GGML_ROOT_DIR  ${LLAMA_ROOT_DIR}/ggml)
set(LLAMA_RUNTIME_GGML_BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/ggml-runtime)

set(LLAMA_ANDROID_HEXAGON_BACKEND_DEFAULT OFF)
if (DEFINED ENV{HEXAGON_SDK_ROOT} AND NOT "$ENV{HEXAGON_SDK_ROOT}" STREQUAL "")
    set(LLAMA_ANDROID_HEXAGON_BACKEND_DEFAULT ON)
endif()

option(LLAMA_ANDROID_BUILD_RUNTIME_BACKENDS
       "Build/package ggml runtime backend libraries alongside llama_jni"
       ON)
option(LLAMA_ANDROID_BUILD_HEXAGON_BACKEND
       "Build/package ggml Hexagon backend artifacts when the Hexagon SDK is available"
       ${LLAMA_ANDROID_HEXAGON_BACKEND_DEFAULT})

set(LLAMA_ANDROID_HEXAGON_RUNTIME_ENABLED OFF)
if (LLAMA_ANDROID_BUILD_RUNTIME_BACKENDS)
    if (LLAMA_ANDROID_BUILD_HEXAGON_BACKEND AND
            DEFINED ENV{HEXAGON_SDK_ROOT} AND
            NOT "$ENV{HEXAGON_SDK_ROOT}" STREQUAL "")
        set(LLAMA_ANDROID_HEXAGON_RUNTIME_ENABLED ON)
    elseif (LLAMA_ANDROID_BUILD_HEXAGON_BACKEND)
        message(WARNING "Hexagon SDK not detected; building only CPU runtime backends for Android packaging")
    endif()
endif()

# ---------------------------------------------------------
# build-info 用の値を CMake 側で定義（テンプレートに合わせる）
# build-info.cpp.in の中身：
#   @LLAMA_BUILD_NUMBER@
#   @LLAMA_BUILD_COMMIT@
#   @BUILD_COMPILER@
#   @BUILD_TARGET@
# ---------------------------------------------------------
set(LLAMA_BUILD_NUMBER 8653 CACHE STRING "")
set(LLAMA_BUILD_COMMIT "1f34806c441f335f7d66e56fdb8ab3f5d09684e9" CACHE STRING "")
set(BUILD_COMPILER     "Clang"   CACHE STRING "")
set(BUILD_TARGET       "android" CACHE STRING "")

# ggml.c expects these string macros to be defined at compile time.
set(GGML_VERSION      "0.9.11" CACHE STRING "")
set(GGML_BUILD_COMMIT "1f34806c441f335f7d66e56fdb8ab3f5d09684e9" CACHE STRING "")

# ---------------------------------------------------------
# embedded license text for --license in common/arg.cpp
# ---------------------------------------------------------
include(${LLAMA_ROOT_DIR}/cmake/license.cmake)
license_add_file("llama.cpp" "${LLAMA_ROOT_DIR}/LICENSE")
license_add_file("cpp-httplib" "${LLAMA_ROOT_DIR}/vendor/cpp-httplib/LICENSE")

file(GLOB LLAMA_EXTRA_LICENSES
    ${LLAMA_ROOT_DIR}/licenses/LICENSE-*
)

foreach(FILE_PATH ${LLAMA_EXTRA_LICENSES})
    get_filename_component(FILE_NAME "${FILE_PATH}" NAME)
    string(REGEX REPLACE "^LICENSE-" "" LICENSE_NAME "${FILE_NAME}")
    license_add_file("${LICENSE_NAME}" "${FILE_PATH}")
endforeach()

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

file(GLOB LLAMA_COMMON_JINJA_FILES
    ${LLAMA_ROOT_DIR}/common/jinja/*.cpp
)

set(LLAMA_VENDOR_FILES
    ${LLAMA_ROOT_DIR}/vendor/cpp-httplib/httplib.cpp
)

file(GLOB LLAMA_SRC_FILES
    ${LLAMA_ROOT_DIR}/src/*.cpp
)

file(GLOB LLAMA_MODEL_FILES
    ${LLAMA_ROOT_DIR}/src/models/*.cpp
)

set(MTMD_SOURCES
    ${LLAMA_ROOT_DIR}/tools/mtmd/mtmd.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/mtmd-audio.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/mtmd-image.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/mtmd-helper.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/clip.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/cogvlm.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/conformer.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/gemma4v.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/glm4v.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/internvl.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/kimivl.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/kimik25.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/nemotron-v2-vl.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/llama4.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/llava.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/minicpmv.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/paddleocr.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/pixtral.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/qwen2vl.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/qwen3vl.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/siglip.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/whisper-enc.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/deepseekocr.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/mobilenetv5.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/youtuvl.cpp
)

set(LLAMA_SOURCES
    ${LLAMA_ROOT_FILES}
    ${LLAMA_COMMON_FILES}
    ${LLAMA_COMMON_JINJA_FILES}
    ${LLAMA_SRC_FILES}
    ${LLAMA_MODEL_FILES}
    ${MTMD_SOURCES}
    ${LLAMA_VENDOR_FILES}
    ${CMAKE_CURRENT_BINARY_DIR}/build-info.cpp
)

# ---------------------------------------------------------
# GGML sources (updated for llama.cpp b8653)
# ---------------------------------------------------------
set(GGML_SOURCES
    ${GGML_ROOT_DIR}/src/ggml.c
    ${GGML_ROOT_DIR}/src/ggml.cpp
    ${GGML_ROOT_DIR}/src/ggml-alloc.c
    ${GGML_ROOT_DIR}/src/ggml-quants.c
    ${GGML_ROOT_DIR}/src/ggml-opt.cpp
    ${GGML_ROOT_DIR}/src/ggml-threading.cpp
    ${GGML_ROOT_DIR}/src/gguf.cpp
    ${GGML_ROOT_DIR}/src/ggml-backend-dl.cpp
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
# Optional ggml runtime backend libraries for Java/JNI load order
# - Always package ggml-base / ggml-cpu when enabled
# - Package ggml-hexagon + HTP skels only when Hexagon SDK is available
# ---------------------------------------------------------
if (LLAMA_ANDROID_BUILD_RUNTIME_BACKENDS)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "Build shared libraries for Android runtime backends" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "Enable dynamic ggml backends for Android runtime packaging" FORCE)
    set(GGML_BUILD_TESTS OFF CACHE BOOL "Disable ggml tests in Android app build" FORCE)
    set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "Disable ggml examples in Android app build" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP in Android app build" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "Disable host-native tuning while cross-compiling Android backends" FORCE)
    set(GGML_CPU ON CACHE BOOL "Build ggml CPU backend for Android runtime packaging" FORCE)
    set(GGML_HEXAGON ${LLAMA_ANDROID_HEXAGON_RUNTIME_ENABLED} CACHE BOOL "Build ggml Hexagon backend for Android runtime packaging" FORCE)
    add_subdirectory(${GGML_ROOT_DIR} ${LLAMA_RUNTIME_GGML_BINARY_DIR})
endif()

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

license_generate(llama_jni)
set_target_properties(llama_jni PROPERTIES OUTPUT_NAME llama)

# ---------------------------------------------------------
# compile definitions
# ---------------------------------------------------------
target_compile_definitions(
    llama_jni
    PRIVATE
    # This target vendors ggml sources directly, so mirror upstream backend-selection defines.
    GGML_USE_CPU
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
    ${LLAMA_ROOT_DIR}/tools/mtmd

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
    Threads::Threads

    log
    android
    jnigraphics
    m
    dl
    atomic
)

if (LLAMA_ANDROID_BUILD_RUNTIME_BACKENDS AND TARGET ggml-base AND TARGET ggml-cpu)
    set(LLAMA_RUNTIME_BACKEND_DEPENDENCIES
        llama_jni
        ggml-base
        ggml-cpu
    )
    set(LLAMA_RUNTIME_BACKEND_COPY_COMMANDS
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            $<TARGET_FILE:ggml-base>
            $<TARGET_FILE_DIR:llama_jni>/$<TARGET_FILE_NAME:ggml-base>
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            $<TARGET_FILE:ggml-cpu>
            $<TARGET_FILE_DIR:llama_jni>/$<TARGET_FILE_NAME:ggml-cpu>
    )

    if (LLAMA_ANDROID_HEXAGON_RUNTIME_ENABLED AND TARGET ggml-hexagon)
        list(APPEND LLAMA_RUNTIME_BACKEND_DEPENDENCIES ggml-hexagon)
        list(APPEND LLAMA_RUNTIME_BACKEND_COPY_COMMANDS
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                $<TARGET_FILE:ggml-hexagon>
                $<TARGET_FILE_DIR:llama_jni>/$<TARGET_FILE_NAME:ggml-hexagon>
        )

        foreach(HTP_VERSION v68 v69 v73 v75 v79 v81)
            list(APPEND LLAMA_RUNTIME_BACKEND_COPY_COMMANDS
                COMMAND ${CMAKE_COMMAND} -E copy_if_different
                    ${LLAMA_RUNTIME_GGML_BINARY_DIR}/src/ggml-hexagon/libggml-htp-${HTP_VERSION}.so
                    $<TARGET_FILE_DIR:llama_jni>/libggml-htp-${HTP_VERSION}.so
            )
        endforeach()
    endif()

    add_custom_target(
        llama_runtime_backends ALL
        DEPENDS ${LLAMA_RUNTIME_BACKEND_DEPENDENCIES}
        ${LLAMA_RUNTIME_BACKEND_COPY_COMMANDS}
        VERBATIM
    )
endif()

if (NOT MSVC)
    target_compile_options(llama_jni PRIVATE -Wno-cast-qual)
endif()
