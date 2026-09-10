package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.MainThread;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Hands work to the server thread, or runs it inline when already there. */
public final class BukkitMainThread implements MainThread {
    private final Plugin plugin;

    public BukkitMainThread(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
