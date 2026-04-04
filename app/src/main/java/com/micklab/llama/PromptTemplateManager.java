package com.micklab.llama;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manages prompt template selection and application for the inference engine.
 * 
 * Template selection priority:
 * 1. Custom prompt template (Settings)
 * 2. Model family estimation (Qwen/LLaMA/Mistral/Gemma/Phi/Zephyr/Hermes)
 * 3. Fallback: ChatML generic template
 * 
 * System prompt priority:
 * 1. API-provided system (highest priority)
 * 2. Settings system prompt
 * 3. No system message
 */
public class PromptTemplateManager {
    private static final String TAG = "PromptTemplateManager";
    
    // Model family enum
    public enum ModelFamily {
        CHATML,    // Default/fallback
        GEMMA,
        LLAMA,
        MISTRAL,
        QWEN,
        PHI,
        ZEPHYR,
        HERMES
    }
    
    // Message class for chat format
    public static class Message {
        public String role;    // "system", "user", "assistant"
        public String content;
        
        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class TemplateSelectionResult {
        public final String template;
        public final String source;
        public final ModelFamily family;
        public final boolean hasSystem;
        public final String systemSource;
        public final String reason;

        public TemplateSelectionResult(
                String template,
                String source,
                ModelFamily family,
                boolean hasSystem,
                String systemSource) {
            this.template = template;
            this.source = source;
            this.family = family;
            this.hasSystem = hasSystem;
            this.systemSource = systemSource;
            StringBuilder sb = new StringBuilder();
            sb.append("source=").append(source);
            if (family != null) {
                sb.append(", family=").append(family.name());
            }
            sb.append(", system=").append(systemSource);
            sb.append(", hasSystem=").append(hasSystem);
            this.reason = sb.toString();
        }
    }

    public static class PromptBuildResult {
        public final String prompt;
        public final TemplateSelectionResult selection;
        public final String systemPrompt;

        public PromptBuildResult(String prompt, TemplateSelectionResult selection, String systemPrompt) {
            this.prompt = prompt;
            this.selection = selection;
            this.systemPrompt = systemPrompt;
        }
    }

    public static class SystemPromptResult {
        public final String prompt;
        public final String source;

        public SystemPromptResult(String prompt, String source) {
            this.prompt = prompt;
            this.source = source;
        }
    }
    
    // Template definitions
    private static final String CHATML_TEMPLATE = 
        "<|im_start|>system\n{SYSTEM}<|im_end|>\n" +
        "<|im_start|>user\n{USER}<|im_end|>\n" +
        "<|im_start|>assistant\n";
    
    private static final String CHATML_TEMPLATE_NO_SYSTEM = 
        "<|im_start|>user\n{USER}<|im_end|>\n" +
        "<|im_start|>assistant\n";
    
    private static final String GEMMA_TEMPLATE =
        "<start_of_turn>system\n{SYSTEM}\n<end_of_turn>\n" +
        "<start_of_turn>user\n{USER}\n<end_of_turn>\n" +
        "<start_of_turn>model\n";
    
    private static final String LLAMA_TEMPLATE = CHATML_TEMPLATE;
    
    private static final String LLAMA_TEMPLATE_NO_SYSTEM = CHATML_TEMPLATE_NO_SYSTEM;
    
    private static final String MISTRAL_TEMPLATE = 
        "[INST] {SYSTEM}\n\n{USER} [/INST]";
    
    private static final String MISTRAL_TEMPLATE_NO_SYSTEM = 
        "[INST] {USER} [/INST]";
    
    private static final String QWEN_TEMPLATE = 
        "<|im_start|>system\n{SYSTEM}<|im_end|>\n" +
        "<|im_start|>user\n{USER}<|im_end|>\n" +
        "<|im_start|>assistant\n";
    
    private static final String QWEN_TEMPLATE_NO_SYSTEM = 
        "<|im_start|>user\n{USER}<|im_end|>\n" +
        "<|im_start|>assistant\n";
    
    private static final String PHI_TEMPLATE = 
        "<|system|>\n{SYSTEM}\n<|end|>\n" +
        "<|user|>\n{USER}\n<|end|>\n" +
        "<|assistant|>\n";
    
    private static final String PHI_TEMPLATE_NO_SYSTEM = 
        "<|user|>\n{USER}\n<|end|>\n" +
        "<|assistant|>\n";
    
    private static final String ZEPHYR_TEMPLATE = 
        "<|system|>\n{SYSTEM}</s>\n" +
        "<|user|>\n{USER}</s>\n" +
        "<|assistant|>\n";
    
    private static final String ZEPHYR_TEMPLATE_NO_SYSTEM = 
        "<|user|>\n{USER}</s>\n" +
        "<|assistant|>\n";
    
    private static final String HERMES_TEMPLATE = 
        "<|im_start|>system\n{SYSTEM}<|im_end|>\n" +
        "<|im_start|>user\n{USER}<|im_end|>\n" +
        "<|im_start|>assistant\n";
    
    private static final String HERMES_TEMPLATE_NO_SYSTEM = 
        "<|im_start|>user\n{USER}<|im_end|>\n" +
        "<|im_start|>assistant\n";

    private static final String NO_THINKING_BLOCK = "<think>\n\n</think>\n\n";
    
    /**
     * Detect model family from model filename or path.
     */
    public static ModelFamily detectModelFamily(String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) {
            return ModelFamily.CHATML;
        }
        
        String lowerPath = modelPath.toLowerCase(Locale.US);
        
        if (lowerPath.contains("gemma")) {
            return ModelFamily.GEMMA;
        } else if (lowerPath.contains("qwen")) {
            return ModelFamily.QWEN;
        } else if (lowerPath.contains("mistral")) {
            return ModelFamily.MISTRAL;
        } else if (lowerPath.contains("llama")) {
            return ModelFamily.LLAMA;
        } else if (lowerPath.contains("phi")) {
            return ModelFamily.PHI;
        } else if (lowerPath.contains("zephyr")) {
            return ModelFamily.ZEPHYR;
        } else if (lowerPath.contains("hermes")) {
            return ModelFamily.HERMES;
        }
        
        return ModelFamily.CHATML;
    }
    
    /**
     * Get the template for a model family.
     */
    public static String getTemplateForFamily(ModelFamily family, boolean hasSystem) {
        switch (family) {
            case GEMMA:
                return GEMMA_TEMPLATE;
            case LLAMA:
                return hasSystem ? LLAMA_TEMPLATE : LLAMA_TEMPLATE_NO_SYSTEM;
            case MISTRAL:
                return hasSystem ? MISTRAL_TEMPLATE : MISTRAL_TEMPLATE_NO_SYSTEM;
            case QWEN:
                return hasSystem ? QWEN_TEMPLATE : QWEN_TEMPLATE_NO_SYSTEM;
            case PHI:
                return hasSystem ? PHI_TEMPLATE : PHI_TEMPLATE_NO_SYSTEM;
            case ZEPHYR:
                return hasSystem ? ZEPHYR_TEMPLATE : ZEPHYR_TEMPLATE_NO_SYSTEM;
            case HERMES:
                return hasSystem ? HERMES_TEMPLATE : HERMES_TEMPLATE_NO_SYSTEM;
            case CHATML:
            default:
                return hasSystem ? CHATML_TEMPLATE : CHATML_TEMPLATE_NO_SYSTEM;
        }
    }

    public static TemplateSelectionResult selectTemplateWithReason(
            String customTemplate,
            String ggufChatTemplate,
            String modelPath,
            boolean hasSystem,
            String systemSource) {

        if (customTemplate != null && !customTemplate.isEmpty()) {
            return new TemplateSelectionResult(customTemplate, "custom", null, hasSystem, systemSource);
        }

        ModelFamily family = detectModelFamily(modelPath);
        String template = getTemplateForFamily(family, hasSystem);
        return new TemplateSelectionResult(template, "model-family", family, hasSystem, systemSource);
    }
    
    /**
     * Select the appropriate template based on priority.
     * 
     * Priority:
     * 1. Custom template from settings
     * 2. Model family template
     * 3. ChatML fallback
     */
    public static String selectTemplate(
            String customTemplate,
            String ggufChatTemplate,
            String modelPath,
            boolean hasSystem) {
        TemplateSelectionResult selection = selectTemplateWithReason(
                customTemplate,
                ggufChatTemplate,
                modelPath,
                hasSystem,
                hasSystem ? "provided" : "none");
        return selection.template;
    }
    
    /**
     * Resolve the system prompt based on priority.
     * 
     * Priority:
     * 1. API-provided system (highest)
     * 2. Settings system prompt
     * 3. Empty (no system)
     */
    public static String resolveSystemPrompt(String apiSystem, String settingsSystem) {
        // 1. API-provided system (highest priority)
        if (apiSystem != null && !apiSystem.isEmpty()) {
            return apiSystem;
        }
        
        // 2. Settings system prompt
        if (settingsSystem != null && !settingsSystem.isEmpty()) {
            return settingsSystem;
        }
        
        // 3. No system
        return null;
    }

    public static SystemPromptResult resolveSystemPromptWithSource(String apiSystem, String settingsSystem) {
        if (apiSystem != null && !apiSystem.isEmpty()) {
            return new SystemPromptResult(apiSystem, "api");
        }

        if (settingsSystem != null && !settingsSystem.isEmpty()) {
            return new SystemPromptResult(settingsSystem, "settings");
        }

        return new SystemPromptResult(null, "none");
    }
    
    /**
     * Apply template to system and user content.
     * Handles both {SYSTEM}/{USER} placeholders and Jinja-style templates.
     */
    public static String applyTemplate(String template, String system, String user) {
        if (template == null || template.isEmpty()) {
            // Fallback to simple format
            if (system != null && !system.isEmpty()) {
                return system + "\n\n" + user;
            }
            return user;
        }
        
        String result = template;
        
        // Handle {SYSTEM} and {USER} placeholders
        if (system != null && !system.isEmpty()) {
            result = result.replace("{SYSTEM}", system);
        } else {
            // Remove system-related markers if no system prompt
            result = result.replace("{SYSTEM}\n\n", "");
            result = result.replace("{SYSTEM}\n", "");
            result = result.replace("{SYSTEM}", "");
        }
        
        result = result.replace("{USER}", user != null ? user : "");
        result = result.replace("{USER_INPUT}", user != null ? user : "");
        
        return result;
    }

    private static String applyThinkingToggle(
            String prompt,
            TemplateSelectionResult selection,
            boolean enableThinking) {
        if (enableThinking || prompt == null || prompt.isEmpty() || selection == null) {
            return prompt;
        }
        if ("custom".equals(selection.source) || selection.family != ModelFamily.QWEN) {
            return prompt;
        }
        if (prompt.endsWith("<|im_start|>assistant\n")) {
            return prompt + NO_THINKING_BLOCK;
        }
        return prompt;
    }
    
    /**
     * Build prompt from messages array (for /api/chat).
     * Applies system prompt priority and role normalization.
     */
    public static String buildPromptFromMessages(
            JSONArray messages,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath) throws JSONException {
        return buildPromptFromMessages(
                messages,
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath,
                true);
    }

    public static String buildPromptFromMessages(
            JSONArray messages,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath,
            boolean enableThinking) throws JSONException {
        return buildPromptFromMessagesWithSelection(
                messages,
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath,
                enableThinking).prompt;
    }

    public static PromptBuildResult buildPromptFromMessagesWithSelection(
            JSONArray messages,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath) throws JSONException {
        return buildPromptFromMessagesWithSelection(
                messages,
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath,
                true);
    }

    public static PromptBuildResult buildPromptFromMessagesWithSelection(
            JSONArray messages,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath,
            boolean enableThinking) throws JSONException {
        
        if (messages == null || messages.length() == 0) {
            SystemPromptResult systemResult = resolveSystemPromptWithSource(null, settingsSystemPrompt);
            TemplateSelectionResult selection = selectTemplateWithReason(
                    customTemplate,
                    ggufChatTemplate,
                    modelPath,
                    systemResult.prompt != null && !systemResult.prompt.isEmpty(),
                    systemResult.source);
            return new PromptBuildResult("", selection, systemResult.prompt);
        }
        
        // Extract messages and find API system prompt
        String apiSystemPrompt = null;
        List<Message> conversationHistory = new ArrayList<>();
        
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            String role = msg.optString("role", "");
            String content = msg.optString("content", "");
            
            // Strip existing template markers to prevent double-templating
            content = stripTemplateMarkers(content);
            
            if ("system".equals(role)) {
                apiSystemPrompt = content;
            } else {
                conversationHistory.add(new Message(role, content));
            }
        }
        
        SystemPromptResult systemResult = resolveSystemPromptWithSource(apiSystemPrompt, settingsSystemPrompt);
        boolean hasSystem = systemResult.prompt != null && !systemResult.prompt.isEmpty();
        
        TemplateSelectionResult selection = selectTemplateWithReason(
                customTemplate,
                ggufChatTemplate,
                modelPath,
                hasSystem,
                systemResult.source);
        
        String prompt;
        if ("custom".equals(selection.source)) {
            String conversationText = buildConversationText(conversationHistory);
            prompt = applyTemplate(selection.template, systemResult.prompt, conversationText);
        } else {
            // Build prompt using model-family-specific multi-turn formatting
            ModelFamily family = detectModelFamily(modelPath);
            prompt = buildMultiTurnPrompt(family, systemResult.prompt, conversationHistory);
        }
        prompt = applyThinkingToggle(prompt, selection, enableThinking);
        return new PromptBuildResult(prompt, selection, systemResult.prompt);
    }
    
    /**
     * Build prompt for /api/generate format.
     * Takes prompt string with optional system from API.
     */
    public static String buildPromptForGenerate(
            String prompt,
            String apiSystem,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath) {
        return buildPromptForGenerate(
                prompt,
                apiSystem,
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath,
                true);
    }

    public static String buildPromptForGenerate(
            String prompt,
            String apiSystem,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath,
            boolean enableThinking) {
        return buildPromptForGenerateWithSelection(
                prompt,
                apiSystem,
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath,
                enableThinking).prompt;
    }

    public static PromptBuildResult buildPromptForGenerateWithSelection(
            String prompt,
            String apiSystem,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath) {
        return buildPromptForGenerateWithSelection(
                prompt,
                apiSystem,
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath,
                true);
    }

    public static PromptBuildResult buildPromptForGenerateWithSelection(
            String prompt,
            String apiSystem,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath,
            boolean enableThinking) {

        String cleanPrompt = stripTemplateMarkers(prompt);

        SystemPromptResult systemResult = resolveSystemPromptWithSource(apiSystem, settingsSystemPrompt);
        boolean hasSystem = systemResult.prompt != null && !systemResult.prompt.isEmpty();

        TemplateSelectionResult selection = selectTemplateWithReason(
                customTemplate,
                ggufChatTemplate,
                modelPath,
                hasSystem,
                systemResult.source);

        String finalPrompt = applyTemplate(selection.template, systemResult.prompt, cleanPrompt);
        finalPrompt = applyThinkingToggle(finalPrompt, selection, enableThinking);
        return new PromptBuildResult(finalPrompt, selection, systemResult.prompt);
    }
    
    /**
     * Build prompt for direct input (app's prompt input field).
     * Only uses Settings system prompt (no API system).
     */
    public static String buildPromptForDirectInput(
            String userInput,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath) {
        return buildPromptForDirectInput(
                userInput,
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath,
                true);
    }

    public static String buildPromptForDirectInput(
            String userInput,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath,
            boolean enableThinking) {
        return buildPromptForDirectInputWithSelection(
                userInput,
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath,
                enableThinking).prompt;
    }

    public static PromptBuildResult buildPromptForDirectInputWithSelection(
            String userInput,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath) {
        return buildPromptForDirectInputWithSelection(
                userInput,
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath,
                true);
    }

    public static PromptBuildResult buildPromptForDirectInputWithSelection(
            String userInput,
            String customTemplate,
            String ggufChatTemplate,
            String settingsSystemPrompt,
            String modelPath,
            boolean enableThinking) {

        return buildPromptForGenerateWithSelection(
                userInput,
                null,
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath,
                enableThinking);
    }

    private static String buildConversationText(List<Message> history) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            if ("assistant".equals(msg.role)) {
                sb.append("assistant: ");
            } else if ("user".equals(msg.role)) {
                sb.append("user: ");
            } else {
                sb.append(msg.role).append(": ");
            }
            sb.append(msg.content != null ? msg.content : "");
        }
        return sb.toString();
    }
    
    /**
     * Build a multi-turn prompt formatted according to the model family's chat template.
     * Each message in the conversation history is wrapped with the appropriate role markers.
     */
    public static String buildMultiTurnPrompt(ModelFamily family, String systemPrompt, List<Message> history) {
        switch (family) {
            case GEMMA:
                return buildMultiTurnGemma(systemPrompt, history);
            case LLAMA:
                return buildMultiTurnChatML(systemPrompt, history);
            case MISTRAL:
                return buildMultiTurnMistral(systemPrompt, history);
            case PHI:
                return buildMultiTurnPhi(systemPrompt, history);
            case ZEPHYR:
                return buildMultiTurnZephyr(systemPrompt, history);
            case QWEN:
            case HERMES:
            case CHATML:
            default:
                return buildMultiTurnChatML(systemPrompt, history);
        }
    }

    // ChatML / Qwen / Hermes multi-turn
    private static String buildMultiTurnChatML(String system, List<Message> history) {
        StringBuilder sb = new StringBuilder();
        if (system != null && !system.isEmpty()) {
            sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n");
        }
        for (Message msg : history) {
            sb.append("<|im_start|>").append(msg.role).append("\n")
              .append(msg.content).append("<|im_end|>\n");
        }
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    // Gemma family multi-turn. Emit the leading system turn even when empty so the
    // prompt shape stays compatible with the required system/user/model protocol.
    private static String buildMultiTurnGemma(String system, List<Message> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("<start_of_turn>system\n")
                .append(system != null ? system : "")
                .append("\n<end_of_turn>\n");
        for (Message msg : history) {
            if ("user".equals(msg.role)) {
                sb.append("<start_of_turn>user\n")
                  .append(msg.content).append("\n<end_of_turn>\n");
            } else if ("assistant".equals(msg.role)) {
                sb.append("<start_of_turn>model\n")
                  .append(msg.content).append("\n<end_of_turn>\n");
            }
        }
        sb.append("<start_of_turn>model\n");
        return sb.toString();
    }

    // LLaMA multi-turn (system in first [INST] only)
    private static String buildMultiTurnLlama(String system, List<Message> history) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Message msg : history) {
            if ("user".equals(msg.role)) {
                sb.append("[INST] ");
                if (first && system != null && !system.isEmpty()) {
                    sb.append("<<SYS>>\n").append(system).append("\n<</SYS>>\n\n");
                }
                sb.append(msg.content).append(" [/INST]");
                first = false;
            } else if ("assistant".equals(msg.role)) {
                sb.append(" ").append(msg.content).append(" ");
            }
        }
        return sb.toString();
    }

    // Mistral multi-turn (system merged into first [INST])
    private static String buildMultiTurnMistral(String system, List<Message> history) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Message msg : history) {
            if ("user".equals(msg.role)) {
                sb.append("[INST] ");
                if (first && system != null && !system.isEmpty()) {
                    sb.append(system).append("\n\n");
                }
                sb.append(msg.content).append(" [/INST]");
                first = false;
            } else if ("assistant".equals(msg.role)) {
                sb.append(" ").append(msg.content).append(" ");
            }
        }
        return sb.toString();
    }

    // Phi multi-turn
    private static String buildMultiTurnPhi(String system, List<Message> history) {
        StringBuilder sb = new StringBuilder();
        if (system != null && !system.isEmpty()) {
            sb.append("<|system|>\n").append(system).append("\n<|end|>\n");
        }
        for (Message msg : history) {
            if ("user".equals(msg.role)) {
                sb.append("<|user|>\n").append(msg.content).append("\n<|end|>\n");
            } else if ("assistant".equals(msg.role)) {
                sb.append("<|assistant|>\n").append(msg.content).append("\n<|end|>\n");
            }
        }
        sb.append("<|assistant|>\n");
        return sb.toString();
    }

    // Zephyr multi-turn
    private static String buildMultiTurnZephyr(String system, List<Message> history) {
        StringBuilder sb = new StringBuilder();
        if (system != null && !system.isEmpty()) {
            sb.append("<|system|>\n").append(system).append("</s>\n");
        }
        for (Message msg : history) {
            if ("user".equals(msg.role)) {
                sb.append("<|user|>\n").append(msg.content).append("</s>\n");
            } else if ("assistant".equals(msg.role)) {
                sb.append("<|assistant|>\n").append(msg.content).append("</s>\n");
            }
        }
        sb.append("<|assistant|>\n");
        return sb.toString();
    }
    
    /**
     * Strip common prompt template markers from content to prevent double-templating.
     */
    public static String stripTemplateMarkers(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        
        String result = content;
        
        // Common template markers to strip
        String[] markers = {
            "<start_of_turn>system", "<end_of_turn>", "<start_of_turn>user", 
            "<start_of_turn>model", "<start_of_turn>assistant",
            "<|system|>", "<|user|>", "<|assistant|>", "<|model|>",
            "<|im_start|>system", "<|im_start|>user", "<|im_start|>assistant", "<|im_end|>",
            "[INST]", "[/INST]", "<<SYS>>", "<</SYS>>",
            "<|end|>", "</s>"
        };
        
        for (String marker : markers) {
            result = result.replace(marker, "");
        }
        
        // Clean up extra whitespace/newlines left behind
        result = result.replaceAll("\\n{3,}", "\n\n").trim();
        
        return result;
    }
    
    /**
     * Normalize role for specific model families.
     * Some models require different role representations.
     */
    public static String normalizeRole(String role, ModelFamily family) {
        if (role == null) return "user";
        
        switch (family) {
            case GEMMA:
                // Gemma: system → user (merged)
                if ("system".equals(role)) {
                    return "user";
                }
                break;
            case HERMES:
                // Hermes: uses standard roles
                break;
            case LLAMA:
            case MISTRAL:
                // LLaMA/Mistral: roles embedded in INST blocks
                break;
            default:
                // ChatML and others: use standard roles
                break;
        }
        
        return role;
    }
}
