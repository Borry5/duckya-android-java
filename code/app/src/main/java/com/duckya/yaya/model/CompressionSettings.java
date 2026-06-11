package com.duckya.yaya.model;

public class CompressionSettings {
    private final CompressionPreset preset;
    private final int maxLongSide;
    private final int jpegQuality;

    public CompressionSettings(CompressionPreset preset, int maxLongSide, int jpegQuality) {
        this.preset = preset;
        this.maxLongSide = maxLongSide;
        this.jpegQuality = jpegQuality;
    }

    public static CompressionSettings light() {
        return new CompressionSettings(CompressionPreset.LIGHT, 2560, 88);
    }

    public static CompressionSettings balanced() {
        return new CompressionSettings(CompressionPreset.BALANCED, 1920, 74);
    }

    public static CompressionSettings strong() {
        return new CompressionSettings(CompressionPreset.STRONG, 1280, 58);
    }

    public CompressionPreset getPreset() {
        return preset;
    }

    public int getMaxLongSide() {
        return maxLongSide;
    }

    public int getJpegQuality() {
        return jpegQuality;
    }
}
