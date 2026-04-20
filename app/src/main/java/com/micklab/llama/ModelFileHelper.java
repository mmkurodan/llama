package com.micklab.llama;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ModelFileHelper {
    public static final class InferredModalities {
        private final boolean vision;
        private final boolean audio;

        public InferredModalities(boolean vision, boolean audio) {
            this.vision = vision;
            this.audio = audio;
        }

        public boolean supportsVision() {
            return vision;
        }

        public boolean supportsAudio() {
            return audio;
        }
    }

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
        return findAutoDetectedMultimodalProjectorFile(context, modelReference, false, false);
    }

    public static File findAutoDetectedMultimodalProjectorFile(
            Context context,
            String modelReference,
            boolean preferVision,
            boolean preferAudio) {
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
            if (isLikelyProjectorFilename(lowerName)) {
                candidates.add(file);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        String modelFilename = extractFilename(modelReference);
        String modelStem = stripGgufSuffix(modelFilename).toLowerCase(Locale.US);
        List<String> tokens = tokenizeModelStem(modelStem);

        File best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean ambiguous = false;
        for (File candidate : candidates) {
            String candidateStem = stripGgufSuffix(candidate.getName()).toLowerCase(Locale.US);
            int score = scoreProjectorCandidate(candidateStem, modelStem, tokens, preferVision, preferAudio);
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

    public static InferredModalities inferAutoDetectedModalities(Context context, String modelReference) {
        File storageDir = getModelStorageDir(context);
        File[] files = storageDir.listFiles();
        if (files == null || files.length == 0) {
            return new InferredModalities(false, false);
        }

        String modelFilename = extractFilename(modelReference);
        String modelStem = stripGgufSuffix(modelFilename).toLowerCase(Locale.US);
        List<String> tokens = tokenizeModelStem(modelStem);
        boolean vision = false;
        boolean audio = false;

        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String lowerName = file.getName().toLowerCase(Locale.US);
            if (!isLikelyProjectorFilename(lowerName)) {
                continue;
            }

            String candidateStem = stripGgufSuffix(lowerName);
            if (scoreProjectorCandidate(candidateStem, modelStem, tokens, false, false) < 130) {
                continue;
            }

            boolean audioHint = hasAudioProjectorHint(candidateStem);
            boolean visionHint = hasVisionProjectorHint(candidateStem);
            if (visionHint || (!audioHint && candidateStem.contains("mmproj"))) {
                vision = true;
            }
            if (audioHint) {
                audio = true;
            }
        }

        return new InferredModalities(vision, audio);
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

    private static boolean isLikelyProjectorFilename(String lowerName) {
        return isGgufFilename(lowerName)
                && (lowerName.contains("mmproj")
                || lowerName.contains("projector")
                || lowerName.contains("gemma4a")
                || lowerName.contains("gemma4v"));
    }

    private static int scoreProjectorCandidate(
            String candidateStem,
            String modelStem,
            List<String> tokens,
            boolean preferVision,
            boolean preferAudio) {
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
        if (candidateStem.contains("mmproj")) {
            score += 10;
        }

        boolean audioHint = hasAudioProjectorHint(candidateStem);
        boolean visionHint = hasVisionProjectorHint(candidateStem);

        if (preferVision && preferAudio) {
            if (audioHint && visionHint) {
                score += 220;
            } else if (!audioHint && !visionHint) {
                score += 120;
            } else {
                score -= 200;
            }
            return score;
        }

        if (preferAudio) {
            if (audioHint) {
                score += 160;
            }
            if (visionHint && !audioHint) {
                score -= 120;
            }
            return score;
        }

        if (preferVision) {
            if (visionHint) {
                score += 160;
            }
            if (audioHint && !visionHint) {
                score -= 120;
            }
            return score;
        }

        if (audioHint || visionHint) {
            score += 20;
        }
        return score;
    }

    private static boolean hasAudioProjectorHint(String candidateStem) {
        return containsAny(candidateStem,
                "audio",
                "gemma4a",
                "qwen2a",
                "qwen25o",
                "voxtral",
                "ultravox",
                "glma",
                "lfm2a",
                "whisper");
    }

    private static boolean hasVisionProjectorHint(String candidateStem) {
        return containsAny(candidateStem,
                "vision",
                "image",
                "gemma4v",
                "siglip",
                "llava",
                "glm4v",
                "minicpmv");
    }

    private static boolean containsAny(String candidateStem, String... needles) {
        for (String needle : needles) {
            if (candidateStem.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
