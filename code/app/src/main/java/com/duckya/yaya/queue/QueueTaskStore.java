package com.duckya.yaya.queue;

import android.content.Context;
import android.net.Uri;

import com.duckya.yaya.model.CompressionPreset;
import com.duckya.yaya.model.CompressionSettings;
import com.duckya.yaya.model.MediaItemInfo;
import com.duckya.yaya.model.MediaKind;
import com.duckya.yaya.model.QueueAction;
import com.duckya.yaya.model.QueueStatus;
import com.duckya.yaya.model.QueueTask;
import com.duckya.yaya.model.VideoAudioMode;
import com.duckya.yaya.model.VideoCodecOption;
import com.duckya.yaya.model.VideoCompressionPreset;
import com.duckya.yaya.model.VideoCompressionSettings;
import com.duckya.yaya.model.VideoFrameRateOption;
import com.duckya.yaya.model.VideoResolutionOption;

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

// 将任务队列保存到 app 私有目录，避免切后台或进程恢复后完成态丢失。
public class QueueTaskStore {
    private static final String STORE_FILE_NAME = "queue_tasks.json";

    public List<QueueTask> load(Context context) {
        File storeFile = storeFile(context);
        if (!storeFile.exists()) {
            return new ArrayList<>();
        }
        try {
            JSONArray array = new JSONArray(readAll(storeFile));
            List<QueueTask> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                result.add(fromJson(array.getJSONObject(i)));
            }
            return result;
        } catch (IOException | JSONException | IllegalArgumentException e) {
            return new ArrayList<>();
        }
    }

    public void save(Context context, List<QueueTask> tasks) {
        JSONArray array = new JSONArray();
        for (QueueTask task : tasks) {
            array.put(toJson(task));
        }
        try (FileWriter writer = new FileWriter(storeFile(context), false)) {
            writer.write(array.toString());
        } catch (IOException ignored) {
            // 持久化失败不影响当前内存队列，下一次操作仍会再次尝试保存。
        }
    }

    private File storeFile(Context context) {
        return new File(context.getFilesDir(), STORE_FILE_NAME);
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

    private JSONObject toJson(QueueTask task) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", task.getId());
            object.put("media", mediaToJson(task.getMedia()));
            object.put("action", task.getAction().name());
            object.put("settings", settingsToJson(task.getSettings()));
            object.put("videoSettings", videoSettingsToJson(task.getVideoSettings()));
            object.put("status", task.getStatus().name());
            object.put("progress", task.getProgress());
            object.put("actualOutputBytes", task.getActualOutputBytes());
            object.put("failureReason", task.getFailureReason());
            object.put("originalAssetUri", task.getOriginalAssetUri() == null
                    ? JSONObject.NULL
                    : task.getOriginalAssetUri().toString());
            object.put("compressedAssetUri", task.getCompressedAssetUri() == null
                    ? JSONObject.NULL
                    : task.getCompressedAssetUri().toString());
            object.put("originalRecycled", task.isOriginalRecycled());
            object.put("outputRecycled", task.isOutputRecycled());
        } catch (JSONException ignored) {
            // JSONObject 写入基础类型通常不会失败，这里保底返回已写字段。
        }
        return object;
    }

    private JSONObject mediaToJson(MediaItemInfo item) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("name", item.getName());
        object.put("uri", item.getUri().toString());
        object.put("sizeBytes", item.getSizeBytes());
        object.put("modifiedTimeMs", item.getModifiedTimeMs());
        object.put("kind", item.getKind().name());
        object.put("width", item.getWidth());
        object.put("height", item.getHeight());
        object.put("durationMs", item.getDurationMs());
        return object;
    }

    private JSONObject settingsToJson(CompressionSettings settings) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("preset", settings.getPreset().name());
        object.put("maxLongSide", settings.getMaxLongSide());
        object.put("jpegQuality", settings.getJpegQuality());
        object.put("adaptiveVisualLossless", settings.isAdaptiveVisualLossless());
        return object;
    }

    private QueueTask fromJson(JSONObject object) throws JSONException {
        MediaItemInfo media = mediaFromJson(object.getJSONObject("media"));
        QueueTask task = new QueueTask(
                object.getString("id"),
                media,
                QueueAction.valueOf(object.getString("action"))
        );
        String originalUriText = object.optString("originalAssetUri", "");
        String compressedUriText = object.optString("compressedAssetUri", "");
        task.restoreState(
                settingsFromJson(object.optJSONObject("settings")),
                videoSettingsFromJson(object.optJSONObject("videoSettings")),
                QueueStatus.valueOf(object.optString("status", QueueStatus.PENDING.name())),
                (float) object.optDouble("progress", 0.0),
                object.optLong("actualOutputBytes", 0L),
                object.optString("failureReason", null),
                originalUriText.isEmpty() || "null".equals(originalUriText) ? null : Uri.parse(originalUriText),
                compressedUriText.isEmpty() || "null".equals(compressedUriText) ? null : Uri.parse(compressedUriText),
                object.optBoolean("originalRecycled", false),
                object.optBoolean("outputRecycled", false)
        );
        return task;
    }

    private MediaItemInfo mediaFromJson(JSONObject object) throws JSONException {
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

    private CompressionSettings settingsFromJson(JSONObject object) {
        if (object == null) {
            return CompressionSettings.light();
        }
        return new CompressionSettings(
                CompressionPreset.valueOf(object.optString("preset", CompressionPreset.LIGHT.name())),
                object.optInt("maxLongSide"),
                object.optInt("jpegQuality"),
                object.optBoolean("adaptiveVisualLossless", false)
        );
    }

    private JSONObject videoSettingsToJson(VideoCompressionSettings settings) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("preset", settings.getPreset().name());
        object.put("resolutionOption", settings.getResolutionOption().name());
        object.put("codecOption", settings.getCodecOption().name());
        object.put("targetBitrateMbps", settings.getTargetBitrateMbps());
        object.put("autoBitrate", settings.isAutoBitrate());
        object.put("audioMode", settings.getAudioMode().name());
        object.put("keepFrameRate", settings.isKeepFrameRate());
        object.put("frameRateOption", settings.getFrameRateOption().name());
        object.put("limitToOneGb", settings.isLimitToOneGb());
        object.put("fallbackToH264", settings.isFallbackToH264());
        return object;
    }

    private VideoCompressionSettings videoSettingsFromJson(JSONObject object) {
        if (object == null) {
            return VideoCompressionSettings.balanced();
        }
        return new VideoCompressionSettings(
                enumValue(object.optString("preset"), VideoCompressionPreset.BALANCED),
                enumValue(object.optString("resolutionOption"), VideoResolutionOption.P1080),
                enumValue(object.optString("codecOption"), VideoCodecOption.AUTO),
                (float) object.optDouble("targetBitrateMbps", 9.0),
                object.optBoolean("autoBitrate", true),
                enumValue(object.optString("audioMode"), VideoAudioMode.KEEP),
                enumValue(
                        object.optString("frameRateOption"),
                        object.optBoolean("keepFrameRate", true)
                                ? VideoFrameRateOption.ORIGINAL
                                : VideoFrameRateOption.FPS30
                ),
                object.optBoolean("limitToOneGb", false),
                object.optBoolean("fallbackToH264", true)
        );
    }

    private <T extends Enum<T>> T enumValue(String name, T fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
