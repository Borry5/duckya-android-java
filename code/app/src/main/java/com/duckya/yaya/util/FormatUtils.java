package com.duckya.yaya.util;

import java.util.Locale;

public final class FormatUtils {

    private FormatUtils() {
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0L) {
            return "0 B";
        }
        double kb = bytes / 1024.0;
        double mb = kb / 1024.0;
        double gb = mb / 1024.0;
        if (gb >= 1.0) {
            return String.format(Locale.getDefault(), "%.2f GB", gb);
        }
        if (mb >= 1.0) {
            return String.format(Locale.getDefault(), "%.1f MB", mb);
        }
        if (kb >= 1.0) {
            return String.format(Locale.getDefault(), "%.0f KB", kb);
        }
        return bytes + " B";
    }

    public static String formatDuration(long durationMs) {
        if (durationMs <= 0L) {
            return "0 秒";
        }
        long totalSeconds = Math.max(1L, Math.round(durationMs / 1000.0));
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return seconds == 0L
                    ? String.format(Locale.getDefault(), "%d 小时 %d 分", hours, minutes)
                    : String.format(Locale.getDefault(), "%d 小时 %d 分 %d 秒", hours, minutes, seconds);
        }
        if (minutes > 0L) {
            return seconds == 0L
                    ? String.format(Locale.getDefault(), "%d 分", minutes)
                    : String.format(Locale.getDefault(), "%d 分 %d 秒", minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%d 秒", seconds);
    }
}
