package com.duckya.yaya.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.graphics.BitmapFactory;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.duckya.yaya.R;
import com.duckya.yaya.model.CompressionSettings;
import com.duckya.yaya.model.CompressionPreset;
import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.model.QueueAction;
import com.duckya.yaya.model.QueueTask;
import com.duckya.yaya.queue.QueueChangeListener;
import com.duckya.yaya.queue.QueueManager;
import com.duckya.yaya.util.FormatUtils;
import com.duckya.yaya.util.MediaTrashManager;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class QueueFragment extends Fragment implements QueueChangeListener {
    private QueueTaskAdapter adapter;
    private QueueTaskAdapter completedAdapter;
    private View mainPage;
    private View completedPage;
    private View previewPage;
    private TextView savedText;
    private TextView remainingText;
    private TextView emptyText;
    private TextView completedCountText;
    private TextView completedEmptyText;
    private TextView previewOriginalInfoText;
    private TextView previewCompressedInfoText;
    private ZoomImageView previewOriginalImage;
    private ZoomImageView previewCompressedImage;
    private Button previewRecycleOriginalButton;
    private Button previewRecycleOutputButton;
    private Button previewRecompressButton;
    private Button clearButton;
    private Button startButton;
    private Button completedClearButton;
    private Button completedRecycleAllButton;
    private View completedEntry;
    private boolean syncingPreviewZoom;
    private final QueueManager queueManager = QueueManager.getInstance();
    private final MediaTrashManager trashManager = new MediaTrashManager();
    private PendingRecycleRequest pendingRecycleRequest;
    private QueueTask previewTask;

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
        previewPage = view.findViewById(R.id.queue_preview_page);
        savedText = view.findViewById(R.id.queue_saved_text);
        remainingText = view.findViewById(R.id.queue_remaining_text);
        emptyText = view.findViewById(R.id.queue_empty_text);
        completedCountText = view.findViewById(R.id.queue_completed_count_text);
        completedEmptyText = view.findViewById(R.id.queue_completed_empty_text);
        previewOriginalInfoText = view.findViewById(R.id.queue_preview_original_info);
        previewCompressedInfoText = view.findViewById(R.id.queue_preview_compressed_info);
        previewOriginalImage = view.findViewById(R.id.queue_preview_original_image);
        previewCompressedImage = view.findViewById(R.id.queue_preview_compressed_image);
        previewRecycleOriginalButton = view.findViewById(R.id.queue_preview_recycle_original_button);
        previewRecycleOutputButton = view.findViewById(R.id.queue_preview_recycle_output_button);
        previewRecompressButton = view.findViewById(R.id.queue_preview_recompress_button);
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
        previewOriginalImage.setOnZoomStateChangeListener((source, state) ->
                syncPreviewZoom(previewCompressedImage, state));
        previewCompressedImage.setOnZoomStateChangeListener((source, state) ->
                syncPreviewZoom(previewOriginalImage, state));
        clearButton.setOnClickListener(v -> queueManager.clear());
        startButton.setOnClickListener(v -> queueManager.toggleRunning());
        completedEntry.setOnClickListener(v -> showCompletedPage());
        view.findViewById(R.id.queue_completed_back_button).setOnClickListener(v -> showMainPage());
        view.findViewById(R.id.queue_preview_back_button).setOnClickListener(v -> showCompletedPage());
        previewRecycleOriginalButton.setOnClickListener(v -> {
            if (previewTask != null) {
                recycleTaskMedia(previewTask, false);
            }
        });
        previewRecycleOutputButton.setOnClickListener(v -> {
            if (previewTask != null) {
                recycleTaskMedia(previewTask, true);
            }
        });
        previewRecompressButton.setOnClickListener(v -> {
            if (previewTask == null) {
                return;
            }
            queueManager.retryTask(previewTask.getId());
            Toast.makeText(requireContext(), R.string.queue_recompress_added, Toast.LENGTH_SHORT).show();
            showMainPage();
        });
        completedClearButton.setOnClickListener(v -> showClearCompletedConfirmDialog());
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
        previewPage = null;
        savedText = null;
        remainingText = null;
        emptyText = null;
        completedCountText = null;
        completedEmptyText = null;
        previewOriginalInfoText = null;
        previewCompressedInfoText = null;
        if (previewOriginalImage != null) {
            previewOriginalImage.setOnZoomStateChangeListener(null);
        }
        if (previewCompressedImage != null) {
            previewCompressedImage.setOnZoomStateChangeListener(null);
        }
        previewOriginalImage = null;
        previewCompressedImage = null;
        previewRecycleOriginalButton = null;
        previewRecycleOutputButton = null;
        previewRecompressButton = null;
        clearButton = null;
        startButton = null;
        completedClearButton = null;
        completedRecycleAllButton = null;
        completedEntry = null;
        previewTask = null;
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
                || completedRecycleAllButton == null || completedEntry == null || previewPage == null
                || previewRecycleOriginalButton == null || previewRecycleOutputButton == null || previewRecompressButton == null) {
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
        if (previewPage.getVisibility() == View.VISIBLE && previewTask != null) {
            bindPreviewActions(previewTask);
        }
        if (completedTasks.isEmpty() && completedPage != null && completedPage.getVisibility() == View.VISIBLE) {
            showMainPage();
        }
        if (previewTask != null && !containsTask(completedTasks, previewTask.getId())
                && previewPage.getVisibility() == View.VISIBLE) {
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
                showPreviewPage(task);
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
        if (shouldConfirmCounterpartRecycle(targets, false)) {
            showCounterpartRecycleConfirmDialog(targets, false);
            return;
        }
        requestRecycle(targets, false);
    }

    private void recycleTaskMedia(QueueTask task, boolean outputTarget) {
        List<QueueTask> targets = new ArrayList<>();
        targets.add(task);
        if (shouldConfirmCounterpartRecycle(targets, outputTarget)) {
            showCounterpartRecycleConfirmDialog(targets, outputTarget);
            return;
        }
        requestRecycle(targets, outputTarget);
    }

    private boolean shouldConfirmCounterpartRecycle(List<QueueTask> tasks, boolean outputTarget) {
        for (QueueTask task : tasks) {
            boolean counterpartRecycled = outputTarget ? task.isOriginalRecycled() : task.isOutputRecycled();
            boolean alreadyRecycled = outputTarget ? task.isOutputRecycled() : task.isOriginalRecycled();
            Uri targetUri = outputTarget ? task.getCompressedAssetUri() : task.getMedia().getUri();
            if (counterpartRecycled && !alreadyRecycled && targetUri != null) {
                return true;
            }
        }
        return false;
    }

    // 原图和压缩图通常保留其一；当另一份已回收时，继续回收前先明确提醒用户。
    private void showCounterpartRecycleConfirmDialog(List<QueueTask> tasks, boolean outputTarget) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.queue_recycle_counterpart_confirm_title)
                .setMessage(outputTarget
                        ? R.string.queue_recycle_counterpart_confirm_output_message
                        : R.string.queue_recycle_counterpart_confirm_original_message)
                .setNegativeButton(R.string.queue_recycle_counterpart_confirm_cancel, null)
                .setPositiveButton(R.string.queue_recycle_counterpart_confirm_action,
                        (dialog, which) -> requestRecycle(tasks, outputTarget))
                .show();
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

    // 清空已完成任务前增加一次确认，避免误触直接清空记录列表。
    private void showClearCompletedConfirmDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.queue_completed_clear_confirm_title)
                .setMessage(R.string.queue_completed_clear_confirm_message)
                .setNegativeButton(R.string.queue_completed_clear_confirm_cancel, null)
                .setPositiveButton(R.string.queue_completed_clear_confirm_action, (dialog, which) -> {
                    queueManager.clearCompletedTasks();
                    showMainPage();
                })
                .show();
    }

    private void showCompletedPage() {
        java.util.List<QueueTask> completedTasks = queueManager.getCompletedTasks();
        if (completedTasks.isEmpty()) {
            Toast.makeText(requireContext(), R.string.queue_completed_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // 在当前 Fragment 内切换到完整二级页面，保留左上角返回入口。
        mainPage.setVisibility(View.GONE);
        previewPage.setVisibility(View.GONE);
        completedPage.setVisibility(View.VISIBLE);
    }

    private void showMainPage() {
        if (mainPage == null || completedPage == null || previewPage == null) {
            return;
        }
        previewPage.setVisibility(View.GONE);
        completedPage.setVisibility(View.GONE);
        mainPage.setVisibility(View.VISIBLE);
        previewTask = null;
    }

    private void syncPreviewZoom(ZoomImageView target, ZoomImageView.ZoomState state) {
        if (syncingPreviewZoom || target == null) {
            return;
        }
        syncingPreviewZoom = true;
        try {
            // 原图和压缩图尺寸可能不同，具体位置换算交给 ZoomImageView 统一处理。
            target.applyZoomState(state);
        } finally {
            syncingPreviewZoom = false;
        }
    }

    private void showPreviewPage(QueueTask task) {
        if (mainPage == null || previewPage == null || completedPage == null || previewOriginalImage == null
                || previewCompressedImage == null || previewOriginalInfoText == null || previewCompressedInfoText == null
                || previewRecycleOriginalButton == null || previewRecycleOutputButton == null || previewRecompressButton == null) {
            return;
        }
        previewTask = task;
        Uri originalUri = resolvePreviewOriginalUri(task);
        Uri compressedUri = task.getCompressedAssetUri();
        mainPage.setVisibility(View.GONE);
        completedPage.setVisibility(View.GONE);
        previewPage.setVisibility(View.VISIBLE);
        previewOriginalImage.setImageURI(originalUri);
        // 部分异常任务可能没有压缩结果，进入详情页时要避免复用上一张压缩图。
        previewCompressedImage.setImageURI(compressedUri);
        previewOriginalInfoText.setText(buildOriginalInfo(task));
        previewCompressedInfoText.setText(buildCompressedInfo(task));
        bindPreviewActions(task);
    }

    private void bindPreviewActions(QueueTask task) {
        boolean isVideo = task.getMedia().getKind() == MediaKind.VIDEO;
        previewRecycleOriginalButton.setText(isVideo
                ? R.string.queue_recycle_original_video
                : R.string.queue_recycle_original_image);
        previewRecycleOutputButton.setText(isVideo
                ? R.string.queue_recycle_compressed_video
                : R.string.queue_recycle_compressed_image);
        if (task.isOriginalRecycled()) {
            previewRecycleOriginalButton.setText(isVideo
                    ? R.string.queue_recycled_original_video
                    : R.string.queue_recycled_original_image);
        }
        if (task.isOutputRecycled()) {
            previewRecycleOutputButton.setText(isVideo
                    ? R.string.queue_recycled_compressed_video
                    : R.string.queue_recycled_compressed_image);
        }
        previewRecycleOriginalButton.setEnabled(!task.isOriginalRecycled());
        previewRecycleOutputButton.setEnabled(!task.isOutputRecycled() && task.getCompressedAssetUri() != null);
        previewRecompressButton.setEnabled(task.getAction() == QueueAction.COMPRESS && !task.isOriginalRecycled());
    }

    private Uri resolvePreviewOriginalUri(QueueTask task) {
        // 优先展示“压缩对照”相册里的原始版本副本，避免原始相册 URI 因云端占位或权限时序而空白。
        return task.getOriginalAssetUri() == null ? task.getMedia().getUri() : task.getOriginalAssetUri();
    }

    private boolean containsTask(List<QueueTask> tasks, String taskId) {
        for (QueueTask task : tasks) {
            if (task.getId().equals(taskId)) {
                return true;
            }
        }
        return false;
    }

    private String buildOriginalInfo(QueueTask task) {
        int width = task.getMedia().getWidth();
        int height = task.getMedia().getHeight();
        return getString(R.string.completed_compare_original) + "\n"
                + getString(R.string.completed_preview_size) + "：" + FormatUtils.formatSize(task.getMedia().getSizeBytes()) + "\n"
                + getString(R.string.completed_preview_resolution) + "：" + formatResolution(width, height);
    }

    private String buildCompressedInfo(QueueTask task) {
        Uri compressedUri = task.getCompressedAssetUri();
        if (compressedUri == null || task.getActualOutputBytes() <= 0L) {
            return getString(R.string.completed_compare_compressed) + "\n"
                    + getString(R.string.queue_recycle_no_target);
        }
        ImageBounds bounds = readImageBounds(compressedUri);
        return getString(R.string.completed_compare_compressed) + "\n"
                + getString(R.string.completed_preview_size) + "：" + FormatUtils.formatSize(task.getActualOutputBytes()) + "\n"
                + getString(R.string.completed_preview_resolution) + "：" + formatResolution(bounds.width, bounds.height);
    }

    private ImageBounds readImageBounds(Uri uri) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri)) {
            if (inputStream != null) {
                BitmapFactory.decodeStream(inputStream, null, options);
            }
        } catch (Exception ignored) {
            // 读取压缩图参数失败时显示占位符，不影响图片本身展示。
        }
        return new ImageBounds(Math.max(options.outWidth, 0), Math.max(options.outHeight, 0));
    }

    private String formatResolution(int width, int height) {
        if (width <= 0 || height <= 0) {
            return "--";
        }
        return width + " x " + height;
    }

    private static class ImageBounds {
        private final int width;
        private final int height;

        private ImageBounds(int width, int height) {
            this.width = width;
            this.height = height;
        }
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
        MaterialCardView lightOption = dialogView.findViewById(R.id.preset_light_option);
        MaterialCardView strongOption = dialogView.findViewById(R.id.preset_strong_option);
        RadioButton lightRadio = dialogView.findViewById(R.id.preset_light_radio);
        RadioButton strongRadio = dialogView.findViewById(R.id.preset_strong_radio);
        Button closeButton = dialogView.findViewById(R.id.preset_close_button);
        boolean strongSelected = task.getSettings().getPreset() == CompressionPreset.STRONG;
        bindCompressionPresetSelection(lightOption, strongOption, lightRadio, strongRadio, strongSelected);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .create();

        lightOption.setOnClickListener(v -> {
            bindCompressionPresetSelection(lightOption, strongOption, lightRadio, strongRadio, false);
            queueManager.updateTaskSettings(task.getId(), CompressionSettings.light());
            dialog.dismiss();
        });
        strongOption.setOnClickListener(v -> {
            bindCompressionPresetSelection(lightOption, strongOption, lightRadio, strongRadio, true);
            queueManager.updateTaskSettings(task.getId(), CompressionSettings.strong());
            dialog.dismiss();
        });
        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // 用卡片边框和单选按钮同步展示当前选中的压缩档位。
    private void bindCompressionPresetSelection(
            MaterialCardView lightOption,
            MaterialCardView strongOption,
            RadioButton lightRadio,
            RadioButton strongRadio,
            boolean strongSelected
    ) {
        int selectedStroke = ContextCompat.getColor(requireContext(), R.color.yaya_primary);
        int unselectedStroke = resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant);
        lightRadio.setChecked(!strongSelected);
        strongRadio.setChecked(strongSelected);
        lightOption.setStrokeColor(strongSelected ? unselectedStroke : selectedStroke);
        lightOption.setStrokeWidth(strongSelected ? dpToPx(1) : dpToPx(2));
        strongOption.setStrokeColor(strongSelected ? selectedStroke : unselectedStroke);
        strongOption.setStrokeWidth(strongSelected ? dpToPx(2) : dpToPx(1));
    }

    private int resolveThemeColor(int attrResId) {
        android.util.TypedValue value = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attrResId, value, true);
        return value.data;
    }

    private int dpToPx(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
