package com.minecraftcitiesnetwork.directions.i18n;

import com.minecraftcitiesnetwork.directions.config.ConfigLoader;
import com.minecraftcitiesnetwork.directions.navigation.Waypoint;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;

public class LangService {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private YamlConfiguration lang;

    public LangService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File folder = plugin.getDataFolder();
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File langFile = new File(folder, "lang.yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang.yml", false);
        }
        this.lang = YamlConfiguration.loadConfiguration(langFile);
    }

    public void reload() {
        load();
    }

    public String raw(String key) {
        if (lang == null) {
            load();
        }
        return lang.getString(key, "<red>Missing lang key: " + key + "</red>");
    }

    public Component message(String key, TagResolver... placeholders) {
        return miniMessage.deserialize(raw(key), placeholders);
    }

    public Component deserialize(String template, TagResolver... placeholders) {
        return miniMessage.deserialize(template, placeholders);
    }

    public void send(CommandSender sender, String key, TagResolver... placeholders) {
        sender.sendMessage(message(key, placeholders));
    }

    /** Placeholder for names.yml display names (MiniMessage allowed). */
    public TagResolver namedDisplay(String key, String value) {
        return placeholderRaw(key, value);
    }

    public TagResolver stopName(String key, ConfigLoader.LoadedData data, String stopId) {
        return namedDisplay(key, data.displayStop(stopId));
    }

    public TagResolver lineNames(String key, String lineText) {
        return namedDisplay(key, lineText);
    }

    public TagResolver waypointLabel(String key, ConfigLoader.LoadedData data, Waypoint waypoint) {
        return switch (waypoint) {
            case Waypoint.Coordinates coords -> placeholder(key, coords.displayLabel(data));
            case Waypoint.Region region -> regionDestination(key, data, region.id());
        };
    }

    public TagResolver regionDestination(String key, ConfigLoader.LoadedData data, String regionId) {
        if (data.hasNamedStopDisplay(regionId)) {
            return namedDisplay(key, data.displayStop(regionId));
        }
        return placeholder(key, regionId);
    }

    /** Placeholder with user-supplied text escaped so it cannot inject MiniMessage tags. */
    public TagResolver placeholder(String key, String value) {
        return Placeholder.parsed(key, escape(value));
    }

    /** Placeholder for trusted MiniMessage (e.g. values loaded from lang.yml). */
    public TagResolver placeholderRaw(String key, String value) {
        return Placeholder.parsed(key, value);
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
