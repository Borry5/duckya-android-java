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
 * 这个文件是应用主页面入口，负责承载底部导航和两个一级页面。
 * 输入是系统创建 Activity 时传入的 Context、Bundle，以及用户点击底部导航的交互事件。
 * 处理过程是初始化主布局、接管系统栏边距、恢复 Fragment 状态，并在浏览本地页与任务队列页之间切换。
 * 输出是稳定展示的主界面，以及当前应该显示的 Fragment 页面内容。
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG_SCAN = "main_scan";
    private static final String TAG_QUEUE = "main_queue";

    private Fragment scanFragment;
    private Fragment queueFragment;
    private Fragment activeFragment;

    @Override
    /**
     * 这个函数用于固定应用内部的字体缩放比例。
     * 输入是系统即将附加到 Activity 的基础 Context。
     * 输出是经过字体缩放修正后的新 Context，供后续界面加载使用。
     */
    protected void attachBaseContext(Context newBase) {
        // 固定应用内字体缩放，避免系统大字号把当前界面布局撑得过大。
        Configuration configuration = new Configuration(newBase.getResources().getConfiguration());
        configuration.fontScale = 1.0f;
        super.attachBaseContext(newBase.createConfigurationContext(configuration));
    }

    @Override
    /**
     * 这个函数用于初始化主页面、底部导航和首屏显示逻辑。
     * 输入是系统恢复界面时可能传入的 savedInstanceState。
     * 输出是完成布局绑定、队列初始化和页面切换监听后的主界面。
     */
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
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });
        // 底部导航单独消费导航栏高度，避免三键导航时整页和底栏同时叠加底部留白。
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bottom_navigation), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), systemBars.bottom);
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

    /**
     * 这个函数用于切换底部导航对应的一级页面。
     * 输入是被选中的导航菜单 id。
     * 输出是显示目标 Fragment，并隐藏当前 Fragment。
     */
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

    /**
     * 这个函数用于按导航 id 获取或创建对应的 Fragment 实例。
     * 输入是底部导航菜单 id。
     * 输出是可直接显示的 ScanFragment 或 QueueFragment。
     */
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

    /**
     * 这个函数用于在系统重建 Activity 后恢复当前持有的 Fragment 引用。
     * 输入是 FragmentManager 中已经存在的 Fragment 状态。
     * 输出是重新绑定 scanFragment、queueFragment 和 activeFragment。
     */
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
