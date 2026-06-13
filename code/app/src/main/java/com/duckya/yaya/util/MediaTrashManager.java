package com.duckya.yaya.util;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collection;

// 统一创建系统相册回收授权请求，避免直接用文件接口删除用户媒体。
public class MediaTrashManager {
    public boolean isTrashRequestSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public PendingIntent createTrashRequest(Context context, Collection<Uri> uris) {
        if (!isTrashRequestSupported() || uris.isEmpty()) {
            return null;
        }
        ContentResolver resolver = context.getContentResolver();
        return MediaStore.createTrashRequest(resolver, new ArrayList<>(uris), true);
    }
}
