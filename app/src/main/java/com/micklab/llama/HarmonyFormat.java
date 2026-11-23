package com.micklab.llama;

import java.util.Locale;

/**
 * OpenAI gpt-oss "harmony" chat format support.
 *
 * <p>gpt-oss answers as a sequence of messages, each with a channel:
 * <pre>
 *   &lt;|channel|&gt;analysis&lt;|message|&gt;…reasoning…&lt;|end|&gt;
 *   &lt;|start|&gt;assistant&lt;|channel|&gt;final&lt;|message|&gt;…answer…&lt;|return|&gt;
 * </pre>
 * The rest of the app (stream filters, WebUI reasoning split, end-marker sanitizer) understands
 * {@code <think>…</think>answer}. {@link Converter} rewrites harmony output into that shape:
 * analysis / commentary channels become a {@code <think>} block, the final channel becomes the
 * plain answer, and the structural markers are dropped. Without this, {@code <|end|>} after the
 * analysis was treated as an end-of-response marker and generation stopped at the reasoning.
 */
public final class HarmonyFormat {
    private HarmonyFormat() {
    }

    static final String START = "<|start|>";
    static final String CHANNEL = "<|channel|>";
    static final String MESSAGE = "<|message|>";
    static final String END = "<|end|>";
    static final String RETURN = "<|return|>";
    static final String CALL = "<|call|>";
    static final String CONSTRAIN = "<|constrain|>";

    private static final String[] BODY_MARKERS = {START, CHANNEL, END, RETURN, CALL};
    /** A header (role / channel / recipient) longer than this is not a real header: emit it as text. */
    private static final int MAX_HEADER_CHARS = 256;

    /** True for a GGUF chat template written in the harmony format. Pass the template text. */
    public static boolean looksLikeHarmonyTemplate(String template) {
        if (template == null) {
            return false;
        }
        return template.contains(CHANNEL) && template.contains(MESSAGE) && template.contains(START);
    }

    /** True when a model file name/path looks like a gpt-oss model. */
    public static boolean isHarmonyModelPath(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);
        return lower.contains("gpt-oss") || lower.contains("gpt_oss") || lower.contains("gptoss");
    }

    /** Quick check whether text contains harmony structure at all. */
    public static boolean containsHarmonyMarkers(String text) {
        return text != null && (text.contains(CHANNEL) || text.contains(MESSAGE));
    }

    /** Convert a complete harmony response to {@code <think>…</think>answer}. */
    public static String toThinkFormat(String raw) {
        if (raw == null || raw.isEmpty() || !containsHarmonyMarkers(raw)) {
            return raw;
        }
        Converter converter = new Converter();
        StringBuilder out = new StringBuilder(raw.length());
        converter.feed(raw, out);
        converter.finish(out);
        return out.toString();
    }

    /** Incremental (streaming) converter. Not thread-safe; one instance per generation. */
    public static final class Converter {
        private final StringBuilder buf = new StringBuilder();
        private boolean inHeader = false;
        private boolean thinkOpen = false;

        public void feed(String text, StringBuilder out) {
            if (text == null || text.isEmpty()) {
                return;
            }
            buf.append(text);
            process(out, false);
        }

        public void finish(StringBuilder out) {
            process(out, true);
            buf.setLength(0);
            inHeader = false;
            if (thinkOpen) {
                out.append("\n").append(PromptTemplateManager.THINK_CLOSE_TAG).append("\n\n");
                thinkOpen = false;
            }
        }

        private void process(StringBuilder out, boolean flushAll) {
            while (buf.length() > 0) {
                if (inHeader) {
                    int msgIdx = buf.indexOf(MESSAGE);
                    if (msgIdx < 0) {
                        if (flushAll) {
                            buf.setLength(0);  // dangling header at the end: nothing to show
                        } else if (buf.length() > MAX_HEADER_CHARS) {
                            inHeader = false;  // malformed: treat as normal text
                            continue;
                        }
                        return;
                    }
                    String header = buf.substring(0, msgIdx);
                    buf.delete(0, msgIdx + MESSAGE.length());
                    inHeader = false;
                    openChannel(isReasoningHeader(header), out);
                    continue;
                }

                String text = buf.toString();
                int bestIdx = -1;
                String bestMarker = null;
                for (String marker : BODY_MARKERS) {
                    int idx = text.indexOf(marker);
                    if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                        bestIdx = idx;
                        bestMarker = marker;
                    }
                }
                if (bestMarker != null) {
                    if (bestIdx > 0) {
                        out.append(text, 0, bestIdx);
                    }
                    buf.delete(0, bestIdx + bestMarker.length());
                    if (START.equals(bestMarker)) {
                        inHeader = true;
                    } else if (CHANNEL.equals(bestMarker)) {
                        buf.insert(0, CHANNEL);   // keep it so the header parser sees the channel
                        inHeader = true;
                    }
                    // END / RETURN / CALL just terminate the current message.
                    continue;
                }

                int keep = flushAll ? 0 : partialMarkerTail(text);
                int emit = text.length() - keep;
                if (emit > 0) {
                    out.append(text, 0, emit);
                    buf.delete(0, emit);
                }
                return;
            }
        }

        private void openChannel(boolean reasoning, StringBuilder out) {
            if (reasoning && !thinkOpen) {
                out.append(PromptTemplateManager.THINK_OPEN_TAG).append("\n");
                thinkOpen = true;
            } else if (!reasoning && thinkOpen) {
                out.append("\n").append(PromptTemplateManager.THINK_CLOSE_TAG).append("\n\n");
                thinkOpen = false;
            }
        }

        /** analysis / commentary → reasoning; final (or no channel at all) → answer. */
        private static boolean isReasoningHeader(String header) {
            int ch = header.indexOf(CHANNEL);
            if (ch < 0) {
                return false;
            }
            String channel = header.substring(ch + CHANNEL.length()).trim().toLowerCase(Locale.US);
            return !channel.startsWith("final");
        }

        /** Length of a trailing fragment that could be the start of a marker (held back). */
        private static int partialMarkerTail(String text) {
            int lt = text.lastIndexOf('<');
            if (lt < 0 || text.length() - lt >= CONSTRAIN.length()) {
                return 0;
            }
            String tail = text.substring(lt);
            for (String marker : BODY_MARKERS) {
                if (marker.startsWith(tail)) {
                    return tail.length();
                }
            }
            return 0;
        }
    }

    /** Token listener wrapper applying {@link Converter} before the real listener sees the text. */
    static final class ConvertingTokenListener implements LlamaNative.TokenListener {
        private final LlamaNative.TokenListener inner;
        private final Converter converter = new Converter();
        private final StringBuilder scratch = new StringBuilder();

        ConvertingTokenListener(LlamaNative.TokenListener inner) {
            this.inner = inner;
        }

        @Override
        public synchronized void onToken(String token) {
            scratch.setLength(0);
            converter.feed(token, scratch);
            if (scratch.length() > 0) {
                inner.onToken(scratch.toString());
            }
        }

        @Override
        public synchronized void onComplete() {
            scratch.setLength(0);
            converter.finish(scratch);
            if (scratch.length() > 0) {
                inner.onToken(scratch.toString());
            }
            inner.onComplete();
        }

        @Override
        public void onError(String error) {
            inner.onError(error);
        }
    }
}
