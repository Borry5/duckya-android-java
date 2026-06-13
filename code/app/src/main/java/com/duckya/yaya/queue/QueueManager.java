package com.duckya.yaya.queue;

import android.content.Context;

import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.QueueAction;
import com.duckya.yaya.model.QueueStatus;
import com.duckya.yaya.model.QueueTask;
import com.duckya.yaya.model.CompressionSettings;
import com.duckya.yaya.util.ImageCompressionWorker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QueueManager {
    private static final QueueManager INSTANCE = new QueueManager();

    private final List<QueueTask> tasks = new ArrayList<>();
    private final List<QueueChangeListener> listeners = new ArrayList<>();
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
    private final ImageCompressionWorker imageCompressionWorker = new ImageCompressionWorker();
    private final QueueTaskStore taskStore = new QueueTaskStore();
    private Context appContext;
    private boolean running;
    private int runToken;

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
                task.setCompressedAssetUri(null);
                task.setFailureReason(null);
                break;
            }
        }
        notifyListeners();
    }

    public synchronized void prioritizeTask(String taskId) {
        int index = -1;
        for (int i = 0; i < tasks.size(); i++) {
            QueueTask task = tasks.get(i);
            if (task.getId().equals(taskId) && task.getStatus() == QueueStatus.PENDING) {
                index = i;
                break;
            }
        }
        if (index > 0) {
            QueueTask task = tasks.remove(index);
            tasks.add(0, task);
            notifyListeners();
        }
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
                notifyListeners();
                return task;
            }
        }
        return null;
    }

    private void processTask(QueueTask task, int token) throws InterruptedException {
        try {
            if (task.getAction() == QueueAction.DELETE) {
                failTask(task.getId(), "删除功能将在第 6 阶段接入", token);
                return;
            }
            // 第 5 阶段只处理图片压缩，视频转码放到后续阶段接入。
            if (task.getMedia().getKind() != MediaKind.IMAGE) {
                failTask(task.getId(), "视频压缩将在第 8 阶段接入", token);
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
            task.setCompressedAssetUri(result.getOutputUri());
            task.setProgress(1f);
            task.setStatus(QueueStatus.DONE);
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
            if (task.getStatus() == QueueStatus.RUNNING) {
                task.setStatus(QueueStatus.PENDING);
                task.setProgress(0f);
            }
        }
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
