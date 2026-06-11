package com.duckya.yaya.model;

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

    public QueueTask(MediaItemInfo media, QueueAction action) {
        this.id = UUID.randomUUID().toString();
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
        double ratio;
        if (settings.getPreset() == CompressionPreset.STRONG) {
            ratio = 0.38;
        } else if (settings.getPreset() == CompressionPreset.BALANCED) {
            ratio = 0.58;
        } else {
            ratio = 0.78;
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

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
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
