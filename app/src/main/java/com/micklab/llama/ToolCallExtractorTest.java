package com.micklab.llama;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Test cases for Gemma-4 parameter parsing and tool call extraction.
 * 
 * These tests verify that tool calls with Gemma-4 escape sequences
 * are correctly parsed and converted to valid JSON.
 */
public class ToolCallExtractorTest {

    public static void main(String[] args) {
        System.out.println("=== ToolCallExtractor Tests ===\n");
        
        // Test 1: Gemma-4 format with quote escapes
        testCase1_GemmaQuoteEscape();
        
        // Test 2: Multiple parameters with Gemma escapes
        testCase2_MultipleGemmaParams();
        
        // Test 3: Standard quoted format (backward compatibility)
        testCase3_StandardQuotedFormat();
        
        // Test 4: Simple key=value format
        testCase4_SimpleKeyValue();
        
        // Test 5: Mixed formats
        testCase5_MixedFormats();
        
        System.out.println("\n=== All Tests Complete ===");
    }

    /**
     * Test: format:<|"|>readable<|"|>
     * Expected: {"format": "readable"}
     */
    static void testCase1_GemmaQuoteEscape() {
        System.out.println("Test 1: Gemma quote escape - format:<|"|>readable<|"|>");
        try {
            String toolCallContent = "<|tool_call>call:get_time{format:<|\"|>readable<|\"|>}<|tool_call|>";
            JSONArray toolCalls = ToolCallExtractor.extractToolCalls(toolCallContent);
            
            assert toolCalls != null && toolCalls.length() > 0 : "No tool calls extracted";
            
            JSONObject toolCall = toolCalls.getJSONObject(0);
            JSONObject function = toolCall.getJSONObject("function");
            
            assert "get_time".equals(function.getString("name")) : "Tool name mismatch";
            
            JSONObject args = new JSONObject(function.getString("arguments"));
            assert "readable".equals(args.getString("format")) : "Format parameter not parsed correctly";
            
            System.out.println("✓ PASSED: " + args.toString());
        } catch (Exception e) {
            System.out.println("✗ FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test: query:<|"|>search term<|"|>,maxResults=5
     * Expected: {"query": "search term", "maxResults": "5"}
     */
    static void testCase2_MultipleGemmaParams() {
        System.out.println("\nTest 2: Multiple Gemma parameters");
        try {
            String toolCallContent = "<|tool_call>call:web_search{query:<|\"|>search term<|\"|>,maxResults=5}<|tool_call|>";
            JSONArray toolCalls = ToolCallExtractor.extractToolCalls(toolCallContent);
            
            assert toolCalls != null && toolCalls.length() > 0 : "No tool calls extracted";
            
            JSONObject toolCall = toolCalls.getJSONObject(0);
            JSONObject function = toolCall.getJSONObject("function");
            
            assert "web_search".equals(function.getString("name")) : "Tool name mismatch";
            
            JSONObject args = new JSONObject(function.getString("arguments"));
            assert "search term".equals(args.getString("query")) : "Query parameter not parsed correctly";
            assert "5".equals(args.getString("maxResults")) : "MaxResults parameter not parsed correctly";
            
            System.out.println("✓ PASSED: " + args.toString());
        } catch (Exception e) {
            System.out.println("✗ FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test: format="readable"
     * Expected: {"format": "readable"}
     */
    static void testCase3_StandardQuotedFormat() {
        System.out.println("\nTest 3: Standard quoted format (backward compatibility)");
        try {
            String toolCallContent = "<|tool_call>call:get_time{format=\"readable\"}<|tool_call|>";
            JSONArray toolCalls = ToolCallExtractor.extractToolCalls(toolCallContent);
            
            assert toolCalls != null && toolCalls.length() > 0 : "No tool calls extracted";
            
            JSONObject toolCall = toolCalls.getJSONObject(0);
            JSONObject function = toolCall.getJSONObject("function");
            
            JSONObject args = new JSONObject(function.getString("arguments"));
            assert "readable".equals(args.getString("format")) : "Format parameter not parsed correctly";
            
            System.out.println("✓ PASSED: " + args.toString());
        } catch (Exception e) {
            System.out.println("✗ FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test: format=readable
     * Expected: {"format": "readable"}
     */
    static void testCase4_SimpleKeyValue() {
        System.out.println("\nTest 4: Simple key=value format");
        try {
            String toolCallContent = "<|tool_call>call:get_time{format=readable}<|tool_call|>";
            JSONArray toolCalls = ToolCallExtractor.extractToolCalls(toolCallContent);
            
            assert toolCalls != null && toolCalls.length() > 0 : "No tool calls extracted";
            
            JSONObject toolCall = toolCalls.getJSONObject(0);
            JSONObject function = toolCall.getJSONObject("function");
            
            JSONObject args = new JSONObject(function.getString("arguments"));
            assert "readable".equals(args.getString("format")) : "Format parameter not parsed correctly";
            
            System.out.println("✓ PASSED: " + args.toString());
        } catch (Exception e) {
            System.out.println("✗ FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test: Mixed formats with Gemma quotes and standard quotes
     * text:<|"|>hello world<|"|>,count=3,name="test_user"
     */
    static void testCase5_MixedFormats() {
        System.out.println("\nTest 5: Mixed formats");
        try {
            String toolCallContent = "<|tool_call>call:test_tool{text:<|\"|>hello world<|\"|>,count=3,name=\"test_user\"}<|tool_call|>";
            JSONArray toolCalls = ToolCallExtractor.extractToolCalls(toolCallContent);
            
            assert toolCalls != null && toolCalls.length() > 0 : "No tool calls extracted";
            
            JSONObject toolCall = toolCalls.getJSONObject(0);
            JSONObject function = toolCall.getJSONObject("function");
            
            JSONObject args = new JSONObject(function.getString("arguments"));
            assert "hello world".equals(args.getString("text")) : "Text parameter not parsed correctly";
            assert "3".equals(args.getString("count")) : "Count parameter not parsed correctly";
            assert "test_user".equals(args.getString("name")) : "Name parameter not parsed correctly";
            
            System.out.println("✓ PASSED: " + args.toString());
        } catch (Exception e) {
            System.out.println("✗ FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
