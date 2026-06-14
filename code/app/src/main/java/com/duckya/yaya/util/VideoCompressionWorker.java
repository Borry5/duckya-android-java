package com.duckya.yaya.util;

import android.content.Context;
import android.net.Uri;
import android.os.HandlerThread;

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
import com.duckya.yaya.model.VideoAudioMode;
import com.duckya.yaya.model.VideoCodecOption;
import com.duckya.yaya.model.VideoCompressionSettings;
import com.duckya.yaya.model.VideoResolutionOption;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class VideoCompressionWorker {
    public interface ProgressCallback {
        boolean onProgress(float progress);
    }

    public static class Result {
        private final long outputBytes;
        private final Uri outputUri;

        private Result(long outputBytes, Uri outputUri) {
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

    public Result compress(
            Context context,
            MediaItemInfo item,
            VideoCompressionSettings settings,
            @Nullable ProgressCallback callback
    ) throws Exception {
        File outputFile = createOutputFile(context);
        try {
            return exportOnceWithFallback(context, item, settings, outputFile, callback);
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
            EditedMediaItem.Builder itemBuilder = new EditedMediaItem.Builder(MediaItem.fromUri(item.getUri()))
                    .setRemoveAudio(settings.getAudioMode() == VideoAudioMode.MUTE);
            Effects effects = buildEffects(item, settings);
            if (effects != Effects.EMPTY) {
                itemBuilder.setEffects(effects);
            }
            if (!settings.isKeepFrameRate()) {
                itemBuilder.setFrameRate(30);
            }
            EditedMediaItem editedItem = itemBuilder.build();
            transformer.start(editedItem, outputFile.getAbsolutePath());
            ProgressHolder progressHolder = new ProgressHolder();
            while (!doneLatch.await(250L, TimeUnit.MILLISECONDS)) {
                if (callback != null) {
                    int progressState = transformer.getProgress(progressHolder);
                    if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        float progress = Math.max(0f, Math.min(progressHolder.progress / 100f, 0.98f));
                        if (!callback.onProgress(progress)) {
                            transformer.cancel();
                            throw new InterruptedException("video compression cancelled");
                        }
                    } else if (!callback.onProgress(0.08f)) {
                        transformer.cancel();
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
            return new Result(outputFile.length(), Uri.fromFile(outputFile));
        } finally {
            transformer.cancel();
            thread.quitSafely();
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
}
