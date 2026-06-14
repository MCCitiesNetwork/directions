package com.minecraftcitiesnetwork.directions.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class GpsBrigadierRegistrar {
    private final DirectionsPlugin plugin;
    private final GpsCommand command;

    public GpsBrigadierRegistrar(DirectionsPlugin plugin, GpsCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("gps")
                    .executes(this::sendUsage)
                    .then(Commands.literal("stop")
                            .requires(source -> source.getSender().hasPermission("gps.use"))
                            .executes(this::stop))
                    .then(Commands.argument("region", StringArgumentType.word())
                            .requires(source -> source.getSender().hasPermission("gps.use"))
                            .suggests((context, builder) -> {
                                CommandSender sender = context.getSource().getSender();
                                if (sender instanceof Player player) {
                                    for (String id : command.suggestions().regionSuggestions(player, builder.getRemainingLowerCase())) {
                                        builder.suggest(id);
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .executes(this::startRegion));

            event.registrar().register(root.build(), "Direct arrow navigation to a WorldGuard region");
        });
    }

    private int sendUsage(CommandContext<CommandSourceStack> context) {
        command.sendUsage(context.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }

    private int startRegion(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            command.sendOnlyPlayer(sender);
            return Command.SINGLE_SUCCESS;
        }
        String region = StringArgumentType.getString(context, "region");
        command.start(player, region);
        return Command.SINGLE_SUCCESS;
    }

    private int stop(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            command.sendOnlyPlayer(sender);
            return Command.SINGLE_SUCCESS;
        }
        command.stop(player);
        return Command.SINGLE_SUCCESS;
    }
}
