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
import com.duckya.yaya.model.VideoAudioMode;
import com.duckya.yaya.model.VideoCodecOption;
import com.duckya.yaya.model.VideoCompressionPreset;
import com.duckya.yaya.model.VideoCompressionSettings;
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

public class QueueFragment extends Fragment implements QueueChangeListener {
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
    public void onDestroy() {
        super.onDestroy();
        previewImageExecutor.shutdownNow();
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

    private void bindImagePreviewMode() {
        previewOriginalVideo.stopPlayback();
        previewCompressedVideo.stopPlayback();
        previewOriginalVideo.setVisibility(View.GONE);
        previewCompressedVideo.setVisibility(View.GONE);
        previewOriginalImage.setVisibility(View.VISIBLE);
        previewCompressedImage.setVisibility(View.VISIBLE);
    }

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

    private int calculatePreviewSampleSize(int width, int height) {
        int sampleSize = 1;
        while (Math.max(width / sampleSize, height / sampleSize) > PREVIEW_MAX_LONG_SIDE
                || ((long) (width / sampleSize) * (long) (height / sampleSize) * 4L) > PREVIEW_MAX_BITMAP_BYTES) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    @Nullable
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

    // 视频任务使用独立设置弹窗，避免图片档位和视频参数混在一起。
    private void showVideoCompressionDialog(QueueTask task) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_video_compression, null, false);
        RadioGroup presetGroup = dialogView.findViewById(R.id.video_preset_group);
        RadioGroup resolutionGroup = dialogView.findViewById(R.id.video_resolution_group);
        RadioGroup codecGroup = dialogView.findViewById(R.id.video_codec_group);
        RadioGroup audioGroup = dialogView.findViewById(R.id.video_audio_group);
        CheckBox autoBitrateCheck = dialogView.findViewById(R.id.video_auto_bitrate_check);
        CheckBox keepFrameRateCheck = dialogView.findViewById(R.id.video_keep_framerate_check);
        CheckBox fallbackH264Check = dialogView.findViewById(R.id.video_fallback_h264_check);
        TextView bitrateValueText = dialogView.findViewById(R.id.video_bitrate_value_text);
        Slider bitrateSlider = dialogView.findViewById(R.id.video_bitrate_slider);
        Button closeButton = dialogView.findViewById(R.id.video_settings_close_button);
        Button applyButton = dialogView.findViewById(R.id.video_settings_apply_button);

        bindVideoSettingsToDialog(
                task.getVideoSettings(),
                presetGroup,
                resolutionGroup,
                codecGroup,
                audioGroup,
                autoBitrateCheck,
                keepFrameRateCheck,
                fallbackH264Check,
                bitrateValueText,
                bitrateSlider
        );

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .create();

        autoBitrateCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            bitrateSlider.setEnabled(!isChecked);
            bitrateValueText.setEnabled(!isChecked);
        });
        bitrateSlider.addOnChangeListener((slider, value, fromUser) ->
                bitrateValueText.setText(getString(R.string.video_bitrate_value, value)));
        applyButton.setOnClickListener(v -> {
            VideoCompressionSettings settings = collectVideoSettings(
                    presetGroup,
                    resolutionGroup,
                    codecGroup,
                    audioGroup,
                    autoBitrateCheck,
                    keepFrameRateCheck,
                    fallbackH264Check,
                    bitrateSlider
            );
            queueManager.updateVideoTaskSettings(task.getId(), settings);
            dialog.dismiss();
        });
        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void bindVideoSettingsToDialog(
            VideoCompressionSettings settings,
            RadioGroup presetGroup,
            RadioGroup resolutionGroup,
            RadioGroup codecGroup,
            RadioGroup audioGroup,
            CheckBox autoBitrateCheck,
            CheckBox keepFrameRateCheck,
            CheckBox fallbackH264Check,
            TextView bitrateValueText,
            Slider bitrateSlider
    ) {
        presetGroup.check(idForVideoPreset(settings.getPreset()));
        resolutionGroup.check(idForVideoResolution(settings.getResolutionOption()));
        codecGroup.check(idForVideoCodec(settings.getCodecOption()));
        audioGroup.check(idForVideoAudio(settings.getAudioMode()));
        autoBitrateCheck.setChecked(settings.isAutoBitrate());
        keepFrameRateCheck.setChecked(settings.isKeepFrameRate());
        fallbackH264Check.setChecked(settings.isFallbackToH264());
        bitrateSlider.setValue(settings.getTargetBitrateMbps());
        bitrateSlider.setEnabled(!settings.isAutoBitrate());
        bitrateValueText.setEnabled(!settings.isAutoBitrate());
        bitrateValueText.setText(getString(R.string.video_bitrate_value, settings.getTargetBitrateMbps()));
    }

    private VideoCompressionSettings collectVideoSettings(
            RadioGroup presetGroup,
            RadioGroup resolutionGroup,
            RadioGroup codecGroup,
            RadioGroup audioGroup,
            CheckBox autoBitrateCheck,
            CheckBox keepFrameRateCheck,
            CheckBox fallbackH264Check,
            Slider bitrateSlider
    ) {
        return new VideoCompressionSettings(
                videoPresetFromId(presetGroup.getCheckedRadioButtonId()),
                videoResolutionFromId(resolutionGroup.getCheckedRadioButtonId()),
                videoCodecFromId(codecGroup.getCheckedRadioButtonId()),
                bitrateSlider.getValue(),
                autoBitrateCheck.isChecked(),
                videoAudioFromId(audioGroup.getCheckedRadioButtonId()),
                keepFrameRateCheck.isChecked(),
                fallbackH264Check.isChecked()
        );
    }

    private int idForVideoPreset(VideoCompressionPreset preset) {
        if (preset == VideoCompressionPreset.HIGH_QUALITY) {
            return R.id.video_preset_high_quality;
        }
        if (preset == VideoCompressionPreset.SHARE) {
            return R.id.video_preset_share;
        }
        return R.id.video_preset_balanced;
    }

    private VideoCompressionPreset videoPresetFromId(int id) {
        if (id == R.id.video_preset_high_quality) {
            return VideoCompressionPreset.HIGH_QUALITY;
        }
        if (id == R.id.video_preset_share) {
            return VideoCompressionPreset.SHARE;
        }
        return VideoCompressionPreset.BALANCED;
    }

    private int idForVideoResolution(VideoResolutionOption option) {
        if (option == VideoResolutionOption.ORIGINAL) {
            return R.id.video_resolution_original;
        }
        if (option == VideoResolutionOption.P720) {
            return R.id.video_resolution_720;
        }
        if (option == VideoResolutionOption.P480) {
            return R.id.video_resolution_480;
        }
        return R.id.video_resolution_1080;
    }

    private VideoResolutionOption videoResolutionFromId(int id) {
        if (id == R.id.video_resolution_original) {
            return VideoResolutionOption.ORIGINAL;
        }
        if (id == R.id.video_resolution_720) {
            return VideoResolutionOption.P720;
        }
        if (id == R.id.video_resolution_480) {
            return VideoResolutionOption.P480;
        }
        return VideoResolutionOption.P1080;
    }

    private int idForVideoCodec(VideoCodecOption option) {
        if (option == VideoCodecOption.H265) {
            return R.id.video_codec_h265;
        }
        if (option == VideoCodecOption.H264) {
            return R.id.video_codec_h264;
        }
        return R.id.video_codec_auto;
    }

    private VideoCodecOption videoCodecFromId(int id) {
        if (id == R.id.video_codec_h265) {
            return VideoCodecOption.H265;
        }
        if (id == R.id.video_codec_h264) {
            return VideoCodecOption.H264;
        }
        return VideoCodecOption.AUTO;
    }

    private int idForVideoAudio(VideoAudioMode mode) {
        if (mode == VideoAudioMode.REDUCE) {
            return R.id.video_audio_reduce;
        }
        if (mode == VideoAudioMode.MUTE) {
            return R.id.video_audio_mute;
        }
        return R.id.video_audio_keep;
    }

    private VideoAudioMode videoAudioFromId(int id) {
        if (id == R.id.video_audio_reduce) {
            return VideoAudioMode.REDUCE;
        }
        if (id == R.id.video_audio_mute) {
            return VideoAudioMode.MUTE;
        }
        return VideoAudioMode.KEEP;
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
