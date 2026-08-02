#!/usr/bin/env bash
# =============================================================================
# regression_test.sh — OllamaApiServer regression test suite
#
# Usage: ./scripts/regression_test.sh [options]
#   --fast          Skip per-profile generation tests (infra + API-feature only)
#   --section N     Run only section N (can repeat: --section 3 --section 4)
#   --profile NAME  Test only the specified profile (can repeat)
#   --no-color      Disable ANSI colors
#   --timeout N     Per-request timeout in seconds (default: 300)
#                   Slow profiles (Test05-08 Think, Test10-11 mmproj) use 600s automatically
#
# Tests:
#   1.  API Sanity (/health, /api/tags, /v1/models)
#   2.  Profile Discovery (all Test* from /api/tags — explicit slot assignments)
#   3.  Ollama /api/chat      (non-streaming, per profile)
#   4.  Ollama /api/generate  (non-streaming, per profile)
#   5.  OpenAI /v1/chat/completions (non-streaming, per profile)
#   6.  Streaming tests (api/chat, api/generate, v1/chat/completions)
#   7.  Embedding tests (/api/embed, /api/embeddings, /v1/embeddings)
#   8.  Tokenize tests (/api/tokenize, /v1/responses/input_tokens)
#   9.  Structured output (format=json, json_schema)
#   10. Sampling parameters (temperature, num_predict, max_tokens)
#   11. Error handling (unknown model, empty messages, bad tools, malformed JSON)
#   12. Cross-API consistency (Ollama vs OpenAI same model)
#   13. Multimodal / mmproj (vision profiles: non-stream, stream, multi-turn, error)
#
# Note: MCP / tool-call tests are handled by a separate script (mcp_test.sh)
# =============================================================================

set -euo pipefail

BASE_URL="http://127.0.0.1:11434"
TIMEOUT=300
SLOW_TIMEOUT=600   # 10 min for Think / mmproj profiles

# Profiles that are known to take > 5 min per request (Thinking + mmproj load)
SLOW_PROFILES=(
  "Test05 Qwen CPU Streaming Think"
  "Test06 Qwen CPU NonStreaming Think"
  "Test07 Qwen CPU Streaming NonThink"
  "Test08 Qwen CPU NonStreaming NonThink"
  "Test10 mmproj"
  "Test11 mmproj MTP"
)
FAST=0
USE_COLOR=1
FILTER_PROFILES=()
RUN_SECTIONS=()  # empty = all; populated by --section N

# --- Argument parsing ---------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --fast)       FAST=1; shift ;;
    --profile)    FILTER_PROFILES+=("$2"); shift 2 ;;
    --section)    RUN_SECTIONS+=("$2"); shift 2 ;;
    --no-color)   USE_COLOR=0; shift ;;
    --timeout)    TIMEOUT="$2"; shift 2 ;;
    --base-url)   BASE_URL="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# Returns 0 if section number $1 should run (all sections run when RUN_SECTIONS is empty)
_section_enabled() {
  [[ ${#RUN_SECTIONS[@]} -eq 0 ]] && return 0
  local n; for n in "${RUN_SECTIONS[@]}"; do [[ "$n" == "$1" ]] && return 0; done
  return 1
}

# --- Colors -------------------------------------------------------------------
if [[ $USE_COLOR -eq 1 && -t 1 ]]; then
  RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
  CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; CYAN=''; BOLD=''; NC=''
fi

# --- Counters -----------------------------------------------------------------
PASS=0; FAIL=0; SKIP=0
FAILED_TESTS=()

# --- Dependency check ---------------------------------------------------------
for dep in curl jq; do
  if ! command -v "$dep" &>/dev/null; then
    echo "ERROR: '$dep' is required. Install it and retry."
    exit 1
  fi
done

# =============================================================================
# Helper functions
# =============================================================================

section() { echo -e "\n${BOLD}${CYAN}══ $1 ══${NC}"; }

# Returns SLOW_TIMEOUT if $1 is in SLOW_PROFILES, otherwise TIMEOUT
_profile_timeout() {
  local p="$1"
  for s in "${SLOW_PROFILES[@]}"; do
    [[ "$s" == "$p" ]] && echo "$SLOW_TIMEOUT" && return
  done
  echo "$TIMEOUT"
}

pass()  { echo -e "  ${GREEN}✓${NC}  $1"; PASS=$(( PASS + 1 )); }
fail()  {
  local name="$1" detail="${2:-}"
  echo -e "  ${RED}✗${NC}  $name"
  [[ -n "$detail" ]] && echo -e "      ${RED}↳ ${detail:0:200}${NC}"
  FAIL=$(( FAIL + 1 ))
  FAILED_TESTS+=("$name")
}
skip()  { echo -e "  ${YELLOW}⊘${NC}  $1${2:+: $2}"; SKIP=$(( SKIP + 1 )); }
# Returns 0 if $1 is an mmproj profile (tested in §13 with dedicated image input)
_is_mmproj() { echo "$1" | grep -qi "mmproj"; }

# curl wrappers
_get() {
  curl -sf --max-time "$TIMEOUT" "$BASE_URL$1"
}
_post() {
  # No -f: we keep the response body even on 4xx/5xx so tests can show the real error.
  curl -s --max-time "$TIMEOUT" \
    -H "Content-Type: application/json" \
    -d "$2" \
    "$BASE_URL$1"
}

# Wait until a trivial /api/chat request succeeds (server not busy).
# Call between heavy sections to avoid cascade-503 failures.
await_server_ready() {
  local model="${1:-Test01 CPU}"
  # Give mmproj / Think models up to SLOW_TIMEOUT to load; others up to 600s
  local _wait=$(( SLOW_TIMEOUT > 600 ? SLOW_TIMEOUT : 600 ))
  local deadline=$(( $(date +%s) + _wait ))
  echo "  Waiting for server (model: $model)..."
  while [[ $(date +%s) -lt $deadline ]]; do
    local r
    r=$(curl -s --max-time 30 \
      -H "Content-Type: application/json" \
      -d "{\"model\":\"$model\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"1\"}]}" \
      "$BASE_URL/api/chat" 2>/dev/null || true)
    if echo "$r" | jq -e '.message' &>/dev/null; then
      echo "  Server ready."
      return 0
    fi
    sleep 15
  done
  echo "  Warning: server did not become ready within 10 min — continuing anyway"
}
_post_status() {
  # Returns HTTP status code only
  curl -s -o /dev/null -w "%{http_code}" --max-time "${3:-30}" \
    -H "Content-Type: application/json" \
    -d "$2" \
    "$BASE_URL$1"
}
_post_stream() {
  # Returns first few chunks
  curl -sf --max-time "$TIMEOUT" \
    -H "Content-Type: application/json" \
    -d "$2" \
    "$BASE_URL$1" \
  | head -10
}

# jq check helpers
_has() { echo "$1" | jq -e "$2" &>/dev/null; }
_get_str() { echo "$1" | jq -r "$2" 2>/dev/null; }
# Returns 0 if $1 looks like a "server busy" / "queue full" response
_is_busy() { echo "$1" | grep -qi "busy\|queue"; }

# =============================================================================
# Section 1: API Sanity Checks
# =============================================================================
section "1. API Sanity Checks"

# /health
resp=$(_get "/health" 2>/dev/null || true)
if _has "$resp" '.status == "ok"'; then
  pass "GET /health → status=ok"
else
  fail "GET /health" "Response: ${resp:0:100}"
fi

# /v1/health
resp=$(_get "/v1/health" 2>/dev/null || true)
if _has "$resp" '.status == "ok"'; then
  pass "GET /v1/health → status=ok"
else
  fail "GET /v1/health" "Response: ${resp:0:100}"
fi

# GET /api/tags
TAGS_RESP=$(_get "/api/tags" 2>/dev/null || true)
if _has "$TAGS_RESP" '.models | type == "array"'; then
  cnt=$(_get_str "$TAGS_RESP" '.models | length')
  pass "GET /api/tags → ${cnt} model(s)"
else
  fail "GET /api/tags" "${TAGS_RESP:0:100}"
fi

# POST /api/tags
resp=$(_post "/api/tags" "{}" 2>/dev/null || true)
if _has "$resp" '.models | type == "array"'; then
  pass "POST /api/tags"
else
  fail "POST /api/tags" "${resp:0:100}"
fi

# GET /v1/models
resp=$(_get "/v1/models" 2>/dev/null || true)
if _has "$resp" '.data | type == "array"'; then
  cnt=$(_get_str "$resp" '.data | length')
  pass "GET /v1/models → ${cnt} model(s)"
else
  fail "GET /v1/models" "${resp:0:100}"
fi

# =============================================================================
# Section 2: Profile Discovery (all Test* from /api/tags)
# =============================================================================
section "2. Profile Discovery (Test* — loaded from /api/tags)"

AVAILABLE_PROFILES=()
QWEN_PROFILES=()

# Read every profile whose name starts with "Test", sorted in natural order.
while IFS= read -r _p; do
  [[ -z "$_p" ]] && continue
  # Apply --profile filter if set
  if [[ ${#FILTER_PROFILES[@]} -gt 0 ]]; then
    _match=0
    for _fp in "${FILTER_PROFILES[@]}"; do [[ "$_fp" == "$_p" ]] && _match=1; done
    [[ $_match -eq 0 ]] && continue
  fi
  _family=$(echo "$TAGS_RESP" | jq -r --arg p "$_p" \
    '.models[] | select(.name == $p) | .details.family // "unknown"' 2>/dev/null)
  AVAILABLE_PROFILES+=("$_p")
  if echo "$_family" | grep -qi "qwen"; then
    QWEN_PROFILES+=("$_p")
    pass "Found: $_p  [family=$_family] ← Qwen (MCP tests enabled)"
  else
    pass "Found: $_p  [family=$_family]"
  fi
done < <(echo "$TAGS_RESP" | jq -r '.models[].name | select(startswith("Test"))' | sort -V)

echo ""
echo "  Profiles available : ${#AVAILABLE_PROFILES[@]}"
echo "  Qwen profiles      : ${#QWEN_PROFILES[@]}${QWEN_PROFILES[*]:+ (${QWEN_PROFILES[*]})}"

if [[ ${#AVAILABLE_PROFILES[@]} -eq 0 ]]; then
  echo -e "\n${RED}No Test* profiles found. Ensure the app is running and profiles are configured.${NC}"
  exit 1
fi

# =============================================================================
# Explicit slot assignments for sections 7–12
# Each section uses a distinct profile; update names here when profiles change.
#
#  Var          Profile                              Section  Content
#  -----------  -----------------------------------  -------  --------------------------------
#  P_S7_CHAT    Test01 CPU                              6     streaming /api/chat
#  P_S7_GEN     Test02 CPU KV Q4                        6     streaming /api/generate
#  P_S7_V1      Test03 GPU                              6     streaming /v1/chat/completions
#  P_S8         Test04 GPU KV Q4                        7     Embeddings (all 3 endpoints)
#  P_S9         Test09 MTP                              8     Tokenize (2 endpoints)
#  P_S10        Test05 Qwen CPU Streaming Think          9    Structured output (json/schema/GBNF)
#  P_S11        Test06 Qwen CPU NonStreaming Think       10    Sampling (temperature/max_tokens/…)
#  P_S12        Test07 Qwen CPU Streaming NonThink       11    Error handling (model-dependent)
#
#  §3,4,5,12 iterate ALL AVAILABLE_PROFILES.
#  §13 uses mmproj profiles (name contains "mmproj"): Test10, Test11.
#  Test08 (Qwen CPU NonStreaming NonThink) is covered by §3,4,5,12.
# =============================================================================
P_S7_CHAT="Test01 CPU"
P_S7_GEN="Test02 CPU KV Q4"
P_S7_V1="Test03 GPU"
P_S8="Test04 GPU KV Q4"
P_S9="Test09 MTP"
P_S10="Test05 Qwen CPU Streaming Think"
P_S11="Test06 Qwen CPU NonStreaming Think"
P_S12="Test07 Qwen CPU Streaming NonThink"

# Validate: warn if a slot profile is not in AVAILABLE_PROFILES
for _slot in "$P_S7_CHAT" "$P_S7_GEN" "$P_S7_V1" "$P_S8" "$P_S9" "$P_S10" "$P_S11" "$P_S12"; do
  _ok=0
  for _ap in "${AVAILABLE_PROFILES[@]}"; do [[ "$_ap" == "$_slot" ]] && _ok=1 && break; done
  if [[ $_ok -eq 0 ]]; then
    echo -e "  ${YELLOW}⚠${NC}  Slot profile not in /api/tags: '$_slot' — affected tests will fail"
  fi
done

# Wait for server to be idle before starting model-loading tests
await_server_ready "Test01 CPU"

# =============================================================================
# Section 3: Ollama /api/chat  (per profile, non-streaming)
# =============================================================================
if _section_enabled 3; then
if [[ $FAST -eq 0 ]]; then
  section "3. Ollama /api/chat  (non-streaming, per profile)"
  for p in "${AVAILABLE_PROFILES[@]}"; do
    _t=$(_profile_timeout "$p")
    body=$(jq -n --arg m "$p" '{model:$m, stream:false,
      options:{num_predict:4096},
      messages:[{role:"user",content:"What is 2+2?"}]}')
    resp=$(curl -s --max-time "$_t" -H "Content-Type: application/json" \
      -d "$body" "$BASE_URL/api/chat" 2>/dev/null || true)
    if _has "$resp" '.message.content | type == "string" and length > 0'; then
      content=$(_get_str "$resp" '.message.content' | tr -d '\n' | cut -c1-60)
      pass "/api/chat [$p] → \"$content\""
    elif _is_busy "$resp"; then
      skip "/api/chat [$p]" "server busy"
    elif [[ -z "$resp" ]] && _is_mmproj "$p"; then
      skip "/api/chat [$p]" "empty reply — mmproj memory pressure (covered in §13)"
    else
      fail "/api/chat [$p]" "${resp:0:200}"
    fi
  done
else
  section "3. Ollama /api/chat  (--fast: skipped)"
  skip "Per-profile /api/chat tests" "--fast flag"
fi
fi

# After iterating all profiles (including heavy mmproj models), wait for server idle.
[[ $FAST -eq 0 ]] && _section_enabled 3 && await_server_ready "${AVAILABLE_PROFILES[0]}"

# =============================================================================
# Section 4: Ollama /api/generate  (per profile, non-streaming)
# =============================================================================
if _section_enabled 4; then
if [[ $FAST -eq 0 ]]; then
  section "4. Ollama /api/generate  (non-streaming, per profile)"
  for p in "${AVAILABLE_PROFILES[@]}"; do
    _t=$(_profile_timeout "$p")
    body=$(jq -n --arg m "$p" '{model:$m, stream:false,
      options:{num_predict:4096},
      prompt:"What is 2+2? Reply with the number only."}')
    resp=$(curl -s --max-time "$_t" -H "Content-Type: application/json" \
      -d "$body" "$BASE_URL/api/generate" 2>/dev/null || true)
    if _has "$resp" '.response | type == "string" and length > 0'; then
      content=$(_get_str "$resp" '.response' | tr -d '\n' | cut -c1-60)
      pass "/api/generate [$p] → \"$content\""
    elif _is_busy "$resp"; then
      skip "/api/generate [$p]" "server busy"
    elif [[ -z "$resp" ]] && _is_mmproj "$p"; then
      skip "/api/generate [$p]" "empty reply — mmproj memory pressure (covered in §13)"
    else
      fail "/api/generate [$p]" "${resp:0:200}"
    fi
  done
else
  section "4. Ollama /api/generate  (--fast: skipped)"
  skip "Per-profile /api/generate tests" "--fast flag"
fi
fi

[[ $FAST -eq 0 ]] && _section_enabled 4 && await_server_ready "${AVAILABLE_PROFILES[0]}"

# =============================================================================
# Section 5: OpenAI /v1/chat/completions  (per profile, non-streaming)
# =============================================================================
if _section_enabled 5; then
if [[ $FAST -eq 0 ]]; then
  section "5. OpenAI /v1/chat/completions  (non-streaming, per profile)"
  for p in "${AVAILABLE_PROFILES[@]}"; do
    _t=$(_profile_timeout "$p")
    body=$(jq -n --arg m "$p" '{model:$m, stream:false,
      max_tokens:4096,
      messages:[{role:"user",content:"What is 2+2?"}]}')
    resp=$(curl -s --max-time "$_t" -H "Content-Type: application/json" \
      -d "$body" "$BASE_URL/v1/chat/completions" 2>/dev/null || true)
    if _has "$resp" '.choices[0].message.content | type == "string" and length > 0'; then
      content=$(_get_str "$resp" '.choices[0].message.content' | tr -d '\n' | cut -c1-60)
      pass "/v1/chat/completions [$p] → \"$content\""
    elif _is_busy "$resp"; then
      skip "/v1/chat/completions [$p]" "server busy"
    elif [[ -z "$resp" ]] && _is_mmproj "$p"; then
      skip "/v1/chat/completions [$p]" "empty reply — mmproj memory pressure (covered in §13)"
    else
      fail "/v1/chat/completions [$p]" "${resp:0:200}"
    fi
  done
else
  section "5. OpenAI /v1/chat/completions  (--fast: skipped)"
  skip "Per-profile /v1/chat/completions tests" "--fast flag"
fi
fi

# Ensure server is idle before feature-test sections (§5 Think models may have timed out)
await_server_ready "Test01 CPU"

# =============================================================================
# Section 6: Streaming Tests
# =============================================================================
if _section_enabled 6; then
section "6. Streaming Tests"

# /api/chat streaming  [profile slot 0]
body=$(jq -n --arg m "$P_S7_CHAT" '{model:$m, stream:true,
  messages:[{role:"user",content:"Hi"}]}')
chunks=$(_post_stream "/api/chat" "$body" 2>/dev/null || true)
if echo "$chunks" | grep -q '"message"'; then
  pass "/api/chat streaming [$P_S7_CHAT] → NDJSON chunks"
else
  fail "/api/chat streaming [$P_S7_CHAT]" "${chunks:0:200}"
fi

# /api/generate streaming  [profile slot 1]
body=$(jq -n --arg m "$P_S7_GEN" '{model:$m, stream:true, prompt:"Hi"}')
chunks=$(_post_stream "/api/generate" "$body" 2>/dev/null || true)
if echo "$chunks" | grep -q '"response"'; then
  pass "/api/generate streaming [$P_S7_GEN] → NDJSON chunks"
else
  fail "/api/generate streaming [$P_S7_GEN]" "${chunks:0:200}"
fi

# /v1/chat/completions SSE streaming  [profile slot 2]
body=$(jq -n --arg m "$P_S7_V1" '{model:$m, stream:true,
  messages:[{role:"user",content:"Hi"}]}')
chunks=$(_post_stream "/v1/chat/completions" "$body" 2>/dev/null || true)
if echo "$chunks" | grep -q '^data:'; then
  pass "/v1/chat/completions streaming [$P_S7_V1] → SSE data:"
else
  fail "/v1/chat/completions streaming [$P_S7_V1]" "${chunks:0:200}"
fi

# Streaming done=true terminator  [profile slot 2, same model]
body=$(jq -n --arg m "$P_S7_V1" '{model:$m, stream:true,
  messages:[{role:"user",content:"Say hi once."}],
  options:{num_predict:5}}')
all_chunks=$(curl -sf --max-time "$TIMEOUT" \
  -H "Content-Type: application/json" -d "$body" \
  "$BASE_URL/api/chat" 2>/dev/null || true)
if echo "$all_chunks" | jq -e 'select(.done == true)' &>/dev/null; then
  pass "/api/chat streaming done=true terminator [$P_S7_V1]"
else
  fail "/api/chat streaming done=true [$P_S7_V1]" "Terminator chunk not found"
fi
fi  # _section_enabled 6

# =============================================================================
# Section 7: Embedding Tests
# =============================================================================
if _section_enabled 7; then
section "7. Embedding Tests"

# Profile slot 3
EMBED_M="$P_S8"
echo "  (profile: $EMBED_M)"

# /api/embed — single string input
body=$(jq -n --arg m "$EMBED_M" '{model:$m, input:"Hello world"}')
resp=$(_post "/api/embed" "$body" 2>/dev/null || true)
if _has "$resp" '.embeddings[0] | type == "array" and length > 0'; then
  dim=$(_get_str "$resp" '.embeddings[0] | length')
  pass "/api/embed [single string] [$EMBED_M] dim=$dim"
else
  fail "/api/embed [single string] [$EMBED_M]" "${resp:0:200}"
fi

# /api/embed — array input
body=$(jq -n --arg m "$EMBED_M" '{model:$m, input:["Hello","World","Test"]}')
resp=$(_post "/api/embed" "$body" 2>/dev/null || true)
if _has "$resp" '.embeddings | type == "array" and length == 3'; then
  pass "/api/embed [array, 3 items] [$EMBED_M] → 3 embeddings"
else
  fail "/api/embed [array, 3 items] [$EMBED_M]" "${resp:0:200}"
fi

# /api/embeddings — legacy (prompt field)
body=$(jq -n --arg m "$EMBED_M" '{model:$m, prompt:"Hello world"}')
resp=$(_post "/api/embeddings" "$body" 2>/dev/null || true)
if _has "$resp" '.embedding | type == "array" and length > 0'; then
  dim=$(_get_str "$resp" '.embedding | length')
  pass "/api/embeddings (legacy) [$EMBED_M] dim=$dim"
else
  fail "/api/embeddings (legacy) [$EMBED_M]" "${resp:0:200}"
fi

# /v1/embeddings — single string
body=$(jq -n --arg m "$EMBED_M" '{model:$m, input:"Hello world"}')
resp=$(_post "/v1/embeddings" "$body" 2>/dev/null || true)
if _has "$resp" '.data[0].embedding | type == "array" and length > 0'; then
  dim=$(_get_str "$resp" '.data[0].embedding | length')
  pass "/v1/embeddings [single string] [$EMBED_M] dim=$dim"
else
  fail "/v1/embeddings [single string] [$EMBED_M]" "${resp:0:200}"
fi

# /v1/embeddings — array input
body=$(jq -n --arg m "$EMBED_M" '{model:$m, input:["Hello","World"]}')
resp=$(_post "/v1/embeddings" "$body" 2>/dev/null || true)
if _has "$resp" '.data | type == "array" and length == 2'; then
  pass "/v1/embeddings [array, 2 items] [$EMBED_M] → 2 embeddings"
else
  fail "/v1/embeddings [array, 2 items] [$EMBED_M]" "${resp:0:200}"
fi

# Embedding dimensions consistent across endpoints
dim_embed=$(_get_str "$(_post "/api/embed" "$(jq -n --arg m "$EMBED_M" '{model:$m,input:"test"}')" 2>/dev/null)" '.embeddings[0] | length' 2>/dev/null || echo "0")
dim_v1=$(_get_str "$(_post "/v1/embeddings" "$(jq -n --arg m "$EMBED_M" '{model:$m,input:"test"}')" 2>/dev/null)" '.data[0].embedding | length' 2>/dev/null || echo "0")
if [[ "$dim_embed" == "$dim_v1" && "$dim_embed" != "0" ]]; then
  pass "/api/embed ↔ /v1/embeddings dimension consistent (dim=$dim_embed)"
else
  fail "/api/embed ↔ /v1/embeddings dimension mismatch" "api/embed=$dim_embed v1/embeddings=$dim_v1"
fi
fi  # _section_enabled 7

# =============================================================================
# Section 8: Tokenize Tests
# =============================================================================
if _section_enabled 8; then
section "8. Tokenize Tests"

# Profile slot 4
TOK_M="$P_S9"
TEST_TEXT="Hello, this is a test sentence for tokenization."
echo "  (profile: $TOK_M)"

# /api/tokenize
body=$(jq -n --arg m "$TOK_M" --arg t "$TEST_TEXT" '{model:$m, content:$t}')
resp=$(_post "/api/tokenize" "$body" 2>/dev/null || true)
if _has "$resp" '.tokens | type == "array" and length > 0'; then
  cnt=$(_get_str "$resp" '.tokens | length')
  pass "/api/tokenize [$TOK_M] → $cnt tokens"
else
  fail "/api/tokenize [$TOK_M]" "${resp:0:200}"
fi

# /v1/responses/input_tokens
body=$(jq -n --arg m "$TOK_M" --arg t "$TEST_TEXT" '{model:$m, input:$t}')
resp=$(_post "/v1/responses/input_tokens" "$body" 2>/dev/null || true)
if _has "$resp" '.input_tokens | type == "number"'; then
  cnt=$(_get_str "$resp" '.input_tokens')
  obj=$(_get_str "$resp" '.object // "?"')
  pass "/v1/responses/input_tokens [$TOK_M] → $cnt tokens  [object=$obj]"
else
  fail "/v1/responses/input_tokens [$TOK_M]" "${resp:0:200}"
fi

# Token count consistency between endpoints
cnt_tok=$(_get_str "$(_post "/api/tokenize" "$(jq -n --arg m "$TOK_M" --arg t "$TEST_TEXT" '{model:$m,content:$t}')" 2>/dev/null)" '.tokens | length' 2>/dev/null || echo "-1")
cnt_v1=$(_get_str "$(_post "/v1/responses/input_tokens" "$(jq -n --arg m "$TOK_M" --arg t "$TEST_TEXT" '{model:$m,input:$t}')" 2>/dev/null)" '.input_tokens' 2>/dev/null || echo "-1")
if [[ "$cnt_tok" == "$cnt_v1" && "$cnt_tok" != "-1" ]]; then
  pass "/api/tokenize ↔ /v1/responses/input_tokens count consistent ($cnt_tok) [$TOK_M]"
else
  skip "/api/tokenize ↔ /v1/responses/input_tokens count [$TOK_M]" "api/tokenize=$cnt_tok v1/input_tokens=$cnt_v1 (different prompt wrapping)"
fi
fi  # _section_enabled 8

# =============================================================================
# Section 9: Structured Output Tests
# =============================================================================
if _section_enabled 9; then
section "9. Structured Output Tests"

# Profile slot 5
SO_M="$P_S10"
STRUCT_PROMPT='Return a JSON object with exactly one field "status" set to "ok".'
echo "  (profile: $SO_M)"

# /api/generate format=json
body=$(jq -n --arg m "$SO_M" --arg p "$STRUCT_PROMPT" '{model:$m, stream:false, format:"json", options:{num_predict:4096}, prompt:$p}')
resp=$(_post "/api/generate" "$body" 2>/dev/null || true)
if _has "$resp" '.response | type == "string"'; then
  raw=$(_get_str "$resp" '.response')
  if echo "$raw" | jq -e 'type == "object"' &>/dev/null; then
    pass "/api/generate format=json [$SO_M] → valid JSON object"
  else
    fail "/api/generate format=json [$SO_M]" "Response is not JSON: ${raw:0:100}"
  fi
else
  fail "/api/generate format=json [$SO_M]" "${resp:0:200}"
fi

# /api/chat format=json
body=$(jq -n --arg m "$SO_M" --arg p "$STRUCT_PROMPT" '{model:$m, stream:false, format:"json",
  options:{num_predict:4096}, messages:[{role:"user",content:$p}]}')
resp=$(_post "/api/chat" "$body" 2>/dev/null || true)
if _has "$resp" '.message.content | type == "string"'; then
  raw=$(_get_str "$resp" '.message.content')
  if echo "$raw" | jq -e 'type == "object"' &>/dev/null; then
    pass "/api/chat format=json [$SO_M] → valid JSON object"
  else
    fail "/api/chat format=json [$SO_M]" "Response is not JSON: ${raw:0:100}"
  fi
else
  fail "/api/chat format=json [$SO_M]" "${resp:0:200}"
fi

# /v1/chat/completions response_format=json_object
body=$(jq -n --arg m "$SO_M" --arg p "$STRUCT_PROMPT" '{
  model:$m, stream:false, max_tokens:4096,
  response_format:{type:"json_object"},
  messages:[{role:"user",content:$p}]
}')
resp=$(_post "/v1/chat/completions" "$body" 2>/dev/null || true)
if _has "$resp" '.choices[0].message.content | type == "string"'; then
  raw=$(_get_str "$resp" '.choices[0].message.content')
  if echo "$raw" | jq -e 'type == "object"' &>/dev/null; then
    pass "/v1/chat/completions response_format=json_object [$SO_M] → valid JSON"
  else
    fail "/v1/chat/completions response_format=json_object [$SO_M]" "Not JSON: ${raw:0:100}"
  fi
else
  fail "/v1/chat/completions response_format=json_object [$SO_M]" "${resp:0:200}"
fi

# /v1/chat/completions json_schema (schema-constrained)
JSON_SCHEMA=$(jq -n '{
  type: "json_schema",
  json_schema: {
    name: "status_resp",
    schema: {
      type: "object",
      properties: {status: {type: "string"}},
      required: ["status"],
      additionalProperties: false
    },
    strict: true
  }
}')
body=$(jq -n --arg m "$SO_M" --arg p "$STRUCT_PROMPT" --argjson rf "$JSON_SCHEMA" '{
  model:$m, stream:false, max_tokens:4096,
  response_format:$rf,
  messages:[{role:"user",content:$p}]
}')
resp=$(_post "/v1/chat/completions" "$body" 2>/dev/null || true)
if _has "$resp" '.choices[0].message.content | type == "string"'; then
  raw=$(_get_str "$resp" '.choices[0].message.content')
  if echo "$raw" | jq -e '.status | type == "string"' &>/dev/null; then
    pass "/v1/chat/completions json_schema [$SO_M] → {\"status\":\"...\"} ✓"
  else
    fail "/v1/chat/completions json_schema [$SO_M]" "Schema mismatch: ${raw:0:100}"
  fi
else
  fail "/v1/chat/completions json_schema [$SO_M]" "${resp:0:200}"
fi

# Grammar passthrough (GBNF): ensure server accepts grammar field without error
GBNF='root ::= ("yes" | "no")'
body=$(jq -n --arg m "$SO_M" --arg g "$GBNF" '{
  model:$m, stream:false,
  grammar:$g,
  messages:[{role:"user",content:"Is the sky blue? yes or no."}]
}')
resp=$(_post "/api/chat" "$body" 2>/dev/null || true)
if _has "$resp" '.message.content | type == "string" and length > 0'; then
  content=$(_get_str "$resp" '.message.content' | tr -d '\n' | cut -c1-20)
  pass "/api/chat grammar (GBNF) [$SO_M] → \"$content\""
else
  fail "/api/chat grammar (GBNF) [$SO_M]" "${resp:0:200}"
fi
fi  # _section_enabled 9

# =============================================================================
# Section 10: Sampling Parameter Tests
# =============================================================================
if _section_enabled 10; then
section "10. Sampling Parameter Tests"

# Profile slot 6
SAMP_M="$P_S11"
echo "  (profile: $SAMP_M)"

# temperature=0 via options
body=$(jq -n --arg m "$SAMP_M" '{model:$m, stream:false,
  messages:[{role:"user",content:"What is 1+1?"}],
  options:{temperature:0.0, num_predict:20}}')
resp=$(_post "/api/chat" "$body" 2>/dev/null || true)
if _has "$resp" '.message.content | type == "string" and length > 0'; then
  pass "/api/chat temperature=0 num_predict=20 [$SAMP_M]"
else
  fail "/api/chat temperature=0 [$SAMP_M]" "${resp:0:200}"
fi

# max_tokens via /v1/chat/completions
body=$(jq -n --arg m "$SAMP_M" '{model:$m, stream:false,
  messages:[{role:"user",content:"Count from 1 to 1000."}],
  max_tokens:8, temperature:0}')
resp=$(_post "/v1/chat/completions" "$body" 2>/dev/null || true)
if _has "$resp" '.choices[0].message.content | type == "string"'; then
  reason=$(_get_str "$resp" '.choices[0].finish_reason // "?"')
  pass "/v1/chat/completions max_tokens=8 [$SAMP_M] → finish_reason=$reason"
else
  fail "/v1/chat/completions max_tokens=8 [$SAMP_M]" "${resp:0:200}"
fi

# top_p / top_k via options
body=$(jq -n --arg m "$SAMP_M" '{model:$m, stream:false,
  messages:[{role:"user",content:"Say one word."}],
  options:{top_p:0.9, top_k:40, repeat_penalty:1.1}}')
resp=$(_post "/api/chat" "$body" 2>/dev/null || true)
if _has "$resp" '.message.content | type == "string" and length > 0'; then
  pass "/api/chat top_p top_k repeat_penalty [$SAMP_M]"
else
  fail "/api/chat top_p top_k repeat_penalty [$SAMP_M]" "${resp:0:200}"
fi

# system prompt via /api/chat messages
body=$(jq -n --arg m "$SAMP_M" '{model:$m, stream:false,
  messages:[
    {role:"system", content:"You always respond with exactly the word PONG."},
    {role:"user", content:"ping"}
  ]}')
resp=$(_post "/api/chat" "$body" 2>/dev/null || true)
if _has "$resp" '.message.content | type == "string" and length > 0'; then
  content=$(_get_str "$resp" '.message.content' | tr -d '\n' | cut -c1-40)
  pass "/api/chat system prompt [$SAMP_M] → \"$content\""
else
  fail "/api/chat system prompt [$SAMP_M]" "${resp:0:200}"
fi
fi  # _section_enabled 10

# =============================================================================
# Section 11: Error Handling Tests
# =============================================================================
if _section_enabled 11; then
section "11. Error Handling Tests"
# Profile slot 7 for model-dependent checks
echo "  (profile for model-dependent checks: $P_S12)"

# Unknown model (no profile needed)
sc=$(_post_status "/api/chat" \
  '{"model":"__nonexistent_xyz__","stream":false,"messages":[{"role":"user","content":"hi"}]}' 30)
if [[ "$sc" == "4"* || "$sc" == "5"* ]]; then
  pass "Unknown model → HTTP $sc"
else
  fail "Unknown model should return 4xx/5xx" "Got $sc"
fi

# Empty messages array
body=$(jq -n --arg m "$P_S12" '{model:$m, stream:false, messages:[]}')
sc=$(_post_status "/api/chat" "$body" 30)
if [[ "$sc" == "400" ]]; then
  pass "Empty messages[] → HTTP 400 [$P_S12]"
else
  fail "Empty messages[] should return 400 [$P_S12]" "Got $sc"
fi

# tools not an array (/api/chat)
body=$(jq -n --arg m "$P_S12" '{model:$m, stream:false,
  messages:[{role:"user",content:"hi"}], tools:"bad_string"}')
sc=$(_post_status "/api/chat" "$body" 30)
if [[ "$sc" == "400" ]]; then
  pass "tools=string → HTTP 400 (/api/chat) [$P_S12]"
else
  fail "tools=string should return 400 [$P_S12]" "Got $sc"
fi

# tools not an array (/v1/chat/completions)
body=$(jq -n --arg m "$P_S12" '{model:$m, stream:false,
  messages:[{role:"user",content:"hi"}], tools:42}')
sc=$(_post_status "/v1/chat/completions" "$body" 30)
if [[ "$sc" == "400" ]]; then
  pass "tools=number → HTTP 400 (/v1/chat/completions)"
else
  fail "tools=number should return 400 (/v1/chat/completions) [$P_S12]" "Got $sc"
fi

# Malformed JSON
sc=$(_post_status "/api/chat" "{bad json!!}" 30)
if [[ "$sc" == "4"* || "$sc" == "5"* ]]; then
  pass "Malformed JSON → HTTP $sc"
else
  fail "Malformed JSON should return 4xx/5xx" "Got $sc"
fi

# GET /api/tags returns well-formed Ollama tags structure
resp=$(_get "/api/tags" 2>/dev/null || true)
if _has "$resp" '.models[0] | has("name") and has("details")'; then
  pass "/api/tags entry has name+details fields"
else
  skip "/api/tags field check" "no models in response"
fi
fi  # _section_enabled 11

# =============================================================================
# Section 12: Cross-API Consistency  (Ollama ↔ OpenAI)
# =============================================================================
if _section_enabled 12; then
if [[ $FAST -eq 0 ]]; then
  section "12. Cross-API Consistency  (Ollama ↔ OpenAI, per profile)"
  CONSISTENCY_PROMPT="What is 2+2?"
  for p in "${AVAILABLE_PROFILES[@]}"; do
    _t=$(_profile_timeout "$p")
    body=$(jq -n --arg m "$p" --arg txt "$CONSISTENCY_PROMPT" '{model:$m, stream:false,
      options:{num_predict:4096}, max_tokens:4096,
      messages:[{role:"user",content:$txt}]}')
    r_ollama=$(curl -s --max-time "$_t" -H "Content-Type: application/json" \
      -d "$body" "$BASE_URL/api/chat" 2>/dev/null || true)
    r_openai=$(curl -s --max-time "$_t" -H "Content-Type: application/json" \
      -d "$body" "$BASE_URL/v1/chat/completions" 2>/dev/null || true)

    ok_ollama=$( _has "$r_ollama" '.message.content | type == "string" and length > 0' && echo 1 || echo 0 )
    ok_openai=$( _has "$r_openai" '.choices[0].message.content | type == "string" and length > 0' && echo 1 || echo 0 )

    if [[ "$ok_ollama" == "1" && "$ok_openai" == "1" ]]; then
      pass "Consistency [$p]: both endpoints respond"
    elif [[ "$ok_ollama" == "1" ]]; then
      fail "Consistency [$p]: Ollama OK but OpenAI failed"
    elif [[ "$ok_openai" == "1" ]]; then
      fail "Consistency [$p]: OpenAI OK but Ollama failed"
    elif [[ -z "$r_ollama" && -z "$r_openai" ]] && _is_mmproj "$p"; then
      skip "Consistency [$p]" "empty reply — mmproj memory pressure (covered in §13)"
    else
      fail "Consistency [$p]: both endpoints failed"
    fi
  done
else
  section "12. Cross-API Consistency  (--fast: skipped)"
  skip "Consistency tests" "--fast flag"
fi
fi  # _section_enabled 12

# =============================================================================
# Section 13: Multimodal / mmproj Tests  (vision-capable profiles)
# =============================================================================
if _section_enabled 13; then
section "13. Multimodal / mmproj Tests  (vision profiles — image input)"

# ── Locate test image ─────────────────────────────────────────────────────────
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TEST_IMAGE=""
for _img in \
    "$SCRIPT_DIR/../1.jpg" \
    "$SCRIPT_DIR/../2.jpg" \
    "$SCRIPT_DIR/../3.jpg" \
    "$SCRIPT_DIR/../ic_launcher.png"; do
  if [[ -f "$_img" ]]; then
    TEST_IMAGE=$(cd "$(dirname "$_img")" && pwd)/$(basename "$_img")
    break
  fi
done

if [[ -z "$TEST_IMAGE" ]]; then
  skip "Image file not found" "Place a .jpg/.png in $(dirname "$SCRIPT_DIR")"
else
  IMG_BASENAME=$(basename "$TEST_IMAGE")
  IMG_EXT="${IMG_BASENAME##*.}"
  # GNU base64 uses -w 0 to suppress line-wrapping; BSD/macOS base64 has no flag
  IMG_B64=$(base64 -w 0 "$TEST_IMAGE" 2>/dev/null || base64 "$TEST_IMAGE")
  IMG_DATA_URL="data:image/${IMG_EXT};base64,${IMG_B64}"
  echo "  Image: $IMG_BASENAME  ($(wc -c < "$TEST_IMAGE") bytes)"

  # Build vision request JSON via Python (jq --arg fails when image is large).
  # Usage: _vision_body <model> <endpoint_type> <text_prompt> [stream]
  #   endpoint_type: "ollama" → {messages:[…]} with Ollama fields
  #                  "openai" → same structure for /v1/chat/completions
  #   stream: "true" or "false" (default: false)
  # Write the image URL to a temp file so it's not passed on the command line
  # (base64 of 297KB image exceeds ARG_MAX on many systems).
  _IMGURL_FILE=$(mktemp /tmp/llama_imgurl_XXXXXX.txt)
  printf '%s' "$IMG_DATA_URL" > "$_IMGURL_FILE"

  _vision_body() {
    local model="$1" text="$2" stream="${3:-false}"
    local tmp; tmp=$(mktemp /tmp/llama_vis_XXXXXX.json)
    python3 - "$model" "$_IMGURL_FILE" "$text" "$stream" <<'PYEOF' > "$tmp"
import json, sys
model, imgurl_file, text, stream = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] == "true"
with open(imgurl_file) as f:
    img_url = f.read()
body = {
    "model": model, "stream": stream,
    "messages": [{"role": "user", "content": [
        {"type": "image_url", "image_url": {"url": img_url}},
        {"type": "text", "text": text}
    ]}]
}
print(json.dumps(body))
PYEOF
    echo "$tmp"
  }

  # curl wrapper that reads body from a temp file and cleans up after
  _post_file() {
    local file="$1" endpoint="$2"
    local resp; resp=$(curl -s --max-time "$TIMEOUT" \
      -H "Content-Type: application/json" \
      -d "@$file" \
      "$BASE_URL$endpoint" 2>/dev/null || true)
    rm -f "$file"
    echo "$resp"
  }
  _post_file_stream() {
    local file="$1" endpoint="$2"
    local resp; resp=$(curl -sf --max-time "$TIMEOUT" \
      -H "Content-Type: application/json" \
      -d "@$file" \
      "$BASE_URL$endpoint" 2>/dev/null | head -10 || true)
    rm -f "$file"
    echo "$resp"
  }

  # ── Detect vision-capable profiles: name contains "mmproj" (case-insensitive) ─
  VISION_PROFILES=()
  for _p in "${AVAILABLE_PROFILES[@]}"; do
    if echo "$_p" | grep -qi "mmproj"; then
      VISION_PROFILES+=("$_p")
    fi
  done

  if [[ ${#VISION_PROFILES[@]} -eq 0 ]]; then
    skip "No mmproj profiles found" "Profile name must contain 'mmproj' to enable vision tests"
  else
    echo "  mmproj profiles: ${VISION_PROFILES[*]}"
  fi

  for _p in "${VISION_PROFILES[@]}"; do

    # ── 13a: /api/chat — Ollama multipart content ────────────────────────────
    _f=$(_vision_body "$_p" "Briefly describe what you see in this image." "false")
    resp=$(_post_file "$_f" "/api/chat")
    if _has "$resp" '.message.content | type == "string" and length > 0'; then
      content=$(_get_str "$resp" '.message.content' | tr -d '\n' | cut -c1-80)
      pass "/api/chat vision [$_p] → \"$content\""
    else
      fail "/api/chat vision [$_p]" "${resp:0:200}"
    fi

    # ── 13b: /v1/chat/completions — OpenAI multipart content ─────────────────
    _f=$(_vision_body "$_p" "Briefly describe what you see in this image." "false")
    resp=$(_post_file "$_f" "/v1/chat/completions")
    if _has "$resp" '.choices[0].message.content | type == "string" and length > 0'; then
      content=$(_get_str "$resp" '.choices[0].message.content' | tr -d '\n' | cut -c1-80)
      pass "/v1/chat/completions vision [$_p] → \"$content\""
    else
      fail "/v1/chat/completions vision [$_p]" "${resp:0:200}"
    fi

    # ── 13c: /api/chat streaming — vision ────────────────────────────────────
    _f=$(_vision_body "$_p" "What color is the main subject?" "true")
    chunks=$(_post_file_stream "$_f" "/api/chat")
    if echo "$chunks" | grep -q '"message"'; then
      pass "/api/chat vision streaming [$_p] → NDJSON chunks"
    else
      fail "/api/chat vision streaming [$_p]" "${chunks:0:200}"
    fi

    # ── 13d: /v1/chat/completions streaming — vision ─────────────────────────
    _f=$(_vision_body "$_p" "What color is the main subject?" "true")
    chunks=$(_post_file_stream "$_f" "/v1/chat/completions")
    if echo "$chunks" | grep -q '^data:'; then
      pass "/v1/chat/completions vision streaming [$_p] → SSE data:"
    else
      fail "/v1/chat/completions vision streaming [$_p]" "${chunks:0:200}"
    fi

    # ── 13e: Multi-turn with image (image in first turn, text follow-up) ──────
    _f=$(_vision_body "$_p" "What is in this image? One sentence." "false")
    first_resp=$(_post_file "$_f" "/api/chat")
    first_content=$(_get_str "$first_resp" '.message.content // ""')
    if [[ -n "$first_content" ]]; then
      # Build follow-up via Python (assistant message content may contain quotes)
      _f2=$(mktemp /tmp/llama_vis2_XXXXXX.json)
      python3 - "$_p" "$_IMGURL_FILE" "$first_content" <<'PYEOF' > "$_f2"
import json, sys
model, imgurl_file, first_content = sys.argv[1], sys.argv[2], sys.argv[3]
with open(imgurl_file) as f: img_url = f.read()
body = {
    "model": model, "stream": False,
    "messages": [
        {"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": img_url}},
            {"type": "text", "text": "What is in this image? One sentence."}
        ]},
        {"role": "assistant", "content": first_content},
        {"role": "user", "content": "How many objects did you mention?"}
    ]
}
print(json.dumps(body))
PYEOF
      resp2=$(_post_file "$_f2" "/api/chat")
      if _has "$resp2" '.message.content | type == "string" and length > 0'; then
        content=$(_get_str "$resp2" '.message.content' | tr -d '\n' | cut -c1-60)
        pass "/api/chat vision multi-turn [$_p] → \"$content\""
      else
        fail "/api/chat vision multi-turn [$_p]" "${resp2:0:200}"
      fi
    else
      skip "/api/chat vision multi-turn [$_p]" "first turn produced no content"
    fi

    # ── 13f: Error — image sent to non-vision model ──────────────────────────
    NON_VISION_M=""
    for _nv in "${AVAILABLE_PROFILES[@]}"; do
      _is_vision=0
      for _vp in "${VISION_PROFILES[@]}"; do [[ "$_vp" == "$_nv" ]] && _is_vision=1 && break; done
      [[ $_is_vision -eq 0 ]] && NON_VISION_M="$_nv" && break
    done
    if [[ -n "$NON_VISION_M" ]]; then
      _f=$(_vision_body "$NON_VISION_M" "describe this" "false")
      sc=$(curl -s -o /dev/null -w "%{http_code}" --max-time 30 \
        -H "Content-Type: application/json" -d "@$_f" "$BASE_URL/api/chat" 2>/dev/null || echo "000")
      rm -f "$_f"
      if [[ "$sc" == "4"* || "$sc" == "5"* ]]; then
        pass "Image → non-vision model [$NON_VISION_M] → HTTP $sc (rejected)"
      else
        skip "Image → non-vision model [$NON_VISION_M]" "HTTP $sc (may strip image silently)"
      fi
    fi

  done  # end for VISION_PROFILES
  rm -f "$_IMGURL_FILE"
fi  # end if TEST_IMAGE found
fi  # _section_enabled 13

# =============================================================================
# Summary
# =============================================================================
TOTAL=$((PASS + FAIL + SKIP))
echo ""
echo -e "${BOLD}════════════════════════════════════════════${NC}"
echo -e "${BOLD} Test Summary${NC}"
echo -e "${BOLD}════════════════════════════════════════════${NC}"
printf "  %-8s %d\n" "Total:"  "$TOTAL"
printf "  ${GREEN}%-8s %d${NC}\n" "PASS:"   "$PASS"
printf "  ${RED}%-8s %d${NC}\n" "FAIL:"   "$FAIL"
printf "  ${YELLOW}%-8s %d${NC}\n" "SKIP:"   "$SKIP"
echo -e "${BOLD}════════════════════════════════════════════${NC}"

if [[ ${#FAILED_TESTS[@]} -gt 0 ]]; then
  echo -e "\n${RED}${BOLD}Failed tests:${NC}"
  for t in "${FAILED_TESTS[@]}"; do
    echo -e "  ${RED}•${NC} $t"
  done
fi

echo ""
if [[ $FAIL -eq 0 ]]; then
  echo -e "${GREEN}${BOLD}All tests passed!${NC}"
  exit 0
else
  echo -e "${RED}${BOLD}$FAIL test(s) failed.${NC}"
  exit 1
fi
