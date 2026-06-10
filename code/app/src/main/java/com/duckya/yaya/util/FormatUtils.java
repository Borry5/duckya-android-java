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
}
