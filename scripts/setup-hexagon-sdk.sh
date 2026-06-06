#!/usr/bin/env bash
# =============================================================================
# Hexagon SDK セットアップ & HTP skel ビルド支援スクリプト
# =============================================================================
# 使用前に以下の変数を自環境に合わせて変更してください。
# Hexagon SDK は https://developer.qualcomm.com/software/hexagon-dsp-sdk
# からダウンロードします（要 Qualcomm アカウント）。
#
# 動作確認済み SDK バージョン: 5.4.x, 5.5.x
# =============================================================================
set -euo pipefail

# ---- 設定項目 (環境に合わせて変更) ----------------------------------------
HEXAGON_SDK_ROOT="${HEXAGON_SDK_ROOT:-/opt/hexagon-sdk-5.4.0}"
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-/opt/android-sdk/ndk/27.2.12479018}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNILIBS="${PROJECT_ROOT}/app/src/main/jniLibs/arm64-v8a"

# ビルド対象の DSP バージョン (デバイスの Snapdragon SoC に合わせる)
# v73 = Snapdragon 8 Gen 1 (SM8450)
# v75 = Snapdragon 8 Gen 2 (SM8550)
# v79 = Snapdragon 8 Gen 3 (SM8650)
# v81 = Snapdragon 8 Elite (SM8750)
HTP_DSP_VERSIONS="${HTP_DSP_VERSIONS:-v73 v75 v79}"
# ---------------------------------------------------------------------------

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ---- 前提チェック ----------------------------------------------------------
check_prerequisites() {
    info "前提チェック中..."

    if [[ ! -d "${HEXAGON_SDK_ROOT}" ]]; then
        error "HEXAGON_SDK_ROOT が見つかりません: ${HEXAGON_SDK_ROOT}"
        error "Hexagon SDK を https://developer.qualcomm.com/software/hexagon-dsp-sdk から入手し、"
        error "HEXAGON_SDK_ROOT=/path/to/sdk を指定してください。"
        exit 1
    fi
    info "Hexagon SDK: ${HEXAGON_SDK_ROOT}"

    if [[ ! -d "${ANDROID_NDK_ROOT}" ]]; then
        error "ANDROID_NDK_ROOT が見つかりません: ${ANDROID_NDK_ROOT}"
        exit 1
    fi
    info "Android NDK: ${ANDROID_NDK_ROOT}"

    # qaic の存在確認
    local qaic
    qaic="$(find "${HEXAGON_SDK_ROOT}/tools/utils/qaic" -name "qaic" -type f 2>/dev/null | head -1)"
    if [[ -z "${qaic}" ]]; then
        error "qaic が見つかりません: ${HEXAGON_SDK_ROOT}/tools/utils/qaic"
        exit 1
    fi
    info "qaic: ${qaic}"
    export QAIC_EXE="${qaic}"
}

# ---- HTP skel ビルド -------------------------------------------------------
build_htp_skel() {
    local version="$1"
    local htp_src="${PROJECT_ROOT}/app/src/main/cpp/llama/ggml/src/ggml-hexagon/htp"
    local build_dir="/tmp/htp-build-${version}"
    local output_so="${build_dir}/libggml-htp-${version}.so"

    info "HTP skel ビルド中: ${version} -> ${output_so}"

    # Hexagon Tools Root を SDK から自動検出
    local tools_root
    if [[ -f "${HEXAGON_SDK_ROOT}/hexagon_sdk.json" ]]; then
        tools_root="$(python3 -c "
import json, sys
with open('${HEXAGON_SDK_ROOT}/hexagon_sdk.json') as f:
    d = json.load(f)
path = d['root']['tools']['info'][0]['path']
print('${HEXAGON_SDK_ROOT}/' + path)
" 2>/dev/null || true)"
    fi

    if [[ -z "${tools_root}" || ! -d "${tools_root}" ]]; then
        # フォールバック: 既知のパスパターンで検索
        tools_root="$(ls -d "${HEXAGON_SDK_ROOT}/tools/HEXAGON_Tools/"*/Tools 2>/dev/null | sort -V | tail -1 || true)"
    fi

    if [[ -z "${tools_root}" || ! -d "${tools_root}" ]]; then
        warn "Hexagon Tools Root を検出できません。HEXAGON_TOOLS_ROOT 環境変数で指定してください。"
        tools_root="${HEXAGON_TOOLS_ROOT:-}"
    fi

    if [[ -z "${tools_root}" ]]; then
        error "HEXAGON_TOOLS_ROOT を設定してください (Hexagon SDK の Tools ディレクトリ)"
        exit 1
    fi
    info "Hexagon Tools Root: ${tools_root}"

    mkdir -p "${build_dir}"

    cmake -S "${htp_src}" \
          -B "${build_dir}" \
          -DDSP_VERSION="${version}" \
          -DHEXAGON_SDK_ROOT="${HEXAGON_SDK_ROOT}" \
          -DHEXAGON_TOOLS_ROOT="${tools_root}" \
          -DPREBUILT_LIB_DIR="toolv19_${version}" \
          -DCMAKE_BUILD_TYPE=Release \
          -DCMAKE_INSTALL_LIBDIR="${build_dir}"

    cmake --build "${build_dir}" --parallel

    if [[ -f "${output_so}" ]]; then
        info "ビルド成功: ${output_so}"
        mkdir -p "${JNILIBS}"
        cp "${output_so}" "${JNILIBS}/"
        info "配置完了: ${JNILIBS}/libggml-htp-${version}.so"
    else
        error "ビルド成果物が見つかりません: ${output_so}"
        exit 1
    fi
}

# ---- llama_jni (ARM64) ビルド ----------------------------------------------
build_llama_jni() {
    local build_dir="${PROJECT_ROOT}/build/hexagon-arm64"
    info "llama_jni (ARM64 + Hexagon) ビルド中..."

    mkdir -p "${build_dir}"
    cmake \
        -S "${PROJECT_ROOT}/app/src/main/cpp" \
        -B "${build_dir}" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-31 \
        -DANDROID_NDK="${ANDROID_NDK_ROOT}" \
        -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" \
        -DCMAKE_BUILD_TYPE=Release \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DGGML_HEXAGON=ON \
        -DHEXAGON_SDK_ROOT="${HEXAGON_SDK_ROOT}"

    cmake --build "${build_dir}" --target llama_jni --parallel

    info "llama_jni ビルド完了: ${build_dir}"
}

# ---- APK ビルド ------------------------------------------------------------
build_apk() {
    info "APK ビルド中 (gradle assembleRelease)..."
    cd "${PROJECT_ROOT}"
    ./gradlew assembleRelease
    local apk
    apk="$(find . -name "*release*.apk" -newer build.gradle 2>/dev/null | head -1)"
    if [[ -n "${apk}" ]]; then
        info "APK 生成完了: ${apk}"
    fi
}

# ---- メイン ----------------------------------------------------------------
main() {
    local target="${1:-all}"

    case "${target}" in
        check)
            check_prerequisites
            ;;
        htp)
            check_prerequisites
            for ver in ${HTP_DSP_VERSIONS}; do
                build_htp_skel "${ver}"
            done
            ;;
        native)
            check_prerequisites
            build_llama_jni
            ;;
        apk)
            build_apk
            ;;
        all)
            check_prerequisites
            for ver in ${HTP_DSP_VERSIONS}; do
                build_htp_skel "${ver}"
            done
            build_llama_jni
            build_apk
            ;;
        *)
            echo "使用方法: $0 [check|htp|native|apk|all]"
            echo "  check  : 前提環境チェックのみ"
            echo "  htp    : HTP skel .so ビルド + jniLibs 配置"
            echo "  native : llama_jni (ARM64) ビルド"
            echo "  apk    : gradle assembleRelease"
            echo "  all    : htp + native + apk (デフォルト)"
            exit 1
            ;;
    esac

    info "完了"
}

main "$@"
