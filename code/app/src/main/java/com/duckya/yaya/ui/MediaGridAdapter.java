package com.duckya.yaya.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.duckya.yaya.R;
import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.util.FormatUtils;
import com.duckya.yaya.util.ThumbnailLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MediaGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public static final int VIEW_TYPE_HEADER = 0;
    public static final int VIEW_TYPE_MEDIA = 1;

    public interface Listener {
        void onMediaClick(MediaItemInfo item);

        void onMediaLongClick(MediaItemInfo item, View anchor);

        void onPrimaryActionClick();

        void onFilterClick();

        void onSortClick(View anchor);

        void onSelectModeClick();

        void onSelectionDoneClick();

        void onSelectionCompressClick();

        void onSelectionDeleteClick();
    }

    private final List<MediaItemInfo> items = new ArrayList<>();
    private final Listener listener;
    private HeaderState headerState = HeaderState.initial();
    private boolean selectionMode;
    private Set<String> selectedUris;

    public MediaGridAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<MediaItemInfo> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void setHeaderState(HeaderState state) {
        headerState = state;
        notifyItemChanged(0);
    }

    // 接收 Fragment 中维护的选择状态，刷新网格里的勾选标记。
    public void setSelectionState(boolean selectionMode, Set<String> selectedUris) {
        this.selectionMode = selectionMode;
        this.selectedUris = selectedUris;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? VIEW_TYPE_HEADER : VIEW_TYPE_MEDIA;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_scan_header, parent, false);
            return new HeaderViewHolder(view);
        }
        View view = inflater.inflate(R.layout.item_media_grid, parent, false);
        return new MediaViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(headerState, listener);
            return;
        }
        MediaViewHolder mediaHolder = (MediaViewHolder) holder;
        MediaItemInfo item = items.get(position - 1);
        boolean selected = selectedUris != null && selectedUris.contains(item.getUri().toString());
        mediaHolder.sizeBadge.setText(FormatUtils.formatSize(item.getSizeBytes()));
        mediaHolder.kindBadge.setText(item.getKind() == MediaKind.VIDEO
                ? formatDuration(item.getDurationMs())
                : formatMegapixels(item.getWidth(), item.getHeight()));
        mediaHolder.selectionBadge.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        mediaHolder.selectionBadge.setText(selected ? "✓" : "○");
        mediaHolder.itemView.setAlpha(!selectionMode || selected ? 1.0f : 0.72f);
        ThumbnailLoader.loadInto(mediaHolder.thumbnail.getContext(), item, mediaHolder.thumbnail);
        mediaHolder.itemView.setOnClickListener(v -> listener.onMediaClick(item));
        mediaHolder.itemView.setOnLongClickListener(v -> {
            listener.onMediaLongClick(item, v);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size() + 1;
    }

    private String formatMegapixels(int width, int height) {
        if (width <= 0 || height <= 0) {
            return "图片";
        }
        double mp = (width * (double) height) / 1_000_000.0;
        return String.format(Locale.getDefault(), "%.0fMP", Math.max(mp, 1.0));
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(durationMs / 1000L, 0L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView kindBadge;
        final TextView sizeBadge;
        final TextView selectionBadge;

        MediaViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.media_thumbnail);
            kindBadge = itemView.findViewById(R.id.media_kind_badge);
            sizeBadge = itemView.findViewById(R.id.media_size_badge);
            selectionBadge = itemView.findViewById(R.id.media_selection_badge);
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final View titleBar;
        final View selectionBar;
        final TextView statusText;
        final TextView detailText;
        final TextView doneText;
        final TextView progressPercentText;
        final TextView mediaCountText;
        final ProgressBar progressBar;
        final Button primaryButton;
        final Button filterButton;
        final Button sortButton;
        final Button selectButton;
        final Button selectionDeleteButton;
        final Button selectionCompressButton;
        final Button selectionDoneButton;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            titleBar = itemView.findViewById(R.id.scan_title_bar);
            selectionBar = itemView.findViewById(R.id.scan_selection_bar);
            statusText = itemView.findViewById(R.id.scan_status_text);
            detailText = itemView.findViewById(R.id.scan_detail_text);
            doneText = itemView.findViewById(R.id.scan_done_text);
            progressPercentText = itemView.findViewById(R.id.scan_progress_percent_text);
            mediaCountText = itemView.findViewById(R.id.media_count_text);
            progressBar = itemView.findViewById(R.id.scan_progress_bar);
            primaryButton = itemView.findViewById(R.id.scan_primary_button);
            filterButton = itemView.findViewById(R.id.filter_button);
            sortButton = itemView.findViewById(R.id.sort_button);
            selectButton = itemView.findViewById(R.id.scan_select_button);
            selectionDeleteButton = itemView.findViewById(R.id.scan_selection_delete_button);
            selectionCompressButton = itemView.findViewById(R.id.scan_selection_compress_button);
            selectionDoneButton = itemView.findViewById(R.id.scan_selection_done_button);
        }

        void bind(HeaderState state, Listener listener) {
            titleBar.setVisibility(state.selectionMode ? View.GONE : View.VISIBLE);
            selectionBar.setVisibility(state.selectionMode ? View.VISIBLE : View.GONE);
            statusText.setText(state.statusText);
            detailText.setText(state.detailText);
            doneText.setVisibility(state.doneVisible ? View.VISIBLE : View.INVISIBLE);
            progressBar.setProgress(state.progress);
            progressPercentText.setText(state.progress + "%");
            primaryButton.setText(state.primaryButtonText);
            primaryButton.setEnabled(state.primaryEnabled);
            filterButton.setEnabled(state.controlsEnabled);
            sortButton.setEnabled(state.controlsEnabled);
            filterButton.setAlpha(state.filtered ? 1.0f : 0.85f);
            sortButton.setAlpha(state.controlsEnabled ? 1.0f : 0.65f);
            sortButton.setText(state.sortButtonText);
            mediaCountText.setText(state.mediaCountText);
            primaryButton.setOnClickListener(v -> listener.onPrimaryActionClick());
            filterButton.setOnClickListener(v -> listener.onFilterClick());
            sortButton.setOnClickListener(v -> listener.onSortClick(sortButton));
            selectButton.setEnabled(state.controlsEnabled);
            selectButton.setOnClickListener(v -> listener.onSelectModeClick());
            selectionDeleteButton.setEnabled(state.selectedCount > 0);
            selectionCompressButton.setEnabled(state.selectedCount > 0);
            selectionDoneButton.setOnClickListener(v -> listener.onSelectionDoneClick());
            selectionDeleteButton.setOnClickListener(v -> listener.onSelectionDeleteClick());
            selectionCompressButton.setOnClickListener(v -> listener.onSelectionCompressClick());
        }
    }

    public static class HeaderState {
        final String statusText;
        final String detailText;
        final String primaryButtonText;
        final String sortButtonText;
        final String mediaCountText;
        final int progress;
        final boolean doneVisible;
        final boolean primaryEnabled;
        final boolean controlsEnabled;
        final boolean filtered;
        final boolean selectionMode;
        final int selectedCount;

        public HeaderState(
                String statusText,
                String detailText,
                String primaryButtonText,
                String sortButtonText,
                String mediaCountText,
                int progress,
                boolean doneVisible,
                boolean primaryEnabled,
                boolean controlsEnabled,
                boolean filtered,
                boolean selectionMode,
                int selectedCount
        ) {
            this.statusText = statusText;
            this.detailText = detailText;
            this.primaryButtonText = primaryButtonText;
            this.sortButtonText = sortButtonText;
            this.mediaCountText = mediaCountText;
            this.progress = progress;
            this.doneVisible = doneVisible;
            this.primaryEnabled = primaryEnabled;
            this.controlsEnabled = controlsEnabled;
            this.filtered = filtered;
            this.selectionMode = selectionMode;
            this.selectedCount = selectedCount;
        }

        static HeaderState initial() {
            return new HeaderState("", "", "", "", "", 0, false, false, false, false, false, 0);
        }
    }
}
