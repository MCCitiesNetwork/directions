package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.Pos;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkPath;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;

/** Draws a path with particles for one player for a while. Debug only. */
public final class PathParticles {
    private static final double VISIBLE_RANGE_SQUARED = 64 * 64;

    private PathParticles() {
    }

    public static void show(Plugin plugin, Player player, WalkPath path, Duration duration) {
        List<Pos> nodes = path.nodes();
        long endAt = System.currentTimeMillis() + duration.toMillis();
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || System.currentTimeMillis() > endAt) {
                handle[0].cancel();
                return;
            }
            Location eye = player.getLocation();
            for (int i = 0; i < nodes.size(); i++) {
                Pos p = nodes.get(i);
                double dx = p.centerX() - eye.getX();
                double dz = p.centerZ() - eye.getZ();
                if (dx * dx + dz * dz > VISIBLE_RANGE_SQUARED) {
                    continue;
                }
                boolean end = i == nodes.size() - 1;
                player.spawnParticle(end ? Particle.HAPPY_VILLAGER : Particle.END_ROD,
                        p.centerX(), p.y() + 0.3, p.centerZ(), end ? 6 : 1, 0, 0, 0, 0);
            }
        }, 0L, 10L);
    }
}
