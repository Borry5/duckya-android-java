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
import java.util.Objects;

public class QueueTaskAdapter extends RecyclerView.Adapter<QueueTaskAdapter.TaskViewHolder> {
    private static final String PAYLOAD_DYNAMIC = "payload_dynamic";

    public interface Listener {
        void onCancel(QueueTask task);

        void onRetry(QueueTask task);

        void onOpenSettings(QueueTask task);

        void onRecycleOriginal(QueueTask task);

        void onRecycleOutput(QueueTask task);

        void onRecompress(QueueTask task);

        void onPreview(QueueTask task);
    }

    private final List<QueueTask> tasks = new ArrayList<>();
    private final List<TaskSnapshot> snapshots = new ArrayList<>();
    private final Listener listener;
    private final boolean completedMode;

    public QueueTaskAdapter(Listener listener) {
        this(listener, false);
    }

    public QueueTaskAdapter(Listener listener, boolean completedMode) {
        this.listener = listener;
        this.completedMode = completedMode;
    }

    public void submitList(List<QueueTask> newTasks) {
        List<TaskSnapshot> newSnapshots = buildSnapshots(newTasks);
        if (!hasSameStructure(newSnapshots)) {
            tasks.clear();
            tasks.addAll(newTasks);
            snapshots.clear();
            snapshots.addAll(newSnapshots);
            notifyDataSetChanged();
            return;
        }

        tasks.clear();
        tasks.addAll(newTasks);
        for (int i = 0; i < newSnapshots.size(); i++) {
            TaskSnapshot oldSnapshot = snapshots.get(i);
            TaskSnapshot newSnapshot = newSnapshots.get(i);
            if (!oldSnapshot.hasSameDynamicState(newSnapshot)) {
                notifyItemChanged(i, PAYLOAD_DYNAMIC);
            }
        }
        snapshots.clear();
        snapshots.addAll(newSnapshots);
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
        holder.sourceSizeText.setText(FormatUtils.formatSize(task.getMedia().getSizeBytes()));
        ThumbnailLoader.loadInto(holder.thumbnail.getContext(), task.getMedia(), holder.thumbnail);
        holder.thumbnail.setOnClickListener(v -> {
            if (completedMode) {
                listener.onPreview(task);
            }
        });
        holder.thumbnail.setClickable(completedMode);
        holder.thumbnail.setContentDescription(completedMode
                ? holder.itemView.getContext().getString(R.string.completed_preview_open)
                : null);
        bindDynamicState(holder, task);
    }

    @Override
    public void onBindViewHolder(
            @NonNull TaskViewHolder holder,
            int position,
            @NonNull List<Object> payloads
    ) {
        if (payloads.contains(PAYLOAD_DYNAMIC)) {
            bindDynamicState(holder, tasks.get(position));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    private void bindDynamicState(TaskViewHolder holder, QueueTask task) {
        holder.statusText.setText(holder.itemView.getContext().getString(statusRes(task.getStatus())));
        holder.outputSizeText.setText(buildOutputText(holder.itemView, task));
        holder.outputEstimateHintText.setVisibility(task.getActualOutputBytes() > 0L ? View.GONE : View.VISIBLE);
        holder.metaText.setText(buildMetaText(holder.itemView, task));
        bindBitrateText(holder, task);
        holder.progressBar.setProgress(Math.round(task.getProgress() * 100f));
        holder.inlineActionRow.setVisibility(completedMode ? View.GONE : View.VISIBLE);
        holder.completedActionRow.setVisibility(completedMode ? View.VISIBLE : View.GONE);
        holder.cancelButton.setVisibility(!completedMode && task.getStatus() != QueueStatus.DONE ? View.VISIBLE : View.GONE);
        holder.retryButton.setVisibility(!completedMode && task.getStatus() == QueueStatus.FAILED ? View.VISIBLE : View.GONE);
        holder.settingsButton.setVisibility(task.getAction() == QueueAction.COMPRESS ? View.VISIBLE : View.GONE);
        bindCompletedActions(holder, task);
        holder.cancelButton.setOnClickListener(v -> listener.onCancel(task));
        holder.retryButton.setOnClickListener(v -> listener.onRetry(task));
        holder.settingsButton.setOnClickListener(v -> listener.onOpenSettings(task));
        holder.recycleOriginalButton.setOnClickListener(v -> listener.onRecycleOriginal(task));
        holder.recycleOutputButton.setOnClickListener(v -> listener.onRecycleOutput(task));
        holder.recompressButton.setOnClickListener(v -> listener.onRecompress(task));
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    private String buildMetaText(View view, QueueTask task) {
        int savedRes = task.getStatus() == QueueStatus.DONE ? R.string.queue_saved_done : R.string.queue_est_saved;
        String saved = view.getContext().getString(savedRes, FormatUtils.formatSize(task.getSavedBytes()));
        if (task.getStatus() == QueueStatus.FAILED && task.getFailureReason() != null && !task.getFailureReason().isEmpty()) {
            return task.getFailureReason();
        }
        if (task.getAction() == QueueAction.DELETE) {
            return view.getContext().getString(R.string.queue_delete_hint);
        }
        return String.format(Locale.getDefault(), "%s · %s", presetLabel(view, task), saved);
    }

    private void bindCompletedActions(TaskViewHolder holder, QueueTask task) {
        if (!completedMode) {
            return;
        }
        boolean isVideo = task.getMedia().getKind() == MediaKind.VIDEO;
        holder.recycleOriginalButton.setText(isVideo
                ? R.string.queue_recycle_original_video
                : R.string.queue_recycle_original_image);
        holder.recycleOutputButton.setText(isVideo
                ? R.string.queue_recycle_compressed_video
                : R.string.queue_recycle_compressed_image);
        if (task.isOriginalRecycled()) {
            holder.recycleOriginalButton.setText(isVideo
                    ? R.string.queue_recycled_original_video
                    : R.string.queue_recycled_original_image);
        }
        if (task.isOutputRecycled()) {
            holder.recycleOutputButton.setText(isVideo
                    ? R.string.queue_recycled_compressed_video
                    : R.string.queue_recycled_compressed_image);
        }
        holder.recycleOriginalButton.setEnabled(!task.isOriginalRecycled());
        holder.recycleOutputButton.setEnabled(!task.isOutputRecycled() && task.getCompressedAssetUri() != null);
        holder.recompressButton.setEnabled(task.getAction() == QueueAction.COMPRESS && !task.isOriginalRecycled());
    }

    private String buildOutputText(View view, QueueTask task) {
        long outputBytes = task.getActualOutputBytes() > 0L
                ? task.getActualOutputBytes()
                : task.getEstimatedOutputBytes();
        String output = FormatUtils.formatSize(outputBytes);
        if (task.getActualOutputBytes() > 0L) {
            return output;
        }
        return output;
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
        if (task.getVideoSettings().isLimitToOneGb()) {
            return "总文件不超过 1 GB";
        }
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
        if (task.getMedia().getKind() == MediaKind.VIDEO) {
            switch (task.getVideoSettings().getPreset()) {
                case HIGH_QUALITY:
                    return view.getContext().getString(R.string.video_preset_high_quality);
                case SHARE:
                    return view.getContext().getString(R.string.video_preset_share);
                case BALANCED:
                default:
                    return view.getContext().getString(R.string.video_preset_balanced);
            }
        }
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

    private List<TaskSnapshot> buildSnapshots(List<QueueTask> sourceTasks) {
        List<TaskSnapshot> result = new ArrayList<>();
        for (QueueTask task : sourceTasks) {
            result.add(new TaskSnapshot(task));
        }
        return result;
    }

    private boolean hasSameStructure(List<TaskSnapshot> newSnapshots) {
        if (snapshots.size() != newSnapshots.size()) {
            return false;
        }
        for (int i = 0; i < snapshots.size(); i++) {
            if (!Objects.equals(snapshots.get(i).id, newSnapshots.get(i).id)) {
                return false;
            }
        }
        return true;
    }

    private static class TaskSnapshot {
        private final String id;
        private final QueueStatus status;
        private final float progress;
        private final long estimatedOutputBytes;
        private final long actualOutputBytes;
        private final String failureReason;
        private final com.duckya.yaya.model.CompressionPreset preset;
        private final com.duckya.yaya.model.VideoCompressionPreset videoPreset;
        private final com.duckya.yaya.model.VideoResolutionOption videoResolution;
        private final com.duckya.yaya.model.VideoCodecOption videoCodec;
        private final com.duckya.yaya.model.VideoFrameRateOption videoFrameRate;
        private final com.duckya.yaya.model.VideoAudioMode videoAudio;
        private final float videoBitrateMbps;
        private final boolean videoAutoBitrate;
        private final boolean videoLimitToOneGb;
        private final boolean originalRecycled;
        private final boolean outputRecycled;

        TaskSnapshot(QueueTask task) {
            id = task.getId();
            status = task.getStatus();
            progress = task.getProgress();
            estimatedOutputBytes = task.getEstimatedOutputBytes();
            actualOutputBytes = task.getActualOutputBytes();
            failureReason = task.getFailureReason();
            preset = task.getSettings().getPreset();
            videoPreset = task.getVideoSettings().getPreset();
            videoResolution = task.getVideoSettings().getResolutionOption();
            videoCodec = task.getVideoSettings().getCodecOption();
            videoFrameRate = task.getVideoSettings().getFrameRateOption();
            videoAudio = task.getVideoSettings().getAudioMode();
            videoBitrateMbps = task.getVideoSettings().getTargetBitrateMbps();
            videoAutoBitrate = task.getVideoSettings().isAutoBitrate();
            videoLimitToOneGb = task.getVideoSettings().isLimitToOneGb();
            originalRecycled = task.isOriginalRecycled();
            outputRecycled = task.isOutputRecycled();
        }

        private boolean hasSameDynamicState(TaskSnapshot other) {
            return status == other.status
                    && Float.compare(progress, other.progress) == 0
                    && estimatedOutputBytes == other.estimatedOutputBytes
                    && actualOutputBytes == other.actualOutputBytes
                    && Objects.equals(failureReason, other.failureReason)
                    && preset == other.preset
                    && videoPreset == other.videoPreset
                    && videoResolution == other.videoResolution
                    && videoCodec == other.videoCodec
                    && videoFrameRate == other.videoFrameRate
                    && videoAudio == other.videoAudio
                    && Float.compare(videoBitrateMbps, other.videoBitrateMbps) == 0
                    && videoAutoBitrate == other.videoAutoBitrate
                    && videoLimitToOneGb == other.videoLimitToOneGb
                    && originalRecycled == other.originalRecycled
                    && outputRecycled == other.outputRecycled;
        }
    }

    static class TaskViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView statusText;
        final TextView sourceSizeText;
        final TextView outputSizeText;
        final TextView bitrateText;
        final TextView metaText;
        final ProgressBar progressBar;
        final View inlineActionRow;
        final View completedActionRow;
        final ImageButton settingsButton;
        final Button cancelButton;
        final Button retryButton;
        final Button recycleOriginalButton;
        final Button recycleOutputButton;
        final Button recompressButton;
        final TextView outputEstimateHintText;

        TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.queue_thumb);
            statusText = itemView.findViewById(R.id.queue_task_status);
            sourceSizeText = itemView.findViewById(R.id.queue_source_size_text);
            outputSizeText = itemView.findViewById(R.id.queue_output_size_text);
            bitrateText = itemView.findViewById(R.id.queue_bitrate_text);
            metaText = itemView.findViewById(R.id.queue_task_meta);
            progressBar = itemView.findViewById(R.id.queue_task_progress);
            inlineActionRow = itemView.findViewById(R.id.queue_inline_action_row);
            completedActionRow = itemView.findViewById(R.id.queue_completed_action_row);
            settingsButton = itemView.findViewById(R.id.queue_settings_button);
            cancelButton = itemView.findViewById(R.id.queue_cancel_button);
            retryButton = itemView.findViewById(R.id.queue_retry_button);
            recycleOriginalButton = itemView.findViewById(R.id.queue_recycle_original_button);
            recycleOutputButton = itemView.findViewById(R.id.queue_recycle_output_button);
            recompressButton = itemView.findViewById(R.id.queue_recompress_button);
            outputEstimateHintText = itemView.findViewById(R.id.queue_output_estimate_hint_text);
        }
    }
}
