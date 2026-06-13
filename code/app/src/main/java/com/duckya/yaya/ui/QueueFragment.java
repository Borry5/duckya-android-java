package com.duckya.yaya.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.duckya.yaya.util.MediaTrashManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class QueueFragment extends Fragment implements QueueChangeListener {
    private QueueTaskAdapter adapter;
    private QueueTaskAdapter completedAdapter;
    private View mainPage;
    private View completedPage;
    private TextView savedText;
    private TextView remainingText;
    private TextView emptyText;
    private TextView completedCountText;
    private TextView completedEmptyText;
    private Button clearButton;
    private Button startButton;
    private Button completedClearButton;
    private Button completedRecycleAllButton;
    private View completedEntry;
    private final QueueManager queueManager = QueueManager.getInstance();
    private final MediaTrashManager trashManager = new MediaTrashManager();
    private PendingRecycleRequest pendingRecycleRequest;

    private final ActivityResultLauncher<IntentSenderRequest> trashRequestLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (pendingRecycleRequest == null) {
                    return;
                }
                PendingRecycleRequest request = pendingRecycleRequest;
                pendingRecycleRequest = null;
                if (result.getResultCode() != Activity.RESULT_OK) {
                    Toast.makeText(requireContext(), R.string.queue_recycle_cancelled, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (request.outputTarget) {
                    queueManager.markOutputRecycled(request.taskIds);
                } else {
                    queueManager.markOriginalRecycled(request.taskIds);
                }
                Toast.makeText(requireContext(), R.string.queue_recycle_done, Toast.LENGTH_SHORT).show();
            });

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
        mainPage = view.findViewById(R.id.queue_main_page);
        completedPage = view.findViewById(R.id.queue_completed_page);
        savedText = view.findViewById(R.id.queue_saved_text);
        remainingText = view.findViewById(R.id.queue_remaining_text);
        emptyText = view.findViewById(R.id.queue_empty_text);
        completedCountText = view.findViewById(R.id.queue_completed_count_text);
        completedEmptyText = view.findViewById(R.id.queue_completed_empty_text);
        clearButton = view.findViewById(R.id.queue_clear_button);
        startButton = view.findViewById(R.id.queue_start_button);
        completedClearButton = view.findViewById(R.id.queue_completed_clear_button);
        completedRecycleAllButton = view.findViewById(R.id.queue_completed_recycle_all_button);
        completedEntry = view.findViewById(R.id.queue_completed_entry);
        RecyclerView recyclerView = view.findViewById(R.id.queue_recycler);
        RecyclerView completedRecyclerView = view.findViewById(R.id.queue_completed_recycler);
        adapter = new QueueTaskAdapter(createQueueListener());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        if (recyclerView.getItemAnimator() instanceof SimpleItemAnimator) {
            // 进度频繁更新时关闭 change 动画，避免缩略图和卡片产生闪动。
            ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        recyclerView.setAdapter(adapter);
        // 已完成任务使用独立列表承载，进入二级页面时不会挤在弹窗里。
        completedAdapter = new QueueTaskAdapter(createQueueListener(), true);
        completedRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        if (completedRecyclerView.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) completedRecyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        completedRecyclerView.setAdapter(completedAdapter);
        clearButton.setOnClickListener(v -> queueManager.clear());
        startButton.setOnClickListener(v -> queueManager.toggleRunning());
        completedEntry.setOnClickListener(v -> showCompletedPage());
        view.findViewById(R.id.queue_completed_back_button).setOnClickListener(v -> showMainPage());
        completedClearButton.setOnClickListener(v -> {
            queueManager.clearCompletedTasks();
            showMainPage();
        });
        completedRecycleAllButton.setOnClickListener(v -> recycleAllOriginals());
        queueManager.addListener(this);
        refreshQueue();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        queueManager.removeListener(this);
        adapter = null;
        completedAdapter = null;
        mainPage = null;
        completedPage = null;
        savedText = null;
        remainingText = null;
        emptyText = null;
        completedCountText = null;
        completedEmptyText = null;
        clearButton = null;
        startButton = null;
        completedClearButton = null;
        completedRecycleAllButton = null;
        completedEntry = null;
    }

    @Override
    public void onQueueChanged() {
        if (getActivity() == null) {
            return;
        }
        requireActivity().runOnUiThread(this::refreshQueue);
    }

    private void refreshQueue() {
        if (adapter == null || completedAdapter == null || savedText == null || remainingText == null
                || emptyText == null || completedCountText == null || completedEmptyText == null
                || clearButton == null || startButton == null || completedClearButton == null
                || completedRecycleAllButton == null || completedEntry == null) {
            return;
        }
        java.util.List<QueueTask> activeTasks = queueManager.getActiveTasks();
        java.util.List<QueueTask> completedTasks = queueManager.getCompletedTasks();
        adapter.submitList(activeTasks);
        completedAdapter.submitList(completedTasks);
        savedText.setText(FormatUtils.formatSize(queueManager.actualSavedBytes()));
        remainingText.setText(activeTasks.isEmpty()
                ? "--"
                : FormatUtils.formatSize(queueManager.estimatedRemainingBytes()));
        emptyText.setVisibility(activeTasks.isEmpty() ? View.VISIBLE : View.GONE);
        clearButton.setEnabled(!activeTasks.isEmpty());
        startButton.setEnabled(!activeTasks.isEmpty());
        startButton.setText(queueManager.isRunning() ? R.string.queue_pause : R.string.queue_start);
        completedCountText.setText(getString(R.string.queue_completed_count, completedTasks.size()));
        // 没有已完成任务时隐藏入口，避免空栏目占用主队列空间。
        completedEntry.setVisibility(completedTasks.isEmpty() ? View.GONE : View.VISIBLE);
        completedEmptyText.setVisibility(completedTasks.isEmpty() ? View.VISIBLE : View.GONE);
        completedClearButton.setEnabled(!completedTasks.isEmpty());
        completedRecycleAllButton.setEnabled(hasRecyclableOriginal(completedTasks));
        if (completedTasks.isEmpty() && completedPage != null && completedPage.getVisibility() == View.VISIBLE) {
            showMainPage();
        }
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
                recycleTaskMedia(task, false);
            }

            @Override
            public void onRecycleOutput(QueueTask task) {
                recycleTaskMedia(task, true);
            }

            @Override
            public void onRecompress(QueueTask task) {
                queueManager.retryTask(task.getId());
                Toast.makeText(requireContext(), R.string.queue_recompress_added, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onPreview(QueueTask task) {
                CompletedTaskPreviewSheet.newInstance(task)
                        .show(getParentFragmentManager(), "completed_task_preview");
            }
        };
    }

    private boolean hasRecyclableOriginal(List<QueueTask> tasks) {
        for (QueueTask task : tasks) {
            if (!task.isOriginalRecycled()) {
                return true;
            }
        }
        return false;
    }

    private void recycleAllOriginals() {
        List<QueueTask> targets = new ArrayList<>();
        for (QueueTask task : queueManager.getCompletedTasks()) {
            if (!task.isOriginalRecycled()) {
                targets.add(task);
            }
        }
        requestRecycle(targets, false);
    }

    private void recycleTaskMedia(QueueTask task, boolean outputTarget) {
        List<QueueTask> targets = new ArrayList<>();
        targets.add(task);
        requestRecycle(targets, outputTarget);
    }

    private void requestRecycle(List<QueueTask> tasks, boolean outputTarget) {
        if (!trashManager.isTrashRequestSupported()) {
            Toast.makeText(requireContext(), R.string.queue_recycle_pending, Toast.LENGTH_SHORT).show();
            return;
        }
        List<Uri> uris = new ArrayList<>();
        List<String> taskIds = new ArrayList<>();
        for (QueueTask task : tasks) {
            Uri targetUri = outputTarget ? task.getCompressedAssetUri() : task.getMedia().getUri();
            boolean alreadyRecycled = outputTarget ? task.isOutputRecycled() : task.isOriginalRecycled();
            if (targetUri == null || alreadyRecycled) {
                continue;
            }
            uris.add(targetUri);
            taskIds.add(task.getId());
        }
        if (uris.isEmpty()) {
            Toast.makeText(requireContext(), R.string.queue_recycle_no_target, Toast.LENGTH_SHORT).show();
            return;
        }
        PendingIntent pendingIntent = trashManager.createTrashRequest(requireContext(), uris);
        if (pendingIntent == null) {
            Toast.makeText(requireContext(), R.string.queue_recycle_pending, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingRecycleRequest = new PendingRecycleRequest(taskIds, outputTarget);
        try {
            trashRequestLauncher.launch(new IntentSenderRequest.Builder(pendingIntent.getIntentSender()).build());
        } catch (RuntimeException e) {
            pendingRecycleRequest = null;
            Toast.makeText(requireContext(), R.string.queue_recycle_pending, Toast.LENGTH_SHORT).show();
        }
    }

    private void showCompletedPage() {
        java.util.List<QueueTask> completedTasks = queueManager.getCompletedTasks();
        if (completedTasks.isEmpty()) {
            Toast.makeText(requireContext(), R.string.queue_completed_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // 在当前 Fragment 内切换到完整二级页面，保留左上角返回入口。
        mainPage.setVisibility(View.GONE);
        completedPage.setVisibility(View.VISIBLE);
    }

    private void showMainPage() {
        if (mainPage == null || completedPage == null) {
            return;
        }
        completedPage.setVisibility(View.GONE);
        mainPage.setVisibility(View.VISIBLE);
    }

    private static class PendingRecycleRequest {
        private final List<String> taskIds;
        private final boolean outputTarget;

        private PendingRecycleRequest(List<String> taskIds, boolean outputTarget) {
            this.taskIds = taskIds;
            this.outputTarget = outputTarget;
        }
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
