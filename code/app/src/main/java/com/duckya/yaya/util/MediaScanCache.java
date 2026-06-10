package com.duckya.yaya.util;

import android.content.Context;
import android.net.Uri;

import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.MediaKind;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// 将上一次 MediaStore 扫描结果保存到 app 私有目录，避免页面切换时反复重扫。
public class MediaScanCache {
    private static final String CACHE_FILE_NAME = "media_scan_cache.json";

    public List<MediaItemInfo> load(Context context) {
        File cacheFile = cacheFile(context);
        if (!cacheFile.exists()) {
            return new ArrayList<>();
        }
        try {
            JSONArray array = new JSONArray(readAll(cacheFile));
            List<MediaItemInfo> items = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                items.add(fromJson(object));
            }
            return items;
        } catch (IOException | JSONException | IllegalArgumentException e) {
            return new ArrayList<>();
        }
    }

    public void save(Context context, List<MediaItemInfo> items) {
        JSONArray array = new JSONArray();
        for (MediaItemInfo item : items) {
            array.put(toJson(item));
        }
        try (FileWriter writer = new FileWriter(cacheFile(context), false)) {
            writer.write(array.toString());
        } catch (IOException ignored) {
            // 缓存失败不影响主流程，下一次仍可重新扫描。
        }
    }

    private File cacheFile(Context context) {
        return new File(context.getFilesDir(), CACHE_FILE_NAME);
    }

    private String readAll(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private JSONObject toJson(MediaItemInfo item) {
        JSONObject object = new JSONObject();
        try {
            object.put("name", item.getName());
            object.put("uri", item.getUri().toString());
            object.put("sizeBytes", item.getSizeBytes());
            object.put("modifiedTimeMs", item.getModifiedTimeMs());
            object.put("kind", item.getKind().name());
            object.put("width", item.getWidth());
            object.put("height", item.getHeight());
            object.put("durationMs", item.getDurationMs());
        } catch (JSONException ignored) {
            // JSONObject 写入基础类型通常不会失败，这里保底返回已写入字段。
        }
        return object;
    }

    private MediaItemInfo fromJson(JSONObject object) throws JSONException {
        return new MediaItemInfo(
                object.optString("name"),
                Uri.parse(object.getString("uri")),
                object.optLong("sizeBytes"),
                object.optLong("modifiedTimeMs"),
                MediaKind.valueOf(object.getString("kind")),
                object.optInt("width"),
                object.optInt("height"),
                object.optLong("durationMs")
        );
    }
}
