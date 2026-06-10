package com.duckya.yaya.model;

import java.util.UUID;

public class QueueTask {
    private final String id;
    private final MediaItemInfo media;
    private final QueueAction action;
    private final CompressionSettings settings;
    private QueueStatus status;
    private float progress;
    private long estimatedOutputBytes;
    private long actualOutputBytes;
    private String failureReason;

    public QueueTask(MediaItemInfo media, QueueAction action) {
        this.id = UUID.randomUUID().toString();
        this.media = media;
        this.action = action;
        this.settings = CompressionSettings.balanced();
        this.status = QueueStatus.PENDING;
        this.progress = 0f;
        this.estimatedOutputBytes = estimateOutputBytes(media, action);
    }

    private long estimateOutputBytes(MediaItemInfo media, QueueAction action) {
        if (action == QueueAction.DELETE) {
            return 0L;
        }
        return Math.max((long) (media.getSizeBytes() * 0.58), 1L);
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
}
