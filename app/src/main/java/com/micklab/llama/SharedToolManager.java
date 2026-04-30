package com.micklab.llama;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class SharedToolManager {
    private static final String TAG = "SharedToolManager";
    private static final int MAX_AUTO_TOOL_TURNS = 10;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 300;
    private static final String JSON_RPC_VERSION = "2.0";
    private static final String MCP_PROTOCOL_VERSION = "2025-06-18";
    private static final String MCP_CLIENT_NAME = "llama-android-mcp";
    private static final String MCP_CLIENT_VERSION = "1.0.0";

    private SharedToolManager() {
    }

    static final class ChatResult {
        final String content;
        final String reasoningContent;
        final JSONArray toolCalls;
        final String finishReason;

        ChatResult(
                String content,
                String reasoningContent,
                JSONArray toolCalls,
                String finishReason
        ) {
            this.content = content;
            this.reasoningContent = reasoningContent;
            this.toolCalls = toolCalls;
            this.finishReason = finishReason != null ? finishReason : "stop";
        }

        static ChatResult fromJson(String rawJson) throws JSONException {
            JSONObject json = new JSONObject(rawJson);
            String error = json.optString("error", "");
            if (!error.isEmpty()) {
                throw new IOExceptionWrappedException(error);
            }
            JSONArray toolCalls = json.optJSONArray("tool_calls");
            
            // If no direct tool_calls field, try to extract from thinking content
            if ((toolCalls == null || toolCalls.length() == 0)) {
                String reasoningContent = json.optString("reasoning_content", "");
                String content = json.optString("content", "");
                
                // Try to extract from thinking log patterns
                JSONArray extractedCalls = ToolCallExtractor.extractToolCalls(reasoningContent);
                if (extractedCalls == null && !content.isEmpty()) {
                    extractedCalls = ToolCallExtractor.extractToolCalls(content);
                }
                
                if (extractedCalls != null) {
                    toolCalls = extractedCalls;
                }
            }
            
            return new ChatResult(
                    json.optString("content", ""),
                    json.optString("reasoning_content", null),
                    toolCalls,
                    json.optString("finish_reason", toolCalls != null && toolCalls.length() > 0 ? "tool_calls" : "stop")
            );
        }
    }

    static ChatResult generateWithTools(
            Context context,
            LlamaNative llama,
            JSONArray messages,
            JSONArray requestTools,
            String customTemplate,
            String settingsSystemPrompt,
            String serializedToolChoice,
            boolean parallelToolCalls,
            boolean enableThinking,
            byte[][] mediaFiles,
            boolean includeSharedFunctionDefinitions,
            boolean autoExecuteSharedTools
    ) throws Exception {
        try (SharedToolCatalog catalog = SharedToolCatalog.load(context, requestTools, includeSharedFunctionDefinitions)) {
            if (catalog.hasFatalSharedConfigurationError()) {
                throw new IOException(catalog.getPrimaryLoadError());
            }
            if (!catalog.hasAnyTools()) {
                return null;
            }

            JSONArray conversation = prepareMessagesForNativeChat(messages, settingsSystemPrompt);
            String currentToolChoice = serializedToolChoice;
            String toolsJson = catalog.combinedTools.toString();

            for (int turn = 0; turn < MAX_AUTO_TOOL_TURNS; turn++) {
                String rawResult = llama.generateOpenAiChatCompletion(
                        conversation.toString(),
                        toolsJson,
                        customTemplate,
                        currentToolChoice != null ? currentToolChoice : "",
                        parallelToolCalls,
                        enableThinking,
                        mediaFiles
                );
                ChatResult result = ChatResult.fromJson(rawResult);
                if (result.toolCalls == null || result.toolCalls.length() == 0 || !autoExecuteSharedTools) {
                    return result;
                }
                if (!catalog.canExecuteAll(result.toolCalls)) {
                    return result;
                }

                appendAssistantToolCallMessage(conversation, result);
                catalog.appendToolResults(conversation, result.toolCalls);
                currentToolChoice = null;
            }
        }

        throw new IOException("Tool execution exceeded " + MAX_AUTO_TOOL_TURNS + " turns");
    }

    private static void appendAssistantToolCallMessage(JSONArray conversation, ChatResult result)
            throws JSONException {
        JSONObject assistantMessage = new JSONObject();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", result.content != null ? result.content : "");
        if (result.reasoningContent != null && !result.reasoningContent.isEmpty()) {
            assistantMessage.put("reasoning_content", result.reasoningContent);
        }
        if (result.toolCalls != null && result.toolCalls.length() > 0) {
            assistantMessage.put("tool_calls", new JSONArray(result.toolCalls.toString()));
        }
        conversation.put(assistantMessage);
    }

    static String serializeToolChoice(Object toolChoice) {
        if (toolChoice == null || toolChoice == JSONObject.NULL) {
            return null;
        }
        if (toolChoice instanceof JSONObject || toolChoice instanceof JSONArray) {
            return toolChoice.toString();
        }
        if (toolChoice instanceof String) {
            return JSONObject.quote((String) toolChoice);
        }
        return null;
    }

    private static JSONObject copyJsonObject(JSONObject source) throws JSONException {
        JSONObject copy = new JSONObject();
        if (source == null) {
            return copy;
        }
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            copy.put(key, source.opt(key));
        }
        return copy;
    }

    private static JSONArray prepareMessagesForNativeChat(
            JSONArray messages,
            String settingsSystemPrompt
    ) throws JSONException {
        JSONArray normalizedMessages = new JSONArray();
        String apiSystemPrompt = null;

        for (int i = 0; i < messages.length(); i++) {
            JSONObject original = messages.getJSONObject(i);
            JSONObject normalized = copyJsonObject(original);

            Object rawContent = normalized.opt("content");
            if (rawContent instanceof String) {
                normalized.put(
                        "content",
                        PromptTemplateManager.stripTemplateMarkers((String) rawContent)
                );
            }

            if ("system".equals(normalized.optString("role", ""))) {
                Object systemContent = normalized.opt("content");
                if (systemContent == JSONObject.NULL || systemContent == null) {
                    apiSystemPrompt = null;
                } else if (systemContent instanceof String) {
                    apiSystemPrompt = (String) systemContent;
                } else {
                    apiSystemPrompt = String.valueOf(systemContent);
                }
                continue;
            }

            normalizedMessages.put(normalized);
        }

        String resolvedSystemPrompt = PromptTemplateManager.resolveSystemPrompt(
                apiSystemPrompt,
                settingsSystemPrompt
        );
        if (resolvedSystemPrompt == null || resolvedSystemPrompt.isEmpty()) {
            return normalizedMessages;
        }

        JSONArray withSystem = new JSONArray();
        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", resolvedSystemPrompt);
        withSystem.put(systemMessage);
        for (int i = 0; i < normalizedMessages.length(); i++) {
            withSystem.put(normalizedMessages.getJSONObject(i));
        }
        return withSystem;
    }

    private interface ToolExecutor {
        String execute(String argumentsJson) throws Exception;
    }

    private static final class SharedToolCatalog implements Closeable {
        final JSONArray combinedTools = new JSONArray();
        private final Map<String, ToolExecutor> executableTools = new HashMap<>();
        private final List<Closeable> closeables = new ArrayList<>();
        private final List<String> loadErrors = new ArrayList<>();
        private final Set<String> occupiedNames = new HashSet<>();
        private int enabledSharedMcpServerCount = 0;
        private int loadedSharedMcpToolCount = 0;
        private int requestedSharedFunctionDefinitionCount = 0;
        private int loadedSharedFunctionDefinitionCount = 0;

        static SharedToolCatalog load(
                Context context,
                JSONArray requestTools,
                boolean includeSharedFunctionDefinitions
        ) throws JSONException {
            SharedToolCatalog catalog = new SharedToolCatalog();
            catalog.addRequestTools(requestTools);
            if (includeSharedFunctionDefinitions) {
                catalog.addSharedFunctionDefinitions(context);
            }
            catalog.addSharedMcpTools(context);
            return catalog;
        }

        private void addRequestTools(JSONArray requestTools) throws JSONException {
            if (requestTools == null) {
                return;
            }
            for (int i = 0; i < requestTools.length(); i++) {
                JSONObject tool = requestTools.optJSONObject(i);
                if (tool == null) {
                    continue;
                }
                combinedTools.put(new JSONObject(tool.toString()));
                String requestedName = extractToolName(tool);
                if (requestedName != null && !requestedName.isEmpty()) {
                    occupiedNames.add(requestedName);
                }
            }
        }

        private void addSharedFunctionDefinitions(Context context) throws JSONException {
            String rawJson = McpSettingsHelper.getSharedFunctionDefinitionsJsonForNonWebUi(context);
            if (rawJson.isEmpty()) {
                return;
            }

            JSONArray definitions = new JSONArray(rawJson);
            requestedSharedFunctionDefinitionCount = definitions.length();
            for (int i = 0; i < definitions.length(); i++) {
                JSONObject normalized = normalizeStoredFunctionDefinition(definitions.optJSONObject(i));
                if (normalized == null) {
                    loadErrors.add("Invalid shared function definition at index " + i);
                    continue;
                }

                String originalName = normalized.getJSONObject("function").optString("name", "");
                String uniqueName = reserveToolName(originalName, "function");
                if (!uniqueName.equals(originalName)) {
                    normalized.getJSONObject("function").put("name", uniqueName);
                }
                combinedTools.put(normalized);
                loadedSharedFunctionDefinitionCount++;
            }
        }

        private void addSharedMcpTools(Context context) {
            String rawJson = McpSettingsHelper.getSharedMcpServersJsonForNonWebUi(context);
            if (rawJson.isEmpty()) {
                return;
            }

            try {
                JSONArray servers = new JSONArray(rawJson);
                for (int i = 0; i < servers.length(); i++) {
                    JSONObject server = servers.optJSONObject(i);
                    if (server == null || !server.optBoolean("enabled", false)) {
                        continue;
                    }

                    String url = server.optString("url", "").trim();
                    if (url.isEmpty()) {
                        continue;
                    }

                    enabledSharedMcpServerCount++;
                    String serverLabel = buildServerLabel(server, enabledSharedMcpServerCount);
                    try {
                        McpSession session = McpSession.connect(server, serverLabel);
                        closeables.add(session);
                        JSONArray tools = session.listTools();
                        for (int toolIndex = 0; toolIndex < tools.length(); toolIndex++) {
                            JSONObject mcpTool = tools.optJSONObject(toolIndex);
                            if (mcpTool == null) {
                                continue;
                            }

                            String originalName = mcpTool.optString("name", "").trim();
                            if (originalName.isEmpty()) {
                                continue;
                            }

                            String exposedName = reserveToolName(originalName, serverLabel);
                            combinedTools.put(buildOpenAiTool(exposedName, serverLabel, mcpTool));
                            executableTools.put(
                                    exposedName,
                                    argumentsJson -> session.callTool(originalName, argumentsJson)
                            );
                            loadedSharedMcpToolCount++;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to load MCP tools from " + url, e);
                        loadErrors.add(serverLabel + ": " + (e.getMessage() != null ? e.getMessage() : "unknown error"));
                    }
                }
            } catch (JSONException e) {
                loadErrors.add("Invalid shared MCP config JSON");
            }
        }

        boolean hasAnyTools() {
            return combinedTools.length() > 0;
        }

        boolean hasFatalSharedConfigurationError() {
            if (enabledSharedMcpServerCount > 0 && loadedSharedMcpToolCount == 0 && !loadErrors.isEmpty()) {
                return true;
            }
            return requestedSharedFunctionDefinitionCount > 0
                    && loadedSharedFunctionDefinitionCount == 0
                    && !loadErrors.isEmpty();
        }

        String getPrimaryLoadError() {
            if (loadErrors.isEmpty()) {
                return "Failed to load shared tools";
            }
            return loadErrors.get(0);
        }

        boolean canExecuteAll(JSONArray toolCalls) {
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject toolCall = toolCalls.optJSONObject(i);
                if (toolCall == null) {
                    return false;
                }
                JSONObject function = toolCall.optJSONObject("function");
                String name = function != null ? function.optString("name", "") : "";
                if (!executableTools.containsKey(name)) {
                    return false;
                }
            }
            return true;
        }

        void appendToolResults(JSONArray conversation, JSONArray toolCalls) throws JSONException {
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject toolCall = toolCalls.getJSONObject(i);
                JSONObject function = toolCall.optJSONObject("function");
                String toolName = function != null ? function.optString("name", "") : "";
                String toolCallId = toolCall.optString("id", "tool_call_" + i);
                String argumentsJson = function != null ? function.optString("arguments", "") : "";

                ToolExecutor executor = executableTools.get(toolName);
                String resultContent;
                if (executor == null) {
                    resultContent = buildErrorToolContent("No executor for tool: " + toolName);
                } else {
                    try {
                        resultContent = executor.execute(argumentsJson);
                    } catch (Exception e) {
                        Log.w(TAG, "Tool execution failed: " + toolName, e);
                        resultContent = buildErrorToolContent(e.getMessage());
                    }
                }

                JSONObject toolMessage = new JSONObject();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", toolCallId);
                toolMessage.put("name", toolName);
                toolMessage.put("content", resultContent);
                conversation.put(toolMessage);
            }
        }

        @Override
        public void close() throws IOException {
            IOException firstError = null;
            for (Closeable closeable : closeables) {
                try {
                    closeable.close();
                } catch (IOException e) {
                    if (firstError == null) {
                        firstError = e;
                    }
                }
            }
            if (firstError != null) {
                throw firstError;
            }
        }

        private static JSONObject normalizeStoredFunctionDefinition(JSONObject definition)
                throws JSONException {
            if (definition == null) {
                return null;
            }

            JSONObject normalized = new JSONObject(definition.toString());
            if ("function".equals(normalized.optString("type"))) {
                JSONObject function = normalized.optJSONObject("function");
                if (function == null || function.optString("name", "").trim().isEmpty()) {
                    return null;
                }
                ensureParametersObject(function);
                return normalized;
            }

            if (normalized.optString("name", "").trim().isEmpty()) {
                return null;
            }
            ensureParametersObject(normalized);

            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", normalized);
            return tool;
        }

        private static void ensureParametersObject(JSONObject function) throws JSONException {
            Object parameters = function.opt("parameters");
            if (!(parameters instanceof JSONObject)) {
                JSONObject defaultParameters = new JSONObject();
                defaultParameters.put("type", "object");
                defaultParameters.put("properties", new JSONObject());
                function.put("parameters", defaultParameters);
            }
        }

        private String reserveToolName(String preferredName, String fallbackPrefix) {
            String base = sanitizeToolName(preferredName);
            if (base.isEmpty()) {
                base = sanitizeToolName(fallbackPrefix);
            }
            if (base.isEmpty()) {
                base = "tool";
            }
            String candidate = base;
            int suffix = 2;
            while (occupiedNames.contains(candidate)) {
                candidate = base + "_" + suffix;
                suffix++;
            }
            occupiedNames.add(candidate);
            return candidate;
        }

        private static String sanitizeToolName(String rawName) {
            if (rawName == null) {
                return "";
            }
            String sanitized = rawName.trim().replaceAll("[^A-Za-z0-9_-]", "_");
            sanitized = sanitized.replaceAll("_+", "_");
            while (sanitized.startsWith("_")) {
                sanitized = sanitized.substring(1);
            }
            while (sanitized.endsWith("_")) {
                sanitized = sanitized.substring(0, sanitized.length() - 1);
            }
            return sanitized;
        }

        private static String extractToolName(JSONObject tool) {
            if (tool == null) {
                return null;
            }
            if ("function".equals(tool.optString("type"))) {
                JSONObject function = tool.optJSONObject("function");
                return function != null ? function.optString("name", "") : null;
            }
            return tool.optString("name", "");
        }

        private static String buildServerLabel(JSONObject server, int fallbackIndex) {
            String name = server.optString("name", "").trim();
            if (!name.isEmpty()) {
                return name;
            }
            String urlText = server.optString("url", "").trim();
            if (!urlText.isEmpty()) {
                try {
                    URI uri = URI.create(urlText);
                    String host = uri.getHost();
                    if (host != null && !host.isEmpty()) {
                        return host;
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            return "mcp-" + fallbackIndex;
        }

        private static JSONObject buildOpenAiTool(
                String exposedName,
                String serverLabel,
                JSONObject mcpTool
        ) throws JSONException {
            JSONObject function = new JSONObject();
            function.put("name", exposedName);

            String description = mcpTool.optString("description", "").trim();
            if (!serverLabel.isEmpty()) {
                description = description.isEmpty()
                        ? "Provided by MCP server: " + serverLabel
                        : description + "\n\nMCP server: " + serverLabel;
            }
            function.put("description", description);

            JSONObject parameters = mcpTool.optJSONObject("inputSchema");
            if (parameters == null) {
                parameters = new JSONObject();
                parameters.put("type", "object");
                parameters.put("properties", new JSONObject());
            }
            function.put("parameters", new JSONObject(parameters.toString()));

            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", function);
            return tool;
        }
    }

    private static final class McpSession implements Closeable {
        private final String serverLabel;
        private final String urlText;
        private final Map<String, String> headers;
        private final int requestTimeoutSeconds;
        private final Transport transport;
        private final AtomicLong requestId = new AtomicLong(1);
        private final Map<Long, ArrayBlockingQueue<JSONObject>> pendingResponses = new ConcurrentHashMap<>();

        private HttpURLConnection sseConnection;
        private BufferedReader sseReader;
        private Thread sseThread;
        private final CountDownLatch sseEndpointReady = new CountDownLatch(1);
        private volatile String sseMessageEndpoint;

        static McpSession connect(JSONObject serverConfig, String serverLabel) throws Exception {
            String urlText = serverConfig.optString("url", "").trim();
            int requestTimeoutSeconds = serverConfig.optInt("requestTimeoutSeconds", DEFAULT_REQUEST_TIMEOUT_SECONDS);
            McpSession session = new McpSession(
                    serverLabel,
                    urlText,
                    parseHeaders(serverConfig.optString("headers", "")),
                    requestTimeoutSeconds
            );
            session.initialize();
            return session;
        }

        private McpSession(
                String serverLabel,
                String urlText,
                Map<String, String> headers,
                int requestTimeoutSeconds
        ) {
            this.serverLabel = serverLabel;
            this.urlText = urlText;
            this.headers = headers;
            this.requestTimeoutSeconds = requestTimeoutSeconds > 0
                    ? requestTimeoutSeconds
                    : DEFAULT_REQUEST_TIMEOUT_SECONDS;
            this.transport = detectTransport(urlText);
        }

        JSONArray listTools() throws Exception {
            JSONObject response = sendRequest("tools/list", new JSONObject());
            Object resultValue = response.opt("result");
            if (!(resultValue instanceof JSONObject)) {
                return new JSONArray();
            }
            JSONArray tools = ((JSONObject) resultValue).optJSONArray("tools");
            return tools != null ? tools : new JSONArray();
        }

        String callTool(String originalName, String argumentsJson) throws Exception {
            JSONObject params = new JSONObject();
            params.put("name", originalName);
            JSONObject arguments = parseToolArguments(argumentsJson);
            if (arguments.length() > 0) {
                params.put("arguments", arguments);
            }
            JSONObject response = sendRequest("tools/call", params);
            Object resultValue = response.opt("result");
            if (resultValue instanceof JSONObject) {
                return formatToolResult((JSONObject) resultValue);
            }
            if (resultValue != null && resultValue != JSONObject.NULL) {
                return String.valueOf(resultValue);
            }
            return "{}";
        }

        private void initialize() throws Exception {
            if (transport == Transport.UNSUPPORTED_WEBSOCKET) {
                throw new IOException("WebSocket MCP transport is not supported");
            }

            if (transport == Transport.SSE) {
                startSseTransport();
            }

            JSONObject params = new JSONObject();
            params.put("protocolVersion", MCP_PROTOCOL_VERSION);

            JSONObject capabilities = new JSONObject();
            JSONObject toolsCapability = new JSONObject();
            toolsCapability.put("listChanged", true);
            capabilities.put("tools", toolsCapability);
            params.put("capabilities", capabilities);

            JSONObject clientInfo = new JSONObject();
            clientInfo.put("name", MCP_CLIENT_NAME);
            clientInfo.put("version", MCP_CLIENT_VERSION);
            params.put("clientInfo", clientInfo);

            sendRequest("initialize", params);
            sendNotification("notifications/initialized", new JSONObject());
        }

        private JSONObject sendRequest(String method, JSONObject params) throws Exception {
            long id = requestId.getAndIncrement();
            JSONObject payload = new JSONObject();
            payload.put("jsonrpc", JSON_RPC_VERSION);
            payload.put("id", id);
            payload.put("method", method);
            payload.put("params", params != null ? params : new JSONObject());

            JSONObject response;
            if (transport == Transport.SSE) {
                response = sendSseRequest(id, payload);
            } else {
                response = sendHttpRequest(urlText, payload, true);
            }

            JSONObject error = response.optJSONObject("error");
            if (error != null) {
                throw new IOException(serverLabel + ": " + error.optString("message", "request failed"));
            }
            return response;
        }

        private void sendNotification(String method, JSONObject params) throws Exception {
            JSONObject payload = new JSONObject();
            payload.put("jsonrpc", JSON_RPC_VERSION);
            payload.put("method", method);
            payload.put("params", params != null ? params : new JSONObject());

            if (transport == Transport.SSE) {
                sendSseNotification(payload);
            } else {
                sendHttpRequest(urlText, payload, false);
            }
        }

        private void startSseTransport() throws Exception {
            sseConnection = openConnection(urlText, "GET", DEFAULT_CONNECTION_TIMEOUT_MS, requestTimeoutSeconds * 1000);
            sseConnection.setRequestProperty("Accept", "text/event-stream");
            applyHeaders(sseConnection, headers);
            sseConnection.connect();

            int responseCode = sseConnection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("MCP SSE handshake failed with HTTP " + responseCode);
            }

            InputStream inputStream = sseConnection.getInputStream();
            sseReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            sseThread = new Thread(this::readSseLoop, "McpSse-" + sanitizeThreadName(serverLabel));
            sseThread.setDaemon(true);
            sseThread.start();

            if (!sseEndpointReady.await(DEFAULT_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("Timed out waiting for MCP SSE endpoint");
            }
        }

        private void readSseLoop() {
            String eventType = "message";
            StringBuilder data = new StringBuilder();
            try {
                String line;
                while ((line = sseReader.readLine()) != null) {
                    if (line.isEmpty()) {
                        dispatchSseEvent(eventType, data.toString());
                        eventType = "message";
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        eventType = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(line.substring("data:".length()).trim());
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "MCP SSE reader stopped for " + serverLabel, e);
            } finally {
                sseEndpointReady.countDown();
                failPendingResponses("MCP SSE connection closed");
            }
        }

        private void dispatchSseEvent(String eventType, String data) {
            if (data == null || data.isEmpty()) {
                return;
            }
            if ("endpoint".equals(eventType)) {
                try {
                    sseMessageEndpoint = resolveRelativeUrl(urlText, data.trim());
                    sseEndpointReady.countDown();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to resolve MCP SSE endpoint for " + serverLabel, e);
                    failPendingResponses("Invalid MCP SSE endpoint");
                }
                return;
            }

            try {
                Object parsed = new JSONTokener(data).nextValue();
                if (!(parsed instanceof JSONObject)) {
                    return;
                }
                JSONObject json = (JSONObject) parsed;
                if (json.has("id")) {
                    long id = json.optLong("id", -1L);
                    ArrayBlockingQueue<JSONObject> queue = pendingResponses.get(id);
                    if (queue != null) {
                        queue.offer(json);
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, "Failed to parse MCP SSE event for " + serverLabel + ": " + data, e);
            }
        }

        private JSONObject sendSseRequest(long id, JSONObject payload) throws Exception {
            if (!sseEndpointReady.await(DEFAULT_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS) || sseMessageEndpoint == null) {
                throw new IOException("MCP SSE endpoint is not ready");
            }
            ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(1);
            pendingResponses.put(id, queue);
            try {
                JSONObject immediateResponse = sendHttpRequest(sseMessageEndpoint, payload, false);
                if (immediateResponse != null && immediateResponse.has("id")) {
                    queue.offer(immediateResponse);
                }

                JSONObject response = queue.poll(requestTimeoutSeconds, TimeUnit.SECONDS);
                if (response == null) {
                    throw new IOException("Timed out waiting for MCP response");
                }
                return response;
            } finally {
                pendingResponses.remove(id);
            }
        }

        private void sendSseNotification(JSONObject payload) throws Exception {
            if (!sseEndpointReady.await(DEFAULT_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS) || sseMessageEndpoint == null) {
                throw new IOException("MCP SSE endpoint is not ready");
            }
            sendHttpRequest(sseMessageEndpoint, payload, false);
        }

        private JSONObject sendHttpRequest(
                String targetUrl,
                JSONObject payload,
                boolean expectJsonResponse
        ) throws Exception {
            HttpURLConnection connection = openConnection(
                    targetUrl,
                    "POST",
                    DEFAULT_CONNECTION_TIMEOUT_MS,
                    requestTimeoutSeconds * 1000
            );
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            applyHeaders(connection, headers);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }

            int responseCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, responseCode);
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + (responseBody.isEmpty() ? "" : ": " + responseBody));
            }
            return parseHttpResponseBody(targetUrl, responseBody, expectJsonResponse);
        }

        private static String readResponseBody(HttpURLConnection connection, int responseCode) throws IOException {
            InputStream stream = responseCode >= 200 && responseCode < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            if (stream == null) {
                return "";
            }
            try (InputStream in = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toString(StandardCharsets.UTF_8.name()).trim();
            }
        }

        private JSONObject parseHttpResponseBody(
                String targetUrl,
                String responseBody,
                boolean expectJsonResponse
        ) throws IOException, JSONException {
            if (responseBody.isEmpty()) {
                if (!expectJsonResponse) {
                    return null;
                }
                throw new IOException("Empty MCP response");
            }

            Object parsed;
            try {
                parsed = new JSONTokener(responseBody).nextValue();
            } catch (JSONException e) {
                if (!expectJsonResponse) {
                    Log.d(TAG, "Ignoring non-JSON inline MCP response from " + targetUrl + ": " + abbreviateForLog(responseBody));
                    return null;
                }
                throw e;
            }
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
            if (parsed instanceof JSONArray) {
                JSONArray responseArray = (JSONArray) parsed;
                if (responseArray.length() == 1) {
                    JSONObject singleResponse = responseArray.optJSONObject(0);
                    if (singleResponse != null) {
                        return singleResponse;
                    }
                }
                if (!expectJsonResponse) {
                    Log.d(TAG, "Ignoring non-object MCP response array from " + targetUrl);
                    return null;
                }
                throw new IOException("Unexpected MCP response array");
            }
            if (!expectJsonResponse) {
                Log.d(TAG, "Ignoring non-JSON inline MCP response from " + targetUrl + ": " + abbreviateForLog(responseBody));
                return null;
            }
            throw new IOException("Unexpected MCP response body");
        }

        private static String abbreviateForLog(String value) {
            if (value == null) {
                return "";
            }
            String trimmed = value.trim();
            if (trimmed.length() <= 120) {
                return trimmed;
            }
            return trimmed.substring(0, 117) + "...";
        }

        private void failPendingResponses(String message) {
            for (Map.Entry<Long, ArrayBlockingQueue<JSONObject>> entry : pendingResponses.entrySet()) {
                try {
                    JSONObject error = new JSONObject();
                    JSONObject errorBody = new JSONObject();
                    errorBody.put("message", message);
                    error.put("error", errorBody);
                    entry.getValue().offer(error);
                } catch (JSONException ignored) {
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (sseConnection != null) {
                sseConnection.disconnect();
            }
            if (sseReader != null) {
                sseReader.close();
            }
        }

        private static JSONObject parseToolArguments(String argumentsJson) throws JSONException {
            if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
                return new JSONObject();
            }
            Object parsed = new JSONTokener(argumentsJson).nextValue();
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
            throw new JSONException("Tool arguments must be a JSON object");
        }

        private static String formatToolResult(JSONObject result) throws JSONException {
            if (result.has("toolResult") && !result.isNull("toolResult")) {
                return String.valueOf(result.get("toolResult"));
            }

            JSONArray content = result.optJSONArray("content");
            String textContent = extractToolText(content);
            JSONObject structuredContent = result.optJSONObject("structuredContent");
            boolean isError = result.optBoolean("isError", false);

            if (structuredContent == null && !isError) {
                return textContent.isEmpty() ? "{}" : textContent;
            }

            JSONObject payload = new JSONObject();
            if (!textContent.isEmpty()) {
                payload.put("content", textContent);
            }
            if (structuredContent != null) {
                payload.put("structuredContent", structuredContent);
            }
            if (isError) {
                payload.put("is_error", true);
            }
            return payload.length() == 0 ? "{}" : payload.toString();
        }

        private static String extractToolText(JSONArray content) throws JSONException {
            if (content == null || content.length() == 0) {
                return "";
            }

            StringBuilder text = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                Object rawPart = content.get(i);
                if (!(rawPart instanceof JSONObject)) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(String.valueOf(rawPart));
                    continue;
                }

                JSONObject part = (JSONObject) rawPart;
                String type = part.optString("type", "");
                String piece;
                if ("text".equals(type)) {
                    piece = part.optString("text", "");
                } else {
                    piece = part.toString();
                }
                if (piece.isEmpty()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(piece);
            }
            return text.toString();
        }

        private static Transport detectTransport(String urlText) {
            String normalized = urlText.toLowerCase(Locale.US);
            if (normalized.startsWith("ws://") || normalized.startsWith("wss://")) {
                return Transport.UNSUPPORTED_WEBSOCKET;
            }
            if (normalized.contains("/sse") || normalized.contains("transport=sse")) {
                return Transport.SSE;
            }
            return Transport.HTTP;
        }

        private static HttpURLConnection openConnection(
                String targetUrl,
                String method,
                int connectTimeoutMs,
                int readTimeoutMs
        ) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            return connection;
        }

        private static void applyHeaders(HttpURLConnection connection, Map<String, String> headers) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        private static Map<String, String> parseHeaders(String rawHeaders) throws JSONException {
            Map<String, String> headers = new LinkedHashMap<>();
            if (rawHeaders == null || rawHeaders.trim().isEmpty()) {
                return headers;
            }
            JSONObject json = new JSONObject(rawHeaders);
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = json.optString(key, null);
                if (value != null) {
                    headers.put(key, value);
                }
            }
            return headers;
        }

        private static String resolveRelativeUrl(String baseUrl, String endpoint) {
            return URI.create(baseUrl).resolve(endpoint).toString();
        }

        private static String sanitizeThreadName(String value) {
            if (value == null || value.isEmpty()) {
                return "mcp";
            }
            return value.replaceAll("[^A-Za-z0-9_-]", "_");
        }
    }

    private enum Transport {
        HTTP,
        SSE,
        UNSUPPORTED_WEBSOCKET
    }

    private static String buildErrorToolContent(String message) {
        String safeMessage = message == null || message.trim().isEmpty()
                ? "unknown error"
                : message;
        try {
            JSONObject error = new JSONObject();
            error.put("is_error", true);
            error.put("message", safeMessage);
            return error.toString();
        } catch (JSONException e) {
            return "{\"is_error\":true,\"message\":\"unknown error\"}";
        }
    }

    private static final class IOExceptionWrappedException extends RuntimeException {
        private IOExceptionWrappedException(String message) {
            super(message);
        }
    }
}
