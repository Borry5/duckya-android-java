package com.duckya.yaya.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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

public class MediaGridAdapter extends RecyclerView.Adapter<MediaGridAdapter.MediaViewHolder> {
    public interface OnMediaClickListener {
        void onMediaClick(MediaItemInfo item);
    }

    private final List<MediaItemInfo> items = new ArrayList<>();
    private final OnMediaClickListener listener;

    public MediaGridAdapter(OnMediaClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<MediaItemInfo> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_media_grid, parent, false);
        return new MediaViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
        MediaItemInfo item = items.get(position);
        holder.sizeBadge.setText(FormatUtils.formatSize(item.getSizeBytes()));
        holder.kindBadge.setText(item.getKind() == MediaKind.VIDEO
                ? formatDuration(item.getDurationMs())
                : formatMegapixels(item.getWidth(), item.getHeight()));
        ThumbnailLoader.loadInto(holder.thumbnail.getContext(), item, holder.thumbnail);
        holder.itemView.setOnClickListener(v -> listener.onMediaClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
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

        MediaViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.media_thumbnail);
            kindBadge = itemView.findViewById(R.id.media_kind_badge);
            sizeBadge = itemView.findViewById(R.id.media_size_badge);
        }
    }
}
