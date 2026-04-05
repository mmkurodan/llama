package com.micklab.llama;

import android.content.Context;

import java.io.File;
import java.util.Locale;

public final class ModelFileHelper {
    private ModelFileHelper() {
    }

    public static String extractFilename(String modelReference) {
        if (modelReference == null) {
            return null;
        }

        String trimmed = modelReference.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        int q = trimmed.indexOf('?');
        String pure = q >= 0 ? trimmed.substring(0, q) : trimmed;
        int slash = pure.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < pure.length()) {
            return pure.substring(slash + 1);
        }
        return pure;
    }

    public static boolean isRemoteModelReference(String modelReference) {
        if (modelReference == null) {
            return false;
        }
        String trimmed = modelReference.trim();
        return trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8);
    }

    public static boolean isGgufFilename(String filename) {
        return filename != null && filename.toLowerCase(Locale.US).endsWith(".gguf");
    }

    public static File getModelStorageDir(Context context) {
        File externalDir = context.getExternalFilesDir(null);
        return externalDir != null ? externalDir : context.getFilesDir();
    }

    public static File resolveStoredModelFile(Context context, String modelReference) {
        String filename = extractFilename(modelReference);
        if (filename == null || filename.isEmpty()) {
            return null;
        }
        return new File(getModelStorageDir(context), filename);
    }
}
