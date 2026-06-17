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

/**
 * 这个文件是任务队列的统一调度中心，负责管理压缩任务和回收任务的生命周期。
 * 输入是浏览页加入的媒体任务、界面触发的开始暂停操作，以及压缩 Worker 返回的进度和结果。
 * 处理过程是维护任务列表、串行执行压缩任务、等待系统回收授权、记录预估速度并持久化状态。
 * 输出是最新的队列数据、任务状态变化通知，以及图片或视频压缩执行结果。
 */
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

    /**
     * 这个构造函数用于限制外部直接创建队列管理器实例。
     * 输入是无。
     * 输出是单例对象自身。
     */
    private QueueManager() {
    }

    /**
     * 这个函数用于在应用启动后初始化队列管理器。
     * 输入是应用级 Context。
     * 输出是加载持久化任务后的内存队列状态。
     */
    // 在应用启动时注入 applicationContext，供后台任务长期使用。
    public synchronized void initialize(Context context) {
        appContext = context.getApplicationContext();
        if (tasks.isEmpty()) {
            tasks.addAll(taskStore.load(appContext));
        }
    }

    /**
     * 这个函数用于向队列新增一个任务。
     * 输入是媒体信息和任务动作类型。
     * 输出是更新后的任务列表，并通知界面刷新。
     */
    public synchronized void addTask(MediaItemInfo media, QueueAction action) {
        tasks.add(new QueueTask(media, action));
        notifyListeners();
    }

    /**
     * 这个函数用于获取全部任务快照。
     * 输入是无。
     * 输出是当前任务列表的副本。
     */
    public synchronized List<QueueTask> getTasks() {
        return new ArrayList<>(tasks);
    }

    /**
     * 这个函数用于获取未完成的活动任务。
     * 输入是无。
     * 输出是状态不是 DONE 的任务列表。
     */
    public synchronized List<QueueTask> getActiveTasks() {
        List<QueueTask> result = new ArrayList<>();
        for (QueueTask task : tasks) {
            if (task.getStatus() != QueueStatus.DONE) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * 这个函数用于获取所有已完成任务。
     * 输入是无。
     * 输出是状态为 DONE 的任务列表。
     */
    public synchronized List<QueueTask> getCompletedTasks() {
        List<QueueTask> result = new ArrayList<>();
        for (QueueTask task : tasks) {
            if (task.getStatus() == QueueStatus.DONE) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * 这个函数用于获取等待系统回收确认的任务。
     * 输入是无。
     * 输出是等待回收授权的删除任务列表。
     */
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

    /**
     * 这个函数用于返回队列当前是否处于运行中。
     * 输入是无。
     * 输出是运行状态布尔值。
     */
    public synchronized boolean isRunning() {
        return running;
    }

    /**
     * 这个函数用于切换队列的开始或暂停状态。
     * 输入是无。
     * 输出是更新后的运行状态；若开始执行则会异步启动队列处理。
     */
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

    /**
     * 这个函数用于清空未完成任务。
     * 输入是无。
     * 输出是移除未完成任务后的队列状态。
     */
    public synchronized void clear() {
        running = false;
        runToken++;
        tasks.removeIf(task -> task.getStatus() != QueueStatus.DONE);
        notifyListeners();
    }

    /**
     * 这个函数用于清空已完成任务记录。
     * 输入是无。
     * 输出是移除完成记录后的队列状态。
     */
    public synchronized void clearCompletedTasks() {
        tasks.removeIf(task -> task.getStatus() == QueueStatus.DONE);
        if (tasks.isEmpty()) {
            running = false;
        }
        notifyListeners();
    }

    /**
     * 这个函数用于删除指定任务。
     * 输入是任务 id。
     * 输出是移除目标任务后的队列状态。
     */
    public synchronized void removeTask(String taskId) {
        tasks.removeIf(task -> task.getId().equals(taskId));
        runToken++;
        if (tasks.isEmpty()) {
            running = false;
        }
        notifyListeners();
    }

    /**
     * 这个函数用于把失败或已完成任务重新置为待处理。
     * 输入是任务 id。
     * 输出是重置后的任务状态，并通知界面刷新。
     */
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

    /**
     * 这个函数用于更新图片任务的压缩设置。
     * 输入是任务 id 和新的图片压缩设置。
     * 输出是更新后的任务状态；运行中的任务不会被修改。
     */
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

    /**
     * 这个函数用于更新视频任务的压缩设置。
     * 输入是任务 id 和新的视频压缩设置。
     * 输出是更新后的任务状态；运行中的任务不会被修改。
     */
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

    /**
     * 这个函数用于统计待处理和处理中任务数量。
     * 输入是无。
     * 输出是当前待执行任务数。
     */
    public synchronized int pendingCount() {
        int count = 0;
        for (QueueTask task : tasks) {
            if (task.getStatus() == QueueStatus.PENDING || task.getStatus() == QueueStatus.RUNNING) {
                count++;
            }
        }
        return count;
    }

    /**
     * 这个函数用于估算全部任务理论上可节省的空间。
     * 输入是无。
     * 输出是按任务预估值累计的字节数。
     */
    public synchronized long estimatedSavedBytes() {
        long total = 0L;
        for (QueueTask task : tasks) {
            total += task.getSavedBytes();
        }
        return total;
    }

    /**
     * 这个函数用于统计已经完成任务实际节省的空间。
     * 输入是无。
     * 输出是已完成任务累计节省的字节数。
     */
    public synchronized long actualSavedBytes() {
        long total = 0L;
        for (QueueTask task : tasks) {
            if (task.getStatus() == QueueStatus.DONE) {
                total += task.getSavedBytes();
            }
        }
        return total;
    }

    /**
     * 这个函数用于统计所有未完成任务预计还能节省多少空间。
     * 输入是无。
     * 输出是剩余任务的预估节省字节数。
     */
    public synchronized long estimatedRemainingBytes() {
        long total = 0L;
        for (QueueTask task : tasks) {
            if (task.getStatus() != QueueStatus.DONE) {
                total += task.getSavedBytes();
            }
        }
        return total;
    }

    /**
     * 这个函数用于根据已处理体积和耗时估算剩余时间。
     * 输入是无。
     * 输出是剩余预计毫秒数；无法估算时返回 -1。
     */
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

    /**
     * 这个函数用于注册队列变化监听器。
     * 输入是监听器实例。
     * 输出是更新后的监听列表。
     */
    public synchronized void addListener(QueueChangeListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 这个函数用于移除队列变化监听器。
     * 输入是监听器实例。
     * 输出是更新后的监听列表。
     */
    public synchronized void removeListener(QueueChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * 这个函数用于在线程池中循环处理任务队列。
     * 输入是本轮运行 token。
     * 输出是按顺序处理直到没有可执行任务为止。
     */
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

    /**
     * 这个函数用于取出当前轮次下一个待执行任务，并切换到运行中状态。
     * 输入是本轮运行 token。
     * 输出是下一条待处理任务；若当前轮次已失效或无任务则返回 null。
     */
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

    /**
     * 这个函数用于根据任务类型分发到图片压缩、视频压缩或回收流程。
     * 输入是当前任务对象和本轮运行 token。
     * 输出是无；成功时更新任务结果，失败时写入失败原因。
     */
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

    /**
     * 这个函数用于把删除任务切换为等待系统回收授权状态。
     * 输入是任务 id 和本轮运行 token。
     * 输出是暂停队列运行，并把相关删除任务标记为等待确认。
     */
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

    /**
     * 这个函数用于更新运行中任务的进度。
     * 输入是任务 id、最新进度和本轮运行 token。
     * 输出是是否成功更新进度。
     */
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

    /**
     * 这个函数用于标记原始文件已经被回收。
     * 输入是一组任务 id。
     * 输出是更新后的任务状态。
     */
    public synchronized void markOriginalRecycled(Collection<String> taskIds) {
        for (QueueTask task : tasks) {
            if (taskIds.contains(task.getId())) {
                task.setOriginalRecycled(true);
            }
        }
        notifyListeners();
    }

    /**
     * 这个函数用于标记压缩产物已经被回收。
     * 输入是一组任务 id。
     * 输出是更新后的任务状态。
     */
    public synchronized void markOutputRecycled(Collection<String> taskIds) {
        for (QueueTask task : tasks) {
            if (taskIds.contains(task.getId())) {
                task.setOutputRecycled(true);
            }
        }
        notifyListeners();
    }

    /**
     * 这个函数用于在系统授权成功后完成回收任务。
     * 输入是一组等待回收的任务 id。
     * 输出是把对应任务标记为完成。
     */
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

    /**
     * 这个函数用于在回收失败或取消后写入失败状态。
     * 输入是任务 id 集合和失败原因。
     * 输出是把对应删除任务标记为失败。
     */
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

    /**
     * 这个函数用于把删除任务提前切换到等待授权状态。
     * 输入是一组任务 id。
     * 输出是更新后的任务状态。
     */
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

    /**
     * 这个函数用于在授权完成后继续启动剩余待处理任务。
     * 输入是无。
     * 输出是若存在待处理任务则重新启动队列执行。
     */
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

    /**
     * 这个函数用于缓存原始副本在对照相册中的 Uri。
     * 输入是任务 id 和原始副本 Uri。
     * 输出是把反查到的原始副本地址写回任务持久化数据。
     */
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

    /**
     * 这个函数用于处理图片压缩成功后的结果写回。
     * 输入是任务 id、图片压缩结果和本轮运行 token。
     * 输出是把任务标记为完成并记录统计信息。
     */
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

    /**
     * 这个函数用于处理视频压缩成功后的结果写回。
     * 输入是任务 id、视频压缩结果和本轮运行 token。
     * 输出是把任务标记为完成并记录统计信息。
     */
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

    /**
     * 这个函数用于把任务切换到失败状态。
     * 输入是任务 id、失败原因和本轮运行 token。
     * 输出是更新后的失败任务状态。
     */
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

    /**
     * 这个函数用于在当前轮次没有任务后结束运行状态。
     * 输入是本轮运行 token。
     * 输出是把 running 状态设置为 false。
     */
    private synchronized void finishRunIfIdle(int token) {
        if (!isActiveRun(token)) {
            return;
        }
        running = false;
        notifyListeners();
    }

    /**
     * 这个函数用于判断给定 token 是否仍然对应当前有效运行轮次。
     * 输入是本轮运行 token。
     * 输出是是否仍然有效的布尔值。
     */
    private synchronized boolean isActiveRun(int token) {
        return running && runToken == token;
    }

    /**
     * 这个函数用于按任务 id 查找队列中的任务对象。
     * 输入是任务 id。
     * 输出是找到的任务；不存在时返回 null。
     */
    private synchronized QueueTask findTask(String taskId) {
        for (QueueTask task : tasks) {
            if (task.getId().equals(taskId)) {
                return task;
            }
        }
        return null;
    }

    /**
     * 这个函数用于在暂停时把运行中的任务恢复为待处理状态。
     * 输入是无。
     * 输出是重置后的任务状态和当前运行中的统计信息。
     */
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

    /**
     * 这个函数用于把刚完成的压缩任务计入速度统计。
     * 输入是刚完成的任务 id。
     * 输出是累计已处理体积和耗时数据。
     */
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

    /**
     * 这个函数用于在任务失败或取消时清理当前运行中的压缩统计上下文。
     * 输入是任务 id。
     * 输出是重置当前活动压缩任务记录。
     */
    private void clearActiveCompressionIfMatches(String taskId) {
        if (activeCompressionTaskId == null || !activeCompressionTaskId.equals(taskId)) {
            return;
        }
        activeCompressionTaskId = null;
        activeCompressionInputBytes = 0L;
        activeCompressionStartedAtMs = 0L;
    }

    /**
     * 这个函数用于持久化任务并通知所有界面监听器刷新。
     * 输入是无。
     * 输出是最新的持久化任务数据和界面更新回调。
     */
    private void notifyListeners() {
        persistTasks();
        List<QueueChangeListener> snapshot = new ArrayList<>(listeners);
        for (QueueChangeListener listener : snapshot) {
            listener.onQueueChanged();
        }
    }

    /**
     * 这个函数用于把当前任务列表保存到本地存储。
     * 输入是无。
     * 输出是更新后的本地持久化数据。
     */
    private void persistTasks() {
        if (appContext != null) {
            taskStore.save(appContext, tasks);
        }
    }
}
