package com.minecraftcitiesnetwork.directions.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Turns a routed node list into legs, marking which are rides and which are walks. */
public final class LegPlanner {
    private LegPlanner() {
    }

    /**
     * @param pathNodes     route nodes, starting with {@code startNode} and usually ending with {@code destNode}
     * @param isTransitHop  whether two consecutive stops are connected by a line
     */
    public static List<Leg> plan(List<String> pathNodes, String startNode, String destNode,
                                 Waypoint finalWaypoint, BiPredicate<String, String> isTransitHop) {
        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i + 1 < pathNodes.size(); i++) {
            String from = pathNodes.get(i);
            String to = pathNodes.get(i + 1);
            if (to.equals(destNode)) {
                continue;
            }
            boolean ride = !from.equals(startNode) && isTransitHop.test(from, to);
            Waypoint target = new Waypoint.Region(to);
            legs.add(ride ? Leg.ride(target) : Leg.walk(target));
        }
        if (legs.isEmpty() || !legs.getLast().target().equals(finalWaypoint)) {
            legs.add(Leg.walk(finalWaypoint));
        }
        return legs;
    }
}
