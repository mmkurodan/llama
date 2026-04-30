package com.micklab.llama;

/**
 * Utility for handling parameter escaping in tool call arguments.
 * 
 * Supports multiple escape formats found across LLM models:
 * - Gemma-4: <|"|> for quotes, <|'|> for single quotes
 * - Standard: Regular quoted strings (all models)
 * - Future: Extensible for other models' escape sequences
 * 
 * Example: format:<|"|>readable<|"|> should be parsed as format: "readable"
 */
final class ParameterEscapeUtil {
    private ParameterEscapeUtil() {
    }

    /**
     * Unescape parameter format to standard representation.
     * Currently supports Gemma-4 escape sequences.
     * 
     * Converts:
     * - <|"|> → "
     * - <|'|> → '
     * 
     * @param escaped The escaped value
     * @return The unescaped value
     */
    static String unescape(String escaped) {
        if (escaped == null || escaped.isEmpty()) {
            return escaped;
        }
        
        return escaped
                .replace("<|\"|>", "\"")
                .replace("<|'|>", "'");
    }

    /**
     * Check if a string contains known escape sequences.
     * Currently checks for Gemma-4 patterns.
     */
    static boolean hasEscapeSequences(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("<|\"|>") || text.contains("<|'|>");
    }

    /**
     * Extract value from key=value or key:value pair.
     * 
     * Handles multiple quote/escape formats:
     * - Gemma-4 escape: key:<|"|>value<|"|>
     * - Standard quotes: key="value" or key='value'
     * - Unquoted: key=value
     * 
     * @param kvPair A key-value pair string
     * @return The extracted value, or empty string if not found
     */
    static String extractValue(String kvPair) {
        if (kvPair == null || kvPair.isEmpty()) {
            return "";
        }

        // Split by = or : to get the value part
        int delimiterIndex = kvPair.indexOf('=');
        if (delimiterIndex < 0) {
            delimiterIndex = kvPair.indexOf(':');
        }
        if (delimiterIndex < 0) {
            return kvPair.trim();
        }

        String valuePart = kvPair.substring(delimiterIndex + 1).trim();

        // Handle Gemma-4 escaped quotes: <|"|>...<|"|>
        if (valuePart.startsWith("<|\"|>")) {
            int endIdx = valuePart.indexOf("<|\"|>", 6); // Start search after opening sequence
            if (endIdx > 0) {
                return valuePart.substring(6, endIdx);
            }
        }

        // Handle Gemma-4 escaped single quotes: <|'|>...<|'|>
        if (valuePart.startsWith("<|'|>")) {
            int endIdx = valuePart.indexOf("<|'|>", 5);
            if (endIdx > 0) {
                return valuePart.substring(5, endIdx);
            }
        }

        // Handle standard double-quoted strings
        if (valuePart.startsWith("\"")) {
            int endIdx = valuePart.indexOf("\"", 1);
            if (endIdx > 0) {
                return valuePart.substring(1, endIdx);
            }
        }

        // Handle standard single-quoted strings
        if (valuePart.startsWith("'")) {
            int endIdx = valuePart.indexOf("'", 1);
            if (endIdx > 0) {
                return valuePart.substring(1, endIdx);
            }
        }

        // Return as-is if no delimiters found
        return valuePart;
    }

    /**
     * Extract key from a key=value or key:value pair.
     * 
     * @param kvPair A key-value pair string
     * @return The extracted key, or empty string if not found
     */
    static String extractKey(String kvPair) {
        if (kvPair == null || kvPair.isEmpty()) {
            return "";
        }

        int delimiterIndex = kvPair.indexOf('=');
        if (delimiterIndex < 0) {
            delimiterIndex = kvPair.indexOf(':');
        }
        if (delimiterIndex < 0) {
            return kvPair.trim();
        }

        return kvPair.substring(0, delimiterIndex).trim();
    }
}
