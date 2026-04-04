package com.micklab.llama;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ollama-compatible API server that provides /api/chat and /api/generate endpoints.
 * Uses registered Configurations as model names.
 * Uses ModelManager for unified model management with busy state.
 */
public class OllamaApiServer {
    private static final String TAG = "OllamaApiServer";
    public static final int DEFAULT_PORT = 11434;
    private static final String PREFS_NAME = "ollama_prefs";
    private static final String PREF_LOG_LEVEL = "log_level";
    private static final int LOG_LEVEL_MAX_DEBUG = 0;
    private static final int MAX_BUSY_QUEUE_SIZE = 10;
    private static final long BUSY_QUEUE_WAIT_MS = 60_000L;
    private static final long BUSY_QUEUE_POLL_INTERVAL_MS = 100L;
    
    private final Context context;
    private final ConfigurationManager configManager;
    private final ModelManager modelManager;
    
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private static final Object TOKEN_COMPLETE = new Object();
    
    // Track active client connections for disconnectAll
    private final java.util.Set<Socket> activeConnections = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final AtomicInteger waitingRequestCount = new AtomicInteger(0);

    private static class TokenError {
        final String error;
        TokenError(String error) {
            if (error == null) {
                this.error = "unknown error";
            } else {
                String trimmed = error.trim();
                this.error = trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? "unknown error" : error;
            }
        }
    }
    
    private static String stripResponseMarkers(String text) {
        return ResponseMarkerSanitizer.stripResponseMarkers(text);
    }

    private static class StreamTokenFilter {
        private static class ParseResult {
            final String output;
            final String remaining;
            final boolean endMarkerDetected;

            ParseResult(String output, String remaining, boolean endMarkerDetected) {
                this.output = output;
                this.remaining = remaining;
                this.endMarkerDetected = endMarkerDetected;
            }
        }

        private final java.util.concurrent.LinkedBlockingQueue<Object> tokenQueue;
        private final Runnable onEndMarkerDetected;
        private final StringBuilder pending = new StringBuilder();
        private final AtomicBoolean completionQueued = new AtomicBoolean(false);
        private boolean finishedByEndMarker = false;

        StreamTokenFilter(
                java.util.concurrent.LinkedBlockingQueue<Object> tokenQueue,
                Runnable onEndMarkerDetected
        ) {
            this.tokenQueue = tokenQueue;
            this.onEndMarkerDetected = onEndMarkerDetected;
        }

        void onToken(String token) {
            if (token == null || token.isEmpty() || finishedByEndMarker) {
                return;
            }
            pending.append(token);
            flushFiltered(false);
        }

        void onComplete() {
            if (finishedByEndMarker) {
                queueCompleteOnce();
                return;
            }
            flushFiltered(true);
            if (!finishedByEndMarker) {
                queueCompleteOnce();
            }
        }

        void onError(String error) {
            if (finishedByEndMarker) {
                queueCompleteOnce();
                return;
            }
            flushFiltered(true);
            if (finishedByEndMarker) {
                return;
            }
            tokenQueue.offer(new TokenError(error));
        }

        private void flushFiltered(boolean flushAll) {
            if (pending.length() == 0 || finishedByEndMarker) {
                return;
            }
            ParseResult parsed = parsePending(pending.toString(), flushAll);
            pending.setLength(0);
            pending.append(parsed.remaining);
            if (!parsed.output.isEmpty()) {
                tokenQueue.offer(parsed.output);
            }
            if (parsed.endMarkerDetected) {
                finishedByEndMarker = true;
                pending.setLength(0);
                if (onEndMarkerDetected != null) {
                    onEndMarkerDetected.run();
                }
                queueCompleteOnce();
            }
        }

        private void queueCompleteOnce() {
            if (completionQueued.compareAndSet(false, true)) {
                tokenQueue.offer(TOKEN_COMPLETE);
            }
        }

        private ParseResult parsePending(String text, boolean flushAll) {
            StringBuilder output = new StringBuilder();
            int i = 0;

            while (i < text.length()) {
                int markerPos = ResponseMarkerSanitizer.findNextMarkerStart(text, i);
                if (markerPos < 0) {
                    if (flushAll) {
                        output.append(text, i, text.length());
                        return new ParseResult(output.toString(), "", false);
                    }
                    int keepFrom = text.length();
                    if (text.length() - i >= 2
                            && text.charAt(text.length() - 2) == '<'
                            && text.charAt(text.length() - 1) == '|') {
                        keepFrom = text.length() - 2;
                    } else if (text.length() - i >= 1 && text.charAt(text.length() - 1) == '<') {
                        keepFrom = text.length() - 1;
                    }
                    if (keepFrom > i) {
                        output.append(text, i, keepFrom);
                    }
                    return new ParseResult(output.toString(), text.substring(keepFrom), false);
                }

                if (markerPos > i) {
                    output.append(text, i, markerPos);
                }

                String tail = text.substring(markerPos);
                String tailLower = tail.toLowerCase(Locale.ROOT);

                boolean matchesStart = ResponseMarkerSanitizer.startsWithImStartMarker(tailLower);
                boolean matchesImEnd = ResponseMarkerSanitizer.startsWithImEndMarker(tailLower);
                boolean matchesGenericEnd = ResponseMarkerSanitizer.startsWithGenericEndMarker(tailLower);

                if (!matchesStart && !matchesImEnd && !matchesGenericEnd) {
                    if (ResponseMarkerSanitizer.isPossibleMarkerPrefix(tailLower)) {
                        if (flushAll) {
                            return new ParseResult(output.toString(), "", false);
                        }
                        return new ParseResult(output.toString(), tail, false);
                    }
                    output.append(text, markerPos, markerPos + 2);
                    i = markerPos + 2;
                    continue;
                }

                if (matchesGenericEnd) {
                    return new ParseResult(output.toString(), "", true);
                }

                int closePos = tailLower.indexOf("|>");
                if (closePos < 0) {
                    if (flushAll) {
                        return new ParseResult(output.toString(), "", matchesImEnd);
                    }
                    return new ParseResult(output.toString(), tail, false);
                }

                i = markerPos + closePos + 2;
                if (matchesImEnd) {
                    return new ParseResult(output.toString(), "", true);
                }
            }

            return new ParseResult(output.toString(), "", false);
        }
    }

    private int port = DEFAULT_PORT;
    
    public interface ServerListener {
        void onServerStarted(int port);
        void onServerStopped();
        void onServerError(String error);
        void onRequest(String method, String path);
        void onModelLoading(String configName);
        void onModelLoaded(String configName);
        void onGenerating(String configName);
    }
    
    private ServerListener listener;
    
    public OllamaApiServer(Context context, ModelManager modelManager) {
        this.context = context;
        this.configManager = new ConfigurationManager(context);
        this.modelManager = modelManager;
    }
    
    public void setListener(ServerListener listener) {
        this.listener = listener;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public int getPort() {
        return port;
    }
    
    public boolean isRunning() {
        return running.get();
    }
    
    public void start() {
        if (running.get()) {
            Log.w(TAG, "Server already running");
            return;
        }
        
        executorService = Executors.newCachedThreadPool();
        executorService.submit(() -> {
            try {
                // Bind to 0.0.0.0 to accept external connections
                serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
                running.set(true);
                Log.i(TAG, "Llama API server started on port " + port + " (0.0.0.0)");
                
                if (listener != null) {
                    listener.onServerStarted(port);
                }
                
                while (running.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        activeConnections.add(clientSocket);
                        executorService.submit(() -> handleClient(clientSocket));
                    } catch (IOException e) {
                        if (running.get()) {
                            Log.e(TAG, "Error accepting connection", e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to start server", e);
                if (listener != null) {
                    listener.onServerError("Failed to start server: " + e.getMessage());
                }
            }
        });
    }
    
    public void stop() {
        running.set(false);
        
        // Close all active client connections
        disconnectAll();
        
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        Log.i(TAG, "Llama API server stopped");
        if (listener != null) {
            listener.onServerStopped();
        }
    }
    
    /**
     * Disconnect all active client connections.
     * This is used when reinitializing the model to stop ongoing requests.
     */
    public void disconnectAll() {
        synchronized (activeConnections) {
            for (Socket socket : activeConnections) {
                try {
                    if (!socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Error closing client socket", e);
                }
            }
            activeConnections.clear();
        }
        Log.i(TAG, "Disconnected all active connections");
    }
    
    private void handleClient(Socket clientSocket) {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream outputStream = clientSocket.getOutputStream();
            
            // Read request line
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                clientSocket.close();
                return;
            }
            
            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2) {
                sendErrorResponse(outputStream, 400, "Bad Request");
                clientSocket.close();
                return;
            }
            
            String method = requestParts[0];
            String path = requestParts[1];
            
            Log.d(TAG, "Request: " + method + " " + path);
            if (listener != null) {
                listener.onRequest(method, path);
            }
            
            // Read headers
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            int contentLength = 0;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                int colonIndex = headerLine.indexOf(':');
                if (colonIndex > 0) {
                    String key = headerLine.substring(0, colonIndex).trim().toLowerCase();
                    String value = headerLine.substring(colonIndex + 1).trim();
                    headers.put(key, value);
                    if ("content-length".equals(key)) {
                        contentLength = Integer.parseInt(value);
                    }
                }
            }
            
            // Read body
            String body = "";
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int read = reader.read(bodyChars, 0, contentLength);
                body = new String(bodyChars, 0, read);
            }

            Log.d(TAG, "Raw request body:\n" + body);
            logMaxDebugPayload("api.request.body", body);
            
            // Route request
            if ("POST".equals(method)) {
                if ("/api/generate".equals(path)) {
                    handleGenerate(outputStream, body);
                } else if ("/api/chat".equals(path)) {
                    handleChat(outputStream, body);
                } else if ("/api/tags".equals(path) || "/api/tags/".equals(path)) {
                    handleTags(outputStream);
                } else {
                    sendErrorResponse(outputStream, 404, "Not Found");
                }
            } else if ("GET".equals(method)) {
                if ("/api/tags".equals(path) || "/api/tags/".equals(path)) {
                    handleTags(outputStream);
                } else if ("/".equals(path) || "/api".equals(path)) {
                    sendJsonResponse(outputStream, 200, "{\"status\":\"Ollama is running\"}");
                } else {
                    sendErrorResponse(outputStream, 404, "Not Found");
                }
            } else if ("OPTIONS".equals(method)) {
                handleCors(outputStream);
            } else {
                sendErrorResponse(outputStream, 405, "Method Not Allowed");
            }
            
            clientSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
            try {
                clientSocket.close();
            } catch (IOException ignored) {}
        } finally {
            activeConnections.remove(clientSocket);
        }
    }
    
    private void handleGenerate(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = request.optString("model", "default");
            String prompt = request.optString("prompt", "");
            String apiSystem = request.optString("system", null); // Optional system from API
            boolean stream = request.optBoolean("stream", true);
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "generate request model=" + model + " stream=" + stream + " promptLen=" + prompt.length());
            }
            
            if (!acquireGenerationSlot(outputStream, "/api/generate")) {
                return;
            }
            
            try {
                // Load model/configuration if needed (will be fast if same config already loaded)
                if (!modelManager.loadConfiguration(model)) {
                    sendErrorResponse(outputStream, 500, "Failed to load configuration: " + model);
                    return;
                }
                
                ConfigurationManager.Configuration config = null;
                try {
                    config = configManager.loadConfiguration(model);
                } catch (Exception e) {
                    Log.w(TAG, "Could not load config for template", e);
                }
                
                if (listener != null) {
                    listener.onGenerating(model);
                }
                
                // Use PromptTemplateManager for prompt generation
                String ggufChatTemplate = modelManager.getLlama().getChatTemplate();
                String customTemplate = (config != null) ? config.customChatTemplate : null;
                String settingsSystemPrompt = (config != null) ? config.systemPrompt : null;
                boolean enableThinking = config == null || config.enableThinking;
                String modelPath = modelManager.getCurrentModelPath();
                
                PromptTemplateManager.PromptBuildResult promptResult =
                        PromptTemplateManager.buildPromptForGenerateWithSelection(
                                prompt,
                                apiSystem,
                                customTemplate,
                                ggufChatTemplate,
                                settingsSystemPrompt,
                                modelPath,
                                enableThinking);
                logTemplateSelection("generate", promptResult.selection);
                String promptToUse = promptResult.prompt;
                logMaxDebugPayload("api.generate.prompt", promptToUse);

                if (stream) {
                    final boolean[] errorSent = { false };
                    final AtomicBoolean clientDisconnected = new AtomicBoolean(false);
                    final Runnable onClientDisconnected = () -> {
                        if (clientDisconnected.compareAndSet(false, true)) {
                            Log.w(TAG, "Client disconnected during /api/generate stream");
                            modelManager.getLlama().cancelGeneration();
                        }
                    };
                    // Start chunked response
                    String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/x-ndjson\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "\r\n";
                    outputStream.write(header.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();

                    final Object writeLock = new Object();
                    // Use a queue + writer thread so the native generation thread is never blocked by network I/O
                    final java.util.concurrent.LinkedBlockingQueue<Object> tokenQueue = new java.util.concurrent.LinkedBlockingQueue<>();
                    final StreamTokenFilter streamTokenFilter = new StreamTokenFilter(
                            tokenQueue,
                            () -> modelManager.getLlama().cancelGeneration()
                    );
                    final Thread writerThread = new Thread(() -> {
                        try {
                            while (true) {
                                Object ev = tokenQueue.take();
                                if (ev == TOKEN_COMPLETE) {
                                    if (errorSent[0]) {
                                        break;
                                    }
                                    try {
                                        JSONObject chunk = new JSONObject();
                                        chunk.put("model", model);
                                        chunk.put("created_at", getTimestamp());
                                        chunk.put("response", "");
                                        chunk.put("done", true);
                                        byte[] chunkBytes = (chunk.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                                        synchronized (writeLock) {
                                            String chunkSize = Integer.toHexString(chunkBytes.length) + "\r\n";
                                            outputStream.write(chunkSize.getBytes(StandardCharsets.UTF_8));
                                            outputStream.write(chunkBytes);
                                             outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                                             outputStream.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                                             outputStream.flush();
                                         }
                                     } catch (Exception e) {
                                        Log.w(TAG, "Error writing final streaming chunk", e);
                                        onClientDisconnected.run();
                                     }
                                     break;
                                 } else if (ev instanceof TokenError) {
                                     TokenError te = (TokenError) ev;
                                     try {
                                        errorSent[0] = true;
                                        JSONObject err = new JSONObject();
                                        err.put("error", te.error);
                                        byte[] chunkBytes = (err.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                                        synchronized (writeLock) {
                                            String chunkSize = Integer.toHexString(chunkBytes.length) + "\r\n";
                                            outputStream.write(chunkSize.getBytes(StandardCharsets.UTF_8));
                                            outputStream.write(chunkBytes);
                                             outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                                             outputStream.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                                             outputStream.flush();
                                         }
                                     } catch (Exception e) {
                                        Log.w(TAG, "Error writing error chunk", e);
                                        onClientDisconnected.run();
                                     }
                                     break;
                                 } else {
                                     String tokenStr = (String) ev;
                                     try {
                                        JSONObject chunk = new JSONObject();
                                        chunk.put("model", model);
                                        chunk.put("created_at", getTimestamp());
                                        String safeToken = stripResponseMarkers(tokenStr);
                                        chunk.put("response", safeToken);
                                        chunk.put("done", false);
                                        logMaxDebugPayload("api.generate.stream.chunk.response", safeToken);
                                        byte[] chunkBytes = (chunk.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                                        synchronized (writeLock) {
                                            String chunkSize = Integer.toHexString(chunkBytes.length) + "\r\n";
                                            outputStream.write(chunkSize.getBytes(StandardCharsets.UTF_8));
                                            outputStream.write(chunkBytes);
                                             outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                                             outputStream.flush();
                                         }
                                     } catch (Exception e) {
                                        Log.w(TAG, "Error writing streaming chunk", e);
                                        onClientDisconnected.run();
                                        break;
                                     }
                                 }
                             }
                         } catch (InterruptedException ie) {
                             Log.w(TAG, "Writer thread interrupted", ie);
                            Thread.currentThread().interrupt();
                        } finally {
                            try { outputStream.flush(); } catch (Exception ignored) {}
                        }
                    }, "OllamaApiWriter-" + Thread.currentThread().getId());
                    writerThread.start();

                    modelManager.getLlama().setTokenListener(new LlamaNative.TokenListener() {
                        private int tokenCount = 0;

                        @Override
                        public void onToken(String token) {
                            tokenCount++;
                            if (BuildConfig.DEBUG && (tokenCount % 50 == 0)) {
                                Log.d(TAG, "generate stream tokens=" + tokenCount);
                            }
                            logMaxDebugPayload("api.generate.stream.model.token", token);
                            // Fast, non-blocking enqueue so native thread isn't blocked
                            streamTokenFilter.onToken(token);
                        }

                        @Override
                        public void onComplete() {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "generate stream complete tokens=" + tokenCount);
                            }
                            streamTokenFilter.onComplete();
                        }

                        @Override
                        public void onError(String error) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "generate stream error: " + error);
                            }
                            streamTokenFilter.onError(error);
                        }
                    });

                    try {
                        // This will trigger token callbacks
                        modelManager.generate(promptToUse);
                    } finally {
                        modelManager.getLlama().setTokenListener(null);
                        if (!errorSent[0] && !clientDisconnected.get()) {
                            tokenQueue.offer(TOKEN_COMPLETE);
                        }
                    }
                    try {
                        writerThread.join(5000);
                        if (writerThread.isAlive()) {
                            Log.w(TAG, "Writer thread did not finish before timeout (generate)");
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "Writer thread join interrupted (generate)", ie);
                    }
                } else {
                    // Non-streaming response
                    String rawResponse = modelManager.generate(promptToUse);
                    logMaxDebugPayload("api.generate.nonstream.model.raw", rawResponse);
                    String response = stripResponseMarkers(rawResponse);
                    logMaxDebugPayload("api.generate.nonstream.response", response);
                    JSONObject result = new JSONObject();
                    result.put("model", model);
                    result.put("created_at", getTimestamp());
                    result.put("response", response);
                    result.put("done", true);
                    logMaxDebugPayload("api.generate.nonstream.response.json", result.toString());

                    sendJsonResponse(outputStream, 200, result.toString());
                }
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid JSON in generate request", e);
            sendErrorResponse(outputStream, 400, "Invalid JSON: " + e.getMessage());
        }
    }
    
    private void handleChat(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = request.optString("model", "default");
            JSONArray messages = request.optJSONArray("messages");
            boolean stream = request.optBoolean("stream", true);
            
            if (messages == null || messages.length() == 0) {
                sendErrorResponse(outputStream, 400, "No messages provided");
                return;
            }
            
            if (!acquireGenerationSlot(outputStream, "/api/chat")) {
                return;
            }
            
            try {
                // Load model/configuration if needed (will be fast if same config already loaded)
                if (!modelManager.loadConfiguration(model)) {
                    sendErrorResponse(outputStream, 500, "Failed to load configuration: " + model);
                    return;
                }
                
                if (listener != null) {
                    listener.onGenerating(model);
                }
                
                // Get configuration for prompt template settings
                ConfigurationManager.Configuration config = null;
                try {
                    config = configManager.loadConfiguration(model);
                } catch (Exception e) {
                    Log.w(TAG, "Could not load config for template", e);
                }
                
                // Use PromptTemplateManager for prompt generation
                String ggufChatTemplate = modelManager.getLlama().getChatTemplate();
                String customTemplate = (config != null) ? config.customChatTemplate : null;
                String settingsSystemPrompt = (config != null) ? config.systemPrompt : null;
                boolean enableThinking = config == null || config.enableThinking;
                String modelPath = modelManager.getCurrentModelPath();
                
                PromptTemplateManager.PromptBuildResult promptResult =
                        PromptTemplateManager.buildPromptFromMessagesWithSelection(
                                messages,
                                customTemplate,
                                ggufChatTemplate,
                                settingsSystemPrompt,
                                modelPath,
                                enableThinking);
                logTemplateSelection("chat", promptResult.selection);
                String promptToUse = promptResult.prompt;
                logMaxDebugPayload("api.chat.prompt", promptToUse);

                if (stream) {
                    final boolean[] errorSent = { false };
                    final AtomicBoolean clientDisconnected = new AtomicBoolean(false);
                    final Runnable onClientDisconnected = () -> {
                        if (clientDisconnected.compareAndSet(false, true)) {
                            Log.w(TAG, "Client disconnected during /api/chat stream");
                            modelManager.getLlama().cancelGeneration();
                        }
                    };
                    // Start chunked response
                    String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/x-ndjson\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "\r\n";
                    outputStream.write(header.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();

                    final Object writeLock = new Object();
                    // Use a queue + writer thread so the native generation thread is never blocked by network I/O
                    final java.util.concurrent.LinkedBlockingQueue<Object> tokenQueue = new java.util.concurrent.LinkedBlockingQueue<>();
                    final StreamTokenFilter streamTokenFilter = new StreamTokenFilter(
                            tokenQueue,
                            () -> modelManager.getLlama().cancelGeneration()
                    );
                    final Thread writerThread = new Thread(() -> {
                        try {
                            while (true) {
                                Object ev = tokenQueue.take();
                                if (ev == TOKEN_COMPLETE) {
                                    if (errorSent[0]) {
                                        break;
                                    }
                                    try {
                                        JSONObject chunk = new JSONObject();
                                        chunk.put("model", model);
                                        chunk.put("created_at", getTimestamp());

                                        JSONObject message = new JSONObject();
                                        message.put("role", "assistant");
                                        message.put("content", "");
                                        chunk.put("message", message);
                                        chunk.put("done", true);

                                        byte[] chunkBytes = (chunk.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                                        synchronized (writeLock) {
                                            String chunkSize = Integer.toHexString(chunkBytes.length) + "\r\n";
                                            outputStream.write(chunkSize.getBytes(StandardCharsets.UTF_8));
                                            outputStream.write(chunkBytes);
                                             outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                                             outputStream.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                                             outputStream.flush();
                                         }
                                     } catch (Exception e) {
                                        Log.w(TAG, "Error writing final streaming chunk", e);
                                        onClientDisconnected.run();
                                     }
                                     break;
                                 } else if (ev instanceof TokenError) {
                                     TokenError te = (TokenError) ev;
                                     try {
                                        errorSent[0] = true;
                                        JSONObject err = new JSONObject();
                                        err.put("error", te.error);
                                        byte[] chunkBytes = (err.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                                        synchronized (writeLock) {
                                            String chunkSize = Integer.toHexString(chunkBytes.length) + "\r\n";
                                            outputStream.write(chunkSize.getBytes(StandardCharsets.UTF_8));
                                            outputStream.write(chunkBytes);
                                             outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                                             outputStream.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                                             outputStream.flush();
                                         }
                                     } catch (Exception e) {
                                        Log.w(TAG, "Error writing error chunk", e);
                                        onClientDisconnected.run();
                                     }
                                     break;
                                 } else {
                                     String tokenStr = (String) ev;
                                     try {
                                        JSONObject chunk = new JSONObject();
                                        chunk.put("model", model);
                                        chunk.put("created_at", getTimestamp());

                                        JSONObject message = new JSONObject();
                                        message.put("role", "assistant");
                                        String safeToken = stripResponseMarkers(tokenStr);
                                        message.put("content", safeToken);
                                        chunk.put("message", message);
                                        chunk.put("done", false);
                                        logMaxDebugPayload("api.chat.stream.chunk.content", safeToken);

                                        byte[] chunkBytes = (chunk.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                                        synchronized (writeLock) {
                                            String chunkSize = Integer.toHexString(chunkBytes.length) + "\r\n";
                                            outputStream.write(chunkSize.getBytes(StandardCharsets.UTF_8));
                                            outputStream.write(chunkBytes);
                                             outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                                             outputStream.flush();
                                         }
                                     } catch (Exception e) {
                                        Log.w(TAG, "Error writing streaming chunk", e);
                                        onClientDisconnected.run();
                                        break;
                                     }
                                 }
                             }
                         } catch (InterruptedException ie) {
                             Log.w(TAG, "Writer thread interrupted", ie);
                            Thread.currentThread().interrupt();
                        } finally {
                            try { outputStream.flush(); } catch (Exception ignored) {}
                        }
                    }, "OllamaApiWriter-" + Thread.currentThread().getId());
                    writerThread.start();

                    modelManager.getLlama().setTokenListener(new LlamaNative.TokenListener() {
                        private int tokenCount = 0;

                        @Override
                        public void onToken(String token) {
                            tokenCount++;
                            if (BuildConfig.DEBUG && (tokenCount % 50 == 0)) {
                                Log.d(TAG, "chat stream tokens=" + tokenCount);
                            }
                            logMaxDebugPayload("api.chat.stream.model.token", token);
                            streamTokenFilter.onToken(token);
                        }

                        @Override
                        public void onComplete() {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "chat stream complete tokens=" + tokenCount);
                            }
                            streamTokenFilter.onComplete();
                        }

                        @Override
                        public void onError(String error) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "chat stream error: " + error);
                            }
                            streamTokenFilter.onError(error);
                        }
                    });

                    try {
                        modelManager.generate(promptToUse);
                    } finally {
                        modelManager.getLlama().setTokenListener(null);
                        if (!errorSent[0] && !clientDisconnected.get()) {
                            tokenQueue.offer(TOKEN_COMPLETE);
                        }
                    }
                    try {
                        writerThread.join(5000);
                        if (writerThread.isAlive()) {
                            Log.w(TAG, "Writer thread did not finish before timeout (chat)");
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "Writer thread join interrupted (chat)", ie);
                    }
                } else {
                    // Non-streaming response
                    String rawResponse = modelManager.generate(promptToUse);
                    logMaxDebugPayload("api.chat.nonstream.model.raw", rawResponse);
                    String response = stripResponseMarkers(rawResponse);
                    logMaxDebugPayload("api.chat.nonstream.response", response);

                    JSONObject result = new JSONObject();
                    result.put("model", model);
                    result.put("created_at", getTimestamp());

                    JSONObject message = new JSONObject();
                    message.put("role", "assistant");
                    message.put("content", response);
                    result.put("message", message);
                    result.put("done", true);
                    logMaxDebugPayload("api.chat.nonstream.response.json", result.toString());

                    sendJsonResponse(outputStream, 200, result.toString());
                }
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid JSON in chat request", e);
            sendErrorResponse(outputStream, 400, "Invalid JSON: " + e.getMessage());
        }
    }

    private boolean acquireGenerationSlot(OutputStream outputStream, String endpoint) throws IOException {
        if (modelManager.tryAcquire()) {
            return true;
        }

        int queued = waitingRequestCount.incrementAndGet();
        if (queued > MAX_BUSY_QUEUE_SIZE) {
            waitingRequestCount.decrementAndGet();
            Log.w(TAG, "Busy queue full for " + endpoint + " queued=" + queued);
            sendErrorResponse(outputStream, 503, "Model busy queue is full (max 10)");
            return false;
        }

        long deadline = System.currentTimeMillis() + BUSY_QUEUE_WAIT_MS;
        boolean acquired = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (modelManager.tryAcquire()) {
                    acquired = true;
                    break;
                }
                try {
                    Thread.sleep(BUSY_QUEUE_POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            waitingRequestCount.decrementAndGet();
        }

        if (!acquired) {
            Log.w(TAG, "Busy queue timeout for " + endpoint + " waitedMs=" + BUSY_QUEUE_WAIT_MS);
            sendErrorResponse(outputStream, 503, "Model is busy; queue wait timed out after 60 seconds");
            return false;
        }

        return true;
    }
    
    private void handleTags(OutputStream outputStream) throws IOException {
        try {
            List<String> configs = configManager.listConfigurations();
            JSONArray models = new JSONArray();
            
            for (String configName : configs) {
                JSONObject model = new JSONObject();
                model.put("name", configName);
                model.put("model", configName);
                model.put("modified_at", getTimestamp());
                model.put("size", 0);
                
                JSONObject details = new JSONObject();
                details.put("format", "gguf");
                details.put("family", "llama");
                details.put("parameter_size", "unknown");
                details.put("quantization_level", "unknown");
                model.put("details", details);
                
                models.put(model);
            }
            
            JSONObject response = new JSONObject();
            response.put("models", models);
            
            sendJsonResponse(outputStream, 200, response.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error building tags response", e);
            sendErrorResponse(outputStream, 500, "Internal Server Error");
        }
    }

    private void logTemplateSelection(String contextLabel, PromptTemplateManager.TemplateSelectionResult selection) {
        if (selection == null) {
            return;
        }
        String message = "Prompt template selection (" + contextLabel + "): " + selection.reason;
        Log.i(TAG, message);
        sendProcessingLog(message);
    }

    private boolean isMaxDebugEnabled() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int defaultLogLevel = 2;
        return prefs.getInt(PREF_LOG_LEVEL, defaultLogLevel) == LOG_LEVEL_MAX_DEBUG;
    }

    private void logMaxDebugPayload(String label, String payload) {
        if (!isMaxDebugEnabled()) {
            return;
        }
        String safe = payload == null ? "null" : payload;
        if (safe.isEmpty()) {
            String msg = "[MAX_DEBUG] " + label + " (empty)";
            Log.d(TAG, msg);
            sendProcessingLog(msg);
            return;
        }
        final int chunkSize = 1800;
        int index = 0;
        for (int start = 0; start < safe.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, safe.length());
            String msg = "[MAX_DEBUG] " + label + " part=" + index + " \"" + safe.substring(start, end) + "\"";
            Log.d(TAG, msg);
            sendProcessingLog(msg);
            index++;
        }
    }

    private void sendProcessingLog(String message) {
        Intent intent = new Intent(OllamaForegroundService.ACTION_LOG);
        intent.putExtra(OllamaForegroundService.EXTRA_LOG_MESSAGE, message);
        context.sendBroadcast(intent);
    }
    
    private void handleCors(OutputStream outputStream) throws IOException {
        String response = "HTTP/1.1 204 No Content\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Access-Control-Max-Age: 86400\r\n" +
            "\r\n";
        outputStream.write(response.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }
    
    private void sendJsonResponse(OutputStream outputStream, int statusCode, String body) throws IOException {
        String status = statusCode == 200 ? "OK" : (statusCode == 400 ? "Bad Request" : 
                        (statusCode == 404 ? "Not Found" : (statusCode == 503 ? "Service Unavailable" : "Error")));
        
        String response = "HTTP/1.1 " + statusCode + " " + status + "\r\n" +
            "Content-Type: application/json\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
            "\r\n" +
            body;
        
        outputStream.write(response.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }
    
    private void sendStreamingResponse(OutputStream outputStream, String body) throws IOException {
        String response = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/x-ndjson\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n";
        
        outputStream.write(response.getBytes(StandardCharsets.UTF_8));
        
        // Write chunk
        byte[] chunk = (body + "\n").getBytes(StandardCharsets.UTF_8);
        String chunkSize = Integer.toHexString(chunk.length) + "\r\n";
        outputStream.write(chunkSize.getBytes(StandardCharsets.UTF_8));
        outputStream.write(chunk);
        outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
        
        // End chunk
        outputStream.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }
    
    private void sendErrorResponse(OutputStream outputStream, int statusCode, String message) throws IOException {
        try {
            JSONObject error = new JSONObject();
            error.put("error", message);
            sendJsonResponse(outputStream, statusCode, error.toString());
        } catch (JSONException e) {
            sendJsonResponse(outputStream, statusCode, "{\"error\":\"" + message + "\"}");
        }
    }
    
    private String getTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }
}
