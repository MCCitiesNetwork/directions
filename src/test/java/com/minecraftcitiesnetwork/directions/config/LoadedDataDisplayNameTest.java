package com.minecraftcitiesnetwork.directions.config;

import com.minecraftcitiesnetwork.directions.model.Line;
import com.minecraftcitiesnetwork.directions.model.Stop;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadedDataDisplayNameTest {
    @Test
    void explicitDisplayNamesOverrideFallback() {
        ConfigLoader.LoadedData data = new ConfigLoader.LoadedData(
                50.0,
                150.0,
                3.0,
                15.0,
                5.0,
                "Next: <stop>  <remaining>m",
                "shared-mode-only",
                Map.of("reveille_metro_line_churchill", "Churchill Line"),
                Map.of("rev-train-capital", "Capital Station"),
                List.of(new Line("reveille_metro_line_churchill", "train", List.of("rev-train-capital"))),
                Map.of("rev-train-capital", new Stop("rev-train-capital", "world", 0, 0, Set.of("reveille_metro_line_churchill")))
        );

        assertEquals("Churchill Line", data.displayLine("reveille_metro_line_churchill"));
        assertEquals("Capital Station", data.displayStop("rev-train-capital"));
    }

    @Test
    void fallbackHumanizesUnknownNames() {
        ConfigLoader.LoadedData data = new ConfigLoader.LoadedData(
                50.0,
                150.0,
                3.0,
                15.0,
                5.0,
                "Next: <stop>  <remaining>m",
                "shared-mode-only",
                Map.of(),
                Map.of(),
                List.of(),
                Map.of()
        );

        assertEquals("Reveille Metro Line Bondi", data.displayLine("reveille_metro_line_bondi"));
        assertEquals("Rev Train Capital", data.displayStop("rev-train-capital"));
    }
}
