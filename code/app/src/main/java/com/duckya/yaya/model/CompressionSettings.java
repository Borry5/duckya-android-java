package com.duckya.yaya.model;

public class CompressionSettings {
    private final CompressionPreset preset;
    private final int maxLongSide;

    public CompressionSettings(CompressionPreset preset, int maxLongSide) {
        this.preset = preset;
        this.maxLongSide = maxLongSide;
    }

    public static CompressionSettings balanced() {
        return new CompressionSettings(CompressionPreset.BALANCED, 1920);
    }

    public CompressionPreset getPreset() {
        return preset;
    }

    public int getMaxLongSide() {
        return maxLongSide;
    }
}
