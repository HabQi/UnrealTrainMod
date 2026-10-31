/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.SwitchPosition;
import com.unrealtrainmod.track.assembly.TrackTransitionResolver;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.train.TrainManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg;
import static com.mojang.brigadier.arguments.DoubleArgumentType.getDouble;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class TurnoutCommands {
    private static final String PREFIX = "commands.unrealtrainmod.turnout.";
    private TurnoutCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var create = literal("create");
        for (var definition : List.of(TrackAssemblyDefinition.LEFT, TrackAssemblyDefinition.RIGHT)) {
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
            set.then(literal(name).executes(c -> execute(c, g -> setAll(c, g, position))));
        }
        set.then(literal("control").then(argument("control", StringArgumentType.word()).suggests((c, b) -> {
            var assembly = graph(c).assembly(UuidArgument.getUuid(c, "assemblyId"));
            return SharedSuggestionProvider.suggest(assembly.state().controls().keySet(), b);
        }).then(argument("position", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("normal", "reverse"), b))
                .executes(c -> execute(c, g -> {
                    TrainManager.get(c.getSource().getLevel()).refreshTurnoutOccupancy();
                    UUID id = UuidArgument.getUuid(c, "assemblyId");
                    var assembly = g.assembly(id);
                    String control = getString(c, "control");
                    if (!assembly.state().controls().containsKey(control)) {
                        c.getSource().sendFailure(Component.translatable(PREFIX + "unknown_control", control));
                        return 0;
                    }
                    var position = switch (getString(c, "position").toLowerCase(Locale.ROOT)) {
                        case "normal" -> SwitchPosition.NORMAL;
                        case "reverse" -> SwitchPosition.REVERSE;
                        default -> throw new IllegalArgumentException("Unknown switch position");
                    };
                    g.setAssemblyState(id, assembly.state().withControl(control, position));
                    describe(c, g, g.assembly(id));
                    return 1;
                })))));
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
                .then(literal("remove").then(assemblyArgument().executes(c -> remove(c, false))
                        .then(literal("force").executes(c -> remove(c, true)))));
        event.getDispatcher().register(literal("utm").requires(s -> s.hasPermission(2)).then(turnout));
    }

    /** Ordinary turnouts use main; multi-control structures switch every group together. */
    private static int setAll(CommandContext<CommandSourceStack> context, TrackGraph graph, SwitchPosition position) {
        TrainManager.get(context.getSource().getLevel()).refreshTurnoutOccupancy();
        UUID id = UuidArgument.getUuid(context, "assemblyId");
        var assembly = graph.assembly(id);
        if (assembly.state().controls().isEmpty()) {
            context.getSource().sendFailure(Component.translatable(PREFIX + "no_controls"));
            return 0;
        }
        var state = assembly.state();
        if (state.controls().containsKey("main")) state = state.withControl("main", position);
        else for (String control : state.controls().keySet()) state = state.withControl(control, position);
        graph.setAssemblyState(id, state);
        describe(context, graph, graph.assembly(id));
        return 1;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, UUID> assemblyArgument() {
        return argument("assemblyId", UuidArgument.uuid()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                graph(c).assemblies().stream().map(a -> a.id().toString()), b));
    }

    private static int remove(CommandContext<CommandSourceStack> context, boolean force) throws CommandSyntaxException {
        return execute(context, graph -> {
            UUID value = UuidArgument.getUuid(context, "assemblyId");
            TrainManager.get(context.getSource().getLevel()).removeTurnout(value, force);
            context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "removed", id(value)), true);
            return 1;
        });
    }

    private static TrackGraph graph(CommandContext<CommandSourceStack> context) {
        return TrackBlockManager.get(context.getSource().getLevel()).graph();
    }

    private static void describe(CommandContext<CommandSourceStack> c, TrackGraph graph, TrackAssemblyInstance value) {
        var source = c.getSource();
        Component occupancy = value.occupant() == null ? Component.translatable(PREFIX + "free")
                : Component.translatable(PREFIX + (source.getLevel().getEntity(value.occupant()) == null ? "waiting" : "occupied"), id(value.occupant()));
        Component controls = describeControls(value);
        source.sendSuccess(() -> Component.translatable(PREFIX + "info", id(value.id()),
                Component.translatable(PREFIX + value.definition().name().toLowerCase(Locale.ROOT)),
                controls, value.revision(), occupancy), false);
        if (value.trailingPass() != null) {
            var passage = value.trailingPass();
            String exit = value.definition().routes().get(passage.pathId()).exit();
            boolean thrown = value.definition().routes().get(passage.pathId()).enabled(value.state());
            var returning = Component.translatable(PREFIX + (passage.returnState().controls().get("main") == SwitchPosition.NORMAL
                    ? "line_one" : "line_two"));
            source.sendSuccess(() -> Component.translatable(PREFIX + "trailing_info", exit,
                    Component.translatable(PREFIX + (thrown ? "trailing_return" : "trailing_approach")), returning), false);
        }
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
            boolean enabled = TrackTransitionResolver.isPathEnabled(value, entry.getKey());
            source.sendSuccess(() -> Component.translatable(PREFIX + "path", entry.getKey(), steps,
                    Component.translatable(PREFIX + (enabled ? "path_open" : "path_closed")), number(value.gauge())), false);
        }
    }

    private static Component describeControls(TrackAssemblyInstance value) {
        if (value.state().controls().isEmpty()) return Component.translatable(PREFIX + "no_controls");
        if (!value.state().controls().containsKey("main")) {
            return Component.literal(value.state().controls().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + Component.translatable(PREFIX
                            + "position." + entry.getValue().name().toLowerCase(Locale.ROOT)).getString())
                    .collect(Collectors.joining(", ")));
        }
        return Component.translatable(PREFIX + (value.definition() == TrackAssemblyDefinition.CUSTOM
                ? value.state().controls().get("main") == SwitchPosition.NORMAL ? "line_one" : "line_two"
                : value.state().controls().get("main") == SwitchPosition.NORMAL ? "straight" : "diverging"));
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
