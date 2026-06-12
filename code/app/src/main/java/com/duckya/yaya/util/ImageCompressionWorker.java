package com.duckya.yaya.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// 负责执行单张图片压缩，并把原图副本和压缩结果写回系统相册。
public class ImageCompressionWorker {
    private static final String COMPARISON_ALBUM_PATH = "DCIM/压缩对照";
    private static final String COMPARISON_TIME_PATTERN = "yyyyMMdd_HHmmss_SSS";
    private static final long VISUAL_LOSSLESS_FULL_SIZE_PIXELS = 30_000_000L;
    private static final long VISUAL_LOSSLESS_GIANT_PIXELS = 60_000_000L;
    private static final int VISUAL_LOSSLESS_MAX_LONG_SIDE = 7000;
    private static final int VISUAL_QUALITY_HIGH_DETAIL = 96;
    private static final int VISUAL_QUALITY_NORMAL = 94;
    private static final int VISUAL_QUALITY_LOW_DETAIL = 92;
    @SuppressWarnings("deprecation")
    private static final String[] EXIF_TAGS_TO_COPY = new String[]{
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_ISO,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_SCENE_TYPE,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF
    };

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
        CompressionPlan compressionPlan = buildCompressionPlan(settings, sourceWidth, sourceHeight);

        if (!publishProgress(callback, 0.18f)) {
            throw new InterruptedException("compression cancelled");
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = calculateInSampleSize(sourceWidth, sourceHeight, compressionPlan.maxLongSide);
        Bitmap decodedBitmap = decodeBitmap(context.getContentResolver(), item.getUri(), decodeOptions);
        if (decodedBitmap == null) {
            throw new IOException("无法解码图片内容");
        }

        if (!publishProgress(callback, 0.36f)) {
            decodedBitmap.recycle();
            throw new InterruptedException("compression cancelled");
        }

        Bitmap outputBitmap = scaleBitmapIfNeeded(decodedBitmap, compressionPlan.maxLongSide);
        if (outputBitmap != decodedBitmap) {
            decodedBitmap.recycle();
        }
        compressionPlan.jpegQuality = resolveJpegQuality(settings, outputBitmap, compressionPlan.jpegQuality);

        if (!publishProgress(callback, 0.56f)) {
            outputBitmap.recycle();
            throw new InterruptedException("compression cancelled");
        }

        File tempFile = File.createTempFile("duckya_compress_", ".jpg", context.getCacheDir());
        try {
            compressToTempFile(outputBitmap, compressionPlan.jpegQuality, tempFile);
            copyExifToCompressedFile(context, item.getUri(), tempFile);
            outputBitmap.recycle();

            if (!publishProgress(callback, 0.82f)) {
                throw new InterruptedException("compression cancelled");
            }

            // 同一组对照文件只生成一次批次名，避免原图和压缩图被其他任务混淆。
            ComparisonFileNames fileNames = buildComparisonFileNames(item);
            saveOriginalToComparisonAlbum(context, item, fileNames);
            if (!publishProgress(callback, 0.91f)) {
                throw new InterruptedException("compression cancelled");
            }

            Uri savedUri = saveCompressedToComparisonAlbum(context, fileNames, tempFile);
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

    private CompressionPlan buildCompressionPlan(CompressionSettings settings, int width, int height) {
        if (!settings.isAdaptiveVisualLossless()) {
            return new CompressionPlan(settings.getMaxLongSide(), settings.getJpegQuality());
        }

        long pixels = (long) width * (long) height;
        int longSide = Math.max(width, height);
        int targetLongSide = longSide;
        if (pixels > VISUAL_LOSSLESS_GIANT_PIXELS || longSide > VISUAL_LOSSLESS_MAX_LONG_SIDE) {
            targetLongSide = VISUAL_LOSSLESS_MAX_LONG_SIDE;
        } else if (pixels > VISUAL_LOSSLESS_FULL_SIZE_PIXELS) {
            targetLongSide = Math.min(longSide, VISUAL_LOSSLESS_MAX_LONG_SIDE);
        }
        return new CompressionPlan(targetLongSide, VISUAL_QUALITY_NORMAL);
    }

    private int resolveJpegQuality(CompressionSettings settings, Bitmap bitmap, int fallbackQuality) {
        if (!settings.isAdaptiveVisualLossless()) {
            return fallbackQuality;
        }
        ImageDetailLevel detailLevel = analyzeDetailLevel(bitmap);
        if (detailLevel == ImageDetailLevel.HIGH) {
            return VISUAL_QUALITY_HIGH_DETAIL;
        }
        if (detailLevel == ImageDetailLevel.LOW) {
            return VISUAL_QUALITY_LOW_DETAIL;
        }
        return VISUAL_QUALITY_NORMAL;
    }

    private ImageDetailLevel analyzeDetailLevel(Bitmap bitmap) {
        // 抽样估算亮度边缘和整体变化，避免逐像素扫描拖慢超大图。
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(Math.min(width, height) / 72, 1);
        long count = 0L;
        double lumaSum = 0.0;
        double lumaSquareSum = 0.0;
        double edgeSum = 0.0;
        for (int y = 0; y < height - step; y += step) {
            for (int x = 0; x < width - step; x += step) {
                int luma = lumaOf(bitmap.getPixel(x, y));
                int rightLuma = lumaOf(bitmap.getPixel(x + step, y));
                int downLuma = lumaOf(bitmap.getPixel(x, y + step));
                lumaSum += luma;
                lumaSquareSum += (double) luma * luma;
                edgeSum += Math.abs(luma - rightLuma) + Math.abs(luma - downLuma);
                count++;
            }
        }
        if (count == 0L) {
            return ImageDetailLevel.NORMAL;
        }
        double averageLuma = lumaSum / count;
        double variance = Math.max((lumaSquareSum / count) - averageLuma * averageLuma, 0.0);
        double averageEdge = edgeSum / (count * 2.0);
        if (averageEdge >= 11.0 || variance >= 1600.0) {
            return ImageDetailLevel.HIGH;
        }
        if (averageEdge <= 4.0 && variance <= 320.0) {
            return ImageDetailLevel.LOW;
        }
        return ImageDetailLevel.NORMAL;
    }

    private int lumaOf(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        return (red * 299 + green * 587 + blue * 114) / 1000;
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

    private void compressToTempFile(Bitmap bitmap, int jpegQuality, File tempFile) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(tempFile, false)) {
            boolean success = bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream);
            if (!success) {
                throw new IOException("写入压缩文件失败");
            }
            outputStream.flush();
        }
    }

    private void copyExifToCompressedFile(Context context, Uri sourceUri, File targetFile) {
        // Bitmap 重新编码会丢失 EXIF，这里把拍摄设备、ISO、焦距等拍摄信息写回压缩图。
        try (InputStream inputStream = context.getContentResolver().openInputStream(sourceUri)) {
            if (inputStream == null) {
                return;
            }
            ExifInterface sourceExif = new ExifInterface(inputStream);
            ExifInterface targetExif = new ExifInterface(targetFile.getAbsolutePath());
            boolean changed = false;
            for (String tag : EXIF_TAGS_TO_COPY) {
                String value = sourceExif.getAttribute(tag);
                if (value != null) {
                    targetExif.setAttribute(tag, value);
                    changed = true;
                }
            }
            if (changed) {
                targetExif.saveAttributes();
            }
        } catch (IOException ignored) {
            // 少数格式没有可读 EXIF，压缩本身不应因此失败。
        }
    }

    private Uri saveOriginalToComparisonAlbum(Context context, MediaItemInfo item, ComparisonFileNames fileNames)
            throws IOException {
        try (InputStream inputStream = context.getContentResolver().openInputStream(item.getUri())) {
            if (inputStream == null) {
                throw new IOException("无法读取原图副本");
            }
            return copyStreamToGallery(context, inputStream, fileNames.originalName, fileNames.originalMimeType);
        }
    }

    private Uri saveCompressedToComparisonAlbum(Context context, ComparisonFileNames fileNames, File tempFile)
            throws IOException {
        try (InputStream inputStream = new FileInputStream(tempFile)) {
            return copyStreamToGallery(context, inputStream, fileNames.compressedName, "image/jpeg");
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

    private ComparisonFileNames buildComparisonFileNames(MediaItemInfo item) {
        String extension = extractExtension(item.getName(), "jpg");
        String batchBaseName = buildComparisonBatchBaseName(item);
        return new ComparisonFileNames(
                batchBaseName + "_原始版本." + extension,
                batchBaseName + "_压缩版本.jpg",
                inferImageMimeType(extension)
        );
    }

    private String buildComparisonBatchBaseName(MediaItemInfo item) {
        String timeText = new SimpleDateFormat(COMPARISON_TIME_PATTERN, Locale.US).format(new Date());
        String sourceKey = buildShortSourceKey(item);
        return buildBaseName(item.getName()) + "_" + timeText + "_" + sourceKey;
    }

    private String buildBaseName(@Nullable String sourceName) {
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

    private String extractExtension(@Nullable String sourceName, String fallback) {
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

    private String buildShortSourceKey(MediaItemInfo item) {
        // 用原图 Uri、大小和修改时间生成短标识，同名照片也能稳定区分。
        String rawKey = item.getUri() + "|" + item.getSizeBytes() + "|" + item.getModifiedTimeMs();
        return String.format(Locale.US, "%08x", rawKey.hashCode());
    }

    private String inferImageMimeType(String extension) {
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

    private static class ComparisonFileNames {
        private final String originalName;
        private final String compressedName;
        private final String originalMimeType;

        private ComparisonFileNames(String originalName, String compressedName, String originalMimeType) {
            this.originalName = originalName;
            this.compressedName = compressedName;
            this.originalMimeType = originalMimeType;
        }
    }

    private static class CompressionPlan {
        private final int maxLongSide;
        private int jpegQuality;

        private CompressionPlan(int maxLongSide, int jpegQuality) {
            this.maxLongSide = Math.max(maxLongSide, 1);
            this.jpegQuality = jpegQuality;
        }
    }

    private enum ImageDetailLevel {
        LOW,
        NORMAL,
        HIGH
    }

    private boolean publishProgress(@Nullable ProgressCallback callback, float progress) {
        return callback == null || callback.onProgress(progress);
    }
}
