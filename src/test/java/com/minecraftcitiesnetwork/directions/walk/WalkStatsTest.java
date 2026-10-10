package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkStatsTest {

    @Test
    void percentilesOverRecordedSearches() {
        WalkStats stats = new WalkStats(() -> 0L);
        for (int ms = 1; ms <= 100; ms++) {
            stats.record(SearchResult.failed(SearchResult.Status.NO_ROUTE, 0, ms * 1_000_000L));
        }
        assertEquals(50_000_000L, stats.percentileNanos(0.50));
        assertEquals(95_000_000L, stats.percentileNanos(0.95));
        assertEquals(100, stats.results(SearchResult.Status.NO_ROUTE));
    }

    @Test
    void rateCountsTheLastMinuteOnly() {
        AtomicLong clock = new AtomicLong();
        WalkStats stats = new WalkStats(clock::get);
        for (int i = 0; i < 30; i++) {
            stats.record(SearchResult.failed(SearchResult.Status.BUDGET, 0, 1));
        }
        assertEquals(0.5, stats.searchesPerSecond(), 1e-9);
        clock.set(61_000);
        assertEquals(0.0, stats.searchesPerSecond(), 1e-9);
    }

    @Test
    void worstPumpTickCoversTheLastMinute() {
        AtomicLong clock = new AtomicLong();
        WalkStats stats = new WalkStats(clock::get);
        stats.recordPumpTick(400_000);
        clock.set(61_000);
        stats.recordPumpTick(100_000);
        assertEquals(400_000, stats.worstPumpNanos());
        clock.set(122_000);
        stats.recordPumpTick(50_000);
        assertEquals(100_000, stats.worstPumpNanos());
    }

    @Test
    void hitRateAndDescription() {
        WalkStats stats = new WalkStats(() -> 0L);
        stats.cacheHit();
        stats.cacheHit();
        stats.cacheHit();
        stats.cacheMiss();
        assertEquals(0.75, stats.hitRate(), 1e-9);
        assertTrue(stats.describe(812).stream().anyMatch(line -> line.contains("812 chunks") && line.contains("75%")));
    }

    @Test
    void coarseResultsAreCountedAsGuided() {
        WalkStats stats = new WalkStats(() -> 0L);
        stats.record(new SearchResult(SearchResult.Status.PARTIAL, null, 0.0, 0, 1, true));
        stats.record(SearchResult.failed(SearchResult.Status.NO_ROUTE, 0, 1));
        assertTrue(stats.describe(0).contains("Coarse: 1 guided, 0 fell back"));
    }

    @Test
    void coarseSearchTimesAreReported() {
        WalkStats stats = new WalkStats(() -> 0L);
        for (int ms = 1; ms <= 20; ms++) {
            stats.recordCoarse(ms * 1_000_000L);
        }
        assertTrue(stats.describe(0).contains("Coarse search: p50 10.0 ms, p95 19.0 ms (last 20)"),
                stats.describe(0).toString());
    }

    @Test
    void fallbacksAreCounted() {
        WalkStats stats = new WalkStats(() -> 0L);
        stats.recordFallback();
        stats.recordFallback();
        assertTrue(stats.describe(0).contains("Coarse: 0 guided, 2 fell back"));
    }
}
