package com.duckya.yaya.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duckya.yaya.R;
import com.duckya.yaya.queue.QueueChangeListener;
import com.duckya.yaya.queue.QueueManager;
import com.duckya.yaya.util.FormatUtils;

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
            public void onCancel(com.duckya.yaya.model.QueueTask task) {
                queueManager.removeTask(task.getId());
            }

            @Override
            public void onRetry(com.duckya.yaya.model.QueueTask task) {
                queueManager.retryTask(task.getId());
            }

            @Override
            public void onPrioritize(com.duckya.yaya.model.QueueTask task) {
                queueManager.prioritizeTask(task.getId());
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
}
