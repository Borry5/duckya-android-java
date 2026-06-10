package com.duckya.yaya.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MediaGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public static final int VIEW_TYPE_HEADER = 0;
    public static final int VIEW_TYPE_MEDIA = 1;
    private static final String PAYLOAD_HEADER = "payload_header";
    private static final String PAYLOAD_SELECTION = "payload_selection";

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
    private final Map<String, Integer> uriPositions = new HashMap<>();
    private final Listener listener;
    private HeaderState headerState = HeaderState.initial();
    private RecyclerView attachedRecyclerView;
    private boolean selectionMode;
    private Set<String> selectedUris;

    public MediaGridAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submitList(List<MediaItemInfo> newItems) {
        items.clear();
        items.addAll(newItems);
        rebuildUriPositions();
        notifyDataSetChanged();
    }

    public void setHeaderState(HeaderState state) {
        headerState = state;
        if (!bindVisibleHeader()) {
            notifyItemChanged(0, PAYLOAD_HEADER);
        }
    }

    // 选择模式变化时只刷新当前屏幕可见卡片，避免大量媒体列表被整段通知拖慢。
    public void setSelectionMode(boolean selectionMode, Set<String> selectedUris) {
        this.selectionMode = selectionMode;
        this.selectedUris = selectedUris;
        refreshVisibleSelectionItems();
    }

    public void notifySelectionChanged(String uriText) {
        int adapterPosition = adapterPositionForUri(uriText);
        if (adapterPosition < 1) {
            return;
        }
        if (!bindVisibleSelectionItem(adapterPosition)) {
            notifyItemChanged(adapterPosition, PAYLOAD_SELECTION);
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (attachedRecyclerView == recyclerView) {
            attachedRecyclerView = null;
        }
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? VIEW_TYPE_HEADER : VIEW_TYPE_MEDIA;
    }

    @Override
    public long getItemId(int position) {
        if (position == 0) {
            return Long.MIN_VALUE;
        }
        return items.get(position - 1).getUri().toString().hashCode() & 0xffffffffL;
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
        mediaHolder.sizeBadge.setText(FormatUtils.formatSize(item.getSizeBytes()));
        mediaHolder.kindBadge.setText(item.getKind() == MediaKind.VIDEO
                ? formatDuration(item.getDurationMs())
                : formatMegapixels(item.getWidth(), item.getHeight()));
        updateSelectionUi(mediaHolder, item);
        ThumbnailLoader.loadInto(mediaHolder.thumbnail.getContext(), item, mediaHolder.thumbnail);
        mediaHolder.itemView.setOnClickListener(v -> listener.onMediaClick(item));
        mediaHolder.itemView.setOnLongClickListener(v -> {
            listener.onMediaLongClick(item, v);
            return true;
        });
    }

    @Override
    public void onBindViewHolder(
            @NonNull RecyclerView.ViewHolder holder,
            int position,
            @NonNull List<Object> payloads
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
            return;
        }
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(headerState, listener);
            return;
        }
        if (position > 0 && holder instanceof MediaViewHolder) {
            updateSelectionUi((MediaViewHolder) holder, items.get(position - 1));
        }
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

    private void updateSelectionUi(MediaViewHolder holder, MediaItemInfo item) {
        boolean selected = selectedUris != null && selectedUris.contains(item.getUri().toString());
        holder.selectionBadge.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.selectionBadge.setChecked(selected);
        holder.itemView.setAlpha(!selectionMode || selected ? 1.0f : 0.72f);
    }

    private int adapterPositionForUri(String uriText) {
        Integer position = uriPositions.get(uriText);
        return position == null ? RecyclerView.NO_POSITION : position;
    }

    private void rebuildUriPositions() {
        uriPositions.clear();
        for (int i = 0; i < items.size(); i++) {
            uriPositions.put(items.get(i).getUri().toString(), i + 1);
        }
    }

    private boolean bindVisibleHeader() {
        if (attachedRecyclerView == null) {
            return false;
        }
        RecyclerView.ViewHolder holder = attachedRecyclerView.findViewHolderForAdapterPosition(0);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(headerState, listener);
            return true;
        }
        return false;
    }

    private boolean bindVisibleSelectionItem(int adapterPosition) {
        if (attachedRecyclerView == null) {
            return false;
        }
        RecyclerView.ViewHolder holder = attachedRecyclerView.findViewHolderForAdapterPosition(adapterPosition);
        if (holder instanceof MediaViewHolder) {
            updateSelectionUi((MediaViewHolder) holder, items.get(adapterPosition - 1));
            return true;
        }
        return false;
    }

    private void refreshVisibleSelectionItems() {
        if (attachedRecyclerView == null) {
            return;
        }
        for (int i = 0; i < attachedRecyclerView.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = attachedRecyclerView.getChildViewHolder(attachedRecyclerView.getChildAt(i));
            int position = holder.getBindingAdapterPosition();
            if (position > 0 && position <= items.size() && holder instanceof MediaViewHolder) {
                updateSelectionUi((MediaViewHolder) holder, items.get(position - 1));
            }
        }
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView kindBadge;
        final TextView sizeBadge;
        final CheckBox selectionBadge;

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
            titleBar.setVisibility(state.selectionMode ? View.INVISIBLE : View.VISIBLE);
            selectionBar.setVisibility(state.selectionMode ? View.VISIBLE : View.INVISIBLE);
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
            selectButton.setOnClickListener(v -> {
                titleBar.setVisibility(View.INVISIBLE);
                selectionBar.setVisibility(View.VISIBLE);
                listener.onSelectModeClick();
            });
            selectionDeleteButton.setEnabled(state.selectedCount > 0);
            selectionCompressButton.setEnabled(state.selectedCount > 0);
            selectionDoneButton.setOnClickListener(v -> {
                titleBar.setVisibility(View.VISIBLE);
                selectionBar.setVisibility(View.INVISIBLE);
                listener.onSelectionDoneClick();
            });
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
