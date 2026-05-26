package com.micklab.llama;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class HuggingFaceApiClient {
    private static final String API_BASE_URL = "https://huggingface.co/api";
    private static final String WEB_BASE_URL = "https://huggingface.co";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int SEARCH_RESULT_LIMIT = 30;

    private HuggingFaceApiClient() {
    }

    public static List<ModelSearchResult> searchGgufModels(String query) throws IOException, JSONException {
        String trimmedQuery = query != null ? query.trim() : "";
        JSONArray items = readModelSearchPage(trimmedQuery);
        List<ModelSearchResult> results = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }

            String repoId = item.optString("id", "").trim();
            if (repoId.isEmpty() || !hasGgufTag(item.optJSONArray("tags"))) {
                continue;
            }

            results.add(new ModelSearchResult(
                    repoId,
                    item.optLong("downloads", 0L),
                    item.optLong("likes", 0L),
                    item.optString("pipeline_tag", "")));
        }
        return results;
    }

    public static RepositoryFiles getRepositoryGgufFiles(String repoId) throws IOException, JSONException {
        String normalizedRepoId = repoId != null ? repoId.trim() : "";
        if (normalizedRepoId.isEmpty()) {
            throw new IOException("Repository id is empty");
        }

        JSONObject response = readJsonObject(API_BASE_URL + "/models/" + normalizedRepoId);
        JSONArray siblings = response.optJSONArray("siblings");
        if (siblings == null) {
            throw new IOException("Repository response does not contain file metadata");
        }

        String revision = response.optString("sha", "main");
        List<GgufFileInfo> modelFiles = new ArrayList<>();
        for (int i = 0; i < siblings.length(); i++) {
            JSONObject sibling = siblings.optJSONObject(i);
            if (sibling == null) {
                continue;
            }

            String filename = sibling.optString("rfilename", "").trim();
            if (!ModelFileHelper.isGgufFilename(filename)) {
                continue;
            }

            GgufFileInfo fileInfo = new GgufFileInfo(
                    filename,
                    buildResolveUrl(normalizedRepoId, revision, filename),
                    ModelFileHelper.isLikelyProjectorFilename(filename));
            if (!fileInfo.isProjector()) {
                modelFiles.add(fileInfo);
            }
        }

        Collections.sort(modelFiles, Comparator.comparing(GgufFileInfo::getFilename, String.CASE_INSENSITIVE_ORDER));
        return new RepositoryFiles(normalizedRepoId, revision, modelFiles);
    }

    private static boolean hasGgufTag(JSONArray tags) {
        if (tags == null) {
            return false;
        }
        for (int i = 0; i < tags.length(); i++) {
            if ("gguf".equalsIgnoreCase(tags.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    private static String buildResolveUrl(String repoId, String revision, String filename) {
        Uri.Builder builder = Uri.parse(WEB_BASE_URL).buildUpon();
        appendPathSegments(builder, repoId);
        builder.appendPath("resolve");
        builder.appendPath(revision != null && !revision.isEmpty() ? revision : "main");
        appendPathSegments(builder, filename);
        builder.appendQueryParameter("download", "true");
        return builder.build().toString();
    }

    private static void appendPathSegments(Uri.Builder builder, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (!segment.isEmpty()) {
                builder.appendPath(segment);
            }
        }
    }

    private static JSONArray readModelSearchPage(String query) throws IOException, JSONException {
        Uri.Builder builder = Uri.parse(API_BASE_URL + "/models").buildUpon()
                .appendQueryParameter("filter", "gguf")
                .appendQueryParameter("limit", String.valueOf(SEARCH_RESULT_LIMIT));
        if (!query.isEmpty()) {
            builder.appendQueryParameter("search", query);
        }
        Object parsed = readJsonValue(builder.build().toString());
        if (parsed instanceof JSONArray) {
            return (JSONArray) parsed;
        }
        throw new IOException("Unexpected Hugging Face search response");
    }

    private static JSONObject readJsonObject(String url) throws IOException, JSONException {
        Object parsed = readJsonValue(url);
        if (parsed instanceof JSONObject) {
            return (JSONObject) parsed;
        }
        throw new IOException("Unexpected Hugging Face repository response");
    }

    private static Object readJsonValue(String targetUrl) throws IOException, JSONException {
        HttpURLConnection connection = openConnection(targetUrl);
        try {
            int responseCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, responseCode);
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + (responseBody.isEmpty() ? "" : ": " + responseBody));
            }
            return new JSONTokener(responseBody).nextValue();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String targetUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "llama-android-app");
        return connection;
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

    public static final class ModelSearchResult {
        private final String repoId;
        private final long downloads;
        private final long likes;
        private final String pipelineTag;

        private ModelSearchResult(String repoId, long downloads, long likes, String pipelineTag) {
            this.repoId = repoId;
            this.downloads = downloads;
            this.likes = likes;
            this.pipelineTag = pipelineTag != null ? pipelineTag : "";
        }

        public String getRepoId() {
            return repoId;
        }

        public long getDownloads() {
            return downloads;
        }

        public long getLikes() {
            return likes;
        }

        public String getPipelineTag() {
            return pipelineTag;
        }
    }

    public static final class RepositoryFiles {
        private final String repoId;
        private final String revision;
        private final List<GgufFileInfo> files;

        private RepositoryFiles(String repoId, String revision, List<GgufFileInfo> files) {
            this.repoId = repoId;
            this.revision = revision != null ? revision : "";
            this.files = files != null ? files : Collections.emptyList();
        }

        public String getRepoId() {
            return repoId;
        }

        public String getRevision() {
            return revision;
        }

        public List<GgufFileInfo> getFiles() {
            return files;
        }
    }

    public static final class GgufFileInfo {
        private final String filename;
        private final String downloadUrl;
        private final boolean projector;

        private GgufFileInfo(String filename, String downloadUrl, boolean projector) {
            this.filename = filename;
            this.downloadUrl = downloadUrl;
            this.projector = projector;
        }

        public String getFilename() {
            return filename;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public boolean isProjector() {
            return projector;
        }
    }
}
