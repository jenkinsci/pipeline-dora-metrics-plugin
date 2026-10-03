package io.jenkins.plugins.dorametrics.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeriodTest {

    private static final long NOW = Instant.parse("2026-03-10T12:00:00Z").toEpochMilli();

    @Test
    void aYearTooLargeForTheClockIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Period.of(null, "+300000000-01-01", "+300000000-01-02", "UTC", 30, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> Period.of(null, "2026-01-01", "+999999999-12-31", "UTC", 30, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> Period.of(null, "10000-01-01", "10000-01-02", "UTC", 30, NOW));
    }

    @Test
    void aRangeGivenOnlyInPartIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Period.of(null, "2026-03-01", null, "UTC", 30, NOW));
        assertThrows(IllegalArgumentException.class, () -> Period.of(null, "", "2026-03-01", "UTC", 30, NOW));
        assertThrows(IllegalArgumentException.class, () -> Period.of(null, "2026-03-01", "March", "UTC", 30, NOW));
    }

    @Test
    void daysAreAWindowEndingNow() {
        Period p = Period.of("7", null, null, "UTC", 30, NOW);
        assertEquals(7, p.days);
        assertEquals(NOW, p.toMs);
        assertEquals(NOW - 7 * 86_400_000L, p.fromMs);
    }

    @Test
    void anEndInTheFutureStopsAtToday() {
        Period p = Period.of(null, "2026-03-08", "2026-04-30", "UTC", 30, NOW);
        assertEquals(LocalDate.parse("2026-03-08"), p.firstDay);
        assertEquals(LocalDate.parse("2026-03-10"), p.lastDay);
        assertEquals(3, p.days);
    }

    @Test
    void aRangeWhollyInTheFutureIsToday() {
        Period p = Period.of(null, "2026-05-01", "2026-05-03", "UTC", 30, NOW);
        assertEquals(LocalDate.parse("2026-03-10"), p.firstDay);
        assertEquals(LocalDate.parse("2026-03-10"), p.lastDay);
        assertEquals(1, p.days);
    }

    @Test
    void todayFollowsTheViewersZone() {
        // 12:00 UTC on the 10th is already the 11th in Auckland
        Period p = Period.of(null, "2026-03-09", "2026-03-20", "Pacific/Auckland", 30, NOW);
        assertEquals(LocalDate.parse("2026-03-11"), p.lastDay);
    }

    @Test
    void aReversedRangeIsSwapped() {
        Period p = Period.of(null, "2026-03-05", "2026-03-01", "UTC", 30, NOW);
        assertEquals(LocalDate.parse("2026-03-01"), p.firstDay);
        assertEquals(LocalDate.parse("2026-03-05"), p.lastDay);
        assertEquals(5, p.days);
    }

    @Test
    void aRangeIsCappedAtTenYears() {
        Period p = Period.of(null, "0001-01-01", "2026-03-10", "UTC", 30, NOW);
        assertEquals(3650, p.days);
        assertEquals(LocalDate.parse("2026-03-10"), p.lastDay);
    }

    @Test
    void anUnknownZoneFallsBackToTheServers() {
        Period p = Period.of("7", null, null, "Nope/Zone", 30, NOW);
        assertEquals(ZoneId.systemDefault(), p.zone);
        assertEquals(7, p.days);
    }
}
