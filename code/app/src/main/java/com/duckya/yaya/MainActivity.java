package com.duckya.yaya;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.duckya.yaya.queue.QueueManager;
import com.duckya.yaya.ui.QueueFragment;
import com.duckya.yaya.ui.ScanFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 应用主入口。
 * 负责加载主布局、处理系统边距，并通过底部导航切换“浏览本地”和“任务队列”页面。
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG_SCAN = "main_scan";
    private static final String TAG_QUEUE = "main_queue";

    private Fragment scanFragment;
    private Fragment queueFragment;
    private Fragment activeFragment;

    @Override
    protected void attachBaseContext(Context newBase) {
        // 固定应用内字体缩放，避免系统大字号把当前界面布局撑得过大。
        Configuration configuration = new Configuration(newBase.getResources().getConfiguration());
        configuration.fontScale = 1.0f;
        super.attachBaseContext(newBase.createConfigurationContext(configuration));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 开启沉浸式边到边布局，让内容可以延伸到系统栏区域。
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // 提前把 applicationContext 交给任务队列，后续压缩任务才能在后台安全运行。
        QueueManager.getInstance().initialize(getApplicationContext());

        // 根据状态栏、导航栏高度给根布局补内边距，避免内容被系统栏遮挡。
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 监听底部导航点击，根据选中的菜单切换对应 Fragment。
        BottomNavigationView bottomNavigation = findViewById(R.id.bottom_navigation);
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_scan) {
                showFragment(R.id.navigation_scan);
                return true;
            }
            if (itemId == R.id.navigation_queue) {
                showFragment(R.id.navigation_queue);
                return true;
            }
            return false;
        });
        bottomNavigation.setOnItemReselectedListener(item -> {
            if (item.getItemId() == R.id.navigation_queue) {
                Fragment fragment = ensureFragment(R.id.navigation_queue);
                if (fragment instanceof QueueFragment) {
                    ((QueueFragment) fragment).showQueueRootPage();
                }
            }
        });

        restoreFragmentsIfNeeded();

        // 首次进入应用时默认展示相册浏览页；旋转屏幕等恢复场景交给系统保留当前页面。
        if (savedInstanceState == null) {
            bottomNavigation.setSelectedItemId(R.id.navigation_scan);
        }
    }

    // 复用两个主 Fragment，避免切页时反复销毁相册网格和重新加载缩略图。
    private void showFragment(int navigationId) {
        Fragment targetFragment = ensureFragment(navigationId);
        if (navigationId == R.id.navigation_queue && targetFragment instanceof QueueFragment) {
            ((QueueFragment) targetFragment).showQueueRootPage();
        }
        if (targetFragment == activeFragment) {
            return;
        }
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (activeFragment != null) {
            transaction.hide(activeFragment);
        }
        if (!targetFragment.isAdded()) {
            transaction.add(
                    R.id.fragment_container,
                    targetFragment,
                    navigationId == R.id.navigation_scan ? TAG_SCAN : TAG_QUEUE
            );
        } else {
            transaction.show(targetFragment);
        }
        activeFragment = targetFragment;
        transaction.commit();
    }

    private Fragment ensureFragment(int navigationId) {
        if (navigationId == R.id.navigation_scan) {
            if (scanFragment == null) {
                scanFragment = new ScanFragment();
            }
            return scanFragment;
        }
        if (queueFragment == null) {
            queueFragment = new QueueFragment();
        }
        return queueFragment;
    }

    private void restoreFragmentsIfNeeded() {
        scanFragment = getSupportFragmentManager().findFragmentByTag(TAG_SCAN);
        queueFragment = getSupportFragmentManager().findFragmentByTag(TAG_QUEUE);
        if (queueFragment != null && !queueFragment.isHidden()) {
            activeFragment = queueFragment;
        } else if (scanFragment != null && !scanFragment.isHidden()) {
            activeFragment = scanFragment;
        }
    }
}
