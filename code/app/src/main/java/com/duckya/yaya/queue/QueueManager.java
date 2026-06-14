package com.duckya.yaya.queue;

import android.content.Context;

import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.QueueAction;
import com.duckya.yaya.model.QueueStatus;
import com.duckya.yaya.model.QueueTask;
import com.duckya.yaya.model.CompressionSettings;
import com.duckya.yaya.model.VideoCompressionSettings;
import com.duckya.yaya.util.ImageCompressionWorker;
import com.duckya.yaya.util.VideoCompressionWorker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QueueManager {
    private static final QueueManager INSTANCE = new QueueManager();
    private static final long MILLIS_PER_SECOND = 1000L;

    private final List<QueueTask> tasks = new ArrayList<>();
    private final List<QueueChangeListener> listeners = new ArrayList<>();
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
    private final ImageCompressionWorker imageCompressionWorker = new ImageCompressionWorker();
    private final VideoCompressionWorker videoCompressionWorker = new VideoCompressionWorker();
    private final QueueTaskStore taskStore = new QueueTaskStore();
    private Context appContext;
    private boolean running;
    private int runToken;
    private long processedCompressionBytes;
    private long processedCompressionDurationMs;
    private String activeCompressionTaskId;
    private long activeCompressionInputBytes;
    private long activeCompressionStartedAtMs;

    public static QueueManager getInstance() {
        return INSTANCE;
    }

    private QueueManager() {
    }

    // 在应用启动时注入 applicationContext，供后台任务长期使用。
    public synchronized void initialize(Context context) {
        appContext = context.getApplicationContext();
        if (tasks.isEmpty()) {
            tasks.addAll(taskStore.load(appContext));
        }
    }

    public synchronized void addTask(MediaItemInfo media, QueueAction action) {
        tasks.add(new QueueTask(media, action));
        notifyListeners();
    }

    public synchronized List<QueueTask> getTasks() {
        return new ArrayList<>(tasks);
    }

    public synchronized List<QueueTask> getActiveTasks() {
        List<QueueTask> result = new ArrayList<>();
        for (QueueTask task : tasks) {
            if (task.getStatus() != QueueStatus.DONE) {
                result.add(task);
            }
        }
        return result;
    }

    public synchronized List<QueueTask> getCompletedTasks() {
        List<QueueTask> result = new ArrayList<>();
        for (QueueTask task : tasks) {
            if (task.getStatus() == QueueStatus.DONE) {
                result.add(task);
            }
        }
        return result;
    }

    public synchronized List<QueueTask> getWaitingRecycleTasks() {
        List<QueueTask> result = new ArrayList<>();
        for (QueueTask task : tasks) {
            if (task.getAction() == QueueAction.DELETE
                    && task.getStatus() == QueueStatus.WAITING_RECYCLE_CONFIRM) {
                result.add(task);
            }
        }
        return result;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void toggleRunning() {
        if (tasks.isEmpty() || appContext == null) {
            return;
        }
        if (running) {
            running = false;
            runToken++;
            resetRunningTasksToPending();
            notifyListeners();
            return;
        }
        running = true;
        int token = ++runToken;
        notifyListeners();
        workerExecutor.execute(() -> processQueue(token));
    }

    public synchronized void clear() {
        running = false;
        runToken++;
        tasks.removeIf(task -> task.getStatus() != QueueStatus.DONE);
        notifyListeners();
    }

    public synchronized void clearCompletedTasks() {
        tasks.removeIf(task -> task.getStatus() == QueueStatus.DONE);
        if (tasks.isEmpty()) {
            running = false;
        }
        notifyListeners();
    }

    public synchronized void removeTask(String taskId) {
        tasks.removeIf(task -> task.getId().equals(taskId));
        runToken++;
        if (tasks.isEmpty()) {
            running = false;
        }
        notifyListeners();
    }

    public synchronized void retryTask(String taskId) {
        for (QueueTask task : tasks) {
            if (task.getId().equals(taskId)) {
                task.setStatus(QueueStatus.PENDING);
                task.setProgress(0f);
                task.setActualOutputBytes(0L);
                // 重新处理时清掉上一轮压缩产物，避免回收按钮指向旧文件。
                task.setOriginalAssetUri(null);
                task.setCompressedAssetUri(null);
                task.setFailureReason(null);
                break;
            }
        }
        notifyListeners();
    }

    public synchronized void updateTaskSettings(String taskId, CompressionSettings settings) {
        QueueTask task = findTask(taskId);
        if (task == null || task.getAction() != QueueAction.COMPRESS || task.getStatus() == QueueStatus.RUNNING) {
            return;
        }
        task.setSettings(settings);
        task.setStatus(QueueStatus.PENDING);
        task.setProgress(0f);
        task.setCompressedAssetUri(null);
        task.setFailureReason(null);
        notifyListeners();
    }

    public synchronized void updateVideoTaskSettings(String taskId, VideoCompressionSettings settings) {
        QueueTask task = findTask(taskId);
        if (task == null || task.getAction() != QueueAction.COMPRESS || task.getStatus() == QueueStatus.RUNNING) {
            return;
        }
        task.setVideoSettings(settings);
        task.setStatus(QueueStatus.PENDING);
        task.setProgress(0f);
        task.setCompressedAssetUri(null);
        task.setFailureReason(null);
        notifyListeners();
    }

    public synchronized int pendingCount() {
        int count = 0;
        for (QueueTask task : tasks) {
            if (task.getStatus() == QueueStatus.PENDING || task.getStatus() == QueueStatus.RUNNING) {
                count++;
            }
        }
        return count;
    }

    public synchronized long estimatedSavedBytes() {
        long total = 0L;
        for (QueueTask task : tasks) {
            total += task.getSavedBytes();
        }
        return total;
    }

    public synchronized long actualSavedBytes() {
        long total = 0L;
        for (QueueTask task : tasks) {
            if (task.getStatus() == QueueStatus.DONE) {
                total += task.getSavedBytes();
            }
        }
        return total;
    }

    public synchronized long estimatedRemainingBytes() {
        long total = 0L;
        for (QueueTask task : tasks) {
            if (task.getStatus() != QueueStatus.DONE) {
                total += task.getSavedBytes();
            }
        }
        return total;
    }

    public synchronized long estimatedRemainingTimeMs() {
        long remainingBytes = 0L;
        for (QueueTask task : tasks) {
            if (task.getAction() != QueueAction.COMPRESS || task.getStatus() == QueueStatus.DONE) {
                continue;
            }
            double remainingRatio = task.getStatus() == QueueStatus.RUNNING
                    ? Math.max(0d, 1d - task.getProgress())
                    : 1d;
            remainingBytes += (long) Math.ceil(task.getMedia().getSizeBytes() * remainingRatio);
        }
        if (remainingBytes <= 0L) {
            return 0L;
        }
        double processedBytes = processedCompressionBytes;
        long elapsedMs = processedCompressionDurationMs;
        if (activeCompressionTaskId != null && activeCompressionStartedAtMs > 0L && activeCompressionInputBytes > 0L) {
            QueueTask runningTask = findTask(activeCompressionTaskId);
            if (runningTask != null && runningTask.getStatus() == QueueStatus.RUNNING) {
                processedBytes += activeCompressionInputBytes * Math.max(0d, Math.min(runningTask.getProgress(), 1f));
                elapsedMs += Math.max(0L, System.currentTimeMillis() - activeCompressionStartedAtMs);
            }
        }
        if (processedBytes <= 0d || elapsedMs <= 0L) {
            return -1L;
        }
        double bytesPerSecond = processedBytes / (elapsedMs / (double) MILLIS_PER_SECOND);
        if (bytesPerSecond <= 0d) {
            return -1L;
        }
        return (long) Math.ceil(remainingBytes / bytesPerSecond * MILLIS_PER_SECOND);
    }

    public synchronized void addListener(QueueChangeListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public synchronized void removeListener(QueueChangeListener listener) {
        listeners.remove(listener);
    }

    private void processQueue(int token) {
        while (true) {
            // 单线程顺序取任务，保证同一时刻只处理一个压缩任务。
            QueueTask task = beginNextTask(token);
            if (task == null) {
                finishRunIfIdle(token);
                return;
            }
            try {
                processTask(task, token);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    private synchronized QueueTask beginNextTask(int token) {
        if (!isActiveRun(token)) {
            return null;
        }
        for (QueueTask task : tasks) {
            if (task.getStatus() == QueueStatus.PENDING) {
                task.setStatus(QueueStatus.RUNNING);
                task.setProgress(0f);
                task.setFailureReason(null);
                if (task.getAction() == QueueAction.COMPRESS) {
                    activeCompressionTaskId = task.getId();
                    activeCompressionInputBytes = task.getMedia().getSizeBytes();
                    activeCompressionStartedAtMs = System.currentTimeMillis();
                }
                notifyListeners();
                return task;
            }
        }
        return null;
    }

    private void processTask(QueueTask task, int token) throws InterruptedException {
        try {
            if (task.getAction() == QueueAction.DELETE) {
                waitForRecycleAuthorization(task.getId(), token);
                return;
            }
            if (task.getMedia().getKind() == MediaKind.VIDEO) {
                VideoCompressionWorker.Result result = videoCompressionWorker.compress(
                        appContext,
                        task.getMedia(),
                        task.getVideoSettings(),
                        progress -> updateTaskProgress(task.getId(), progress, token)
                );
                completeVideoTask(task.getId(), result, token);
                return;
            }
            ImageCompressionWorker.Result result = imageCompressionWorker.compress(
                    appContext,
                    task.getMedia(),
                    task.getSettings(),
                    progress -> updateTaskProgress(task.getId(), progress, token)
            );
            completeTask(task.getId(), result, token);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            failTask(task.getId(), message, token);
        }
    }

    private void waitForRecycleAuthorization(String taskId, int token) {
        synchronized (this) {
            if (!isActiveRun(token)) {
                return;
            }
            QueueTask task = findTask(taskId);
            if (task == null) {
                return;
            }
            task.setStatus(QueueStatus.WAITING_RECYCLE_CONFIRM);
            task.setProgress(0f);
            task.setFailureReason(null);
            // 系统回收授权必须由界面层发起；这里先暂停队列，授权完成后再继续处理后续任务。
            for (QueueTask pendingTask : tasks) {
                if (pendingTask.getAction() == QueueAction.DELETE
                        && pendingTask.getStatus() == QueueStatus.PENDING) {
                    pendingTask.setStatus(QueueStatus.WAITING_RECYCLE_CONFIRM);
                    pendingTask.setProgress(0f);
                    pendingTask.setFailureReason(null);
                }
            }
            running = false;
            notifyListeners();
        }
    }

    private boolean updateTaskProgress(String taskId, float progress, int token) {
        synchronized (this) {
            if (!isActiveRun(token)) {
                return false;
            }
            QueueTask task = findTask(taskId);
            if (task == null || task.getStatus() != QueueStatus.RUNNING) {
                return false;
            }
            task.setProgress(progress);
            notifyListeners();
            return true;
        }
    }

    public synchronized void markOriginalRecycled(Collection<String> taskIds) {
        for (QueueTask task : tasks) {
            if (taskIds.contains(task.getId())) {
                task.setOriginalRecycled(true);
            }
        }
        notifyListeners();
    }

    public synchronized void markOutputRecycled(Collection<String> taskIds) {
        for (QueueTask task : tasks) {
            if (taskIds.contains(task.getId())) {
                task.setOutputRecycled(true);
            }
        }
        notifyListeners();
    }

    public synchronized void completeRecycleTasks(Collection<String> taskIds) {
        for (QueueTask task : tasks) {
            if (taskIds.contains(task.getId())
                    && task.getAction() == QueueAction.DELETE
                    && task.getStatus() == QueueStatus.WAITING_RECYCLE_CONFIRM) {
                task.setProgress(1f);
                task.setStatus(QueueStatus.DONE);
                task.setFailureReason(null);
                task.setOriginalRecycled(true);
            }
        }
        notifyListeners();
    }

    public synchronized void failRecycleTasks(Collection<String> taskIds, String reason) {
        for (QueueTask task : tasks) {
            if (taskIds.contains(task.getId())
                    && task.getAction() == QueueAction.DELETE
                    && task.getStatus() == QueueStatus.WAITING_RECYCLE_CONFIRM) {
                task.setProgress(0f);
                task.setStatus(QueueStatus.FAILED);
                task.setFailureReason(reason);
            }
        }
        notifyListeners();
    }

    public synchronized void prepareRecycleTasks(Collection<String> taskIds) {
        for (QueueTask task : tasks) {
            if (taskIds.contains(task.getId()) && task.getAction() == QueueAction.DELETE) {
                task.setStatus(QueueStatus.WAITING_RECYCLE_CONFIRM);
                task.setProgress(0f);
                task.setFailureReason(null);
            }
        }
        notifyListeners();
    }

    public synchronized void startIfHasPendingTasks() {
        if (running || appContext == null) {
            return;
        }
        for (QueueTask task : tasks) {
            if (task.getStatus() == QueueStatus.PENDING) {
                running = true;
                int token = ++runToken;
                notifyListeners();
                workerExecutor.execute(() -> processQueue(token));
                return;
            }
        }
    }

    public synchronized void rememberOriginalAssetUri(String taskId, android.net.Uri originalUri) {
        if (originalUri == null) {
            return;
        }
        for (QueueTask task : tasks) {
            if (task.getId().equals(taskId) && task.getOriginalAssetUri() == null) {
                // 老任务首次从压缩对照相册反查到原始版本后，写回缓存供后续稳定预览。
                task.setOriginalAssetUri(originalUri);
                persistTasks();
                return;
            }
        }
    }

    private void completeTask(String taskId, ImageCompressionWorker.Result result, int token) {
        synchronized (this) {
            if (!isActiveRun(token)) {
                return;
            }
            QueueTask task = findTask(taskId);
            if (task == null) {
                return;
            }
            task.setActualOutputBytes(result.getOutputBytes());
            task.setOriginalAssetUri(result.getOriginalUri());
            task.setCompressedAssetUri(result.getOutputUri());
            task.setProgress(1f);
            task.setStatus(QueueStatus.DONE);
            recordCompletedCompression(taskId);
            notifyListeners();
        }
    }

    private void completeVideoTask(String taskId, VideoCompressionWorker.Result result, int token) {
        synchronized (this) {
            if (!isActiveRun(token)) {
                return;
            }
            QueueTask task = findTask(taskId);
            if (task == null) {
                return;
            }
            task.setActualOutputBytes(result.getOutputBytes());
            task.setOriginalAssetUri(result.getOriginalUri());
            task.setCompressedAssetUri(result.getOutputUri());
            task.setProgress(1f);
            task.setStatus(QueueStatus.DONE);
            recordCompletedCompression(taskId);
            notifyListeners();
        }
    }

    private void failTask(String taskId, String reason, int token) {
        synchronized (this) {
            if (!isActiveRun(token)) {
                return;
            }
            QueueTask task = findTask(taskId);
            if (task == null) {
                return;
            }
            task.setProgress(0f);
            task.setStatus(QueueStatus.FAILED);
            task.setFailureReason(reason);
            clearActiveCompressionIfMatches(taskId);
            notifyListeners();
        }
    }

    private synchronized void finishRunIfIdle(int token) {
        if (!isActiveRun(token)) {
            return;
        }
        running = false;
        notifyListeners();
    }

    private synchronized boolean isActiveRun(int token) {
        return running && runToken == token;
    }

    private synchronized QueueTask findTask(String taskId) {
        for (QueueTask task : tasks) {
            if (task.getId().equals(taskId)) {
                return task;
            }
        }
        return null;
    }

    private void resetRunningTasksToPending() {
        for (QueueTask task : tasks) {
            if (task.getStatus() == QueueStatus.RUNNING
                    || task.getStatus() == QueueStatus.WAITING_RECYCLE_CONFIRM) {
                task.setStatus(QueueStatus.PENDING);
                task.setProgress(0f);
            }
        }
        activeCompressionTaskId = null;
        activeCompressionInputBytes = 0L;
        activeCompressionStartedAtMs = 0L;
    }

    private void recordCompletedCompression(String taskId) {
        if (activeCompressionTaskId == null || !activeCompressionTaskId.equals(taskId)) {
            return;
        }
        processedCompressionBytes += Math.max(0L, activeCompressionInputBytes);
        processedCompressionDurationMs += Math.max(0L, System.currentTimeMillis() - activeCompressionStartedAtMs);
        activeCompressionTaskId = null;
        activeCompressionInputBytes = 0L;
        activeCompressionStartedAtMs = 0L;
    }

    private void clearActiveCompressionIfMatches(String taskId) {
        if (activeCompressionTaskId == null || !activeCompressionTaskId.equals(taskId)) {
            return;
        }
        activeCompressionTaskId = null;
        activeCompressionInputBytes = 0L;
        activeCompressionStartedAtMs = 0L;
    }

    private void notifyListeners() {
        persistTasks();
        List<QueueChangeListener> snapshot = new ArrayList<>(listeners);
        for (QueueChangeListener listener : snapshot) {
            listener.onQueueChanged();
        }
    }

    private void persistTasks() {
        if (appContext != null) {
            taskStore.save(appContext, tasks);
        }
    }
}
