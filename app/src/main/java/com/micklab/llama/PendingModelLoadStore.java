package com.micklab.llama;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public final class PendingModelLoadStore {
    public static final String FILE_NAME = "pending_model_load.json";
    private static final String TEMP_FILE_NAME = FILE_NAME + ".tmp";

    public static final class PendingLoad {
        private final String configName;
        private final String modelPath;
        private final String modelUrl;
        private final long createdAtMs;

        private PendingLoad(String configName, String modelPath, String modelUrl, long createdAtMs) {
            this.configName = configName != null ? configName : "";
            this.modelPath = modelPath != null ? modelPath : "";
            this.modelUrl = modelUrl != null ? modelUrl : "";
            this.createdAtMs = createdAtMs;
        }

        public String getConfigName() {
            return configName;
        }

        public String getModelPath() {
            return modelPath;
        }

        public String getModelUrl() {
            return modelUrl;
        }

        public long getCreatedAtMs() {
            return createdAtMs;
        }

        public String getDisplayModelName() {
            if (!modelPath.isEmpty()) {
                return new File(modelPath).getName();
            }
            return extractFilenameFromUrl(modelUrl);
        }
    }

    private PendingModelLoadStore() {
    }

    public static void writePendingLoad(Context context, String configName, String modelPath, String modelUrl)
            throws IOException, JSONException {
        File markerFile = getMarkerFile(context);
        File tempFile = getTempMarkerFile(context);
        File parentDir = markerFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create pending model load directory: " + parentDir.getAbsolutePath());
        }

        JSONObject json = new JSONObject();
        json.put("configName", configName != null ? configName : "");
        json.put("modelPath", modelPath != null ? modelPath : "");
        json.put("modelUrl", modelUrl != null ? modelUrl : "");
        json.put("createdAtMs", System.currentTimeMillis());

        try (FileWriter writer = new FileWriter(tempFile, false)) {
            writer.write(json.toString(2));
        }

        if (markerFile.exists() && !markerFile.delete()) {
            throw new IOException("Failed to replace pending model load marker: " + markerFile.getAbsolutePath());
        }
        if (!tempFile.renameTo(markerFile)) {
            throw new IOException("Failed to finalize pending model load marker: " + markerFile.getAbsolutePath());
        }
    }

    public static PendingLoad readPendingLoad(Context context) throws IOException, JSONException {
        File candidate = getMarkerFile(context);
        if (!candidate.exists()) {
            candidate = getTempMarkerFile(context);
            if (!candidate.exists()) {
                return null;
            }
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(candidate))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        JSONObject json = new JSONObject(sb.toString());
        return new PendingLoad(
                json.optString("configName", ""),
                json.optString("modelPath", ""),
                json.optString("modelUrl", ""),
                json.optLong("createdAtMs", 0L));
    }

    public static void deletePendingLoad(Context context) throws IOException {
        deleteIfExists(getMarkerFile(context));
        deleteIfExists(getTempMarkerFile(context));
    }

    public static boolean isMarkerFile(String fileName) {
        return FILE_NAME.equals(fileName) || TEMP_FILE_NAME.equals(fileName);
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Failed to delete pending model load marker: " + file.getAbsolutePath());
        }
    }

    private static File getMarkerFile(Context context) {
        return new File(getAppFilesDir(context), FILE_NAME);
    }

    private static File getTempMarkerFile(Context context) {
        return new File(getAppFilesDir(context), TEMP_FILE_NAME);
    }

    private static File getAppFilesDir(Context context) {
        File externalDir = context.getExternalFilesDir(null);
        return externalDir != null ? externalDir : context.getFilesDir();
    }

    private static String extractFilenameFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int q = url.indexOf('?');
        String pure = q >= 0 ? url.substring(0, q) : url;
        int slash = pure.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < pure.length()) {
            return pure.substring(slash + 1);
        }
        return pure;
    }
}
