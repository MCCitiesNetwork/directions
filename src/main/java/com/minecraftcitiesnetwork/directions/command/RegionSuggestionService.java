package com.minecraftcitiesnetwork.directions.command;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class RegionSuggestionService {
    List<String> rootSuggestions(String prefix) {
        List<String> out = new ArrayList<>();
        for (String option : List.of("start", "stop", "reload")) {
            if (option.startsWith(prefix)) {
                out.add(option);
            }
        }
        Collections.sort(out);
        return out;
    }

    List<String> regionSuggestions(Player player, String prefix) {
        List<String> out = new ArrayList<>();
        for (String id : worldRegionIds(player)) {
            if (id.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(id);
            }
        }
        Collections.sort(out);
        return out;
    }

    private Collection<String> worldRegionIds(Player player) {
        RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(player.getWorld()));
        if (regionManager == null) {
            return List.of();
        }
        return regionManager.getRegions().keySet();
    }
}
