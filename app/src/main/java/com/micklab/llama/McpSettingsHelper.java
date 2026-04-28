package com.micklab.llama;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

final class McpSettingsHelper {
    static final String PREFS_NAME = "ollama_prefs";
    static final String PREF_SHARED_MCP_SERVERS_JSON = "shared_mcp_servers_json";
    static final String WEBUI_SHARED_MCP_SERVERS_KEY = "sharedMcpServers";

    private static final String SHARED_MCP_SERVERS_HINT =
            "[\n" +
            "  {\n" +
            "    \"enabled\": true,\n" +
            "    \"name\": \"Example SSE Server\",\n" +
            "    \"url\": \"https://mcp.example.com/sse\"\n" +
            "  }\n" +
            "]";

    private McpSettingsHelper() {
    }

    static String getSharedMcpServersHint() {
        return SHARED_MCP_SERVERS_HINT;
    }

    static String getSharedMcpServersJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeStoredJson(prefs.getString(PREF_SHARED_MCP_SERVERS_JSON, ""));
    }

    static void saveSharedMcpServersJson(Context context, String rawJson) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_SHARED_MCP_SERVERS_JSON, normalizeStoredJson(rawJson))
                .apply();
    }

    static boolean isSharedMcpServersJsonValid(String rawJson) {
        String normalized = normalizeStoredJson(rawJson);
        if (normalized.isEmpty()) {
            return true;
        }

        try {
            new JSONArray(normalized);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    private static String normalizeStoredJson(String rawJson) {
        return rawJson == null ? "" : rawJson.trim();
    }
}
