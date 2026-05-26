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

# ---------------------------------------------------------
# build-info 用の値を CMake 側で定義（テンプレートに合わせる）
# build-info.cpp.in の中身：
#   @LLAMA_BUILD_NUMBER@
#   @LLAMA_BUILD_COMMIT@
#   @BUILD_COMPILER@
#   @BUILD_TARGET@
# ---------------------------------------------------------
set(LLAMA_BUILD_NUMBER 9351 CACHE STRING "")
set(LLAMA_BUILD_COMMIT "0d18aaa9d1a8af3df9abccd828e22eeaac7f840b" CACHE STRING "")
set(BUILD_COMPILER     "Clang"   CACHE STRING "")
set(BUILD_TARGET       "android" CACHE STRING "")

# ggml.c expects these string macros to be defined at compile time.
set(GGML_VERSION      "0.13.0" CACHE STRING "")
set(GGML_BUILD_COMMIT "0d18aaa9d1a8af3df9abccd828e22eeaac7f840b" CACHE STRING "")

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
file(GLOB LLAMA_ROOT_FILES CONFIGURE_DEPENDS
    ${LLAMA_ROOT_DIR}/*.cpp
)

file(GLOB LLAMA_COMMON_FILES CONFIGURE_DEPENDS
    ${LLAMA_ROOT_DIR}/common/*.cpp
)

file(GLOB LLAMA_COMMON_JINJA_FILES CONFIGURE_DEPENDS
    ${LLAMA_ROOT_DIR}/common/jinja/*.cpp
)

set(LLAMA_VENDOR_FILES
    ${LLAMA_ROOT_DIR}/vendor/cpp-httplib/httplib.cpp
)

file(GLOB LLAMA_SRC_FILES CONFIGURE_DEPENDS
    ${LLAMA_ROOT_DIR}/src/*.cpp
)

file(GLOB LLAMA_MODEL_FILES CONFIGURE_DEPENDS
    ${LLAMA_ROOT_DIR}/src/models/*.cpp
)

file(GLOB MTMD_MODEL_SOURCES CONFIGURE_DEPENDS
    ${LLAMA_ROOT_DIR}/tools/mtmd/models/*.cpp
)

set(MTMD_SOURCES
    ${LLAMA_ROOT_DIR}/tools/mtmd/mtmd.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/mtmd-audio.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/mtmd-image.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/mtmd-helper.cpp
    ${LLAMA_ROOT_DIR}/tools/mtmd/clip.cpp
    ${MTMD_MODEL_SOURCES}
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
# GGML sources (updated for llama.cpp b9351)
# ---------------------------------------------------------
file(GLOB GGML_CORE_SOURCES CONFIGURE_DEPENDS
    ${GGML_ROOT_DIR}/src/*.c
    ${GGML_ROOT_DIR}/src/*.cpp
)

file(GLOB GGML_CPU_SOURCES CONFIGURE_DEPENDS
    ${GGML_ROOT_DIR}/src/ggml-cpu/*.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/*.cpp
    ${GGML_ROOT_DIR}/src/ggml-cpu/arch/arm/*.c
    ${GGML_ROOT_DIR}/src/ggml-cpu/arch/arm/*.cpp
)

set(GGML_SOURCES
    ${GGML_CORE_SOURCES}
    ${GGML_CPU_SOURCES}
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

license_generate(llama_jni)

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
    ${LLAMA_ROOT_DIR}/common
    ${LLAMA_ROOT_DIR}/src
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

if (NOT MSVC)
    target_compile_options(llama_jni PRIVATE -Wno-cast-qual)
endif()
