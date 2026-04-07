package com.minecraftcitiesnetwork.directions.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

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

    public void send(CommandSender sender, String key, TagResolver... placeholders) {
        sender.sendMessage(message(key, placeholders));
    }

    public TagResolver p(String key, String value) {
        return Placeholder.parsed(key, escape(value));
    }

    public TagResolver pRaw(String key, String value) {
        return Placeholder.parsed(key, value);
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
