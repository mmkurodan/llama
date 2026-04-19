package com.micklab.llama;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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

    public static File findAutoDetectedMultimodalProjectorFile(Context context, String modelReference) {
        File storageDir = getModelStorageDir(context);
        File[] files = storageDir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }

        List<File> candidates = new ArrayList<>();
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String lowerName = file.getName().toLowerCase(Locale.US);
            if (isGgufFilename(lowerName) && lowerName.contains("mmproj")) {
                candidates.add(file);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        String modelFilename = extractFilename(modelReference);
        String modelStem = stripGgufSuffix(modelFilename).toLowerCase(Locale.US);
        List<String> tokens = tokenizeModelStem(modelStem);

        File best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean ambiguous = false;
        for (File candidate : candidates) {
            String candidateStem = stripGgufSuffix(candidate.getName()).toLowerCase(Locale.US);
            int score = 100;
            for (String token : tokens) {
                if (candidateStem.contains(token)) {
                    score += token.length() >= 4 ? 20 : 10;
                }
            }
            if (candidateStem.contains(modelStem)) {
                score += 80;
            }
            if (candidateStem.startsWith("mmproj-")) {
                score += 10;
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
                ambiguous = false;
            } else if (score == bestScore) {
                ambiguous = true;
            }
        }
        return ambiguous || bestScore < 130 ? null : best;
    }

    private static String stripGgufSuffix(String filename) {
        if (filename == null) {
            return "";
        }
        String lower = filename.toLowerCase(Locale.US);
        return lower.endsWith(".gguf") ? lower.substring(0, lower.length() - 5) : lower;
    }

    private static List<String> tokenizeModelStem(String stem) {
        List<String> tokens = new ArrayList<>();
        if (stem == null || stem.isEmpty()) {
            return tokens;
        }
        String[] parts = stem.split("[^a-z0-9]+");
        for (String part : parts) {
            if (part == null || part.length() < 2) {
                continue;
            }
            if ("mmproj".equals(part) || "gguf".equals(part)) {
                continue;
            }
            tokens.add(part);
        }
        return tokens;
    }
}
