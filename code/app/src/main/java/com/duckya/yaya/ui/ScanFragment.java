package com.duckya.yaya.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duckya.yaya.R;
import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.util.MediaStoreScanner;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScanFragment extends Fragment {
    private enum FilterMode { ALL, IMAGE, VIDEO }
    private enum SortMode { SIZE, BITRATE, CAPTURE_TIME, ADDED_TIME }

    private TextView statusText;
    private TextView detailText;
    private TextView doneText;
    private TextView progressPercentText;
    private TextView mediaCountText;
    private ProgressBar progressBar;
    private Button primaryButton;
    private Button filterButton;
    private Button sortButton;
    private MediaGridAdapter mediaAdapter;
    private ExecutorService scanExecutor;
    private final MediaStoreScanner scanner = new MediaStoreScanner();
    private final List<MediaItemInfo> allItems = new ArrayList<>();
    private FilterMode filterMode = FilterMode.ALL;
    private SortMode sortMode = SortMode.SIZE;
    private boolean hideCompressedOutput;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        statusText = view.findViewById(R.id.scan_status_text);
        detailText = view.findViewById(R.id.scan_detail_text);
        doneText = view.findViewById(R.id.scan_done_text);
        progressPercentText = view.findViewById(R.id.scan_progress_percent_text);
        mediaCountText = view.findViewById(R.id.media_count_text);
        progressBar = view.findViewById(R.id.scan_progress_bar);
        primaryButton = view.findViewById(R.id.scan_primary_button);
        filterButton = view.findViewById(R.id.filter_button);
        sortButton = view.findViewById(R.id.sort_button);
        RecyclerView mediaRecycler = view.findViewById(R.id.media_recycler);
        scanExecutor = Executors.newSingleThreadExecutor();
        mediaAdapter = new MediaGridAdapter(item ->
                PreviewBottomSheet.newInstance(item).show(getParentFragmentManager(), "preview"));
        mediaRecycler.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        mediaRecycler.setAdapter(mediaAdapter);

        primaryButton.setOnClickListener(v -> {
            if (hasAllMediaPermissions(requireContext())) {
                startScan();
            } else {
                permissionLauncher.launch(requiredPermissions());
            }
        });
        filterButton.setOnClickListener(v -> showFilterSheet());
        sortButton.setOnClickListener(v -> showSortMenu());
        setFilterControlsEnabled(false);
        updateProgress(0);
        updateSortButtonText();

        if (hasAllMediaPermissions(requireContext())) {
            showReadyState();
            startScan();
        } else {
            showPermissionState();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        statusText = null;
        detailText = null;
        doneText = null;
        progressPercentText = null;
        mediaCountText = null;
        progressBar = null;
        primaryButton = null;
        filterButton = null;
        sortButton = null;
        mediaAdapter = null;
    }

    private void onPermissionResult(Map<String, Boolean> result) {
        boolean granted = true;
        for (Boolean value : result.values()) {
            granted = granted && Boolean.TRUE.equals(value);
        }
        if (granted) {
            showReadyState();
            startScan();
        } else {
            showPermissionState();
        }
    }

    private void startScan() {
        if (scanExecutor == null || statusText == null || detailText == null || primaryButton == null) {
            return;
        }
        statusText.setText(R.string.scan_scanning);
        detailText.setText("");
        primaryButton.setEnabled(false);
        setFilterControlsEnabled(false);
        updateProgress(0);
        if (doneText != null) {
            doneText.setVisibility(View.INVISIBLE);
        }

        Context appContext = requireContext().getApplicationContext();
        scanExecutor.execute(() -> {
            try {
                List<MediaItemInfo> items = scanner.scan(appContext);
                requireActivity().runOnUiThread(() -> {
                    allItems.clear();
                    allItems.addAll(items);
                    showScanResult(items.size());
                    applyFilterAndSort();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                requireActivity().runOnUiThread(() -> showScanError(message));
            }
        });
    }

    private void showPermissionState() {
        if (statusText == null || detailText == null || primaryButton == null) {
            return;
        }
        statusText.setText(R.string.scan_status_waiting_permission);
        detailText.setText(R.string.scan_permission_hint);
        primaryButton.setText(R.string.scan_request_permission);
        primaryButton.setEnabled(true);
        updateProgress(0);
        if (doneText != null) {
            doneText.setVisibility(View.INVISIBLE);
        }
        setFilterControlsEnabled(false);
    }

    private void showReadyState() {
        if (statusText == null || detailText == null || primaryButton == null) {
            return;
        }
        statusText.setText(R.string.scan_status_ready);
        detailText.setText("");
        primaryButton.setText(R.string.scan_start);
        primaryButton.setEnabled(true);
        updateProgress(0);
        if (doneText != null) {
            doneText.setVisibility(View.INVISIBLE);
        }
        setFilterControlsEnabled(false);
    }

    private void showScanResult(int totalCount) {
        if (statusText == null || detailText == null || primaryButton == null) {
            return;
        }
        statusText.setText(R.string.scan_progress_title);
        detailText.setText(getString(R.string.scan_total_count, totalCount));
        primaryButton.setText(R.string.scan_rescan);
        primaryButton.setEnabled(true);
        updateProgress(100);
        if (doneText != null) {
            doneText.setVisibility(View.VISIBLE);
        }
        setFilterControlsEnabled(totalCount > 0);
    }

    private void showScanError(String message) {
        if (statusText == null || detailText == null || primaryButton == null) {
            return;
        }
        statusText.setText(getString(R.string.scan_status_failed, message));
        detailText.setText("");
        primaryButton.setText(R.string.scan_rescan);
        primaryButton.setEnabled(true);
        updateProgress(0);
        if (doneText != null) {
            doneText.setVisibility(View.INVISIBLE);
        }
        setFilterControlsEnabled(false);
    }

    private void applyFilterAndSort() {
        if (mediaAdapter == null) {
            return;
        }
        List<MediaItemInfo> visibleItems = new ArrayList<>();
        for (MediaItemInfo item : allItems) {
            if (filterMode == FilterMode.IMAGE && item.getKind() != MediaKind.IMAGE) {
                continue;
            }
            if (filterMode == FilterMode.VIDEO && item.getKind() != MediaKind.VIDEO) {
                continue;
            }
            if (hideCompressedOutput && item.getName().toLowerCase().contains("_compressed")) {
                continue;
            }
            visibleItems.add(item);
        }
        Collections.sort(visibleItems, comparatorFor(sortMode));
        mediaAdapter.submitList(visibleItems);
        if (mediaCountText != null) {
            mediaCountText.setText(getString(R.string.scan_item_count, visibleItems.size()));
        }
        updateFilterButtonState();
    }

    private Comparator<MediaItemInfo> comparatorFor(SortMode mode) {
        if (mode == SortMode.BITRATE) {
            return bitrateComparator();
        }
        if (mode == SortMode.CAPTURE_TIME || mode == SortMode.ADDED_TIME) {
            return timeComparator();
        }
        return sizeComparator();
    }

    private Comparator<MediaItemInfo> timeComparator() {
        return (left, right) -> Long.compare(right.getModifiedTimeMs(), left.getModifiedTimeMs());
    }

    private Comparator<MediaItemInfo> sizeComparator() {
        return (left, right) -> Long.compare(right.getSizeBytes(), left.getSizeBytes());
    }

    private Comparator<MediaItemInfo> bitrateComparator() {
        return (left, right) -> Double.compare(videoBitrateMbps(right), videoBitrateMbps(left));
    }

    private double videoBitrateMbps(MediaItemInfo item) {
        if (item.getKind() != MediaKind.VIDEO || item.getDurationMs() <= 0L) {
            return 0.0;
        }
        return item.getSizeBytes() * 8.0 / (item.getDurationMs() / 1000.0) / 1_000_000.0;
    }

    private void setFilterControlsEnabled(boolean enabled) {
        if (filterButton == null || sortButton == null) {
            return;
        }
        filterButton.setEnabled(enabled);
        sortButton.setEnabled(enabled);
        updateFilterButtonState();
    }

    private void updateFilterButtonState() {
        if (filterButton == null || sortButton == null) {
            return;
        }
        boolean filtered = filterMode != FilterMode.ALL || hideCompressedOutput;
        filterButton.setAlpha(filtered ? 1.0f : 0.85f);
        sortButton.setAlpha(1.0f);
    }

    private void updateProgress(int progress) {
        if (progressBar != null) {
            progressBar.setProgress(progress);
        }
        if (progressPercentText != null) {
            progressPercentText.setText(progress + "%");
        }
    }

    private void showFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View sheet = LayoutInflater.from(requireContext()).inflate(R.layout.sheet_filter, null, false);
        RadioGroup typeGroup = sheet.findViewById(R.id.filter_type_group);
        Switch hideCompressedSwitch = sheet.findViewById(R.id.filter_hide_compressed_output_switch);
        if (filterMode == FilterMode.IMAGE) {
            typeGroup.check(R.id.filter_type_image);
        } else if (filterMode == FilterMode.VIDEO) {
            typeGroup.check(R.id.filter_type_video);
        } else {
            typeGroup.check(R.id.filter_type_all);
        }
        hideCompressedSwitch.setChecked(hideCompressedOutput);
        typeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.filter_type_image) {
                filterMode = FilterMode.IMAGE;
            } else if (checkedId == R.id.filter_type_video) {
                filterMode = FilterMode.VIDEO;
            } else {
                filterMode = FilterMode.ALL;
            }
            applyFilterAndSort();
        });
        hideCompressedSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            hideCompressedOutput = isChecked;
            applyFilterAndSort();
        });
        dialog.setContentView(sheet);
        dialog.show();
    }

    private void showSortMenu() {
        PopupMenu menu = new PopupMenu(requireContext(), sortButton);
        menu.inflate(R.menu.sort_menu);
        int checkedId;
        if (sortMode == SortMode.BITRATE) {
            checkedId = R.id.sort_by_bitrate;
        } else if (sortMode == SortMode.CAPTURE_TIME) {
            checkedId = R.id.sort_by_capture_time;
        } else if (sortMode == SortMode.ADDED_TIME) {
            checkedId = R.id.sort_by_added_time;
        } else {
            checkedId = R.id.sort_by_size;
        }
        for (int i = 0; i < menu.getMenu().size(); i++) {
            MenuItem item = menu.getMenu().getItem(i);
            item.setCheckable(true);
            item.setChecked(item.getItemId() == checkedId);
        }
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.sort_by_bitrate) {
                sortMode = SortMode.BITRATE;
            } else if (id == R.id.sort_by_capture_time) {
                sortMode = SortMode.CAPTURE_TIME;
            } else if (id == R.id.sort_by_added_time) {
                sortMode = SortMode.ADDED_TIME;
            } else {
                sortMode = SortMode.SIZE;
            }
            updateSortButtonText();
            applyFilterAndSort();
            return true;
        });
        menu.show();
    }

    private void updateSortButtonText() {
        if (sortButton == null) {
            return;
        }
        if (sortMode == SortMode.BITRATE) {
            sortButton.setText(R.string.sort_bitrate);
        } else if (sortMode == SortMode.CAPTURE_TIME) {
            sortButton.setText(R.string.sort_capture_time);
        } else if (sortMode == SortMode.ADDED_TIME) {
            sortButton.setText(R.string.sort_added_time);
        } else {
            sortButton.setText(R.string.sort_size);
        }
    }

    private boolean hasAllMediaPermissions(Context context) {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }
}
