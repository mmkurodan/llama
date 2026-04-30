package com.micklab.llama;

import java.util.Locale;

final class ResponseMarkerSanitizer {
    private static final String IM_START_MARKER_PREFIX = "<|im_start";
    private static final String IM_END_MARKER_PREFIX = "<|im_end";
    private static final String PIPE_GENERIC_END_MARKER_PREFIX = "<|end";
    // Gemma-4 can emit a raw "<end" prefix before the stop marker is complete.
    private static final String RAW_GENERIC_END_MARKER_PREFIX = "<end";
    // Gemma-4 uses channel markers for reasoning (thought, action, etc)
    private static final String CHANNEL_MARKER_PREFIX = "<|channel";

    private static final String[] PARTIAL_MARKER_PREFIXES = {
            IM_START_MARKER_PREFIX,
            IM_END_MARKER_PREFIX,
            PIPE_GENERIC_END_MARKER_PREFIX,
            RAW_GENERIC_END_MARKER_PREFIX,
            CHANNEL_MARKER_PREFIX
    };

    private static final String[] REMOVABLE_MARKERS = {
            "<|im_start|>", "<|IM_START|>",
            "<|im_end|>", "<|IM_END|>", "<|im_end|", "<|IM_END|", "<|im_end", "<|IM_END",
            "<|end|>", "<|END|>", "<|end|", "<|END|", "<|end", "<|END",
            "<|end_of_turn|>", "<|END_OF_TURN|>",
            "<|end_of_turn|", "<|END_OF_TURN|",
            "<|end_of_turn", "<|END_OF_TURN",
            // Gemma-4 channel markers (thought, action, observation, etc)
            "<|channel>thought|>", "<|CHANNEL>THOUGHT|>",
            "<|channel>action|>", "<|CHANNEL>ACTION|>",
            "<|channel>observation|>", "<|CHANNEL>OBSERVATION|>",
            "<|channel>", "<|CHANNEL>"
    };

    private static final int STREAMING_HOLDBACK_LENGTH = computeStreamingHoldbackLength();

    private ResponseMarkerSanitizer() {
    }

    static String stripResponseMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;
        int endMarkerStart = findFirstEndMarkerStart(result);
        if (endMarkerStart >= 0) {
            result = result.substring(0, endMarkerStart);
        }

        for (String marker : REMOVABLE_MARKERS) {
            result = result.replace(marker, "");
        }

        int trailingPartialStart = findTrailingPartialMarkerStart(result);
        if (trailingPartialStart >= 0) {
            result = result.substring(0, trailingPartialStart);
        }

        return result;
    }

    static int getStreamingHoldbackLength() {
        return STREAMING_HOLDBACK_LENGTH;
    }

    static boolean startsWithImStartMarker(String tailLower) {
        return tailLower != null && tailLower.startsWith(IM_START_MARKER_PREFIX);
    }

    static boolean startsWithImEndMarker(String tailLower) {
        return tailLower != null && tailLower.startsWith(IM_END_MARKER_PREFIX);
    }

    static boolean startsWithGenericEndMarker(String tailLower) {
        return tailLower != null && (
                tailLower.startsWith(PIPE_GENERIC_END_MARKER_PREFIX)
                        || tailLower.startsWith(RAW_GENERIC_END_MARKER_PREFIX)
        );
    }

    static int findNextMarkerStart(String text, int fromIndex) {
        if (text == null || text.isEmpty()) {
            return -1;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        int candidate = -1;
        candidate = minPositive(candidate, lower.indexOf("<|", Math.max(0, fromIndex)));
        candidate = minPositive(candidate, lower.indexOf("<e", Math.max(0, fromIndex)));
        return candidate;
    }

    static boolean isPossibleMarkerPrefix(String tailLower) {
        if (tailLower == null || tailLower.isEmpty()) {
            return false;
        }
        for (String prefix : PARTIAL_MARKER_PREFIXES) {
            if (prefix.startsWith(tailLower)) {
                return true;
            }
        }
        return false;
    }

    private static int findFirstEndMarkerStart(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int earliest = -1;
        earliest = minPositive(earliest, lower.indexOf(IM_END_MARKER_PREFIX));
        earliest = minPositive(earliest, lower.indexOf(PIPE_GENERIC_END_MARKER_PREFIX));
        earliest = minPositive(earliest, lower.indexOf(RAW_GENERIC_END_MARKER_PREFIX));
        return earliest;
    }

    private static int findTrailingPartialMarkerStart(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int markerStart = findLatestValidMarkerPrefixStart(lower, "<|");
        markerStart = Math.max(markerStart, findLatestValidMarkerPrefixStart(lower, "<e"));
        return markerStart;
    }

    private static int findLatestValidMarkerPrefixStart(String lower, String needle) {
        int markerStart = lower.lastIndexOf(needle);
        while (markerStart >= 0) {
            String tail = lower.substring(markerStart);
            if (isPossibleMarkerPrefix(tail)) {
                return markerStart;
            }
            markerStart = lower.lastIndexOf(needle, markerStart - 1);
        }
        return -1;
    }

    private static int minPositive(int current, int candidate) {
        if (candidate < 0) {
            return current;
        }
        if (current < 0 || candidate < current) {
            return candidate;
        }
        return current;
    }

    private static int computeStreamingHoldbackLength() {
        int maxLength = 0;
        for (String marker : REMOVABLE_MARKERS) {
            if (marker.length() > maxLength) {
                maxLength = marker.length();
            }
        }
        for (String prefix : PARTIAL_MARKER_PREFIXES) {
            if (prefix.length() > maxLength) {
                maxLength = prefix.length();
            }
        }
        return Math.max(0, maxLength - 1);
    }
}
