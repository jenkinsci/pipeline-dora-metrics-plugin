package io.jenkins.plugins.dorametrics.util;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * The period an API request asks about: either the last {@code days} days, or the calendar
 * days {@code from} to {@code to}, both included, in the viewer's time zone {@code tz}.
 */
public final class Period {

    private static final long DAY_MS = 86_400_000L;
    private static final int MAX_DAYS = 3650;

    public final long fromMs;
    public final long toMs;
    public final int days;
    public final ZoneId zone;
    public final LocalDate firstDay;
    public final LocalDate lastDay;

    private Period(long fromMs, long toMs, int days, ZoneId zone) {
        this.fromMs = fromMs;
        this.toMs = toMs;
        this.days = days;
        this.zone = zone;
        this.firstDay = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate();
        this.lastDay = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate();
    }

    public static Period of(String daysParam, String fromParam, String toParam, String tzParam,
                            int defaultDays, long nowMs) {
        ZoneId zone = zone(tzParam);
        LocalDate from = date(fromParam);
        LocalDate to = date(toParam);
        if (from != null && to != null) {
            if (to.isBefore(from)) {
                LocalDate swap = from;
                from = to;
                to = swap;
            }
            if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
                from = to.minusDays(MAX_DAYS - 1);
            }
            long start = from.atStartOfDay(zone).toInstant().toEpochMilli();
            long end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
            return new Period(start, end, (int) ChronoUnit.DAYS.between(from, to) + 1, zone);
        }
        int days = DurationFormatter.parseDays(daysParam, defaultDays);
        return new Period(nowMs - days * DAY_MS, nowMs, days, zone);
    }

    private static ZoneId zone(String tz) {
        if (tz != null && !tz.isEmpty()) {
            try {
                return ZoneId.of(tz);
            } catch (DateTimeException e) {
                // an unknown zone falls back to the server's
            }
        }
        return ZoneId.systemDefault();
    }

    private static LocalDate date(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
