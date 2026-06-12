package com.duckya.yaya.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.duckya.yaya.R;
import com.duckya.yaya.model.CompressionSettings;
import com.duckya.yaya.model.CompressionPreset;
import com.duckya.yaya.model.QueueTask;
import com.duckya.yaya.queue.QueueChangeListener;
import com.duckya.yaya.queue.QueueManager;
import com.duckya.yaya.util.FormatUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class QueueFragment extends Fragment implements QueueChangeListener {
    private QueueTaskAdapter adapter;
    private TextView savedText;
    private TextView remainingText;
    private TextView emptyText;
    private Button clearButton;
    private Button startButton;
    private Button completedButton;
    private final QueueManager queueManager = QueueManager.getInstance();

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_queue, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        savedText = view.findViewById(R.id.queue_saved_text);
        remainingText = view.findViewById(R.id.queue_remaining_text);
        emptyText = view.findViewById(R.id.queue_empty_text);
        clearButton = view.findViewById(R.id.queue_clear_button);
        startButton = view.findViewById(R.id.queue_start_button);
        completedButton = view.findViewById(R.id.queue_completed_button);
        RecyclerView recyclerView = view.findViewById(R.id.queue_recycler);
        adapter = new QueueTaskAdapter(createQueueListener());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        if (recyclerView.getItemAnimator() instanceof SimpleItemAnimator) {
            // 进度频繁更新时关闭 change 动画，避免缩略图和卡片产生闪动。
            ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        recyclerView.setAdapter(adapter);
        clearButton.setOnClickListener(v -> queueManager.clear());
        startButton.setOnClickListener(v -> queueManager.toggleRunning());
        completedButton.setOnClickListener(v -> showCompletedTasksDialog());
        queueManager.addListener(this);
        refreshQueue();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        queueManager.removeListener(this);
        adapter = null;
        savedText = null;
        remainingText = null;
        emptyText = null;
        clearButton = null;
        startButton = null;
        completedButton = null;
    }

    @Override
    public void onQueueChanged() {
        if (getActivity() == null) {
            return;
        }
        requireActivity().runOnUiThread(this::refreshQueue);
    }

    private void refreshQueue() {
        if (adapter == null || savedText == null || remainingText == null || emptyText == null
                || clearButton == null || startButton == null || completedButton == null) {
            return;
        }
        java.util.List<QueueTask> activeTasks = queueManager.getActiveTasks();
        java.util.List<QueueTask> completedTasks = queueManager.getCompletedTasks();
        adapter.submitList(activeTasks);
        savedText.setText(FormatUtils.formatSize(queueManager.actualSavedBytes()));
        remainingText.setText(activeTasks.isEmpty()
                ? "--"
                : FormatUtils.formatSize(queueManager.estimatedRemainingBytes()));
        emptyText.setVisibility(activeTasks.isEmpty() ? View.VISIBLE : View.GONE);
        clearButton.setEnabled(!activeTasks.isEmpty());
        startButton.setEnabled(!activeTasks.isEmpty());
        startButton.setText(queueManager.isRunning() ? R.string.queue_pause : R.string.queue_start);
        completedButton.setText(getString(R.string.queue_completed_tasks) + "  "
                + getString(R.string.queue_completed_count, completedTasks.size()));
        completedButton.setEnabled(!completedTasks.isEmpty());
    }

    private QueueTaskAdapter.Listener createQueueListener() {
        return new QueueTaskAdapter.Listener() {
            @Override
            public void onCancel(QueueTask task) {
                queueManager.removeTask(task.getId());
            }

            @Override
            public void onRetry(QueueTask task) {
                queueManager.retryTask(task.getId());
            }

            @Override
            public void onPrioritize(QueueTask task) {
                queueManager.prioritizeTask(task.getId());
            }

            @Override
            public void onOpenSettings(QueueTask task) {
                showCompressionDialog(task);
            }

            @Override
            public void onRecycleOriginal(QueueTask task) {
                Toast.makeText(requireContext(), R.string.queue_recycle_pending, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRecycleOutput(QueueTask task) {
                Toast.makeText(requireContext(), R.string.queue_recycle_pending, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRecompress(QueueTask task) {
                queueManager.retryTask(task.getId());
                Toast.makeText(requireContext(), R.string.queue_recompress_added, Toast.LENGTH_SHORT).show();
            }
        };
    }

    private void showCompletedTasksDialog() {
        java.util.List<QueueTask> completedTasks = queueManager.getCompletedTasks();
        if (completedTasks.isEmpty()) {
            Toast.makeText(requireContext(), R.string.queue_completed_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        RecyclerView recyclerView = new RecyclerView(requireContext());
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        recyclerView.setPadding(padding, padding, padding, padding);
        recyclerView.setClipToPadding(false);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        if (recyclerView.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        QueueTaskAdapter completedAdapter = new QueueTaskAdapter(createQueueListener(), true);
        completedAdapter.submitList(completedTasks);
        recyclerView.setAdapter(completedAdapter);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.queue_completed_tasks)
                .setView(recyclerView)
                .setPositiveButton(R.string.queue_settings_close, null)
                .show();
    }

    // 用安卓原生小弹窗切换每个图片任务的压缩档位。
    private void showCompressionDialog(QueueTask task) {
        if (task.getAction() != com.duckya.yaya.model.QueueAction.COMPRESS) {
            return;
        }
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_compression_preset, null, false);
        View lightOption = dialogView.findViewById(R.id.preset_light_option);
        View strongOption = dialogView.findViewById(R.id.preset_strong_option);
        RadioButton lightRadio = dialogView.findViewById(R.id.preset_light_radio);
        RadioButton strongRadio = dialogView.findViewById(R.id.preset_strong_radio);
        boolean strongSelected = task.getSettings().getPreset() == CompressionPreset.STRONG;
        lightRadio.setChecked(!strongSelected);
        strongRadio.setChecked(strongSelected);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.queue_settings_title)
                .setView(dialogView)
                .setNegativeButton(R.string.queue_settings_close, null)
                .create();

        lightOption.setOnClickListener(v -> {
            queueManager.updateTaskSettings(task.getId(), CompressionSettings.light());
            dialog.dismiss();
        });
        strongOption.setOnClickListener(v -> {
            queueManager.updateTaskSettings(task.getId(), CompressionSettings.strong());
            dialog.dismiss();
        });
        dialog.show();
    }
}
