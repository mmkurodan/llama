package com.micklab.llama;

import java.util.Locale;

final class ResponseMarkerSanitizer {
    private static final String IM_START_MARKER_PREFIX = "<|im_start";
    private static final String IM_END_MARKER_PREFIX = "<|im_end";
    private static final String GENERIC_END_MARKER_PREFIX = "<|end";

    private static final String[] PARTIAL_PIPE_MARKER_PREFIXES = {
            IM_START_MARKER_PREFIX,
            IM_END_MARKER_PREFIX,
            GENERIC_END_MARKER_PREFIX
    };

    private static final String[] REMOVABLE_MARKERS = {
            "<|im_start|>", "<|IM_START|>",
            "<|im_end|>", "<|IM_END|>", "<|im_end|", "<|IM_END|", "<|im_end", "<|IM_END",
            "<|end|>", "<|END|>", "<|end|", "<|END|", "<|end", "<|END",
            "<|end_of_turn|>", "<|END_OF_TURN|>",
            "<|end_of_turn|", "<|END_OF_TURN|",
            "<|end_of_turn", "<|END_OF_TURN"
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
        return tailLower != null && tailLower.startsWith(GENERIC_END_MARKER_PREFIX);
    }

    static boolean isPossiblePipeMarkerPrefix(String tailLower) {
        if (tailLower == null || tailLower.isEmpty()) {
            return false;
        }
        for (String prefix : PARTIAL_PIPE_MARKER_PREFIXES) {
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
        earliest = minPositive(earliest, lower.indexOf(GENERIC_END_MARKER_PREFIX));
        return earliest;
    }

    private static int findTrailingPartialMarkerStart(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int markerStart = lower.lastIndexOf("<|");
        if (markerStart < 0) {
            return -1;
        }

        String tail = lower.substring(markerStart);
        return isPossiblePipeMarkerPrefix(tail) ? markerStart : -1;
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
        for (String prefix : PARTIAL_PIPE_MARKER_PREFIXES) {
            if (prefix.length() > maxLength) {
                maxLength = prefix.length();
            }
        }
        return Math.max(0, maxLength - 1);
    }
}
