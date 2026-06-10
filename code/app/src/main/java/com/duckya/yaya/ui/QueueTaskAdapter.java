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
import com.duckya.yaya.model.QueueAction;
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
        holder.titleText.setText(task.getMedia().getName());
        holder.metaText.setText(buildMetaText(holder.itemView, task));
        holder.progressBar.setProgress(Math.round(task.getProgress() * 100f));
        ThumbnailLoader.loadInto(holder.thumbnail.getContext(), task.getMedia(), holder.thumbnail);
        holder.cancelButton.setVisibility(task.getStatus() == QueueStatus.DONE ? View.GONE : View.VISIBLE);
        holder.retryButton.setVisibility(task.getStatus() == QueueStatus.FAILED ? View.VISIBLE : View.GONE);
        holder.prioritizeButton.setVisibility(task.getStatus() == QueueStatus.PENDING ? View.VISIBLE : View.GONE);
        holder.cancelButton.setOnClickListener(v -> listener.onCancel(task));
        holder.retryButton.setOnClickListener(v -> listener.onRetry(task));
        holder.prioritizeButton.setOnClickListener(v -> listener.onPrioritize(task));
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    private String buildMetaText(View view, QueueTask task) {
        String action = view.getContext().getString(task.getAction() == QueueAction.DELETE
                ? R.string.queue_action_delete
                : R.string.queue_action_compress);
        String status = view.getContext().getString(statusRes(task.getStatus()));
        String size = FormatUtils.formatSize(task.getMedia().getSizeBytes());
        String saved = view.getContext().getString(R.string.queue_est_saved, FormatUtils.formatSize(task.getEstimatedSavedBytes()));
        if (task.getAction() == QueueAction.COMPRESS) {
            String output = FormatUtils.formatSize(task.getEstimatedOutputBytes());
            return String.format(Locale.getDefault(), "%s · %s · %s -> %s · %s", action, status, size, output, saved);
        }
        return String.format(Locale.getDefault(), "%s · %s · %s · %s", action, status, size, saved);
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
        final TextView titleText;
        final TextView metaText;
        final ProgressBar progressBar;
        final Button cancelButton;
        final Button retryButton;
        final Button prioritizeButton;

        TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.queue_thumb);
            titleText = itemView.findViewById(R.id.queue_task_title);
            metaText = itemView.findViewById(R.id.queue_task_meta);
            progressBar = itemView.findViewById(R.id.queue_task_progress);
            cancelButton = itemView.findViewById(R.id.queue_cancel_button);
            retryButton = itemView.findViewById(R.id.queue_retry_button);
            prioritizeButton = itemView.findViewById(R.id.queue_prioritize_button);
        }
    }
}
