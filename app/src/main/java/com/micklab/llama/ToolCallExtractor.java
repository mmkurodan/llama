package com.micklab.llama;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts tool calls from model responses, including:
 * - Direct tool_calls field from structured responses
 * - Tool call patterns from reasoning/thinking logs
 * 
 * Supports patterns like:
 * <|channel>thought ... <|tool_call>call:web_search{...}<|tool_call|>
 * 
 * Handles Gemma-4 escape sequences:
 * <|tool_call>call:get_time{format:<|"|>readable<|"|>}<|tool_call|>
 */
class ToolCallExtractor {
    private static final String TAG = "ToolCallExtractor";
    private static final boolean DEBUG = true; // Enable for debugging Gemma param parsing
    
    /**
     * Pattern for extracting tool calls from thinking logs:
     * <|tool_call>call:TOOL_NAME{...}<|tool_call|>
     * 
     * This pattern is lenient to handle various closing braces and escapes.
     */
    private static final Pattern TOOL_CALL_PATTERN = 
        Pattern.compile("<\\|tool_call>call:([a-zA-Z_][a-zA-Z0-9_]*)\\{([^}]*)\\}<\\|tool_call\\|>");
    
    private ToolCallExtractor() {
    }
    
    /**
     * Extract tool calls from model response content, including thinking logs.
     * Returns JSONArray with tool call objects, or null if none found.
     */
    static JSONArray extractToolCalls(String content) throws JSONException {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        List<JSONObject> toolCalls = new ArrayList<>();
        
        // Try to extract from thinking log patterns
        Matcher matcher = TOOL_CALL_PATTERN.matcher(content);
        while (matcher.find()) {
            String toolName = matcher.group(1);
            String params = matcher.group(2).trim();
            
            if (DEBUG) {
                Log.d(TAG, "Extracted tool call: name=" + toolName + ", raw_params=" + params);
            }
            
            try {
                JSONObject toolCall = new JSONObject();
                toolCall.put("type", "function");
                
                JSONObject function = new JSONObject();
                function.put("name", toolName);
                
                // Parse parameters as JSON if possible, otherwise as key=value pairs
                try {
                    if (params.startsWith("{")) {
                        function.put("arguments", params);
                    } else {
                        // Try to parse as key=value pairs (including Gemma format)
                        JSONObject args = parseKeyValueParams(params);
                        String argsJson = args.toString();
                        if (DEBUG) {
                            Log.d(TAG, "Parsed arguments for " + toolName + ": " + argsJson);
                        }
                        function.put("arguments", argsJson);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse arguments for " + toolName + ": " + params, e);
                    function.put("arguments", "{}");
                }
                
                toolCall.put("function", function);
                toolCalls.add(toolCall);
            } catch (JSONException e) {
                // Log but continue extracting other tool calls
                Log.w(TAG, "Failed to create tool call object for: " + toolName, e);
            }
        }
        
        if (DEBUG && !toolCalls.isEmpty()) {
            Log.d(TAG, "Extracted " + toolCalls.size() + " tool calls");
        }
        
        return toolCalls.isEmpty() ? null : new JSONArray(toolCalls);
    }
    
    /**
     * Parse key=value parameters from tool call arguments.
     * 
     * Supports multiple formats:
     * - Standard: key="value" or key='value'
     * - Gemma-4: key:<|"|>value<|"|> (with quote escape sequences)
     * - Simple: key=value
     * 
     * Example inputs and outputs:
     * - query="search term" → {"query": "search term"}
     * - format:<|"|>readable<|"|> → {"format": "readable"}
     * - max_results=5 → {"max_results": "5"}
     */
    private static JSONObject parseKeyValueParams(String params) throws JSONException {
        JSONObject result = new JSONObject();
        
        if (params == null || params.trim().isEmpty()) {
            return result;
        }
        
        // Split by commas (but be careful with quoted values)
        String[] pairs = splitParams(params);
        
        for (String pair : pairs) {
            pair = pair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            
            String key = GemmaParamUtil.extractKey(pair);
            String value = GemmaParamUtil.extractValue(pair);
            
            if (!key.isEmpty()) {
                if (DEBUG) {
                    Log.d(TAG, "Parsed param: key=" + key + ", value=" + value);
                }
                result.put(key, value);
            }
        }
        
        if (result.length() == 0 && !params.isEmpty()) {
            // If no matches, still log for debugging
            Log.w(TAG, "Failed to parse any params from: " + params);
            // Return empty object, not raw wrapper - empty object is safer for JSON parsing
            return result;
        }
        
        return result;
    }
    
    /**
     * Split parameter string by comma, respecting quoted values.
     * Handles both standard quotes and Gemma escape sequences.
     */
    private static String[] splitParams(String params) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inGemmaQuote = false;
        boolean inStandardQuote = false;
        
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            
            // Check for Gemma quote sequences: <|"|> or <|'|>
            if (i + 3 < params.length() && params.charAt(i) == '<' && params.charAt(i + 1) == '|') {
                if (params.substring(i, i + 4).equals("<|\"|>")) {
                    inGemmaQuote = !inGemmaQuote;
                    current.append(params.substring(i, i + 4));
                    i += 3;
                    continue;
                } else if (params.substring(i, i + 4).equals("<|'|>")) {
                    inGemmaQuote = !inGemmaQuote;
                    current.append(params.substring(i, i + 4));
                    i += 3;
                    continue;
                }
            }
            
            // Check for standard quotes
            if (c == '"' && (i == 0 || params.charAt(i - 1) != '\\')) {
                inStandardQuote = !inStandardQuote;
            }
            
            // Check for comma delimiter (outside quotes)
            if (c == ',' && !inGemmaQuote && !inStandardQuote) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        
        return parts.toArray(new String[0]);
    }
    
    /**
     * Extract thinking content from model response if present.
     * Returns the thinking block content, or null if none found.
     */
    static String extractThinkingContent(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        // Look for thinking markers
        Pattern thinkingPattern = Pattern.compile("<\\|channel>thought([^<]*)<\\|channel\\|>");
        Matcher matcher = thinkingPattern.matcher(content);
        
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        // Alternative pattern
        Pattern altPattern = Pattern.compile("<think>([^<]*)</think>");
        Matcher altMatcher = altPattern.matcher(content);
        
        if (altMatcher.find()) {
            return altMatcher.group(1).trim();
        }
        
        return null;
    }
}
