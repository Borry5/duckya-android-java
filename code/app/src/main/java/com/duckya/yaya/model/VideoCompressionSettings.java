package com.duckya.yaya.model;

// 文件说明：
// 1. 这个文件用于定义视频压缩设置模型。
// 2. 输入是视频档位、分辨率、目标码率、帧率、1GB 限制等配置参数。
// 3. 处理是把界面上选择的视频压缩参数封装成统一对象，供队列和视频转码模块读取。
// 4. 输出是一个可直接传给视频压缩执行器的配置对象。
public class VideoCompressionSettings {
    private final VideoCompressionPreset preset;
    private final VideoResolutionOption resolutionOption;
    private final VideoCodecOption codecOption;
    private final float targetBitrateMbps;
    private final boolean autoBitrate;
    private final VideoAudioMode audioMode;
    private final VideoFrameRateOption frameRateOption;
    private final boolean limitToOneGb;
    private final boolean fallbackToH264;

    // 函数说明：
    // 1. 这个构造函数用于兼容旧版视频设置创建方式。
    // 2. 输入是档位、分辨率、编码、目标码率、自动码率、音频模式、是否保留原帧率、是否回退 H.264。
    // 3. 输出是一个 VideoCompressionSettings 对象。
    public VideoCompressionSettings(
            VideoCompressionPreset preset,
            VideoResolutionOption resolutionOption,
            VideoCodecOption codecOption,
            float targetBitrateMbps,
            boolean autoBitrate,
            VideoAudioMode audioMode,
            boolean keepFrameRate,
            boolean fallbackToH264
    ) {
        this(
                preset,
                resolutionOption,
                codecOption,
                targetBitrateMbps,
                autoBitrate,
                audioMode,
                keepFrameRate ? VideoFrameRateOption.ORIGINAL : VideoFrameRateOption.FPS30,
                false,
                fallbackToH264
        );
    }

    // 函数说明：
    // 1. 这个构造函数用于创建完整的视频压缩设置对象。
    // 2. 输入是档位、分辨率、编码、目标码率、自动码率、音频模式、帧率模式、1GB 限制、H.264 回退开关。
    // 3. 输出是一个 VideoCompressionSettings 对象。
    public VideoCompressionSettings(
            VideoCompressionPreset preset,
            VideoResolutionOption resolutionOption,
            VideoCodecOption codecOption,
            float targetBitrateMbps,
            boolean autoBitrate,
            VideoAudioMode audioMode,
            VideoFrameRateOption frameRateOption,
            boolean limitToOneGb,
            boolean fallbackToH264
    ) {
        this.preset = preset;
        this.resolutionOption = resolutionOption;
        // 视频编码固定优先使用 H.265；设备不支持时由 Worker 自动回退 H.264。
        this.codecOption = VideoCodecOption.H265;
        this.targetBitrateMbps = Math.max(1f, Math.min(targetBitrateMbps, 20f));
        this.autoBitrate = autoBitrate;
        // 当前安卓版本不提供音频调节，始终保留原始音频，旧缓存中的静音/降码率设置也会被矫正。
        this.audioMode = VideoAudioMode.KEEP;
        this.frameRateOption = frameRateOption == null ? VideoFrameRateOption.ORIGINAL : frameRateOption;
        this.limitToOneGb = limitToOneGb;
        this.fallbackToH264 = true;
    }

    // 函数说明：
    // 1. 这个函数用于生成“均衡压缩”视频预设。
    // 2. 输入为空。
    // 3. 输出是均衡压缩对应的 VideoCompressionSettings 对象。
    public static VideoCompressionSettings balanced() {
        return new VideoCompressionSettings(
                VideoCompressionPreset.BALANCED,
                VideoResolutionOption.P1080,
                VideoCodecOption.H265,
                3.5f,
                true,
                VideoAudioMode.KEEP,
                VideoFrameRateOption.FPS60,
                false,
                true
        );
    }

    // 函数说明：
    // 1. 这个函数用于生成“视觉无损/高质量”视频预设。
    // 2. 输入为空。
    // 3. 输出是高质量对应的 VideoCompressionSettings 对象。
    public static VideoCompressionSettings highQuality() {
        return new VideoCompressionSettings(
                VideoCompressionPreset.HIGH_QUALITY,
                VideoResolutionOption.ORIGINAL,
                VideoCodecOption.H265,
                9f,
                true,
                VideoAudioMode.KEEP,
                VideoFrameRateOption.ORIGINAL,
                false,
                true
        );
    }

    // 函数说明：
    // 1. 这个函数用于生成“分享画质”视频预设。
    // 2. 输入为空。
    // 3. 输出是分享画质对应的 VideoCompressionSettings 对象。
    public static VideoCompressionSettings share() {
        return new VideoCompressionSettings(
                VideoCompressionPreset.SHARE,
                VideoResolutionOption.P720,
                VideoCodecOption.H265,
                2f,
                true,
                VideoAudioMode.KEEP,
                VideoFrameRateOption.FPS30,
                false,
                true
        );
    }

    // 函数说明：
    // 1. 这个函数用于生成“自定义”视频预设。
    // 2. 输入为空。
    // 3. 输出是自定义对应的 VideoCompressionSettings 对象。
    public static VideoCompressionSettings custom() {
        return new VideoCompressionSettings(
                VideoCompressionPreset.CUSTOM,
                VideoResolutionOption.P1080,
                VideoCodecOption.H265,
                3.5f,
                true,
                VideoAudioMode.KEEP,
                VideoFrameRateOption.FPS60,
                false,
                true
        );
    }

    // 函数说明：
    // 1. 这个函数用于获取视频压缩档位。
    // 2. 输入为空。
    // 3. 输出是 VideoCompressionPreset。
    public VideoCompressionPreset getPreset() {
        return preset;
    }

    // 函数说明：
    // 1. 这个函数用于获取视频目标分辨率选项。
    // 2. 输入为空。
    // 3. 输出是 VideoResolutionOption。
    public VideoResolutionOption getResolutionOption() {
        return resolutionOption;
    }

    // 函数说明：
    // 1. 这个函数用于获取视频编码选项。
    // 2. 输入为空。
    // 3. 输出是 VideoCodecOption。
    public VideoCodecOption getCodecOption() {
        return codecOption;
    }

    // 函数说明：
    // 1. 这个函数用于获取目标视频码率。
    // 2. 输入为空。
    // 3. 输出是 float 形式的目标 Mbps 值。
    public float getTargetBitrateMbps() {
        return targetBitrateMbps;
    }

    // 函数说明：
    // 1. 这个函数用于判断当前是否采用自动码率。
    // 2. 输入为空。
    // 3. 输出是布尔值。
    public boolean isAutoBitrate() {
        return autoBitrate;
    }

    // 函数说明：
    // 1. 这个函数用于获取音频模式。
    // 2. 输入为空。
    // 3. 输出是 VideoAudioMode。
    public VideoAudioMode getAudioMode() {
        return audioMode;
    }

    // 函数说明：
    // 1. 这个函数用于判断是否保持原始帧率。
    // 2. 输入为空。
    // 3. 输出是布尔值。
    public boolean isKeepFrameRate() {
        return frameRateOption == VideoFrameRateOption.ORIGINAL;
    }

    // 函数说明：
    // 1. 这个函数用于获取视频帧率选项。
    // 2. 输入为空。
    // 3. 输出是 VideoFrameRateOption。
    public VideoFrameRateOption getFrameRateOption() {
        return frameRateOption;
    }

    // 函数说明：
    // 1. 这个函数用于判断是否启用 1GB 限制。
    // 2. 输入为空。
    // 3. 输出是布尔值。
    public boolean isLimitToOneGb() {
        return limitToOneGb;
    }

    // 函数说明：
    // 1. 这个函数用于判断是否允许失败时回退到 H.264。
    // 2. 输入为空。
    // 3. 输出是布尔值。
    public boolean isFallbackToH264() {
        return fallbackToH264;
    }
}
