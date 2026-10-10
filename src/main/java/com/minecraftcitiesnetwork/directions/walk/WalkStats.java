package com.minecraftcitiesnetwork.directions.walk;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/** Counters and timings for {@code /directions debug walk}. Thread-safe. */
public final class WalkStats {
    private static final int SAMPLES = 512;
    private static final long MINUTE = 60_000;

    private final LongSupplier millis;
    private final long[] durations = new long[SAMPLES];
    private final long[] coarseDurations = new long[SAMPLES];
    private int coarseStored;
    private int coarseNext;
    private final EnumMap<SearchResult.Status, Long> results = new EnumMap<>(SearchResult.Status.class);
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private int stored;
    private int next;
    private long hits;
    private long misses;
    private long guided;
    private long fallbacks;
    private long worstPump;
    private long previousWorstPump;
    private long pumpWindowStart;

    public WalkStats(LongSupplier millisClock) {
        this.millis = millisClock;
        this.pumpWindowStart = millisClock.getAsLong();
    }

    public synchronized void record(SearchResult result) {
        durations[next] = result.nanos();
        next = (next + 1) % SAMPLES;
        stored = Math.min(stored + 1, SAMPLES);
        results.merge(result.status(), 1L, Long::sum);
        if (result.coarse()) {
            guided++;
        }
        long now = millis.getAsLong();
        recent.addLast(now);
        prune(now);
    }

    /** Time one search spent planning over the coarse graph. */
    public synchronized void recordCoarse(long nanos) {
        coarseDurations[coarseNext] = nanos;
        coarseNext = (coarseNext + 1) % SAMPLES;
        coarseStored = Math.min(coarseStored + 1, SAMPLES);
    }

    /** A search had a graph and a goal beyond the window, but the coarse graph did not lead it. */
    public synchronized void recordFallback() {
        fallbacks++;
    }

    public synchronized void cacheHit() {
        hits++;
    }

    public synchronized void cacheMiss() {
        misses++;
    }

    public synchronized void recordPumpTick(long nanos) {
        long now = millis.getAsLong();
        if (now - pumpWindowStart >= MINUTE) {
            previousWorstPump = worstPump;
            worstPump = 0;
            pumpWindowStart = now;
        }
        worstPump = Math.max(worstPump, nanos);
    }

    public synchronized double searchesPerSecond() {
        prune(millis.getAsLong());
        return recent.size() / 60.0;
    }

    public synchronized long percentileNanos(double p) {
        return percentile(durations, stored, p);
    }

    public synchronized long coarsePercentileNanos(double p) {
        return percentile(coarseDurations, coarseStored, p);
    }

    private static long percentile(long[] samples, int count, double p) {
        if (count == 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(samples, count);
        Arrays.sort(copy);
        int index = (int) Math.ceil(p * count) - 1;
        return copy[Math.max(0, Math.min(count - 1, index))];
    }

    public synchronized long results(SearchResult.Status status) {
        return results.getOrDefault(status, 0L);
    }

    public synchronized double hitRate() {
        long total = hits + misses;
        return total == 0 ? 1.0 : (double) hits / total;
    }

    /** The slowest chunk-capture tick over the last one to two minutes. */
    public synchronized long worstPumpNanos() {
        return Math.max(worstPump, previousWorstPump);
    }

    public synchronized List<String> describe(int cachedChunks) {
        StringBuilder counts = new StringBuilder("Results:");
        for (SearchResult.Status status : SearchResult.Status.values()) {
            counts.append(' ').append(status).append(' ').append(results(status));
        }
        return List.of(
                String.format(Locale.ROOT, "Searches: %.2f/s over the last minute", searchesPerSecond()),
                String.format(Locale.ROOT, "Search time: p50 %.1f ms, p95 %.1f ms (last %d)",
                        percentileNanos(0.50) / 1e6, percentileNanos(0.95) / 1e6, stored),
                counts.toString(),
                String.format(Locale.ROOT, "Chunk cache: %d chunks, hit rate %.0f%%", cachedChunks, hitRate() * 100),
                String.format(Locale.ROOT, "Worst capture tick (last minute): %.2f ms", worstPumpNanos() / 1e6),
                String.format(Locale.ROOT, "Coarse: %d guided, %d fell back", guided, fallbacks),
                String.format(Locale.ROOT, "Coarse search: p50 %.1f ms, p95 %.1f ms (last %d)",
                        coarsePercentileNanos(0.50) / 1e6, coarsePercentileNanos(0.95) / 1e6, coarseStored));
    }

    private void prune(long now) {
        while (!recent.isEmpty() && now - recent.peekFirst() > MINUTE) {
            recent.pollFirst();
        }
    }
}
