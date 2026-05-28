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

    public static boolean isLikelyMultimodalModelReference(String modelReference) {
        String modelFilename = extractFilename(modelReference);
        if (modelFilename == null || modelFilename.isEmpty()) {
            return false;
        }
        return isLikelyMultimodalModelStem(stripGgufSuffix(modelFilename).toLowerCase(Locale.US));
    }

    public static boolean canAutoApplyProjectorReference(String modelReference, String projectorReference) {
        String modelFilename = extractFilename(modelReference);
        String projectorFilename = extractFilename(projectorReference);
        if (modelFilename == null || modelFilename.isEmpty()
                || projectorFilename == null || projectorFilename.isEmpty()
                || !isLikelyProjectorFilename(projectorFilename)) {
            return false;
        }

        String modelStem = stripGgufSuffix(modelFilename).toLowerCase(Locale.US);
        String projectorStem = stripGgufSuffix(projectorFilename).toLowerCase(Locale.US);
        boolean likelyMultimodalModel = isLikelyMultimodalModelStem(modelStem);
        List<String> tokens = tokenizeModelStem(modelStem);
        if (!hasMeaningfulProjectorAffinity(projectorStem, modelStem, tokens, likelyMultimodalModel)) {
            return false;
        }
        int score = scoreProjectorCandidate(
                projectorStem,
                modelStem,
                tokens,
                false,
                false) + scoreProjectorPrecisionCompatibility(modelStem, projectorStem);
        return score >= 130 || likelyMultimodalModel;
    }

    public static File findAutoDetectedMultimodalProjectorFile(Context context, String modelReference) {
        return findAutoDetectedMultimodalProjectorFile(context, modelReference, false, false);
    }

    public static File findAutoDetectedMultimodalProjectorFile(
            Context context,
            String modelReference,
            boolean preferVision,
            boolean preferAudio) {
        File searchDir = getProjectorSearchDir(context, modelReference);
        File[] files = searchDir.listFiles();
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
        boolean likelyMultimodalModel = isLikelyMultimodalModelStem(modelStem);

        File best = null;
        int bestScore = Integer.MIN_VALUE;
        for (File candidate : candidates) {
            String candidateStem = stripGgufSuffix(candidate.getName()).toLowerCase(Locale.US);
            if (!hasMeaningfulProjectorAffinity(candidateStem, modelStem, tokens, likelyMultimodalModel)) {
                continue;
            }
            int score = scoreProjectorCandidate(candidateStem, modelStem, tokens, preferVision, preferAudio);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null) {
            return null;
        }
        return (bestScore >= 130 || (candidates.size() == 1 && likelyMultimodalModel)) ? best : null;
    }

    public static InferredModalities inferAutoDetectedModalities(Context context, String modelReference) {
        File searchDir = getProjectorSearchDir(context, modelReference);
        File[] files = searchDir.listFiles();
        if (files == null || files.length == 0) {
            return new InferredModalities(false, false);
        }

        String modelFilename = extractFilename(modelReference);
        String modelStem = stripGgufSuffix(modelFilename).toLowerCase(Locale.US);
        List<String> tokens = tokenizeModelStem(modelStem);
        boolean likelyMultimodalModel = isLikelyMultimodalModelStem(modelStem);
        boolean vision = false;
        boolean audio = false;

        int candidateCount = 0;
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String lowerName = file.getName().toLowerCase(Locale.US);
            if (isLikelyProjectorFilename(lowerName)) {
                candidateCount++;
            }
        }

        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String lowerName = file.getName().toLowerCase(Locale.US);
            if (!isLikelyProjectorFilename(lowerName)) {
                continue;
            }

            String candidateStem = stripGgufSuffix(lowerName);
            if (!hasMeaningfulProjectorAffinity(candidateStem, modelStem, tokens, likelyMultimodalModel)) {
                continue;
            }
            int score = scoreProjectorCandidate(candidateStem, modelStem, tokens, false, false);
            if (score < 130 && (candidateCount > 1 || !likelyMultimodalModel)) {
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

    public static String findBestMatchingProjectorReference(
            String modelReference,
            List<String> projectorReferences,
            boolean preferVision,
            boolean preferAudio) {
        if (projectorReferences == null || projectorReferences.isEmpty()) {
            return null;
        }

        String modelFilename = extractFilename(modelReference);
        if (modelFilename == null || modelFilename.isEmpty()) {
            return projectorReferences.size() == 1 ? projectorReferences.get(0) : null;
        }

        String modelStem = stripGgufSuffix(modelFilename).toLowerCase(Locale.US);
        List<String> tokens = tokenizeModelStem(modelStem);
        boolean likelyMultimodalModel = isLikelyMultimodalModelStem(modelStem);

        String bestReference = null;
        int bestScore = Integer.MIN_VALUE;
        int candidateCount = 0;
        for (String reference : projectorReferences) {
            String candidateFilename = extractFilename(reference);
            if (!isLikelyProjectorFilename(candidateFilename)) {
                continue;
            }
            candidateCount++;

            String candidateStem = stripGgufSuffix(candidateFilename).toLowerCase(Locale.US);
            if (!hasMeaningfulProjectorAffinity(candidateStem, modelStem, tokens, likelyMultimodalModel)) {
                continue;
            }
            int score = scoreProjectorCandidate(candidateStem, modelStem, tokens, preferVision, preferAudio)
                    + scoreProjectorPrecisionCompatibility(modelStem, candidateStem);
            if (score > bestScore) {
                bestReference = reference;
                bestScore = score;
            }
        }

        if (bestReference == null) {
            return null;
        }
        return (bestScore >= 130 || (candidateCount == 1 && likelyMultimodalModel)) ? bestReference : null;
    }

    private static File getProjectorSearchDir(Context context, String modelReference) {
        if (modelReference != null) {
            String trimmed = modelReference.trim();
            if (!trimmed.isEmpty()) {
                File directFile = new File(trimmed);
                File directParent = directFile.getParentFile();
                if (directFile.isAbsolute() && directParent != null) {
                    return directParent;
                }
            }
        }
        File modelFile = resolveStoredModelFile(context, modelReference);
        if (modelFile != null) {
            File parent = modelFile.getParentFile();
            if (parent != null) {
                return parent;
            }
        }
        return getModelStorageDir(context);
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

    public static boolean isLikelyProjectorFilename(String filename) {
        if (filename == null) {
            return false;
        }
        String lowerName = filename.toLowerCase(Locale.US);
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

    private static int scoreProjectorPrecisionCompatibility(String modelStem, String candidateStem) {
        String modelPrecision = inferPrecisionToken(modelStem);
        String candidatePrecision = inferPrecisionToken(candidateStem);

        if (candidatePrecision.isEmpty()) {
            return 0;
        }
        if ("bf16".equals(modelPrecision)) {
            if ("bf16".equals(candidatePrecision)) {
                return 120;
            }
            if ("f16".equals(candidatePrecision)) {
                return 30;
            }
            return 0;
        }
        if ("f16".equals(modelPrecision)) {
            if ("f16".equals(candidatePrecision)) {
                return 120;
            }
            if ("bf16".equals(candidatePrecision)) {
                return 20;
            }
            return 0;
        }
        if (isQuantizedModelStem(modelStem)) {
            if ("f16".equals(candidatePrecision)) {
                return 120;
            }
            if ("bf16".equals(candidatePrecision)) {
                return 80;
            }
            if ("f32".equals(candidatePrecision)) {
                return 20;
            }
        }
        return 0;
    }

    private static boolean hasMeaningfulProjectorAffinity(
            String candidateStem,
            String modelStem,
            List<String> tokens,
            boolean likelyMultimodalModel) {
        if (candidateStem == null || candidateStem.isEmpty()) {
            return false;
        }
        if (modelStem != null && !modelStem.isEmpty() && candidateStem.contains(modelStem)) {
            return true;
        }
        if (tokens != null) {
            for (String token : tokens) {
                if (token == null || token.length() < 4) {
                    continue;
                }
                if (candidateStem.contains(token)) {
                    return true;
                }
            }
        }
        return likelyMultimodalModel && (candidateStem.contains("mmproj")
                || hasAudioProjectorHint(candidateStem)
                || hasVisionProjectorHint(candidateStem));
    }

    private static String inferPrecisionToken(String stem) {
        if (stem == null || stem.isEmpty()) {
            return "";
        }
        if (stem.contains("bf16")) {
            return "bf16";
        }
        if (stem.contains("f16")) {
            return "f16";
        }
        if (stem.contains("f32")) {
            return "f32";
        }
        return "";
    }

    private static boolean isQuantizedModelStem(String stem) {
        if (stem == null || stem.isEmpty()) {
            return false;
        }
        return stem.contains("q2")
                || stem.contains("q3")
                || stem.contains("q4")
                || stem.contains("q5")
                || stem.contains("q6")
                || stem.contains("q8")
                || stem.contains("iq1")
                || stem.contains("iq2")
                || stem.contains("iq3")
                || stem.contains("iq4")
                || stem.contains("ud-")
                || stem.contains("mxfp4")
                || stem.contains("nvfp4");
    }

    private static boolean isLikelyMultimodalModelStem(String stem) {
        if (stem == null || stem.isEmpty()) {
            return false;
        }
        return containsAny(stem,
                "gemma-4",
                "gemma4",
                "gemma-3n",
                "gemma3n",
                "llava",
                "vision",
                "audio",
                "qwen2-vl",
                "qwen25vl",
                "qwen2vl",
                "qwen2.5-vl",
                "qwen2-audio",
                "qwen2audio",
                "qwen2.5-omni",
                "qwen25omni",
                "glm-4v",
                "glm4v",
                "minicpm-v",
                "minicpmv",
                "voxtral",
                "ultravox");
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
