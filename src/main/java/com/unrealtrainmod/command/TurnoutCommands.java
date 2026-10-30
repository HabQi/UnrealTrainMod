/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.SwitchPosition;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.train.TrainManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg;
import static com.mojang.brigadier.arguments.DoubleArgumentType.getDouble;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class TurnoutCommands {
    private static final String PREFIX = "commands.unrealtrainmod.turnout.";
    private TurnoutCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var create = literal("create");
        for (var definition : TrackAssemblyDefinition.values()) {
            create.then(literal(definition.name().toLowerCase(Locale.ROOT))
                    .then(argument("originNodeId", UuidArgument.uuid()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                            graph(c).nodes().stream().filter(n -> graph(c).assemblyAtPort(n.id()) == null
                                    && graph(c).connections(n.id()).size() <= 1).map(n -> n.id().toString()), b))
                            .then(argument("yaw", doubleArg()).executes(c -> execute(c, g -> {
                                var value = g.createTurnout(UuidArgument.getUuid(c, "originNodeId"), definition, getDouble(c, "yaw"));
                                describe(c, g, value);
                                return 1;
                            })))));
        }
        var set = assemblyArgument();
        for (String name : List.of("straight", "diverging")) {
            var position = name.equals("straight") ? SwitchPosition.NORMAL : SwitchPosition.REVERSE;
            set.then(literal(name).executes(c -> execute(c, g -> {
                TrainManager.get(c.getSource().getLevel()).refreshTurnoutOccupancy();
                UUID id = UuidArgument.getUuid(c, "assemblyId");
                g.setAssemblyState(id, g.assembly(id).state().withControl("main", position));
                describe(c, g, g.assembly(id));
                return 1;
            })));
        }
        var turnout = literal("turnout").then(create).then(literal("set").then(set))
                .then(literal("info").then(assemblyArgument().executes(c -> execute(c, g -> {
                    TrainManager.get(c.getSource().getLevel()).refreshTurnoutOccupancy();
                    describe(c, g, g.assembly(UuidArgument.getUuid(c, "assemblyId"))); return 1;
                }))))
                .then(literal("list").executes(c -> execute(c, g -> {
                    TrainManager.get(c.getSource().getLevel()).refreshTurnoutOccupancy();
                    for (var value : g.assemblies()) describe(c, g, value);
                    c.getSource().sendSuccess(() -> Component.translatable(PREFIX + "count", g.assemblies().size()), false);
                    return g.assemblies().size();
                })))
                .then(literal("remove").then(assemblyArgument().executes(c -> execute(c, g -> {
                    UUID id = UuidArgument.getUuid(c, "assemblyId"); g.removeAssembly(id);
                    c.getSource().sendSuccess(() -> Component.translatable(PREFIX + "removed", id(id)), true); return 1;
                }))));
        event.getDispatcher().register(literal("utm").requires(s -> s.hasPermission(2)).then(turnout));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, UUID> assemblyArgument() {
        return argument("assemblyId", UuidArgument.uuid()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                graph(c).assemblies().stream().map(a -> a.id().toString()), b));
    }

    private static TrackGraph graph(CommandContext<CommandSourceStack> context) {
        return TrackBlockManager.get(context.getSource().getLevel()).graph();
    }

    private static void describe(CommandContext<CommandSourceStack> c, TrackGraph graph, TrackAssemblyInstance value) {
        var source = c.getSource();
        Component occupancy = value.occupant() == null ? Component.translatable(PREFIX + "free")
                : Component.translatable(PREFIX + (source.getLevel().getEntity(value.occupant()) == null ? "waiting" : "occupied"), id(value.occupant()));
        source.sendSuccess(() -> Component.translatable(PREFIX + "info", id(value.id()),
                Component.translatable(PREFIX + value.definition().name().toLowerCase(Locale.ROOT)),
                Component.translatable(PREFIX + (value.state().controls().get("main") == SwitchPosition.NORMAL ? "straight" : "diverging")), value.revision(), occupancy), false);
        for (var entry : value.ports().entrySet()) {
            var port = entry.getValue();
            var node = graph.node(port.nodeId());
            source.sendSuccess(() -> Component.translatable(PREFIX + "port", entry.getKey(), id(node.id()),
                    number(node.position().x()), number(node.position().y()), number(node.position().z()), number(port.outwardYaw())), false);
        }
        for (var entry : value.paths().entrySet()) {
            var steps = Component.empty();
            for (var step : entry.getValue().steps()) {
                if (!steps.getSiblings().isEmpty()) steps.append(" → ");
                steps.append(step.fromStart() ? "+" : "−").append(id(step.segmentId()));
            }
            source.sendSuccess(() -> Component.translatable(PREFIX + "path", entry.getKey(), steps,
                    number(value.gauge())), false);
        }
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.4f", value); }
    private static Component id(UUID id) {
        return Component.literal(id.toString()).withStyle(s -> s.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, id.toString())));
    }

    private static int execute(CommandContext<CommandSourceStack> context, Action action) throws CommandSyntaxException {
        try { return action.run(graph(context)); }
        catch (IllegalArgumentException | IllegalStateException exception) {
            throw new SimpleCommandExceptionType(Component.translatable(PREFIX + "error", exception.getMessage())).create();
        }
    }

    @FunctionalInterface private interface Action { int run(TrackGraph graph); }
}
