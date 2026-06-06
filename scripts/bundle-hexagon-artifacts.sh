#!/usr/bin/env bash
# =============================================================================
# Hexagon artifacts バンドル作成スクリプト (ローカル実行用)
#
# GitHub Actions の build-hexagon-artifacts.yml と同等の処理をローカルで実行し、
# hexagon-artifacts-v{VERSION}.tar.gz を生成します。
#
# 生成したバンドルは GitHub Release に手動アップロードするか、
# gh release create で発行してください。
#
# 使用方法:
#   export HEXAGON_SDK_ROOT=/opt/hexagon/6.6.0.0
#   bash scripts/bundle-hexagon-artifacts.sh
#
# SDK 入手 (ログイン不要のコミュニティ CI 版・推奨):
#   https://github.com/snapdragon-toolchain/hexagon-sdk/releases/download/v6.6.0.0/hexagon-sdk-v6.6.0.0-amd64-lnx.tar.xz
#
# 環境変数:
#   HEXAGON_SDK_ROOT    Hexagon SDK のインストールパス
#   HEXAGON_TOOLS_ROOT  Hexagon DSP クロスコンパイラのパス (自動検出可)
#   SDK_VERSION         バージョン文字列 (デフォルト: SDK から自動取得)
#   DSP_VERSIONS        ビルド対象 DSP バージョン (デフォルト: v68 v69 v73 v75 v79 v81)
#   OUTPUT_DIR          出力ディレクトリ (デフォルト: /tmp/hexagon-bundle)
# =============================================================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
step()  { echo -e "\n${CYAN}──── $* ────${NC}"; }

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ---- 設定 ------------------------------------------------------------------
HEXAGON_SDK_ROOT="${HEXAGON_SDK_ROOT:-}"
HEXAGON_TOOLS_ROOT="${HEXAGON_TOOLS_ROOT:-}"
DSP_VERSIONS="${DSP_VERSIONS:-v68 v69 v73 v75 v79 v81}"
OUTPUT_DIR="${OUTPUT_DIR:-/tmp/hexagon-bundle}"
# ---------------------------------------------------------------------------

# ---- 前提チェック ----------------------------------------------------------
step "前提チェック"

if [[ -z "${HEXAGON_SDK_ROOT}" || ! -d "${HEXAGON_SDK_ROOT}" ]]; then
    error "HEXAGON_SDK_ROOT が設定されていないか、存在しません: '${HEXAGON_SDK_ROOT}'"
    error "Hexagon SDK を https://developer.qualcomm.com/software/hexagon-dsp-sdk から入手し、"
    error "HEXAGON_SDK_ROOT=/path/to/sdk を指定してください。"
    exit 1
fi
info "SDK: ${HEXAGON_SDK_ROOT}"

# qaic 検索 (6.x: ipc/fastrpc/qaic/bin, 5.x: tools/utils/qaic — SDK 全体を検索)
QAIC=$(find "${HEXAGON_SDK_ROOT}" \( -type f -o -type l \) -name "qaic" 2>/dev/null | head -1 || true)
if [[ -z "${QAIC}" ]]; then
    error "qaic が見つかりません (探索: ${HEXAGON_SDK_ROOT})"
    exit 1
fi
chmod +x "${QAIC}"
info "qaic: ${QAIC}"

# SDK バージョン取得
if [[ -z "${SDK_VERSION:-}" ]]; then
    if [[ -f "${HEXAGON_SDK_ROOT}/hexagon_sdk.json" ]]; then
        SDK_VERSION=$(python3 -c "
import json
with open('${HEXAGON_SDK_ROOT}/hexagon_sdk.json') as f:
    d = json.load(f)
try:
    print(d.get('version', ''))
except: pass
" 2>/dev/null || echo "")
    fi
    SDK_VERSION="${SDK_VERSION:-unknown}"
fi
info "SDK Version: ${SDK_VERSION}"

# Hexagon Tools Root 自動検出
if [[ -z "${HEXAGON_TOOLS_ROOT}" || ! -d "${HEXAGON_TOOLS_ROOT}" ]]; then
    HEXAGON_TOOLS_ROOT=$(find "${HEXAGON_SDK_ROOT}/tools/HEXAGON_Tools" \
        -maxdepth 3 -name "cc" -type f 2>/dev/null \
        | head -1 | xargs dirname | xargs dirname | xargs dirname 2>/dev/null || true)
    if [[ -z "${HEXAGON_TOOLS_ROOT}" || ! -d "${HEXAGON_TOOLS_ROOT}" ]]; then
        warn "HEXAGON_TOOLS_ROOT を検出できませんでした。HTP skel ビルドに失敗する可能性があります。"
    else
        info "Hexagon Tools Root: ${HEXAGON_TOOLS_ROOT}"
    fi
fi

# ---- 出力ディレクトリ準備 ---------------------------------------------------
BUNDLE_NAME="hexagon-artifacts-v${SDK_VERSION}"
BUNDLE_DIR="${OUTPUT_DIR}/${BUNDLE_NAME}"
mkdir -p "${BUNDLE_DIR}/stub"
mkdir -p "${BUNDLE_DIR}/sdk-incs"
mkdir -p "${BUNDLE_DIR}/skel"

# ---- Step 1: htp_iface_stub.c 生成 ----------------------------------------
step "htp_iface_stub.c 生成 (qaic)"

IDL="${PROJECT_ROOT}/app/src/main/cpp/llama/ggml/src/ggml-hexagon/htp/htp_iface.idl"
STUB_TMP="${OUTPUT_DIR}/stub_gen"
mkdir -p "${STUB_TMP}"

"${QAIC}" -mdll \
    -o "${STUB_TMP}" \
    "-I${HEXAGON_SDK_ROOT}/incs" \
    "-I${HEXAGON_SDK_ROOT}/incs/stddef" \
    "${IDL}"

cp "${STUB_TMP}/htp_iface_stub.c" "${BUNDLE_DIR}/stub/"
cp "${STUB_TMP}/htp_iface.h"       "${BUNDLE_DIR}/stub/"
info "stub/ 生成完了"

# ---- Step 2: SDK ヘッダ収集 ------------------------------------------------
step "SDK ヘッダ収集"

for SRC_DIR in \
    "${HEXAGON_SDK_ROOT}/incs" \
    "${HEXAGON_SDK_ROOT}/incs/stddef" \
    "${HEXAGON_SDK_ROOT}/utils/examples"; do
    if [[ -d "${SRC_DIR}" ]]; then
        cp -r "${SRC_DIR}/." "${BUNDLE_DIR}/sdk-incs/" 2>/dev/null || true
    fi
done

HEADER_COUNT=$(find "${BUNDLE_DIR}/sdk-incs" -name "*.h" | wc -l)
info "sdk-incs/: ${HEADER_COUNT} ヘッダファイル収集"

# ---- Step 3: HTP skel ビルド -----------------------------------------------
step "HTP skel ビルド"

HTP_SRC="${PROJECT_ROOT}/app/src/main/cpp/llama/ggml/src/ggml-hexagon/htp"
BUILD_FAILED=()
BUILT_COUNT=0

for VER in ${DSP_VERSIONS}; do
    echo ""
    info "Building: libggml-htp-${VER}.so"
    BUILD_DIR="${OUTPUT_DIR}/skel-build/${VER}"
    mkdir -p "${BUILD_DIR}"

    CMAKE_ARGS=(
        -S "${HTP_SRC}"
        -B "${BUILD_DIR}"
        -DCMAKE_TOOLCHAIN_FILE="${HTP_SRC}/cmake-toolchain.cmake"
        -DDSP_VERSION="${VER}"
        -DHEXAGON_SDK_ROOT="${HEXAGON_SDK_ROOT}"
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_LIBDIR="${BUILD_DIR}"
    )
    [[ -n "${HEXAGON_TOOLS_ROOT}" ]] && {
        CMAKE_ARGS+=(-DHEXAGON_TOOLS_ROOT="${HEXAGON_TOOLS_ROOT}")
        CMAKE_ARGS+=(-DPREBUILT_LIB_DIR="toolv19_${VER}")
    }

    if cmake "${CMAKE_ARGS[@]}" 2>&1 | tail -5 \
    && cmake --build "${BUILD_DIR}" --parallel 2>&1 | tail -5; then
        SO="${BUILD_DIR}/libggml-htp-${VER}.so"
        if [[ -f "${SO}" ]]; then
            cp "${SO}" "${BUNDLE_DIR}/skel/"
            SIZE=$(du -sh "${SO}" | cut -f1)
            info "  ✅ libggml-htp-${VER}.so (${SIZE})"
            BUILT_COUNT=$((BUILT_COUNT + 1))
        else
            warn "  ⚠️  ${SO} not found after build"
            BUILD_FAILED+=("${VER}")
        fi
    else
        warn "  ⚠️  Build failed for ${VER}"
        BUILD_FAILED+=("${VER}")
    fi
done

echo ""
info "HTP skel: ${BUILT_COUNT} 成功 / ${#BUILD_FAILED[@]} 失敗"
[[ ${#BUILD_FAILED[@]} -gt 0 ]] && warn "失敗: ${BUILD_FAILED[*]}"

# ---- VERSION ファイル -------------------------------------------------------
cat > "${BUNDLE_DIR}/VERSION" <<EOF
hexagon_sdk_version=${SDK_VERSION}
dsp_versions=${DSP_VERSIONS}
built_skels=${BUILT_COUNT}
build_date=$(date -u +%Y-%m-%dT%H:%M:%SZ)
build_host=$(hostname)
EOF

# ---- バンドル作成 -----------------------------------------------------------
step "tar.gz 作成"

TARBALL="${OUTPUT_DIR}/${BUNDLE_NAME}.tar.gz"
cd "${OUTPUT_DIR}"
tar -czf "${TARBALL}" "${BUNDLE_NAME}/"

SIZE=$(du -sh "${TARBALL}" | cut -f1)
info "バンドル作成完了: ${TARBALL} (${SIZE})"
echo ""
echo "📦 内容:"
tar -tzf "${TARBALL}" | head -30

# ---- 次のステップ案内 -------------------------------------------------------
echo ""
echo -e "${CYAN}════════════════════════════════════════════${NC}"
echo -e "${GREEN}✅ 完了${NC}: ${TARBALL}"
echo ""
echo "次のステップ:"
echo "  1. GitHub Release として発行:"
echo "     gh release create hexagon-artifacts-v${SDK_VERSION} \\"
echo "       '${TARBALL}' \\"
echo "       --title 'Hexagon Artifacts v${SDK_VERSION}' \\"
echo "       --latest=false"
echo ""
echo "  2. または CI に直接配置してテスト:"
echo "     DEST='app/src/main/cpp/hexagon-artifacts'"
echo "     mkdir -p \"\${DEST}\""
echo "     tar -xf '${TARBALL}' -C \"\${DEST}\" --strip-components=1"
echo "     cp \"\${DEST}/skel/\"*.so app/src/main/jniLibs/arm64-v8a/"
echo -e "${CYAN}════════════════════════════════════════════${NC}"
