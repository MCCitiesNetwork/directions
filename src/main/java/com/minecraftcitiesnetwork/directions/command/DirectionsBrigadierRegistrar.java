package com.minecraftcitiesnetwork.directions.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

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
                    .then(Commands.literal("stop")
                            .requires(source -> source.getSender().hasPermission("directions.use"))
                            .executes(this::stop))
                    .then(Commands.literal("reload")
                            .requires(source -> source.getSender().hasPermission("directions.reload"))
                            .executes(this::reload))
                    .then(Commands.literal("debug")
                            .requires(source -> source.getSender().hasPermission("directions.debug"))
                            .then(Commands.literal("walk")
                                    .executes(this::debugWalk)))
                    .then(Commands.literal("walk")
                            .requires(source -> source.getSender().hasPermission("directions.debug"))
                            .then(Commands.literal("build")
                                    .then(Commands.literal("stop")
                                            .executes(this::walkBuildStop))
                                    .then(Commands.argument("world", StringArgumentType.word())
                                            .suggests((context, builder) -> {
                                                String typed = builder.getRemainingLowerCase();
                                                for (World world : Bukkit.getWorlds()) {
                                                    if (world.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                                                        builder.suggest(world.getName());
                                                    }
                                                }
                                                return builder.buildFuture();
                                            })
                                            .executes(this::walkBuild))))
                    .then(Commands.argument("region", StringArgumentType.word())
                            .requires(source -> source.getSender().hasPermission("directions.use"))
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

            event.registrar().register(root.build(), "Transit directions to WorldGuard regions");
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

    private int reload(CommandContext<CommandSourceStack> context) {
        command.reload(context.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }

    private int walkBuild(CommandContext<CommandSourceStack> context) {
        command.walkBuild(context.getSource().getSender(), StringArgumentType.getString(context, "world"));
        return Command.SINGLE_SUCCESS;
    }

    private int walkBuildStop(CommandContext<CommandSourceStack> context) {
        command.walkBuildStop(context.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }

    private int debugWalk(CommandContext<CommandSourceStack> context) {
        command.debugWalk(context.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }
}
