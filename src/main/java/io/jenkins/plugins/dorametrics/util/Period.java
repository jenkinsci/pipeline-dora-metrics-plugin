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
 * A range never runs past today, and a range given only in part is refused.
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
        boolean range = !isEmpty(fromParam) || !isEmpty(toParam);
        if (range && (from == null || to == null)) {
            throw new IllegalArgumentException("from and to must both be dates, as YYYY-MM-DD");
        }
        if (range) {
            if (to.isBefore(from)) {
                LocalDate swap = from;
                from = to;
                to = swap;
            }
            // days that have not happened yet would only dilute the per-day figures
            LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
            if (to.isAfter(today)) to = today;
            if (from.isAfter(today)) from = today;
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

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static LocalDate date(String value) {
        if (isEmpty(value)) return null;
        try {
            LocalDate date = LocalDate.parse(value);
            // ISO dates allow signed years far beyond what fits in epoch milliseconds
            return date.getYear() < 1 || date.getYear() > 9999 ? null : date;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
