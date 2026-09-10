package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.Pos;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkResult;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Headless verification on a real server. Started when {@code -Ddirections.selftest=<scenarios.yml>}
 * is set: optionally builds the synthetic course world, runs every scenario through the service,
 * prints one line per result, writes a JSON report, and shuts the server down.
 */
public final class SelfTestRunner {
    public static final String PROPERTY = "directions.selftest";
    public static final String REPORT_PROPERTY = "directions.selftest.report";

    private record Scenario(String name, String world, Pos from, Pos to, String expect, double maxDistance,
                            double maxLength, List<Pos> forbid) {
    }

    private record Outcome(Scenario scenario, WalkResult result, boolean ok, String note) {
    }

    private final JavaPlugin plugin;
    private final WalkPathService service;
    private boolean finished;

    public SelfTestRunner(JavaPlugin plugin, WalkPathService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public static boolean requested() {
        return System.getProperty(PROPERTY) != null;
    }

    public void start() {
        Bukkit.getScheduler().runTaskLater(plugin, this::run, 40L);
        // Watchdog: never leave a headless server running if something stalls.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!finished) {
                plugin.getLogger().severe("SELFTEST watchdog: scenarios did not finish in time; shutting down");
                finish(new ArrayList<>(List.of(new Outcome(new Scenario("watchdog", "", null, null, "found", 0, 0, List.of()), null, false, "timed out"))));
            }
        }, 20L * 60 * 5);
    }

    private void run() {
        File file = new File(System.getProperty(PROPERTY));
        plugin.getLogger().info("SELFTEST starting with " + file);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.getBoolean("course", false)) {
            CourseBuilder.build(plugin);
        }
        List<Scenario> scenarios = new ArrayList<>();
        for (Map<?, ?> m : yaml.getMapList("scenarios")) {
            scenarios.add(new Scenario(
                    String.valueOf(m.get("name")),
                    m.get("world") == null ? CourseBuilder.WORLD_NAME : String.valueOf(m.get("world")),
                    pos(m.get("from")),
                    pos(m.get("to")),
                    (m.get("expect") == null ? "found" : String.valueOf(m.get("expect"))).toLowerCase(Locale.ROOT),
                    m.get("max-distance") instanceof Number n ? n.doubleValue() : 0,
                    m.get("max-length") instanceof Number n ? n.doubleValue() : 0,
                    m.get("forbid") instanceof List<?> l ? l.stream().map(SelfTestRunner::pos).toList() : List.of()
            ));
        }
        List<Outcome> outcomes = new ArrayList<>();
        // Give the pump and chunk system a moment after world creation, then run sequentially.
        Bukkit.getScheduler().runTaskLater(plugin, () -> runNext(scenarios, 0, outcomes), 40L);
    }

    private void runNext(List<Scenario> scenarios, int index, List<Outcome> outcomes) {
        if (index >= scenarios.size()) {
            finish(outcomes);
            return;
        }
        Scenario s = scenarios.get(index);
        World world = Bukkit.getWorld(s.world);
        if (world == null) {
            outcomes.add(new Outcome(s, null, false, "world not found: " + s.world));
            plugin.getLogger().warning("SELFTEST " + s.name + " FAIL world not found");
            runNext(scenarios, index + 1, outcomes);
            return;
        }
        service.find(world, s.from, s.to, s.maxDistance, Duration.ofSeconds(5)).whenComplete((result, error) -> {
            Outcome o;
            if (error != null || result == null) {
                o = new Outcome(s, null, false, "error: " + error);
            } else {
                o = judge(s, result);
            }
            outcomes.add(o);
            plugin.getLogger().info("SELFTEST " + json(o));
            runNext(scenarios, index + 1, outcomes);
        });
    }

    private static Outcome judge(Scenario s, WalkResult r) {
        boolean ok;
        String note = "";
        switch (s.expect) {
            case "found" -> {
                ok = r.found();
                if (ok && s.maxLength > 0 && r.path().totalDistance() > s.maxLength) {
                    ok = false;
                    note = "path " + Math.round(r.path().totalDistance()) + " blocks exceeds max-length " + s.maxLength;
                }
                if (ok) {
                    for (Pos f : s.forbid) {
                        if (r.path().nodes().contains(f)) {
                            ok = false;
                            note = "path enters forbidden cell " + f;
                            break;
                        }
                    }
                }
                if (!ok && note.isEmpty()) {
                    note = "expected a path, got " + r.status() + (r.detail().isEmpty() ? "" : ": " + r.detail());
                }
            }
            case "none" -> {
                ok = !r.found();
                if (!ok) {
                    note = "expected no path, got one of " + Math.round(r.path().totalDistance()) + " blocks";
                }
            }
            default -> ok = true;
        }
        return new Outcome(s, r, ok, note);
    }

    private void finish(List<Outcome> outcomes) {
        if (finished) {
            return;
        }
        finished = true;
        long failed = outcomes.stream().filter(o -> !o.ok).count();
        StringBuilder report = new StringBuilder("{\"passed\":").append(outcomes.size() - failed)
                .append(",\"failed\":").append(failed).append(",\"results\":[");
        for (int i = 0; i < outcomes.size(); i++) {
            if (i > 0) {
                report.append(',');
            }
            report.append(json(outcomes.get(i)));
        }
        report.append("]}");
        Path out = Path.of(System.getProperty(REPORT_PROPERTY, new File(plugin.getDataFolder(), "selftest-report.json").getPath()));
        try {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, report.toString());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "SELFTEST could not write report", e);
        }
        plugin.getLogger().info("SELFTEST RESULT: " + (failed == 0 ? "PASS" : "FAIL") + " (" + (outcomes.size() - failed) + "/" + outcomes.size() + ")");
        plugin.getLogger().info("SELFTEST stats: searches=" + service.metrics().total()
                + " avg=" + String.format("%.1f", service.metrics().averageMillis()) + "ms"
                + " p95=" + String.format("%.1f", service.metrics().percentileMillis(95)) + "ms"
                + " chunks=" + service.cache().cachedChunks()
                + " snapshot=" + service.cache().snapshotLoads() + " disk=" + service.cache().diskLoads()
                + " unloaded=" + service.cache().unloadedMisses() + " pumpMaxTick=" + String.format("%.2f", service.pump().maxTickMillis()) + "ms");
        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 20L);
    }

    private static String json(Outcome o) {
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(escape(o.scenario.name)).append("\",\"ok\":").append(o.ok);
        if (o.result != null) {
            sb.append(",\"status\":\"").append(o.result.status()).append("\"");
            sb.append(",\"ms\":").append(String.format(Locale.ROOT, "%.1f", o.result.millis()));
            if (o.result.path() != null) {
                sb.append(",\"length\":").append(String.format(Locale.ROOT, "%.1f", o.result.path().totalDistance()));
                sb.append(",\"nodes\":").append(o.result.path().size());
            }
            if (o.result.start() != null) {
                sb.append(",\"start\":\"").append(o.result.start()).append("\"");
            }
            if (o.result.target() != null) {
                sb.append(",\"target\":\"").append(o.result.target()).append("\"");
            }
        }
        if (!o.note.isEmpty()) {
            sb.append(",\"note\":\"").append(escape(o.note)).append("\"");
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Pos pos(Object o) {
        if (o instanceof List<?> l && l.size() == 3) {
            return new Pos(((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue(), ((Number) l.get(2)).intValue());
        }
        throw new IllegalArgumentException("position must be [x, y, z], got " + o);
    }
}
