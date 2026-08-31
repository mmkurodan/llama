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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HuggingFaceApiClient {
    private static final String API_BASE_URL = "https://huggingface.co/api";
    private static final String WEB_BASE_URL = "https://huggingface.co";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int SEARCH_RESULT_LIMIT = 30;
    // When structured filters are active we cannot express them all server-side, so fetch a larger
    // page and narrow it client-side while still capping how many rows we ultimately surface.
    private static final int FILTERED_FETCH_LIMIT = 100;
    /** Matches a parameter-count token such as {@code 7B}, {@code 1.5b}, {@code 0.5B} in a repo id. */
    private static final Pattern PARAM_SIZE_PATTERN =
            Pattern.compile("(?<![a-zA-Z0-9.])(\\d+(?:\\.\\d+)?)\\s*[bB](?![a-zA-Z])");

    private HuggingFaceApiClient() {
    }

    /** Backwards-compatible free-text search (no structured filters). */
    public static List<ModelSearchResult> searchGgufModels(String query) throws IOException, JSONException {
        return searchGgufModels(new SearchFilters(query, "", 0d, 0d, false, false));
    }

    /**
     * Searches GGUF repositories on Hugging Face. The free-text term and the selected model family
     * are combined into the server-side {@code search} query (both match against the repo id);
     * the parameter-size range, multimodal and MTP conditions are applied client-side because the
     * API exposes no reliable server filter for them. Quantization is intentionally not applied
     * here — it is a per-file property handled when listing a repository's GGUF files.
     */
    public static List<ModelSearchResult> searchGgufModels(SearchFilters filters)
            throws IOException, JSONException {
        SearchFilters safe = filters != null ? filters : new SearchFilters("", "", 0d, 0d, false, false);
        boolean clientFiltering = safe.hasClientSideFilters();
        int fetchLimit = clientFiltering ? FILTERED_FETCH_LIMIT : SEARCH_RESULT_LIMIT;
        JSONArray items = readModelSearchPage(safe.buildServerQuery(), fetchLimit);

        List<ModelSearchResult> results = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }

            String repoId = item.optString("id", "").trim();
            JSONArray tagsArray = item.optJSONArray("tags");
            if (repoId.isEmpty() || !hasGgufTag(tagsArray)) {
                continue;
            }

            List<String> tags = toLowerStringList(tagsArray);
            String pipelineTag = item.optString("pipeline_tag", "");
            double paramSizeB = parseParameterSizeB(repoId);
            boolean multimodal = isMultimodal(pipelineTag, tags);

            if (!safe.matchesParamSize(paramSizeB)) {
                continue;
            }
            if (safe.multimodalOnly && !multimodal) {
                continue;
            }
            if (safe.mtpOnly && !supportsMtp(repoId, tags)) {
                continue;
            }

            results.add(new ModelSearchResult(
                    repoId,
                    item.optLong("downloads", 0L),
                    item.optLong("likes", 0L),
                    pipelineTag,
                    paramSizeB,
                    multimodal));
            if (results.size() >= SEARCH_RESULT_LIMIT) {
                break;
            }
        }
        return results;
    }

    private static List<String> toLowerStringList(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim().toLowerCase(java.util.Locale.US);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    /** Extracts the parameter count in billions from a repo id (e.g. {@code 7} from {@code .../Qwen2-7B}), or -1. */
    static double parseParameterSizeB(String repoId) {
        if (repoId == null || repoId.isEmpty()) {
            return -1d;
        }
        int slash = repoId.lastIndexOf('/');
        String name = slash >= 0 ? repoId.substring(slash + 1) : repoId;
        Matcher matcher = PARAM_SIZE_PATTERN.matcher(name);
        double best = -1d;
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                // Prefer the largest plausible token; guards against matching "Q4" style noise.
                if (value > best && value <= 2000d) {
                    best = value;
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return best;
    }

    private static boolean isMultimodal(String pipelineTag, List<String> tags) {
        String pt = pipelineTag != null ? pipelineTag.toLowerCase(java.util.Locale.US) : "";
        if (pt.contains("image-text-to-text")
                || pt.contains("image-to-text")
                || pt.contains("visual")
                || pt.contains("audio-text-to-text")
                || pt.contains("any-to-any")) {
            return true;
        }
        for (String tag : tags) {
            if (tag.contains("multimodal")
                    || tag.contains("vision")
                    || tag.contains("image-text-to-text")
                    || tag.contains("audio-text-to-text")
                    || tag.contains("any-to-any")
                    || tag.equals("mmproj")) {
                return true;
            }
        }
        return false;
    }

    private static boolean supportsMtp(String repoId, List<String> tags) {
        String id = repoId != null ? repoId.toLowerCase(java.util.Locale.US) : "";
        if (id.contains("mtp") || id.contains("multi-token") || id.contains("eagle")) {
            return true;
        }
        for (String tag : tags) {
            if (tag.contains("mtp") || tag.contains("multi-token") || tag.equals("eagle")) {
                return true;
            }
        }
        return false;
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
        List<GgufFileInfo> projectorFiles = new ArrayList<>();
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
            if (fileInfo.isProjector()) {
                projectorFiles.add(fileInfo);
            } else {
                modelFiles.add(fileInfo);
            }
        }

        Collections.sort(modelFiles, Comparator.comparing(GgufFileInfo::getFilename, String.CASE_INSENSITIVE_ORDER));
        Collections.sort(projectorFiles, Comparator.comparing(GgufFileInfo::getFilename, String.CASE_INSENSITIVE_ORDER));
        return new RepositoryFiles(normalizedRepoId, revision, modelFiles, projectorFiles);
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

    private static JSONArray readModelSearchPage(String query, int limit) throws IOException, JSONException {
        Uri.Builder builder = Uri.parse(API_BASE_URL + "/models").buildUpon()
                .appendQueryParameter("filter", "gguf")
                .appendQueryParameter("limit", String.valueOf(limit));
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

    /**
     * Structured search conditions. {@code freeText} and {@code modelFamily} are merged into the
     * server-side search term; {@code paramSizeMinB}/{@code paramSizeMaxB} (billions, {@code 0}
     * meaning "no bound") plus the {@code multimodalOnly}/{@code mtpOnly} flags are applied
     * client-side. Quantization is deliberately absent — it is filtered when listing repo files.
     */
    public static final class SearchFilters {
        private final String freeText;
        private final String modelFamily;
        private final double paramSizeMinB;
        private final double paramSizeMaxB;
        public final boolean multimodalOnly;
        public final boolean mtpOnly;

        public SearchFilters(
                String freeText,
                String modelFamily,
                double paramSizeMinB,
                double paramSizeMaxB,
                boolean multimodalOnly,
                boolean mtpOnly) {
            this.freeText = freeText != null ? freeText.trim() : "";
            this.modelFamily = modelFamily != null ? modelFamily.trim() : "";
            this.paramSizeMinB = paramSizeMinB;
            this.paramSizeMaxB = paramSizeMaxB;
            this.multimodalOnly = multimodalOnly;
            this.mtpOnly = mtpOnly;
        }

        String buildServerQuery() {
            StringBuilder sb = new StringBuilder();
            if (!modelFamily.isEmpty()) {
                sb.append(modelFamily);
            }
            if (!freeText.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(freeText);
            }
            return sb.toString().trim();
        }

        boolean hasClientSideFilters() {
            return paramSizeMinB > 0d || paramSizeMaxB > 0d || multimodalOnly || mtpOnly;
        }

        boolean matchesParamSize(double paramSizeB) {
            if (paramSizeMinB <= 0d && paramSizeMaxB <= 0d) {
                return true;
            }
            if (paramSizeB < 0d) {
                // Size could not be inferred from the repo id; exclude it only when a bound is set.
                return false;
            }
            if (paramSizeMinB > 0d && paramSizeB < paramSizeMinB) {
                return false;
            }
            // Max is treated as inclusive of the labelled upper bound (e.g. "4–8B" keeps an 8B repo).
            return !(paramSizeMaxB > 0d && paramSizeB > paramSizeMaxB);
        }
    }

    public static final class ModelSearchResult {
        private final String repoId;
        private final long downloads;
        private final long likes;
        private final String pipelineTag;
        private final double paramSizeB;
        private final boolean multimodal;

        private ModelSearchResult(
                String repoId,
                long downloads,
                long likes,
                String pipelineTag,
                double paramSizeB,
                boolean multimodal) {
            this.repoId = repoId;
            this.downloads = downloads;
            this.likes = likes;
            this.pipelineTag = pipelineTag != null ? pipelineTag : "";
            this.paramSizeB = paramSizeB;
            this.multimodal = multimodal;
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

        /** Parameter count in billions parsed from the repo id, or a negative value if unknown. */
        public double getParamSizeB() {
            return paramSizeB;
        }

        public boolean isMultimodal() {
            return multimodal;
        }
    }

    public static final class RepositoryFiles {
        private final String repoId;
        private final String revision;
        private final List<GgufFileInfo> files;
        private final List<GgufFileInfo> projectorFiles;

        private RepositoryFiles(
                String repoId,
                String revision,
                List<GgufFileInfo> files,
                List<GgufFileInfo> projectorFiles) {
            this.repoId = repoId;
            this.revision = revision != null ? revision : "";
            this.files = files != null ? files : Collections.emptyList();
            this.projectorFiles = projectorFiles != null ? projectorFiles : Collections.emptyList();
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

        public List<GgufFileInfo> getProjectorFiles() {
            return projectorFiles;
        }

        public GgufFileInfo findMatchingProjector(
                GgufFileInfo modelFile,
                boolean preferVision,
                boolean preferAudio) {
            if (modelFile == null || projectorFiles.isEmpty()) {
                return null;
            }

            List<String> projectorNames = new ArrayList<>();
            for (GgufFileInfo projectorFile : projectorFiles) {
                projectorNames.add(projectorFile.getFilename());
            }
            String matchedName = ModelFileHelper.findBestMatchingProjectorReference(
                    modelFile.getFilename(),
                    projectorNames,
                    preferVision,
                    preferAudio);
            if (matchedName == null || matchedName.isEmpty()) {
                return null;
            }
            for (GgufFileInfo projectorFile : projectorFiles) {
                if (matchedName.equals(projectorFile.getFilename())) {
                    return projectorFile;
                }
            }
            return null;
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
