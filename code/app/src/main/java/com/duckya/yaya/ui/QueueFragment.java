package com.duckya.yaya.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

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
import com.duckya.yaya.model.VideoCodecOption;
import com.duckya.yaya.model.VideoCompressionPreset;
import com.duckya.yaya.model.VideoCompressionSettings;
import com.duckya.yaya.model.VideoFrameRateOption;
import com.duckya.yaya.model.VideoResolutionOption;
import com.duckya.yaya.queue.QueueChangeListener;
import com.duckya.yaya.queue.QueueManager;
import com.duckya.yaya.util.FormatUtils;
import com.duckya.yaya.util.MediaTrashManager;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 这个文件是“任务队列”页面的核心控制器，负责展示待处理任务、已完成任务、预览详情和回收交互。
 * 输入是 QueueManager 提供的任务数据、用户点击开始暂停清空回收等操作，以及系统回收授权结果。
 * 处理过程是根据任务状态刷新三个子页面，分发图片和视频设置弹窗，发起系统回收请求，并处理预览加载。
 * 输出是任务队列页的界面展示、任务状态更新，以及与系统相册回收授权相关的反馈结果。
 */
public class QueueFragment extends Fragment implements QueueChangeListener {
    private static final long ONE_GB_BYTES = 1024L * 1024L * 1024L;

    private static final String TAG = "DuckyaPreview";
    private static final int PREVIEW_MAX_LONG_SIDE = 3072;
    private static final long PREVIEW_MAX_BITMAP_BYTES = 48L * 1024L * 1024L;

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
    private TextView previewOriginalErrorText;
    private TextView previewCompressedErrorText;
    private ZoomImageView previewOriginalImage;
    private ZoomImageView previewCompressedImage;
    private VideoView previewOriginalVideo;
    private VideoView previewCompressedVideo;
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
    private final ExecutorService previewImageExecutor = Executors.newFixedThreadPool(2);
    private PendingRecycleRequest pendingRecycleRequest;
    private QueueTask previewTask;
    private int previewLoadGeneration;
    private boolean restoreMainPageOnNextViewReady;

    private final ActivityResultLauncher<IntentSenderRequest> trashRequestLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (pendingRecycleRequest == null) {
                    return;
                }
                PendingRecycleRequest request = pendingRecycleRequest;
                pendingRecycleRequest = null;
                if (result.getResultCode() != Activity.RESULT_OK) {
                    if (request.deleteQueueRequest) {
                        queueManager.failRecycleTasks(
                                request.taskIds,
                                getString(R.string.queue_recycle_cancelled)
                        );
                    }
                    Toast.makeText(requireContext(), R.string.queue_recycle_cancelled, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (request.deleteQueueRequest) {
                    queueManager.completeRecycleTasks(request.taskIds);
                    queueManager.startIfHasPendingTasks();
                } else if (request.outputTarget) {
                    queueManager.markOutputRecycled(request.taskIds);
                } else {
                    queueManager.markOriginalRecycled(request.taskIds);
                }
                Toast.makeText(requireContext(), R.string.queue_recycle_done, Toast.LENGTH_SHORT).show();
            });

    @Nullable
    @Override
    /**
     * 这个函数用于创建任务队列页的根视图。
     * 输入是布局加载参数 inflater、container 和 savedInstanceState。
     * 输出是 fragment_queue.xml 对应的页面 View。
     */
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_queue, container, false);
    }

    @Override
    /**
     * 这个函数用于初始化任务队列页、已完成页和预览页的全部控件与监听。
     * 输入是根 View 和可选的 savedInstanceState。
     * 输出是可以响应队列变化和用户操作的完整任务页面。
     */
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
        previewOriginalErrorText = view.findViewById(R.id.queue_preview_original_error);
        previewCompressedErrorText = view.findViewById(R.id.queue_preview_compressed_error);
        previewOriginalImage = view.findViewById(R.id.queue_preview_original_image);
        previewCompressedImage = view.findViewById(R.id.queue_preview_compressed_image);
        previewOriginalVideo = view.findViewById(R.id.queue_preview_original_video);
        previewCompressedVideo = view.findViewById(R.id.queue_preview_compressed_video);
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
        clearButton.setOnClickListener(v -> showClearQueueConfirmDialog());
        startButton.setOnClickListener(v -> {
            if (queueManager.isRunning()) {
                queueManager.toggleRunning();
                return;
            }
            startQueueWithRecycleCheck();
        });
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
    /**
     * 这个函数用于在页面销毁时移除监听并释放 View 引用。
     * 输入是无。
     * 输出是清理页面相关资源，避免内存泄漏和过期回调。
     */
    public void onDestroyView() {
        super.onDestroyView();
        queueManager.removeListener(this);
        previewLoadGeneration++;
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
        previewOriginalErrorText = null;
        previewCompressedErrorText = null;
        if (previewOriginalImage != null) {
            previewOriginalImage.setOnZoomStateChangeListener(null);
        }
        if (previewCompressedImage != null) {
            previewCompressedImage.setOnZoomStateChangeListener(null);
        }
        if (previewOriginalVideo != null) {
            previewOriginalVideo.stopPlayback();
        }
        if (previewCompressedVideo != null) {
            previewCompressedVideo.stopPlayback();
        }
        previewOriginalImage = null;
        previewCompressedImage = null;
        previewOriginalVideo = null;
        previewCompressedVideo = null;
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
    /**
     * 这个函数用于在 Fragment 生命周期结束时关闭预览线程池。
     * 输入是无。
     * 输出是停止后台图片预览任务。
     */
    public void onDestroy() {
        super.onDestroy();
        previewImageExecutor.shutdownNow();
    }

    @Override
    /**
     * 这个函数用于接收队列变化通知并切回主线程刷新界面。
     * 输入是无。
     * 输出是触发一次页面刷新。
     */
    public void onQueueChanged() {
        if (getActivity() == null) {
            return;
        }
        requireActivity().runOnUiThread(this::refreshQueue);
    }

    /**
     * 这个函数用于让底部导航重新进入任务队列时回到主队列首页。
     * 输入是无。
     * 输出是显示包含“清空”和“开始”的主页面。
     */
    // 底部导航再次进入任务队列时，总是回到带“清空”和“开始”的主页面。
    public void showQueueRootPage() {
        if (mainPage == null || completedPage == null || previewPage == null) {
            restoreMainPageOnNextViewReady = true;
            previewTask = null;
            return;
        }
        restoreMainPageOnNextViewReady = false;
        showMainPage();
    }

    /**
     * 这个函数用于从队列管理器读取最新数据并刷新整个任务页面。
     * 输入是当前队列状态和页面引用。
     * 输出是更新主队列、已完成列表、预览按钮和统计信息。
     */
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
        if (restoreMainPageOnNextViewReady) {
            showMainPage();
            restoreMainPageOnNextViewReady = false;
        }
        adapter.submitList(activeTasks);
        completedAdapter.submitList(completedTasks);
        savedText.setText(FormatUtils.formatSize(queueManager.actualSavedBytes()));
        remainingText.setText(activeTasks.isEmpty()
                ? "--"
                : formatRemainingEstimate());
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
        requestPendingQueueRecycleIfNeeded();
    }

    /**
     * 这个函数用于格式化当前队列预计剩余时间。
     * 输入是队列管理器返回的预计毫秒值。
     * 输出是适合界面显示的时间字符串。
     */
    private String formatRemainingEstimate() {
        long remainingTimeMs = queueManager.estimatedRemainingTimeMs();
        if (remainingTimeMs < 0L) {
            return "--";
        }
        return FormatUtils.formatDuration(remainingTimeMs);
    }

    /**
     * 这个函数用于创建任务卡片的点击事件回调集合。
     * 输入是无。
     * 输出是适配器使用的监听器实现。
     */
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

    /**
     * 这个函数用于判断已完成任务里是否还存在可回收原文件。
     * 输入是已完成任务列表。
     * 输出是是否至少存在一个未回收原文件。
     */
    private boolean hasRecyclableOriginal(List<QueueTask> tasks) {
        for (QueueTask task : tasks) {
            if (!task.isOriginalRecycled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个函数用于在真正开始压缩前先检查是否存在待授权的回收任务。
     * 输入是无。
     * 输出是先发起回收授权或直接启动剩余任务。
     */
    private void startQueueWithRecycleCheck() {
        List<QueueTask> recycleTasks = collectPendingRecycleQueueTasks();
        if (!recycleTasks.isEmpty()) {
            requestDeleteQueueRecycle(recycleTasks);
            return;
        }
        queueManager.startIfHasPendingTasks();
    }

    /**
     * 这个函数用于批量回收所有已完成任务中的原文件。
     * 输入是无。
     * 输出是发起回收确认或直接请求系统回收授权。
     */
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

    /**
     * 这个函数用于回收某个任务的原文件或压缩产物。
     * 输入是目标任务和是否回收压缩产物的标记。
     * 输出是发起确认弹窗或系统回收请求。
     */
    private void recycleTaskMedia(QueueTask task, boolean outputTarget) {
        List<QueueTask> targets = new ArrayList<>();
        targets.add(task);
        if (shouldConfirmCounterpartRecycle(targets, outputTarget)) {
            showCounterpartRecycleConfirmDialog(targets, outputTarget);
            return;
        }
        requestRecycle(targets, outputTarget);
    }

    /**
     * 这个函数用于判断是否需要在回收前提醒“另一份已经回收”。
     * 输入是目标任务列表和回收目标类型。
     * 输出是是否需要额外确认弹窗。
     */
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

    /**
     * 这个函数用于展示继续回收前的风险确认弹窗。
     * 输入是目标任务列表和回收目标类型。
     * 输出是由用户决定是否继续发起回收请求。
     */
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

    /**
     * 这个函数用于整理待回收 Uri 并发起系统回收授权请求。
     * 输入是目标任务列表和回收目标类型。
     * 输出是系统授权弹窗或失败提示。
     */
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

    /**
     * 这个函数用于检测队列里是否存在等待授权的删除任务，并及时发起系统弹窗。
     * 输入是无。
     * 输出是删除任务的回收授权流程或失败回写。
     */
    private void requestPendingQueueRecycleIfNeeded() {
        if (pendingRecycleRequest != null || !isAdded()) {
            return;
        }
        List<QueueTask> waitingTasks = queueManager.getWaitingRecycleTasks();
        if (waitingTasks.isEmpty()) {
            return;
        }
        if (!trashManager.isTrashRequestSupported()) {
            List<String> taskIds = collectTaskIds(waitingTasks);
            queueManager.failRecycleTasks(taskIds, getString(R.string.queue_recycle_pending));
            Toast.makeText(requireContext(), R.string.queue_recycle_pending, Toast.LENGTH_SHORT).show();
            return;
        }
        List<Uri> uris = new ArrayList<>();
        List<String> taskIds = new ArrayList<>();
        for (QueueTask task : waitingTasks) {
            Uri targetUri = task.getMedia().getUri();
            if (targetUri == null) {
                continue;
            }
            uris.add(targetUri);
            taskIds.add(task.getId());
        }
        if (uris.isEmpty()) {
            queueManager.failRecycleTasks(collectTaskIds(waitingTasks), getString(R.string.queue_recycle_no_target));
            Toast.makeText(requireContext(), R.string.queue_recycle_no_target, Toast.LENGTH_SHORT).show();
            return;
        }
        PendingIntent pendingIntent = trashManager.createTrashRequest(requireContext(), uris);
        if (pendingIntent == null) {
            queueManager.failRecycleTasks(taskIds, getString(R.string.queue_recycle_pending));
            Toast.makeText(requireContext(), R.string.queue_recycle_pending, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingRecycleRequest = PendingRecycleRequest.forDeleteQueue(taskIds);
        try {
            trashRequestLauncher.launch(new IntentSenderRequest.Builder(pendingIntent.getIntentSender()).build());
        } catch (RuntimeException e) {
            pendingRecycleRequest = null;
            queueManager.failRecycleTasks(taskIds, getString(R.string.queue_recycle_pending));
            Toast.makeText(requireContext(), R.string.queue_recycle_pending, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 这个函数用于收集主队列中还未完成授权的删除任务。
     * 输入是无。
     * 输出是待处理的删除任务列表。
     */
    private List<QueueTask> collectPendingRecycleQueueTasks() {
        List<QueueTask> recycleTasks = new ArrayList<>();
        for (QueueTask task : queueManager.getActiveTasks()) {
            if (task.getAction() != QueueAction.DELETE) {
                continue;
            }
            if (task.getStatus() == com.duckya.yaya.model.QueueStatus.PENDING
                    || task.getStatus() == com.duckya.yaya.model.QueueStatus.WAITING_RECYCLE_CONFIRM) {
                recycleTasks.add(task);
            }
        }
        return recycleTasks;
    }

    /**
     * 这个函数用于把删除任务切换到等待授权状态并触发回收请求。
     * 输入是待回收的删除任务列表。
     * 输出是更新任务状态并尝试申请系统授权。
     */
    private void requestDeleteQueueRecycle(List<QueueTask> tasks) {
        List<String> taskIds = new ArrayList<>();
        for (QueueTask task : tasks) {
            taskIds.add(task.getId());
        }
        queueManager.prepareRecycleTasks(taskIds);
        requestPendingQueueRecycleIfNeeded();
    }

    /**
     * 这个函数用于把任务对象列表转换成任务 id 列表。
     * 输入是任务列表。
     * 输出是对应的任务 id 集合。
     */
    private List<String> collectTaskIds(List<QueueTask> tasks) {
        List<String> taskIds = new ArrayList<>();
        for (QueueTask task : tasks) {
            taskIds.add(task.getId());
        }
        return taskIds;
    }

    /**
     * 这个函数用于显示清空主队列前的确认弹窗。
     * 输入是无。
     * 输出是用户确认后清空未完成任务。
     */
    // 清空主队列会移除待处理、处理中和失败任务，先确认可以减少误触损失。
    private void showClearQueueConfirmDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.queue_clear_confirm_title)
                .setMessage(R.string.queue_clear_confirm_message)
                .setNegativeButton(R.string.queue_clear_confirm_cancel, null)
                .setPositiveButton(R.string.queue_clear_confirm_action,
                        (dialog, which) -> queueManager.clear())
                .show();
    }

    /**
     * 这个函数用于显示清空已完成任务前的确认弹窗。
     * 输入是无。
     * 输出是用户确认后清空已完成记录并返回主页面。
     */
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

    /**
     * 这个函数用于切换显示已完成任务二级页面。
     * 输入是无。
     * 输出是显示已完成任务列表；如果没有任务则提示用户。
     */
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

    /**
     * 这个函数用于显示主任务队列页面。
     * 输入是无。
     * 输出是隐藏已完成页和预览页，回到主队列页。
     */
    private void showMainPage() {
        if (mainPage == null || completedPage == null || previewPage == null) {
            return;
        }
        previewPage.setVisibility(View.GONE);
        completedPage.setVisibility(View.GONE);
        mainPage.setVisibility(View.VISIBLE);
        previewTask = null;
    }

    /**
     * 这个函数用于同步原图预览和压缩图预览的缩放状态。
     * 输入是目标缩放视图和源视图的缩放状态。
     * 输出是两个对比预览保持联动缩放。
     */
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

    /**
     * 这个函数用于进入某个已完成任务的预览详情页。
     * 输入是被预览的任务对象。
     * 输出是加载图片或视频预览，并显示原始信息与压缩信息。
     */
    private void showPreviewPage(QueueTask task) {
        if (mainPage == null || previewPage == null || completedPage == null || previewOriginalImage == null
                || previewCompressedImage == null || previewOriginalInfoText == null || previewCompressedInfoText == null
                || previewOriginalVideo == null || previewCompressedVideo == null
                || previewOriginalErrorText == null || previewCompressedErrorText == null
                || previewRecycleOriginalButton == null || previewRecycleOutputButton == null || previewRecompressButton == null) {
            return;
        }
        previewTask = task;
        int loadGeneration = ++previewLoadGeneration;
        Uri originalUri = resolvePreviewOriginalUri(task);
        Uri compressedUri = task.getCompressedAssetUri();
        mainPage.setVisibility(View.GONE);
        completedPage.setVisibility(View.GONE);
        previewPage.setVisibility(View.VISIBLE);
        if (task.getMedia().getKind() == MediaKind.VIDEO) {
            bindVideoPreview(previewOriginalVideo, previewOriginalImage, previewOriginalErrorText, originalUri);
            bindVideoPreview(previewCompressedVideo, previewCompressedImage, previewCompressedErrorText, compressedUri);
        } else {
            bindImagePreviewMode();
            loadPreviewImage(loadGeneration, previewOriginalImage, previewOriginalErrorText, originalUri, "original");
            // 部分异常任务可能没有压缩结果，进入详情页时要避免复用上一张压缩图。
            loadPreviewImage(loadGeneration, previewCompressedImage, previewCompressedErrorText, compressedUri, "compressed");
        }
        previewOriginalInfoText.setText(buildOriginalInfo(task));
        previewCompressedInfoText.setText(buildCompressedInfo(task));
        bindPreviewActions(task);
    }

    /**
     * 这个函数用于把预览页切换到图片对比模式。
     * 输入是无。
     * 输出是隐藏视频控件并显示双图预览控件。
     */
    private void bindImagePreviewMode() {
        previewOriginalVideo.stopPlayback();
        previewCompressedVideo.stopPlayback();
        previewOriginalVideo.setVisibility(View.GONE);
        previewCompressedVideo.setVisibility(View.GONE);
        previewOriginalImage.setVisibility(View.VISIBLE);
        previewCompressedImage.setVisibility(View.VISIBLE);
    }

    /**
     * 这个函数用于绑定单个视频预览控件。
     * 输入是目标 VideoView、占位图片控件、错误提示控件和视频 Uri。
     * 输出是可循环播放的视频预览或错误提示。
     */
    private void bindVideoPreview(VideoView videoView, ZoomImageView imageView, TextView errorText, @Nullable Uri uri) {
        imageView.setImageBitmap(null);
        imageView.setVisibility(View.GONE);
        videoView.stopPlayback();
        videoView.setVisibility(View.VISIBLE);
        errorText.setVisibility(View.GONE);
        if (uri == null) {
            errorText.setVisibility(View.VISIBLE);
            return;
        }
        MediaController controller = new MediaController(requireContext());
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setVideoURI(uri);
        videoView.setOnPreparedListener(player -> {
            player.setLooping(true);
            videoView.seekTo(1);
        });
        videoView.setOnErrorListener((player, what, extra) -> {
            errorText.setVisibility(View.VISIBLE);
            return true;
        });
    }

    /**
     * 这个函数用于根据任务状态刷新预览页上的按钮文案和可用状态。
     * 输入是当前预览任务。
     * 输出是原文件回收、压缩产物回收、重新压缩按钮的最新状态。
     */
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

    /**
     * 这个函数用于解析预览页原始文件应该展示的 Uri。
     * 输入是当前任务。
     * 输出是优先使用对照相册副本、否则回退到原始媒体 Uri。
     */
    private Uri resolvePreviewOriginalUri(QueueTask task) {
        // 优先展示“压缩对照”相册里的原始版本副本，避免原始相册 URI 因云端占位或权限时序而空白。
        if (task.getOriginalAssetUri() != null) {
            return task.getOriginalAssetUri();
        }
        Uri recoveredUri = findComparisonOriginalUri(task);
        if (recoveredUri != null) {
            queueManager.rememberOriginalAssetUri(task.getId(), recoveredUri);
            return recoveredUri;
        }
        return task.getMedia().getUri();
    }

    /**
     * 这个函数用于异步加载图片预览并回到主线程更新 UI。
     * 输入是当前加载代次、目标图片控件、错误控件、图片 Uri 和调试标签。
     * 输出是预览位图显示结果或错误提示。
     */
    private void loadPreviewImage(
            int generation,
            ZoomImageView imageView,
            TextView errorText,
            @Nullable Uri uri,
            String label
    ) {
        imageView.setImageBitmap(null);
        errorText.setVisibility(View.GONE);
        if (uri == null) {
            errorText.setVisibility(View.VISIBLE);
            return;
        }
        ContentResolver resolver = requireContext().getApplicationContext().getContentResolver();
        previewImageExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decodeSampledPreviewBitmap(resolver, uri, label);
            } catch (RuntimeException e) {
                Log.w(TAG, "preview decode failed, label=" + label + ", uri=" + uri, e);
            }
            Bitmap decodedBitmap = bitmap;
            imageView.post(() -> {
                if (generation != previewLoadGeneration || imageView == null) {
                    if (decodedBitmap != null) {
                        decodedBitmap.recycle();
                    }
                    return;
                }
                if (decodedBitmap == null) {
                    errorText.setVisibility(View.VISIBLE);
                    return;
                }
                imageView.setImageBitmap(decodedBitmap);
                errorText.setVisibility(View.GONE);
            });
        });
    }

    @Nullable
    /**
     * 这个函数用于按采样率解码适合预览的大图 Bitmap。
     * 输入是 ContentResolver、图片 Uri 和调试标签。
     * 输出是预览用 Bitmap；解码失败时返回 null。
     */
    private Bitmap decodeSampledPreviewBitmap(ContentResolver resolver, Uri uri, String label) {
        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        try (InputStream boundsStream = resolver.openInputStream(uri)) {
            if (boundsStream == null) {
                Log.w(TAG, "preview bounds stream is null, label=" + label + ", uri=" + uri);
                return null;
            }
            BitmapFactory.decodeStream(boundsStream, null, boundsOptions);
        } catch (Exception e) {
            Log.w(TAG, "preview bounds failed, label=" + label + ", uri=" + uri, e);
            return null;
        }
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            Log.w(TAG, "preview invalid bounds, label=" + label + ", uri=" + uri);
            return null;
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = calculatePreviewSampleSize(boundsOptions.outWidth, boundsOptions.outHeight);
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream decodeStream = resolver.openInputStream(uri)) {
            if (decodeStream == null) {
                Log.w(TAG, "preview decode stream is null, label=" + label + ", uri=" + uri);
                return null;
            }
            Bitmap bitmap = BitmapFactory.decodeStream(decodeStream, null, decodeOptions);
            Log.d(TAG, "preview decoded label=" + label
                    + ", source=" + boundsOptions.outWidth + "x" + boundsOptions.outHeight
                    + ", sample=" + decodeOptions.inSampleSize
                    + ", result=" + (bitmap == null ? "null" : bitmap.getWidth() + "x" + bitmap.getHeight()));
            return bitmap;
        } catch (Exception e) {
            Log.w(TAG, "preview decode failed, label=" + label + ", uri=" + uri, e);
            return null;
        }
    }

    /**
     * 这个函数用于根据原图尺寸计算预览采样倍率。
     * 输入是原图宽高。
     * 输出是适合预览的 inSampleSize。
     */
    private int calculatePreviewSampleSize(int width, int height) {
        int sampleSize = 1;
        while (Math.max(width / sampleSize, height / sampleSize) > PREVIEW_MAX_LONG_SIDE
                || ((long) (width / sampleSize) * (long) (height / sampleSize) * 4L) > PREVIEW_MAX_BITMAP_BYTES) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    @Nullable
    /**
     * 这个函数用于从“压缩对照”相册反查图片任务对应的原始副本 Uri。
     * 输入是当前队列任务。
     * 输出是匹配到的原始副本 Uri；找不到时返回 null。
     */
    private Uri findComparisonOriginalUri(QueueTask task) {
        Uri compressedUri = task.getCompressedAssetUri();
        if (compressedUri == null) {
            return null;
        }
        String compressedName = queryDisplayName(compressedUri);
        if (compressedName == null || !compressedName.endsWith("_压缩版本.jpg")) {
            return null;
        }
        String batchPrefix = compressedName.substring(0, compressedName.length() - "_压缩版本.jpg".length());
        ContentResolver resolver = requireContext().getContentResolver();
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
        };
        String selection = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
        String[] args = {batchPrefix + "_原始版本.%"};
        try (Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
        )) {
            if (cursor == null) {
                return null;
            }
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameIndex);
                if (name != null && name.startsWith(batchPrefix + "_原始版本.")) {
                    long id = cursor.getLong(idIndex);
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                }
            }
        } catch (RuntimeException ignored) {
            // 系统相册查询失败时只放弃兜底查找，页面仍会显示明确的故障提示。
        }
        return null;
    }

    @Nullable
    /**
     * 这个函数用于读取指定 Uri 对应媒体的显示名称。
     * 输入是媒体 Uri。
     * 输出是显示名称字符串；读取失败时返回 null。
     */
    private String queryDisplayName(Uri uri) {
        String[] projection = {MediaStore.Images.Media.DISPLAY_NAME};
        try (Cursor cursor = requireContext().getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex);
                }
            }
        } catch (RuntimeException ignored) {
            // 无法读取名称时回退到原始 URI 预览。
        }
        return null;
    }

    /**
     * 这个函数用于判断指定任务是否仍存在于给定列表中。
     * 输入是任务列表和任务 id。
     * 输出是是否存在的布尔值。
     */
    private boolean containsTask(List<QueueTask> tasks, String taskId) {
        for (QueueTask task : tasks) {
            if (task.getId().equals(taskId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个函数用于构建预览页原始文件信息文案。
     * 输入是当前任务。
     * 输出是原文件大小和分辨率的展示字符串。
     */
    private String buildOriginalInfo(QueueTask task) {
        int width = task.getMedia().getWidth();
        int height = task.getMedia().getHeight();
        return getString(R.string.completed_compare_original) + "\n"
                + getString(R.string.completed_preview_size) + "：" + FormatUtils.formatSize(task.getMedia().getSizeBytes()) + "\n"
                + getString(R.string.completed_preview_resolution) + "：" + formatResolution(width, height);
    }

    /**
     * 这个函数用于构建预览页压缩文件信息文案。
     * 输入是当前任务。
     * 输出是压缩文件大小和分辨率的展示字符串。
     */
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

    /**
     * 这个函数用于只读取图片边界信息，不完整解码位图。
     * 输入是图片 Uri。
     * 输出是图片宽高信息对象。
     */
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

    /**
     * 这个函数用于把分辨率宽高转换成界面文案。
     * 输入是宽度和高度。
     * 输出是如“1920 x 1080”的字符串；未知时返回占位符。
     */
    private String formatResolution(int width, int height) {
        if (width <= 0 || height <= 0) {
            return "--";
        }
        return width + " x " + height;
    }

    private static class ImageBounds {
        private final int width;
        private final int height;

        /**
         * 这个构造函数用于保存图片边界信息。
         * 输入是图片宽度和高度。
         * 输出是一个 ImageBounds 对象。
         */
        private ImageBounds(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static class PendingRecycleRequest {
        private final List<String> taskIds;
        private final boolean outputTarget;
        private final boolean deleteQueueRequest;

        /**
         * 这个构造函数用于创建普通回收请求记录。
         * 输入是任务 id 列表和是否回收压缩产物的标记。
         * 输出是一个 PendingRecycleRequest 对象。
         */
        private PendingRecycleRequest(List<String> taskIds, boolean outputTarget) {
            this.taskIds = taskIds;
            this.outputTarget = outputTarget;
            this.deleteQueueRequest = false;
        }

        /**
         * 这个构造函数用于创建完整的回收请求记录。
         * 输入是任务 id 列表、目标类型和是否属于删除队列授权。
         * 输出是一个 PendingRecycleRequest 对象。
         */
        private PendingRecycleRequest(List<String> taskIds, boolean outputTarget, boolean deleteQueueRequest) {
            this.taskIds = taskIds;
            this.outputTarget = outputTarget;
            this.deleteQueueRequest = deleteQueueRequest;
        }

        /**
         * 这个函数用于创建“删除任务队列”专用的回收请求记录。
         * 输入是任务 id 列表。
         * 输出是 deleteQueueRequest 为 true 的 PendingRecycleRequest。
         */
        private static PendingRecycleRequest forDeleteQueue(List<String> taskIds) {
            return new PendingRecycleRequest(taskIds, false, true);
        }
    }

    /**
     * 这个函数用于显示图片任务的压缩档位弹窗。
     * 输入是当前队列任务。
     * 输出是根据用户选择更新图片压缩设置。
     */
    // 用安卓原生小弹窗切换每个图片任务的压缩档位。
    private void showCompressionDialog(QueueTask task) {
        if (task.getAction() != com.duckya.yaya.model.QueueAction.COMPRESS) {
            return;
        }
        if (task.getMedia().getKind() == MediaKind.VIDEO) {
            showVideoCompressionDialog(task);
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

    /**
     * 这个函数用于显示视频任务的压缩设置弹窗。
     * 输入是当前视频任务。
     * 输出是绑定视频参数界面，并在确认后更新任务设置。
     */
    // 视频任务使用独立设置弹窗，避免图片档位和视频参数混在一起。
    private void showVideoCompressionDialog(QueueTask task) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_video_compression, null, false);
        RadioGroup presetGroup = dialogView.findViewById(R.id.video_preset_group);
        View advancedSection = dialogView.findViewById(R.id.video_custom_section);
        RadioGroup resolutionGroup = dialogView.findViewById(R.id.video_resolution_group);
        RadioGroup frameRateGroup = dialogView.findViewById(R.id.video_framerate_group);
        RadioGroup bitrateModeGroup = dialogView.findViewById(R.id.video_bitrate_mode_group);
        RadioButton oneGbOption = dialogView.findViewById(R.id.video_bitrate_mode_one_gb);
        TextView bitrateValueText = dialogView.findViewById(R.id.video_bitrate_value_text);
        Slider bitrateSlider = dialogView.findViewById(R.id.video_bitrate_slider);
        Button closeButton = dialogView.findViewById(R.id.video_settings_close_button);
        Button applyButton = dialogView.findViewById(R.id.video_settings_apply_button);

        bindVideoSettingsToDialog(
                task,
                task.getVideoSettings(),
                presetGroup,
                advancedSection,
                resolutionGroup,
                frameRateGroup,
                bitrateModeGroup,
                oneGbOption,
                bitrateValueText,
                bitrateSlider
        );

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .create();

        presetGroup.setOnCheckedChangeListener((group, checkedId) -> {
            applyVideoPresetDefaults(
                    videoPresetFromId(checkedId),
                    advancedSection,
                    resolutionGroup,
                    frameRateGroup,
                    bitrateModeGroup,
                    bitrateValueText,
                    bitrateSlider
            );
            saveVideoSettingsFromDialog(task, presetGroup, resolutionGroup, frameRateGroup, bitrateModeGroup, bitrateSlider);
        });
        resolutionGroup.setOnCheckedChangeListener((group, checkedId) ->
                saveVideoSettingsFromDialog(task, presetGroup, resolutionGroup, frameRateGroup, bitrateModeGroup, bitrateSlider));
        frameRateGroup.setOnCheckedChangeListener((group, checkedId) ->
                saveVideoSettingsFromDialog(task, presetGroup, resolutionGroup, frameRateGroup, bitrateModeGroup, bitrateSlider));
        bitrateModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean limitToOneGb = isOneGbLimitAllowed(task)
                    && checkedId == R.id.video_bitrate_mode_one_gb;
            if (!limitToOneGb && checkedId == R.id.video_bitrate_mode_one_gb) {
                bitrateModeGroup.check(R.id.video_bitrate_mode_preset);
                return;
            }
            bindBitrateModeUi(limitToOneGb, bitrateValueText, bitrateSlider);
            saveVideoSettingsFromDialog(task, presetGroup, resolutionGroup, frameRateGroup, bitrateModeGroup, bitrateSlider);
        });
        bitrateSlider.addOnChangeListener((slider, value, fromUser) -> {
            bitrateValueText.setText(getString(R.string.video_bitrate_value, value));
            if (fromUser) {
                saveVideoSettingsFromDialog(task, presetGroup, resolutionGroup, frameRateGroup, bitrateModeGroup, bitrateSlider);
            }
        });
        applyButton.setOnClickListener(v -> {
            VideoCompressionSettings settings = collectVideoSettings(
                    task,
                    presetGroup,
                    resolutionGroup,
                    frameRateGroup,
                    bitrateModeGroup,
                    bitrateSlider
            );
            queueManager.updateVideoTaskSettings(task.getId(), settings);
            dialog.dismiss();
        });
        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * 这个函数用于把当前视频设置对象回填到弹窗控件上。
     * 输入是任务、视频设置对象和一组弹窗控件。
     * 输出是完成弹窗默认值、可用状态和文案绑定。
     */
    private void bindVideoSettingsToDialog(
            QueueTask task,
            VideoCompressionSettings settings,
            RadioGroup presetGroup,
            View advancedSection,
            RadioGroup resolutionGroup,
            RadioGroup frameRateGroup,
            RadioGroup bitrateModeGroup,
            RadioButton oneGbOption,
            TextView bitrateValueText,
            Slider bitrateSlider
    ) {
        boolean oneGbAllowed = isOneGbLimitAllowed(task);
        presetGroup.check(idForVideoPreset(settings.getPreset()));
        bindCustomSectionVisibility(advancedSection, settings.getPreset() == VideoCompressionPreset.CUSTOM);
        resolutionGroup.check(idForVideoResolution(settings.getResolutionOption()));
        frameRateGroup.check(idForVideoFrameRate(settings.getFrameRateOption()));
        oneGbOption.setEnabled(oneGbAllowed);
        oneGbOption.setAlpha(oneGbAllowed ? 1f : 0.45f);
        bitrateModeGroup.check(settings.isLimitToOneGb() && oneGbAllowed
                ? R.id.video_bitrate_mode_one_gb
                : R.id.video_bitrate_mode_preset);
        bitrateSlider.setValue(settings.getTargetBitrateMbps());
        bindBitrateModeUi(settings.isLimitToOneGb() && oneGbAllowed, bitrateValueText, bitrateSlider);
        bitrateValueText.setText(getString(R.string.video_bitrate_value, settings.getTargetBitrateMbps()));
    }

    /**
     * 这个函数用于从视频设置弹窗控件中收集最新参数。
     * 输入是任务对象以及分辨率、帧率、码率等控件状态。
     * 输出是新的 VideoCompressionSettings 对象。
     */
    private VideoCompressionSettings collectVideoSettings(
            QueueTask task,
            RadioGroup presetGroup,
            RadioGroup resolutionGroup,
            RadioGroup frameRateGroup,
            RadioGroup bitrateModeGroup,
            Slider bitrateSlider
    ) {
        return new VideoCompressionSettings(
                videoPresetFromId(presetGroup.getCheckedRadioButtonId()),
                videoResolutionFromId(resolutionGroup.getCheckedRadioButtonId()),
                VideoCodecOption.H265,
                bitrateSlider.getValue(),
                true,
                com.duckya.yaya.model.VideoAudioMode.KEEP,
                videoFrameRateFromId(frameRateGroup.getCheckedRadioButtonId()),
                isOneGbLimitAllowed(task)
                        && bitrateModeGroup.getCheckedRadioButtonId() == R.id.video_bitrate_mode_one_gb,
                true
        );
    }

    /**
     * 这个函数用于在用户调整视频弹窗控件时即时保存设置。
     * 输入是任务和当前弹窗控件状态。
     * 输出是把最新设置写回队列任务，并刷新预估信息。
     */
    private void saveVideoSettingsFromDialog(
            QueueTask task,
            RadioGroup presetGroup,
            RadioGroup resolutionGroup,
            RadioGroup frameRateGroup,
            RadioGroup bitrateModeGroup,
            Slider bitrateSlider
    ) {
        VideoCompressionSettings settings = collectVideoSettings(
                task,
                presetGroup,
                resolutionGroup,
                frameRateGroup,
                bitrateModeGroup,
                bitrateSlider
        );
        queueManager.updateVideoTaskSettings(task.getId(), settings);
    }

    /**
     * 这个函数用于判断当前视频任务是否允许启用 1GB 限制。
     * 输入是视频任务。
     * 输出是仅当原视频大于 1GB 时返回 true。
     */
    private boolean isOneGbLimitAllowed(QueueTask task) {
        return task != null && task.getMedia().getSizeBytes() > ONE_GB_BYTES;
    }

    /**
     * 这个函数用于根据选中的视频档位刷新弹窗默认参数。
     * 输入是视频档位以及一组弹窗控件。
     * 输出是同步自定义区显示状态、默认分辨率、帧率和码率模式。
     */
    private void applyVideoPresetDefaults(
            VideoCompressionPreset preset,
            View advancedSection,
            RadioGroup resolutionGroup,
            RadioGroup frameRateGroup,
            RadioGroup bitrateModeGroup,
            TextView bitrateValueText,
            Slider bitrateSlider
    ) {
        VideoCompressionSettings presetSettings;
        if (preset == VideoCompressionPreset.HIGH_QUALITY) {
            presetSettings = VideoCompressionSettings.highQuality();
        } else if (preset == VideoCompressionPreset.SHARE) {
            presetSettings = VideoCompressionSettings.share();
        } else if (preset == VideoCompressionPreset.CUSTOM) {
            bindCustomSectionVisibility(advancedSection, true);
            return;
        } else {
            presetSettings = VideoCompressionSettings.balanced();
        }
        bindCustomSectionVisibility(advancedSection, false);
        resolutionGroup.check(idForVideoResolution(presetSettings.getResolutionOption()));
        frameRateGroup.check(idForVideoFrameRate(presetSettings.getFrameRateOption()));
        bitrateModeGroup.check(R.id.video_bitrate_mode_preset);
        bitrateSlider.setValue(presetSettings.getTargetBitrateMbps());
        bindBitrateModeUi(false, bitrateValueText, bitrateSlider);
        bitrateValueText.setText(getString(R.string.video_bitrate_value, presetSettings.getTargetBitrateMbps()));
    }

    /**
     * 这个函数用于根据码率模式刷新码率文本和滑块是否可编辑。
     * 输入是是否启用 1GB 限制、码率文本控件和滑块控件。
     * 输出是更新后的码率界面状态。
     */
    private void bindBitrateModeUi(boolean limitToOneGb, TextView bitrateValueText, Slider bitrateSlider) {
        bitrateSlider.setEnabled(!limitToOneGb);
        bitrateValueText.setEnabled(!limitToOneGb);
        bitrateValueText.setAlpha(limitToOneGb ? 0.55f : 1f);
        bitrateSlider.setAlpha(limitToOneGb ? 0.55f : 1f);
    }

    /**
     * 这个函数用于控制视频高级自定义参数区的展开和收起。
     * 输入是高级设置区域 View 和是否显示。
     * 输出是高级设置区可见性变化。
     */
    private void bindCustomSectionVisibility(View advancedSection, boolean visible) {
        advancedSection.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * 这个函数用于把视频预设枚举转换成对应的单选按钮 id。
     * 输入是视频档位枚举。
     * 输出是布局中的 RadioButton id。
     */
    private int idForVideoPreset(VideoCompressionPreset preset) {
        if (preset == VideoCompressionPreset.HIGH_QUALITY) {
            return R.id.video_preset_high_quality;
        }
        if (preset == VideoCompressionPreset.SHARE) {
            return R.id.video_preset_share;
        }
        if (preset == VideoCompressionPreset.CUSTOM) {
            return R.id.video_preset_custom;
        }
        return R.id.video_preset_balanced;
    }

    /**
     * 这个函数用于把单选按钮 id 反向解析成视频预设枚举。
     * 输入是 RadioButton id。
     * 输出是对应的视频档位枚举。
     */
    private VideoCompressionPreset videoPresetFromId(int id) {
        if (id == R.id.video_preset_high_quality) {
            return VideoCompressionPreset.HIGH_QUALITY;
        }
        if (id == R.id.video_preset_share) {
            return VideoCompressionPreset.SHARE;
        }
        if (id == R.id.video_preset_custom) {
            return VideoCompressionPreset.CUSTOM;
        }
        return VideoCompressionPreset.BALANCED;
    }

    /**
     * 这个函数用于把视频分辨率枚举转换成对应的单选按钮 id。
     * 输入是分辨率枚举。
     * 输出是布局中的 RadioButton id。
     */
    private int idForVideoResolution(VideoResolutionOption option) {
        if (option == VideoResolutionOption.ORIGINAL) {
            return R.id.video_resolution_original;
        }
        if (option == VideoResolutionOption.P2160) {
            return R.id.video_resolution_2160;
        }
        if (option == VideoResolutionOption.P1440) {
            return R.id.video_resolution_1440;
        }
        if (option == VideoResolutionOption.P720) {
            return R.id.video_resolution_720;
        }
        if (option == VideoResolutionOption.P480) {
            return R.id.video_resolution_480;
        }
        return R.id.video_resolution_1080;
    }

    /**
     * 这个函数用于把单选按钮 id 反向解析成视频分辨率枚举。
     * 输入是 RadioButton id。
     * 输出是对应的分辨率枚举。
     */
    private VideoResolutionOption videoResolutionFromId(int id) {
        if (id == R.id.video_resolution_original) {
            return VideoResolutionOption.ORIGINAL;
        }
        if (id == R.id.video_resolution_2160) {
            return VideoResolutionOption.P2160;
        }
        if (id == R.id.video_resolution_1440) {
            return VideoResolutionOption.P1440;
        }
        if (id == R.id.video_resolution_720) {
            return VideoResolutionOption.P720;
        }
        if (id == R.id.video_resolution_480) {
            return VideoResolutionOption.P480;
        }
        return VideoResolutionOption.P1080;
    }

    /**
     * 这个函数用于把视频帧率枚举转换成对应的单选按钮 id。
     * 输入是帧率枚举。
     * 输出是布局中的 RadioButton id。
     */
    private int idForVideoFrameRate(VideoFrameRateOption option) {
        if (option == VideoFrameRateOption.FPS60) {
            return R.id.video_framerate_60;
        }
        if (option == VideoFrameRateOption.FPS24) {
            return R.id.video_framerate_24;
        }
        if (option == VideoFrameRateOption.FPS30) {
            return R.id.video_framerate_30;
        }
        return R.id.video_framerate_original;
    }

    /**
     * 这个函数用于把单选按钮 id 反向解析成视频帧率枚举。
     * 输入是 RadioButton id。
     * 输出是对应的帧率枚举。
     */
    private VideoFrameRateOption videoFrameRateFromId(int id) {
        if (id == R.id.video_framerate_60) {
            return VideoFrameRateOption.FPS60;
        }
        if (id == R.id.video_framerate_24) {
            return VideoFrameRateOption.FPS24;
        }
        if (id == R.id.video_framerate_30) {
            return VideoFrameRateOption.FPS30;
        }
        return VideoFrameRateOption.ORIGINAL;
    }

    /**
     * 这个函数用于统一设置图片压缩档位弹窗的选中样式。
     * 输入是两个选项卡片、两个单选按钮以及是否选中强压缩。
     * 输出是更新后的卡片描边、背景和单选状态。
     */
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

    /**
     * 这个函数用于从当前主题中读取颜色值。
     * 输入是主题属性 id。
     * 输出是解析后的颜色整数值。
     */
    private int resolveThemeColor(int attrResId) {
        android.util.TypedValue value = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attrResId, value, true);
        return value.data;
    }

    /**
     * 这个函数用于把 dp 单位转换成像素值。
     * 输入是 dp 数值。
     * 输出是对应的像素整数值。
     */
    private int dpToPx(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
