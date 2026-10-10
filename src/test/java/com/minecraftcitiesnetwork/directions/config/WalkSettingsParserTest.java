package com.minecraftcitiesnetwork.directions.config;

import com.minecraftcitiesnetwork.directions.walk.WalkSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkSettingsParserTest {

    @Test
    void defaultSectionParsesToTheDefaultsWithoutWarnings() {
        List<String> warnings = new ArrayList<>();
        WalkSettings settings = WalkSettingsParser.parse(new ConfigLoader.WalkingPathsSection(), warnings::add);
        assertEquals(WalkSettings.DEFAULTS, settings);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void outOfRangeValuesFallBackWithAWarningEach() {
        ConfigLoader.WalkingPathsSection section = new ConfigLoader.WalkingPathsSection();
        section.windowRadius = 5000;
        section.waterCost = 0.5;
        section.maxDrop = 9;
        List<String> warnings = new ArrayList<>();
        WalkSettings settings = WalkSettingsParser.parse(section, warnings::add);
        assertEquals(80, settings.windowRadius());
        assertEquals(2.5, settings.waterCost());
        assertEquals(3, settings.maxDrop());
        assertEquals(3, warnings.size());
        assertTrue(warnings.get(0).contains("walking-paths.window-radius"), warnings.get(0));
    }

    @Test
    void disabledIsKept() {
        ConfigLoader.WalkingPathsSection section = new ConfigLoader.WalkingPathsSection();
        section.enabled = false;
        assertFalse(WalkSettingsParser.parse(section, w -> { }).enabled());
    }

    @Test
    void missingParticleFallsBackToTheDefault() {
        ConfigLoader.WalkingPathsSection section = new ConfigLoader.WalkingPathsSection();
        section.particles.trail = null;
        section.particles.pulse.type = " ";
        WalkSettings settings = WalkSettingsParser.parse(section, w -> { });
        assertEquals(WalkSettings.DEFAULTS.trailParticle(), settings.trailParticle());
        assertEquals(WalkSettings.DEFAULTS.pulseParticle(), settings.pulseParticle());
    }

    @Test
    void settingsProduceTheWalkerAndTuning() {
        WalkSettings d = WalkSettings.DEFAULTS;
        assertEquals(3, d.walker().maxDrop());
        assertEquals(48.0, d.tuning().extendDistance());
        assertEquals(2000L, d.tuning().cooldownMillis());
    }

    @Test
    void coarseDefaultsApply() {
        List<String> warnings = new ArrayList<>();
        WalkSettings settings = WalkSettingsParser.parse(new ConfigLoader.WalkingPathsSection(), warnings::add);
        assertEquals(WalkSettings.CoarseSettings.DEFAULTS, settings.coarse());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void coarseBuildRateOutOfRangeFallsBack() {
        ConfigLoader.WalkingPathsSection section = new ConfigLoader.WalkingPathsSection();
        section.coarse.buildChunksPerTick = 0;
        List<String> warnings = new ArrayList<>();
        WalkSettings settings = WalkSettingsParser.parse(section, warnings::add);
        assertEquals(4, settings.coarse().buildChunksPerTick());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("walking-paths.coarse.build-chunks-per-tick"), warnings.get(0));
    }

    @Test
    void coarseExpansionsOutOfRangeFallsBack() {
        ConfigLoader.WalkingPathsSection section = new ConfigLoader.WalkingPathsSection();
        section.coarse.maxExpansions = 10;
        List<String> warnings = new ArrayList<>();
        WalkSettings settings = WalkSettingsParser.parse(section, warnings::add);
        assertEquals(50_000, settings.coarse().maxExpansions());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("walking-paths.coarse.max-expansions"), warnings.get(0));
    }
}
