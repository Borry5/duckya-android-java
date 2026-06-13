package com.duckya.yaya.ui;

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.duckya.yaya.R;
import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.model.QueueTask;
import com.duckya.yaya.util.FormatUtils;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.InputStream;
import java.util.Locale;

// 已完成任务专用预览弹窗，可在原始资源和压缩结果之间切换查看。
public class CompletedTaskPreviewSheet extends BottomSheetDialogFragment {
    private static final String ARG_NAME = "name";
    private static final String ARG_ORIGINAL_URI = "original_uri";
    private static final String ARG_COMPRESSED_URI = "compressed_uri";
    private static final String ARG_ORIGINAL_SIZE = "original_size";
    private static final String ARG_COMPRESSED_SIZE = "compressed_size";
    private static final String ARG_KIND = "kind";
    private static final String ARG_WIDTH = "width";
    private static final String ARG_HEIGHT = "height";
    private static final String ARG_DURATION = "duration";

    private MediaItemInfo originalItem;
    private Uri compressedUri;
    private long compressedSizeBytes;
    private boolean showingCompressed;
    private ImageView previewImage;
    private VideoView previewVideo;
    private TextView titleText;
    private TextView infoText;
    private Button originalButton;
    private Button compressedButton;

    public static CompletedTaskPreviewSheet newInstance(QueueTask task) {
        CompletedTaskPreviewSheet sheet = new CompletedTaskPreviewSheet();
        MediaItemInfo item = task.getMedia();
        Bundle args = new Bundle();
        args.putString(ARG_NAME, item.getName());
        args.putString(ARG_ORIGINAL_URI, item.getUri().toString());
        args.putString(ARG_COMPRESSED_URI, task.getCompressedAssetUri() == null
                ? ""
                : task.getCompressedAssetUri().toString());
        args.putLong(ARG_ORIGINAL_SIZE, item.getSizeBytes());
        args.putLong(ARG_COMPRESSED_SIZE, task.getActualOutputBytes());
        args.putString(ARG_KIND, item.getKind().name());
        args.putInt(ARG_WIDTH, item.getWidth());
        args.putInt(ARG_HEIGHT, item.getHeight());
        args.putLong(ARG_DURATION, item.getDurationMs());
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.sheet_completed_task_preview, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        readArgs();
        titleText = view.findViewById(R.id.completed_preview_title);
        previewImage = view.findViewById(R.id.completed_preview_image);
        previewVideo = view.findViewById(R.id.completed_preview_video);
        infoText = view.findViewById(R.id.completed_preview_info);
        originalButton = view.findViewById(R.id.completed_preview_original_button);
        compressedButton = view.findViewById(R.id.completed_preview_compressed_button);
        Button closeButton = view.findViewById(R.id.completed_preview_close_button);

        boolean hasCompressed = compressedUri != null && compressedSizeBytes > 0L;
        showingCompressed = hasCompressed;
        view.findViewById(R.id.completed_preview_source_bar)
                .setVisibility(hasCompressed ? View.VISIBLE : View.GONE);
        originalButton.setOnClickListener(v -> {
            showingCompressed = false;
            renderPreview();
        });
        compressedButton.setOnClickListener(v -> {
            showingCompressed = true;
            renderPreview();
        });
        closeButton.setOnClickListener(v -> dismiss());
        renderPreview();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (previewVideo != null) {
            previewVideo.stopPlayback();
        }
    }

    private void readArgs() {
        Bundle args = requireArguments();
        originalItem = new MediaItemInfo(
                args.getString(ARG_NAME, ""),
                Uri.parse(args.getString(ARG_ORIGINAL_URI, "")),
                args.getLong(ARG_ORIGINAL_SIZE, 0L),
                0L,
                MediaKind.valueOf(args.getString(ARG_KIND, MediaKind.IMAGE.name())),
                args.getInt(ARG_WIDTH, 0),
                args.getInt(ARG_HEIGHT, 0),
                args.getLong(ARG_DURATION, 0L)
        );
        String compressedUriText = args.getString(ARG_COMPRESSED_URI, "");
        compressedUri = compressedUriText == null || compressedUriText.isEmpty() ? null : Uri.parse(compressedUriText);
        compressedSizeBytes = args.getLong(ARG_COMPRESSED_SIZE, 0L);
    }

    private void renderPreview() {
        Uri targetUri = showingCompressed && compressedUri != null ? compressedUri : originalItem.getUri();
        long sizeBytes = showingCompressed ? compressedSizeBytes : originalItem.getSizeBytes();
        titleText.setText(showingCompressed
                ? R.string.completed_preview_compressed_title
                : R.string.completed_preview_original_title);
        originalButton.setEnabled(showingCompressed);
        compressedButton.setEnabled(!showingCompressed);

        if (originalItem.getKind() == MediaKind.VIDEO) {
            renderVideo(targetUri);
            infoText.setText(buildVideoInfo(targetUri, sizeBytes));
        } else {
            renderImage(targetUri);
            infoText.setText(buildImageInfo(targetUri, sizeBytes));
        }
    }

    private void renderImage(Uri uri) {
        previewVideo.stopPlayback();
        previewVideo.setVisibility(View.GONE);
        previewImage.setVisibility(View.VISIBLE);
        previewImage.setImageURI(uri);
    }

    private void renderVideo(Uri uri) {
        previewImage.setVisibility(View.GONE);
        previewVideo.setVisibility(View.VISIBLE);
        MediaController controller = new MediaController(requireContext());
        controller.setAnchorView(previewVideo);
        previewVideo.setMediaController(controller);
        previewVideo.setVideoURI(uri);
        previewVideo.seekTo(1);
    }

    private String buildImageInfo(Uri uri, long sizeBytes) {
        ImageBounds bounds = showingCompressed ? readImageBounds(uri) : new ImageBounds(originalItem.getWidth(), originalItem.getHeight());
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.completed_preview_type)).append("：")
                .append(getString(R.string.preview_type_image)).append("\n");
        builder.append(getString(R.string.completed_preview_size)).append("：")
                .append(FormatUtils.formatSize(sizeBytes)).append("\n");
        builder.append(getString(R.string.completed_preview_resolution)).append("：")
                .append(formatResolution(bounds.width, bounds.height));
        return builder.toString();
    }

    private String buildVideoInfo(Uri uri, long sizeBytes) {
        VideoInfo info = readVideoInfo(uri);
        long durationMs = info.durationMs > 0L ? info.durationMs : originalItem.getDurationMs();
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.completed_preview_type)).append("：")
                .append(getString(R.string.preview_type_video)).append("\n");
        builder.append(getString(R.string.completed_preview_size)).append("：")
                .append(FormatUtils.formatSize(sizeBytes)).append("\n");
        builder.append(getString(R.string.completed_preview_resolution)).append("：")
                .append(formatResolution(info.width, info.height)).append("\n");
        builder.append(getString(R.string.completed_preview_duration)).append("：")
                .append(formatDuration(durationMs)).append("\n");
        builder.append(getString(R.string.completed_preview_bitrate)).append("：")
                .append(formatBitrate(sizeBytes, durationMs, info.bitrate));
        return builder.toString();
    }

    private ImageBounds readImageBounds(Uri uri) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri)) {
            if (inputStream != null) {
                BitmapFactory.decodeStream(inputStream, null, options);
            }
        } catch (Exception ignored) {
            // 读取压缩图尺寸失败时用占位符展示，预览本身仍可继续。
        }
        return new ImageBounds(Math.max(options.outWidth, 0), Math.max(options.outHeight, 0));
    }

    private VideoInfo readVideoInfo(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(requireContext(), uri);
            int width = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            long bitrate = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            return new VideoInfo(width, height, durationMs, bitrate);
        } catch (Exception ignored) {
            return new VideoInfo(originalItem.getWidth(), originalItem.getHeight(), originalItem.getDurationMs(), 0L);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // 少数设备 release 可能抛异常，忽略即可。
            }
        }
    }

    private String formatResolution(int width, int height) {
        if (width <= 0 || height <= 0) {
            return "--";
        }
        return width + " x " + height;
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(durationMs / 1000L, 0L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private String formatBitrate(long sizeBytes, long durationMs, long metadataBitrate) {
        if (metadataBitrate > 0L) {
            return String.format(Locale.getDefault(), "%.1f Mbps", metadataBitrate / 1_000_000.0);
        }
        if (durationMs <= 0L || sizeBytes <= 0L) {
            return "--";
        }
        double seconds = durationMs / 1000.0;
        double mbps = sizeBytes * 8.0 / seconds / 1_000_000.0;
        return String.format(Locale.getDefault(), "%.1f Mbps", mbps);
    }

    private int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static class ImageBounds {
        private final int width;
        private final int height;

        private ImageBounds(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static class VideoInfo {
        private final int width;
        private final int height;
        private final long durationMs;
        private final long bitrate;

        private VideoInfo(int width, int height, long durationMs, long bitrate) {
            this.width = width;
            this.height = height;
            this.durationMs = durationMs;
            this.bitrate = bitrate;
        }
    }
}
