package com.duckya.yaya.util;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.MediaKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MediaStoreScanner {
    public interface ProgressListener {
        void onProgress(int progress, int scannedCount, int totalCount);
    }

    public List<MediaItemInfo> scan(Context context) {
        return scan(context, null);
    }

    // 扫描时按已读取数量回传进度，让界面不再停在 0%。
    public List<MediaItemInfo> scan(Context context, ProgressListener listener) {
        List<MediaItemInfo> items = new ArrayList<>();
        notifyProgress(listener, 1, 0, 0);
        Cursor imageCursor = queryImageCursor(context);
        notifyProgress(listener, 3, 0, 0);
        Cursor videoCursor = queryVideoCursor(context);
        int imageCount = imageCursor == null ? 0 : imageCursor.getCount();
        int videoCount = videoCursor == null ? 0 : videoCursor.getCount();
        int totalCount = imageCount + videoCount;
        notifyProgress(listener, 5, 0, totalCount);

        int scannedCount = 0;
        try {
            scannedCount += readImages(imageCursor, items, listener, scannedCount, totalCount);
            readVideos(videoCursor, items, listener, scannedCount, totalCount);
        } finally {
            if (imageCursor != null) {
                imageCursor.close();
            }
            if (videoCursor != null) {
                videoCursor.close();
            }
        }
        Collections.sort(items, new Comparator<MediaItemInfo>() {
            @Override
            public int compare(MediaItemInfo left, MediaItemInfo right) {
                return Long.compare(right.getModifiedTimeMs(), left.getModifiedTimeMs());
            }
        });
        notifyProgress(listener, 100, totalCount, totalCount);
        return items;
    }

    private Cursor queryImageCursor(Context context) {
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
        };
        return context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC"
        );
    }

    private int readImages(
            Cursor cursor,
            List<MediaItemInfo> items,
            ProgressListener listener,
            int scannedBefore,
            int totalCount
    ) {
        if (cursor == null) {
            return 0;
        }
        int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
        int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
        int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
        int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
        int widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH);
        int heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT);

        int scannedHere = 0;
        while (cursor.moveToNext()) {
            long id = cursor.getLong(idIndex);
            Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            items.add(new MediaItemInfo(
                    valueOrFallback(cursor.getString(nameIndex), "unknown_image"),
                    uri,
                    Math.max(cursor.getLong(sizeIndex), 0L),
                    cursor.getLong(dateIndex) * 1000L,
                    MediaKind.IMAGE,
                    cursor.getInt(widthIndex),
                    cursor.getInt(heightIndex),
                    0L
            ));
            scannedHere++;
            notifyProgressIfNeeded(listener, scannedBefore + scannedHere, totalCount);
        }
        return scannedHere;
    }

    private Cursor queryVideoCursor(Context context) {
        String[] projection = new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DURATION
        };
        return context.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Video.Media.DATE_MODIFIED + " DESC"
        );
    }

    private int readVideos(
            Cursor cursor,
            List<MediaItemInfo> items,
            ProgressListener listener,
            int scannedBefore,
            int totalCount
    ) {
        if (cursor == null) {
            return 0;
        }
        int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
        int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
        int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
        int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
        int widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH);
        int heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT);
        int durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);

        int scannedHere = 0;
        while (cursor.moveToNext()) {
            long id = cursor.getLong(idIndex);
            Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
            items.add(new MediaItemInfo(
                    valueOrFallback(cursor.getString(nameIndex), "unknown_video"),
                    uri,
                    Math.max(cursor.getLong(sizeIndex), 0L),
                    cursor.getLong(dateIndex) * 1000L,
                    MediaKind.VIDEO,
                    cursor.getInt(widthIndex),
                    cursor.getInt(heightIndex),
                    Math.max(cursor.getLong(durationIndex), 0L)
            ));
            scannedHere++;
            notifyProgressIfNeeded(listener, scannedBefore + scannedHere, totalCount);
        }
        return scannedHere;
    }

    private void notifyProgressIfNeeded(ProgressListener listener, int scannedCount, int totalCount) {
        if (totalCount <= 0 || (scannedCount % 8 != 0 && scannedCount != totalCount)) {
            return;
        }
        int progress = Math.max(1, Math.min(99, Math.round(scannedCount * 100f / totalCount)));
        notifyProgress(listener, progress, scannedCount, totalCount);
    }

    private void notifyProgress(ProgressListener listener, int progress, int scannedCount, int totalCount) {
        if (listener != null) {
            listener.onProgress(progress, scannedCount, totalCount);
        }
    }

    private String valueOrFallback(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }
}
