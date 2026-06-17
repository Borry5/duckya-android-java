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

/**
 * 这个文件用于扫描系统相册里的图片和视频，并统一转换成应用内部的媒体数据列表。
 * 输入是 Context，以及可选的扫描进度监听器。
 * 处理过程是分别查询 MediaStore 的图片表和视频表，读取字段后组装为 MediaItemInfo，再按时间倒序排序。
 * 输出是可供浏览页、队列页复用的媒体列表，以及扫描过程中的进度回调。
 */
public class MediaStoreScanner {
    public interface ProgressListener {
        /**
         * 这个函数用于把扫描进度回传给调用方界面。
         * 输入是进度百分比、已扫描数量和总数量。
         * 输出是无返回值，由实现方自行刷新界面。
         */
        void onProgress(int progress, int scannedCount, int totalCount);
    }

    /**
     * 这个函数用于执行一次不带进度监听的媒体扫描。
     * 输入是应用或页面传入的 Context。
     * 输出是扫描完成后的媒体列表。
     */
    public List<MediaItemInfo> scan(Context context) {
        return scan(context, null);
    }

    /**
     * 这个函数用于执行完整的图片和视频扫描流程。
     * 输入是 Context 和可选的进度监听器。
     * 输出是按修改时间倒序排列的媒体列表。
     */
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

    /**
     * 这个函数用于查询系统图片游标。
     * 输入是 Context。
     * 输出是按添加时间倒序排列的图片 Cursor。
     */
    private Cursor queryImageCursor(Context context) {
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
        };
        return context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
        );
    }

    /**
     * 这个函数用于把图片 Cursor 逐条转换成 MediaItemInfo。
     * 输入是图片 Cursor、结果列表、进度监听器，以及当前已扫描数量信息。
     * 输出是本次读取到的图片数量，并把图片数据追加进 items。
     */
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
        int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
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

    /**
     * 这个函数用于查询系统视频游标。
     * 输入是 Context。
     * 输出是按添加时间倒序排列的视频 Cursor。
     */
    private Cursor queryVideoCursor(Context context) {
        String[] projection = new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DURATION
        };
        return context.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Video.Media.DATE_ADDED + " DESC"
        );
    }

    /**
     * 这个函数用于把视频 Cursor 逐条转换成 MediaItemInfo。
     * 输入是视频 Cursor、结果列表、进度监听器，以及当前已扫描数量信息。
     * 输出是本次读取到的视频数量，并把视频数据追加进 items。
     */
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
        int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
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

    /**
     * 这个函数用于控制扫描进度回调的触发频率。
     * 输入是监听器、已扫描数量和总数量。
     * 输出是按条件决定是否触发一次进度更新。
     */
    private void notifyProgressIfNeeded(ProgressListener listener, int scannedCount, int totalCount) {
        if (totalCount <= 0 || (scannedCount % 8 != 0 && scannedCount != totalCount)) {
            return;
        }
        int progress = Math.max(1, Math.min(99, Math.round(scannedCount * 100f / totalCount)));
        notifyProgress(listener, progress, scannedCount, totalCount);
    }

    /**
     * 这个函数用于安全地通知外部扫描进度。
     * 输入是监听器、进度百分比、已扫描数量和总数量。
     * 输出是无返回值；如果监听器为空则直接忽略。
     */
    private void notifyProgress(ProgressListener listener, int progress, int scannedCount, int totalCount) {
        if (listener != null) {
            listener.onProgress(progress, scannedCount, totalCount);
        }
    }

    /**
     * 这个函数用于把空文件名替换成兜底名称。
     * 输入是原始字段值和备用值。
     * 输出是可安全展示的字符串名称。
     */
    private String valueOrFallback(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }
}
