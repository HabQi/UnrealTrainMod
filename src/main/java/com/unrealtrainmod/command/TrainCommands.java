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

/** Administrator controls resolve a vehicle selector to its owning consist. */
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
                .then(literal("couple").then(vehicleArgument("frontVehicle").then(vehicleArgument("rearVehicle")
                        .executes(context -> {
                            var rear = vehicle(context, "rearVehicle");
                            return execute(context, "couple", "frontVehicle", (manager, front) -> manager.couple(front, rear));
                        }))))
                .then(literal("uncouple").then(vehicleArgument()
                        .executes(context -> execute(context, "uncouple", TrainManager::uncouple))))
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
        return vehicleArgument("vehicle");
    }

    private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> vehicleArgument(String name) {
        return argument(name, EntityArgument.entity());
    }

    private static TrainEntity vehicle(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return vehicle(context, "vehicle");
    }

    private static TrainEntity vehicle(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        var entity = EntityArgument.getEntity(context, name);
        if (!(entity instanceof TrainEntity vehicle)) throw error("vehicle");
        if (vehicle.level() != context.getSource().getLevel()) throw error("dimension");
        return vehicle;
    }

    private static int execute(CommandContext<CommandSourceStack> context, String operation, Action action)
            throws CommandSyntaxException {
        return execute(context, operation, "vehicle", action);
    }

    private static int execute(CommandContext<CommandSourceStack> context, String operation, String argument, Action action)
            throws CommandSyntaxException {
        var vehicle = vehicle(context, argument);
        var manager = TrainManager.get(context.getSource().getLevel());
        TrainConsist.StopReason failure;
        try {
            failure = action.run(manager, vehicle);
        } catch (TrainManager.StateException exception) {
            throw error("operation", reason(exception.reason()));
        } catch (IllegalArgumentException exception) {
            throw error("argument", exception.getMessage());
        }
        if (failure != null) throw error("operation", reason(failure));
        var train = trainInfo(manager, vehicle);
        context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "success",
                Component.translatable(PREFIX + "action." + operation), vehicle.getUUID().toString(),
                number(train.configuredSpeed()), number(train.actualSpeed())), true);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var vehicle = vehicle(context);
        var manager = TrainManager.get(context.getSource().getLevel());
        var train = trainInfo(manager, vehicle);
        Component route = train.route() == null ? Component.translatable(PREFIX + "route_unavailable")
                : Component.translatable(PREFIX + "route_info", number(train.progress()), number(train.route().getLength()),
                        Component.translatable(PREFIX + (train.routeDirty() ? "route_stale" : "route_ready")));
        Component state = train.running() ? Component.translatable(PREFIX + "state.running") : reason(train.stopReason());
        context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "info", train.id().toString(), state,
                number(train.configuredSpeed()), number(train.actualSpeed()), route), false);
        for (int i = 0; i < train.cars().size(); i++) {
            int ordinal = i + 1;
            var member = train.cars().get(i);
            var loaded = Component.translatable(PREFIX + (manager.memberLoaded(member.entityId()) ? "member_loaded" : "member_unloaded"));
            var ready = Component.translatable(PREFIX + (manager.isRestored(train) ? "member_restored" : "member_restore_pending"));
            context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "member", ordinal, member.entityId().toString(),
                    member.modelId().toString(), number(member.offset()), loaded, ready), false);
            var savedAnchor = member.anchor();
            context.getSource().sendSuccess(() -> savedAnchor == null
                    ? Component.translatable(PREFIX + "member_anchor_none", ordinal)
                    : Component.translatable(PREFIX + "member_anchor", ordinal, savedAnchor.segmentId().toString(),
                            number(savedAnchor.distance()), Boolean.toString(savedAnchor.fromStart())), false);
        }
        var anchor = train.anchor();
        context.getSource().sendSuccess(() -> anchor == null ? Component.translatable(PREFIX + "anchor_none")
                : Component.translatable(PREFIX + "anchor", anchor.segmentId().toString(), number(anchor.distance()),
                        Boolean.toString(anchor.fromStart())), false);
        return 1;
    }

    private static TrainConsist trainInfo(TrainManager manager, TrainEntity vehicle) throws CommandSyntaxException {
        try {
            return manager.info(vehicle);
        } catch (TrainManager.StateException exception) {
            throw error("operation", reason(exception.reason()));
        }
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
