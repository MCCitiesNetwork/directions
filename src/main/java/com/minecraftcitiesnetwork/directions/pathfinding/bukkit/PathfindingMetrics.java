package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.WalkResult;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Counters and a small latency reservoir for the stats command. Thread-safe. */
public final class PathfindingMetrics {
    private static final int RESERVOIR = 512;

    private final Map<WalkResult.Status, AtomicLong> byStatus = new EnumMap<>(WalkResult.Status.class);
    private final long[] latencyNanos = new long[RESERVOIR];
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();

    public PathfindingMetrics() {
        for (WalkResult.Status s : WalkResult.Status.values()) {
            byStatus.put(s, new AtomicLong());
        }
    }

    public void record(WalkResult result) {
        byStatus.get(result.status()).incrementAndGet();
        long n = recorded.getAndIncrement();
        latencyNanos[(int) (n % RESERVOIR)] = result.nanos();
        totalNanos.addAndGet(result.nanos());
    }

    public long count(WalkResult.Status status) {
        return byStatus.get(status).get();
    }

    public long total() {
        return recorded.get();
    }

    public double averageMillis() {
        long n = recorded.get();
        return n == 0 ? 0 : totalNanos.get() / 1_000_000.0 / n;
    }

    /** Percentile over the most recent samples, in milliseconds. */
    public double percentileMillis(double p) {
        int n = (int) Math.min(recorded.get(), RESERVOIR);
        if (n == 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(latencyNanos, n);
        Arrays.sort(copy);
        int idx = (int) Math.min(n - 1, Math.max(0, Math.round(p / 100.0 * (n - 1))));
        return copy[idx] / 1_000_000.0;
    }
}
