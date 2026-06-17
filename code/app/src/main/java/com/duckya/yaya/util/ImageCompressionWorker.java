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

// 文件说明：
// 1. 这个文件用于执行单张照片压缩任务。
// 2. 输入是系统相册中的图片 Uri、图片基础信息、照片压缩参数，以及进度回调。
// 3. 处理是读取原图、计算压缩方案、缩放并重新编码 JPEG、复制 EXIF 信息，再把原图副本和压缩图写入系统相册。
// 4. 输出是压缩结果对象，其中包含压缩后文件大小、原图副本 Uri、压缩图 Uri。
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
        private final Uri originalUri;
        private final Uri outputUri;

        public Result(long outputBytes, Uri originalUri, Uri outputUri) {
            this.outputBytes = outputBytes;
            this.originalUri = originalUri;
            this.outputUri = outputUri;
        }

        public long getOutputBytes() {
            return outputBytes;
        }

        public Uri getOriginalUri() {
            return originalUri;
        }

        public Uri getOutputUri() {
            return outputUri;
        }
    }

    // 函数说明：
    // 1. 这个函数用于执行照片压缩完整主流程。
    // 2. 输入是 Context、媒体项信息、照片压缩设置、可选的进度回调。
    // 3. 处理是读取原图尺寸、生成压缩方案、解码 Bitmap、必要时缩放、压缩成临时 JPEG、复制 EXIF，并写回“压缩对照”相册。
    // 4. 输出是 Result，里面包含压缩后大小、原图副本 Uri、压缩图 Uri。
    public Result compress(Context context, MediaItemInfo item, CompressionSettings settings, @Nullable ProgressCallback callback)
            throws IOException, InterruptedException {
        // 照片压缩主流程：读取原图 -> 计算压缩方案 -> 生成临时压缩图 -> 把原图副本和压缩图一起写入“压缩对照”相册。
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
            Uri originalUri = saveOriginalToComparisonAlbum(context, item, fileNames);
            if (!publishProgress(callback, 0.91f)) {
                throw new InterruptedException("compression cancelled");
            }

            Uri savedUri = saveCompressedToComparisonAlbum(context, fileNames, tempFile);
            if (!publishProgress(callback, 1.0f)) {
                throw new InterruptedException("compression cancelled");
            }
            return new Result(tempFile.length(), originalUri, savedUri);
        } finally {
            if (outputBitmap != null && !outputBitmap.isRecycled()) {
                outputBitmap.recycle();
            }
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    // 函数说明：
    // 1. 这个函数用于只读取图片边界信息，不加载完整像素。
    // 2. 输入是 ContentResolver、原图 Uri、BitmapFactory.Options。
    // 3. 输出为空，但会把宽高写入 options 中。
    private void decodeBounds(ContentResolver resolver, Uri uri, BitmapFactory.Options options) throws IOException {
        try (InputStream inputStream = resolver.openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("无法打开原图");
            }
            BitmapFactory.decodeStream(inputStream, null, options);
        }
    }

    // 函数说明：
    // 1. 这个函数用于把原图真正解码为 Bitmap。
    // 2. 输入是 ContentResolver、原图 Uri、解码参数 options。
    // 3. 输出是解码后的 Bitmap。
    private Bitmap decodeBitmap(ContentResolver resolver, Uri uri, BitmapFactory.Options options) throws IOException {
        try (InputStream inputStream = resolver.openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("无法打开原图");
            }
            return BitmapFactory.decodeStream(inputStream, null, options);
        }
    }

    // 函数说明：
    // 1. 这个函数用于根据照片设置和原图尺寸生成压缩计划。
    // 2. 输入是压缩设置、原图宽度、原图高度。
    // 3. 输出是 CompressionPlan，包含目标长边和 JPEG 质量。
    private CompressionPlan buildCompressionPlan(CompressionSettings settings, int width, int height) {
        // 视觉无损档位不是写死参数，而是根据图片像素量和长边尺寸动态决定缩放策略。
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

    // 函数说明：
    // 1. 这个函数用于为视觉无损档位动态决定更合适的 JPEG 质量。
    // 2. 输入是压缩设置、当前 Bitmap、兜底 JPEG 质量。
    // 3. 输出是最终使用的 JPEG 质量值。
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

    // 函数说明：
    // 1. 这个函数用于粗略分析图片细节丰富程度。
    // 2. 输入是 Bitmap。
    // 3. 输出是 ImageDetailLevel，表示低、中、高细节。
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

    // 函数说明：
    // 1. 这个函数用于把一个像素颜色换算成亮度值。
    // 2. 输入是 int 形式的颜色值。
    // 3. 输出是整数亮度值。
    private int lumaOf(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    // 函数说明：
    // 1. 这个函数用于计算图片解码采样率，避免超大图直接全尺寸解码。
    // 2. 输入是原图宽高和目标最大长边。
    // 3. 输出是 inSampleSize。
    private int calculateInSampleSize(int width, int height, int maxLongSide) {
        int sampleSize = 1;
        int longSide = Math.max(width, height);
        while (longSide / sampleSize > maxLongSide * 2) {
            sampleSize *= 2;
        }
        return Math.max(sampleSize, 1);
    }

    // 函数说明：
    // 1. 这个函数用于在必要时按最大长边缩放图片。
    // 2. 输入是原始 Bitmap 和目标最大长边。
    // 3. 输出是缩放后的 Bitmap；如果无需缩放则直接返回原 Bitmap。
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

    // 函数说明：
    // 1. 这个函数用于把 Bitmap 以 JPEG 格式压缩到临时文件。
    // 2. 输入是 Bitmap、JPEG 质量和临时文件。
    // 3. 输出为空，结果写入 tempFile。
    private void compressToTempFile(Bitmap bitmap, int jpegQuality, File tempFile) throws IOException {
        // 图片真正的“压缩引擎”在这里，本质上是 Android Bitmap 重新编码成 JPEG。
        try (FileOutputStream outputStream = new FileOutputStream(tempFile, false)) {
            boolean success = bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream);
            if (!success) {
                throw new IOException("写入压缩文件失败");
            }
            outputStream.flush();
        }
    }

    // 函数说明：
    // 1. 这个函数用于把原图的 EXIF 信息复制到压缩图。
    // 2. 输入是 Context、原图 Uri、压缩后的目标文件。
    // 3. 输出为空，效果是把拍摄信息写回目标文件。
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

    // 函数说明：
    // 1. 这个函数用于把原图副本保存到“压缩对照”相册。
    // 2. 输入是 Context、媒体项、对照文件名。
    // 3. 输出是保存后的原图副本 Uri。
    private Uri saveOriginalToComparisonAlbum(Context context, MediaItemInfo item, ComparisonFileNames fileNames)
            throws IOException {
        try (InputStream inputStream = context.getContentResolver().openInputStream(item.getUri())) {
            if (inputStream == null) {
                throw new IOException("无法读取原图副本");
            }
            return copyStreamToGallery(context, inputStream, fileNames.originalName, fileNames.originalMimeType);
        }
    }

    // 函数说明：
    // 1. 这个函数用于把压缩后的临时图片保存到“压缩对照”相册。
    // 2. 输入是 Context、对照文件名、临时压缩文件。
    // 3. 输出是保存后的压缩图 Uri。
    private Uri saveCompressedToComparisonAlbum(Context context, ComparisonFileNames fileNames, File tempFile)
            throws IOException {
        try (InputStream inputStream = new FileInputStream(tempFile)) {
            return copyStreamToGallery(context, inputStream, fileNames.compressedName, "image/jpeg");
        }
    }

    // 函数说明：
    // 1. 这个函数用于通过 MediaStore 把输入流内容写入系统相册。
    // 2. 输入是 Context、输入流、显示文件名、MIME 类型。
    // 3. 输出是系统相册中新建媒体条目的 Uri。
    private Uri copyStreamToGallery(Context context, InputStream inputStream, String displayName, String mimeType)
            throws IOException {
        // 统一通过 MediaStore 写回系统相册，避免直接操作外部存储文件带来的兼容性问题。
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

    // 函数说明：
    // 1. 这个函数用于生成原图副本和压缩图的成对文件名。
    // 2. 输入是媒体项信息。
    // 3. 输出是 ComparisonFileNames，对应原图文件名、压缩图文件名和 MIME 类型。
    private ComparisonFileNames buildComparisonFileNames(MediaItemInfo item) {
        String extension = extractExtension(item.getName(), "jpg");
        String batchBaseName = buildComparisonBatchBaseName(item);
        return new ComparisonFileNames(
                batchBaseName + "_原始版本." + extension,
                batchBaseName + "_压缩版本.jpg",
                inferImageMimeType(extension)
        );
    }

    // 函数说明：
    // 1. 这个函数用于生成一次压缩任务的唯一批次名。
    // 2. 输入是媒体项信息。
    // 3. 输出是字符串形式的批次基名。
    private String buildComparisonBatchBaseName(MediaItemInfo item) {
        String timeText = new SimpleDateFormat(COMPARISON_TIME_PATTERN, Locale.US).format(new Date());
        String sourceKey = buildShortSourceKey(item);
        return buildBaseName(item.getName()) + "_" + timeText + "_" + sourceKey;
    }

    // 函数说明：
    // 1. 这个函数用于从原文件名中提取安全的基础文件名。
    // 2. 输入是原始文件名。
    // 3. 输出是去掉扩展名并清理非法字符后的基础名。
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

    // 函数说明：
    // 1. 这个函数用于从原文件名中提取扩展名。
    // 2. 输入是原始文件名和默认扩展名。
    // 3. 输出是最终扩展名字符串。
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

    // 函数说明：
    // 1. 这个函数用于根据原图来源生成短标识，避免同名图片冲突。
    // 2. 输入是媒体项信息。
    // 3. 输出是字符串形式的短标识。
    private String buildShortSourceKey(MediaItemInfo item) {
        // 用原图 Uri、大小和修改时间生成短标识，同名照片也能稳定区分。
        String rawKey = item.getUri() + "|" + item.getSizeBytes() + "|" + item.getModifiedTimeMs();
        return String.format(Locale.US, "%08x", rawKey.hashCode());
    }

    // 函数说明：
    // 1. 这个函数用于根据扩展名推断图片 MIME 类型。
    // 2. 输入是扩展名。
    // 3. 输出是 MIME 类型字符串。
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

    // 函数说明：
    // 1. 这个函数用于统一分发压缩进度。
    // 2. 输入是进度回调和当前进度值。
    // 3. 输出是布尔值，表示任务是否继续执行。
    private boolean publishProgress(@Nullable ProgressCallback callback, float progress) {
        return callback == null || callback.onProgress(progress);
    }
}
