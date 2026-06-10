package com.duckya.yaya.queue;

import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.QueueAction;
import com.duckya.yaya.model.QueueStatus;
import com.duckya.yaya.model.QueueTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QueueManager {
    private static final QueueManager INSTANCE = new QueueManager();

    private final List<QueueTask> tasks = new ArrayList<>();
    private final List<QueueChangeListener> listeners = new ArrayList<>();
    private boolean running;

    public static QueueManager getInstance() {
        return INSTANCE;
    }

    private QueueManager() {
    }

    public synchronized void addTask(MediaItemInfo media, QueueAction action) {
        tasks.add(new QueueTask(media, action));
        notifyListeners();
    }

    public synchronized List<QueueTask> getTasks() {
        return new ArrayList<>(tasks);
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void toggleRunning() {
        if (tasks.isEmpty()) {
            return;
        }
        running = !running;
        if (running) {
            for (QueueTask task : tasks) {
                if (task.getStatus() == QueueStatus.PENDING) {
                    task.setStatus(QueueStatus.RUNNING);
                    task.setProgress(0f);
                    break;
                }
            }
        } else {
            for (QueueTask task : tasks) {
                if (task.getStatus() == QueueStatus.RUNNING) {
                    task.setStatus(QueueStatus.PENDING);
                    task.setProgress(0f);
                }
            }
        }
        notifyListeners();
    }

    public synchronized void clear() {
        tasks.clear();
        running = false;
        notifyListeners();
    }

    public synchronized void removeTask(String taskId) {
        tasks.removeIf(task -> task.getId().equals(taskId));
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
            total += task.getEstimatedSavedBytes();
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

    private void notifyListeners() {
        List<QueueChangeListener> snapshot = new ArrayList<>(listeners);
        for (QueueChangeListener listener : snapshot) {
            listener.onQueueChanged();
        }
    }
}
