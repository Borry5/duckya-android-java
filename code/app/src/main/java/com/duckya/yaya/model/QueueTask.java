package com.duckya.yaya.model;

import android.net.Uri;

import java.util.UUID;

public class QueueTask {
    private final String id;
    private final MediaItemInfo media;
    private final QueueAction action;
    private CompressionSettings settings;
    private QueueStatus status;
    private float progress;
    private long estimatedOutputBytes;
    private long actualOutputBytes;
    private String failureReason;
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
        this.status = QueueStatus.PENDING;
        this.progress = 0f;
        this.estimatedOutputBytes = estimateOutputBytes(media, action, settings);
    }

    private long estimateOutputBytes(MediaItemInfo media, QueueAction action, CompressionSettings settings) {
        if (action == QueueAction.DELETE) {
            return 0L;
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
        this.estimatedOutputBytes = estimateOutputBytes(media, action, settings);
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
            QueueStatus status,
            float progress,
            long actualOutputBytes,
            String failureReason,
            Uri compressedAssetUri,
            boolean originalRecycled,
            boolean outputRecycled
    ) {
        this.settings = settings;
        this.estimatedOutputBytes = estimateOutputBytes(media, action, settings);
        this.status = status == QueueStatus.RUNNING ? QueueStatus.PENDING : status;
        this.progress = status == QueueStatus.RUNNING ? 0f : Math.max(0f, Math.min(progress, 1f));
        this.actualOutputBytes = actualOutputBytes;
        this.failureReason = failureReason;
        this.compressedAssetUri = compressedAssetUri;
        this.originalRecycled = originalRecycled;
        this.outputRecycled = outputRecycled;
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
