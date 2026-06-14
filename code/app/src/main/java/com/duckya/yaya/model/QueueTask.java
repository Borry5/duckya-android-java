package com.duckya.yaya.model;

import android.net.Uri;

import java.util.UUID;

public class QueueTask {
    private static final long ONE_GB_BYTES = 1024L * 1024L * 1024L;

    private final String id;
    private final MediaItemInfo media;
    private final QueueAction action;
    private CompressionSettings settings;
    private VideoCompressionSettings videoSettings;
    private QueueStatus status;
    private float progress;
    private long estimatedOutputBytes;
    private long actualOutputBytes;
    private String failureReason;
    private Uri originalAssetUri;
    private Uri compressedAssetUri;
    private boolean originalRecycled;
    private boolean outputRecycled;

    public QueueTask(MediaItemInfo media, QueueAction action) {
        this(UUID.randomUUID().toString(), media, action);
    }

    public QueueTask(String id, MediaItemInfo media, QueueAction action) {
        this.id = id;
        this.media = media;
        this.action = action;
        this.settings = CompressionSettings.light();
        this.videoSettings = VideoCompressionSettings.balanced();
        this.status = QueueStatus.PENDING;
        this.progress = 0f;
        this.estimatedOutputBytes = estimateOutputBytes(media, action, settings, videoSettings);
    }

    private long estimateOutputBytes(
            MediaItemInfo media,
            QueueAction action,
            CompressionSettings settings,
            VideoCompressionSettings videoSettings
    ) {
        if (action == QueueAction.DELETE) {
            return 0L;
        }
        if (media.getKind() == MediaKind.VIDEO) {
            if (media.getDurationMs() > 0L) {
                double seconds = media.getDurationMs() / 1000.0;
                if (videoSettings.isLimitToOneGb() && media.getSizeBytes() > ONE_GB_BYTES) {
                    return ONE_GB_BYTES;
                }
                double sourceMbps = media.getSizeBytes() * 8.0 / seconds / 1_000_000.0;
                double targetMbps = Math.min(sourceMbps, videoSettings.getTargetBitrateMbps());
                long targetBytes = (long) (targetMbps * 1_000_000.0 / 8.0 * seconds);
                return Math.max(Math.min(targetBytes, media.getSizeBytes()), 1L);
            }
            double ratio;
            if (videoSettings.getPreset() == VideoCompressionPreset.HIGH_QUALITY) {
                ratio = 0.75;
            } else if (videoSettings.getPreset() == VideoCompressionPreset.SHARE) {
                ratio = 0.25;
            } else {
                ratio = 0.42;
            }
            return Math.max((long) (media.getSizeBytes() * ratio), 1L);
        }
        // 队列里只是预估体积，真实结果仍以压缩完成后的文件大小为准。
        double ratio;
        if (settings.getPreset() == CompressionPreset.STRONG) {
            ratio = 0.18;
        } else if (settings.getPreset() == CompressionPreset.BALANCED) {
            ratio = 0.58;
        } else if (media.getSizeBytes() >= 20L * 1024L * 1024L) {
            ratio = 0.45;
        } else if (media.getSizeBytes() >= 8L * 1024L * 1024L) {
            ratio = 0.62;
        } else {
            ratio = 0.86;
        }
        return Math.max((long) (media.getSizeBytes() * ratio), 1L);
    }

    public String getId() {
        return id;
    }

    public MediaItemInfo getMedia() {
        return media;
    }

    public QueueAction getAction() {
        return action;
    }

    public CompressionSettings getSettings() {
        return settings;
    }

    public void setSettings(CompressionSettings settings) {
        this.settings = settings;
        this.estimatedOutputBytes = estimateOutputBytes(media, action, settings, videoSettings);
        if (actualOutputBytes > 0L) {
            actualOutputBytes = 0L;
        }
    }

    public VideoCompressionSettings getVideoSettings() {
        return videoSettings;
    }

    public void setVideoSettings(VideoCompressionSettings videoSettings) {
        this.videoSettings = normalizeVideoSettingsForMedia(videoSettings);
        this.estimatedOutputBytes = estimateOutputBytes(media, action, settings, this.videoSettings);
        if (actualOutputBytes > 0L) {
            actualOutputBytes = 0L;
        }
    }

    public QueueStatus getStatus() {
        return status;
    }

    public void setStatus(QueueStatus status) {
        this.status = status;
    }

    public float getProgress() {
        return progress;
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(progress, 1f));
    }

    public long getEstimatedOutputBytes() {
        return estimatedOutputBytes;
    }

    public long getActualOutputBytes() {
        return actualOutputBytes;
    }

    public void setActualOutputBytes(long actualOutputBytes) {
        this.actualOutputBytes = actualOutputBytes;
    }

    public Uri getOriginalAssetUri() {
        return originalAssetUri;
    }

    public void setOriginalAssetUri(Uri originalAssetUri) {
        this.originalAssetUri = originalAssetUri;
        this.originalRecycled = false;
    }

    public Uri getCompressedAssetUri() {
        return compressedAssetUri;
    }

    public void setCompressedAssetUri(Uri compressedAssetUri) {
        this.compressedAssetUri = compressedAssetUri;
        this.outputRecycled = false;
    }

    public boolean isOriginalRecycled() {
        return originalRecycled;
    }

    public void setOriginalRecycled(boolean originalRecycled) {
        this.originalRecycled = originalRecycled;
    }

    public boolean isOutputRecycled() {
        return outputRecycled;
    }

    public void setOutputRecycled(boolean outputRecycled) {
        this.outputRecycled = outputRecycled;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    // 从本地缓存恢复队列时一次性写回任务状态，避免外部反射或破坏构造流程。
    public void restoreState(
            CompressionSettings settings,
            VideoCompressionSettings videoSettings,
            QueueStatus status,
            float progress,
            long actualOutputBytes,
            String failureReason,
            Uri originalAssetUri,
            Uri compressedAssetUri,
            boolean originalRecycled,
            boolean outputRecycled
    ) {
        this.settings = settings;
        this.videoSettings = normalizeVideoSettingsForMedia(videoSettings);
        this.estimatedOutputBytes = estimateOutputBytes(media, action, settings, this.videoSettings);
        this.status = status == QueueStatus.RUNNING ? QueueStatus.PENDING : status;
        this.progress = status == QueueStatus.RUNNING ? 0f : Math.max(0f, Math.min(progress, 1f));
        this.actualOutputBytes = actualOutputBytes;
        this.failureReason = failureReason;
        this.originalAssetUri = originalAssetUri;
        this.compressedAssetUri = compressedAssetUri;
        this.originalRecycled = originalRecycled;
        this.outputRecycled = outputRecycled;
    }

    private VideoCompressionSettings normalizeVideoSettingsForMedia(VideoCompressionSettings sourceSettings) {
        if (sourceSettings == null) {
            return VideoCompressionSettings.balanced();
        }
        if (!sourceSettings.isLimitToOneGb() || media.getSizeBytes() > ONE_GB_BYTES) {
            return sourceSettings;
        }
        // 1GB 限制只对超过 1GB 的原视频有意义；小视频恢复旧缓存时自动回到档位码率。
        return new VideoCompressionSettings(
                sourceSettings.getPreset(),
                sourceSettings.getResolutionOption(),
                sourceSettings.getCodecOption(),
                sourceSettings.getTargetBitrateMbps(),
                sourceSettings.isAutoBitrate(),
                sourceSettings.getAudioMode(),
                sourceSettings.getFrameRateOption(),
                false,
                sourceSettings.isFallbackToH264()
        );
    }

    public long getEstimatedSavedBytes() {
        if (action == QueueAction.DELETE) {
            return media.getSizeBytes();
        }
        return Math.max(media.getSizeBytes() - estimatedOutputBytes, 0L);
    }

    public long getSavedBytes() {
        if (action == QueueAction.DELETE) {
            return media.getSizeBytes();
        }
        if (actualOutputBytes > 0L) {
            return Math.max(media.getSizeBytes() - actualOutputBytes, 0L);
        }
        return getEstimatedSavedBytes();
    }
}
