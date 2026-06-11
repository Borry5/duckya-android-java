package com.duckya.yaya.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.duckya.yaya.R;
import com.duckya.yaya.model.QueueAction;
import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.model.QueueStatus;
import com.duckya.yaya.model.QueueTask;
import com.duckya.yaya.util.FormatUtils;
import com.duckya.yaya.util.ThumbnailLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class QueueTaskAdapter extends RecyclerView.Adapter<QueueTaskAdapter.TaskViewHolder> {
    public interface Listener {
        void onCancel(QueueTask task);

        void onRetry(QueueTask task);

        void onPrioritize(QueueTask task);

        void onOpenSettings(QueueTask task);
    }

    private final List<QueueTask> tasks = new ArrayList<>();
    private final Listener listener;

    public QueueTaskAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<QueueTask> newTasks) {
        tasks.clear();
        tasks.addAll(newTasks);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_queue_task, parent, false);
        return new TaskViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        QueueTask task = tasks.get(position);
        holder.statusText.setText(holder.itemView.getContext().getString(statusRes(task.getStatus())));
        holder.sourceSizeText.setText(FormatUtils.formatSize(task.getMedia().getSizeBytes()));
        holder.outputSizeText.setText(buildOutputText(holder.itemView, task));
        holder.metaText.setText(buildMetaText(holder.itemView, task));
        bindBitrateText(holder, task);
        holder.progressBar.setProgress(Math.round(task.getProgress() * 100f));
        ThumbnailLoader.loadInto(holder.thumbnail.getContext(), task.getMedia(), holder.thumbnail);
        holder.cancelButton.setVisibility(task.getStatus() == QueueStatus.DONE ? View.GONE : View.VISIBLE);
        holder.retryButton.setVisibility(task.getStatus() == QueueStatus.FAILED ? View.VISIBLE : View.GONE);
        holder.prioritizeButton.setVisibility(task.getStatus() == QueueStatus.PENDING ? View.VISIBLE : View.GONE);
        holder.settingsButton.setVisibility(task.getAction() == QueueAction.COMPRESS ? View.VISIBLE : View.GONE);
        holder.cancelButton.setOnClickListener(v -> listener.onCancel(task));
        holder.retryButton.setOnClickListener(v -> listener.onRetry(task));
        holder.prioritizeButton.setOnClickListener(v -> listener.onPrioritize(task));
        holder.settingsButton.setOnClickListener(v -> listener.onOpenSettings(task));
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    private String buildMetaText(View view, QueueTask task) {
        String saved = view.getContext().getString(R.string.queue_est_saved, FormatUtils.formatSize(task.getSavedBytes()));
        if (task.getStatus() == QueueStatus.FAILED && task.getFailureReason() != null && !task.getFailureReason().isEmpty()) {
            return task.getFailureReason();
        }
        if (task.getAction() == QueueAction.DELETE) {
            return view.getContext().getString(R.string.queue_delete_hint);
        }
        return String.format(Locale.getDefault(), "%s · %s", presetLabel(view, task), saved);
    }

    private String buildOutputText(View view, QueueTask task) {
        long outputBytes = task.getActualOutputBytes() > 0L
                ? task.getActualOutputBytes()
                : task.getEstimatedOutputBytes();
        String output = FormatUtils.formatSize(outputBytes);
        if (task.getActualOutputBytes() > 0L) {
            return output;
        }
        return view.getContext().getString(R.string.queue_output_estimated, output);
    }

    private void bindBitrateText(TaskViewHolder holder, QueueTask task) {
        if (task.getMedia().getKind() != MediaKind.VIDEO) {
            holder.bitrateText.setVisibility(View.GONE);
            return;
        }
        holder.bitrateText.setVisibility(View.VISIBLE);
        holder.bitrateText.setText(buildVideoBitrateText(task));
    }

    private String buildVideoBitrateText(QueueTask task) {
        long durationMs = task.getMedia().getDurationMs();
        if (durationMs <= 0L) {
            return "";
        }
        double seconds = durationMs / 1000.0;
        double sourceMbps = task.getMedia().getSizeBytes() * 8.0 / seconds / 1_000_000.0;
        double outputMbps = task.getEstimatedOutputBytes() * 8.0 / seconds / 1_000_000.0;
        return String.format(Locale.getDefault(), "%.1f Mbps -> %.1f Mbps", sourceMbps, outputMbps);
    }

    private String presetLabel(View view, QueueTask task) {
        if (task.getSettings().getPreset() == com.duckya.yaya.model.CompressionPreset.STRONG) {
            return view.getContext().getString(R.string.queue_preset_strong);
        }
        return view.getContext().getString(R.string.queue_preset_light);
    }

    private int statusRes(QueueStatus status) {
        if (status == QueueStatus.RUNNING) {
            return R.string.queue_status_running;
        }
        if (status == QueueStatus.DONE) {
            return R.string.queue_status_done;
        }
        if (status == QueueStatus.FAILED) {
            return R.string.queue_status_failed;
        }
        return R.string.queue_status_pending;
    }

    static class TaskViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView statusText;
        final TextView sourceSizeText;
        final TextView outputSizeText;
        final TextView bitrateText;
        final TextView metaText;
        final ProgressBar progressBar;
        final ImageButton settingsButton;
        final Button cancelButton;
        final Button retryButton;
        final Button prioritizeButton;

        TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.queue_thumb);
            statusText = itemView.findViewById(R.id.queue_task_status);
            sourceSizeText = itemView.findViewById(R.id.queue_source_size_text);
            outputSizeText = itemView.findViewById(R.id.queue_output_size_text);
            bitrateText = itemView.findViewById(R.id.queue_bitrate_text);
            metaText = itemView.findViewById(R.id.queue_task_meta);
            progressBar = itemView.findViewById(R.id.queue_task_progress);
            settingsButton = itemView.findViewById(R.id.queue_settings_button);
            cancelButton = itemView.findViewById(R.id.queue_cancel_button);
            retryButton = itemView.findViewById(R.id.queue_retry_button);
            prioritizeButton = itemView.findViewById(R.id.queue_prioritize_button);
        }
    }
}
