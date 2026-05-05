package com.micklab.llama;

import java.util.Locale;

public enum InferenceBackend {
    CPU(0),
    GPU(1),
    NPU(2);

    private final int nativeValue;

    InferenceBackend(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    public int getNativeValue() {
        return nativeValue;
    }

    public boolean usesAccelerator() {
        return this != CPU;
    }

    public boolean usesHexagon() {
        return this == NPU;
    }

    public static InferenceBackend fromStorage(String rawValue) {
        if (rawValue != null) {
            String normalized = rawValue.trim().toUpperCase(Locale.US);
            for (InferenceBackend backend : values()) {
                if (backend.name().equals(normalized)) {
                    return backend;
                }
            }
        }
        return CPU;
    }

    public static InferenceBackend fromConfig(String rawValue, int legacyGpuOffloadLayers) {
        if (rawValue != null && !rawValue.trim().isEmpty()) {
            return fromStorage(rawValue);
        }
        return legacyGpuOffloadLayers != 0 ? GPU : CPU;
    }
}
