package com.micklab.llama;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.content.res.AssetManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ollama-compatible API server that provides /api/* plus llama.cpp WebUI-compatible routes.
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
    private static final String DEFAULT_CONFIG_NAME = "default";
    private static final String SERVER_ROLE_MODEL = "model";
    private static final String SERVER_ROLE_ROUTER = "router";
    private static final String WEBUI_ASSET_ROOT = "webui/";
    private static final String MTMD_MEDIA_MARKER = "<__media__>";
    private static final int MAX_REMOTE_MEDIA_BYTES = 10 * 1024 * 1024;
    // Hard cap on a single request body. Guards against a malicious/buggy Content-Length (or a huge
    // inline base64 image) allocating an unbounded buffer and OOM-killing the app.
    private static final int MAX_REQUEST_BODY_BYTES = 64 * 1024 * 1024;
    private static final String BUILD_INFO = "llama.cpp build 8653 (1f34806c441f335f7d66e56fdb8ab3f5d09684e9)";
    private static final Map<String, String> WEBUI_CONTENT_TYPES;

    static {
        Map<String, String> contentTypes = new HashMap<>();
        contentTypes.put("index.html", "text/html; charset=utf-8");
        contentTypes.put("bundle.js", "application/javascript; charset=utf-8");
        contentTypes.put("bundle.css", "text/css; charset=utf-8");
        contentTypes.put("loading.html", "text/html; charset=utf-8");
        // PWA assets
        contentTypes.put("manifest.webmanifest", "application/manifest+json; charset=utf-8");
        contentTypes.put("sw.js", "application/javascript; charset=utf-8");
        contentTypes.put("icon-192.png", "image/png");
        contentTypes.put("icon-512.png", "image/png");
        contentTypes.put("icon-512-maskable.png", "image/png");
        WEBUI_CONTENT_TYPES = Collections.unmodifiableMap(contentTypes);
    }
    
    private final Context context;
    private final ConfigurationManager configManager;
    private final ModelManager modelManager;
    
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private static final Object TOKEN_COMPLETE = new Object();
    private final Map<String, byte[]> webUiAssetCache = new ConcurrentHashMap<>();
    
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

    private static final class PreparedMessages {
        final JSONArray messages;
        final List<byte[]> mediaFiles;

        PreparedMessages(JSONArray messages, List<byte[]> mediaFiles) {
            this.messages = messages;
            this.mediaFiles = mediaFiles;
        }

        boolean hasMedia() {
            return mediaFiles != null && !mediaFiles.isEmpty();
        }

        byte[][] toMediaArray() {
            if (!hasMedia()) {
                return new byte[0][];
            }
            return mediaFiles.toArray(new byte[0][]);
        }
    }

    private static final class RequestedModalities {
        final boolean vision;
        final boolean audio;

        RequestedModalities(boolean vision, boolean audio) {
            this.vision = vision;
            this.audio = audio;
        }
    }

    private static final class InvalidMediaException extends Exception {
        InvalidMediaException(String message) {
            super(message);
        }

        InvalidMediaException(String message, Throwable cause) {
            super(message, cause);
        }
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

    private static byte[] readAllBytesLimited(InputStream inputStream, int maxBytes) throws IOException {
        try (InputStream in = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Remote media exceeds size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static byte[] decodeImageUrl(JSONObject imageUrl) throws InvalidMediaException {
        if (imageUrl == null) {
            throw new InvalidMediaException("image_url is required");
        }
        String url = imageUrl.optString("url", "");
        if (url.isEmpty()) {
            throw new InvalidMediaException("image_url.url is required");
        }

        if (url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8)) {
            try {
                URLConnection connection = new URL(url).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                return validateImageBytes(readAllBytesLimited(connection.getInputStream(), MAX_REMOTE_MEDIA_BYTES));
            } catch (IOException e) {
                throw new InvalidMediaException("Failed to download image input", e);
            }
        }

        int commaIndex = url.indexOf(',');
        if (commaIndex <= 0) {
            throw new InvalidMediaException("Invalid image_url.url");
        }
        String header = url.substring(0, commaIndex).toLowerCase(Locale.ROOT);
        if (!header.startsWith("data:image/") || !header.endsWith(";base64")) {
            throw new InvalidMediaException("image_url.url must be a base64 data URL");
        }
        try {
            return validateImageBytes(Base64.decode(url.substring(commaIndex + 1), Base64.DEFAULT));
        } catch (IllegalArgumentException e) {
            throw new InvalidMediaException("image_url.url contains invalid base64 data", e);
        }
    }

    /**
     * Rejects image formats the native mtmd/stb_image decoder cannot read but that are the DEFAULT
     * camera/screenshot formats on modern Android (HEIC/HEIF, WebP, AVIF). Previously these were
     * accepted by the header check and then failed deep in native with a generic
     * "failed to decode multimodal input"; here we fail early with an actionable message.
     */
    private static byte[] validateImageBytes(byte[] data) throws InvalidMediaException {
        String unsupported = detectUnsupportedImageFormat(data);
        if (unsupported != null) {
            throw new InvalidMediaException("unsupported image format: " + unsupported
                    + " (the on-device decoder supports PNG, JPEG, BMP and GIF; please convert to PNG or JPEG)");
        }
        return data;
    }

    private static String detectUnsupportedImageFormat(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        // WEBP: "RIFF"<4 bytes>"WEBP"
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        // ISO-BMFF (HEIF/HEIC/AVIF): bytes 4..7 == "ftyp", major brand at 8..11
        if (data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') {
            String brand = new String(data, 8, 4, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            if (brand.startsWith("avi")) {
                return "avif";
            }
            if (brand.startsWith("hei") || brand.startsWith("hev")
                    || brand.startsWith("mif") || brand.startsWith("msf")) {
                return "heic";
            }
        }
        return null;
    }

    private static byte[] decodeInputAudio(JSONObject inputAudio) throws InvalidMediaException {
        if (inputAudio == null) {
            throw new InvalidMediaException("input_audio is required");
        }
        String data = inputAudio.optString("data", "");
        String format = inputAudio.optString("format", "").toLowerCase(Locale.ROOT);
        if (data.isEmpty()) {
            throw new InvalidMediaException("input_audio.data is required");
        }
        if (!"wav".equals(format) && !"mp3".equals(format)) {
            throw new InvalidMediaException("input_audio.format must be either 'wav' or 'mp3'");
        }
        try {
            return Base64.decode(data, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new InvalidMediaException("input_audio.data contains invalid base64 data", e);
        }
    }

    private static PreparedMessages normalizeMessagesForMedia(
            JSONArray messages,
            boolean allowVision,
            boolean allowAudio
    ) throws JSONException, InvalidMediaException {
        JSONArray normalizedMessages = new JSONArray();
        List<byte[]> mediaFiles = new ArrayList<>();

        for (int i = 0; i < messages.length(); i++) {
            JSONObject original = messages.getJSONObject(i);
            JSONObject normalized = copyJsonObject(original);
            Object rawContent = original.opt("content");
            if (!(rawContent instanceof JSONArray)) {
                normalizedMessages.put(normalized);
                continue;
            }

            JSONArray contentParts = (JSONArray) rawContent;
            StringBuilder textContent = new StringBuilder();
            for (int partIndex = 0; partIndex < contentParts.length(); partIndex++) {
                JSONObject part = contentParts.getJSONObject(partIndex);
                String type = part.optString("type", "");
                if ("text".equals(type) || "input_text".equals(type) || ("".equals(type) && part.has("text"))) {
                    textContent.append(part.optString("text", ""));
                } else if ("image_url".equals(type)) {
                    if (!allowVision) {
                        throw new InvalidMediaException("image input is not supported by the current model");
                    }
                    mediaFiles.add(decodeImageUrl(part.optJSONObject("image_url")));
                    textContent.append(MTMD_MEDIA_MARKER);
                } else if ("input_audio".equals(type)) {
                    if (!allowAudio) {
                        throw new InvalidMediaException("audio input is not supported by the current model");
                    }
                    mediaFiles.add(decodeInputAudio(part.optJSONObject("input_audio")));
                    textContent.append(MTMD_MEDIA_MARKER);
                } else if ("media_marker".equals(type)) {
                    textContent.append(MTMD_MEDIA_MARKER);
                } else {
                    throw new InvalidMediaException("unsupported content[].type: " + type);
                }
            }

            normalized.put("content", textContent.toString());
            normalizedMessages.put(normalized);
        }

        return new PreparedMessages(normalizedMessages, mediaFiles);
    }

    private static RequestedModalities detectRequestedModalities(JSONArray messages) throws JSONException {
        boolean vision = false;
        boolean audio = false;
        for (int i = 0; i < messages.length(); i++) {
            Object rawContent = messages.getJSONObject(i).opt("content");
            if (!(rawContent instanceof JSONArray)) {
                continue;
            }

            JSONArray contentParts = (JSONArray) rawContent;
            for (int partIndex = 0; partIndex < contentParts.length(); partIndex++) {
                String type = contentParts.getJSONObject(partIndex).optString("type", "");
                if ("image_url".equals(type)) {
                    vision = true;
                } else if ("input_audio".equals(type)) {
                    audio = true;
                }
                if (vision && audio) {
                    return new RequestedModalities(true, true);
                }
            }
        }
        return new RequestedModalities(vision, audio);
    }

    private static final class NativeChatCompletionResult {
        final String content;
        final String reasoningContent;
        final JSONArray toolCalls;
        final String finishReason;

        NativeChatCompletionResult(
                String content,
                String reasoningContent,
                JSONArray toolCalls,
                String finishReason
        ) {
            this.content = content;
            this.reasoningContent = reasoningContent;
            this.toolCalls = toolCalls;
            this.finishReason = finishReason;
        }
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

    private static String serializeToolChoice(Object toolChoice) {
        return SharedToolManager.serializeToolChoice(toolChoice);
    }

    private boolean hasSharedToolConfig() {
        return McpSettingsHelper.hasSharedToolConfigForNonWebUi(context);
    }

    private static JSONArray buildGenerateMessages(String prompt, String apiSystem) throws JSONException {
        JSONArray messages = new JSONArray();
        if (apiSystem != null) {
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", apiSystem);
            messages.put(systemMessage);
        }

        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt != null ? prompt : "");
        messages.put(userMessage);
        return messages;
    }

    private static NativeChatCompletionResult parseNativeChatCompletionResult(String rawJson)
            throws JSONException {
        JSONObject json = new JSONObject(rawJson);
        String error = json.optString("error", "");
        if (!error.isEmpty()) {
            throw new RuntimeException(error);
        }

        JSONArray toolCalls = json.optJSONArray("tool_calls");
        String finishReason = json.optString(
                "finish_reason",
                toolCalls != null && toolCalls.length() > 0 ? "tool_calls" : "stop"
        );
        return new NativeChatCompletionResult(
                json.optString("content", ""),
                json.optString("reasoning_content", null),
                toolCalls,
                finishReason
        );
    }

    private static JSONArray buildToolCallDeltas(JSONArray toolCalls) throws JSONException {
        JSONArray deltas = new JSONArray();
        if (toolCalls == null) {
            return deltas;
        }

        for (int i = 0; i < toolCalls.length(); i++) {
            JSONObject toolCall = toolCalls.getJSONObject(i);
            JSONObject delta = new JSONObject();
            delta.put("index", i);
            if (toolCall.has("id") && !toolCall.isNull("id")) {
                delta.put("id", toolCall.get("id"));
            }
            delta.put("type", toolCall.optString("type", "function"));
            JSONObject function = toolCall.optJSONObject("function");
            if (function != null) {
                JSONObject functionDelta = new JSONObject();
                if (function.has("name") && !function.isNull("name")) {
                    functionDelta.put("name", function.getString("name"));
                }
                if (function.has("arguments") && !function.isNull("arguments")) {
                    functionDelta.put("arguments", function.getString("arguments"));
                }
                delta.put("function", functionDelta);
            }
            deltas.put(delta);
        }
        return deltas;
    }

    /** Marker placed in tokenQueue to distinguish reasoning content from regular content. */
    private static final class ReasoningToken {
        final String content;
        ReasoningToken(String c) { content = c; }
    }

    /**
     * Wraps a StreamTokenFilter and intercepts &lt;think&gt;…&lt;/think&gt; blocks.
     *
     * <ul>
     *   <li>Structured mode ({@code reasoningSink != null}): thinking content is routed to
     *       {@code reasoningSink} (which enqueues it as {@link ReasoningToken}); regular content
     *       goes to the downstream {@link StreamTokenFilter} as before.</li>
     *   <li>Strip mode ({@code reasoningSink == null}): all recognised reasoning tags and their
     *       content are suppressed entirely.</li>
     * </ul>
     */
    private static final class ThinkBlockStreamFilter {
        private static final String OPEN_TAG  = PromptTemplateManager.THINK_OPEN_TAG;
        private static final String CLOSE_TAG = PromptTemplateManager.THINK_CLOSE_TAG;
        // Hold-back to avoid splitting the close tag across two flush cycles
        private static final int HOLDBACK = Math.max(OPEN_TAG.length(), CLOSE_TAG.length()) - 1;

        // Extended tag pairs stripped when enableThinking=false.
        // Each row: { openTag, closeTag } – lowercase; detection is case-insensitive.
        private static final String[][] REASONING_PAIRS = {
            {"<think>",         "</think>"},
            {"<analysis>",      "</analysis>"},
            {"<commentary>",    "</commentary>"},
            {"<deliberate>",    "</deliberate>"},
            {"<final>",         "</final>"},
            {"<answer>",        "</answer>"},
            {"<tool_call>",     "</tool_call>"},
            {"<tool_response>", "</tool_response>"},
            {"<tool_result>",   "</tool_result>"},
            {"[think]",         "[/think]"},
            {"[THINK]",         "[/THINK]"},
            {"[final]",         "[/final]"},
            {"[FINAL]",         "[/FINAL]"},
        };
        // Named tag stems used to detect attribute-bearing opens, e.g. <think token_budget="N">
        private static final String[] ATTR_TAG_STEMS = {
            "think", "analysis", "commentary", "deliberate", "final",
            "answer", "tool_call", "tool_response", "tool_result"
        };
        // Hold-back for the multi-tag strip path – must cover the longest open/close tag
        // plus extra room for attribute-bearing tags (e.g. <think token_budget="8192">)
        private static final int MULTI_HOLDBACK = 64;

        private final StreamTokenFilter inner;
        // Non-null = structured mode; null = strip mode.
        private final java.util.function.Consumer<String> reasoningSink;
        private final StringBuilder pending = new StringBuilder();

        // Structured-mode state
        private boolean inThinkBlock = false;
        // Strip-mode state: null = NORMAL, non-null = inside a reasoning block
        private String activeCloseTag = null;

        /** Structured mode: route thinking content to {@code reasoningSink}. */
        ThinkBlockStreamFilter(StreamTokenFilter inner,
                               java.util.function.Consumer<String> reasoningSink) {
            this.inner = inner;
            this.reasoningSink = java.util.Objects.requireNonNull(reasoningSink);
        }

        /** Strip mode: suppress all recognised reasoning blocks. */
        ThinkBlockStreamFilter(StreamTokenFilter inner) {
            this.inner = inner;
            this.reasoningSink = null;
        }

        void onToken(String token) {
            if (token == null || token.isEmpty()) return;
            pending.append(token);
            flush(false);
        }

        void onComplete() {
            flush(true);
            inner.onComplete();
        }

        void onError(String error) {
            flush(true);
            inner.onError(error);
        }

        // ── Helpers for the multi-tag strip path ───────────────────────────────────────

        /** Result of locating the earliest reasoning open tag in the pending buffer. */
        private static final class TagMatch {
            final int start;    // index of first char of open tag in text
            final int end;      // index just after the last char of open tag
            final String close; // corresponding close tag (lowercase)
            TagMatch(int start, int end, String close) {
                this.start = start;
                this.end   = end;
                this.close = close;
            }
        }

        /**
         * Case-insensitive search of {@code needle} in {@code haystack}.
         * Returns -1 if not found.
         */
        private static int indexOfCI(String haystack, String needle) {
            return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        }

        /**
         * Find the earliest reasoning open tag in {@code text} and return a {@link TagMatch},
         * or {@code null} if none found.  Handles:
         * <ul>
         *   <li>Exact simple tags from {@link #REASONING_PAIRS}</li>
         *   <li>Attribute-bearing named tags, e.g. {@code <think token_budget="N">}</li>
         *   <li>Pipe-token pairs {@code <|word|>}</li>
         * </ul>
         */
        private static TagMatch findEarliestOpenTag(String text) {
            TagMatch best = null;

            // Exact simple pairs (case-insensitive)
            for (String[] pair : REASONING_PAIRS) {
                int idx = indexOfCI(text, pair[0]);
                if (idx >= 0 && (best == null || idx < best.start)) {
                    best = new TagMatch(idx, idx + pair[0].length(), pair[1]);
                }
            }

            // Attribute-bearing named tags: <stem ...>
            for (String stem : ATTR_TAG_STEMS) {
                String prefix = "<" + stem;
                int idx = indexOfCI(text, prefix);
                if (idx >= 0 && (best == null || idx < best.start)) {
                    int afterPrefix = idx + prefix.length();
                    if (afterPrefix < text.length()) {
                        char next = text.charAt(afterPrefix);
                        if (next == ' ' || next == '\t' || next == '\r' || next == '\n') {
                            int gt = text.indexOf('>', afterPrefix);
                            if (gt >= 0) {
                                best = new TagMatch(idx, gt + 1, "</" + stem + ">");
                            }
                        }
                    }
                }
            }

            // Pipe-token pairs: <|word|> … </|word|>
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<\\|([a-zA-Z_]+)\\|>")
                    .matcher(text);
            if (m.find()) {
                int idx = m.start();
                if (best == null || idx < best.start) {
                    best = new TagMatch(idx, m.end(), "</|" + m.group(1) + "|>");
                }
            }

            return best;
        }

        // ── Main flush logic ────────────────────────────────────────────────────────────

        private void flush(boolean flushAll) {
            while (pending.length() > 0) {
                String text = pending.toString();

                if (reasoningSink != null) {
                    // ── Structured mode: route <think> content to reasoningSink ──────
                    if (inThinkBlock) {
                        int endIdx = text.indexOf(CLOSE_TAG);
                        if (endIdx >= 0) {
                            String inside = text.substring(0, endIdx);
                            if (!inside.isEmpty()) reasoningSink.accept(inside);
                            inThinkBlock = false;
                            int after = endIdx + CLOSE_TAG.length();
                            while (after < text.length()
                                    && (text.charAt(after) == '\n' || text.charAt(after) == '\r')) {
                                after++;
                            }
                            pending.setLength(0);
                            pending.append(text, after, text.length());
                        } else if (flushAll) {
                            if (!text.isEmpty()) reasoningSink.accept(text);
                            pending.setLength(0);
                            break;
                        } else {
                            int safe = Math.max(0, text.length() - HOLDBACK);
                            if (safe > 0) reasoningSink.accept(text.substring(0, safe));
                            pending.setLength(0);
                            pending.append(text, safe, text.length());
                            break;
                        }
                    } else {
                        int openIdx = text.indexOf(OPEN_TAG);
                        if (openIdx >= 0) {
                            if (openIdx > 0) inner.onToken(text.substring(0, openIdx));
                            inThinkBlock = true;
                            // <think> itself is not forwarded to either channel
                            pending.setLength(0);
                            pending.append(text, openIdx + OPEN_TAG.length(), text.length());
                        } else if (flushAll) {
                            if (!text.isEmpty()) inner.onToken(text);
                            pending.setLength(0);
                            break;
                        } else {
                            int safe = Math.max(0, text.length() - HOLDBACK);
                            if (safe > 0) inner.onToken(text.substring(0, safe));
                            pending.setLength(0);
                            pending.append(text, safe, text.length());
                            break;
                        }
                    }

                } else {
                    // ── enableThinking=false: strip ALL reasoning tags ────────────────
                    if (activeCloseTag != null) {
                        int endIdx = indexOfCI(text, activeCloseTag);
                        if (endIdx >= 0) {
                            // Discard block content and close tag
                            int after = endIdx + activeCloseTag.length();
                            while (after < text.length()
                                    && (text.charAt(after) == '\n' || text.charAt(after) == '\r')) {
                                after++;
                            }
                            activeCloseTag = null;
                            pending.setLength(0);
                            pending.append(text, after, text.length());
                        } else if (flushAll) {
                            // Unclosed block at end — discard
                            pending.setLength(0);
                            break;
                        } else {
                            // Hold back to catch partial close tag at tail
                            int safe = Math.max(0, text.length() - MULTI_HOLDBACK);
                            pending.setLength(0);
                            pending.append(text, safe, text.length());
                            break;
                        }
                    } else {
                        TagMatch match = findEarliestOpenTag(text);
                        if (match != null) {
                            if (match.start > 0) inner.onToken(text.substring(0, match.start));
                            activeCloseTag = match.close;
                            pending.setLength(0);
                            pending.append(text, match.end, text.length());
                        } else if (flushAll) {
                            if (!text.isEmpty()) inner.onToken(text);
                            pending.setLength(0);
                            break;
                        } else {
                            int safe = Math.max(0, text.length() - MULTI_HOLDBACK);
                            if (safe > 0) inner.onToken(text.substring(0, safe));
                            pending.setLength(0);
                            pending.append(text, safe, text.length());
                            break;
                        }
                    }
                }
            }
        }
    } // end ThinkBlockStreamFilter

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

    /** Returns "\n(in:N  out:M  Xs  temp:T°C)" if showPerfMetrics is enabled, else "". */
    private String buildPerfMetricsSuffix() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("ollama_prefs", android.content.Context.MODE_PRIVATE);
        if (!prefs.getBoolean("show_perf_metrics", false)) return "";
        LlamaNative llama = modelManager.getLlama();
        if (llama == null) return "";
        int nIn  = llama.getLastNPromptTokens();
        int nOut = llama.getLastNEvalTokens();
        double sec = llama.getLastTotalTimeMs() / 1000.0;
        String timeStr = (sec < 60.0)
                ? String.format(Locale.US, "%.1fs", sec)
                : String.format(Locale.US, "%dm%.1fs", (int)(sec / 60), sec % 60);
        String tpsStr = (sec > 0.0)
                ? String.format(Locale.US, "%.1ftok/s", nOut / sec)
                : "-";
        String tempStr = getDeviceTempString();
        return String.format(Locale.US,
                "\n(in: %d  out: %d  time: %s  %s  temp: %s)", nIn, nOut, timeStr, tpsStr, tempStr);
    }

    private String getDeviceTempString() {
        try {
            Intent intent = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return "?°C";
            int tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenths == Integer.MIN_VALUE) return "?°C";
            return String.format(Locale.US, "%.1f°C", tenths / 10.0f);
        } catch (Exception e) {
            return "?°C";
        }
    }

    /** Appends the perf metrics suffix to content if showPerfMetrics is enabled. */
    private String appendPerfMetrics(String content) {
        String suffix = buildPerfMetricsSuffix();
        return suffix.isEmpty() ? content : content + suffix;
    }

    /**
     * Adds Ollama-compatible performance fields to a final {@code done:true} response object,
     * using the metrics from the most recent generation. Durations are in nanoseconds, matching
     * the Ollama API ({@code total_duration}, {@code load_duration}, {@code prompt_eval_count},
     * {@code prompt_eval_duration}, {@code eval_count}, {@code eval_duration}). No-op if the
     * native context is unavailable. Always emitted (Ollama clients rely on these for tok/s).
     */
    private void putOllamaMetrics(JSONObject obj) {
        try {
            LlamaNative llama = modelManager.getLlama();
            if (llama == null) return;
            long loadNs       = Math.round(llama.getLastLoadTimeMs()       * 1.0e6);
            long promptEvalNs = Math.round(llama.getLastPromptEvalTimeMs() * 1.0e6);
            long evalNs       = Math.round(llama.getLastTotalTimeMs()      * 1.0e6); // decode time
            obj.put("total_duration", loadNs + promptEvalNs + evalNs);
            obj.put("load_duration", loadNs);
            obj.put("prompt_eval_count", llama.getLastNPromptTokens());
            obj.put("prompt_eval_duration", promptEvalNs);
            obj.put("eval_count", llama.getLastNEvalTokens());
            obj.put("eval_duration", evalNs);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to add Ollama perf metrics", e);
        }
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
        
        // Bounded, cached-style pool: reuse idle threads but cap the maximum so a connection flood
        // cannot spawn unbounded threads (each of which may buffer a large request body) and OOM the
        // app. CallerRunsPolicy applies natural backpressure — under extreme load the acceptor runs
        // the handler inline instead of queueing without limit.
        int maxHandlerThreads = Math.max(32, Runtime.getRuntime().availableProcessors() * 8);
        executorService = new ThreadPoolExecutor(
                0, maxHandlerThreads,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.CallerRunsPolicy());
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
            String requestTarget = requestParts[1];
            String path = requestTarget;
            String query = "";
            int queryIndex = requestTarget.indexOf('?');
            if (queryIndex >= 0) {
                path = requestTarget.substring(0, queryIndex);
                query = requestTarget.substring(queryIndex + 1);
            }
            Map<String, String> queryParams = parseQueryParameters(query);
            
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
                        try {
                            contentLength = Integer.parseInt(value.trim());
                        } catch (NumberFormatException e) {
                            // Previously this threw and the connection was closed with no response;
                            // the client saw a reset instead of a proper error.
                            sendErrorResponse(outputStream, 400, "Invalid Content-Length header");
                            clientSocket.close();
                            return;
                        }
                    }
                }
            }

            if (contentLength < 0) {
                sendErrorResponse(outputStream, 400, "Invalid Content-Length header");
                clientSocket.close();
                return;
            }
            if (contentLength > MAX_REQUEST_BODY_BYTES) {
                sendErrorResponse(outputStream, 413,
                        "Request body too large (max " + MAX_REQUEST_BODY_BYTES + " bytes)");
                clientSocket.close();
                return;
            }

            // Read the body until the full Content-Length (a byte count) has been consumed. A single
            // Reader.read() is not guaranteed to fill its buffer, so large bodies (e.g. a base64
            // image) were previously truncated and then failed JSON parsing as a misleading
            // "Invalid JSON". Track progress in UTF-8 bytes (the Reader yields decoded chars) so
            // multibyte bodies neither truncate nor block waiting for chars that will never arrive.
            String body = "";
            if (contentLength > 0) {
                StringBuilder bodyBuilder = new StringBuilder(Math.min(contentLength, 1 << 16));
                char[] chunk = new char[8192];
                int bytesConsumed = 0;
                while (bytesConsumed < contentLength) {
                    int r = reader.read(chunk, 0, chunk.length);
                    if (r == -1) {
                        break;
                    }
                    bodyBuilder.append(chunk, 0, r);
                    bytesConsumed += new String(chunk, 0, r).getBytes(StandardCharsets.UTF_8).length;
                }
                body = bodyBuilder.toString();
            }

            // Avoid dumping an entire (possibly multi-MB base64 image) body into logcat.
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Raw request body (" + body.length() + " chars):\n"
                        + (body.length() > 2048 ? body.substring(0, 2048) + "…[truncated]" : body));
            }
            logMaxDebugPayload("api.request.body", body);
            
            // Route request
            if ("POST".equals(method)) {
                if ("/api/generate".equals(path)) {
                    handleGenerate(outputStream, body);
                } else if ("/api/chat".equals(path)) {
                    handleChat(outputStream, body);
                } else if ("/api/tags".equals(path) || "/api/tags/".equals(path)) {
                    handleTags(outputStream);
                } else if ("/v1/chat/completions".equals(path)) {
                    handleOpenAiChat(outputStream, body);
                } else if ("/api/embed".equals(path)) {
                    handleEmbed(outputStream, body);
                } else if ("/api/embeddings".equals(path)) {
                    handleEmbeddings(outputStream, body);
                } else if ("/v1/embeddings".equals(path)) {
                    handleOpenAiEmbeddings(outputStream, body);
                } else if ("/api/tokenize".equals(path)) {
                    handleOllamaTokenize(outputStream, body);
                } else if ("/v1/tokenize".equals(path)) {
                    handleOpenAiTokenize(outputStream, body);
                } else if ("/models/load".equals(path)) {
                    handleLoadModel(outputStream, body);
                } else if ("/models/unload".equals(path)) {
                    handleUnloadModel(outputStream, body);
                } else {
                    sendErrorResponse(outputStream, 404, "Not Found");
                }
            } else if ("GET".equals(method)) {
                if ("/api/tags".equals(path) || "/api/tags/".equals(path)) {
                    handleTags(outputStream);
                } else if ("/".equals(path) || "/index.html".equals(path)) {
                    handleWebUi(outputStream, path);
                } else if ("/api".equals(path)) {
                    sendJsonResponse(outputStream, 200, "{\"status\":\"Ollama is running\"}");
                } else if ("/health".equals(path) || "/v1/health".equals(path)) {
                    handleHealth(outputStream);
                } else if ("/v1/models".equals(path) || "/models".equals(path)) {
                    handleModelsList(outputStream);
                } else if ("/props".equals(path)) {
                    handleProps(outputStream, queryParams);
                } else if ("/slots".equals(path)) {
                    handleSlots(outputStream, queryParams);
                } else if (isWebUiPath(path)) {
                    handleWebUi(outputStream, path);
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

    private Map<String, String> parseQueryParameters(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            String value = separator >= 0 ? pair.substring(separator + 1) : "";
            params.put(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8)
            );
        }
        return params;
    }

    private boolean isWebUiPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return !path.startsWith("/api")
                && !path.startsWith("/v1/")
                && !"/models".equals(path)
                && !path.startsWith("/models/")
                && !"/props".equals(path)
                && !"/slots".equals(path)
                && !"/health".equals(path)
                && !"/metrics".equals(path)
                && !"/cors-proxy".equals(path);
    }

    private String getServerRole() {
        return configManager.listConfigurations().size() > 1 ? SERVER_ROLE_ROUTER : SERVER_ROLE_MODEL;
    }

    private String resolveRequestedModel(String requestedModel) {
        if (requestedModel != null) {
            String trimmed = requestedModel.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }

        String current = modelManager.getCurrentConfigName();
        if (current != null && !current.trim().isEmpty()) {
            return current.trim();
        }

        List<String> configs = configManager.listConfigurations();
        if (configs.contains(DEFAULT_CONFIG_NAME)) {
            return DEFAULT_CONFIG_NAME;
        }
        if (!configs.isEmpty()) {
            return configs.get(0);
        }
        return DEFAULT_CONFIG_NAME;
    }

    private ConfigurationManager.Configuration loadResolvedConfiguration(String requestedModel) throws IOException, JSONException {
        return configManager.loadConfiguration(resolveRequestedModel(requestedModel));
    }

    private void handleHealth(OutputStream outputStream) throws IOException {
        try {
            JSONObject response = new JSONObject();
            response.put("status", "ok");
            response.put("role", getServerRole());
            response.put("webui", true);
            sendJsonResponse(outputStream, 200, response.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build health response", e);
            sendErrorResponse(outputStream, 500, "Internal Server Error");
        }
    }

    private void handleModelsList(OutputStream outputStream) throws IOException {
        try {
            List<String> configNames = new ArrayList<>(configManager.listConfigurations());
            JSONArray data = new JSONArray();
            JSONArray models = new JSONArray();
            long created = System.currentTimeMillis() / 1000L;
            String loadedConfig = modelManager.getCurrentConfigName();
            boolean isLoaded = modelManager.isModelLoaded();

            for (String configName : configNames) {
                ConfigurationManager.Configuration config = configManager.loadConfiguration(configName);
                File modelFile = ModelFileHelper.resolveStoredModelFile(context, config.modelUrl);
                boolean thisConfigLoaded = isLoaded && configName.equals(loadedConfig);
                PromptTemplateManager.ModelFamily family = PromptTemplateManager.detectModelFamily(
                        modelFile != null ? modelFile.getAbsolutePath() : config.modelUrl);

                JSONObject dataEntry = new JSONObject();
                dataEntry.put("id", configName);
                dataEntry.put("name", configName);
                dataEntry.put("object", "model");
                dataEntry.put("owned_by", "llamacpp");
                dataEntry.put("created", created);
                dataEntry.put("in_cache", modelFile != null && modelFile.exists());
                dataEntry.put("path", modelFile != null ? modelFile.getAbsolutePath() : config.modelUrl);

                JSONObject status = new JSONObject();
                status.put("value", thisConfigLoaded ? "loaded" : "unloaded");
                dataEntry.put("status", status);
                dataEntry.put("tags", new JSONArray());
                data.put(dataEntry);

                JSONObject details = new JSONObject();
                details.put("format", "gguf");
                details.put("family", family.name().toLowerCase(Locale.US));
                details.put("parameter_size", "unknown");
                details.put("quantization_level", "unknown");

                JSONObject modelEntry = new JSONObject();
                modelEntry.put("name", configName);
                modelEntry.put("model", configName);
                modelEntry.put("modified_at", getTimestamp());
                modelEntry.put("size", modelFile != null && modelFile.exists() ? modelFile.length() : 0);
                JSONObject modalities = buildModelModalities(configName, config);
                JSONArray capabilities = new JSONArray();
                if (modalities.optBoolean("vision", false) || modalities.optBoolean("audio", false)) {
                    capabilities.put("multimodal");
                }
                modelEntry.put("capabilities", capabilities);
                modelEntry.put("modalities", modalities);
                modelEntry.put("details", details);
                models.put(modelEntry);
            }

            JSONObject response = new JSONObject();
            response.put("object", "list");
            response.put("data", data);
            response.put("models", models);
            sendJsonResponse(outputStream, 200, response.toString());
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to build models response", e);
            sendErrorResponse(outputStream, 500, "Internal Server Error");
        }
    }

    private void handleProps(OutputStream outputStream, Map<String, String> queryParams) throws IOException {
        try {
            String modelName = resolveRequestedModel(queryParams.get("model"));
            ConfigurationManager.Configuration config = configManager.loadConfiguration(modelName);
            JSONObject response = buildPropsResponse(modelName, config);
            sendJsonResponse(outputStream, 200, response.toString());
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to build props response", e);
            sendErrorResponse(outputStream, 400, "Unknown model");
        }
    }

    private JSONObject buildPropsResponse(String modelName, ConfigurationManager.Configuration config) throws JSONException {
        File modelFile = ModelFileHelper.resolveStoredModelFile(context, config.modelUrl);
        JSONObject response = new JSONObject();
        response.put("default_generation_settings", buildDefaultGenerationSettings(modelName, config));
        response.put("total_slots", 1);
        response.put("model_path", modelFile != null ? modelFile.getAbsolutePath() : "");
        response.put("model_alias", modelName);
        response.put("role", getServerRole());

        response.put("modalities", buildModelModalities(modelName, config));

        String chatTemplate = "";
        if (modelManager.isModelLoaded() && modelName.equals(modelManager.getCurrentConfigName())) {
            chatTemplate = modelManager.getLlama().getChatTemplate();
        }
        if ((chatTemplate == null || chatTemplate.isEmpty()) && config.customChatTemplate != null) {
            chatTemplate = config.customChatTemplate;
        }
        response.put("chat_template", chatTemplate != null ? chatTemplate : "");
        response.put("bos_token", "");
        response.put("eos_token", "");
        response.put("build_info", BUILD_INFO);
        response.put("webui_settings", buildWebUiSettings(config));
        response.put("webui", true);
        return response;
    }

    private JSONObject buildModelModalities(
            String modelName,
            ConfigurationManager.Configuration config
    ) throws JSONException {
        JSONObject modalities = new JSONObject();
        boolean isLoadedCurrentModel = modelManager.isModelLoaded()
                && modelName != null
                && modelName.equals(modelManager.getCurrentConfigName());
        if (isLoadedCurrentModel) {
            ModelFileHelper.InferredModalities advertised =
                    modelManager.getAdvertisedModalities(modelName);
            modalities.put("vision", advertised.supportsVision());
            modalities.put("audio", advertised.supportsAudio());
            return modalities;
        }

        ModelFileHelper.InferredModalities inferred = ModelFileHelper.inferAutoDetectedModalities(
                context,
                config != null ? config.modelUrl : null);
        modalities.put("vision", inferred.supportsVision());
        modalities.put("audio", inferred.supportsAudio());
        return modalities;
    }

    private JSONObject buildDefaultGenerationSettings(String modelName, ConfigurationManager.Configuration config) throws JSONException {
        JSONObject settings = new JSONObject();
        settings.put("id", 0);
        settings.put("id_task", 0);
        settings.put("n_ctx", Math.max(1, config.nCtx));
        settings.put("speculative", false);
        settings.put("is_processing", modelManager.isBusy() && modelName.equals(resolveRequestedModel(modelManager.getCurrentConfigName())));
        settings.put("params", buildPropsParams(config));
        settings.put("prompt", "");

        JSONObject nextToken = new JSONObject();
        nextToken.put("has_next_token", false);
        nextToken.put("has_new_line", false);
        nextToken.put("n_remain", 0);
        nextToken.put("n_decoded", 0);
        nextToken.put("stopping_word", "");
        settings.put("next_token", nextToken);
        return settings;
    }

    private JSONObject buildPropsParams(ConfigurationManager.Configuration config) throws JSONException {
        int nPredict = config.nPredict > 0 ? config.nPredict : config.nCtx;
        String reasoningFormat = config.enableThinking ? "auto" : "none";
        JSONObject params = new JSONObject();
        params.put("n_predict", nPredict);
        params.put("seed", -1);
        params.put("temperature", config.temp);
        params.put("dynatemp_range", config.dynatempRange);
        params.put("dynatemp_exponent", config.dynatempExponent);
        params.put("top_k", config.topK);
        params.put("top_p", config.topP);
        params.put("min_p", config.minP);
        params.put("top_n_sigma", config.topNSigma);
        params.put("xtc_probability", config.xtcProbability);
        params.put("xtc_threshold", config.xtcThreshold);
        params.put("typ_p", config.typicalP);
        params.put("repeat_last_n", config.penaltyLastN);
        params.put("repeat_penalty", config.penaltyRepeat);
        params.put("presence_penalty", config.penaltyPresent);
        params.put("frequency_penalty", config.penaltyFreq);
        params.put("dry_multiplier", config.dryMultiplier);
        params.put("dry_base", config.dryBase);
        params.put("dry_allowed_length", config.dryAllowedLength);
        params.put("dry_penalty_last_n", config.dryPenaltyLastN);
        params.put("dry_sequence_breakers", stringListToJsonArray(parseDrySequenceBreakers(config.drySequenceBreakers)));
        params.put("mirostat", config.mirostat);
        params.put("mirostat_tau", config.mirostatTau);
        params.put("mirostat_eta", config.mirostatEta);
        params.put("stop", new JSONArray());
        // Only advertise max_tokens when nPredict is explicitly capped. When nPredict=0
        // (unlimited), omitting max_tokens lets the WebUI treat the limit as unset and
        // avoids sending nCtx back in requests which would wrongly cap generation.
        if (config.nPredict > 0) params.put("max_tokens", config.nPredict);
        params.put("n_keep", 0);
        params.put("n_discard", 0);
        params.put("ignore_eos", false);
        params.put("stream", config.streaming);
        params.put("logit_bias", new JSONArray());
        params.put("n_probs", 0);
        params.put("min_keep", 0);
        params.put("grammar", "");
        params.put("grammar_lazy", false);
        params.put("grammar_triggers", new JSONArray());
        params.put("preserved_tokens", new JSONArray());
        params.put("chat_format", PromptTemplateManager.detectModelFamily(config.modelUrl).name().toLowerCase(Locale.US));
        params.put("reasoning_format", reasoningFormat);
        params.put("reasoning_in_content", false);
        params.put("generation_prompt", "");
        params.put("samplers", stringListToJsonArray(defaultSamplers()));
        params.put("backend_sampling", false);
        params.put("speculative.n_max", 0);
        params.put("speculative.n_min", 0);
        params.put("speculative.p_min", 0);
        params.put("timings_per_token", false);
        params.put("post_sampling_probs", false);
        params.put("lora", new JSONArray());
        return params;
    }

    private JSONObject buildWebUiSettings(ConfigurationManager.Configuration config) throws JSONException {
        JSONObject settings = new JSONObject();
        settings.put("systemMessage", config.systemPrompt != null ? config.systemPrompt : "");
        settings.put("showSystemMessage", config.systemPrompt != null && !config.systemPrompt.isEmpty());
        settings.put("showThoughtInProgress", config.enableThinking);
        // Problem 1: sync disableReasoningParsing so the WebUI sends reasoning_format=none
        // when the app's Thinking toggle is off, preventing reasoning extraction and
        // ensuring <think> blocks are stripped server-side rather than sent back to the API.
        settings.put("disableReasoningParsing", !config.enableThinking);
        // Problem 2: surface the raw thinking flag so the bundle can derive
        // chat_template_kwargs.enable_thinking for the generation request.
        settings.put("enableThinking", config.enableThinking);
        settings.put("excludeReasoningFromContext", false);
        settings.put("sendOnEnter", false);
        settings.put("showRawModelNames", false);
        settings.put(
                McpSettingsHelper.WEBUI_SHARED_MCP_SERVERS_KEY,
                McpSettingsHelper.getSharedMcpServersJson(context));
        settings.put(
                McpSettingsHelper.WEBUI_SHARED_FUNCTION_DEFINITIONS_KEY,
                McpSettingsHelper.getSharedFunctionDefinitionsJson(context));
        // Version counter that the WebUI uses to detect model (re)loads. When this value
        // changes, the WebUI resets its settings to the app-side defaults (force-sync),
        // so the loaded model's profile settings (system prompt, thinking toggle, etc.)
        // become the WebUI's initial state. Subsequent user changes in the WebUI are then
        // preserved until the next model load or reset.
        settings.put("settings_version", modelManager.getWebUiVersion());
        return settings;
    }

    private void handleSlots(OutputStream outputStream, Map<String, String> queryParams) throws IOException {
        try {
            String modelName = resolveRequestedModel(queryParams.get("model"));
            ConfigurationManager.Configuration config = configManager.loadConfiguration(modelName);
            JSONArray response = new JSONArray();

            JSONObject slot = new JSONObject();
            slot.put("id", 0);
            slot.put("id_task", 0);
            slot.put("n_ctx", Math.max(1, config.nCtx));
            slot.put("speculative", false);
            slot.put("is_processing", modelManager.isBusy() && modelName.equals(modelManager.getCurrentConfigName()));
            slot.put("params", buildSlotParams(config));

            JSONObject nextToken = new JSONObject();
            nextToken.put("has_next_token", false);
            nextToken.put("has_new_line", false);
            nextToken.put("n_remain", 0);
            nextToken.put("n_decoded", 0);
            slot.put("next_token", nextToken);

            response.put(slot);
            sendJsonResponse(outputStream, 200, response.toString());
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to build slots response", e);
            sendErrorResponse(outputStream, 400, "Unknown model");
        }
    }

    private JSONObject buildSlotParams(ConfigurationManager.Configuration config) throws JSONException {
        int nPredict = config.nPredict > 0 ? config.nPredict : config.nCtx;
        String reasoningFormat = config.enableThinking ? "auto" : "none";
        JSONObject params = new JSONObject();
        params.put("n_predict", nPredict);
        params.put("seed", -1);
        params.put("temperature", config.temp);
        params.put("dynatemp_range", config.dynatempRange);
        params.put("dynatemp_exponent", config.dynatempExponent);
        params.put("top_k", config.topK);
        params.put("top_p", config.topP);
        params.put("min_p", config.minP);
        params.put("top_n_sigma", config.topNSigma);
        params.put("xtc_probability", config.xtcProbability);
        params.put("xtc_threshold", config.xtcThreshold);
        params.put("typical_p", config.typicalP);
        params.put("repeat_last_n", config.penaltyLastN);
        params.put("repeat_penalty", config.penaltyRepeat);
        params.put("presence_penalty", config.penaltyPresent);
        params.put("frequency_penalty", config.penaltyFreq);
        params.put("dry_multiplier", config.dryMultiplier);
        params.put("dry_base", config.dryBase);
        params.put("dry_allowed_length", config.dryAllowedLength);
        params.put("dry_penalty_last_n", config.dryPenaltyLastN);
        params.put("mirostat", config.mirostat);
        params.put("mirostat_tau", config.mirostatTau);
        params.put("mirostat_eta", config.mirostatEta);
        if (config.nPredict > 0) params.put("max_tokens", config.nPredict);
        params.put("n_keep", 0);
        params.put("n_discard", 0);
        params.put("ignore_eos", false);
        params.put("stream", config.streaming);
        params.put("n_probs", 0);
        params.put("min_keep", 0);
        params.put("chat_format", PromptTemplateManager.detectModelFamily(config.modelUrl).name().toLowerCase(Locale.US));
        params.put("reasoning_format", reasoningFormat);
        params.put("reasoning_in_content", false);
        params.put("generation_prompt", "");
        params.put("samplers", stringListToJsonArray(defaultSamplers()));
        params.put("backend_sampling", false);
        params.put("speculative.n_max", 0);
        params.put("speculative.n_min", 0);
        params.put("speculative.p_min", 0);
        params.put("timings_per_token", false);
        params.put("post_sampling_probs", false);
        params.put("lora", new JSONArray());
        return params;
    }

    private JSONArray stringListToJsonArray(List<String> values) {
        JSONArray array = new JSONArray();
        if (values == null) {
            return array;
        }
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private List<String> parseDrySequenceBreakers(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return values;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String normalized = part.replace("\\n", "\n").trim();
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return values;
    }

    private List<String> defaultSamplers() {
        List<String> samplers = new ArrayList<>();
        samplers.add("top_k");
        samplers.add("typ_p");
        samplers.add("top_p");
        samplers.add("min_p");
        samplers.add("temperature");
        return samplers;
    }

    /**
     * Problem 4: apply the {@code samplers} array from the request body to the native
     * sampler chain.  An absent or empty field resets to the built-in default order.
     * Must be called AFTER {@link #applyRequestOverrides} / {@link ModelManager#applyConfiguration}.
     */
    private void applySamplerOrderOverride(JSONObject request) {
        if (request == null) return;
        JSONArray arr = request.optJSONArray("samplers");
        String order = "";
        if (arr != null && arr.length() > 0) {
            List<String> parts = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) parts.add(s);
            }
            order = String.join(";", parts);
        }
        modelManager.getLlama().setSamplerOrder(order);
    }

    private void handleLoadModel(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = resolveRequestedModel(request.optString("model", null));
            if (!acquireGenerationSlot(outputStream, "/models/load")) {
                return;
            }
            try {
                boolean success = modelManager.loadConfiguration(model);
                JSONObject response = new JSONObject();
                response.put("success", success);
                if (!success) {
                    response.put("error", "Failed to load model");
                }
                sendJsonResponse(outputStream, success ? 200 : 500, response.toString());
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid load-model request", e);
            sendErrorResponse(outputStream, 400, "Invalid JSON");
        }
    }

    private void handleUnloadModel(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = resolveRequestedModel(request.optString("model", null));
            if (modelManager.isBusy()) {
                sendErrorResponse(outputStream, 503, "Model is busy");
                return;
            }
            if (modelManager.isModelLoaded() && model.equals(modelManager.getCurrentConfigName())) {
                modelManager.free();
            }
            JSONObject response = new JSONObject();
            response.put("success", true);
            sendJsonResponse(outputStream, 200, response.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Invalid unload-model request", e);
            sendErrorResponse(outputStream, 400, "Invalid JSON");
        }
    }

    private void applyNPredictOverride(JSONObject request, ConfigurationManager.Configuration config) {
        if (request == null || config == null) return;
        int override = -1;
        if (request.has("num_predict")) {
            override = request.optInt("num_predict", -1);
        } else if (request.has("n_predict")) {
            override = request.optInt("n_predict", -1);
        } else if (request.has("max_tokens")) {
            override = request.optInt("max_tokens", -1);
        }
        int effective = override > 0 ? override : config.nPredict;
        try {
            modelManager.getLlama().setNPredict(effective);
        } catch (Exception ignored) {}
    }

    /**
     * Resolve the effective {@code enableThinking} for this request.
     * <p>
     * Priority (highest first):
     * <ol>
     *   <li>{@code reasoning_format} field in the request body:
     *       {@code "none"} → thinking disabled, {@code "auto"} → thinking enabled.</li>
     *   <li>The model config's {@code enableThinking} flag.</li>
     * </ol>
     * This lets the WebUI's "Disable reasoning content parsing" toggle
     * ({@code reasoning_format=none}) override the per-profile setting on a
     * per-request basis, without touching the saved configuration.
     */
    private static boolean resolveEnableThinking(JSONObject request, ConfigurationManager.Configuration config) {
        boolean configDefault = config == null || config.enableThinking;
        if (request == null) return configDefault;
        String fmt = request.optString("reasoning_format", null);
        if (fmt == null) return configDefault;
        return !"none".equalsIgnoreCase(fmt);
    }

    private void applyRequestOverrides(ConfigurationManager.Configuration config, JSONObject request) {
        if (config == null || request == null) {
            return;
        }

        if (request.has("temperature")) {
            config.temp = request.optDouble("temperature", config.temp);
        }
        if (request.has("top_k")) {
            config.topK = request.optInt("top_k", config.topK);
        }
        if (request.has("top_p")) {
            config.topP = request.optDouble("top_p", config.topP);
        }
        if (request.has("min_p")) {
            config.minP = request.optDouble("min_p", config.minP);
        }
        if (request.has("dynatemp_range")) {
            config.dynatempRange = request.optDouble("dynatemp_range", config.dynatempRange);
        }
        if (request.has("dynatemp_exponent")) {
            config.dynatempExponent = request.optDouble("dynatemp_exponent", config.dynatempExponent);
        }
        if (request.has("xtc_probability")) {
            config.xtcProbability = request.optDouble("xtc_probability", config.xtcProbability);
        }
        if (request.has("xtc_threshold")) {
            config.xtcThreshold = request.optDouble("xtc_threshold", config.xtcThreshold);
        }
        if (request.has("typ_p")) {
            config.typicalP = request.optDouble("typ_p", config.typicalP);
        }
        if (request.has("repeat_last_n")) {
            config.penaltyLastN = request.optInt("repeat_last_n", config.penaltyLastN);
        }
        if (request.has("repeat_penalty")) {
            config.penaltyRepeat = request.optDouble("repeat_penalty", config.penaltyRepeat);
        }
        if (request.has("presence_penalty")) {
            config.penaltyPresent = request.optDouble("presence_penalty", config.penaltyPresent);
        }
        if (request.has("frequency_penalty")) {
            config.penaltyFreq = request.optDouble("frequency_penalty", config.penaltyFreq);
        }
        if (request.has("dry_multiplier")) {
            config.dryMultiplier = request.optDouble("dry_multiplier", config.dryMultiplier);
        }
        if (request.has("dry_base")) {
            config.dryBase = request.optDouble("dry_base", config.dryBase);
        }
        if (request.has("dry_allowed_length")) {
            config.dryAllowedLength = request.optInt("dry_allowed_length", config.dryAllowedLength);
        }
        if (request.has("dry_penalty_last_n")) {
            config.dryPenaltyLastN = request.optInt("dry_penalty_last_n", config.dryPenaltyLastN);
        }
        if (request.has("mirostat")) {
            config.mirostat = request.optInt("mirostat", config.mirostat);
        }
        if (request.has("mirostat_tau")) {
            config.mirostatTau = request.optDouble("mirostat_tau", config.mirostatTau);
        }
        if (request.has("mirostat_eta")) {
            config.mirostatEta = request.optDouble("mirostat_eta", config.mirostatEta);
        }
    }

    private void handleWebUi(OutputStream outputStream, String path) throws IOException {
        String assetName = resolveWebUiAssetName(path);
        byte[] assetBytes;
        try {
            assetBytes = loadWebUiAsset(assetName);
        } catch (IOException e) {
            Log.w(TAG, "WebUI asset not found: " + assetName, e);
            sendErrorResponse(outputStream, 404, "Not Found");
            return;
        }
        if (assetBytes == null) {
            sendErrorResponse(outputStream, 404, "Not Found");
            return;
        }
        sendBinaryResponse(outputStream, 200, WEBUI_CONTENT_TYPES.get(assetName), assetBytes);
    }

    private String resolveWebUiAssetName(String path) {
        if (path == null || path.isEmpty() || "/".equals(path) || "/index.html".equals(path)) {
            return "index.html";
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (WEBUI_CONTENT_TYPES.containsKey(normalized)) {
            return normalized;
        }
        return "index.html";
    }

    private byte[] loadWebUiAsset(String assetName) throws IOException {
        byte[] cached = webUiAssetCache.get(assetName);
        if (cached != null) {
            return cached;
        }

        AssetManager assetManager = context.getAssets();
        try (InputStream inputStream = assetManager.open(WEBUI_ASSET_ROOT + assetName);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            byte[] bytes = buffer.toByteArray();
            webUiAssetCache.put(assetName, bytes);
            return bytes;
        }
    }
    
    private void handleGenerate(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = request.optString("model", "default");
            String prompt = request.optString("prompt", "");
            String apiSystem = request.optString("system", null); // Optional system from API
            JSONArray tools = request.optJSONArray("tools");
            boolean stream = request.optBoolean("stream", true);
            boolean hasSharedToolConfig = hasSharedToolConfig();
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "generate request model=" + model + " stream=" + stream + " promptLen=" + prompt.length());
            }
            if (request.has("tools") && tools == null) {
                sendErrorResponse(outputStream, 400, "'tools' must be an array");
                return;
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
                if (abortIfResetRequested(outputStream, "/api/generate")) {
                    return;
                }

                // Structured output: apply format/grammar (GBNF / JSON Schema) to the sampler.
                applyStructuredOutputConstraint(request);

                ConfigurationManager.Configuration config = null;
                try {
                    config = configManager.loadConfiguration(model);
                    // Problem 3: apply per-request sampling overrides (temperature, top_k, …)
                    applyRequestOverrides(config, request);
                    modelManager.applyConfiguration(config);
                } catch (Exception e) {
                    Log.w(TAG, "Could not apply /api/generate request overrides", e);
                }
                // Problem 4: apply custom sampler chain order from request
                applySamplerOrderOverride(request);

                applyNPredictOverride(request, config);

                if (listener != null) {
                    listener.onGenerating(model);
                }

                // Use PromptTemplateManager for prompt generation
                String ggufChatTemplate = modelManager.getLlama().getChatTemplate();
                String customTemplate = (config != null) ? config.customChatTemplate : null;
                String settingsSystemPrompt = (config != null) ? config.systemPrompt : null;
                boolean enableThinking = resolveEnableThinking(request, config);
                String modelPath = modelManager.getCurrentModelPath();

                if ((tools != null && tools.length() > 0) || hasSharedToolConfig) {
                    SharedToolManager.ChatResult toolResult = SharedToolManager.generateWithTools(
                            context,
                            modelManager.getLlama(),
                            buildGenerateMessages(prompt, apiSystem),
                            tools,
                            customTemplate,
                            settingsSystemPrompt,
                            serializeToolChoice(request.opt("tool_choice")),
                            request.optBoolean("parallel_tool_calls", false),
                            enableThinking,
                            new byte[0][],
                            true,
                            true
                    );
                    if (toolResult != null) {
                        sendOllamaGenerateToolResponse(outputStream, model, stream, toolResult);
                        return;
                    }
                }
                
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
                if (abortIfResetRequested(outputStream, "/api/generate")) {
                    return;
                }

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
                    // Think ON → structured mode: <think> content → delta.reasoning_content.
                    // Think OFF → strip mode: all reasoning blocks suppressed.
                    final ThinkBlockStreamFilter thinkBlockFilter = enableThinking
                            ? new ThinkBlockStreamFilter(streamTokenFilter,
                                    tok -> tokenQueue.offer(new ReasoningToken(tok)))
                            : new ThinkBlockStreamFilter(streamTokenFilter);
                    final Thread writerThread = new Thread(() -> {
                        try {
                            while (true) {
                                Object ev = tokenQueue.take();
                                if (ev == TOKEN_COMPLETE) {
                                    if (errorSent[0]) {
                                        break;
                                    }
                                    try {
                                        String metricsSuffix = buildPerfMetricsSuffix();
                                        if (!metricsSuffix.isEmpty()) {
                                            JSONObject metricsChunk = new JSONObject();
                                            metricsChunk.put("model", model);
                                            metricsChunk.put("created_at", getTimestamp());
                                            metricsChunk.put("response", metricsSuffix);
                                            metricsChunk.put("done", false);
                                            byte[] mBytes = (metricsChunk.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                                            synchronized (writeLock) {
                                                outputStream.write((Integer.toHexString(mBytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                                                outputStream.write(mBytes);
                                                outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                                            }
                                        }
                                        JSONObject chunk = new JSONObject();
                                        chunk.put("model", model);
                                        chunk.put("created_at", getTimestamp());
                                        chunk.put("response", "");
                                        chunk.put("done", true);
                                        putOllamaMetrics(chunk);
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
                                     try {
                                        JSONObject chunk = new JSONObject();
                                        chunk.put("model", model);
                                        chunk.put("created_at", getTimestamp());
                                        if (ev instanceof ReasoningToken) {
                                            chunk.put("reasoning_content", ((ReasoningToken) ev).content);
                                            chunk.put("response", "");
                                        } else {
                                            String safeToken = stripResponseMarkers((String) ev);
                                            chunk.put("response", safeToken);
                                            logMaxDebugPayload("api.generate.stream.chunk.response", safeToken);
                                        }
                                        chunk.put("done", false);
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
                            thinkBlockFilter.onToken(token);
                        }

                        @Override
                        public void onComplete() {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "generate stream complete tokens=" + tokenCount);
                            }
                            thinkBlockFilter.onComplete();
                        }

                        @Override
                        public void onError(String error) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "generate stream error: " + error);
                            }
                            thinkBlockFilter.onError(error);
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
                    final String genReasoning;
                    final String genContent;
                    if (enableThinking) {
                        String[] parts = PromptTemplateManager.extractThinkingContent(rawResponse);
                        genReasoning = parts[0];
                        genContent   = parts[1];
                    } else {
                        genReasoning = null;
                        genContent   = PromptTemplateManager.stripThinkingBlocks(rawResponse);
                    }
                    String response = appendPerfMetrics(stripResponseMarkers(genContent));
                    logMaxDebugPayload("api.generate.nonstream.response", response);
                    JSONObject result = new JSONObject();
                    result.put("model", model);
                    result.put("created_at", getTimestamp());
                    result.put("response", response);
                    if (genReasoning != null && !genReasoning.isEmpty()) {
                        result.put("reasoning_content", genReasoning);
                    }
                    result.put("done", true);
                    putOllamaMetrics(result);
                    logMaxDebugPayload("api.generate.nonstream.response.json", result.toString());

                    sendJsonResponse(outputStream, 200, result.toString());
                }
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid JSON in generate request", e);
            sendErrorResponse(outputStream, 400, "Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Tool-enabled generate request failed", e);
            sendErrorResponse(outputStream, 500, e.getMessage() != null ? e.getMessage() : "Tool-enabled generate failed");
        }
    }
    
    private void handleChat(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = request.optString("model", "default");
            JSONArray messages = request.optJSONArray("messages");
            JSONArray tools = request.optJSONArray("tools");
            boolean stream = request.optBoolean("stream", true);
            boolean hasSharedToolConfig = hasSharedToolConfig();
            
            if (messages == null || messages.length() == 0) {
                sendErrorResponse(outputStream, 400, "No messages provided");
                return;
            }
            if (request.has("tools") && tools == null) {
                sendErrorResponse(outputStream, 400, "'tools' must be an array");
                return;
            }
            
            if (!acquireGenerationSlot(outputStream, "/api/chat")) {
                return;
            }
            
            try {
                RequestedModalities requestedModalities = detectRequestedModalities(messages);

                // Load model/configuration if needed (will be fast if same config already loaded)
                if (!modelManager.loadConfiguration(
                        model,
                        requestedModalities.vision,
                        requestedModalities.audio)) {
                    sendErrorResponse(outputStream, 500, "Failed to load configuration: " + model);
                    return;
                }
                if (abortIfResetRequested(outputStream, "/api/chat")) {
                    return;
                }

                // Structured output: apply format/grammar (GBNF / JSON Schema) to the sampler.
                applyStructuredOutputConstraint(request);

                // Get configuration for prompt template settings
                ConfigurationManager.Configuration config = null;
                try {
                    config = configManager.loadConfiguration(model);
                    // Problem 3: apply per-request sampling overrides (temperature, top_k, …)
                    applyRequestOverrides(config, request);
                    modelManager.applyConfiguration(config);
                } catch (Exception e) {
                    Log.w(TAG, "Could not apply /api/chat request overrides", e);
                }
                // Problem 4: apply custom sampler chain order from request
                applySamplerOrderOverride(request);

                applyNPredictOverride(request, config);

                // Use PromptTemplateManager for prompt generation
                String ggufChatTemplate = modelManager.getLlama().getChatTemplate();
                String customTemplate = (config != null) ? config.customChatTemplate : null;
                String settingsSystemPrompt = (config != null) ? config.systemPrompt : null;
                boolean enableThinking = resolveEnableThinking(request, config);
                String modelPath = modelManager.getCurrentModelPath();
                DiagnosticsLogger.logEvent(context, "mm-diag",
                        "endpoint=/api/chat reqVision=" + requestedModalities.vision
                                + " reqAudio=" + requestedModalities.audio
                                + " supportsVision=" + modelManager.supportsVision()
                                + " supportsAudio=" + modelManager.supportsAudio());
                PreparedMessages preparedMessages = normalizeMessagesForMedia(
                        messages,
                        modelManager.supportsVision(),
                        modelManager.supportsAudio()
                );
                DiagnosticsLogger.logEvent(context, "mm-diag",
                        "endpoint=/api/chat mediaExtracted="
                                + (preparedMessages.hasMedia() ? preparedMessages.mediaFiles.size() : 0));
                
                PromptTemplateManager.PromptBuildResult promptResult =
                        PromptTemplateManager.buildPromptFromMessagesWithSelection(
                                preparedMessages.messages,
                                customTemplate,
                                ggufChatTemplate,
                                settingsSystemPrompt,
                                modelPath,
                                enableThinking);
                logTemplateSelection("chat", promptResult.selection);
                String promptToUse = promptResult.prompt;
                logMaxDebugPayload("api.chat.prompt", promptToUse);
                if (abortIfResetRequested(outputStream, "/api/chat")) {
                    return;
                }

                if (listener != null) {
                    listener.onGenerating(model);
                }

                if ((tools != null && tools.length() > 0) || hasSharedToolConfig) {
                    SharedToolManager.ChatResult toolResult = SharedToolManager.generateWithTools(
                            context,
                            modelManager.getLlama(),
                            preparedMessages.messages,
                            tools,
                            customTemplate,
                            settingsSystemPrompt,
                            serializeToolChoice(request.opt("tool_choice")),
                            request.optBoolean("parallel_tool_calls", false),
                            enableThinking,
                            preparedMessages.toMediaArray(),
                            true,
                            true
                    );
                    if (toolResult != null) {
                        sendOllamaChatToolResponse(outputStream, model, stream, toolResult);
                        return;
                    }
                }

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
                    // Think ON → structured mode: <think> content → message.reasoning_content.
                    // Think OFF → strip mode: all reasoning blocks suppressed.
                    final ThinkBlockStreamFilter thinkBlockFilter = enableThinking
                            ? new ThinkBlockStreamFilter(streamTokenFilter,
                                    tok -> tokenQueue.offer(new ReasoningToken(tok)))
                            : new ThinkBlockStreamFilter(streamTokenFilter);
                    final Thread writerThread = new Thread(() -> {
                        try {
                            while (true) {
                                Object ev = tokenQueue.take();
                                if (ev == TOKEN_COMPLETE) {
                                    if (errorSent[0]) {
                                        break;
                                    }
                                    try {
                                        String metricsSuffix = buildPerfMetricsSuffix();
                                        if (!metricsSuffix.isEmpty()) {
                                            JSONObject metricsChunk = new JSONObject();
                                            metricsChunk.put("model", model);
                                            metricsChunk.put("created_at", getTimestamp());
                                            JSONObject metricsMsg = new JSONObject();
                                            metricsMsg.put("role", "assistant");
                                            metricsMsg.put("content", metricsSuffix);
                                            metricsChunk.put("message", metricsMsg);
                                            metricsChunk.put("done", false);
                                            byte[] mBytes = (metricsChunk.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                                            synchronized (writeLock) {
                                                outputStream.write((Integer.toHexString(mBytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                                                outputStream.write(mBytes);
                                                outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                                            }
                                        }
                                        JSONObject chunk = new JSONObject();
                                        chunk.put("model", model);
                                        chunk.put("created_at", getTimestamp());

                                        JSONObject message = new JSONObject();
                                        message.put("role", "assistant");
                                        message.put("content", "");
                                        chunk.put("message", message);
                                        chunk.put("done", true);
                                        putOllamaMetrics(chunk);

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
                                     try {
                                        JSONObject chunk = new JSONObject();
                                        chunk.put("model", model);
                                        chunk.put("created_at", getTimestamp());

                                        JSONObject message = new JSONObject();
                                        message.put("role", "assistant");
                                        if (ev instanceof ReasoningToken) {
                                            message.put("content", "");
                                            message.put("reasoning_content", ((ReasoningToken) ev).content);
                                        } else {
                                            String safeToken = stripResponseMarkers((String) ev);
                                            message.put("content", safeToken);
                                            logMaxDebugPayload("api.chat.stream.chunk.content", safeToken);
                                        }
                                        chunk.put("message", message);
                                        chunk.put("done", false);

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
                            thinkBlockFilter.onToken(token);
                        }

                        @Override
                        public void onComplete() {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "chat stream complete tokens=" + tokenCount);
                            }
                            thinkBlockFilter.onComplete();
                        }

                        @Override
                        public void onError(String error) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "chat stream error: " + error);
                            }
                            thinkBlockFilter.onError(error);
                        }
                    });

                    try {
                        modelManager.generate(promptToUse, preparedMessages.toMediaArray());
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
                    String rawResponse = modelManager.generate(promptToUse, preparedMessages.toMediaArray());
                    logMaxDebugPayload("api.chat.nonstream.model.raw", rawResponse);
                    final String chatReasoning;
                    final String chatContent;
                    if (enableThinking) {
                        String[] parts = PromptTemplateManager.extractThinkingContent(rawResponse);
                        chatReasoning = parts[0];
                        chatContent   = parts[1];
                    } else {
                        chatReasoning = null;
                        chatContent   = PromptTemplateManager.stripThinkingBlocks(rawResponse);
                    }
                    String response = appendPerfMetrics(stripResponseMarkers(chatContent));
                    logMaxDebugPayload("api.chat.nonstream.response", response);

                    JSONObject result = new JSONObject();
                    result.put("model", model);
                    result.put("created_at", getTimestamp());

                    JSONObject message = new JSONObject();
                    message.put("role", "assistant");
                    message.put("content", response);
                    if (chatReasoning != null && !chatReasoning.isEmpty()) {
                        message.put("reasoning_content", chatReasoning);
                    }
                    result.put("message", message);
                    result.put("done", true);
                    putOllamaMetrics(result);
                    logMaxDebugPayload("api.chat.nonstream.response.json", result.toString());

                    sendJsonResponse(outputStream, 200, result.toString());
                }
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid JSON in chat request", e);
            sendErrorResponse(outputStream, 400, "Invalid JSON: " + e.getMessage());
        } catch (InvalidMediaException e) {
            Log.e(TAG, "Invalid media in chat request", e);
            sendErrorResponse(outputStream, 400, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Tool-enabled chat request failed", e);
            sendErrorResponse(outputStream, 500, e.getMessage() != null ? e.getMessage() : "Tool-enabled chat failed");
        }
    }

    private void handleOpenAiChat(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = resolveRequestedModel(request.optString("model", null));
            JSONArray messages = request.optJSONArray("messages");
            JSONArray tools = request.optJSONArray("tools");
            boolean stream = request.optBoolean("stream", true);
            boolean preEncodeOnly = request.has("n_predict") && request.optInt("n_predict", -1) == 0;

            if (messages == null || messages.length() == 0) {
                sendOpenAiErrorResponse(outputStream, 400, "No messages provided", "invalid_request_error");
                return;
            }
            if (request.has("tools") && tools == null) {
                sendOpenAiErrorResponse(outputStream, 400, "'tools' must be an array", "invalid_request_error");
                return;
            }

            if (!acquireGenerationSlot(outputStream, "/v1/chat/completions", true)) {
                return;
            }

            try {
                RequestedModalities requestedModalities = detectRequestedModalities(messages);

                if (!modelManager.loadConfiguration(
                        model,
                        requestedModalities.vision,
                        requestedModalities.audio)) {
                    sendOpenAiErrorResponse(outputStream, 500, "Failed to load configuration: " + model, "server_error");
                    return;
                }
                if (modelManager.isResetPendingOrInProgress()) {
                    sendOpenAiErrorResponse(outputStream, 503, "Model reset requested", "unavailable_error");
                    return;
                }

                ConfigurationManager.Configuration config = null;
                try {
                    config = configManager.loadConfiguration(model);
                    applyRequestOverrides(config, request);
                    modelManager.applyConfiguration(config);
                } catch (Exception e) {
                    Log.w(TAG, "Could not apply OpenAI request overrides", e);
                }
                // Problem 4: apply custom sampler chain order from request
                applySamplerOrderOverride(request);

                applyNPredictOverride(request, config);

                String ggufChatTemplate = modelManager.getLlama().getChatTemplate();
                String customTemplate = config != null ? config.customChatTemplate : null;
                String settingsSystemPrompt = config != null ? config.systemPrompt : null;
                boolean enableThinking = resolveEnableThinking(request, config);
                DiagnosticsLogger.logEvent(context, "mm-diag",
                        "endpoint=/v1/chat reqVision=" + requestedModalities.vision
                                + " reqAudio=" + requestedModalities.audio
                                + " supportsVision=" + modelManager.supportsVision()
                                + " supportsAudio=" + modelManager.supportsAudio());
                PreparedMessages preparedMessages = normalizeMessagesForMedia(
                        messages,
                        modelManager.supportsVision(),
                        modelManager.supportsAudio()
                );
                DiagnosticsLogger.logEvent(context, "mm-diag",
                        "endpoint=/v1/chat mediaExtracted="
                                + (preparedMessages.hasMedia() ? preparedMessages.mediaFiles.size() : 0));

                if (preEncodeOnly) {
                    sendJsonResponse(outputStream, 200, buildOpenAiChatResponse(model, "").toString());
                    return;
                }

                boolean hasSharedToolConfig = hasSharedToolConfig();
                if ((tools != null && tools.length() > 0) || hasSharedToolConfig) {
                    SharedToolManager.ChatResult toolResult = SharedToolManager.generateWithTools(
                            context,
                            modelManager.getLlama(),
                            preparedMessages.messages,
                            tools,
                            customTemplate,
                            settingsSystemPrompt,
                            serializeToolChoice(request.opt("tool_choice")),
                            request.optBoolean("parallel_tool_calls", false),
                            enableThinking,
                            preparedMessages.toMediaArray(),
                            true,
                            true
                    );
                    if (toolResult != null) {
                        if (stream) {
                            String header = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/event-stream\r\n" +
                                    "Cache-Control: no-cache\r\n" +
                                    "Connection: keep-alive\r\n" +
                                    "Access-Control-Allow-Origin: *\r\n" +
                                    "\r\n";
                            outputStream.write(header.getBytes(StandardCharsets.UTF_8));
                            outputStream.flush();
                            sendSseEvent(outputStream, buildOpenAiRoleChunk(model).toString());

                            if ((toolResult.content != null && !toolResult.content.isEmpty())
                                    || (toolResult.reasoningContent != null && !toolResult.reasoningContent.isEmpty())
                                    || (toolResult.toolCalls != null && toolResult.toolCalls.length() > 0)) {
                                sendSseEvent(
                                        outputStream,
                                        buildOpenAiStreamChunk(
                                                model,
                                                toolResult.content,
                                                toolResult.reasoningContent,
                                                buildToolCallDeltas(toolResult.toolCalls),
                                                null
                                        ).toString()
                                );
                            }

                            sendSseEvent(
                                    outputStream,
                                    buildOpenAiStreamChunk(
                                            model,
                                            "",
                                            null,
                                            null,
                                            toolResult.finishReason
                                    ).toString()
                            );
                            sendSseEvent(outputStream, "[DONE]");
                        } else {
                            sendJsonResponse(
                                    outputStream,
                                    200,
                                    buildOpenAiChatResponse(
                                            model,
                                            toolResult.content,
                                            toolResult.reasoningContent,
                                            toolResult.toolCalls,
                                            toolResult.finishReason
                                    ).toString()
                            );
                        }
                        return;
                    }
                }

                String modelPath = modelManager.getCurrentModelPath();
                PromptTemplateManager.PromptBuildResult promptResult =
                        PromptTemplateManager.buildPromptFromMessagesWithSelection(
                                preparedMessages.messages,
                                customTemplate,
                                ggufChatTemplate,
                                settingsSystemPrompt,
                                modelPath,
                                enableThinking);
                logTemplateSelection("chat.completions", promptResult.selection);
                String promptToUse = promptResult.prompt;
                logMaxDebugPayload("openai.chat.prompt", promptToUse);

                if (stream) {
                    String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "\r\n";
                    outputStream.write(header.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                    sendSseEvent(outputStream, buildOpenAiRoleChunk(model).toString());

                    final Object writeLock = new Object();
                    final boolean[] errorSent = { false };
                    final AtomicBoolean clientDisconnected = new AtomicBoolean(false);
                    final Runnable onClientDisconnected = () -> {
                        if (clientDisconnected.compareAndSet(false, true)) {
                            Log.w(TAG, "Client disconnected during /v1/chat/completions stream");
                            modelManager.getLlama().cancelGeneration();
                        }
                    };
                    final java.util.concurrent.LinkedBlockingQueue<Object> tokenQueue = new java.util.concurrent.LinkedBlockingQueue<>();
                    final StreamTokenFilter streamTokenFilter = new StreamTokenFilter(
                            tokenQueue,
                            () -> modelManager.getLlama().cancelGeneration()
                    );
                    // Think ON → structured mode: <think> content → delta.reasoning_content.
                    // Think OFF → strip mode: all reasoning blocks suppressed.
                    final ThinkBlockStreamFilter thinkBlockFilter = enableThinking
                            ? new ThinkBlockStreamFilter(streamTokenFilter,
                                    tok -> tokenQueue.offer(new ReasoningToken(tok)))
                            : new ThinkBlockStreamFilter(streamTokenFilter);
                    final Thread writerThread = new Thread(() -> {
                        try {
                            while (true) {
                                Object ev = tokenQueue.take();
                                if (ev == TOKEN_COMPLETE) {
                                    synchronized (writeLock) {
                                        String metricsSuffix = buildPerfMetricsSuffix();
                                        if (!metricsSuffix.isEmpty()) {
                                            sendSseEvent(outputStream, buildOpenAiStreamChunk(model, metricsSuffix, null, null, null).toString());
                                        }
                                        sendSseEvent(
                                                outputStream,
                                                buildOpenAiStreamChunk(model, "", null, null, "stop").toString()
                                        );
                                        sendSseEvent(outputStream, "[DONE]");
                                    }
                                    break;
                                } else if (ev instanceof TokenError) {
                                    TokenError tokenError = (TokenError) ev;
                                    errorSent[0] = true;
                                    synchronized (writeLock) {
                                        sendSseEvent(outputStream, buildOpenAiErrorObject(500, tokenError.error, "server_error").toString());
                                        sendSseEvent(outputStream, "[DONE]");
                                    }
                                    break;
                                } else if (ev instanceof ReasoningToken) {
                                    synchronized (writeLock) {
                                        sendSseEvent(outputStream,
                                                buildOpenAiStreamChunk(model, null,
                                                        ((ReasoningToken) ev).content, null, null).toString());
                                    }
                                } else {
                                    String token = stripResponseMarkers((String) ev);
                                    if (token.isEmpty()) {
                                        continue;
                                    }
                                    synchronized (writeLock) {
                                        sendSseEvent(
                                                outputStream,
                                                buildOpenAiStreamChunk(model, token, null, null, null).toString()
                                        );
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error writing OpenAI chat stream", e);
                            onClientDisconnected.run();
                        } finally {
                            try {
                                outputStream.flush();
                            } catch (Exception ignored) {
                            }
                        }
                    }, "OpenAiApiWriter-" + Thread.currentThread().getId());
                    writerThread.start();

                    modelManager.getLlama().setTokenListener(new LlamaNative.TokenListener() {
                        @Override
                        public void onToken(String token) {
                            thinkBlockFilter.onToken(token);
                        }

                        @Override
                        public void onComplete() {
                            thinkBlockFilter.onComplete();
                        }

                        @Override
                        public void onError(String error) {
                            thinkBlockFilter.onError(error);
                        }
                    });

                    try {
                        modelManager.generate(promptToUse, preparedMessages.toMediaArray());
                    } finally {
                        modelManager.getLlama().setTokenListener(null);
                        if (!errorSent[0] && !clientDisconnected.get()) {
                            tokenQueue.offer(TOKEN_COMPLETE);
                        }
                    }

                    try {
                        writerThread.join(5000);
                        if (writerThread.isAlive()) {
                            Log.w(TAG, "OpenAI writer thread did not finish before timeout");
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "OpenAI writer thread join interrupted", ie);
                    }
                } else {
                    String rawResponse = modelManager.generate(promptToUse, preparedMessages.toMediaArray());
                    final String oaiReasoning;
                    final String oaiContent;
                    if (enableThinking) {
                        String[] parts = PromptTemplateManager.extractThinkingContent(rawResponse);
                        oaiReasoning = parts[0];
                        oaiContent   = parts[1];
                    } else {
                        oaiReasoning = null;
                        oaiContent   = PromptTemplateManager.stripThinkingBlocks(rawResponse);
                    }
                    String response = appendPerfMetrics(stripResponseMarkers(oaiContent));
                    sendJsonResponse(outputStream, 200,
                            buildOpenAiChatResponse(model, response,
                                    (oaiReasoning != null && !oaiReasoning.isEmpty()) ? oaiReasoning : null,
                                    null, "stop").toString());
                }
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid JSON in OpenAI chat request", e);
            sendOpenAiErrorResponse(outputStream, 400, "Invalid JSON: " + e.getMessage(), "invalid_request_error");
        } catch (InvalidMediaException e) {
            Log.e(TAG, "Invalid media in OpenAI chat request", e);
            sendOpenAiErrorResponse(outputStream, 400, e.getMessage(), "invalid_request_error");
        } catch (Exception e) {
            Log.e(TAG, "OpenAI chat request failed", e);
            sendOpenAiErrorResponse(
                    outputStream,
                    500,
                    e.getMessage() != null ? e.getMessage() : "OpenAI chat request failed",
                    "server_error");
        }
    }

    // ==================== Embeddings ====================

    /** Ollama /api/embed : { "model", "input": string | [string,...] } -> { "model", "embeddings": [[...]] } */
    private void handleEmbed(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = request.optString("model", "default");
            List<String> inputs = extractEmbeddingInputs(request.opt("input"));
            if (inputs.isEmpty()) {
                sendErrorResponse(outputStream, 400, "'input' is required");
                return;
            }
            if (!acquireGenerationSlot(outputStream, "/api/embed")) {
                return;
            }
            try {
                if (!modelManager.loadConfiguration(model)) {
                    sendErrorResponse(outputStream, 500, "Failed to load configuration: " + model);
                    return;
                }
                JSONArray embeddings = new JSONArray();
                for (String in : inputs) {
                    float[] vec = computeEmbeddingOrNull(in);
                    if (vec == null) {
                        sendErrorResponse(outputStream, 500, "Embedding generation failed");
                        return;
                    }
                    embeddings.put(floatArrayToJson(vec));
                }
                JSONObject result = new JSONObject();
                result.put("model", model);
                result.put("embeddings", embeddings);
                sendJsonResponse(outputStream, 200, result.toString());
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            sendErrorResponse(outputStream, 400, "Invalid JSON: " + e.getMessage());
        }
    }

    /** Ollama legacy /api/embeddings : { "model", "prompt" } -> { "embedding": [...] } */
    private void handleEmbeddings(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = request.optString("model", "default");
            String prompt = request.optString("prompt", "");
            if (prompt.isEmpty()) {
                sendErrorResponse(outputStream, 400, "'prompt' is required");
                return;
            }
            if (!acquireGenerationSlot(outputStream, "/api/embeddings")) {
                return;
            }
            try {
                if (!modelManager.loadConfiguration(model)) {
                    sendErrorResponse(outputStream, 500, "Failed to load configuration: " + model);
                    return;
                }
                float[] vec = computeEmbeddingOrNull(prompt);
                if (vec == null) {
                    sendErrorResponse(outputStream, 500, "Embedding generation failed");
                    return;
                }
                JSONObject result = new JSONObject();
                result.put("embedding", floatArrayToJson(vec));
                sendJsonResponse(outputStream, 200, result.toString());
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            sendErrorResponse(outputStream, 400, "Invalid JSON: " + e.getMessage());
        }
    }

    /** OpenAI /v1/embeddings : { "model", "input": string | [string,...] } -> { object:list, data:[...] } */
    private void handleOpenAiEmbeddings(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = request.optString("model", "default");
            List<String> inputs = extractEmbeddingInputs(request.opt("input"));
            if (inputs.isEmpty()) {
                sendOpenAiErrorResponse(outputStream, 400, "'input' is required", "invalid_request_error");
                return;
            }
            if (!acquireGenerationSlot(outputStream, "/v1/embeddings", true)) {
                return;
            }
            try {
                if (!modelManager.loadConfiguration(model)) {
                    sendOpenAiErrorResponse(outputStream, 500, "Failed to load configuration: " + model, "server_error");
                    return;
                }
                JSONArray data = new JSONArray();
                for (int i = 0; i < inputs.size(); i++) {
                    float[] vec = computeEmbeddingOrNull(inputs.get(i));
                    if (vec == null) {
                        sendOpenAiErrorResponse(outputStream, 500, "Embedding generation failed", "server_error");
                        return;
                    }
                    JSONObject item = new JSONObject();
                    item.put("object", "embedding");
                    item.put("index", i);
                    item.put("embedding", floatArrayToJson(vec));
                    data.put(item);
                }
                JSONObject result = new JSONObject();
                result.put("object", "list");
                result.put("data", data);
                result.put("model", model);
                result.put("usage", new JSONObject().put("prompt_tokens", 0).put("total_tokens", 0));
                sendJsonResponse(outputStream, 200, result.toString());
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            sendOpenAiErrorResponse(outputStream, 400, "Invalid JSON: " + e.getMessage(), "invalid_request_error");
        }
    }

    private List<String> extractEmbeddingInputs(Object input) {
        List<String> out = new ArrayList<>();
        if (input instanceof String) {
            String s = (String) input;
            if (!s.isEmpty()) {
                out.add(s);
            }
        } else if (input instanceof JSONArray) {
            JSONArray arr = (JSONArray) input;
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Calls native embed and parses {"embedding":[...]}; returns null on error (caller holds the slot). */
    private float[] computeEmbeddingOrNull(String text) {
        String json = modelManager.embed(text);
        if (json == null) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.optJSONArray("embedding");
            if (arr == null) {
                Log.w(TAG, "embed returned error: " + obj.optString("error", json));
                return null;
            }
            float[] vec = new float[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                vec[i] = (float) arr.optDouble(i, 0.0);
            }
            return vec;
        } catch (JSONException e) {
            Log.w(TAG, "embed parse error", e);
            return null;
        }
    }

    private JSONArray floatArrayToJson(float[] vec) throws JSONException {
        JSONArray arr = new JSONArray();
        for (float v : vec) {
            arr.put(Float.isFinite(v) ? (double) v : 0.0);
        }
        return arr;
    }

    /** Ollama /api/tokenize : { "model", "content": string } -> { "tokens": [...] } */
    private void handleOllamaTokenize(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = request.optString("model", "default");
            String content = request.optString("content", "");
            if (content.isEmpty()) {
                sendErrorResponse(outputStream, 400, "'content' is required");
                return;
            }
            if (!acquireGenerationSlot(outputStream, "/api/tokenize")) {
                return;
            }
            try {
                if (!modelManager.loadConfiguration(model)) {
                    sendErrorResponse(outputStream, 500, "Failed to load configuration: " + model);
                    return;
                }
                String resultJson = modelManager.getLlama().tokenize(content);
                JSONObject parsed = new JSONObject(resultJson);
                if (parsed.has("error")) {
                    sendErrorResponse(outputStream, 500, parsed.getString("error"));
                    return;
                }
                JSONObject result = new JSONObject();
                result.put("tokens", parsed.getJSONArray("ids"));
                sendJsonResponse(outputStream, 200, result.toString());
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            sendErrorResponse(outputStream, 400, "Invalid JSON: " + e.getMessage());
        }
    }

    /** OpenAI /v1/tokenize : { "model", "input": string } -> { "tokens": [...], "count": N, "ids": [...] } */
    private void handleOpenAiTokenize(OutputStream outputStream, String body) throws IOException {
        try {
            JSONObject request = new JSONObject(body);
            String model = resolveRequestedModel(request.optString("model", null));
            String input = request.optString("input", "");
            if (input.isEmpty()) {
                sendOpenAiErrorResponse(outputStream, 400, "'input' is required", "invalid_request_error");
                return;
            }
            if (!acquireGenerationSlot(outputStream, "/v1/tokenize", true)) {
                return;
            }
            try {
                if (!modelManager.loadConfiguration(model)) {
                    sendOpenAiErrorResponse(outputStream, 500, "Failed to load configuration: " + model, "server_error");
                    return;
                }
                String resultJson = modelManager.getLlama().tokenize(input);
                JSONObject parsed = new JSONObject(resultJson);
                if (parsed.has("error")) {
                    sendOpenAiErrorResponse(outputStream, 500, parsed.getString("error"), "server_error");
                    return;
                }
                sendJsonResponse(outputStream, 200, resultJson);
            } finally {
                modelManager.release();
            }
        } catch (JSONException e) {
            sendOpenAiErrorResponse(outputStream, 400, "Invalid JSON: " + e.getMessage(), "invalid_request_error");
        }
    }

    /**
     * Applies the structured-output constraint from an /api/chat or /api/generate request to the
     * native sampler for the upcoming generation. Reads "grammar" (raw GBNF) and "format"
     * (Ollama: the string "json" or a JSON Schema object). Always set-or-clears, so a constraint
     * from a previous request never leaks into the next one.
     */
    private void applyStructuredOutputConstraint(JSONObject request) {
        String gbnf = "";
        String schema = "";
        try {
            gbnf = request.optString("grammar", "");
            Object format = request.opt("format");
            if (format instanceof JSONObject) {
                schema = format.toString();
            } else if (format instanceof String) {
                String f = ((String) format).trim();
                if ("json".equalsIgnoreCase(f)) {
                    schema = "{\"type\":\"object\"}";
                } else if (f.startsWith("{")) {
                    schema = f;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyStructuredOutputConstraint parse failed", t);
        }
        modelManager.setGrammarConstraint(gbnf, schema);
    }

    private boolean acquireGenerationSlot(OutputStream outputStream, String endpoint) throws IOException {
        return acquireGenerationSlot(outputStream, endpoint, false);
    }

    private boolean acquireGenerationSlot(OutputStream outputStream, String endpoint, boolean openAiStyle) throws IOException {
        if (modelManager.isResetPendingOrInProgress()) {
            Log.i(TAG, "Rejecting request while model reset is pending for " + endpoint);
            sendBusyResponse(outputStream, 503, "Model reset in progress", openAiStyle);
            return false;
        }

        if (modelManager.tryAcquire()) {
            return true;
        }

        int queued = waitingRequestCount.incrementAndGet();
        if (queued > MAX_BUSY_QUEUE_SIZE) {
            waitingRequestCount.decrementAndGet();
            Log.w(TAG, "Busy queue full for " + endpoint + " queued=" + queued);
            sendBusyResponse(outputStream, 503, "Model busy queue is full (max 10)", openAiStyle);
            return false;
        }

        long deadline = System.currentTimeMillis() + BUSY_QUEUE_WAIT_MS;
        boolean acquired = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (modelManager.isResetPendingOrInProgress()) {
                    break;
                }
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

        if (modelManager.isResetPendingOrInProgress()) {
            Log.i(TAG, "Aborting queued request while model reset is pending for " + endpoint);
            sendBusyResponse(outputStream, 503, "Model reset in progress", openAiStyle);
            return false;
        }

        if (!acquired) {
            Log.w(TAG, "Busy queue timeout for " + endpoint + " waitedMs=" + BUSY_QUEUE_WAIT_MS);
            sendBusyResponse(outputStream, 503, "Model is busy; queue wait timed out after 60 seconds", openAiStyle);
            return false;
        }

        return true;
    }

    private void sendBusyResponse(OutputStream outputStream, int statusCode, String message, boolean openAiStyle)
            throws IOException {
        if (openAiStyle) {
            sendOpenAiErrorResponse(outputStream, statusCode, message, "unavailable_error");
        } else {
            sendErrorResponse(outputStream, statusCode, message);
        }
    }

    private boolean abortIfResetRequested(OutputStream outputStream, String endpoint) throws IOException {
        if (!modelManager.isResetPendingOrInProgress()) {
            return false;
        }
        Log.i(TAG, endpoint + ": aborting request because model reset was requested");
        sendErrorResponse(outputStream, 503, "Model reset requested");
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

    private void sendBinaryResponse(OutputStream outputStream, int statusCode, String contentType, byte[] body)
            throws IOException {
        String status = statusCode == 200 ? "OK" : (statusCode == 404 ? "Not Found" : "Error");
        String response = "HTTP/1.1 " + statusCode + " " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Cross-Origin-Embedder-Policy: require-corp\r\n" +
                "Cross-Origin-Opener-Policy: same-origin\r\n" +
                "\r\n";
        outputStream.write(response.getBytes(StandardCharsets.UTF_8));
        outputStream.write(body);
        outputStream.flush();
    }

    private void sendSseEvent(OutputStream outputStream, String data) throws IOException {
        outputStream.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private JSONObject buildOpenAiChatResponse(String model, String content) throws JSONException {
        return buildOpenAiChatResponse(model, content, null, null, "stop");
    }

    private void sendOllamaChatToolResponse(
            OutputStream outputStream,
            String model,
            boolean stream,
            SharedToolManager.ChatResult toolResult
    ) throws IOException, JSONException {
        JSONObject chunk = buildOllamaChatResponse(model, toolResult);
        if (!stream) {
            sendJsonResponse(outputStream, 200, chunk.toString());
            return;
        }

        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-ndjson\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n";
        outputStream.write(header.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();

        byte[] chunkBytes = (chunk.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        String chunkSize = Integer.toHexString(chunkBytes.length) + "\r\n";
        outputStream.write(chunkSize.getBytes(StandardCharsets.UTF_8));
        outputStream.write(chunkBytes);
        outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
        outputStream.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private void sendOllamaGenerateToolResponse(
            OutputStream outputStream,
            String model,
            boolean stream,
            SharedToolManager.ChatResult toolResult
    ) throws IOException, JSONException {
        JSONObject chunk = buildOllamaGenerateResponse(model, toolResult);
        if (!stream) {
            sendJsonResponse(outputStream, 200, chunk.toString());
            return;
        }

        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-ndjson\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n";
        outputStream.write(header.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();

        byte[] chunkBytes = (chunk.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        String chunkSize = Integer.toHexString(chunkBytes.length) + "\r\n";
        outputStream.write(chunkSize.getBytes(StandardCharsets.UTF_8));
        outputStream.write(chunkBytes);
        outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
        outputStream.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private JSONObject buildOllamaChatResponse(
            String model,
            SharedToolManager.ChatResult toolResult
    ) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("model", model);
        result.put("created_at", getTimestamp());

        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", toolResult.content != null ? toolResult.content : "");
        if (toolResult.reasoningContent != null && !toolResult.reasoningContent.isEmpty()) {
            message.put("reasoning_content", toolResult.reasoningContent);
        }
        if (toolResult.toolCalls != null && toolResult.toolCalls.length() > 0) {
            message.put("tool_calls", toolResult.toolCalls);
        }

        result.put("message", message);
        result.put("done", true);
        result.put("done_reason", toolResult.finishReason != null ? toolResult.finishReason : "stop");
        putOllamaMetrics(result);
        return result;
    }

    private JSONObject buildOllamaGenerateResponse(
            String model,
            SharedToolManager.ChatResult toolResult
    ) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("model", model);
        result.put("created_at", getTimestamp());
        result.put(
                "response",
                stripResponseMarkers(toolResult.content != null ? toolResult.content : "")
        );
        if (toolResult.reasoningContent != null && !toolResult.reasoningContent.isEmpty()) {
            result.put("reasoning_content", toolResult.reasoningContent);
        }
        if (toolResult.toolCalls != null && toolResult.toolCalls.length() > 0) {
            result.put("tool_calls", toolResult.toolCalls);
        }
        result.put("done", true);
        result.put("done_reason", toolResult.finishReason != null ? toolResult.finishReason : "stop");
        putOllamaMetrics(result);
        return result;
    }

    private JSONObject buildOpenAiChatResponse(
            String model,
            String content,
            String reasoningContent,
            JSONArray toolCalls,
            String finishReason
    ) throws JSONException {
        JSONObject response = new JSONObject();
        response.put("object", "chat.completion");
        response.put("model", model);

        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", content != null ? content : "");
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            message.put("reasoning_content", reasoningContent);
        }
        if (toolCalls != null && toolCalls.length() > 0) {
            message.put("tool_calls", toolCalls);
        }

        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason != null ? finishReason : "stop");

        JSONArray choices = new JSONArray();
        choices.put(choice);
        response.put("choices", choices);
        putOpenAiUsage(response);
        return response;
    }

    /** Adds an OpenAI-style {@code usage} object (prompt/completion/total tokens) from the last generation. */
    private void putOpenAiUsage(JSONObject response) {
        try {
            LlamaNative llama = modelManager.getLlama();
            if (llama == null) return;
            int promptTokens = llama.getLastNPromptTokens();
            int completionTokens = llama.getLastNEvalTokens();
            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", promptTokens);
            usage.put("completion_tokens", completionTokens);
            usage.put("total_tokens", promptTokens + completionTokens);
            response.put("usage", usage);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to add OpenAI usage", e);
        }
    }

    private JSONObject buildOpenAiStreamChunk(String model, String content, boolean done) throws JSONException {
        return buildOpenAiStreamChunk(model, content, null, null, done ? "stop" : null);
    }

    private JSONObject buildOpenAiStreamChunk(
            String model,
            String content,
            String reasoningContent,
            JSONArray toolCalls,
            String finishReason
    ) throws JSONException {
        JSONObject response = new JSONObject();
        response.put("object", "chat.completion.chunk");
        response.put("model", model);

        JSONObject delta = new JSONObject();
        if (content != null && !content.isEmpty()) {
            delta.put("content", content);
        }
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            delta.put("reasoning_content", reasoningContent);
        }
        if (toolCalls != null && toolCalls.length() > 0) {
            delta.put("tool_calls", toolCalls);
        }

        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason != null ? finishReason : JSONObject.NULL);

        JSONArray choices = new JSONArray();
        choices.put(choice);
        response.put("choices", choices);
        return response;
    }

    private JSONObject buildOpenAiRoleChunk(String model) throws JSONException {
        JSONObject response = new JSONObject();
        response.put("object", "chat.completion.chunk");
        response.put("model", model);

        JSONObject delta = new JSONObject();
        delta.put("role", "assistant");

        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", JSONObject.NULL);

        JSONArray choices = new JSONArray();
        choices.put(choice);
        response.put("choices", choices);
        return response;
    }

    private JSONObject buildOpenAiErrorObject(int statusCode, String message, String type) throws JSONException {
        JSONObject error = new JSONObject();
        error.put("message", message);
        error.put("type", type);
        error.put("code", statusCode);

        JSONObject wrapper = new JSONObject();
        wrapper.put("error", error);
        return wrapper;
    }

    private void sendOpenAiErrorResponse(OutputStream outputStream, int statusCode, String message, String type)
            throws IOException {
        try {
            sendJsonResponse(outputStream, statusCode, buildOpenAiErrorObject(statusCode, message, type).toString());
        } catch (JSONException e) {
            sendErrorResponse(outputStream, statusCode, message);
        }
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
