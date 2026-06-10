package com.duckya.yaya.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Size;
import android.widget.ImageView;

import com.duckya.yaya.R;
import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.MediaKind;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ThumbnailLoader {
    private static final int THUMBNAIL_SIZE = 360;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    private ThumbnailLoader() {
    }

    public static void loadInto(Context context, MediaItemInfo item, ImageView imageView) {
        String key = item.getUri().toString();
        imageView.setTag(key);
        imageView.setImageResource(R.drawable.ic_photo_library_24);

        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Bitmap bitmap = loadBitmap(appContext, item);
            imageView.post(() -> {
                Object tag = imageView.getTag();
                if (!key.equals(tag)) {
                    return;
                }
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                } else {
                    imageView.setImageResource(item.getKind() == MediaKind.VIDEO
                            ? R.drawable.ic_queue_24
                            : R.drawable.ic_photo_library_24);
                }
            });
        });
    }

    private static Bitmap loadBitmap(Context context, MediaItemInfo item) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.getContentResolver().loadThumbnail(
                        item.getUri(),
                        new Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE),
                        null
                );
            }
            if (item.getKind() == MediaKind.VIDEO) {
                return loadVideoFrame(context, item);
            }
            return loadImageSample(context, item);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Bitmap loadVideoFrame(Context context, MediaItemInfo item) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, item.getUri());
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static Bitmap loadImageSample(Context context, MediaItemInfo item) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(item.getUri())) {
            BitmapFactory.decodeStream(input, null, bounds);
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight);
        try (InputStream input = context.getContentResolver().openInputStream(item.getUri())) {
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private static int calculateSampleSize(int width, int height) {
        int sample = 1;
        while ((width / sample) > THUMBNAIL_SIZE * 2 || (height / sample) > THUMBNAIL_SIZE * 2) {
            sample *= 2;
        }
        return Math.max(sample, 1);
    }
}
