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
import android.widget.PopupMenu;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.Toast;

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
import com.duckya.yaya.model.QueueAction;
import com.duckya.yaya.queue.QueueManager;
import com.duckya.yaya.util.MediaScanCache;
import com.duckya.yaya.util.MediaStoreScanner;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScanFragment extends Fragment {
    private enum FilterMode { ALL, IMAGE, VIDEO }
    private enum SortMode { SIZE, BITRATE, CAPTURE_TIME, ADDED_TIME }

    private MediaGridAdapter mediaAdapter;
    private ExecutorService scanExecutor;
    private final MediaStoreScanner scanner = new MediaStoreScanner();
    private final MediaScanCache scanCache = new MediaScanCache();
    private final List<MediaItemInfo> allItems = new ArrayList<>();
    private final Set<String> selectedUris = new HashSet<>();
    private FilterMode filterMode = FilterMode.ALL;
    private SortMode sortMode = SortMode.SIZE;
    private boolean hideCompressedOutput;
    private boolean selectionMode;
    private String headerStatusText = "";
    private String headerDetailText = "";
    private String headerPrimaryButtonText = "";
    private String headerSortButtonText = "";
    private String headerMediaCountText = "";
    private int headerProgress = 0;
    private boolean headerDoneVisible;
    private boolean headerPrimaryEnabled;
    private boolean headerControlsEnabled;

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
        RecyclerView mediaRecycler = view.findViewById(R.id.media_recycler);
        scanExecutor = Executors.newSingleThreadExecutor();
        mediaAdapter = new MediaGridAdapter(new MediaGridAdapter.Listener() {
            @Override
            public void onMediaClick(MediaItemInfo item) {
                if (selectionMode) {
                    toggleSelection(item);
                } else {
                    PreviewBottomSheet.newInstance(item).show(getParentFragmentManager(), "preview");
                }
            }

            @Override
            public void onMediaLongClick(MediaItemInfo item, View anchor) {
                if (selectionMode) {
                    toggleSelection(item);
                } else {
                    showMediaActionMenu(item, anchor);
                }
            }

            @Override
            public void onPrimaryActionClick() {
                if (hasAllMediaPermissions(requireContext())) {
                    startScan();
                } else {
                    permissionLauncher.launch(requiredPermissions());
                }
            }

            @Override
            public void onFilterClick() {
                showFilterSheet();
            }

            @Override
            public void onSortClick(View anchor) {
                showSortMenu(anchor);
            }

            @Override
            public void onSelectModeClick() {
                setSelectionMode(true);
            }

            @Override
            public void onSelectionDoneClick() {
                setSelectionMode(false);
            }

            @Override
            public void onSelectionCompressClick() {
                addSelectedToQueue(QueueAction.COMPRESS);
            }

            @Override
            public void onSelectionDeleteClick() {
                addSelectedToQueue(QueueAction.DELETE);
            }
        });
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 3);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mediaAdapter.getItemViewType(position) == MediaGridAdapter.VIEW_TYPE_HEADER ? 3 : 1;
            }
        });
        mediaRecycler.setLayoutManager(layoutManager);
        mediaRecycler.setAdapter(mediaAdapter);

        setFilterControlsEnabled(false);
        updateProgress(0);
        updateSortButtonText();

        if (hasAllMediaPermissions(requireContext())) {
            showReadyState();
            showCachedResultOrScan();
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
        mediaAdapter = null;
    }

    private void onPermissionResult(Map<String, Boolean> result) {
        boolean granted = true;
        for (Boolean value : result.values()) {
            granted = granted && Boolean.TRUE.equals(value);
        }
        if (granted) {
            showReadyState();
            showCachedResultOrScan();
        } else {
            showPermissionState();
        }
    }

    private void showCachedResultOrScan() {
        List<MediaItemInfo> cachedItems = scanCache.load(requireContext().getApplicationContext());
        if (cachedItems.isEmpty()) {
            startScan();
            return;
        }
        // 有缓存时直接渲染上次结果，避免从队列页返回后重复触发 MediaStore 扫描。
        allItems.clear();
        allItems.addAll(cachedItems);
        headerStatusText = getString(R.string.scan_cached_loaded);
        headerDetailText = getString(R.string.scan_total_count, cachedItems.size());
        headerPrimaryButtonText = getString(R.string.scan_rescan);
        headerPrimaryEnabled = true;
        headerDoneVisible = true;
        updateProgress(100);
        setFilterControlsEnabled(true);
        applyFilterAndSort();
    }

    private void startScan() {
        if (scanExecutor == null || mediaAdapter == null) {
            return;
        }
        setSelectionMode(false);
        headerStatusText = getString(R.string.scan_scanning);
        headerDetailText = getString(R.string.scan_progress_detail, 0, 0);
        headerPrimaryEnabled = false;
        setFilterControlsEnabled(false);
        updateProgress(1);
        headerDoneVisible = false;
        renderHeader();

        Context appContext = requireContext().getApplicationContext();
        scanExecutor.execute(() -> {
            try {
                List<MediaItemInfo> items = scanner.scan(appContext, this::postScanProgress);
                scanCache.save(appContext, items);
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    allItems.clear();
                    allItems.addAll(items);
                    showScanResult(items.size());
                    applyFilterAndSort();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> showScanError(message));
            }
        });
    }

    private void showPermissionState() {
        headerStatusText = getString(R.string.scan_status_waiting_permission);
        headerDetailText = getString(R.string.scan_permission_hint);
        headerPrimaryButtonText = getString(R.string.scan_request_permission);
        headerPrimaryEnabled = true;
        updateProgress(0);
        headerDoneVisible = false;
        setFilterControlsEnabled(false);
        renderHeader();
    }

    private void showReadyState() {
        headerStatusText = getString(R.string.scan_status_ready);
        headerDetailText = "";
        headerPrimaryButtonText = getString(R.string.scan_start);
        headerPrimaryEnabled = true;
        updateProgress(0);
        headerDoneVisible = false;
        setFilterControlsEnabled(false);
        renderHeader();
    }

    private void showScanResult(int totalCount) {
        headerStatusText = getString(R.string.scan_progress_title);
        headerDetailText = getString(R.string.scan_total_count, totalCount);
        headerPrimaryButtonText = getString(R.string.scan_rescan);
        headerPrimaryEnabled = true;
        updateProgress(100);
        headerDoneVisible = true;
        setFilterControlsEnabled(totalCount > 0);
        renderHeader();
    }

    private void showScanError(String message) {
        headerStatusText = getString(R.string.scan_status_failed, message);
        headerDetailText = "";
        headerPrimaryButtonText = getString(R.string.scan_rescan);
        headerPrimaryEnabled = true;
        updateProgress(0);
        headerDoneVisible = false;
        setFilterControlsEnabled(false);
        renderHeader();
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
        mediaAdapter.setSelectionMode(selectionMode, selectedUris);
        headerMediaCountText = getString(R.string.scan_item_count, visibleItems.size());
        updateFilterButtonState();
        renderHeader();
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
        headerControlsEnabled = enabled;
        updateFilterButtonState();
    }

    private void updateFilterButtonState() {
        renderHeader();
    }

    private void updateProgress(int progress) {
        headerProgress = progress;
        renderHeader();
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

    private void showMediaActionMenu(MediaItemInfo item, View anchor) {
        final int actionAddCompress = 1;
        final int actionAddDelete = 2;
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, actionAddCompress, 0, R.string.scan_menu_add_compress);
        menu.getMenu().add(0, actionAddDelete, 1, R.string.scan_menu_add_delete);
        menu.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == actionAddDelete) {
                QueueManager.getInstance().addTask(item, QueueAction.DELETE);
                return true;
            }
            QueueManager.getInstance().addTask(item, QueueAction.COMPRESS);
            return true;
        });
        menu.show();
    }

    private void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        if (!enabled) {
            selectedUris.clear();
        }
        renderHeader();
        if (mediaAdapter != null) {
            mediaAdapter.setSelectionMode(selectionMode, selectedUris);
        }
    }

    private void toggleSelection(MediaItemInfo item) {
        String uri = item.getUri().toString();
        if (selectedUris.contains(uri)) {
            selectedUris.remove(uri);
        } else {
            selectedUris.add(uri);
        }
        if (mediaAdapter != null) {
            mediaAdapter.notifySelectionChanged(uri);
        }
        renderHeader();
    }

    private void postScanProgress(int progress, int scannedCount, int totalCount) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (mediaAdapter == null) {
                return;
            }
            headerStatusText = getString(R.string.scan_scanning);
            headerDetailText = getString(R.string.scan_progress_detail, scannedCount, totalCount);
            updateProgress(progress);
        });
    }

    private void addSelectedToQueue(QueueAction action) {
        List<MediaItemInfo> selectedItems = selectedItems();
        for (MediaItemInfo item : selectedItems) {
            QueueManager.getInstance().addTask(item, action);
        }
        int count = selectedItems.size();
        if (count > 0) {
            int messageId = action == QueueAction.DELETE
                    ? R.string.scan_selected_added_delete
                    : R.string.scan_selected_added_compress;
            Toast.makeText(requireContext(), getString(messageId, count), Toast.LENGTH_SHORT).show();
        }
        setSelectionMode(false);
    }

    private List<MediaItemInfo> selectedItems() {
        List<MediaItemInfo> selectedItems = new ArrayList<>();
        for (MediaItemInfo item : allItems) {
            if (selectedUris.contains(item.getUri().toString())) {
                selectedItems.add(item);
            }
        }
        return selectedItems;
    }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
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
        if (sortMode == SortMode.BITRATE) {
            headerSortButtonText = getString(R.string.sort_bitrate);
        } else if (sortMode == SortMode.CAPTURE_TIME) {
            headerSortButtonText = getString(R.string.sort_capture_time);
        } else if (sortMode == SortMode.ADDED_TIME) {
            headerSortButtonText = getString(R.string.sort_added_time);
        } else {
            headerSortButtonText = getString(R.string.sort_size);
        }
        renderHeader();
    }

    private void renderHeader() {
        if (mediaAdapter == null) {
            return;
        }
        boolean filtered = filterMode != FilterMode.ALL || hideCompressedOutput;
        String mediaCountText = selectionMode
                ? getString(R.string.scan_selection_count, selectedUris.size())
                : headerMediaCountText;
        mediaAdapter.setHeaderState(new MediaGridAdapter.HeaderState(
                headerStatusText,
                headerDetailText,
                headerPrimaryButtonText,
                headerSortButtonText,
                mediaCountText,
                headerProgress,
                headerDoneVisible,
                headerPrimaryEnabled,
                headerControlsEnabled,
                filtered,
                selectionMode,
                selectedUris.size()
        ));
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
