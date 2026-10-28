/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.command;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackNode;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.persistence.TrackSavedData;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg;
import static com.mojang.brigadier.arguments.DoubleArgumentType.getDouble;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Server-side diagnostics only: these commands create data, not blocks or rendered rails. */
public final class TrackCommands {
    private static final String PREFIX = "commands.unrealtrainmod.track.";
    private static final double DEFAULT_GAUGE = 1.435;

    private TrackCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var node = literal("node")
                .then(literal("add").then(argument("position", Vec3Argument.vec3(false))
                        .executes(context -> addNode(context, DEFAULT_GAUGE))
                        .then(argument("gauge", doubleArg()).executes(context -> addNode(context, getDouble(context, "gauge"))))))
                .then(literal("info").then(nodeArgument("nodeId").executes(TrackCommands::nodeInfo)))
                .then(literal("remove").then(nodeArgument("nodeId").executes(TrackCommands::removeNode)));
        var controls = argument("c1x", doubleArg()).then(argument("c1z", doubleArg())
                .then(argument("c2x", doubleArg()).then(argument("c2z", doubleArg())
                        .executes(context -> addSegment(context, true)))));
        var track = literal("track").then(node)
                .then(literal("line").then(nodeArgument("startNodeId").then(nodeArgument("endNodeId")
                        .executes(context -> addSegment(context, false)))))
                .then(literal("bezier").then(nodeArgument("startNodeId").then(nodeArgument("endNodeId").then(controls))))
                .then(literal("info").then(segmentArgument("segmentId").executes(TrackCommands::segmentInfo)))
                .then(literal("remove").then(segmentArgument("segmentId").executes(TrackCommands::removeSegment)))
                .then(literal("list")
                        .executes(context -> list(context, (int)Math.floor(context.getSource().getPosition().x / 16),
                                (int)Math.floor(context.getSource().getPosition().z / 16)))
                        .then(argument("chunkX", integer()).then(argument("chunkZ", integer())
                                .executes(context -> list(context, getInteger(context, "chunkX"), getInteger(context, "chunkZ"))))));
        event.getDispatcher().register(literal("utm").requires(source -> source.hasPermission(2)).then(track));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, UUID> nodeArgument(String name) {
        return argument(name, UuidArgument.uuid()).suggests((context, builder) ->
                SharedSuggestionProvider.suggest(graph(context).nodes().stream().map(node -> node.id().toString()), builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, UUID> segmentArgument(String name) {
        return argument(name, UuidArgument.uuid()).suggests((context, builder) ->
                SharedSuggestionProvider.suggest(graph(context).segments().stream().map(segment -> segment.id().toString()), builder));
    }

    private static int addNode(CommandContext<CommandSourceStack> context, double gauge) throws CommandSyntaxException {
        return execute(context, graph -> {
            var position = Vec3Argument.getVec3(context, "position");
            TrackNode node = graph.addNode(UUID.randomUUID(), new TrackVector(position.x, position.y, position.z), gauge);
            context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "node_created", id(node.id()),
                    position.x, position.y, position.z, node.gauge()), true);
            return 1;
        });
    }

    private static int addSegment(CommandContext<CommandSourceStack> context, boolean bezier) throws CommandSyntaxException {
        return execute(context, graph -> {
            UUID start = UuidArgument.getUuid(context, "startNodeId"), end = UuidArgument.getUuid(context, "endNodeId");
            UUID segmentId = UUID.randomUUID();
            TrackSegment segment = bezier ? graph.addBezier(segmentId, start, end,
                    new TrackPoint(getDouble(context, "c1x"), getDouble(context, "c1z")),
                    new TrackPoint(getDouble(context, "c2x"), getDouble(context, "c2z")))
                    : graph.addStraight(segmentId, start, end);
            context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "segment_created", id(segment.id()),
                    type(segment), id(start), id(end), length(segment)), true);
            return 1;
        });
    }

    private static int nodeInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return execute(context, graph -> {
            TrackNode node = graph.node(UuidArgument.getUuid(context, "nodeId"));
            List<TrackConnection> connections = graph.connections(node.id());
            context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "node_info", id(node.id()),
                    node.position().x(), node.position().y(), node.position().z(), node.gauge(), connections.size()), false);
            for (TrackConnection connection : connections) {
                TrackSegment segment = graph.segment(connection.segmentId());
                UUID destination = connection.fromStart() ? segment.endNodeId() : segment.startNodeId();
                Component direction = Component.translatable(PREFIX + (connection.fromStart() ? "from_start" : "from_end"));
                context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "connection", id(segment.id()),
                        direction, id(destination)), false);
            }
            return 1;
        });
    }

    private static int segmentInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return execute(context, graph -> {
            TrackSegment segment = graph.segment(UuidArgument.getUuid(context, "segmentId"));
            context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "segment_info", id(segment.id()),
                    type(segment), id(segment.startNodeId()), id(segment.endNodeId()), length(segment),
                    graph.node(segment.startNodeId()).gauge()), false);
            if (segment.geometry() instanceof BezierTrackGeometry bezier) {
                context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "controls", bezier.control1().x(),
                        bezier.control1().z(), bezier.control2().x(), bezier.control2().z()), false);
            }
            return 1;
        });
    }

    private static int removeNode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return execute(context, graph -> {
            UUID nodeId = UuidArgument.getUuid(context, "nodeId");
            graph.removeNode(nodeId);
            context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "node_removed", id(nodeId)), true);
            return 1;
        });
    }

    private static int removeSegment(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return execute(context, graph -> {
            UUID segmentId = UuidArgument.getUuid(context, "segmentId");
            graph.removeSegment(segmentId);
            context.getSource().sendSuccess(() -> Component.translatable(PREFIX + "segment_removed", id(segmentId)), true);
            return 1;
        });
    }

    private static int list(CommandContext<CommandSourceStack> context, int chunkX, int chunkZ) throws CommandSyntaxException {
        return execute(context, graph -> {
            List<TrackSegment> segments = graph.segmentsInChunk(chunkX, chunkZ);
            var source = context.getSource();
            source.sendSuccess(() -> Component.translatable(PREFIX + "list", source.getLevel().dimension().location().toString(),
                    chunkX, chunkZ, segments.size()), false);
            for (TrackSegment segment : segments) {
                source.sendSuccess(() -> Component.translatable(PREFIX + "list_entry", id(segment.id()), type(segment),
                        id(segment.startNodeId()), id(segment.endNodeId()), length(segment)), false);
            }
            return segments.size();
        });
    }

    private static TrackGraph graph(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        try {
            return TrackSavedData.get(context.getSource().getLevel()).graph();
        } catch (IllegalStateException exception) {
            throw error("storage", exception.getMessage());
        }
    }

    private static int execute(CommandContext<CommandSourceStack> context, GraphAction action) throws CommandSyntaxException {
        try {
            return action.run(graph(context));
        } catch (TrackGraphException exception) {
            throw error(exception.reason().name().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw error("geometry", exception.getMessage());
        }
    }

    private static CommandSyntaxException error(String key, Object... arguments) {
        return new SimpleCommandExceptionType(Component.translatable(PREFIX + "error." + key, arguments)).create();
    }

    private static Component id(UUID id) {
        String value = id.toString();
        return Component.literal(value).withStyle(ChatFormatting.AQUA)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value)));
    }

    private static Component type(TrackSegment segment) {
        return Component.translatable(PREFIX + "type." + segment.type().name().toLowerCase(Locale.ROOT));
    }

    private static String length(TrackSegment segment) {
        return String.format(Locale.ROOT, "%.6f", segment.geometry().getLength());
    }

    @FunctionalInterface
    private interface GraphAction {
        int run(TrackGraph graph);
    }
}
