package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.GuidePolicy;
import com.minecraftcitiesnetwork.directions.walk.Goal;
import com.minecraftcitiesnetwork.directions.walk.Route;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Follows one walking leg for one player: feeds {@link GuidePolicy} and runs the searches it asks for. Main thread. */
public final class WalkGuide {
    private final WalkSearchService service;
    private final UUID worldId;
    private final Goal goal;
    private final GuidePolicy policy;
    private boolean paused;

    public WalkGuide(WalkSearchService service, World world, Goal goal) {
        this.service = service;
        this.worldId = world.getUID();
        this.goal = goal;
        this.policy = new GuidePolicy(service.settings().tuning());
    }

    public void tick(Player player) {
        World world = player.getWorld();
        if (!world.getUID().equals(worldId)) {
            if (!paused) {
                policy.reset();
                paused = true;
            }
            return;
        }
        paused = false;
        Location at = player.getLocation();
        Route route = policy.route();
        boolean blocked = route != null
                && route.firstMissing(service.terrain(world), policy.cursor(), service.settings().trailLength()) >= 0;
        boolean onFoot = !player.isFlying() && !player.isGliding() && !player.isInsideVehicle();
        GuidePolicy.SearchOrder order = policy.tick(new GuidePolicy.Observation(
                System.currentTimeMillis(), at.getX(), at.getY(), at.getZ(), onFoot, blocked));
        if (order != null) {
            service.plan(world, at.getX(), at.getY(), at.getZ(), goal, order.spentExits())
                    .thenAccept(result -> policy.onResult(order.generation(), result, System.currentTimeMillis()));
        }
    }

    /** The route to draw and follow, or null while there is none (searching, failed, other world). */
    public @Nullable Route visibleRoute() {
        return paused ? null : policy.route();
    }

    public int cursor() {
        return policy.cursor();
    }

    /** Where the arrow should point. Only valid while {@link #visibleRoute()} is non-null. */
    public Location arrowTarget(Player player) {
        Route route = policy.route();
        int i = route.indexAhead(policy.cursor(), service.settings().arrowLookAhead());
        return new Location(player.getWorld(), route.x(i) + 0.5, route.y(i) + 0.1, route.z(i) + 0.5);
    }

    /** Remaining walking distance, with a straight-line estimate beyond a partial route's end. */
    public double remaining(Player player) {
        Route route = policy.route();
        Location at = player.getLocation();
        return route.remainingFrom(policy.cursor(), at.getX(), at.getY(), at.getZ()) + route.endEstimate();
    }

    public boolean approximate() {
        Route route = policy.route();
        return route != null && !route.complete();
    }

    public boolean takeNoRouteMessage() {
        return policy.takeNoRouteMessage();
    }
}
