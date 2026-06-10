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

    public List<MediaItemInfo> scan(Context context) {
        List<MediaItemInfo> items = new ArrayList<>();
        items.addAll(queryImages(context));
        items.addAll(queryVideos(context));
        Collections.sort(items, new Comparator<MediaItemInfo>() {
            @Override
            public int compare(MediaItemInfo left, MediaItemInfo right) {
                return Long.compare(right.getModifiedTimeMs(), left.getModifiedTimeMs());
            }
        });
        return items;
    }

    private List<MediaItemInfo> queryImages(Context context) {
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
        };
        List<MediaItemInfo> items = new ArrayList<>();
        Cursor cursor = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC"
        );
        if (cursor == null) {
            return items;
        }
        try {
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
            int widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH);
            int heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT);

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
            }
        } finally {
            cursor.close();
        }
        return items;
    }

    private List<MediaItemInfo> queryVideos(Context context) {
        String[] projection = new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DURATION
        };
        List<MediaItemInfo> items = new ArrayList<>();
        Cursor cursor = context.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Video.Media.DATE_MODIFIED + " DESC"
        );
        if (cursor == null) {
            return items;
        }
        try {
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
            int widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH);
            int heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT);
            int durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);

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
            }
        } finally {
            cursor.close();
        }
        return items;
    }

    private String valueOrFallback(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }
}
