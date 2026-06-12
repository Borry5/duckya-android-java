package com.duckya.yaya.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.duckya.yaya.model.CompressionSettings;
import com.duckya.yaya.model.MediaItemInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

// 负责执行单张图片压缩，并把原图副本和压缩结果写回系统相册。
public class ImageCompressionWorker {
    private static final String COMPARISON_ALBUM_PATH = "DCIM/压缩对照";

    public interface ProgressCallback {
        boolean onProgress(float progress);
    }

    public static class Result {
        private final long outputBytes;
        private final Uri outputUri;

        public Result(long outputBytes, Uri outputUri) {
            this.outputBytes = outputBytes;
            this.outputUri = outputUri;
        }

        public long getOutputBytes() {
            return outputBytes;
        }

        public Uri getOutputUri() {
            return outputUri;
        }
    }

    public Result compress(Context context, MediaItemInfo item, CompressionSettings settings, @Nullable ProgressCallback callback)
            throws IOException, InterruptedException {
        if (!publishProgress(callback, 0.05f)) {
            throw new InterruptedException("compression cancelled");
        }

        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        decodeBounds(context.getContentResolver(), item.getUri(), boundsOptions);
        int sourceWidth = Math.max(boundsOptions.outWidth, 0);
        int sourceHeight = Math.max(boundsOptions.outHeight, 0);
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IOException("无法读取图片尺寸");
        }

        if (!publishProgress(callback, 0.18f)) {
            throw new InterruptedException("compression cancelled");
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = calculateInSampleSize(sourceWidth, sourceHeight, settings.getMaxLongSide());
        Bitmap decodedBitmap = decodeBitmap(context.getContentResolver(), item.getUri(), decodeOptions);
        if (decodedBitmap == null) {
            throw new IOException("无法解码图片内容");
        }

        if (!publishProgress(callback, 0.36f)) {
            decodedBitmap.recycle();
            throw new InterruptedException("compression cancelled");
        }

        Bitmap outputBitmap = scaleBitmapIfNeeded(decodedBitmap, settings.getMaxLongSide());
        if (outputBitmap != decodedBitmap) {
            decodedBitmap.recycle();
        }

        if (!publishProgress(callback, 0.56f)) {
            outputBitmap.recycle();
            throw new InterruptedException("compression cancelled");
        }

        File tempFile = File.createTempFile("duckya_compress_", ".jpg", context.getCacheDir());
        try {
            compressToTempFile(outputBitmap, settings, tempFile);
            outputBitmap.recycle();

            if (!publishProgress(callback, 0.82f)) {
                throw new InterruptedException("compression cancelled");
            }

            // 保存一组对照文件，方便用户在系统相册里直接比较压缩前后效果。
            saveOriginalToComparisonAlbum(context, item);
            if (!publishProgress(callback, 0.91f)) {
                throw new InterruptedException("compression cancelled");
            }

            Uri savedUri = saveCompressedToComparisonAlbum(context, item.getName(), tempFile);
            if (!publishProgress(callback, 1.0f)) {
                throw new InterruptedException("compression cancelled");
            }
            return new Result(tempFile.length(), savedUri);
        } finally {
            if (outputBitmap != null && !outputBitmap.isRecycled()) {
                outputBitmap.recycle();
            }
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void decodeBounds(ContentResolver resolver, Uri uri, BitmapFactory.Options options) throws IOException {
        try (InputStream inputStream = resolver.openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("无法打开原图");
            }
            BitmapFactory.decodeStream(inputStream, null, options);
        }
    }

    private Bitmap decodeBitmap(ContentResolver resolver, Uri uri, BitmapFactory.Options options) throws IOException {
        try (InputStream inputStream = resolver.openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("无法打开原图");
            }
            return BitmapFactory.decodeStream(inputStream, null, options);
        }
    }

    private int calculateInSampleSize(int width, int height, int maxLongSide) {
        int sampleSize = 1;
        int longSide = Math.max(width, height);
        while (longSide / sampleSize > maxLongSide * 2) {
            sampleSize *= 2;
        }
        return Math.max(sampleSize, 1);
    }

    private Bitmap scaleBitmapIfNeeded(Bitmap bitmap, int maxLongSide) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longSide = Math.max(width, height);
        if (longSide <= maxLongSide) {
            return bitmap;
        }
        float scale = maxLongSide / (float) longSide;
        int targetWidth = Math.max(Math.round(width * scale), 1);
        int targetHeight = Math.max(Math.round(height * scale), 1);
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    private void compressToTempFile(Bitmap bitmap, CompressionSettings settings, File tempFile) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(tempFile, false)) {
            boolean success = bitmap.compress(Bitmap.CompressFormat.JPEG, settings.getJpegQuality(), outputStream);
            if (!success) {
                throw new IOException("写入压缩文件失败");
            }
            outputStream.flush();
        }
    }

    private Uri saveOriginalToComparisonAlbum(Context context, MediaItemInfo item) throws IOException {
        String originalName = buildOriginalFileName(item.getName());
        String mimeType = inferImageMimeType(originalName);
        try (InputStream inputStream = context.getContentResolver().openInputStream(item.getUri())) {
            if (inputStream == null) {
                throw new IOException("无法读取原图副本");
            }
            return copyStreamToGallery(context, inputStream, originalName, mimeType);
        }
    }

    private Uri saveCompressedToComparisonAlbum(Context context, String sourceName, File tempFile) throws IOException {
        try (InputStream inputStream = new FileInputStream(tempFile)) {
            return copyStreamToGallery(context, inputStream, buildCompressedFileName(sourceName), "image/jpeg");
        }
    }

    private Uri copyStreamToGallery(Context context, InputStream inputStream, String displayName, String mimeType)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, COMPARISON_ALBUM_PATH);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) {
            throw new IOException("无法创建相册输出项");
        }
        try {
            try (OutputStream outputStream = resolver.openOutputStream(outputUri, "w")) {
                if (outputStream == null) {
                    throw new IOException("无法写入系统相册");
                }
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                outputStream.flush();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues doneValues = new ContentValues();
                doneValues.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(outputUri, doneValues, null, null);
            }
            return outputUri;
        } catch (IOException e) {
            resolver.delete(outputUri, null, null);
            throw e;
        }
    }

    private String buildOriginalFileName(String sourceName) {
        String extension = extractExtension(sourceName, "jpg");
        return buildBaseName(sourceName) + "_original." + extension;
    }

    private String buildCompressedFileName(String sourceName) {
        return buildBaseName(sourceName) + "_compressed.jpg";
    }

    private String buildBaseName(String sourceName) {
        String baseName = sourceName == null ? "image" : sourceName.trim();
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }
        baseName = baseName.replace('/', '_').replace('\\', '_');
        if (baseName.isEmpty()) {
            baseName = "image";
        }
        return baseName;
    }

    private String extractExtension(String sourceName, String fallback) {
        if (sourceName == null) {
            return fallback;
        }
        int dotIndex = sourceName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == sourceName.length() - 1) {
            return fallback;
        }
        String extension = sourceName.substring(dotIndex + 1)
                .replace('/', '_')
                .replace('\\', '_')
                .toLowerCase(Locale.US);
        return extension.isEmpty() ? fallback : extension;
    }

    private String inferImageMimeType(String fileName) {
        String extension = extractExtension(fileName, "jpg");
        if ("png".equals(extension)) {
            return "image/png";
        }
        if ("webp".equals(extension)) {
            return "image/webp";
        }
        if ("gif".equals(extension)) {
            return "image/gif";
        }
        if ("heic".equals(extension) || "heif".equals(extension)) {
            return "image/heif";
        }
        return "image/jpeg";
    }

    private boolean publishProgress(@Nullable ProgressCallback callback, float progress) {
        return callback == null || callback.onProgress(progress);
    }
}
