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

/**
 * 照片压缩执行器（核心类）
 * 
 * 【职责边界】
 * 这个文件只负责一件事：把一张照片压缩变小。
 * 
 * 【输入】
 * - 照片的 URI（告诉系统照片在哪儿）
 * - 照片的基本信息（宽、高、大小等）
 * - 压缩参数（用户选的是“无感/平衡/强力”）
 * 
 * 【处理流程】
 * 1. 读取原图尺寸（不加载全图，防内存溢出）
 * 2. 根据参数计算压缩计划（目标尺寸、JPEG质量）
 * 3. 采样解码 Bitmap（降低内存占用）
 * 4. 缩放到目标尺寸
 * 5. 动态调整 JPEG 质量（无感模式下根据图片细节自动决策）
 * 6. 编码成 JPEG 临时文件
 * 7. 把原图的 EXIF 信息（拍摄时间、GPS、相机型号等）复制到压缩图
 * 8. 把原图副本和压缩图一起写入系统相册的“压缩对照”目录
 * 
 * 【输出】
 * 返回一个 Result 对象，包含：
 * - 压缩后的文件大小
 * - 原图副本在相册中的 URI
 * - 压缩图在相册中的 URI
 */
public class ImageCompressionWorker {

    // ============================================================
    // 一、常量配置（压缩策略的“调参面板”）
    // ============================================================

    /**
     * 压缩后的照片存到相册的哪个目录
     * 最终路径：DCIM/压缩对照
     */
    private static final String COMPARISON_ALBUM_PATH = "DCIM/压缩对照";

    /**
     * 对照文件名中使用的时间戳格式
     * 例如：20250617_143025_123（年 月 日 _ 时 分 秒 _ 毫秒）
     */
    private static final String COMPARISON_TIME_PATTERN = "yyyyMMdd_HHmmss_SSS";

    /**
     * 【无感模式】尺寸自适应阈值（一）
     * 超过 3000 万像素的照片，属于“高像素图”，需要适当缩小
     */
    private static final long VISUAL_LOSSLESS_FULL_SIZE_PIXELS = 30_000_000L;

    /**
     * 【无感模式】尺寸自适应阈值（二）
     * 超过 6000 万像素的照片，属于“超大图”，必须强制缩小
     */
    private static final long VISUAL_LOSSLESS_GIANT_PIXELS = 60_000_000L;

    /**
     * 【无感模式】最大长边限制
     * 无论如何压缩，长边不超过 7000 像素
     * 目的：防止超高分辨率全景图把内存撑爆
     */
    private static final int VISUAL_LOSSLESS_MAX_LONG_SIDE = 7000;

    /**
     * 【无感模式】JPEG 质量档位
     * 范围 0-100，越高画质越好、文件越大
     * 96：高细节图（风景、人像）→ 保留更多细节
     * 94：普通图 → 平衡
     * 92：低细节图（截图、纯色背景）→ 可以压更低
     */
    private static final int VISUAL_QUALITY_HIGH_DETAIL = 96;
    private static final int VISUAL_QUALITY_NORMAL = 94;
    private static final int VISUAL_QUALITY_LOW_DETAIL = 92;

    /**
     * EXIF 元数据标签列表（共 40+ 个）
     * 
     * 什么是 EXIF？
     * 照片的“隐藏身份证”——包含了拍摄时间、GPS位置、相机型号、光圈、ISO、焦距等。
     * 
     * 为什么要复制这些标签？
     * 当使用 Bitmap.compress() 重新编码 JPEG 时，这些信息会全部丢失。
     * 如果不复制，压缩后的照片就变成了一张“无头照片”。
     * 
     * 这里定义了 40 多个标签，原图里有的，全部复制到压缩图。
     */
    @SuppressWarnings("deprecation")
    private static final String[] EXIF_TAGS_TO_COPY = new String[]{
            // 设备信息
            ExifInterface.TAG_MAKE,           // 相机品牌（如 "Apple"）
            ExifInterface.TAG_MODEL,          // 相机型号（如 "iPhone 15 Pro"）
            ExifInterface.TAG_SOFTWARE,       // 处理软件

            // 时间信息（拍摄时间、数字化时间等）
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,

            // 拍摄参数
            ExifInterface.TAG_ORIENTATION,    // 旋转方向
            ExifInterface.TAG_ISO,            // ISO 感光度
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,   // 焦距
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_F_NUMBER,       // 光圈值
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_EXPOSURE_TIME,  // 曝光时间
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_FLASH,          // 闪光灯
            ExifInterface.TAG_WHITE_BALANCE,  // 白平衡
            ExifInterface.TAG_METERING_MODE,  // 测光模式
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_SCENE_TYPE,
            ExifInterface.TAG_COLOR_SPACE,

            // GPS 地理位置信息
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

    // ============================================================
    // 二、接口定义（对外交互的“契约”）
    // ============================================================

    /**
     * 进度回调接口
     * 
     * 用于让 UI 层感知压缩进度（0%~100%）
     * 返回值 true：继续压缩；false：用户取消了，立即中断
     */
    public interface ProgressCallback {
        boolean onProgress(float progress);
    }

    /**
     * 压缩结果封装类
     * 
     * 压缩完成后，返回这三个信息给调用方（QueueManager）
     */
    public static class Result {
        private final long outputBytes;      // 压缩后的文件大小（字节）
        private final Uri originalUri;       // 原图副本在相册中的 URI
        private final Uri outputUri;         // 压缩图在相册中的 URI

        public Result(long outputBytes, Uri originalUri, Uri outputUri) {
            this.outputBytes = outputBytes;
            this.originalUri = originalUri;
            this.outputUri = outputUri;
        }

        public long getOutputBytes() { return outputBytes; }
        public Uri getOriginalUri() { return originalUri; }
        public Uri getOutputUri() { return outputUri; }
    }

    // ============================================================
    // 三、主流程（对外唯一入口）
    // ============================================================

    /**
     * 【主入口】执行照片压缩完整流程
     * 
     * 别人调用这个方法，传入照片和参数，它就开始压缩。
     * 整个压缩过程是同步的（在调用线程中执行），由上层 QueueManager 在后台线程中调用。
     * 
     * @param context   应用上下文（用于访问 ContentResolver 和缓存目录）
     * @param item      照片信息（包含 URI、宽高、大小等）
     * @param settings  压缩参数（档位、目标尺寸、质量等）
     * @param callback  进度回调（可为 null，表示不需要进度通知）
     * @return Result 对象，包含压缩后大小和存到相册的 URI
     * @throws IOException           IO 错误（如无法读取原图、无法写入相册）
     * @throws InterruptedException  用户取消了压缩
     */
    public Result compress(
            Context context,
            MediaItemInfo item,
            CompressionSettings settings,
            @Nullable ProgressCallback callback
    ) throws IOException, InterruptedException {

        // ============================================================
        // 步骤 0：进度检查（5%）
        // ============================================================
        // 每到一个关键节点，都检查用户是否取消
        // 如果回调返回 false，说明用户点了取消，立即中断
        if (!publishProgress(callback, 0.05f)) {
            throw new InterruptedException("compression cancelled");
        }

        // ============================================================
        // 步骤 1：读取图片尺寸（只读宽高，不加载像素）
        // ============================================================
        // 为什么只读尺寸？
        // 假设有一张 8000x6000 的照片（4800万像素），直接解码会占用
        // 8000*6000*4 ≈ 192MB 内存，手机直接崩溃。
        // inJustDecodeBounds=true 只读文件头里的宽高信息，内存消耗几乎为0。
        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        decodeBounds(context.getContentResolver(), item.getUri(), boundsOptions);

        int sourceWidth = Math.max(boundsOptions.outWidth, 0);
        int sourceHeight = Math.max(boundsOptions.outHeight, 0);

        // 如果读不到宽高，说明图片损坏或格式不支持
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IOException("无法读取图片尺寸");
        }

        // ============================================================
        // 步骤 2：计算压缩计划
        // ============================================================
        // 根据用户选的档位和图片尺寸，决定：
        // - 目标长边（缩放到多大）
        // - JPEG 质量（用多少质量编码）
        CompressionPlan compressionPlan = buildCompressionPlan(settings, sourceWidth, sourceHeight);

        if (!publishProgress(callback, 0.18f)) {
            throw new InterruptedException("compression cancelled");
        }

        // ============================================================
        // 步骤 3：采样解码 Bitmap
        // ============================================================
        // 用计算好的采样率解码图片，避免加载全尺寸大图
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = calculateInSampleSize(
                sourceWidth, sourceHeight, compressionPlan.maxLongSide
        );

        Bitmap decodedBitmap = decodeBitmap(context.getContentResolver(), item.getUri(), decodeOptions);
        if (decodedBitmap == null) {
            throw new IOException("无法解码图片内容");
        }

        if (!publishProgress(callback, 0.36f)) {
            decodedBitmap.recycle();
            throw new InterruptedException("compression cancelled");
        }

        // ============================================================
        // 步骤 4：缩放到目标尺寸
        // ============================================================
        Bitmap outputBitmap = scaleBitmapIfNeeded(decodedBitmap, compressionPlan.maxLongSide);
        if (outputBitmap != decodedBitmap) {
            // 如果缩放产生了新 Bitmap，回收原来的
            decodedBitmap.recycle();
        }

        // ============================================================
        // 步骤 5：动态调整 JPEG 质量（仅无感模式）
        // ============================================================
        // 如果是“无感”模式，根据图片细节复杂度决定用 92/94/96
        compressionPlan.jpegQuality = resolveJpegQuality(
                settings, outputBitmap, compressionPlan.jpegQuality
        );

        if (!publishProgress(callback, 0.56f)) {
            outputBitmap.recycle();
            throw new InterruptedException("compression cancelled");
        }

        // ============================================================
        // 步骤 6：创建临时文件
        // ============================================================
        // 在应用缓存目录创建临时文件，用于存放压缩后的 JPEG
        // 命名：duckya_compress_xxxxx.jpg
        File tempFile = File.createTempFile(
                "duckya_compress_",
                ".jpg",
                context.getCacheDir()
        );

        try {
            // ============================================================
            // 步骤 7：编码成 JPEG 到临时文件
            // ============================================================
            // 调用 Android 原生 Bitmap.compress() 进行 JPEG 编码
            // 这是真正的“压缩引擎”，我们只是调用系统能力
            compressToTempFile(outputBitmap, compressionPlan.jpegQuality, tempFile);

            // ============================================================
            // 步骤 8：复制 EXIF 信息到压缩图
            // ============================================================
            // Bitmap.compress() 会丢失所有 EXIF 信息
            // 这里用 ExifInterface 把原图的 40+ 个标签全部复制过去
            copyExifToCompressedFile(context, item.getUri(), tempFile);

            // Bitmap 用完了，回收内存
            outputBitmap.recycle();

            if (!publishProgress(callback, 0.82f)) {
                throw new InterruptedException("compression cancelled");
            }

            // ============================================================
            // 步骤 9：生成对照文件名
            // ============================================================
            // 格式：原文件名_时间戳_哈希值_原始版本.jpg
            //       原文件名_时间戳_哈希值_压缩版本.jpg
            ComparisonFileNames fileNames = buildComparisonFileNames(item);

            // ============================================================
            // 步骤 10：保存原图副本到“压缩对照”相册
            // ============================================================
            Uri originalUri = saveOriginalToComparisonAlbum(context, item, fileNames);

            if (!publishProgress(callback, 0.91f)) {
                throw new InterruptedException("compression cancelled");
            }

            // ============================================================
            // 步骤 11：保存压缩图到“压缩对照”相册
            // ============================================================
            Uri savedUri = saveCompressedToComparisonAlbum(context, fileNames, tempFile);

            if (!publishProgress(callback, 1.0f)) {
                throw new InterruptedException("compression cancelled");
            }

            // ============================================================
            // 步骤 12：返回结果
            // ============================================================
            return new Result(tempFile.length(), originalUri, savedUri);

        } finally {
            // ============================================================
            // 步骤 13：清理资源
            // ============================================================
            // 回收 Bitmap 占用的 Native 内存
            if (outputBitmap != null && !outputBitmap.isRecycled()) {
                outputBitmap.recycle();
            }
            // 删除临时文件（已经写入相册了，不需要了）
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    // ============================================================
    // 四、私有辅助方法（各步骤的具体实现）
    // ============================================================

    /**
     * 【步骤 1 实现】只读取图片边界信息，不加载完整像素
     * 
     * @param resolver  ContentResolver（用于打开 URI）
     * @param uri       图片的 URI
     * @param options   BitmapFactory.Options（outWidth/outHeight 会被填充）
     */
    private void decodeBounds(ContentResolver resolver, Uri uri, BitmapFactory.Options options)
            throws IOException {
        try (InputStream inputStream = resolver.openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("无法打开原图");
            }
            BitmapFactory.decodeStream(inputStream, null, options);
        }
    }

    /**
     * 【步骤 3 实现】把原图真正解码为 Bitmap
     * 
     * @param resolver  ContentResolver
     * @param uri       图片的 URI
     * @param options   解码参数（包含 inSampleSize）
     * @return 解码后的 Bitmap
     */
    private Bitmap decodeBitmap(ContentResolver resolver, Uri uri, BitmapFactory.Options options)
            throws IOException {
        try (InputStream inputStream = resolver.openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("无法打开原图");
            }
            return BitmapFactory.decodeStream(inputStream, null, options);
        }
    }

    /**
     * 【步骤 2 实现】根据压缩设置和原图尺寸生成压缩计划
     * 
     * 逻辑：
     * - 如果是“平衡”或“强力”模式：直接用用户预设的数值
     * - 如果是“无感”模式：根据像素量动态决定目标尺寸
     * 
     * @param settings 压缩参数
     * @param width    原图宽度
     * @param height   原图高度
     * @return CompressionPlan（目标长边 + JPEG 质量）
     */
    private CompressionPlan buildCompressionPlan(CompressionSettings settings, int width, int height) {
        // 如果不是“无感”模式，直接用用户设定的参数
        if (!settings.isAdaptiveVisualLossless()) {
            return new CompressionPlan(settings.getMaxLongSide(), settings.getJpegQuality());
        }

        // ---------- “无感”模式：动态计算 ----------
        long pixels = (long) width * (long) height;
        int longSide = Math.max(width, height);
        int targetLongSide = longSide;

        // 如果超过 6000 万像素 或 长边超过 7000，强制压到 7000
        if (pixels > VISUAL_LOSSLESS_GIANT_PIXELS || longSide > VISUAL_LOSSLESS_MAX_LONG_SIDE) {
            targetLongSide = VISUAL_LOSSLESS_MAX_LONG_SIDE;
        }
        // 如果在 3000 万 ~ 6000 万之间，适当缩小（但不超过 7000）
        else if (pixels > VISUAL_LOSSLESS_FULL_SIZE_PIXELS) {
            targetLongSide = Math.min(longSide, VISUAL_LOSSLESS_MAX_LONG_SIDE);
        }
        // 低于 3000 万像素，不缩小，保留原尺寸

        // 质量先用 94%，后面还会根据细节调整
        return new CompressionPlan(targetLongSide, VISUAL_QUALITY_NORMAL);
    }

    /**
     * 【步骤 5 实现】为“无感”模式动态决定更合适的 JPEG 质量
     * 
     * 逻辑：
     * - 分析图片细节复杂度
     * - 高细节（风景/人像）→ 96%
     * - 低细节（纯色截图）→ 92%
     * - 普通 → 94%
     * 
     * @param settings       压缩参数
     * @param bitmap         当前 Bitmap
     * @param fallbackQuality 兜底质量（如果是非无感模式直接用这个）
     * @return 最终使用的 JPEG 质量值
     */
    private int resolveJpegQuality(CompressionSettings settings, Bitmap bitmap, int fallbackQuality) {
        if (!settings.isAdaptiveVisualLossless()) {
            return fallbackQuality;
        }
        ImageDetailLevel detailLevel = analyzeDetailLevel(bitmap);
        if (detailLevel == ImageDetailLevel.HIGH) {
            return VISUAL_QUALITY_HIGH_DETAIL;   // 96
        }
        if (detailLevel == ImageDetailLevel.LOW) {
            return VISUAL_QUALITY_LOW_DETAIL;    // 92
        }
        return VISUAL_QUALITY_NORMAL;             // 94
    }

    /**
     * 【步骤 5 子方法】分析图片细节丰富程度
     * 
     * 实现原理：
     * 1. 每隔 step 个像素采样一次（不是全量扫描，否则太慢）
     * 2. 计算亮度方差（variance）—— 方差大说明颜色丰富
     * 3. 计算边缘强度（averageEdge）—— 边缘强说明有清晰轮廓
     * 
     * 判定规则：
     * - 边缘强 且 方差大 → 高细节
     * - 边缘弱 且 方差小 → 低细节
     * - 其他 → 普通
     * 
     * @param bitmap 当前 Bitmap
     * @return 低/中/高细节
     */
    private ImageDetailLevel analyzeDetailLevel(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // step：采样步长，图片尺寸的 1/72
        // 例如 4000x3000 的图，step ≈ 55，只采样约 1/55 的像素
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
                // 边缘强度 = 水平差 + 垂直差
                edgeSum += Math.abs(luma - rightLuma) + Math.abs(luma - downLuma);
                count++;
            }
        }

        if (count == 0L) {
            return ImageDetailLevel.NORMAL;
        }

        // 计算平均亮度和方差
        double averageLuma = lumaSum / count;
        double variance = Math.max((lumaSquareSum / count) - averageLuma * averageLuma, 0.0);
        double averageEdge = edgeSum / (count * 2.0);

        // 高细节：边缘强 或 方差大
        if (averageEdge >= 11.0 || variance >= 1600.0) {
            return ImageDetailLevel.HIGH;
        }
        // 低细节：边缘弱 且 方差小
        if (averageEdge <= 4.0 && variance <= 320.0) {
            return ImageDetailLevel.LOW;
        }
        return ImageDetailLevel.NORMAL;
    }

    /**
     * 【步骤 5 子方法】把一个像素的 ARGB 颜色换算成亮度值（Y）
     * 
     * 亮度公式：Y = 0.299*R + 0.587*G + 0.114*B
     * 这是人眼对不同颜色敏感度的加权平均
     * 
     * @param color 像素颜色值（int）
     * @return 亮度值（0~255）
     */
    private int lumaOf(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    /**
     * 【步骤 3 子方法】计算图片解码采样率（inSampleSize）
     * 
     * 为什么要采样？
     * 直接解码 4000x3000 的图需要 48MB 内存。
     * 用 inSampleSize=2 解码后得到 2000x1500，只需要 12MB。
     * 
     * 目标：解码后的图片长边大约为目标长边的 2 倍，
     *       然后再用 scaleBitmapIfNeeded 缩放到精确尺寸。
     *       这样既保证了最终画质，又大幅降低了内存峰值。
     * 
     * @param width        原图宽度
     * @param height       原图高度
     * @param maxLongSide  目标最大长边
     * @return inSampleSize（2 的倍数，如 1, 2, 4, 8...）
     */
    private int calculateInSampleSize(int width, int height, int maxLongSide) {
        int sampleSize = 1;
        int longSide = Math.max(width, height);
        // 当长边 / sampleSize > 目标长边 * 2 时，继续翻倍
        while (longSide / sampleSize > maxLongSide * 2) {
            sampleSize *= 2;
        }
        return Math.max(sampleSize, 1);
    }

    /**
     * 【步骤 4 实现】在必要时按最大长边缩放图片
     * 
     * 如果原图长边已经小于等于目标长边，不缩放，直接返回原图。
     * 否则，按比例缩放到目标长边。
     * 
     * @param bitmap       原始 Bitmap
     * @param maxLongSide  目标最大长边
     * @return 缩放后的 Bitmap；如果无需缩放则直接返回原 Bitmap
     */
    private Bitmap scaleBitmapIfNeeded(Bitmap bitmap, int maxLongSide) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longSide = Math.max(width, height);

        if (longSide <= maxLongSide) {
            return bitmap; // 无需缩放
        }

        float scale = maxLongSide / (float) longSide;
        int targetWidth = Math.max(Math.round(width * scale), 1);
        int targetHeight = Math.max(Math.round(height * scale), 1);

        // 使用双线性滤波（true），缩放后更平滑
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    /**
     * 【步骤 7 实现】把 Bitmap 以 JPEG 格式压缩到临时文件
     * 
     * 注意：这只是把 Bitmap 编码成 JPEG，不涉及任何“压缩算法”的创新。
     * 真正的“压缩引擎”是 Android 系统的 Bitmap.compress()。
     * 我们做的只是调用系统能力，并选择合适的质量参数。
     * 
     * @param bitmap       要编码的 Bitmap
     * @param jpegQuality  JPEG 质量（0~100）
     * @param tempFile     输出临时文件
     */
    private void compressToTempFile(Bitmap bitmap, int jpegQuality, File tempFile)
            throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(tempFile, false)) {
            boolean success = bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream);
            if (!success) {
                throw new IOException("写入压缩文件失败");
            }
            outputStream.flush();
        }
    }

    /**
     * 【步骤 8 实现】把原图的 EXIF 信息复制到压缩图
     * 
     * 为什么要复制？
     * Bitmap.compress() 会丢失所有 EXIF 信息。
     * 这里用 ExifInterface 把原图的 40+ 个标签全部复制过去。
     * 
     * 如果原图本身没有 EXIF（如某些截图），则跳过，不影响压缩。
     * 
     * @param context    应用上下文
     * @param sourceUri  原图的 URI
     * @param targetFile 压缩后的目标文件
     */
    private void copyExifToCompressedFile(Context context, Uri sourceUri, File targetFile) {
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
            // 少数格式没有可读 EXIF，压缩本身不应因此失败
        }
    }

    /**
     * 【步骤 10 实现】把原图副本保存到“压缩对照”相册
     * 
     * @param context   应用上下文
     * @param item      原图信息
     * @param fileNames 对照文件名
     * @return 保存后的原图副本 URI
     */
    private Uri saveOriginalToComparisonAlbum(
            Context context,
            MediaItemInfo item,
            ComparisonFileNames fileNames
    ) throws IOException {
        try (InputStream inputStream = context.getContentResolver().openInputStream(item.getUri())) {
            if (inputStream == null) {
                throw new IOException("无法读取原图副本");
            }
            return copyStreamToGallery(
                    context,
                    inputStream,
                    fileNames.originalName,
                    fileNames.originalMimeType
            );
        }
    }

    /**
     * 【步骤 11 实现】把压缩后的临时图片保存到“压缩对照”相册
     * 
     * @param context   应用上下文
     * @param fileNames 对照文件名
     * @param tempFile  临时压缩文件
     * @return 保存后的压缩图 URI
     */
    private Uri saveCompressedToComparisonAlbum(
            Context context,
            ComparisonFileNames fileNames,
            File tempFile
    ) throws IOException {
        try (InputStream inputStream = new FileInputStream(tempFile)) {
            return copyStreamToGallery(
                    context,
                    inputStream,
                    fileNames.compressedName,
                    "image/jpeg"
            );
        }
    }

    /**
     * 【步骤 10/11 核心】通过 MediaStore 把输入流内容写入系统相册
     * 
     * 为什么用 MediaStore 而不是直接写文件？
     * Android 10+ 限制了直接访问外部存储，
     * MediaStore 是官方推荐的方式，兼容性更好。
     * 
     * 关键步骤：
     * 1. 创建 ContentValues，设置文件名、路径、MIME 类型
     * 2. Android 10+ 使用 IS_PENDING = 1 标记“写入中”
     * 3. 写入文件内容
     * 4. Android 10+ 使用 IS_PENDING = 0 标记“写入完成”
     * 
     * @param context     应用上下文
     * @param inputStream 要写入的数据流
     * @param displayName 显示文件名
     * @param mimeType    MIME 类型（如 image/jpeg）
     * @return 系统相册中新建媒体条目的 URI
     */
    private Uri copyStreamToGallery(
            Context context,
            InputStream inputStream,
            String displayName,
            String mimeType
    ) throws IOException {
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);

        // Android 10+ 使用相对路径和 IS_PENDING 标记
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, COMPARISON_ALBUM_PATH);
            values.put(MediaStore.Images.Media.IS_PENDING, 1); // 标记“写入中”
        }

        Uri outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) {
            throw new IOException("无法创建相册输出项");
        }

        try {
            // 写入文件内容
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

            // Android 10+ 标记“写入完成”
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues doneValues = new ContentValues();
                doneValues.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(outputUri, doneValues, null, null);
            }

            return outputUri;

        } catch (IOException e) {
            // 写入失败，删除刚创建的条目，避免残留损坏文件
            resolver.delete(outputUri, null, null);
            throw e;
        }
    }

    /**
     * 【步骤 9 实现】生成原图副本和压缩图的成对文件名
     * 
     * 文件名格式：
     *   原文件名_20250617_143025_12345678_原始版本.jpg
     *   原文件名_20250617_143025_12345678_压缩版本.jpg
     * 
     * 其中 12345678 是原图 URI 的哈希值，防止同名文件冲突。
     * 
     * @param item 原图信息
     * @return ComparisonFileNames（包含原始文件名、压缩文件名、MIME类型）
     */
    private ComparisonFileNames buildComparisonFileNames(MediaItemInfo item) {
        String extension = extractExtension(item.getName(), "jpg");
        String batchBaseName = buildComparisonBatchBaseName(item);
        return new ComparisonFileNames(
                batchBaseName + "_原始版本." + extension,
                batchBaseName + "_压缩版本.jpg",
                inferImageMimeType(extension)
        );
    }

    /**
     * 【步骤 9 子方法】生成一次压缩任务的唯一批次名
     * 
     * 格式：原文件名_时间戳_哈希值
     * 
     * @param item 原图信息
     * @return 批次基名
     */
    private String buildComparisonBatchBaseName(MediaItemInfo item) {
        String timeText = new SimpleDateFormat(COMPARISON_TIME_PATTERN, Locale.US)
                .format(new Date());
        String sourceKey = buildShortSourceKey(item);
        return buildBaseName(item.getName()) + "_" + timeText + "_" + sourceKey;
    }

    /**
     * 【步骤 9 子方法】从原文件名中提取安全的基础文件名
     * 
     * 处理：
     * - 去掉扩展名
     * - 替换非法字符（/ \）
     * - 如果为空，使用 "image"
     * 
     * @param sourceName 原始文件名
     * @return 安全的基础文件名
     */
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

    /**
     * 【步骤 9 子方法】从原文件名中提取扩展名
     * 
     * @param sourceName 原始文件名
     * @param fallback   默认扩展名（如 "jpg"）
     * @return 扩展名（小写）
     */
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

    /**
     * 【步骤 9 子方法】根据原图来源生成短标识，避免同名图片冲突
     * 
     * 用原图的 URI、大小、修改时间拼接后取哈希值，
     * 这样即使两个文件名相同，只要来源不同，哈希值也会不同。
     * 
     * @param item 原图信息
     * @return 8 位十六进制哈希字符串
     */
    private String buildShortSourceKey(MediaItemInfo item) {
        String rawKey = item.getUri() + "|" + item.getSizeBytes() + "|" + item.getModifiedTimeMs();
        return String.format(Locale.US, "%08x", rawKey.hashCode());
    }

    /**
     * 【步骤 9 子方法】根据扩展名推断图片 MIME 类型
     * 
     * @param extension 文件扩展名（如 "jpg", "png"）
     * @return MIME 类型字符串
     */
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

    // ============================================================
    // 五、内部数据类（仅在本文件内使用）
    // ============================================================

    /**
     * 对照文件名封装
     * 用于在保存原图副本和压缩图时传递成对的文件名
     */
    private static class ComparisonFileNames {
        private final String originalName;      // 原图副本文件名
        private final String compressedName;    // 压缩图文件名
        private final String originalMimeType;  // 原图的 MIME 类型

        private ComparisonFileNames(String originalName, String compressedName, String originalMimeType) {
            this.originalName = originalName;
            this.compressedName = compressedName;
            this.originalMimeType = originalMimeType;
        }
    }

    /**
     * 压缩计划封装
     * 在 buildCompressionPlan 中生成，传递给后续步骤
     */
    private static class CompressionPlan {
        private final int maxLongSide;   // 目标长边（像素）
        private int jpegQuality;         // JPEG 质量（0~100），允许后续调整

        private CompressionPlan(int maxLongSide, int jpegQuality) {
            this.maxLongSide = Math.max(maxLongSide, 1);
            this.jpegQuality = jpegQuality;
        }
    }

    /**
     * 图片细节等级枚举
     * 用于 analyzeDetailLevel 的返回值
     */
    private enum ImageDetailLevel {
        LOW,      // 低细节（纯色截图、简单图形）
        NORMAL,   // 普通细节
        HIGH      // 高细节（风景、人像、纹理丰富）
    }

    // ============================================================
    // 六、工具方法
    // ============================================================

    /**
     * 统一分发压缩进度
     * 
     * 每个关键步骤前调用此方法：
     * - 如果回调返回 true，继续执行
     * - 如果回调返回 false，说明用户取消了，需要中断
     * 
     * @param callback 进度回调（可为 null）
     * @param progress 当前进度（0~1）
     * @return true：继续；false：取消
     */
    private boolean publishProgress(@Nullable ProgressCallback callback, float progress) {
        return callback == null || callback.onProgress(progress);
    }
}
