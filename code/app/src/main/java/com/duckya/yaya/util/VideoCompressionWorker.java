package com.duckya.yaya.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.VideoCodecOption;
import com.duckya.yaya.model.VideoCompressionSettings;
import com.duckya.yaya.model.VideoFrameRateOption;
import com.duckya.yaya.model.VideoResolutionOption;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// 文件说明：
// 1. 这个文件用于执行单个视频压缩任务。
// 2. 输入是系统相册中的视频 Uri、视频基础信息、视频压缩参数，以及进度回调。
// 3. 处理是调用 Media3 Transformer 执行视频转码，根据设置处理分辨率、码率、帧率和编码回退，再把原视频副本和压缩视频写回系统相册。
// 4. 输出是压缩结果对象，其中包含压缩后文件大小、原视频副本 Uri、压缩视频 Uri。
public class VideoCompressionWorker {
    private static final long ONE_GB_BYTES = 1024L * 1024L * 1024L;

    public interface ProgressCallback {
        boolean onProgress(float progress);
    }

    public static class Result {
        private final long outputBytes;
        private final Uri originalUri;
        private final Uri outputUri;

        private Result(long outputBytes, Uri originalUri, Uri outputUri) {
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
    // 1. 这个函数用于执行视频压缩完整主流程。
    // 2. 输入是 Context、媒体项信息、视频压缩设置、可选的进度回调。
    // 3. 处理是先把视频转码到临时文件，再把原视频副本和压缩视频写入“压缩对照”相册。
    // 4. 输出是 Result，里面包含压缩后大小、原视频副本 Uri、压缩视频 Uri。
    public Result compress(
            Context context,
            MediaItemInfo item,
            VideoCompressionSettings settings,
            @Nullable ProgressCallback callback
    ) throws Exception {
        // 视频压缩主流程：先调用 Media3 转码到临时文件，再把原视频副本和压缩视频写入“压缩对照”相册。
        File outputFile = createOutputFile(context);
        try {
            Result fileResult = exportOnceWithFallback(context, item, settings, outputFile, callback);
            return saveComparisonVideos(context, item, outputFile, fileResult.getOutputBytes());
        } catch (Exception e) {
            if (outputFile.exists()) {
                outputFile.delete();
            }
            throw e;
        }
    }

    // 函数说明：
    // 1. 这个函数用于执行“先 H.265、失败再回退 H.264”的导出流程。
    // 2. 输入是 Context、媒体项、视频设置、临时输出文件、进度回调。
    // 3. 输出是导出结果 Result。
    private Result exportOnceWithFallback(
            Context context,
            MediaItemInfo item,
            VideoCompressionSettings settings,
            File outputFile,
            @Nullable ProgressCallback callback
    ) throws Exception {
        // 默认先尝试 H.265，设备不支持时再自动回退 H.264，兼顾压缩率和兼容性。
        VideoCodecOption firstCodec = settings.getCodecOption();
        if (firstCodec == VideoCodecOption.AUTO) {
            firstCodec = VideoCodecOption.H265;
        }
        try {
            return export(context, item, settings, firstCodec, outputFile, callback);
        } catch (Exception e) {
            if (firstCodec == VideoCodecOption.H265 && settings.isFallbackToH264()) {
                if (outputFile.exists()) {
                    outputFile.delete();
                }
                // 部分设备 HEVC 编码不可用，允许时自动回退到 H.264。
                return export(context, item, settings, VideoCodecOption.H264, outputFile, callback);
            }
            throw e;
        }
    }

    // 函数说明：
    // 1. 这个函数用于真正调用 Media3 Transformer 执行一次视频转码。
    // 2. 输入是 Context、媒体项、视频设置、目标编码格式、临时输出文件、进度回调。
    // 3. 输出是导出结果 Result。
    private Result export(
            Context context,
            MediaItemInfo item,
            VideoCompressionSettings settings,
            VideoCodecOption codec,
            File outputFile,
            @Nullable ProgressCallback callback
    ) throws Exception {
        // 真正的视频“压缩引擎”是 AndroidX Media3 Transformer，我们负责参数组织、进度回调和失败兜底。
        HandlerThread thread = new HandlerThread("DuckyaVideoTransformer");
        thread.start();
        CountDownLatch doneLatch = new CountDownLatch(1);
        Handler transformerHandler = new Handler(thread.getLooper());
        AtomicReference<Exception> failure = new AtomicReference<>();

        Transformer transformer = new Transformer.Builder(context)
                .setLooper(thread.getLooper())
                .setVideoMimeType(videoMimeType(codec))
                .setEncoderFactory(new DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder()
                                .setBitrate(resolveTargetBitrateBps(item, settings))
                                .build())
                        .setEnableFallback(true)
                        .build())
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(androidx.media3.transformer.Composition composition, ExportResult exportResult) {
                        doneLatch.countDown();
                    }

                    @Override
                    public void onError(
                            androidx.media3.transformer.Composition composition,
                            ExportResult exportResult,
                            ExportException exportException
                    ) {
                        failure.set(exportException);
                        doneLatch.countDown();
                    }
                })
                .build();

        try {
            // 视频压缩只处理画面参数，音频轨道保持原样交给 Media3 透传/复用。
            EditedMediaItem.Builder itemBuilder = new EditedMediaItem.Builder(MediaItem.fromUri(item.getUri()))
                    .setRemoveAudio(false);
            Effects effects = buildEffects(item, settings);
            if (effects != Effects.EMPTY) {
                itemBuilder.setEffects(effects);
            }
            if (settings.getFrameRateOption() == VideoFrameRateOption.FPS60) {
                itemBuilder.setFrameRate(60);
            } else if (settings.getFrameRateOption() == VideoFrameRateOption.FPS30) {
                itemBuilder.setFrameRate(30);
            } else if (settings.getFrameRateOption() == VideoFrameRateOption.FPS24) {
                itemBuilder.setFrameRate(24);
            }
            EditedMediaItem editedItem = itemBuilder.build();
            postToTransformerThread(transformerHandler, () ->
                    transformer.start(editedItem, outputFile.getAbsolutePath()));
            ProgressHolder progressHolder = new ProgressHolder();
            while (!doneLatch.await(250L, TimeUnit.MILLISECONDS)) {
                if (callback != null) {
                    AtomicReference<Integer> stateRef = new AtomicReference<>(Transformer.PROGRESS_STATE_UNAVAILABLE);
                    postToTransformerThread(transformerHandler, () ->
                            stateRef.set(transformer.getProgress(progressHolder)));
                    int progressState = stateRef.get();
                    if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        float progress = Math.max(0f, Math.min(progressHolder.progress / 100f, 0.98f));
                        if (!callback.onProgress(progress)) {
                            postToTransformerThread(transformerHandler, transformer::cancel);
                            throw new InterruptedException("video compression cancelled");
                        }
                    } else if (!callback.onProgress(0.08f)) {
                        postToTransformerThread(transformerHandler, transformer::cancel);
                        throw new InterruptedException("video compression cancelled");
                    }
                }
            }
            Exception exportFailure = failure.get();
            if (exportFailure != null) {
                throw exportFailure;
            }
            if (callback != null && !callback.onProgress(1f)) {
                throw new InterruptedException("video compression cancelled");
            }
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                throw new IOException("视频转码未生成有效文件");
            }
            return new Result(outputFile.length(), null, Uri.fromFile(outputFile));
        } finally {
            try {
                postToTransformerThread(transformerHandler, transformer::cancel);
            } catch (Exception ignored) {
                // 转码已结束或线程正在关闭时，取消失败不影响清理。
            }
            thread.quitSafely();
        }
    }

    // 函数说明：
    // 1. 这个函数用于把对 Transformer 的调用切换到专属线程执行。
    // 2. 输入是 Handler 和一个可抛异常的任务。
    // 3. 输出为空；如果内部执行失败则抛出异常。
    private void postToTransformerThread(Handler handler, ThrowingRunnable runnable) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        handler.post(() -> {
            try {
                runnable.run();
            } catch (Exception e) {
                failure.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await();
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    // 函数说明：
    // 1. 这个函数用于创建视频压缩临时输出文件。
    // 2. 输入是 Context。
    // 3. 输出是一个可写入的临时 mp4 文件。
    private File createOutputFile(Context context) throws IOException {
        File outputDir = new File(context.getFilesDir(), "video_outputs");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("无法创建视频输出目录");
        }
        return File.createTempFile("duckya_video_", ".mp4", outputDir);
    }

    // 函数说明：
    // 1. 这个函数用于把编码选项转换成 Media3 需要的 MIME 类型。
    // 2. 输入是视频编码选项。
    // 3. 输出是视频 MIME 类型字符串。
    private String videoMimeType(VideoCodecOption codec) {
        if (codec == VideoCodecOption.H265) {
            return MimeTypes.VIDEO_H265;
        }
        return MimeTypes.VIDEO_H264;
    }

    // 函数说明：
    // 1. 这个函数用于把原视频副本和压缩视频保存到“压缩对照”相册。
    // 2. 输入是 Context、媒体项、压缩后的临时文件、压缩输出大小。
    // 3. 输出是最终保存后的 Result。
    private Result saveComparisonVideos(Context context, MediaItemInfo item, File compressedFile, long outputBytes)
            throws IOException {
        ComparisonFileNames names = buildComparisonFileNames(item);
        Uri originalUri;
        try (InputStream inputStream = context.getContentResolver().openInputStream(item.getUri())) {
            if (inputStream == null) {
                throw new IOException("无法读取原视频副本");
            }
            originalUri = copyStreamToGallery(context, inputStream, names.originalName, names.originalMimeType);
        }
        Uri compressedUri;
        try (InputStream inputStream = new FileInputStream(compressedFile)) {
            compressedUri = copyStreamToGallery(context, inputStream, names.compressedName, "video/mp4");
        }
        if (compressedFile.exists()) {
            compressedFile.delete();
        }
        return new Result(outputBytes, originalUri, compressedUri);
    }

    // 函数说明：
    // 1. 这个函数用于通过 MediaStore 把视频流写入系统相册。
    // 2. 输入是 Context、输入流、显示文件名、MIME 类型。
    // 3. 输出是系统相册中新建视频条目的 Uri。
    private Uri copyStreamToGallery(Context context, InputStream inputStream, String displayName, String mimeType)
            throws IOException {
        // 转码完成后仍然通过 MediaStore 落到系统相册，保证用户能在相册 App 中直接看到压缩结果。
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, mimeType);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/压缩对照");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }
        Uri outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) {
            throw new IOException("无法创建视频相册输出项");
        }
        try {
            try (OutputStream outputStream = resolver.openOutputStream(outputUri, "w")) {
                if (outputStream == null) {
                    throw new IOException("无法写入系统相册视频");
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                outputStream.flush();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues doneValues = new ContentValues();
                doneValues.put(MediaStore.Video.Media.IS_PENDING, 0);
                resolver.update(outputUri, doneValues, null, null);
            }
            return outputUri;
        } catch (IOException e) {
            resolver.delete(outputUri, null, null);
            throw e;
        }
    }

    // 函数说明：
    // 1. 这个函数用于生成原视频副本和压缩视频的成对文件名。
    // 2. 输入是媒体项信息。
    // 3. 输出是 ComparisonFileNames。
    private ComparisonFileNames buildComparisonFileNames(MediaItemInfo item) {
        String extension = extractExtension(item.getName(), "mp4");
        String baseName = buildBaseName(item.getName());
        String timeText = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String shortKey = String.format(Locale.US, "%08x", item.getUri().toString().hashCode());
        String batchBaseName = baseName + "_" + timeText + "_" + shortKey;
        return new ComparisonFileNames(
                batchBaseName + "_原始版本." + extension,
                batchBaseName + "_压缩版本.mp4",
                inferVideoMimeType(extension)
        );
    }

    // 函数说明：
    // 1. 这个函数用于从原视频名中提取安全基础名。
    // 2. 输入是原始文件名。
    // 3. 输出是清理后的基础名字符串。
    private String buildBaseName(String sourceName) {
        String baseName = sourceName == null ? "video" : sourceName.trim();
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }
        baseName = baseName.replace('/', '_').replace('\\', '_');
        return baseName.isEmpty() ? "video" : baseName;
    }

    // 函数说明：
    // 1. 这个函数用于提取原视频扩展名。
    // 2. 输入是原始文件名和默认扩展名。
    // 3. 输出是扩展名字符串。
    private String extractExtension(String sourceName, String fallback) {
        if (sourceName == null) {
            return fallback;
        }
        int dotIndex = sourceName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == sourceName.length() - 1) {
            return fallback;
        }
        String extension = sourceName.substring(dotIndex + 1).toLowerCase(Locale.US);
        return extension.isEmpty() ? fallback : extension;
    }

    // 函数说明：
    // 1. 这个函数用于根据扩展名推断视频 MIME 类型。
    // 2. 输入是扩展名。
    // 3. 输出是 MIME 类型字符串。
    private String inferVideoMimeType(String extension) {
        if ("mov".equals(extension) || "qt".equals(extension)) {
            return "video/quicktime";
        }
        if ("3gp".equals(extension)) {
            return "video/3gpp";
        }
        if ("mkv".equals(extension)) {
            return "video/x-matroska";
        }
        return "video/mp4";
    }

    // 函数说明：
    // 1. 这个函数用于根据分辨率设置生成视频缩放特效。
    // 2. 输入是媒体项信息和视频压缩设置。
    // 3. 输出是 Effects；如果不需要缩放则返回空效果。
    private Effects buildEffects(MediaItemInfo item, VideoCompressionSettings settings) {
        int targetHeight = targetHeight(settings.getResolutionOption());
        if (targetHeight <= 0 || item.getHeight() <= 0 || item.getHeight() <= targetHeight) {
            return Effects.EMPTY;
        }
        // Media3 Presentation 会按视频宽高比例生成目标尺寸，避免手动拉伸画面。
        return new Effects(
                Collections.emptyList(),
                Collections.singletonList(Presentation.createForHeight(targetHeight))
        );
    }

    // 函数说明：
    // 1. 这个函数用于把分辨率选项映射成目标高度。
    // 2. 输入是分辨率枚举。
    // 3. 输出是目标高度整数值。
    private int targetHeight(VideoResolutionOption option) {
        switch (option) {
            case P2160:
                return 2160;
            case P1440:
                return 1440;
            case P1080:
                return 1080;
            case P720:
                return 720;
            case P480:
                return 480;
            case ORIGINAL:
            default:
                return 0;
        }
    }

    // 函数说明：
    // 1. 这个函数用于计算视频转码目标码率。
    // 2. 输入是媒体项信息和视频压缩设置。
    // 3. 输出是最终给编码器使用的 bps 码率值。
    private int resolveTargetBitrateBps(MediaItemInfo item, VideoCompressionSettings settings) {
        int targetBps = Math.round(settings.getTargetBitrateMbps() * 1_000_000f);
        if (settings.isLimitToOneGb() && item.getSizeBytes() > ONE_GB_BYTES && item.getDurationMs() > 0L) {
            // “不超过 1GB”主要服务微信发送场景，预留一小段码率给原音频轨道。
            double seconds = item.getDurationMs() / 1000.0;
            double totalBps = (1024.0 * 1024.0 * 1024.0 * 8.0) / seconds;
            targetBps = (int) Math.max(600_000, totalBps - 128_000);
        }
        if (item.getDurationMs() > 0L && item.getSizeBytes() > 0L) {
            double seconds = item.getDurationMs() / 1000.0;
            int sourceBps = (int) Math.max(1, item.getSizeBytes() * 8.0 / seconds);
            targetBps = Math.min(targetBps, sourceBps);
        }
        return Math.max(600_000, targetBps);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
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
}
