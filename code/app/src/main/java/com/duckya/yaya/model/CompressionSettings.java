package com.duckya.yaya.model;

// 文件说明：
// 1. 这个文件用于定义照片压缩设置模型。
// 2. 输入是压缩档位、最大长边、JPEG 质量等配置参数。
// 3. 处理是把界面选择的照片压缩策略封装成统一对象，供队列和压缩模块读取。
// 4. 输出是一个可直接传给照片压缩执行器的配置对象。
public class CompressionSettings {
    private final CompressionPreset preset;
    private final int maxLongSide;
    private final int jpegQuality;
    // 视觉无损会根据图片尺寸和细节动态生成压缩参数。
    private final boolean adaptiveVisualLossless;

    // 函数说明：
    // 1. 这个构造函数用于创建普通照片压缩设置。
    // 2. 输入是压缩档位、最大长边、JPEG 质量。
    // 3. 输出是一个 CompressionSettings 对象。
    public CompressionSettings(CompressionPreset preset, int maxLongSide, int jpegQuality) {
        this(preset, maxLongSide, jpegQuality, false);
    }

    // 函数说明：
    // 1. 这个构造函数用于创建完整的照片压缩设置。
    // 2. 输入是压缩档位、最大长边、JPEG 质量，以及是否启用自适应视觉无损。
    // 3. 输出是一个 CompressionSettings 对象。
    public CompressionSettings(CompressionPreset preset, int maxLongSide, int jpegQuality, boolean adaptiveVisualLossless) {
        this.preset = preset;
        this.maxLongSide = maxLongSide;
        this.jpegQuality = jpegQuality;
        this.adaptiveVisualLossless = adaptiveVisualLossless;
    }

    // 函数说明：
    // 1. 这个函数用于生成“视觉无损”照片压缩预设。
    // 2. 输入为空。
    // 3. 输出是启用自适应视觉无损的 CompressionSettings 对象。
    public static CompressionSettings light() {
        // 这里的 0 只是占位，实际长边和质量由 ImageCompressionWorker 自适应计算。
        return new CompressionSettings(CompressionPreset.LIGHT, 0, 0, true);
    }

    // 函数说明：
    // 1. 这个函数用于生成“均衡压缩”照片压缩预设。
    // 2. 输入为空。
    // 3. 输出是均衡压缩对应的 CompressionSettings 对象。
    public static CompressionSettings balanced() {
        return new CompressionSettings(CompressionPreset.BALANCED, 1920, 74);
    }

    // 函数说明：
    // 1. 这个函数用于生成“分享画质/强压缩”照片压缩预设。
    // 2. 输入为空。
    // 3. 输出是强压缩对应的 CompressionSettings 对象。
    public static CompressionSettings strong() {
        return new CompressionSettings(CompressionPreset.STRONG, 2560, 88);
    }

    // 函数说明：
    // 1. 这个函数用于获取照片压缩档位。
    // 2. 输入为空。
    // 3. 输出是 CompressionPreset。
    public CompressionPreset getPreset() {
        return preset;
    }

    // 函数说明：
    // 1. 这个函数用于获取目标最大长边。
    // 2. 输入为空。
    // 3. 输出是整数形式的最大长边值。
    public int getMaxLongSide() {
        return maxLongSide;
    }

    // 函数说明：
    // 1. 这个函数用于获取 JPEG 压缩质量。
    // 2. 输入为空。
    // 3. 输出是整数形式的 JPEG 质量值。
    public int getJpegQuality() {
        return jpegQuality;
    }

    // 函数说明：
    // 1. 这个函数用于判断是否启用自适应视觉无损策略。
    // 2. 输入为空。
    // 3. 输出是布尔值。
    public boolean isAdaptiveVisualLossless() {
        return adaptiveVisualLossless;
    }
}
