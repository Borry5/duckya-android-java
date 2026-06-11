package com.duckya.yaya.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
    private TextView pendingText;
    private TextView savedText;
    private Button clearButton;
    private Button startButton;
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
        pendingText = view.findViewById(R.id.queue_pending_text);
        savedText = view.findViewById(R.id.queue_saved_text);
        clearButton = view.findViewById(R.id.queue_clear_button);
        startButton = view.findViewById(R.id.queue_start_button);
        RecyclerView recyclerView = view.findViewById(R.id.queue_recycler);
        adapter = new QueueTaskAdapter(new QueueTaskAdapter.Listener() {
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
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        clearButton.setOnClickListener(v -> queueManager.clear());
        startButton.setOnClickListener(v -> queueManager.toggleRunning());
        queueManager.addListener(this);
        refreshQueue();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        queueManager.removeListener(this);
        adapter = null;
        pendingText = null;
        savedText = null;
        clearButton = null;
        startButton = null;
    }

    @Override
    public void onQueueChanged() {
        if (getActivity() == null) {
            return;
        }
        requireActivity().runOnUiThread(this::refreshQueue);
    }

    private void refreshQueue() {
        if (adapter == null || pendingText == null || savedText == null || clearButton == null || startButton == null) {
            return;
        }
        adapter.submitList(queueManager.getTasks());
        pendingText.setText(getString(R.string.queue_pending_count, queueManager.pendingCount()));
        savedText.setText(getString(R.string.queue_total_saved, FormatUtils.formatSize(queueManager.estimatedSavedBytes())));
        boolean hasTasks = !queueManager.getTasks().isEmpty();
        clearButton.setEnabled(hasTasks);
        startButton.setEnabled(hasTasks);
        startButton.setText(queueManager.isRunning() ? R.string.queue_pause : R.string.queue_start);
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
