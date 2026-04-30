package com.micklab.llama;

/**
 * Utility for handling Gemma-4 parameter escaping.
 * 
 * Gemma-4 uses special escape sequences for parameter values:
 * - <|"|> represents a quote character
 * - <|'|> represents a single quote
 * 
 * Example: format:<|"|>readable<|"|> should be parsed as format: "readable"
 */
final class GemmaParamUtil {
    private GemmaParamUtil() {
    }

    /**
     * Unescape Gemma-4 parameter format to standard representation.
     * Converts <|"|> → " and <|'|> → '
     * 
     * @param escaped The escaped value from Gemma-4
     * @return The unescaped value
     */
    static String unescapeGemmaFormat(String escaped) {
        if (escaped == null || escaped.isEmpty()) {
            return escaped;
        }
        
        return escaped
                .replace("<|\"|>", "\"")
                .replace("<|'|>", "'");
    }

    /**
     * Check if a string contains Gemma escape sequences.
     */
    static boolean hasGemmaEscapes(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("<|\"|>") || text.contains("<|'|>");
    }

    /**
     * Extract value between delimiters with Gemma escape handling.
     * Example: "format:<|"|>readable<|"|>" → "readable"
     * 
     * Handles both standard quotes and Gemma escape sequences.
     */
    static String extractValue(String kvPair) {
        if (kvPair == null || kvPair.isEmpty()) {
            return "";
        }

        // Split by = to get the value part
        int eqIndex = kvPair.indexOf('=');
        if (eqIndex < 0) {
            eqIndex = kvPair.indexOf(':');
        }
        if (eqIndex < 0) {
            return kvPair.trim();
        }

        String valuePart = kvPair.substring(eqIndex + 1).trim();

        // Handle Gemma-escaped quotes: <|"|>...<|"|>
        if (valuePart.startsWith("<|\"|>")) {
            int endIdx = valuePart.indexOf("<|\"|>", 6); // Start search after opening sequence
            if (endIdx > 0) {
                return valuePart.substring(6, endIdx);
            }
        }

        // Handle Gemma-escaped single quotes: <|'|>...<|'|>
        if (valuePart.startsWith("<|'|>")) {
            int endIdx = valuePart.indexOf("<|'|>", 5);
            if (endIdx > 0) {
                return valuePart.substring(5, endIdx);
            }
        }

        // Handle standard quoted strings
        if (valuePart.startsWith("\"")) {
            int endIdx = valuePart.indexOf("\"", 1);
            if (endIdx > 0) {
                return valuePart.substring(1, endIdx);
            }
        }

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
     */
    static String extractKey(String kvPair) {
        if (kvPair == null || kvPair.isEmpty()) {
            return "";
        }

        int eqIndex = kvPair.indexOf('=');
        if (eqIndex < 0) {
            eqIndex = kvPair.indexOf(':');
        }
        if (eqIndex < 0) {
            return kvPair.trim();
        }

        return kvPair.substring(0, eqIndex).trim();
    }
}
