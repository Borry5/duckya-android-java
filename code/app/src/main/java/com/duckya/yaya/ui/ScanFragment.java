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
import android.widget.RadioGroup;
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

/**
 * 这个文件是“浏览本地”页面的核心控制器，负责权限申请、媒体扫描、筛选排序和多选入队。
 * 输入是用户的授权结果、点击操作、筛选排序选择，以及 MediaStore 扫描返回的媒体列表。
 * 处理过程是驱动扫描缓存、维护当前过滤条件和选中集合，并把用户选择的媒体加入任务队列。
 * 输出是浏览页网格内容、头部状态、固定选择栏，以及提交给队列系统的任务数据。
 */

//浏览本地
public class ScanFragment extends Fragment {
    private enum FilterMode { ALL, IMAGE, VIDEO }
    private enum SortMode { SIZE, BITRATE, ADDED_TIME }

    private MediaGridAdapter mediaAdapter;
    private ExecutorService scanExecutor;
    private final MediaStoreScanner scanner = new MediaStoreScanner();
    private final MediaScanCache scanCache = new MediaScanCache();
    private final List<MediaItemInfo> allItems = new ArrayList<>();
    private final Set<String> selectedUris = new HashSet<>();
    private FilterMode filterMode = FilterMode.ALL;
    private SortMode sortMode = SortMode.SIZE;
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
    private View fixedTitleBar;
    private Button fixedSelectButton;
    private View fixedSelectionBar;
    private Button fixedCompressButton;
    private Button fixedDeleteButton;
    private Button fixedDoneButton;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);
    /**
     * 这个函数用于创建浏览页的根视图。
     * 输入是布局加载参数 inflater、container 和 savedInstanceState。
     * 输出是 fragment_scan.xml 对应的页面 View。
     */
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    /**
     * 这个函数用于初始化浏览页控件、列表适配器和交互监听。
     * 输入是已经创建好的根 View 和可选的 savedInstanceState。
     * 输出是完成初始化的浏览页界面，并按权限状态决定是否开始展示数据。
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView mediaRecycler = view.findViewById(R.id.media_recycler);
        fixedTitleBar = view.findViewById(R.id.scan_fixed_title_bar);
        fixedSelectButton = view.findViewById(R.id.scan_fixed_select_button);
        fixedSelectionBar = view.findViewById(R.id.scan_fixed_selection_bar);
        fixedCompressButton = view.findViewById(R.id.scan_fixed_selection_compress_button);
        fixedDeleteButton = view.findViewById(R.id.scan_fixed_selection_delete_button);
        fixedDoneButton = view.findViewById(R.id.scan_fixed_selection_done_button);
        fixedSelectButton.setOnClickListener(v -> setSelectionMode(true));
        fixedCompressButton.setOnClickListener(v -> addSelectedToQueue(QueueAction.COMPRESS));
        fixedDeleteButton.setOnClickListener(v -> addSelectedToQueue(QueueAction.DELETE));
        fixedDoneButton.setOnClickListener(v -> setSelectionMode(false));
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
            public void onSelectionToggleClick(MediaItemInfo item) {
                toggleSelection(item);
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

    /**
     * 这个函数用于销毁浏览页时释放线程池和页面引用。
     * 输入是无。
     * 输出是避免页面销毁后继续持有 View 或执行扫描任务。
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        mediaAdapter = null;
        fixedTitleBar = null;
        fixedSelectButton = null;
        fixedSelectionBar = null;
        fixedCompressButton = null;
        fixedDeleteButton = null;
        fixedDoneButton = null;
    }

    /**
     * 这个函数用于处理媒体读取权限的申请结果。
     * 输入是权限名到授权结果的映射。
     * 输出是根据授权情况切换到可扫描状态或继续提示授权。
     */
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

    /**
     * 这个函数用于优先展示缓存扫描结果，没有缓存时再触发系统扫描。
     * 输入是无。
     * 输出是缓存媒体列表或一次新的扫描流程。
     */
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

    /**
     * 这个函数用于启动一次后台媒体扫描。
     * 输入是无。
     * 输出是扫描结果写入缓存，并刷新页面头部和网格内容。
     */
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

    /**
     * 这个函数用于展示等待权限授权的头部状态。
     * 输入是无。
     * 输出是更新后的头部文案和按钮状态。
     */
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

    /**
     * 这个函数用于展示准备开始扫描时的默认状态。
     * 输入是无。
     * 输出是更新后的头部文案和按钮状态。
     */
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

    /**
     * 这个函数用于在扫描成功后展示统计结果。
     * 输入是扫描得到的媒体总数。
     * 输出是更新头部状态并开放筛选排序控件。
     */
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

    /**
     * 这个函数用于展示扫描失败信息。
     * 输入是失败原因字符串。
     * 输出是更新头部状态并允许重新扫描。
     */
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

    /**
     * 这个函数用于根据当前筛选条件和排序方式刷新网格列表。
     * 输入是当前 allItems、filterMode、sortMode 和 selectionMode 状态。
     * 输出是新的可见媒体列表和头部展示状态。
     */
    private void applyFilterAndSort() {
        if (mediaAdapter == null) {
            return;
        }
        if (sortMode == SortMode.BITRATE && filterMode != FilterMode.VIDEO) {
            filterMode = FilterMode.VIDEO;
        }
        List<MediaItemInfo> visibleItems = new ArrayList<>();
        for (MediaItemInfo item : allItems) {
            if (filterMode == FilterMode.IMAGE && item.getKind() != MediaKind.IMAGE) {
                continue;
            }
            if (filterMode == FilterMode.VIDEO && item.getKind() != MediaKind.VIDEO) {
                continue;
            }
            visibleItems.add(item);
        }
        visibleItems.sort(comparatorFor(sortMode));
        mediaAdapter.submitList(visibleItems);
        mediaAdapter.setCornerBadgeMode(cornerBadgeModeFor(sortMode));
        mediaAdapter.setSelectionMode(selectionMode, selectedUris);
        headerMediaCountText = getString(R.string.scan_item_count, visibleItems.size());
        updateFilterButtonState();
        renderHeader();
    }

    /**
     * 这个函数用于返回指定排序方式对应的比较器。
     * 输入是排序模式。
     * 输出是媒体列表排序所需的 Comparator。
     */
    private Comparator<MediaItemInfo> comparatorFor(SortMode mode) {
        if (mode == SortMode.BITRATE) {
            return bitrateComparator();
        }
        if (mode == SortMode.ADDED_TIME) {
            return timeComparator();
        }
        return sizeComparator();
    }

    /**
     * 这个函数用于返回指定排序方式下角标展示模式。
     * 输入是排序模式。
     * 输出是媒体卡片右上角要显示的标记类型。
     */
    private MediaGridAdapter.CornerBadgeMode cornerBadgeModeFor(SortMode mode) {
        if (mode == SortMode.BITRATE) {
            return MediaGridAdapter.CornerBadgeMode.VIDEO_BITRATE;
        }
        if (mode == SortMode.ADDED_TIME) {
            return MediaGridAdapter.CornerBadgeMode.DATE;
        }
        return MediaGridAdapter.CornerBadgeMode.NONE;
    }

    /**
     * 这个函数用于按媒体添加时间倒序排序。
     * 输入是两个媒体项。
     * 输出是比较结果。
     */
    private Comparator<MediaItemInfo> timeComparator() {
        return (left, right) -> Long.compare(right.getModifiedTimeMs(), left.getModifiedTimeMs());
    }

    /**
     * 这个函数用于按文件体积倒序排序。
     * 输入是两个媒体项。
     * 输出是比较结果。
     */
    private Comparator<MediaItemInfo> sizeComparator() {
        return (left, right) -> Long.compare(right.getSizeBytes(), left.getSizeBytes());
    }

    /**
     * 这个函数用于按视频码率倒序排序。
     * 输入是两个媒体项。
     * 输出是比较结果。
     */
    private Comparator<MediaItemInfo> bitrateComparator() {
        return (left, right) -> Double.compare(videoBitrateMbps(right), videoBitrateMbps(left));
    }

    /**
     * 这个函数用于估算单个视频文件的平均码率。
     * 输入是媒体项。
     * 输出是 Mbps 单位的估算码率；非视频返回 0。
     */
    private double videoBitrateMbps(MediaItemInfo item) {
        if (item.getKind() != MediaKind.VIDEO || item.getDurationMs() <= 0L) {
            return 0.0;
        }
        return item.getSizeBytes() * 8.0 / (item.getDurationMs() / 1000.0) / 1_000_000.0;
    }

    /**
     * 这个函数用于统一控制头部筛选和选择入口是否可用。
     * 输入是是否启用的布尔值。
     * 输出是更新后的顶部控件状态。
     */
    private void setFilterControlsEnabled(boolean enabled) {
        headerControlsEnabled = enabled;
        updateFixedSelectionBar();
        updateFilterButtonState();
    }

    /**
     * 这个函数用于刷新筛选按钮相关的展示状态。
     * 输入是无。
     * 输出是重新渲染头部。
     */
    private void updateFilterButtonState() {
        renderHeader();
    }

    /**
     * 这个函数用于更新扫描进度百分比。
     * 输入是进度值。
     * 输出是刷新头部进度显示。
     */
    private void updateProgress(int progress) {
        headerProgress = progress;
        renderHeader();
    }

    /**
     * 这个函数用于弹出筛选底部面板。
     * 输入是无。
     * 输出是用户可切换全部、图片或视频筛选。
     */
    private void showFilterSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        ViewGroup root = getView() instanceof ViewGroup ? (ViewGroup) getView() : null;
        View sheet = LayoutInflater.from(requireContext()).inflate(R.layout.sheet_filter, root, false);
        RadioGroup typeGroup = sheet.findViewById(R.id.filter_type_group);
        if (filterMode == FilterMode.IMAGE) {
            typeGroup.check(R.id.filter_type_image);
        } else if (filterMode == FilterMode.VIDEO) {
            typeGroup.check(R.id.filter_type_video);
        } else {
            typeGroup.check(R.id.filter_type_all);
        }
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
        dialog.setContentView(sheet);
        dialog.show();
    }

    /**
     * 这个函数用于显示单个媒体项的快捷操作菜单。
     * 输入是媒体项和弹出菜单的锚点 View。
     * 输出是把该媒体加入压缩队列或回收队列。
     */
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

    /**
     * 这个函数用于切换浏览页多选模式。
     * 输入是是否开启多选模式。
     * 输出是更新固定顶栏、多选状态和列表选中态。
     */
    private void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        if (!enabled) {
            selectedUris.clear();
        }
        updateFixedSelectionBar();
        renderHeader();
        if (mediaAdapter != null) {
            mediaAdapter.setSelectionMode(selectionMode, selectedUris);
        }
    }

    /**
     * 这个函数用于切换单个媒体的选中状态。
     * 输入是被点击的媒体项。
     * 输出是更新 selectedUris，并刷新对应卡片和顶栏状态。
     */
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
        updateFixedSelectionBar();
        renderHeader();
    }

    /**
     * 这个函数用于刷新固定在顶部的选择栏按钮状态。
     * 输入是当前 selectionMode、headerControlsEnabled 和选中数量。
     * 输出是展示普通顶栏或多选顶栏，并同步按钮可点状态。
     */
    // 顶层固定多选栏不在 RecyclerView 里，滚动媒体列表时仍然停留在页面上方。
    private void updateFixedSelectionBar() {
        if (fixedTitleBar == null || fixedSelectButton == null || fixedSelectionBar == null
                || fixedCompressButton == null || fixedDeleteButton == null || fixedDoneButton == null) {
            return;
        }
        fixedTitleBar.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        fixedSelectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        fixedSelectButton.setEnabled(headerControlsEnabled);
        fixedSelectButton.setAlpha(headerControlsEnabled ? 1.0f : 0.65f);
        boolean hasSelection = !selectedUris.isEmpty();
        fixedCompressButton.setEnabled(hasSelection);
        fixedDeleteButton.setEnabled(hasSelection);
    }

    /**
     * 这个函数用于接收扫描器的后台进度并切回主线程更新头部。
     * 输入是进度百分比、已扫描数量和总数量。
     * 输出是浏览页头部的扫描进度显示。
     */
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

    /**
     * 这个函数用于把当前选中的媒体批量加入指定类型的任务队列。
     * 输入是任务动作类型，可能是压缩或回收。
     * 输出是新增队列任务，并提示用户加入数量。
     */
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

    /**
     * 这个函数用于从全部媒体中筛出当前被选中的项。
     * 输入是 allItems 和 selectedUris。
     * 输出是选中媒体列表。
     */
    private List<MediaItemInfo> selectedItems() {
        List<MediaItemInfo> selectedItems = new ArrayList<>();
        for (MediaItemInfo item : allItems) {
            if (selectedUris.contains(item.getUri().toString())) {
                selectedItems.add(item);
            }
        }
        return selectedItems;
    }

    /**
     * 这个函数用于显示排序菜单并响应用户选择。
     * 输入是菜单锚点 View。
     * 输出是更新排序方式，并刷新可见媒体列表。
     */
    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.sort_menu);
        int checkedId;
        if (sortMode == SortMode.BITRATE) {
            checkedId = R.id.sort_by_bitrate;
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
                // 视频码率只对视频有意义，切换时自动收窄到视频结果。
                filterMode = FilterMode.VIDEO;
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

    /**
     * 这个函数用于更新头部排序按钮的文案。
     * 输入是当前 sortMode。
     * 输出是新的排序按钮文本，并重新渲染头部。
     */
    private void updateSortButtonText() {
        if (sortMode == SortMode.BITRATE) {
            headerSortButtonText = getString(R.string.sort_bitrate);
        } else if (sortMode == SortMode.ADDED_TIME) {
            headerSortButtonText = getString(R.string.sort_time);
        } else {
            headerSortButtonText = getString(R.string.sort_size);
        }
        renderHeader();
    }

    /**
     * 这个函数用于把头部状态对象提交给列表适配器展示。
     * 输入是当前头部文案、进度、筛选、多选等页面状态。
     * 输出是浏览页头部 UI 的最新显示内容。
     */
    private void renderHeader() {
        if (mediaAdapter == null) {
            return;
        }
        boolean filtered = filterMode != FilterMode.ALL;
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

    /**
     * 这个函数用于检查当前系统媒体读取权限是否都已授予。
     * 输入是 Context。
     * 输出是是否具备浏览本地媒体所需权限。
     */
    private boolean hasAllMediaPermissions(Context context) {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 这个函数用于根据系统版本返回所需的媒体读取权限列表。
     * 输入是无。
     * 输出是权限数组。
     */
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
