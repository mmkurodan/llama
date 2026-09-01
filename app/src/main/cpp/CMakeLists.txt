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
set(LLAMA_BUILD_NUMBER 10621 CACHE STRING "")
set(LLAMA_BUILD_COMMIT "c1d0e7a004015f23bc0233470b747b596f29b264" CACHE STRING "")
# llama.cpp switched to semantic versioning at v0.3.0; llama_version() needs this string.
set(LLAMA_VERSION      "0.3.0"  CACHE STRING "")
set(BUILD_COMPILER     "Clang"   CACHE STRING "")
set(BUILD_TARGET       "android" CACHE STRING "")

# ggml.c expects these string macros to be defined at compile time.
set(GGML_VERSION      "0.22.0" CACHE STRING "")
set(GGML_BUILD_COMMIT "c1d0e7a004015f23bc0233470b747b596f29b264" CACHE STRING "")

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
    # b10621/v0.3.0: mtmd-helper hashes bitmap IDs with hash_sha256_hex (FNV -> SHA-256);
    # hash.cpp delegates to the C sha256 implementation. (sha1/xxhash are only for the
    # gguf-hash example, which we don't build.)
    ${LLAMA_ROOT_DIR}/vendor/hash/hash.cpp
    ${LLAMA_ROOT_DIR}/vendor/hash/sha256/sha256.c
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
# ARM feature flags for the repacked-quant GEMM/GEMV kernels (decode hot path).
#
# ggml 0.13.0 repacks Q4_0/Q4_K/Q5_K/Q6_K weights into interleaved layouts and routes their
# matmul through ggml_gemv/gemm_*_q8 in ggml-cpu/arch/arm/repack.cpp. WHICH layout is offered
# is chosen AT RUNTIME from CPU feature detection (ggml_cpu_has_dotprod / _matmul_int8 via
# getauxval, in cpu-feats.cpp), but those kernel bodies are gated AT COMPILE TIME by
# __ARM_FEATURE_DOTPROD / __ARM_FEATURE_MATMUL_INT8. With the NDK default (-march=armv8-a)
# those macros are undefined, so on a dotprod-capable device (e.g. Snapdragon 8 Elite) the
# weights are repacked but the GEMM falls back to the scalar *_generic path -> ~0.1 tok/s
# decode on every backend (GPU not built / NPU stuck both fall back to this CPU path).
#
# Compile these kernels for the features the runtime already assumes. They are only ever
# reached AFTER the matching runtime feature check, so this stays safe on CPUs that lack
# the features (a non-dotprod device is never offered the repack layout and never enters
# this code, keeping the plain NEON vec_dot path). ABI is arm64-v8a only (see build.gradle).
set_source_files_properties(
    ${GGML_ROOT_DIR}/src/ggml-cpu/arch/arm/repack.cpp
    PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod+i8mm"
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

# ----- Hexagon/HTP NPU backend: DISABLED -----
# NPU support was removed from this repo to avoid Qualcomm Hexagon SDK (Community
# Version) redistribution restrictions on the compiled HTP skels (they statically
# link Qualcomm QuRT runtime). The vendored ggml-hexagon sources are kept in-tree
# but are never compiled: GGML_HEXAGON stays OFF and GGML_USE_HEXAGON is undefined,
# so the app builds CPU/GPU-only. Backup + how to re-enable: /root/llama-npu-backup/.
# Do NOT re-introduce the artifacts auto-detect that force-set GGML_HEXAGON=ON.

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
    # CPU-affinity code in ggml-cpu.c (cpu_set_t / CPU_SET / sched_setaffinity, reached via the
    # __linux__ path on Android) needs the GNU libc extensions. Upstream's ggml/src/CMakeLists.txt
    # adds _GNU_SOURCE for Linux AND Android; mirror that here since we build ggml in our own target.
    _GNU_SOURCE
    GGML_VERSION="${GGML_VERSION}"
    GGML_COMMIT="${GGML_BUILD_COMMIT}"
    # b10621/v0.3.0: src/llama.cpp (llama_version()) now needs LLAMA_VERSION at compile
    # time. Upstream's src/CMakeLists.txt passes it as a target compile definition; mirror
    # that here since this target vendors the llama sources directly.
    LLAMA_VERSION="${LLAMA_VERSION}"
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

# ---------------------------------------------------------
# Optional: OpenCL (Adreno GPU) backend as a DL plugin: libggml-opencl.so
#
# Built only when libOpenCL.so is linkable (CI injects it into the NDK sysroot)
# and the vendored CL headers exist. It is a separate shared lib (GGML_BACKEND_DL)
# loaded at runtime via dlopen — so the app does NOT hard-depend on libOpenCL.so
# and still runs on devices without an OpenCL driver (GPU just stays unavailable).
# llama_jni provides the ggml-base symbols (linked as a NEEDED dependency).
# ---------------------------------------------------------
# libOpenCL.so to link against: prefer the CI-provided path (NDK-agnostic),
# otherwise fall back to the NDK sysroot. Runtime uses the device's libOpenCL.so.
if(DEFINED OPENCL_STUB_LIB AND EXISTS "${OPENCL_STUB_LIB}")
    set(OPENCL_LIB "${OPENCL_STUB_LIB}")
else()
    find_library(OPENCL_LIB OpenCL)
endif()
set(_OPENCL_CL_INC "${CMAKE_SOURCE_DIR}/third_party/opencl")
if(OPENCL_LIB AND EXISTS "${_OPENCL_CL_INC}/CL/cl.h")
    message(STATUS "opencl: building libggml-opencl.so DL plugin (lib=${OPENCL_LIB})")
    find_package(Python3 REQUIRED COMPONENTS Interpreter)

    set(_OPENCL_SRC_DIR "${GGML_ROOT_DIR}/src/ggml-opencl")
    set(_OPENCL_AUTOGEN "${CMAKE_BINARY_DIR}/opencl-autogenerated")
    file(MAKE_DIRECTORY "${_OPENCL_AUTOGEN}")

    # Embed every kernel (*.cl -> *.cl.h) so nothing is loaded from disk at runtime.
    file(GLOB _OPENCL_CL_FILES "${_OPENCL_SRC_DIR}/kernels/*.cl")
    set(_OPENCL_KERNEL_HEADERS "")
    foreach(_clf ${_OPENCL_CL_FILES})
        get_filename_component(_kn "${_clf}" NAME_WE)
        set(_kh "${_OPENCL_AUTOGEN}/${_kn}.cl.h")
        add_custom_command(
            OUTPUT  "${_kh}"
            COMMAND ${Python3_EXECUTABLE} "${_OPENCL_SRC_DIR}/kernels/embed_kernel.py" "${_clf}" "${_kh}"
            DEPENDS "${_clf}" "${_OPENCL_SRC_DIR}/kernels/embed_kernel.py"
            COMMENT "opencl: embed kernel ${_kn}")
        list(APPEND _OPENCL_KERNEL_HEADERS "${_kh}")
    endforeach()

    add_library(ggml-opencl SHARED
        "${_OPENCL_SRC_DIR}/ggml-opencl.cpp"
        ${_OPENCL_KERNEL_HEADERS})
    target_compile_features(ggml-opencl PRIVATE cxx_std_17)
    target_compile_definitions(ggml-opencl PRIVATE
        GGML_BACKEND_DL GGML_BACKEND_SHARED GGML_BACKEND_BUILD
        GGML_USE_OPENCL GGML_OPENCL_EMBED_KERNELS GGML_OPENCL_SOA_Q
        GGML_OPENCL_TARGET_VERSION=300 GGML_OPENCL_USE_ADRENO_KERNELS
        GGML_VERSION="${GGML_VERSION}" GGML_COMMIT="${GGML_BUILD_COMMIT}")
    target_include_directories(ggml-opencl PRIVATE
        "${_OPENCL_CL_INC}"
        "${_OPENCL_AUTOGEN}"
        "${_OPENCL_SRC_DIR}"
        "${GGML_ROOT_DIR}/include"
        "${GGML_ROOT_DIR}/src")
    target_link_options(ggml-opencl PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)
    # llama_jni exports the ggml-base API symbols (GGML_API default visibility);
    # link it so the plugin resolves them via a NEEDED dependency at dlopen time.
    target_link_libraries(ggml-opencl PRIVATE llama_jni ${OPENCL_LIB} log dl)
    if (NOT MSVC)
        target_compile_options(ggml-opencl PRIVATE -Wno-cast-qual)
    endif()
else()
    message(STATUS "opencl: libOpenCL not found — GPU (OpenCL) backend disabled")
endif()
