/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.command;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.train.TrainAnchor;
import com.unrealtrainmod.train.TrainConsist;
import com.unrealtrainmod.train.TrainManager;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg;
import static com.mojang.brigadier.arguments.DoubleArgumentType.getDouble;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Administrator controls for the first, single-car server simulation. */
public final class TrainCommands {
    private static final String PREFIX = "commands.unrealtrainmod.train.";

    private TrainCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var segment = argument("segmentId", UuidArgument.uuid()).suggests((context, builder) -> {
            try {
                return SharedSuggestionProvider.suggest(TrackBlockManager.get(context.getSource().getLevel()).graph()
                        .segments().stream().map(value -> value.id().toString()), builder);
            } catch (IllegalStateException exception) {
                return builder.buildFuture();
            }
        });
        segment.then(argument("distance", doubleArg(0)).then(argument("fromStart", bool())
                .executes(context -> execute(context, "bind", (manager, vehicle) -> manager.bind(vehicle,
                        new TrainAnchor(UuidArgument.getUuid(context, "segmentId"),
                                getDouble(context, "distance"), getBool(context, "fromStart")))))));
        var train = literal("train")
                .then(literal("bind").then(vehicleArgument().then(segment)))
                .then(literal("start").then(vehicleArgument()
                        .executes(context -> execute(context, "start", TrainManager::start))))
                .then(literal("stop").then(vehicleArgument().executes(context -> execute(context, "stop", (manager, vehicle) -> {
                    manager.stop(vehicle);
                    return null;
                }))))
                .then(literal("speed").then(vehicleArgument().then(argument("blocksPerSecond",
                        doubleArg(-TrainConsist.MAX_SPEED, TrainConsist.MAX_SPEED))
                        .executes(context -> execute(context, "speed", (manager, vehicle) ->
                                manager.setSpeed(vehicle, getDouble(context, "blocksPerSecond")))))))
                .then(literal("reverse").then(vehicleArgument()
                        .executes(context -> execute(context, "reverse", TrainManager::reverse))))
                .then(literal("info").then(vehicleArgument().executes(TrainCommands::info)));
        event.getDispatcher().register(literal("utm").requires(source -> source.hasPermission(2)).then(train));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> vehicleArgument() {
        return argument("vehicle", EntityArgument.entity());
    }

    private static TrainEntity vehicle(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var entity = EntityArgument.getEntity(context, "vehicle");
        if (!(entity instanceof TrainEntity vehicle)) throw error("vehicle");
        if (vehicle.level() != context.getSource().getLevel()) throw error("dimension");
        return vehicle;
    }

    private static int execute(CommandContext<CommandSourceStack> context, String operation, Action action)
            throws CommandSyntaxException {
        var vehicle = vehicle(context);
        var manager = TrainManager.get(context.getSource().getLevel());
        TrainConsist.StopReason failure;
        try {
            failure = action.run(manager, vehicle);
        } catch (IllegalArgumentException exception) {
            throw error("argument", exception.getMessage());
        }
        if (failure != null) throw error("operation", reason(failure));
        var train = manager.info(vehicle);
        context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "success",
                Component.translatable(PREFIX + "action." + operation), vehicle.getUUID().toString(),
                number(train.configuredSpeed()), number(train.actualSpeed())), true);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var vehicle = vehicle(context);
        var train = TrainManager.get(context.getSource().getLevel()).info(vehicle);
        Component route = train.route() == null ? Component.translatable(PREFIX + "route_unavailable")
                : Component.translatable(PREFIX + "route_info", number(train.progress()), number(train.route().getLength()),
                        Component.translatable(PREFIX + (train.routeDirty() ? "route_stale" : "route_ready")));
        Component state = train.running() ? Component.translatable(PREFIX + "state.running") : reason(train.stopReason());
        context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "info", train.id().toString(), state,
                number(train.configuredSpeed()), number(train.actualSpeed()), route), false);
        var anchor = vehicle.getRailAnchor();
        context.getSource().sendSuccess(() -> anchor == null ? Component.translatable(PREFIX + "anchor_none")
                : Component.translatable(PREFIX + "anchor", anchor.segmentId().toString(), number(anchor.distance()),
                        Boolean.toString(anchor.fromStart())), false);
        return 1;
    }

    private static Component reason(TrainConsist.StopReason reason) {
        return Component.translatable(PREFIX + "state." + reason.name().toLowerCase(Locale.ROOT));
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }

    private static CommandSyntaxException error(String key, Object... arguments) {
        return new SimpleCommandExceptionType(Component.translatable(PREFIX + "error." + key, arguments)).create();
    }

    @FunctionalInterface
    private interface Action {
        TrainConsist.StopReason run(TrainManager manager, TrainEntity vehicle);
    }
}
