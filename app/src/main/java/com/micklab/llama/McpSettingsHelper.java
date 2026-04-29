package com.micklab.llama;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class McpSettingsHelper {
    static final String PREFS_NAME = "ollama_prefs";
    static final String PREF_SHARED_MCP_SERVERS_JSON = "shared_mcp_servers_json";
    static final String PREF_SHARED_FUNCTION_DEFINITIONS_JSON = "shared_function_definitions_json";
    static final String WEBUI_SHARED_MCP_SERVERS_KEY = "sharedMcpServers";
    static final String WEBUI_SHARED_FUNCTION_DEFINITIONS_KEY = "sharedFunctionDefinitions";

    private static final String SHARED_MCP_SERVERS_HINT =
            "[\n" +
            "  {\n" +
            "    \"enabled\": true,\n" +
            "    \"name\": \"Example SSE Server\",\n" +
            "    \"url\": \"https://mcp.example.com/sse\"\n" +
            "  }\n" +
            "]";
    private static final String SHARED_FUNCTION_DEFINITIONS_HINT =
            "[\n" +
            "  {\n" +
            "    \"name\": \"get_weather\",\n" +
            "    \"description\": \"Get weather by city name\",\n" +
            "    \"parameters\": {\n" +
            "      \"type\": \"object\",\n" +
            "      \"properties\": {\n" +
            "        \"city\": {\n" +
            "          \"type\": \"string\"\n" +
            "        }\n" +
            "      },\n" +
            "      \"required\": [\"city\"]\n" +
            "    }\n" +
            "  }\n" +
            "]";

    private McpSettingsHelper() {
    }

    static String getSharedMcpServersHint() {
        return SHARED_MCP_SERVERS_HINT;
    }

    static String getSharedFunctionDefinitionsHint() {
        return SHARED_FUNCTION_DEFINITIONS_HINT;
    }

    static String getSharedMcpServersJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeStoredJson(prefs.getString(PREF_SHARED_MCP_SERVERS_JSON, ""));
    }

    static String getSharedFunctionDefinitionsJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeStoredJson(prefs.getString(PREF_SHARED_FUNCTION_DEFINITIONS_JSON, ""));
    }

    static void saveSharedMcpServersJson(Context context, String rawJson) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_SHARED_MCP_SERVERS_JSON, normalizeStoredJson(rawJson))
                .apply();
    }

    static void saveSharedFunctionDefinitionsJson(Context context, String rawJson) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_SHARED_FUNCTION_DEFINITIONS_JSON, normalizeStoredJson(rawJson))
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

    static boolean isSharedFunctionDefinitionsJsonValid(String rawJson) {
        String normalized = normalizeStoredJson(rawJson);
        if (normalized.isEmpty()) {
            return true;
        }

        try {
            JSONArray definitions = new JSONArray(normalized);
            for (int i = 0; i < definitions.length(); i++) {
                JSONObject definition = definitions.optJSONObject(i);
                if (definition == null) {
                    return false;
                }

                JSONObject function = definition;
                if ("function".equals(definition.optString("type"))) {
                    function = definition.optJSONObject("function");
                }
                if (function == null) {
                    return false;
                }

                String name = function.optString("name", "").trim();
                if (name.isEmpty()) {
                    return false;
                }

                Object parameters = function.opt("parameters");
                if (parameters != null && parameters != JSONObject.NULL && !(parameters instanceof JSONObject)) {
                    return false;
                }
            }
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    private static String normalizeStoredJson(String rawJson) {
        return rawJson == null ? "" : rawJson.trim();
    }
}
