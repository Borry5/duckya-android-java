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
import com.duckya.yaya.util.FormatUtils;
import com.duckya.yaya.util.ThumbnailLoader;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.Locale;

public class PreviewBottomSheet extends BottomSheetDialogFragment {
    private static final String ARG_NAME = "name";
    private static final String ARG_URI = "uri";
    private static final String ARG_SIZE = "size";
    private static final String ARG_MODIFIED = "modified";
    private static final String ARG_KIND = "kind";
    private static final String ARG_WIDTH = "width";
    private static final String ARG_HEIGHT = "height";
    private static final String ARG_DURATION = "duration";

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
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.sheet_preview, container, false);
    }

    @Override
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

        View.OnClickListener placeholderClick = v ->
                Toast.makeText(requireContext(), R.string.preview_stage_toast, Toast.LENGTH_SHORT).show();
        compressButton.setOnClickListener(placeholderClick);
        deleteButton.setOnClickListener(placeholderClick);
    }

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

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(durationMs / 1000L, 0L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private String formatBitrate(MediaItemInfo item) {
        if (item.getDurationMs() <= 0L) {
            return "--";
        }
        double seconds = item.getDurationMs() / 1000.0;
        double mbps = item.getSizeBytes() * 8.0 / seconds / 1_000_000.0;
        return String.format(Locale.getDefault(), "%.1f", mbps);
    }
}
