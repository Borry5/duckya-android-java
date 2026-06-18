package com.duckya.yaya.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.duckya.yaya.R;
import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.model.QueueAction;
import com.duckya.yaya.queue.QueueManager;
import com.duckya.yaya.util.FormatUtils;
import com.duckya.yaya.util.ThumbnailLoader;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.Locale;

/**
 * 这个文件是浏览页单个媒体的预览底部弹窗，负责展示缩略图、基本信息和快捷操作。
 * 输入是被点击媒体的各项参数，以及用户点击压缩或删除按钮的交互。
 * 处理过程是从参数中恢复媒体对象，绑定预览内容，并把媒体加入任务队列。
 * 输出是底部预览弹窗界面，以及新增的压缩或回收任务。
 */
public class PreviewBottomSheet extends BottomSheetDialogFragment {
    private static final String ARG_NAME = "name";
    private static final String ARG_URI = "uri";
    private static final String ARG_SIZE = "size";
    private static final String ARG_MODIFIED = "modified";
    private static final String ARG_KIND = "kind";
    private static final String ARG_WIDTH = "width";
    private static final String ARG_HEIGHT = "height";
    private static final String ARG_DURATION = "duration";

    /**
     * 这个函数用于根据媒体对象创建一个预览弹窗实例。
     * 输入是单个媒体对象。
     * 输出是带有媒体参数的 PreviewBottomSheet 实例。
     */
    public static PreviewBottomSheet newInstance(MediaItemInfo item) {
        PreviewBottomSheet sheet = new PreviewBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_NAME, item.getName());
        args.putString(ARG_URI, item.getUri().toString());
        args.putLong(ARG_SIZE, item.getSizeBytes());
        args.putLong(ARG_MODIFIED, item.getModifiedTimeMs());
        args.putString(ARG_KIND, item.getKind().name());
        args.putInt(ARG_WIDTH, item.getWidth());
        args.putInt(ARG_HEIGHT, item.getHeight());
        args.putLong(ARG_DURATION, item.getDurationMs());
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    /**
     * 这个函数用于创建预览弹窗的根视图。
     * 输入是布局加载参数和可选状态。
     * 输出是 sheet_preview.xml 对应的 View。
     */
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.sheet_preview, container, false);
    }

    @Override
    /**
     * 这个函数用于初始化预览弹窗中的图片、信息和按钮点击事件。
     * 输入是已创建的根 View 和可选状态。
     * 输出是一个可预览、可加入任务队列的底部弹窗界面。
     */
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MediaItemInfo item = readItem();
        ImageView image = view.findViewById(R.id.preview_image);
        TextView nameText = view.findViewById(R.id.preview_name_text);
        TextView infoText = view.findViewById(R.id.preview_info_text);
        Button compressButton = view.findViewById(R.id.preview_compress_button);
        Button deleteButton = view.findViewById(R.id.preview_delete_button);

        nameText.setText(item.getName());
        infoText.setText(buildInfoText(item));
        ThumbnailLoader.loadInto(requireContext(), item, image);

        compressButton.setOnClickListener(v -> {
            QueueManager.getInstance().addTask(item, QueueAction.COMPRESS);
            Toast.makeText(requireContext(), R.string.preview_added_compress, Toast.LENGTH_SHORT).show();
            dismiss();
        });
        deleteButton.setOnClickListener(v -> {
            QueueManager.getInstance().addTask(item, QueueAction.DELETE);
            Toast.makeText(requireContext(), R.string.preview_added_delete, Toast.LENGTH_SHORT).show();
            dismiss();
        });
    }

    /**
     * 这个函数用于从 arguments 中恢复出一个完整的媒体对象。
     * 输入是弹窗参数里的名称、Uri、大小、类型等字段。
     * 输出是一个 MediaItemInfo 对象。
     */
    private MediaItemInfo readItem() {
        Bundle args = requireArguments();
        return new MediaItemInfo(
                args.getString(ARG_NAME, ""),
                Uri.parse(args.getString(ARG_URI, "")),
                args.getLong(ARG_SIZE, 0L),
                args.getLong(ARG_MODIFIED, 0L),
                MediaKind.valueOf(args.getString(ARG_KIND, MediaKind.IMAGE.name())),
                args.getInt(ARG_WIDTH, 0),
                args.getInt(ARG_HEIGHT, 0),
                args.getLong(ARG_DURATION, 0L)
        );
    }

    /**
     * 这个函数用于拼接预览弹窗中的媒体信息文本。
     * 输入是单个媒体对象。
     * 输出是包含类型、大小、分辨率、时长和码率的说明文字。
     */
    private String buildInfoText(MediaItemInfo item) {
        String type = item.getKind() == MediaKind.VIDEO
                ? getString(R.string.preview_type_video)
                : getString(R.string.preview_type_image);
        StringBuilder builder = new StringBuilder();
        builder.append(type).append("\n");
        builder.append(FormatUtils.formatSize(item.getSizeBytes())).append("\n");
        if (item.getWidth() > 0 && item.getHeight() > 0) {
            builder.append(getString(R.string.preview_resolution, item.getWidth(), item.getHeight())).append("\n");
        }
        if (item.getKind() == MediaKind.VIDEO) {
            builder.append(getString(R.string.preview_duration, formatDuration(item.getDurationMs()))).append("\n");
            builder.append(getString(R.string.preview_bitrate, formatBitrate(item)));
        }
        return builder.toString().trim();
    }

    /**
     * 这个函数用于把视频时长格式化成分钟秒数字符串。
     * 输入是毫秒单位的视频时长。
     * 输出是类似“1:35”的文本。
     */
    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(durationMs / 1000L, 0L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    /**
     * 这个函数用于估算并格式化视频平均码率。
     * 输入是视频媒体对象。
     * 输出是类似“6.8”的 Mbps 数值字符串。
     */
    private String formatBitrate(MediaItemInfo item) {
        if (item.getDurationMs() <= 0L) {
            return "--";
        }
        double seconds = item.getDurationMs() / 1000.0;
        double mbps = item.getSizeBytes() * 8.0 / seconds / 1_000_000.0;
        return String.format(Locale.getDefault(), "%.1f", mbps);
    }
}
