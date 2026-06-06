# Hexagon NPU ビルドに必要なファイル一覧

NPU/HTP バックエンドを有効にするには、以下のファイルを Hexagon SDK から
取得してローカルに配置してください。配置後に通常のビルド手順でAPKが生成されます。

---

## 配置パスの概要

```
リポジトリルート/
├── app/src/main/cpp/hexagon-artifacts/    ← コンパイル時に使用
│   ├── stub/
│   │   ├── htp_iface_stub.c              (qaic 生成)
│   │   └── htp_iface.h                   (qaic 生成)
│   └── sdk-incs/                         (Hexagon SDK ヘッダ群)
│       ├── AEEStdErr.h
│       ├── AEEStdDef.h
│       ├── AEEBufBound.h
│       ├── dspqueue.h
│       ├── domain.h
│       ├── remote.h
│       ├── rpcmem.h
│       └── stddef/                       (SDK incs/stddef/ の内容)
│           ├── AEEVaList.h
│           └── ...
└── app/src/main/jniLibs/arm64-v8a/       ← APK に同梱・実行時に使用
    ├── libggml-htp-v68.so
    ├── libggml-htp-v69.so
    ├── libggml-htp-v73.so
    ├── libggml-htp-v75.so
    ├── libggml-htp-v79.so
    └── libggml-htp-v81.so
```

---

## ファイル詳細

### A. コンパイル時ファイル (`hexagon-artifacts/`)

#### A-1. qaic 生成スタブ (`stub/`)

| ファイル | 生成元 | 取得方法 |
|---|---|---|
| `htp_iface_stub.c` | `htp_iface.idl` → qaic | Hexagon SDK の qaic ツールで生成 |
| `htp_iface.h`      | `htp_iface.idl` → qaic | 同上 |

生成コマンド:
```bash
# HEXAGON_SDK_ROOT = Hexagon SDK のインストールパス
QAIC="${HEXAGON_SDK_ROOT}/tools/utils/qaic/Ubuntu22/qaic"
IDL="app/src/main/cpp/llama/ggml/src/ggml-hexagon/htp/htp_iface.idl"
OUT="app/src/main/cpp/hexagon-artifacts/stub"
mkdir -p "${OUT}"
"${QAIC}" -mdll -o "${OUT}" "-I${HEXAGON_SDK_ROOT}/incs/stddef" "${IDL}"
```

#### A-2. Hexagon SDK ヘッダ群 (`sdk-incs/`)

`ggml-hexagon.cpp` が直接インクルードするヘッダ:

| ファイル | SDK パス | 備考 |
|---|---|---|
| `AEEStdErr.h`  | `${SDK}/incs/AEEStdErr.h`  | Qualcomm エラーコード定義 |
| `dspqueue.h`   | `${SDK}/incs/dspqueue.h`   | DSP キュー管理 API |
| `rpcmem.h`     | `${SDK}/incs/rpcmem.h`     | リモートメモリ割り当て API |
| `domain.h`     | `${SDK}/incs/domain.h`     | FastRPC ドメイン定義 |
| `remote.h`     | `${SDK}/incs/remote.h`     | FastRPC リモート API |

推移的インクルードにより追加で必要になるヘッダ (SDK バージョンにより変動):

| ファイル | SDK パス |
|---|---|
| `AEEStdDef.h`  | `${SDK}/incs/AEEStdDef.h`  |
| `AEEBufBound.h`| `${SDK}/incs/AEEBufBound.h`|
| `HAP_farf.h`   | `${SDK}/utils/examples/HAP_farf.h` または `${SDK}/incs/HAP_farf.h` |
| `incs/stddef/` 以下の全ファイル | `${SDK}/incs/stddef/*.h` |

**推奨: SDK の `incs/` ディレクトリをそのままコピーする**

```bash
SDK="${HEXAGON_SDK_ROOT}"
DST="app/src/main/cpp/hexagon-artifacts/sdk-incs"
mkdir -p "${DST}"

# incs/ 全体をコピー (推移的依存を確実にカバー)
cp -r "${SDK}/incs/."        "${DST}/"
cp -r "${SDK}/incs/stddef/." "${DST}/stddef/"

# utils/examples/ (HAP_farf.h 等が含まれる場合)
if [[ -d "${SDK}/utils/examples" ]]; then
  cp -r "${SDK}/utils/examples/." "${DST}/"
fi

echo "ヘッダ数: $(find "${DST}" -name '*.h' | wc -l)"
```

---

### B. 実行時ファイル (`jniLibs/arm64-v8a/`)

Hexagon DSP 上で動作する HTP skel ライブラリ。
APK に同梱され、インストール時に `/data/app/.../lib/arm64/` に展開されます。
JNI 側が `ADSP_LIBRARY_PATH` をこのパスに設定することで DSP ローダーが検出します。

| ファイル | DSP バージョン | 対応 Snapdragon SoC | 優先度 |
|---|---|---|---|
| `libggml-htp-v68.so` | V68 | 865, 870, 888 以前の一部 | 低 |
| `libggml-htp-v69.so` | V69 | 888 / 888+ | 低 |
| `libggml-htp-v73.so` | V73 | **8 Gen 1 (SM8450)** | ★ |
| `libggml-htp-v75.so` | V75 | **8 Gen 2 (SM8550)** | ★ |
| `libggml-htp-v79.so` | V79 | **8 Gen 3 (SM8650)** | ★ |
| `libggml-htp-v81.so` | V81 | **8 Elite (SM8750)**  | ★ |

**ターゲットデバイスが判明している場合は 1 ファイルだけで可**  
(不要な DSP バージョンの skel は APK サイズを増やすだけ)

取得方法:
```bash
# Hexagon SDK で全バージョンをビルドする場合:
bash scripts/bundle-hexagon-artifacts.sh

# 生成後のコピー:
mkdir -p app/src/main/jniLibs/arm64-v8a
cp /tmp/hexagon-bundle/hexagon-artifacts-v*/skel/libggml-htp-*.so \
   app/src/main/jniLibs/arm64-v8a/
```

---

## Hexagon SDK の取得

```
ダウンロード: https://developer.qualcomm.com/software/hexagon-dsp-sdk
要: Qualcomm アカウント (無料登録)
バージョン: 5.4.x または 5.5.x を推奨 (toolv19 対応)
OS: Ubuntu 20.04 / 22.04 (Linux 版)
容量: 約 1.5〜2 GB
```

SDK を展開後に確認すべきパス:
```bash
${HEXAGON_SDK_ROOT}/
├── incs/                       # ヘッダ群 (A-2 で使用)
├── incs/stddef/                # stddef ヘッダ群
├── utils/examples/             # HAP_farf.h 等
├── tools/
│   ├── utils/qaic/Ubuntu22/qaic  # IDL コンパイラ (A-1 で使用)
│   └── HEXAGON_Tools/X.Y.Z/Tools/  # DSP クロスコンパイラ (skel ビルドで使用)
└── hexagon_sdk.json            # SDK バージョン情報
```

---

## 自動化スクリプト

上記の手順をすべて実行する場合:
```bash
export HEXAGON_SDK_ROOT=/path/to/hexagon-sdk-5.4.0
bash scripts/bundle-hexagon-artifacts.sh
```

個別に操作する場合:
```bash
export HEXAGON_SDK_ROOT=/path/to/hexagon-sdk-5.4.0

# Step 1: stub 生成
bash scripts/setup-hexagon-sdk.sh check  # 前提確認

# Step 2: HTP skel ビルド + jniLibs 配置
bash scripts/setup-hexagon-sdk.sh htp

# Step 3: SDK ヘッダコピー
SDK="${HEXAGON_SDK_ROOT}"
DST="app/src/main/cpp/hexagon-artifacts/sdk-incs"
mkdir -p "${DST}"
cp -r "${SDK}/incs/."        "${DST}/"
[[ -d "${SDK}/utils/examples" ]] && cp -r "${SDK}/utils/examples/." "${DST}/"

# Step 4: stub 生成
QAIC=$(find "${SDK}/tools/utils/qaic" -name qaic -type f | head -1)
IDL="app/src/main/cpp/llama/ggml/src/ggml-hexagon/htp/htp_iface.idl"
OUT="app/src/main/cpp/hexagon-artifacts/stub"
mkdir -p "${OUT}"
"${QAIC}" -mdll -o "${OUT}" "-I${SDK}/incs/stddef" "${IDL}"

# Step 5: APK ビルド (Hexagon が自動検出される)
./gradlew assembleRelease
```

---

## 確認コマンド

配置が正しいか確認:
```bash
echo "=== stub ==="; ls app/src/main/cpp/hexagon-artifacts/stub/ 2>/dev/null || echo "MISSING"
echo "=== sdk-incs ==="; find app/src/main/cpp/hexagon-artifacts/sdk-incs -name "AEEStdErr.h" 2>/dev/null || echo "MISSING"
echo "=== skel ==="; ls app/src/main/jniLibs/arm64-v8a/libggml-htp-*.so 2>/dev/null || echo "MISSING"
```

CMake ログで確認:
```
# 正常な場合:
hexagon: build mode=artifacts
hexagon: using pre-built stub=.../hexagon-artifacts/stub/htp_iface_stub.c
hexagon: Hexagon/HTP sources added to llama_jni
```
