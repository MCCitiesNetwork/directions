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

public final class DirectionsBrigadierRegistrar {
    private final DirectionsPlugin plugin;
    private final DirectionsCommand command;

    public DirectionsBrigadierRegistrar(DirectionsPlugin plugin, DirectionsCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("directions")
                    .executes(this::sendUsage)
                    .then(Commands.literal("start")
                            .requires(source -> source.getSender().hasPermission("directions.use"))
                            .then(Commands.argument("region", StringArgumentType.word())
                                    .suggests((context, builder) -> {
                                        CommandSender sender = context.getSource().getSender();
                                        if (sender instanceof Player player) {
                                            for (String id : command.suggestions().regionSuggestions(player, builder.getRemainingLowerCase())) {
                                                builder.suggest(id);
                                            }
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(this::start)))
                    .then(Commands.literal("stop")
                            .requires(source -> source.getSender().hasPermission("directions.use"))
                            .executes(this::stop))
                    .then(Commands.literal("reload")
                            .requires(source -> source.getSender().hasPermission("directions.reload"))
                            .executes(this::reload));

            event.registrar().register(root.build(), "Transit directions to WorldGuard regions");
        });
    }

    private int sendUsage(CommandContext<CommandSourceStack> context) {
        command.sendUsage(context.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }

    private int start(CommandContext<CommandSourceStack> context) {
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

    private int reload(CommandContext<CommandSourceStack> context) {
        command.reload(context.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }
}
