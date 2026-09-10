package com.minecraftcitiesnetwork.directions.pathfinding;

import com.minecraftcitiesnetwork.directions.pathfinding.grid.ArrayBlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.GridCodec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L1: real-terrain regression on captures kept in the local-only sample-data folder. Skips when the
 * folder or its scenarios file is absent (CI, other machines).
 *
 * <pre>
 * scenarios:
 *   - name: merkel-to-richmond
 *     file: merkel-richmond.grid.gz
 *     from: [x, y, z]          # local coordinates (world minus the capture origin)
 *     to: [x, y, z]
 *     expect: found            # found | none
 *     golden-length: 213.1     # optional, checked within 10%
 *     forbid: [[x, y, z]]      # optional
 * </pre>
 */
@Tag("captured")
class CapturedTerrainTest {
    private static final WalkerProfile P = WalkerProfile.DEFAULT;

    @TestFactory
    List<DynamicTest> capturedScenarios() throws IOException {
        Path root = Path.of(System.getProperty("directions.sampleData", "sample-data")).resolve("captures");
        Path file = root.resolve("scenarios.yml");
        assumeTrue(Files.isRegularFile(file), "sample data not available: " + file);

        ConfigurationNode node = YamlConfigurationLoader.builder().path(file).build().load();
        WalkPathfinder finder = new WalkPathfinder(P, 500_000);
        Map<String, ArrayBlockGrid> grids = new HashMap<>();
        List<DynamicTest> tests = new ArrayList<>();
        for (ConfigurationNode s : node.node("scenarios").childrenList()) {
            String name = s.node("name").getString("unnamed");
            String gridFile = s.node("file").getString();
            Pos from = pos(s.node("from"));
            Pos to = pos(s.node("to"));
            String expect = s.node("expect").getString("found");
            double golden = s.node("golden-length").getDouble(0);
            List<Pos> forbid = new ArrayList<>();
            for (ConfigurationNode f : s.node("forbid").childrenList()) {
                forbid.add(pos(f));
            }
            tests.add(DynamicTest.dynamicTest(name, () -> {
                ArrayBlockGrid grid = grids.computeIfAbsent(gridFile, g -> {
                    try {
                        return GridCodec.read(root.resolve(g));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                WalkResult r = finder.find(grid, new WalkRequest(from, to, 0, Duration.ofSeconds(10)));
                if (expect.equals("none")) {
                    assertFalse(r.found(), name + ": expected no path, got " + r);
                    return;
                }
                WalkPath path = PathAssertions.assertFound(r, grid, P);
                for (Pos f : forbid) {
                    assertFalse(path.nodes().contains(f), name + ": path enters forbidden cell " + f);
                }
                if (golden > 0) {
                    assertEquals(golden, path.totalDistance(), golden * 0.10, name + ": length drifted from golden");
                }
                System.out.printf("captured: %-32s %s%n", name, r);
            }));
        }
        return tests;
    }

    private static Pos pos(ConfigurationNode n) {
        List<? extends ConfigurationNode> c = n.childrenList();
        return new Pos(c.get(0).getInt(), c.get(1).getInt(), c.get(2).getInt());
    }
}
