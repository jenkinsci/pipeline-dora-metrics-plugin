package io.jenkins.plugins.dorametrics.util;

import java.util.Locale;

/**
 * Shared duration formatting utility. Single source of truth for
 * converting milliseconds to human-readable strings.
 */
public final class DurationFormatter {

    private DurationFormatter() {}

    public static String format(long ms) {
        if (ms < 60_000) {
            return (ms / 1000) + "s";
        } else if (ms < 3600_000) {
            long min = ms / 60_000;
            long sec = (ms % 60_000) / 1000;
            return min + "m " + sec + "s";
        } else if (ms < 86400_000) {
            return String.format(Locale.ROOT, "%.1fh", ms / 3600_000.0);
        } else {
            return String.format(Locale.ROOT, "%.1fd", ms / 86400_000.0);
        }
    }

    public static int parseDays(String param, int defaultVal) {
        if (param == null || param.isEmpty()) return defaultVal;
        try {
            int val = Integer.parseInt(param);
            return Math.max(1, Math.min(val, 3650));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    public static int parseLimit(String param, int defaultVal) {
        if (param == null || param.isEmpty()) return defaultVal;
        try {
            int val = Integer.parseInt(param);
            return Math.max(1, Math.min(val, 100));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
