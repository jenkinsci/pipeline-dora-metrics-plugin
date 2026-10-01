package io.jenkins.plugins.dorametrics.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationFormatterTest {

    @Test
    void formatSeconds() {
        assertEquals("0s", DurationFormatter.format(0));
        assertEquals("1s", DurationFormatter.format(1000));
        assertEquals("30s", DurationFormatter.format(30000));
        assertEquals("59s", DurationFormatter.format(59999));
    }

    @Test
    void formatMinutes() {
        assertEquals("1m 0s", DurationFormatter.format(60000));
        assertEquals("2m 30s", DurationFormatter.format(150000));
        assertEquals("59m 59s", DurationFormatter.format(3599999));
    }

    @Test
    void formatHours() {
        assertEquals("1.0h", DurationFormatter.format(3600000));
        assertEquals("2.5h", DurationFormatter.format(9000000));
        assertEquals("24.0h", DurationFormatter.format(86399999));
    }

    @Test
    void formatDays() {
        assertEquals("1.0d", DurationFormatter.format(86400000));
        assertEquals("7.0d", DurationFormatter.format(604800000));
    }

    @Test
    void parseDaysValid() {
        assertEquals(7, DurationFormatter.parseDays("7", 30));
        assertEquals(90, DurationFormatter.parseDays("90", 30));
        assertEquals(365, DurationFormatter.parseDays("365", 30));
    }

    @Test
    void parseDaysDefaults() {
        assertEquals(30, DurationFormatter.parseDays(null, 30));
        assertEquals(30, DurationFormatter.parseDays("", 30));
        assertEquals(30, DurationFormatter.parseDays("abc", 30));
    }

    @Test
    void parseDaysClamped() {
        assertEquals(1, DurationFormatter.parseDays("-5", 30));
        assertEquals(1, DurationFormatter.parseDays("0", 30));
        assertEquals(3650, DurationFormatter.parseDays("99999", 30));
    }

    @Test
    void parseLimitValid() {
        assertEquals(5, DurationFormatter.parseLimit("5", 10));
        assertEquals(50, DurationFormatter.parseLimit("50", 10));
    }

    @Test
    void parseLimitClamped() {
        assertEquals(1, DurationFormatter.parseLimit("0", 10));
        assertEquals(100, DurationFormatter.parseLimit("999", 10));
    }
}
