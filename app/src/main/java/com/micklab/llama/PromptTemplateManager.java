package com.micklab.llama;

import android.util.Log;

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
 * 2. GGUF metadata chat_template
 * 3. Model family estimation (Qwen/LLaMA/Mistral/Gemma/Phi/Zephyr/Hermes)
 * 4. Fallback: ChatML generic template
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
    
    // Template definitions
    private static final String CHATML_TEMPLATE = 
        "<|im_start|>system\n{SYSTEM}<|im_end|>\n" +
        "<|im_start|>user\n{USER}<|im_end|>\n" +
        "<|im_start|>assistant\n";
    
    private static final String CHATML_TEMPLATE_NO_SYSTEM = 
        "<|im_start|>user\n{USER}<|im_end|>\n" +
        "<|im_start|>assistant\n";
    
    private static final String GEMMA_TEMPLATE = 
        "<start_of_turn>user\n{SYSTEM}\n\n{USER}<end_of_turn>\n" +
        "<start_of_turn>model\n";
    
    private static final String GEMMA_TEMPLATE_NO_SYSTEM = 
        "<start_of_turn>user\n{USER}<end_of_turn>\n" +
        "<start_of_turn>model\n";
    
    private static final String LLAMA_TEMPLATE = 
        "[INST] <<SYS>>\n{SYSTEM}\n<</SYS>>\n\n{USER} [/INST]";
    
    private static final String LLAMA_TEMPLATE_NO_SYSTEM = 
        "[INST] {USER} [/INST]";
    
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
                return hasSystem ? GEMMA_TEMPLATE : GEMMA_TEMPLATE_NO_SYSTEM;
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
    
    /**
     * Select the appropriate template based on priority.
     * 
     * Priority:
     * 1. Custom template from settings
     * 2. GGUF chat_template
     * 3. Model family template
     * 4. ChatML fallback
     */
    public static String selectTemplate(
            String customTemplate,
            String ggufChatTemplate,
            String modelPath,
            boolean hasSystem) {
        
        // 1. Custom template (highest priority)
        if (customTemplate != null && !customTemplate.isEmpty()) {
            Log.d(TAG, "Using custom template from settings");
            return customTemplate;
        }
        
        // 2. GGUF chat_template
        if (ggufChatTemplate != null && !ggufChatTemplate.isEmpty()) {
            Log.d(TAG, "Using GGUF chat_template from model metadata");
            return ggufChatTemplate;
        }
        
        // 3. Model family detection
        ModelFamily family = detectModelFamily(modelPath);
        Log.d(TAG, "Detected model family: " + family.name());
        
        return getTemplateForFamily(family, hasSystem);
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
        
        if (messages == null || messages.length() == 0) {
            return "";
        }
        
        // Extract messages and find API system prompt
        String apiSystemPrompt = null;
        StringBuilder userContent = new StringBuilder();
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
        
        // Resolve final system prompt using priority
        String finalSystem = resolveSystemPrompt(apiSystemPrompt, settingsSystemPrompt);
        boolean hasSystem = finalSystem != null && !finalSystem.isEmpty();
        
        // Select template
        String template = selectTemplate(customTemplate, ggufChatTemplate, modelPath, hasSystem);
        
        // Build user content from conversation history
        for (int i = 0; i < conversationHistory.size(); i++) {
            Message msg = conversationHistory.get(i);
            if ("user".equals(msg.role)) {
                if (userContent.length() > 0) {
                    userContent.append("\n");
                }
                userContent.append(msg.content);
            } else if ("assistant".equals(msg.role)) {
                // Include previous assistant responses in context
                if (userContent.length() > 0 && i < conversationHistory.size() - 1) {
                    userContent.append("\nAssistant: ").append(msg.content).append("\nUser: ");
                }
            }
        }
        
        // Apply template
        return applyTemplate(template, finalSystem, userContent.toString());
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
        
        // Strip template markers from prompt
        String cleanPrompt = stripTemplateMarkers(prompt);
        
        // Resolve system prompt
        String finalSystem = resolveSystemPrompt(apiSystem, settingsSystemPrompt);
        boolean hasSystem = finalSystem != null && !finalSystem.isEmpty();
        
        // Select template
        String template = selectTemplate(customTemplate, ggufChatTemplate, modelPath, hasSystem);
        
        // Apply template
        return applyTemplate(template, finalSystem, cleanPrompt);
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
        
        // For direct input, API system is always null
        return buildPromptForGenerate(
                userInput,
                null,  // no API system
                customTemplate,
                ggufChatTemplate,
                settingsSystemPrompt,
                modelPath);
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
