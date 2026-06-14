package com.duckya.yaya.model;

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
        this.codecOption = codecOption;
        this.targetBitrateMbps = Math.max(1f, Math.min(targetBitrateMbps, 20f));
        this.autoBitrate = autoBitrate;
        // 当前安卓版本不提供音频调节，始终保留原始音频，旧缓存中的静音/降码率设置也会被矫正。
        this.audioMode = VideoAudioMode.KEEP;
        this.frameRateOption = frameRateOption == null ? VideoFrameRateOption.ORIGINAL : frameRateOption;
        this.limitToOneGb = limitToOneGb;
        this.fallbackToH264 = fallbackToH264;
    }

    public static VideoCompressionSettings balanced() {
        return new VideoCompressionSettings(
                VideoCompressionPreset.BALANCED,
                VideoResolutionOption.P1080,
                VideoCodecOption.H265,
                3.5f,
                true,
                VideoAudioMode.KEEP,
                VideoFrameRateOption.ORIGINAL,
                false,
                true
        );
    }

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

    public VideoCompressionPreset getPreset() {
        return preset;
    }

    public VideoResolutionOption getResolutionOption() {
        return resolutionOption;
    }

    public VideoCodecOption getCodecOption() {
        return codecOption;
    }

    public float getTargetBitrateMbps() {
        return targetBitrateMbps;
    }

    public boolean isAutoBitrate() {
        return autoBitrate;
    }

    public VideoAudioMode getAudioMode() {
        return audioMode;
    }

    public boolean isKeepFrameRate() {
        return frameRateOption == VideoFrameRateOption.ORIGINAL;
    }

    public VideoFrameRateOption getFrameRateOption() {
        return frameRateOption;
    }

    public boolean isLimitToOneGb() {
        return limitToOneGb;
    }

    public boolean isFallbackToH264() {
        return fallbackToH264;
    }
}
