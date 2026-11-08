/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.compat.ir.IrControlDefinition;
import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.train.TrainAnchor;
import com.unrealtrainmod.train.TrainCarEnd;
import com.unrealtrainmod.train.TrainConsist;
import com.unrealtrainmod.train.TrainManager;
import com.unrealtrainmod.train.TrainDiagnostics;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleDoor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
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
        var doorLeft = literal("left");
        var doorRight = literal("right");
        var doorBoth = literal("both");
        addDoorOperations(doorLeft, VehicleDoor.LEFT);
        addDoorOperations(doorRight, VehicleDoor.RIGHT);
        addDoorOperations(doorBoth, null);
        var door = literal("door").then(vehicleArgument()
                .then(doorLeft)
                .then(doorRight)
                .then(doorBoth)
                .then(literal("info").executes(TrainCommands::doorInfo)));
        var train = literal("train")
                .then(literal("bind").then(vehicleArgument().then(segment)))
                .then(literal("couple").then(vehicleArgument("frontVehicle").then(vehicleArgument("rearVehicle")
                        .executes(context -> {
                            var rear = vehicle(context, "rearVehicle");
                            return execute(context, "couple", "frontVehicle", (manager, front) -> manager.couple(front, rear));
                        }))))
                .then(literal("uncouple").then(vehicleArgument()
                        .then(literal("front").executes(context -> execute(context, "uncouple",
                                (manager, vehicle) -> manager.uncouple(vehicle, TrainCarEnd.FRONT))))
                        .then(literal("rear").executes(context -> execute(context, "uncouple",
                                (manager, vehicle) -> manager.uncouple(vehicle, TrainCarEnd.REAR))))))
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
                .then(door)
                .then(irCommands())
                .then(literal("info").then(vehicleArgument().executes(TrainCommands::info)))
                .then(literal("debug")
                        .then(literal("off").executes(context -> {
                            TrainDiagnostics.stop(context.getSource().getPlayerOrException());
                            return 1;
                        }))
                        .then(vehicleArgument().executes(context -> {
                            TrainDiagnostics.watch(context.getSource().getPlayerOrException(), vehicle(context));
                            return 1;
                        })));
        event.getDispatcher().register(literal("utm").requires(source -> source.hasPermission(2)).then(train));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> irCommands() {
        return literal("ir").then(vehicleArgument()
                .then(literal("list").executes(context -> {
                    var vehicle = vehicle(context);
                    var definition = irDefinition(vehicle);
                    context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "ir.list", vehicle.getUUID().toString(), definition.controls().size()), false);
                    for (String group : definition.controls().keySet().stream().sorted().toList()) sendIrControl(context, vehicle, definition, group);
                    return definition.controls().size();
                }))
                .then(literal("get").then(irGroup().executes(context -> {
                    var vehicle = vehicle(context);
                    sendIrControl(context, vehicle, irDefinition(vehicle), getString(context, "group"));
                    return 1;
                })))
                .then(literal("set").then(irGroup().then(argument("value", doubleArg(0, 1))
                        .executes(context -> changeIrControl(context, "set")))))
                .then(literal("toggle").then(irGroup().executes(context -> changeIrControl(context, "toggle"))))
                .then(literal("pulse").then(irGroup().executes(context -> changeIrControl(context, "pulse")))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> irGroup() {
        return argument("group", string()).suggests((context, builder) -> SharedSuggestionProvider.suggest(
                irDefinition(vehicle(context)).controls().keySet().stream().sorted(), builder));
    }

    private static IrVehicleDefinition irDefinition(TrainEntity vehicle) throws CommandSyntaxException {
        var definition = VehicleCatalog.find(vehicle.getModelId());
        if (definition == null || definition.ir() == IrVehicleDefinition.NONE) throw error("ir.vehicle");
        return definition.ir();
    }

    private static float irValue(TrainEntity vehicle, IrVehicleDefinition definition, String group) {
        var snapshot = vehicle.irSnapshot();
        return definition.value(group, snapshot.values(), vehicle.runtimeState(), snapshot.frontCoupled(), snapshot.rearCoupled());
    }

    private static void sendIrControl(CommandContext<CommandSourceStack> context, TrainEntity vehicle,
                                      IrVehicleDefinition definition, String group) throws CommandSyntaxException {
        var control = definition.controls().get(group);
        if (control == null) throw error("ir.unknown", group);
        context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "ir.control", group, control.label(),
                number(irValue(vehicle, definition, group)), number(control.defaultValue()),
                Component.translatable(PREFIX + "ir.type." + control.type().name().toLowerCase(Locale.ROOT)),
                Component.translatable(PREFIX + (control.readOnly() ? "ir.readonly" : "ir.writable"))), false);
    }

    private static int changeIrControl(CommandContext<CommandSourceStack> context, String operation) throws CommandSyntaxException {
        var vehicle = vehicle(context);
        var definition = irDefinition(vehicle);
        String group = getString(context, "group");
        var control = definition.controls().get(group);
        if (control == null) throw error("ir.unknown", group);
        if (control.readOnly()) throw error("ir.readonly_group", group);
        double value = switch (operation) {
            case "set" -> getDouble(context, "value");
            case "toggle" -> irValue(vehicle, definition, group) >= 0.5f ? 0 : 1;
            default -> 1;
        };
        if (!Double.isFinite(value) || value < 0 || value > 1
                || (control.type() != IrControlDefinition.Type.CONTINUOUS && value != 0 && value != 1)) {
            throw error("ir.value", group);
        }
        boolean changed = vehicle.setIrControl(group, (float)value, operation.equals("pulse"));
        sendIrControl(context, vehicle, definition, group);
        return changed ? 1 : 0;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> vehicleArgument() {
        return vehicleArgument("vehicle");
    }

    private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> vehicleArgument(String name) {
        return argument(name, EntityArgument.entity()).suggests(TrainCommands::suggestVehicles);
    }

    /**
     * Candidates come from the loaded vehicles of the source dimension, so completion no longer depends on the
     * crosshair hit. Selector input keeps the vanilla parser-driven suggestions.
     */
    private static CompletableFuture<Suggestions> suggestVehicles(CommandContext<CommandSourceStack> context,
                                                                  SuggestionsBuilder builder) {
        if (builder.getRemaining().startsWith("@")) return EntityArgument.entity().listSuggestions(context, builder);
        var source = context.getSource();
        var origin = source.getPosition();
        var vehicles = new ArrayList<TrainEntity>();
        for (var entity : source.getLevel().getAllEntities()) {
            if (entity instanceof TrainEntity vehicle) vehicles.add(vehicle);
        }
        vehicles.sort(Comparator.comparingDouble(vehicle -> vehicle.position().distanceToSqr(origin)));
        var range = StringRange.between(builder.getStart(), builder.getInput().length());
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        var candidates = new ArrayList<Suggestion>();
        for (var vehicle : vehicles) {
            String uuid = vehicle.getUUID().toString();
            if (!uuid.equals(builder.getRemaining()) && SharedSuggestionProvider.matchesSubStr(remaining, uuid)) {
                candidates.add(new Suggestion(range, uuid, vehicleName(vehicle)));
            }
        }
        // SuggestionsBuilder.build() sorts text, which would discard the distance order.
        var uuids = new Suggestions(range, List.copyOf(candidates));
        if (!builder.getRemaining().isEmpty()) return CompletableFuture.completedFuture(uuids);
        // An empty argument keeps the vanilla name and selector hints; the candidates follow them.
        var selectors = EntityArgument.entity().listSuggestions(context, builder.createOffset(builder.getStart()));
        return selectors.thenApply(hints -> concat(hints, uuids));
    }

    private static Component vehicleName(TrainEntity vehicle) {
        var definition = VehicleCatalog.find(vehicle.getModelId());
        return Component.literal(definition == null ? String.valueOf(vehicle.getModelId()) : definition.name());
    }

    private static Suggestions concat(Suggestions first, Suggestions second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        var list = new ArrayList<>(first.getList());
        list.addAll(second.getList());
        return new Suggestions(first.getRange(), list);
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

    private static void addDoorOperations(LiteralArgumentBuilder<CommandSourceStack> node, VehicleDoor side) {
        node.then(literal("open").executes(context -> setDoor(context, side, Boolean.TRUE)))
                .then(literal("close").executes(context -> setDoor(context, side, Boolean.FALSE)))
                .then(literal("toggle").executes(context -> setDoor(context, side, null)));
    }

    private static int setDoor(CommandContext<CommandSourceStack> context, VehicleDoor side, Boolean open)
            throws CommandSyntaxException {
        var vehicle = vehicle(context);
        boolean changed;
        if (side == null) {
            changed = applyDoor(vehicle, VehicleDoor.LEFT, open);
            changed |= applyDoor(vehicle, VehicleDoor.RIGHT, open);
        } else {
            changed = applyDoor(vehicle, side, open);
        }
        sendDoorState(context, vehicle);
        return changed ? 1 : 0;
    }

    private static boolean applyDoor(TrainEntity vehicle, VehicleDoor side, Boolean open) {
        return vehicle.setDoorOpen(side, open == null ? !vehicle.isDoorOpen(side) : open);
    }

    private static int doorInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        sendDoorState(context, vehicle(context));
        return 1;
    }

    private static void sendDoorState(CommandContext<CommandSourceStack> context, TrainEntity vehicle) {
        context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "door_state",
                vehicle.getUUID().toString(), doorState(vehicle, VehicleDoor.LEFT),
                doorState(vehicle, VehicleDoor.RIGHT)), true);
    }

    private static Component doorState(TrainEntity vehicle, VehicleDoor side) {
        return Component.translatable(PREFIX + (vehicle.isDoorOpen(side) ? "door_open" : "door_closed"));
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
            for (var end : TrainCarEnd.values()) {
                var peer = train.peer(member.entityId(), end);
                var endName = Component.translatable("item.unrealtrainmod.crowbar.end." + end.name().toLowerCase(Locale.ROOT));
                Component connection = !train.couplingsReady() ? Component.translatable(PREFIX + "coupler_pending")
                        : peer == null ? Component.translatable(PREFIX + "coupler_free")
                        : Component.translatable(PREFIX + "coupler_peer", peer.vehicle().toString(),
                                Component.translatable("item.unrealtrainmod.crowbar.end." + peer.end().name().toLowerCase(Locale.ROOT)));
                context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "member_coupler", ordinal, endName, connection), false);
            }
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
        try {
            for (var line : TrainDiagnostics.occupancyLines(train, manager.occupancy(), 32, 16)) {
                context.getSource().sendSuccess(() -> line, false);
            }
        } catch (IllegalStateException exception) {
            throw error("operation", reason(TrainConsist.StopReason.STORAGE_ERROR));
        }
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
