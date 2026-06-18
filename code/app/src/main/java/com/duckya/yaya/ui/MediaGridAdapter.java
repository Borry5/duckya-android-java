package com.duckya.yaya.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.duckya.yaya.R;
import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.util.FormatUtils;
import com.duckya.yaya.util.ThumbnailLoader;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 这个文件是“浏览本地”页面网格列表的适配器，负责把媒体数据和头部状态显示到 RecyclerView。
 * 输入是媒体列表、头部状态、角标模式、多选状态，以及用户对卡片的点击操作。
 * 处理过程是创建头部和媒体卡片视图，绑定缩略图、大小、角标、多选框和交互事件。
 * 输出是浏览页的媒体网格界面，以及和用户交互联动的列表项显示状态。
 */
public class MediaGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public static final int VIEW_TYPE_HEADER = 0;
    public static final int VIEW_TYPE_MEDIA = 1;
    private static final String PAYLOAD_HEADER = "payload_header";
    private static final String PAYLOAD_SELECTION = "payload_selection";

    public interface Listener {
        void onMediaClick(MediaItemInfo item);

        void onMediaLongClick(MediaItemInfo item, View anchor);

        void onSelectionToggleClick(MediaItemInfo item);

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
    private CornerBadgeMode cornerBadgeMode = CornerBadgeMode.NONE;
    private boolean selectionMode;
    private Set<String> selectedUris;

    public enum CornerBadgeMode {
        NONE,
        DATE,
        VIDEO_BITRATE
    }

    /**
     * 这个构造函数用于创建媒体网格适配器。
     * 输入是列表事件监听器。
     * 输出是一个可绑定到 RecyclerView 的适配器对象。
     */
    public MediaGridAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    /**
     * 这个函数用于替换当前网格显示的全部媒体数据。
     * 输入是新的媒体列表。
     * 输出是刷新后的网格内容和 Uri 位置索引。
     */
    public void submitList(List<MediaItemInfo> newItems) {
        items.clear();
        items.addAll(newItems);
        rebuildUriPositions();
        notifyDataSetChanged();
    }

    /**
     * 这个函数用于更新浏览页头部显示状态。
     * 输入是新的头部状态对象。
     * 输出是刷新后的头部 UI。
     */
    public void setHeaderState(HeaderState state) {
        headerState = state;
        if (!bindVisibleHeader()) {
            notifyItemChanged(0, PAYLOAD_HEADER);
        }
    }

    // 选择模式变化时只刷新当前屏幕可见卡片，避免大量媒体列表被整段通知拖慢。
    /**
     * 这个函数用于切换列表是否进入多选模式。
     * 输入是是否开启多选和当前选中的 Uri 集合。
     * 输出是刷新后的可见卡片多选状态。
     */
    public void setSelectionMode(boolean selectionMode, Set<String> selectedUris) {
        this.selectionMode = selectionMode;
        this.selectedUris = selectedUris;
        refreshVisibleSelectionItems();
    }

    /**
     * 这个函数用于设置卡片右上角角标的显示模式。
     * 输入是角标模式枚举。
     * 输出是刷新后的全部媒体卡片角标显示。
     */
    public void setCornerBadgeMode(CornerBadgeMode cornerBadgeMode) {
        this.cornerBadgeMode = cornerBadgeMode;
        notifyDataSetChanged();
    }

    /**
     * 这个函数用于通知适配器某个媒体项的选中状态发生变化。
     * 输入是目标媒体的 Uri 字符串。
     * 输出是刷新后的单个卡片选择态。
     */
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
    /**
     * 这个函数用于在适配器挂到 RecyclerView 时保存列表引用。
     * 输入是当前绑定的 RecyclerView。
     * 输出是后续可直接刷新可见项的 RecyclerView 引用。
     */
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
    }

    @Override
    /**
     * 这个函数用于在适配器从 RecyclerView 分离时清理引用。
     * 输入是当前解绑的 RecyclerView。
     * 输出是释放掉已保存的 RecyclerView 引用。
     */
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (attachedRecyclerView == recyclerView) {
            attachedRecyclerView = null;
        }
    }

    @Override
    /**
     * 这个函数用于判断当前位置应该显示头部还是媒体卡片。
     * 输入是适配器位置。
     * 输出是对应的视图类型常量。
     */
    public int getItemViewType(int position) {
        return position == 0 ? VIEW_TYPE_HEADER : VIEW_TYPE_MEDIA;
    }

    @Override
    /**
     * 这个函数用于返回列表项的稳定 id。
     * 输入是适配器位置。
     * 输出是头部或媒体项对应的唯一 id。
     */
    public long getItemId(int position) {
        if (position == 0) {
            return Long.MIN_VALUE;
        }
        return items.get(position - 1).getUri().toString().hashCode() & 0xffffffffL;
    }

    @NonNull
    @Override
    /**
     * 这个函数用于创建头部或媒体卡片的 ViewHolder。
     * 输入是父容器和视图类型。
     * 输出是对应类型的 ViewHolder 对象。
     */
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
    /**
     * 这个函数用于完整绑定单个列表项的数据和交互。
     * 输入是 ViewHolder 和当前位置。
     * 输出是显示好的头部或媒体卡片。
     */
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
        bindCornerBadge(mediaHolder, item);
        updateSelectionUi(mediaHolder, item);
        ThumbnailLoader.loadInto(mediaHolder.thumbnail.getContext(), item, mediaHolder.thumbnail);
        mediaHolder.itemView.setOnClickListener(v -> listener.onMediaClick(item));
        mediaHolder.itemView.setOnLongClickListener(v -> {
            listener.onMediaLongClick(item, v);
            return true;
        });
        mediaHolder.selectionCheckBox.setOnClickListener(v -> listener.onSelectionToggleClick(item));
    }

    @Override
    /**
     * 这个函数用于按 payload 高效刷新列表项的局部状态。
     * 输入是 ViewHolder、位置和局部更新标记。
     * 输出是只更新头部或选择态等动态部分。
     */
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
            bindCornerBadge((MediaViewHolder) holder, items.get(position - 1));
            updateSelectionUi((MediaViewHolder) holder, items.get(position - 1));
        }
    }

    @Override
    /**
     * 这个函数用于返回适配器条目总数。
     * 输入是无。
     * 输出是头部加媒体项的总数量。
     */
    public int getItemCount() {
        return items.size() + 1;
    }

    /**
     * 这个函数用于把图片宽高格式化成兆像素文本。
     * 输入是图片宽度和高度。
     * 输出是类似“12MP”的字符串。
     */
    private String formatMegapixels(int width, int height) {
        if (width <= 0 || height <= 0) {
            return "图片";
        }
        double mp = (width * (double) height) / 1_000_000.0;
        return String.format(Locale.getDefault(), "%.0fMP", Math.max(mp, 1.0));
    }

    /**
     * 这个函数用于把视频时长格式化成分钟秒数字符串。
     * 输入是毫秒单位的视频时长。
     * 输出是类似“2:15”的文本。
     */
    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(durationMs / 1000L, 0L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    /**
     * 这个函数用于刷新单个媒体卡片的多选显示状态。
     * 输入是目标 ViewHolder 和媒体项。
     * 输出是更新后的勾选框、透明度和无障碍描述。
     */
    private void updateSelectionUi(MediaViewHolder holder, MediaItemInfo item) {
        boolean selected = selectedUris != null && selectedUris.contains(item.getUri().toString());
        holder.selectionCheckBox.setChecked(selected);
        holder.selectionCheckBox.setContentDescription(holder.itemView.getContext().getString(
                selected ? R.string.scan_checkbox_deselect : R.string.scan_checkbox_select,
                item.getName()
        ));
        if (selectionMode) {
            holder.cornerBadge.setVisibility(View.GONE);
        }
        animateCheckBoxVisibility(holder.selectionCheckBox, selectionMode);
        holder.itemView.setAlpha(!selectionMode || selected ? 1.0f : 0.72f);
        ViewCompat.setStateDescription(holder.itemView, holder.itemView.getContext().getString(
                selected ? R.string.scan_item_selected_state : R.string.scan_item_unselected_state
        ));
    }

    /**
     * 这个函数用于绑定媒体卡片右上角的角标内容。
     * 输入是目标 ViewHolder 和媒体项。
     * 输出是日期角标、码率角标或隐藏角标。
     */
    private void bindCornerBadge(MediaViewHolder holder, MediaItemInfo item) {
        if (selectionMode || cornerBadgeMode == CornerBadgeMode.NONE) {
            holder.cornerBadge.setVisibility(View.GONE);
            return;
        }
        if (cornerBadgeMode == CornerBadgeMode.VIDEO_BITRATE) {
            if (item.getKind() != MediaKind.VIDEO || item.getDurationMs() <= 0L) {
                holder.cornerBadge.setVisibility(View.GONE);
                return;
            }
            holder.cornerBadge.setText(formatVideoBitrate(item));
            holder.cornerBadge.setVisibility(View.VISIBLE);
            return;
        }
        holder.cornerBadge.setText(formatMonthDay(item.getModifiedTimeMs()));
        holder.cornerBadge.setVisibility(View.VISIBLE);
    }

    /**
     * 这个函数用于把时间戳格式化成月/日文本。
     * 输入是毫秒时间戳。
     * 输出是类似“6/18”的日期字符串。
     */
    private String formatMonthDay(long timeMs) {
        if (timeMs <= 0L) {
            return "--/--";
        }
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("M/d", Locale.getDefault());
        return format.format(new java.util.Date(timeMs));
    }

    /**
     * 这个函数用于估算并格式化视频平均码率。
     * 输入是视频媒体项。
     * 输出是类似“8.2 Mbps”的码率文本。
     */
    private String formatVideoBitrate(MediaItemInfo item) {
        double mbps = item.getSizeBytes() * 8.0 / (item.getDurationMs() / 1000.0) / 1_000_000.0;
        return String.format(Locale.getDefault(), "%.1f Mbps", mbps);
    }

    // 只在显隐状态真正变化时做轻量动画，避免频繁 bind 影响滚动与点击响应。
    private void animateCheckBoxVisibility(MaterialCheckBox checkBox, boolean visible) {
        boolean currentlyVisible = checkBox.getVisibility() == View.VISIBLE;
        if (currentlyVisible == visible) {
            if (visible) {
                checkBox.setAlpha(1.0f);
                checkBox.setScaleX(1.0f);
                checkBox.setScaleY(1.0f);
            }
            return;
        }
        checkBox.animate().cancel();
        if (visible) {
            checkBox.setVisibility(View.VISIBLE);
            checkBox.setAlpha(0.0f);
            checkBox.setScaleX(0.82f);
            checkBox.setScaleY(0.82f);
            checkBox.animate()
                    .alpha(1.0f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120L)
                    .start();
            return;
        }
        checkBox.animate()
                .alpha(0.0f)
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(90L)
                .withEndAction(() -> {
                    checkBox.setVisibility(View.GONE);
                    checkBox.setScaleX(0.82f);
                    checkBox.setScaleY(0.82f);
                })
                .start();
    }

    /**
     * 这个函数用于根据 Uri 查找媒体项在适配器中的位置。
     * 输入是媒体 Uri 字符串。
     * 输出是对应的适配器位置；找不到时返回 NO_POSITION。
     */
    private int adapterPositionForUri(String uriText) {
        Integer position = uriPositions.get(uriText);
        return position == null ? RecyclerView.NO_POSITION : position;
    }

    /**
     * 这个函数用于重建媒体 Uri 到适配器位置的索引表。
     * 输入是当前媒体列表。
     * 输出是更新后的 uriPositions 映射。
     */
    private void rebuildUriPositions() {
        uriPositions.clear();
        for (int i = 0; i < items.size(); i++) {
            uriPositions.put(items.get(i).getUri().toString(), i + 1);
        }
    }

    /**
     * 这个函数用于尝试直接刷新当前屏幕上可见的头部项。
     * 输入是无。
     * 输出是是否成功直接绑定了可见头部。
     */
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

    /**
     * 这个函数用于尝试直接刷新当前屏幕上可见的某个媒体项选择状态。
     * 输入是目标适配器位置。
     * 输出是是否成功直接绑定了该可见卡片。
     */
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

    /**
     * 这个函数用于刷新当前屏幕上所有可见媒体项的多选显示状态。
     * 输入是无。
     * 输出是最新的可见卡片勾选框和透明度状态。
     */
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
        final TextView cornerBadge;
        final TextView sizeBadge;
        final MaterialCheckBox selectionCheckBox;

        MediaViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.media_thumbnail);
            kindBadge = itemView.findViewById(R.id.media_kind_badge);
            cornerBadge = itemView.findViewById(R.id.media_corner_badge);
            sizeBadge = itemView.findViewById(R.id.media_size_badge);
            selectionCheckBox = itemView.findViewById(R.id.media_select_checkbox);
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView statusText;
        final TextView detailText;
        final TextView doneText;
        final TextView progressPercentText;
        final TextView mediaCountText;
        final ProgressBar progressBar;
        final Button primaryButton;
        final Button filterButton;
        final Button sortButton;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            statusText = itemView.findViewById(R.id.scan_status_text);
            detailText = itemView.findViewById(R.id.scan_detail_text);
            doneText = itemView.findViewById(R.id.scan_done_text);
            progressPercentText = itemView.findViewById(R.id.scan_progress_percent_text);
            mediaCountText = itemView.findViewById(R.id.media_count_text);
            progressBar = itemView.findViewById(R.id.scan_progress_bar);
            primaryButton = itemView.findViewById(R.id.scan_primary_button);
            filterButton = itemView.findViewById(R.id.filter_button);
            sortButton = itemView.findViewById(R.id.sort_button);
        }

        void bind(HeaderState state, Listener listener) {
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
