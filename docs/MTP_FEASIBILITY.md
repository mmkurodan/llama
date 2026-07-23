# MTP (Multi-Token Prediction) — feasibility notes

Status: **upstream-complete, app integration deferred.** Investigated 2026-07-23
during the b9351 → b10091 llama.cpp update.

## What exists after the update

The vendored llama.cpp (b10091) already implements MTP speculative decoding, and
`common/` is compiled into the app's `.so`, so the machinery is *linkable* today:

- `COMMON_SPECULATIVE_TYPE_DRAFT_MTP` and the `--spec-type draft-mtp` CLI/server flag.
- `common/speculative.{h,cpp}`: `common_speculative_init(_from_params)`, `_begin`,
  `_draft`, `_process`, `common_sampler_sample_and_accept_n`, `_accept`.
- Model side: `blk.%d.nextn.*` tensors (`LLM_TENSOR_NEXTN_*`), `nextn_predict_layers`,
  and the `mtp_on_hybrid_qwen35` path in `src/llama-model.cpp`.

Compatible models need an MTP head from pretraining (e.g. Qwen3.5+, Gemma 4 26B-A4B,
DeepSeek V3/R1). The MTP head ships as a **separate sibling GGUF** (see
`common/download.cpp: find_best_mtp`, keyword `mtp`).

## Why it is NOT wired into this app yet

`app/src/main/cpp/jni/jni_llama.cpp` runs its own single-sequence autoregressive
loop (`common_sampler_sample` → `common_sampler_accept` → `llama_decode`, in
`generate` and `generate_openai_chat_completion_locked`). It does not use
`common_speculative`. Retrofitting MTP is **server-grade**, not the ~100-line
`examples/speculative-simple` pattern (that example is for a *separate draft model*
and explicitly defers MTP to the server: "TODO: extend to support MTP … See server
code for reference").

A correct MTP integration (mirroring `tools/server/server-context.cpp`) requires:

1. Load the MTP sibling GGUF as a draft model + create a draft context
   (`common_speculative_init_from_params(params_dft, model_tgt, ctx_tgt)` then
   `common_speculative_init(params.speculative, n_seq)`).
2. Enable target hidden-state / nextn-embedding extraction
   (`llama_set_embeddings_nextn`, `llama_get_embeddings_layer_inp`,
   `llama_get_embeddings_nextn`).
3. Rework the decode loop: draft → build `[id_last, draft…]` batch → `llama_decode`
   the target → `common_speculative_process(spec, batch)` inside/after decode →
   `common_sampler_sample_and_accept_n` → **KV rollback / checkpoint restore** on
   partial acceptance (`common_prompt_checkpoint`, `llama_memory_seq_rm`) →
   `common_speculative_accept`. Feed each accepted token through the existing
   streaming / stop-sequence / UTF-8 / cancellation logic.
4. Java: a `setSpeculative(String mtpModelPath, int nDraft, boolean enabled)` native
   method + settings plumbing; default OFF so non-MTP behaviour is unchanged.

## Why deferred

- **No local verification in this environment**: native cannot be compiled (NDK
  clang SIGILLs in the sandbox) or run, and there is no device with an MTP-head
  model — so hundreds of lines of stateful speculative code could only be checked
  by CI build + on-device trial, with a high chance of subtle correctness bugs.
- **Limited on-device payoff**: MTP-head models are large (Qwen3.5+/Gemma4-26B/
  DeepSeek); the reported 1.4–2× speedups are GPU-class. On a phone the practical
  benefit is small.

## To pick this up later

Follow `tools/server/server-context.cpp` (search `common_speculative`), keep the
speculative path behind the `enabled` flag, and add a device functional test:
load an MTP-head GGUF + its `*mtp*` sibling, verify (a) spec-off output ==
current output, (b) spec-on output equivalent + faster.
