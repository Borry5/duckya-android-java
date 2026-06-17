package com.duckya.yaya.util;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collection;

/**
 * 这个文件用于统一封装系统媒体回收站授权请求。
 * 输入是当前 Context，以及需要回收的媒体 Uri 集合。
 * 处理过程是检查系统版本是否支持回收请求，再调用 MediaStore 官方接口创建 PendingIntent。
 * 输出是给界面层发起系统授权弹窗使用的 PendingIntent；不支持时返回 null。
 */
// 统一创建系统相册回收授权请求，避免直接用文件接口删除用户媒体。
public class MediaTrashManager {
    /**
     * 这个函数用于判断当前系统是否支持 MediaStore 回收授权接口。
     * 输入是无。
     * 输出是布尔值，表示 Android 11 及以上是否可用。
     */
    public boolean isTrashRequestSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * 这个函数用于创建系统回收授权请求。
     * 输入是 Context 和待回收的媒体 Uri 集合。
     * 输出是系统授权弹窗对应的 PendingIntent；若条件不满足则返回 null。
     */
    public PendingIntent createTrashRequest(Context context, Collection<Uri> uris) {
        if (!isTrashRequestSupported() || uris.isEmpty()) {
            return null;
        }
        ContentResolver resolver = context.getContentResolver();
        return MediaStore.createTrashRequest(resolver, new ArrayList<>(uris), true);
    }
}
