package com.minecraftcitiesnetwork.directions.graph;

import com.minecraftcitiesnetwork.directions.model.Line;
import com.minecraftcitiesnetwork.directions.model.RouteResult;
import com.minecraftcitiesnetwork.directions.model.Stop;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitGraphWalkingPolicyTest {
    @Test
    void sharedModeOnlyBlocksBusToTrainWalking() {
        Stop bus = new Stop("bus-stop", "world", 0, 0, Set.of("bus-line"));
        Stop train = new Stop("train-stop", "world", 10, 0, Set.of("train-line"));

        TransitGraph graph = new TransitGraph(
                List.of(bus, train),
                List.of(
                        new Line("bus-line", "bus", List.of("bus-stop")),
                        new Line("train-line", "train", List.of("train-stop"))
                ),
                50.0,
                150.0,
                "shared-mode-only"
        );

        RouteResult route = graph.routeAStar(
                "__start__",
                0.0,
                0.0,
                "__dest__",
                10.0,
                0.0,
                Map.of("bus-stop", 0.0),
                Map.of("train-stop", 0.0)
        );
        assertTrue(route.pathNodes().isEmpty(), "Bus->train walking should be blocked in shared-mode-only.");
    }

    @Test
    void strictAllowsBusToTrainWalking() {
        Stop bus = new Stop("bus-stop", "world", 0, 0, Set.of("bus-line"));
        Stop train = new Stop("train-stop", "world", 10, 0, Set.of("train-line"));

        TransitGraph graph = new TransitGraph(
                List.of(bus, train),
                List.of(
                        new Line("bus-line", "bus", List.of("bus-stop")),
                        new Line("train-line", "train", List.of("train-stop"))
                ),
                50.0,
                150.0,
                "strict"
        );

        RouteResult route = graph.routeAStar(
                "__start__",
                0.0,
                0.0,
                "__dest__",
                10.0,
                0.0,
                Map.of("bus-stop", 0.0),
                Map.of("train-stop", 0.0)
        );
        assertFalse(route.pathNodes().isEmpty(), "Strict policy should allow walking between nearby stops.");
    }
}
