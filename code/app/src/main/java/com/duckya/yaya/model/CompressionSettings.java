package com.duckya.yaya.model;

public class CompressionSettings {
    private final CompressionPreset preset;
    private final int maxLongSide;
    private final int jpegQuality;
    // 视觉无损会根据图片尺寸和细节动态生成压缩参数。
    private final boolean adaptiveVisualLossless;

    public CompressionSettings(CompressionPreset preset, int maxLongSide, int jpegQuality) {
        this(preset, maxLongSide, jpegQuality, false);
    }

    public CompressionSettings(CompressionPreset preset, int maxLongSide, int jpegQuality, boolean adaptiveVisualLossless) {
        this.preset = preset;
        this.maxLongSide = maxLongSide;
        this.jpegQuality = jpegQuality;
        this.adaptiveVisualLossless = adaptiveVisualLossless;
    }

    public static CompressionSettings light() {
        // 这里的 0 只是占位，实际长边和质量由 ImageCompressionWorker 自适应计算。
        return new CompressionSettings(CompressionPreset.LIGHT, 0, 0, true);
    }

    public static CompressionSettings balanced() {
        return new CompressionSettings(CompressionPreset.BALANCED, 1920, 74);
    }

    public static CompressionSettings strong() {
        return new CompressionSettings(CompressionPreset.STRONG, 2560, 88);
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

    public boolean isAdaptiveVisualLossless() {
        return adaptiveVisualLossless;
    }
}
