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
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.VideoCodecOption;
import com.duckya.yaya.model.VideoCompressionSettings;
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

public class VideoCompressionWorker {
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

    public Result compress(
            Context context,
            MediaItemInfo item,
            VideoCompressionSettings settings,
            @Nullable ProgressCallback callback
    ) throws Exception {
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

    private Result exportOnceWithFallback(
            Context context,
            MediaItemInfo item,
            VideoCompressionSettings settings,
            File outputFile,
            @Nullable ProgressCallback callback
    ) throws Exception {
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

    private Result export(
            Context context,
            MediaItemInfo item,
            VideoCompressionSettings settings,
            VideoCodecOption codec,
            File outputFile,
            @Nullable ProgressCallback callback
    ) throws Exception {
        HandlerThread thread = new HandlerThread("DuckyaVideoTransformer");
        thread.start();
        CountDownLatch doneLatch = new CountDownLatch(1);
        Handler transformerHandler = new Handler(thread.getLooper());
        AtomicReference<Exception> failure = new AtomicReference<>();

        Transformer transformer = new Transformer.Builder(context)
                .setLooper(thread.getLooper())
                .setVideoMimeType(videoMimeType(codec))
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
            if (!settings.isKeepFrameRate()) {
                itemBuilder.setFrameRate(30);
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

    private File createOutputFile(Context context) throws IOException {
        File outputDir = new File(context.getFilesDir(), "video_outputs");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("无法创建视频输出目录");
        }
        return File.createTempFile("duckya_video_", ".mp4", outputDir);
    }

    private String videoMimeType(VideoCodecOption codec) {
        if (codec == VideoCodecOption.H265) {
            return MimeTypes.VIDEO_H265;
        }
        return MimeTypes.VIDEO_H264;
    }

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

    private Uri copyStreamToGallery(Context context, InputStream inputStream, String displayName, String mimeType)
            throws IOException {
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

    private String buildBaseName(String sourceName) {
        String baseName = sourceName == null ? "video" : sourceName.trim();
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }
        baseName = baseName.replace('/', '_').replace('\\', '_');
        return baseName.isEmpty() ? "video" : baseName;
    }

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

    private int targetHeight(VideoResolutionOption option) {
        switch (option) {
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
