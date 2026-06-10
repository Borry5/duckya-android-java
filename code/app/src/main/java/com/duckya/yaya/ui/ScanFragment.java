package com.duckya.yaya.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.duckya.yaya.R;
import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.util.FormatUtils;
import com.duckya.yaya.util.MediaStoreScanner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScanFragment extends Fragment {
    private TextView statusText;
    private TextView detailText;
    private Button primaryButton;
    private ExecutorService scanExecutor;
    private final MediaStoreScanner scanner = new MediaStoreScanner();

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
        primaryButton = view.findViewById(R.id.scan_primary_button);
        scanExecutor = Executors.newSingleThreadExecutor();

        primaryButton.setOnClickListener(v -> {
            if (hasAllMediaPermissions(requireContext())) {
                startScan();
            } else {
                permissionLauncher.launch(requiredPermissions());
            }
        });

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
        primaryButton = null;
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
        detailText.setText(R.string.scan_stage_hint);
        primaryButton.setEnabled(false);

        Context appContext = requireContext().getApplicationContext();
        scanExecutor.execute(() -> {
            try {
                List<MediaItemInfo> items = scanner.scan(appContext);
                long imageCount = 0L;
                long videoCount = 0L;
                long totalBytes = 0L;
                for (MediaItemInfo item : items) {
                    if (item.getKind() == MediaKind.IMAGE) {
                        imageCount++;
                    } else if (item.getKind() == MediaKind.VIDEO) {
                        videoCount++;
                    }
                    totalBytes += item.getSizeBytes();
                }
                long finalImageCount = imageCount;
                long finalVideoCount = videoCount;
                long finalTotalBytes = totalBytes;
                requireActivity().runOnUiThread(() -> showScanResult(items.size(), finalImageCount, finalVideoCount, finalTotalBytes));
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
    }

    private void showReadyState() {
        if (statusText == null || detailText == null || primaryButton == null) {
            return;
        }
        statusText.setText(R.string.scan_status_ready);
        detailText.setText(R.string.scan_stage_hint);
        primaryButton.setText(R.string.scan_start);
        primaryButton.setEnabled(true);
    }

    private void showScanResult(int totalCount, long imageCount, long videoCount, long totalBytes) {
        if (statusText == null || detailText == null || primaryButton == null) {
            return;
        }
        statusText.setText(totalCount == 0 ? R.string.scan_status_empty : R.string.scan_status_done);
        String details = getString(R.string.scan_total_count, totalCount)
                + "\n" + getString(R.string.scan_image_count, imageCount)
                + "\n" + getString(R.string.scan_video_count, videoCount)
                + "\n" + getString(R.string.scan_total_size, FormatUtils.formatSize(totalBytes));
        detailText.setText(details);
        primaryButton.setText(R.string.scan_rescan);
        primaryButton.setEnabled(true);
    }

    private void showScanError(String message) {
        if (statusText == null || detailText == null || primaryButton == null) {
            return;
        }
        statusText.setText(getString(R.string.scan_status_failed, message));
        detailText.setText(R.string.scan_stage_hint);
        primaryButton.setText(R.string.scan_rescan);
        primaryButton.setEnabled(true);
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
