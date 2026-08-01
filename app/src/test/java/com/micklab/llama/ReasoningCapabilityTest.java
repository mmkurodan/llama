package com.micklab.llama;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for model reasoning-capability detection and chat_template_kwargs building.
 *
 * Run with: ./gradlew :app:testDebugUnitTest --tests "*ReasoningCapabilityTest"
 */
public class ReasoningCapabilityTest {

    // ── detectReasoningCapability ────────────────────────────────────────────

    @Test public void gemmaThinking_byThinkKeyword() {
        assertEquals(PromptTemplateManager.ReasoningCapability.GEMMA_THINKING,
                PromptTemplateManager.detectReasoningCapability("gemma-3-1b-thinking-v2-q4_k_m.gguf"));
    }
    @Test public void gemmaThinking_byReasonKeyword() {
        assertEquals(PromptTemplateManager.ReasoningCapability.GEMMA_THINKING,
                PromptTemplateManager.detectReasoningCapability("gemma-3-4b-reasoning-q8_0.gguf"));
    }
    @Test public void gemmaPlain_isNone() {
        assertEquals(PromptTemplateManager.ReasoningCapability.NONE,
                PromptTemplateManager.detectReasoningCapability("gemma-3-4b-it-Q4_K_M.gguf"));
    }

    @Test public void deepSeekR1_isChatmlReasoning() {
        assertEquals(PromptTemplateManager.ReasoningCapability.CHATML_REASONING,
                PromptTemplateManager.detectReasoningCapability("deepseek-r1-distill-qwen-7b-q4_k_m.gguf"));
    }
    @Test public void deepSeekR2_isChatmlReasoning() {
        assertEquals(PromptTemplateManager.ReasoningCapability.CHATML_REASONING,
                PromptTemplateManager.detectReasoningCapability("deepseek-r2-lite-q8_0.gguf"));
    }
    @Test public void deepSeekPlain_isNone() {
        assertEquals(PromptTemplateManager.ReasoningCapability.NONE,
                PromptTemplateManager.detectReasoningCapability("deepseek-v2-lite-chat-q4_k_m.gguf"));
    }

    @Test public void qwq_isChatmlReasoning() {
        assertEquals(PromptTemplateManager.ReasoningCapability.CHATML_REASONING,
                PromptTemplateManager.detectReasoningCapability("QwQ-32B-q4_k_m.gguf"));
    }
    @Test public void qwenThinking_isChatmlReasoning() {
        assertEquals(PromptTemplateManager.ReasoningCapability.CHATML_REASONING,
                PromptTemplateManager.detectReasoningCapability("Qwen3-14B-thinking-q8_0.gguf"));
    }
    @Test public void qwenPlain_isNone() {
        assertEquals(PromptTemplateManager.ReasoningCapability.NONE,
                PromptTemplateManager.detectReasoningCapability("Qwen2.5-7B-Instruct-q4_k_m.gguf"));
    }

    @Test public void llamaReasoning_isChatmlReasoning() {
        assertEquals(PromptTemplateManager.ReasoningCapability.CHATML_REASONING,
                PromptTemplateManager.detectReasoningCapability("llama-3.1-r-q4_k_m.gguf"));
    }
    @Test public void llamaThinking_isChatmlReasoning() {
        assertEquals(PromptTemplateManager.ReasoningCapability.CHATML_REASONING,
                PromptTemplateManager.detectReasoningCapability("llama-3-8b-thinking-q4_k_m.gguf"));
    }
    @Test public void llamaPlain_isNone() {
        assertEquals(PromptTemplateManager.ReasoningCapability.NONE,
                PromptTemplateManager.detectReasoningCapability("llama-3.1-8b-instruct-q4_k_m.gguf"));
    }

    @Test public void phiReasoning_isPhiReasoning() {
        assertEquals(PromptTemplateManager.ReasoningCapability.PHI_REASONING,
                PromptTemplateManager.detectReasoningCapability("phi-4-reasoning-plus-q4_k_m.gguf"));
    }
    @Test public void phiMiniReasoning_isPhiReasoning() {
        assertEquals(PromptTemplateManager.ReasoningCapability.PHI_REASONING,
                PromptTemplateManager.detectReasoningCapability("phi-4-mini-reasoning-q8_0.gguf"));
    }
    @Test public void phiPlain_isNone() {
        assertEquals(PromptTemplateManager.ReasoningCapability.NONE,
                PromptTemplateManager.detectReasoningCapability("phi-3.5-mini-instruct-q4_k_m.gguf"));
    }

    @Test public void mistralReasoning_isMistralReasoning() {
        assertEquals(PromptTemplateManager.ReasoningCapability.MISTRAL_REASONING,
                PromptTemplateManager.detectReasoningCapability("mistral-large-reasoning-q4_k_m.gguf"));
    }
    @Test public void mistralPlain_isNone() {
        assertEquals(PromptTemplateManager.ReasoningCapability.NONE,
                PromptTemplateManager.detectReasoningCapability("mistral-7b-instruct-v0.3-q4_k_m.gguf"));
    }

    @Test public void nullPath_isNone() {
        assertEquals(PromptTemplateManager.ReasoningCapability.NONE,
                PromptTemplateManager.detectReasoningCapability(null));
    }
    @Test public void emptyPath_isNone() {
        assertEquals(PromptTemplateManager.ReasoningCapability.NONE,
                PromptTemplateManager.detectReasoningCapability(""));
    }

    // ── getThinkingKwargsKey ─────────────────────────────────────────────────

    @Test public void gemmaKey_isEnableThinking() {
        assertEquals("enable_thinking",
                PromptTemplateManager.getThinkingKwargsKey(
                        PromptTemplateManager.ReasoningCapability.GEMMA_THINKING));
    }
    @Test public void chatMLKey_isEnableReasoning() {
        assertEquals("enable_reasoning",
                PromptTemplateManager.getThinkingKwargsKey(
                        PromptTemplateManager.ReasoningCapability.CHATML_REASONING));
    }
    @Test public void phiKey_isReasoning() {
        assertEquals("reasoning",
                PromptTemplateManager.getThinkingKwargsKey(
                        PromptTemplateManager.ReasoningCapability.PHI_REASONING));
    }
    @Test public void mistralKey_isEnableReasoning() {
        assertEquals("enable_reasoning",
                PromptTemplateManager.getThinkingKwargsKey(
                        PromptTemplateManager.ReasoningCapability.MISTRAL_REASONING));
    }
    @Test public void noneKey_isNull() {
        assertNull(PromptTemplateManager.getThinkingKwargsKey(
                PromptTemplateManager.ReasoningCapability.NONE));
    }

    // ── buildThinkingKwargs ──────────────────────────────────────────────────

    @Test public void gemmaKwargs_thinkingOn() throws Exception {
        org.json.JSONObject kwargs = PromptTemplateManager.buildThinkingKwargs(
                PromptTemplateManager.ReasoningCapability.GEMMA_THINKING, true);
        assertTrue(kwargs.getBoolean("enable_thinking"));
        assertEquals(1, kwargs.length());
    }
    @Test public void gemmaKwargs_thinkingOff() throws Exception {
        org.json.JSONObject kwargs = PromptTemplateManager.buildThinkingKwargs(
                PromptTemplateManager.ReasoningCapability.GEMMA_THINKING, false);
        assertFalse(kwargs.getBoolean("enable_thinking"));
    }

    @Test public void chatMLKwargs_thinkingOn() throws Exception {
        org.json.JSONObject kwargs = PromptTemplateManager.buildThinkingKwargs(
                PromptTemplateManager.ReasoningCapability.CHATML_REASONING, true);
        assertTrue(kwargs.getBoolean("enable_reasoning"));
        assertEquals(1, kwargs.length());
    }
    @Test public void chatMLKwargs_thinkingOff() throws Exception {
        org.json.JSONObject kwargs = PromptTemplateManager.buildThinkingKwargs(
                PromptTemplateManager.ReasoningCapability.CHATML_REASONING, false);
        assertFalse(kwargs.getBoolean("enable_reasoning"));
    }

    @Test public void phiKwargs_thinkingOn() throws Exception {
        org.json.JSONObject kwargs = PromptTemplateManager.buildThinkingKwargs(
                PromptTemplateManager.ReasoningCapability.PHI_REASONING, true);
        assertEquals("on", kwargs.getString("reasoning"));
        assertEquals(1, kwargs.length());
    }
    @Test public void phiKwargs_thinkingOff() throws Exception {
        org.json.JSONObject kwargs = PromptTemplateManager.buildThinkingKwargs(
                PromptTemplateManager.ReasoningCapability.PHI_REASONING, false);
        assertEquals("off", kwargs.getString("reasoning"));
    }

    @Test public void noneKwargs_isEmpty() throws Exception {
        org.json.JSONObject kwargs = PromptTemplateManager.buildThinkingKwargs(
                PromptTemplateManager.ReasoningCapability.NONE, true);
        assertEquals(0, kwargs.length());
    }
}
