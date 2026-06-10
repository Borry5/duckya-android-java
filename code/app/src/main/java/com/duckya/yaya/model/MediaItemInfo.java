package com.duckya.yaya.model;

import android.net.Uri;

public class MediaItemInfo {
    private final String name;
    private final Uri uri;
    private final long sizeBytes;
    private final long modifiedTimeMs;
    private final MediaKind kind;
    private final int width;
    private final int height;
    private final long durationMs;

    public MediaItemInfo(
            String name,
            Uri uri,
            long sizeBytes,
            long modifiedTimeMs,
            MediaKind kind,
            int width,
            int height,
            long durationMs
    ) {
        this.name = name;
        this.uri = uri;
        this.sizeBytes = sizeBytes;
        this.modifiedTimeMs = modifiedTimeMs;
        this.kind = kind;
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
    }

    public String getName() {
        return name;
    }

    public Uri getUri() {
        return uri;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getModifiedTimeMs() {
        return modifiedTimeMs;
    }

    public MediaKind getKind() {
        return kind;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
