package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.model.Stop;
import com.minecraftcitiesnetwork.directions.pathfinding.Pos;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkResult;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Real walking distances between stops, computed once per reload in the background, and the
 * standing cell of each stop. The transit graph uses the distances in place of straight lines.
 */
public final class WalkingDistanceCache {
    private final Map<String, Double> distances = new ConcurrentHashMap<>();
    private final Map<String, Pos> stopPositions = new ConcurrentHashMap<>();
    private volatile boolean computing;
    private volatile long lastDurationMillis;
    private volatile int lastPairs;
    private volatile int lastFound;

    public static String pairKey(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }

    public Optional<Double> distance(String a, String b) {
        return Optional.ofNullable(distances.get(pairKey(a, b)));
    }

    public Optional<Pos> position(String stopId) {
        return Optional.ofNullable(stopPositions.get(stopId.toLowerCase(Locale.ROOT)));
    }

    public Map<String, Double> snapshot() {
        return Map.copyOf(distances);
    }

    public boolean computing() {
        return computing;
    }

    public int size() {
        return distances.size();
    }

    public String summary() {
        return distances.size() + " walking edges (" + lastFound + "/" + lastPairs + " pairs walkable, " + lastDurationMillis + " ms)";
    }

    /**
     * Recomputes every stop pair within {@code maxEuclid} of each other. Call on the main thread;
     * {@code onDone} runs on the main thread when finished.
     */
    public void recompute(Collection<Stop> stops, double maxEuclid, WalkPathService service, Logger log, Runnable onDone) {
        if (computing) {
            return;
        }
        computing = true;
        Map<String, World> worlds = new HashMap<>();
        for (Stop s : stops) {
            worlds.computeIfAbsent(s.worldName(), Bukkit::getWorld);
        }
        List<Stop> list = new ArrayList<>(stops);
        service.submit(() -> {
            long t0 = System.nanoTime();
            Map<String, Double> fresh = new HashMap<>();
            Map<String, Pos> positions = new HashMap<>();
            int pairs = 0;
            int found = 0;
            try {
                for (Stop s : list) {
                    World w = worlds.get(s.worldName());
                    if (w == null || !service.settings().enabledIn(w.getName())) {
                        continue;
                    }
                    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                    CachedBlockGrid grid = new CachedBlockGrid(w, service.cache(), deadline);
                    StopLocator.locate(grid, service.pathfinder().profile(), s)
                            .ifPresent(p -> positions.put(s.regionId().toLowerCase(Locale.ROOT), p));
                }
                for (int i = 0; i < list.size(); i++) {
                    for (int j = i + 1; j < list.size(); j++) {
                        Stop a = list.get(i);
                        Stop b = list.get(j);
                        if (!a.worldName().equalsIgnoreCase(b.worldName()) || a.distance2D(b) > maxEuclid) {
                            continue;
                        }
                        Pos pa = positions.get(a.regionId().toLowerCase(Locale.ROOT));
                        Pos pb = positions.get(b.regionId().toLowerCase(Locale.ROOT));
                        World w = worlds.get(a.worldName());
                        if (pa == null || pb == null || w == null) {
                            continue;
                        }
                        pairs++;
                        WalkResult r = service.findBlocking(w, pa, pb, maxEuclid * 2.0, service.settings().routing().timeBudget().multipliedBy(4));
                        if (r.found()) {
                            fresh.put(pairKey(a.regionId(), b.regionId()), r.path().totalDistance());
                            found++;
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warning("Walking-distance precompute failed: " + e);
            }
            distances.clear();
            distances.putAll(fresh);
            stopPositions.clear();
            stopPositions.putAll(positions);
            lastPairs = pairs;
            lastFound = found;
            lastDurationMillis = (System.nanoTime() - t0) / 1_000_000;
            computing = false;
            log.info("Precomputed " + summary());
            service.mainThread().run(onDone);
        });
    }
}
