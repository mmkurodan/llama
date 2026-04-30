package com.micklab.llama;

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
 */
class ToolCallExtractor {
    private static final String TAG = "ToolCallExtractor";
    
    /**
     * Pattern for extracting tool calls from thinking logs:
     * <|tool_call>call:TOOL_NAME{...}<|tool_call|>
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
            
            try {
                JSONObject toolCall = new JSONObject();
                toolCall.put("type", "function");
                
                JSONObject function = new JSONObject();
                function.put("name", toolName);
                
                // Parse parameters as JSON if possible, otherwise as empty
                try {
                    if (params.startsWith("{")) {
                        function.put("arguments", params);
                    } else {
                        // Try to parse as key=value pairs
                        JSONObject args = parseKeyValueParams(params);
                        function.put("arguments", args.toString());
                    }
                } catch (Exception e) {
                    function.put("arguments", "{}");
                }
                
                toolCall.put("function", function);
                toolCalls.add(toolCall);
            } catch (JSONException e) {
                // Log but continue extracting other tool calls
                android.util.Log.w(TAG, "Failed to parse tool call: " + toolName);
            }
        }
        
        return toolCalls.isEmpty() ? null : new JSONArray(toolCalls);
    }
    
    /**
     * Parse simple key=value parameters from tool call arguments.
     * Example: query="search term" returns {"query": "search term"}
     */
    private static JSONObject parseKeyValueParams(String params) throws JSONException {
        JSONObject result = new JSONObject();
        
        // Simple regex to match key="value" or key:value patterns
        Pattern kvPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*[\"']?([^\"',}]*)[\"']?");
        Matcher kvMatcher = kvPattern.matcher(params);
        
        while (kvMatcher.find()) {
            String key = kvMatcher.group(1);
            String value = kvMatcher.group(2).trim();
            result.put(key, value);
        }
        
        if (result.length() == 0) {
            // If no matches, return as-is wrapped in braces
            return new JSONObject("{\"raw\": \"" + params.replace("\"", "\\\"") + "\"}");
        }
        
        return result;
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
