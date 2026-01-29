package com.example.ollama;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ollama-compatible API server that provides /api/chat and /api/generate endpoints.
 * Uses registered Configurations as model names.
 * Uses ModelManager for unified model management with busy state.
 */
public class OllamaApiServer {
    private static final String TAG = "OllamaApiServer";
    public static final int DEFAULT_PORT = 11434;
    
    private final Context context;
    private final ConfigurationManager configManager;
    private final ModelManager modelManager;
    
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
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
                serverSocket = new ServerSocket(port);
                running.set(true);
                Log.i(TAG, "Ollama API server started on port " + port);
                
                if (listener != null) {
                    listener.onServerStarted(port);
                }
                
                // Preload default configuration in background
                executorService.submit(() -> {
                    if (modelManager.tryAcquire()) {
                        try {
                            if (modelManager.loadConfiguration("default")) {
                                Log.i(TAG, "Preloaded default configuration");
                            } else {
                                Log.w(TAG, "Preload default configuration failed");
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Preload exception", e);
                        } finally {
                            modelManager.release();
                        }
                    }
                });
                
                while (running.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
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
        
        Log.i(TAG, "Ollama API server stopped");
        if (listener != null) {
            listener.onServerStopped();
        }
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
        }
    }
    
    private void handleGenerate(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = request.optString("model", "default");
            String prompt = request.optString("prompt", "");
            boolean stream = request.optBoolean("stream", true);
            
            // Try to acquire busy lock - return 503 if busy
            if (!modelManager.tryAcquire()) {
                Log.w(TAG, "Model is busy, rejecting request");
                sendErrorResponse(outputStream, 503, "Model is busy processing another request");
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
                
                String promptToUse = applyPromptTemplate(prompt, config);
                // Generate directly - same code path as UI
                String response = modelManager.generate(promptToUse);
                
                if (stream) {
                    // Streaming response (single chunk for simplicity)
                    JSONObject chunk = new JSONObject();
                    chunk.put("model", model);
                    chunk.put("created_at", getTimestamp());
                    chunk.put("response", response);
                    chunk.put("done", true);
                    
                    sendStreamingResponse(outputStream, chunk.toString());
                } else {
                    // Non-streaming response
                    JSONObject result = new JSONObject();
                    result.put("model", model);
                    result.put("created_at", getTimestamp());
                    result.put("response", response);
                    result.put("done", true);
                    
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
            
            // Try to acquire busy lock - return 503 if busy
            if (!modelManager.tryAcquire()) {
                Log.w(TAG, "Model is busy, rejecting request");
                sendErrorResponse(outputStream, 503, "Model is busy processing another request");
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
                
                // Build prompt from messages (already applies template if available)
                String promptToUse = buildPromptFromMessages(messages, model);

                // Generate directly - same code path as UI
                String response = modelManager.generate(promptToUse);

                if (stream) {

                    // Streaming response
                    JSONObject chunk = new JSONObject();
                    chunk.put("model", model);
                    chunk.put("created_at", getTimestamp());
                    
                    JSONObject message = new JSONObject();
                    message.put("role", "assistant");
                    message.put("content", response);
                    chunk.put("message", message);
                    chunk.put("done", true);
                    
                    sendStreamingResponse(outputStream, chunk.toString());
                } else {
                    // Non-streaming response
                    JSONObject result = new JSONObject();
                    result.put("model", model);
                    result.put("created_at", getTimestamp());
                    
                    JSONObject message = new JSONObject();
                    message.put("role", "assistant");
                    message.put("content", response);
                    result.put("message", message);
                    result.put("done", true);
                    
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
    

    private String applyPromptTemplate(String userInput, ConfigurationManager.Configuration config) {
        if (config != null && config.promptTemplate != null && !config.promptTemplate.isEmpty()) {
            return config.promptTemplate.replace("{USER_INPUT}", userInput);
        }
        return "<|system|>\nYou are a helpful assistant.\n<|user|>\n" + userInput + "\n<|assistant|>\n";
    }

    private String buildPromptFromMessages(JSONArray messages, String configName) throws JSONException {
        StringBuilder sb = new StringBuilder();
        
        // Try to get prompt template from config
        String template = null;
        try {
            ConfigurationManager.Configuration config = configManager.loadConfiguration(configName);
            template = config.promptTemplate;
        } catch (Exception e) {
            Log.w(TAG, "Could not load config for template", e);
        }
        
        // Build conversation from messages
        String systemPrompt = "You are a helpful assistant.";
        StringBuilder userContent = new StringBuilder();
        
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            String role = msg.optString("role", "");
            String content = msg.optString("content", "");
            
            if ("system".equals(role)) {
                systemPrompt = content;
            } else if ("user".equals(role)) {
                if (userContent.length() > 0) {
                    userContent.append("\n");
                }
                userContent.append(content);
            } else if ("assistant".equals(role)) {
                // Include previous assistant responses in context
                if (userContent.length() > 0) {
                    userContent.append("\nAssistant: ").append(content).append("\nUser: ");
                }
            }
        }
        
        // Apply template if available
        if (template != null && !template.isEmpty() && template.contains("{USER_INPUT}")) {
            // Replace system prompt marker if present
            String result = template;
            if (template.contains("<|system|>")) {
                // Use template as-is but replace user input
                result = template.replace("{USER_INPUT}", userContent.toString());
            } else {
                result = template.replace("{USER_INPUT}", userContent.toString());
            }
            return result;
        }
        
        // Default format
        sb.append("<|system|>\n").append(systemPrompt).append("\n");
        sb.append("<|user|>\n").append(userContent.toString()).append("\n");
        sb.append("<|assistant|>\n");
        
        return sb.toString();
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
