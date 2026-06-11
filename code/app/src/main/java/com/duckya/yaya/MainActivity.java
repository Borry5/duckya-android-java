package com.duckya.yaya;

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
                showFragment(new ScanFragment());
                return true;
            }
            if (itemId == R.id.navigation_queue) {
                showFragment(new QueueFragment());
                return true;
            }
            return false;
        });

        // 首次进入应用时默认展示相册浏览页；旋转屏幕等恢复场景交给系统保留当前页面。
        if (savedInstanceState == null) {
            bottomNavigation.setSelectedItemId(R.id.navigation_scan);
        }
    }

    // 将指定 Fragment 放入主容器，实现两个主页面之间的切换。
    private void showFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}
