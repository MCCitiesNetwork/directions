package com.minecraftcitiesnetwork.directions.config;

import com.minecraftcitiesnetwork.directions.pathfinding.PathfindingSettings;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code pathfinding:} block maps and validates without a server. */
class PathfindingConfigTest {

    private static ConfigLoader.MainConfig load(String yaml) throws ConfigurateException {
        CommentedConfigurationNode node = YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(yaml)))
                .build()
                .load();
        return node.get(ConfigLoader.MainConfig.class);
    }

    @Test
    void absentSectionMeansDisabledWithDefaults() throws ConfigurateException {
        ConfigLoader.MainConfig cfg = load("transfer-penalty: 50\n");
        PathfindingSettings s = ConfigLoader.readPathfinding(cfg.pathfinding);
        assertFalse(s.enabled());
        assertEquals(2, s.walker().height());
        assertEquals(Duration.ofMillis(250), s.routing().timeBudget());
        assertEquals(8, s.chunks().snapshotsPerTick());
        assertTrue(s.chunks().diskReader());
    }

    @Test
    void sectionValuesAreRead() throws ConfigurateException {
        ConfigLoader.MainConfig cfg = load("""
                pathfinding:
                  enabled: true
                  worlds: [Reveille]
                  routing:
                    max-leg-distance: 250
                    time-budget-ms: 300
                  guidance:
                    look-ahead: 8
                    replan-distance: 10
                  walker:
                    max-drop: 2
                    water-cost: 3.0
                  engine:
                    threads: 3
                  chunks:
                    disk-reader: false
                    flag-band-height: 48
                """);
        PathfindingSettings s = ConfigLoader.readPathfinding(cfg.pathfinding);
        assertTrue(s.enabled());
        assertTrue(s.enabledIn("reveille"));
        assertFalse(s.enabledIn("world_nether"));
        assertEquals(250, s.routing().maxLegDistance());
        assertEquals(Duration.ofMillis(300), s.routing().timeBudget());
        assertEquals(8, s.guidance().lookAhead());
        assertEquals(10, s.guidance().replanDistance());
        assertEquals(2, s.walker().maxDrop());
        assertEquals(3.0, s.walker().waterCost());
        assertEquals(3, s.engine().threads());
        assertFalse(s.chunks().diskReader());
        assertEquals(48, s.chunks().flagBandHeight());
    }

    @Test
    void costMultipliersBelowOneAreRejected() throws ConfigurateException {
        ConfigLoader.MainConfig cfg = load("""
                pathfinding:
                  walker:
                    door-cost: 0.5
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ConfigLoader.readPathfinding(cfg.pathfinding));
        assertTrue(e.getMessage().contains("pathfinding"), e.getMessage());
    }

    @Test
    void outOfRangeEngineSettingsAreRejected() throws ConfigurateException {
        ConfigLoader.MainConfig cfg = load("""
                pathfinding:
                  engine:
                    threads: 0
                """);
        assertThrows(IllegalStateException.class, () -> ConfigLoader.readPathfinding(cfg.pathfinding));
    }

    @Test
    void routingAndGuidanceRespectWorldAllowlist() throws ConfigurateException {
        ConfigLoader.MainConfig cfg = load("""
                pathfinding:
                  enabled: true
                  worlds: [city]
                  guidance:
                    enabled: false
                """);
        PathfindingSettings s = ConfigLoader.readPathfinding(cfg.pathfinding);
        assertTrue(s.routingEnabledIn("City"));
        assertFalse(s.routingEnabledIn("other"));
        assertFalse(s.guidanceEnabledIn("city"));
    }
}
