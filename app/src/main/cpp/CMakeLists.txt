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
# Optional: Hexagon / HTP NPU backend
#
# ビルド有効化:
#   cmake -DGGML_HEXAGON=ON \
#         -DHEXAGON_SDK_ROOT=/opt/hexagon/6.6.0.0 \
#         ...
#
# 事前準備 (Hexagon SDK 必須):
#   1. qaic で htp_iface_stub.c を生成 (自動)
#   2. HTP skel は SDK 付属 DSP toolchain でビルドし
#      app/src/main/jniLibs/arm64-v8a/ に手動配置
# ---------------------------------------------------------
# ---------------------------------------------------------
# Hexagon / HTP NPU backend — 2 つのビルドモード
#
# [モード A] フル SDK ビルド (HEXAGON_SDK_ROOT が必要)
#   cmake -DGGML_HEXAGON=ON -DHEXAGON_SDK_ROOT=/opt/hexagon/6.6.0.0 ...
#   → qaic で htp_iface_stub.c を自動生成し、SDK ヘッダを使用
#
# [モード B] 事前ビルド成果物 (CI / SDK なし環境向け)
#   app/src/main/cpp/hexagon-artifacts/ に以下を配置するだけで自動有効化:
#     stub/htp_iface_stub.c
#     stub/htp_iface.h
#     sdk-incs/AEEStdErr.h  (他 Hexagon SDK ヘッダ群)
#     skel/libggml-htp-v73.so  (他 DSP skel)
#   成果物は build-hexagon-artifacts.yml ワークフローが GitHub Release に発行する
# ---------------------------------------------------------
option(GGML_HEXAGON "Enable Hexagon/HTP NPU backend" OFF)
set(HEXAGON_SDK_ROOT        "" CACHE PATH "Hexagon SDK root [mode A]")
set(GGML_HEXAGON_ARTIFACTS_DIR "" CACHE PATH "Pre-built artifacts dir [mode B]")

# ----- Auto-detect mode B artifacts -----
# CI が hexagon-artifacts/ を配置するだけで自動有効化される
set(_HEXAGON_AUTO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/hexagon-artifacts")
if(NOT GGML_HEXAGON AND IS_DIRECTORY "${_HEXAGON_AUTO_DIR}/stub"
                     AND EXISTS "${_HEXAGON_AUTO_DIR}/stub/htp_iface_stub.c")
    set(GGML_HEXAGON             ON  CACHE BOOL "" FORCE)
    set(GGML_HEXAGON_ARTIFACTS_DIR "${_HEXAGON_AUTO_DIR}" CACHE PATH "" FORCE)
    message(STATUS "hexagon: auto-detected artifacts at ${_HEXAGON_AUTO_DIR}")
endif()

set(GGML_HEXAGON_SOURCES "")

if(GGML_HEXAGON)
    # ---- モード判定 ----
    if(IS_DIRECTORY "${HEXAGON_SDK_ROOT}")
        set(_HEX_MODE "sdk")
    elseif(IS_DIRECTORY "${GGML_HEXAGON_ARTIFACTS_DIR}/stub"
           AND EXISTS    "${GGML_HEXAGON_ARTIFACTS_DIR}/stub/htp_iface_stub.c")
        set(_HEX_MODE "artifacts")
    else()
        message(FATAL_ERROR
            "[GGML_HEXAGON] ビルドに必要な依存物が見つかりません。\n"
            "  [モード A] -DHEXAGON_SDK_ROOT=/path/to/sdk  (Hexagon SDK 6.6.0.0)\n"
            "  [モード B] app/src/main/cpp/hexagon-artifacts/ に成果物を配置\n"
            "    (GitHub Actions: build-hexagon-artifacts.yml を実行して Release から取得)")
    endif()
    message(STATUS "hexagon: build mode=${_HEX_MODE}")

    if(_HEX_MODE STREQUAL "sdk")
        # ---- モード A: SDK から qaic でスタブを生成 ----
        find_program(QAIC_EXE qaic
            PATHS
                "${HEXAGON_SDK_ROOT}/ipc/fastrpc/qaic/bin"            # SDK 6.x
                "${HEXAGON_SDK_ROOT}/tools/utils/qaic/Ubuntu22"       # SDK 5.x
                "${HEXAGON_SDK_ROOT}/tools/utils/qaic/Ubuntu20"
                "${HEXAGON_SDK_ROOT}/tools/utils/qaic/Linux"
                "${HEXAGON_SDK_ROOT}/tools/utils/qaic"
            NO_DEFAULT_PATH
        )
        if(NOT QAIC_EXE)
            message(FATAL_ERROR "[GGML_HEXAGON] qaic が見つかりません (SDK 6.x: ipc/fastrpc/qaic/bin, 5.x: tools/utils/qaic) under ${HEXAGON_SDK_ROOT}")
        endif()
        message(STATUS "hexagon: qaic=${QAIC_EXE}  sdk=${HEXAGON_SDK_ROOT}")

        set(HTP_IDL_FILE    "${GGML_ROOT_DIR}/src/ggml-hexagon/htp/htp_iface.idl")
        set(HTP_STUB_C      "${CMAKE_BINARY_DIR}/htp_iface_stub.c")
        set(HTP_STUB_H_DIR  "${CMAKE_BINARY_DIR}")

        add_custom_command(
            OUTPUT  "${HTP_STUB_C}" "${CMAKE_BINARY_DIR}/htp_iface.h"
            COMMAND "${QAIC_EXE}" -mdll -o "${CMAKE_BINARY_DIR}"
                    "-I${HEXAGON_SDK_ROOT}/incs"
                    "-I${HEXAGON_SDK_ROOT}/incs/stddef"
                    "${HTP_IDL_FILE}"
            DEPENDS "${HTP_IDL_FILE}"
            COMMENT "Generating HTP RPC ARM-side stub from htp_iface.idl (SDK mode)"
            VERBATIM
        )
        add_custom_target(htp_idl_gen DEPENDS "${HTP_STUB_C}")

        set(_HEX_EXTRA_INCS
            "${HEXAGON_SDK_ROOT}/incs"
            "${HEXAGON_SDK_ROOT}/incs/stddef"
            "${HEXAGON_SDK_ROOT}/utils/examples"
        )

    else()
        # ---- モード B: 事前ビルド成果物を使用 ----
        set(HTP_STUB_C     "${GGML_HEXAGON_ARTIFACTS_DIR}/stub/htp_iface_stub.c")
        set(HTP_STUB_H_DIR "${GGML_HEXAGON_ARTIFACTS_DIR}/stub")

        add_custom_target(htp_idl_gen)   # no-op (already generated)

        set(_HEX_EXTRA_INCS
            "${GGML_HEXAGON_ARTIFACTS_DIR}/sdk-incs"
            "${GGML_HEXAGON_ARTIFACTS_DIR}/sdk-incs/stddef"
        )
        message(STATUS "hexagon: using pre-built stub=${HTP_STUB_C}")
    endif()

    set(GGML_HEXAGON_SOURCES
        "${GGML_ROOT_DIR}/src/ggml-hexagon/ggml-hexagon.cpp"
        "${GGML_ROOT_DIR}/src/ggml-hexagon/htp-drv.cpp"
        "${HTP_STUB_C}"
    )
    message(STATUS "hexagon: Hexagon/HTP sources added to llama_jni")
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
    ${GGML_HEXAGON_SOURCES}
)

license_generate(llama_jni)

if(GGML_HEXAGON)
    add_dependencies(llama_jni htp_idl_gen)
endif()

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
if(GGML_HEXAGON)
    target_compile_definitions(llama_jni PRIVATE GGML_USE_HEXAGON)
endif()

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
if(GGML_HEXAGON)
    target_include_directories(llama_jni PRIVATE
        "${GGML_ROOT_DIR}/src/ggml-hexagon"
        "${GGML_ROOT_DIR}/src/ggml-hexagon/htp"
        "${HTP_STUB_H_DIR}"     # htp_iface.h (SDK 生成 or 事前ビルド)
        ${_HEX_EXTRA_INCS}      # AEEStdErr.h 他 SDK ヘッダ群
    )
endif()

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
