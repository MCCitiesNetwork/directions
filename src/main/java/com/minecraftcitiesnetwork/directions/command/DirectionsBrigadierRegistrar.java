package com.minecraftcitiesnetwork.directions.command;

import com.minecraftcitiesnetwork.directions.DirectionsPlugin;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class DirectionsBrigadierRegistrar {
    private final DirectionsPlugin plugin;
    private final DirectionsCommand command;
    private final DebugCommand debug;

    public DirectionsBrigadierRegistrar(DirectionsPlugin plugin, DirectionsCommand command, DebugCommand debug) {
        this.plugin = plugin;
        this.command = command;
        this.debug = debug;
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
                    .then(debugTree())
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

    private LiteralArgumentBuilder<CommandSourceStack> debugTree() {
        return Commands.literal("debug")
                .requires(source -> source.getSender().hasPermission("directions.debug"))
                .then(Commands.literal("stats").executes(ctx -> {
                    debug.stats(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("flags").executes(ctx -> player(ctx, debug::flags)))
                .then(Commands.literal("path")
                        .then(Commands.literal("region")
                                .then(Commands.argument("region", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            if (context.getSource().getSender() instanceof Player player) {
                                                for (String id : command.suggestions().regionSuggestions(player, builder.getRemainingLowerCase())) {
                                                    builder.suggest(id);
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> player(ctx, p -> debug.pathToRegion(p, StringArgumentType.getString(ctx, "region"))))))
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> player(ctx, p -> debug.path(p,
                                                DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "z")))))))
                .then(Commands.literal("capture")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 256))
                                        .executes(ctx -> player(ctx, p -> debug.capture(p,
                                                StringArgumentType.getString(ctx, "name"), IntegerArgumentType.getInteger(ctx, "radius")))))));
    }

    private int player(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<Player> action) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            command.sendOnlyPlayer(sender);
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
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
}
