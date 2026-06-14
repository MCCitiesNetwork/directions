package com.minecraftcitiesnetwork.directions.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class CompassBrigadierRegistrar {
    private final DirectionsPlugin plugin;
    private final CompassCommand command;

    public CompassBrigadierRegistrar(DirectionsPlugin plugin, CompassCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("compass")
                    .executes(this::sendUsage)
                    .then(Commands.literal("stop")
                            .requires(source -> source.getSender().hasPermission("compass.use"))
                            .executes(this::stop))
                    .then(coordinatesBranch(this::startXZ, this::startXYZ))
                    .then(Commands.literal("transit")
                            .requires(source -> source.getSender().hasPermission("compass.use"))
                            .then(coordinatesBranch(this::startTransitXZ, this::startTransitXYZ)));

            event.registrar().register(root.build(), "Navigation to coordinates, direct or via transit");
        });
    }

    private static ArgumentBuilder<CommandSourceStack, ?> coordinatesBranch(
            Command<CommandSourceStack> xz,
            Command<CommandSourceStack> xyz
    ) {
        return Commands.argument("x", DoubleArgumentType.doubleArg())
                .requires(source -> source.getSender().hasPermission("compass.use"))
                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                        .executes(xz))
                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                .executes(xyz)));
    }

    private int sendUsage(CommandContext<CommandSourceStack> context) {
        command.sendUsage(context.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }

    private int startXZ(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            command.sendOnlyPlayer(sender);
            return Command.SINGLE_SUCCESS;
        }
        double x = DoubleArgumentType.getDouble(context, "x");
        double z = DoubleArgumentType.getDouble(context, "z");
        command.start(player, x, z);
        return Command.SINGLE_SUCCESS;
    }

    private int startXYZ(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            command.sendOnlyPlayer(sender);
            return Command.SINGLE_SUCCESS;
        }
        double x = DoubleArgumentType.getDouble(context, "x");
        double y = DoubleArgumentType.getDouble(context, "y");
        double z = DoubleArgumentType.getDouble(context, "z");
        command.start(player, x, y, z);
        return Command.SINGLE_SUCCESS;
    }

    private int startTransitXZ(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            command.sendOnlyPlayer(sender);
            return Command.SINGLE_SUCCESS;
        }
        double x = DoubleArgumentType.getDouble(context, "x");
        double z = DoubleArgumentType.getDouble(context, "z");
        command.startTransit(player, x, z);
        return Command.SINGLE_SUCCESS;
    }

    private int startTransitXYZ(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            command.sendOnlyPlayer(sender);
            return Command.SINGLE_SUCCESS;
        }
        double x = DoubleArgumentType.getDouble(context, "x");
        double y = DoubleArgumentType.getDouble(context, "y");
        double z = DoubleArgumentType.getDouble(context, "z");
        command.startTransit(player, x, y, z);
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
