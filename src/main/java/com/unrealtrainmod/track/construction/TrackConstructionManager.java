/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.construction;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.item.RailPlacerItem;
import com.unrealtrainmod.model.RailModels;
import com.unrealtrainmod.network.payload.TrackConstructionPayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Phase;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackNode;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.StraightTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.persistence.TrackSavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread-only drafts. Neither selecting nor previewing mutates the saved graph. */
public final class TrackConstructionManager {
    private static final double DEFAULT_GAUGE = 1.435;
    private static final double SNAP_DISTANCE_SQUARED = 0.75 * 0.75;
    private static final double MAX_LENGTH = 256;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private TrackConstructionManager() {}

    public static void register(IEventBus bus) {
        bus.addListener(TrackConstructionManager::rightClickBlock);
        bus.addListener(TrackConstructionManager::playerTick);
        bus.addListener(TrackConstructionManager::playerLoggedOut);
        bus.addListener(TrackConstructionManager::playerCloned);
        bus.addListener(TrackConstructionManager::playerChangedDimension);
        bus.addListener(TrackConstructionManager::levelUnloaded);
        bus.addListener(TrackConstructionManager::serverStopped);
    }

    private static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() == InteractionHand.MAIN_HAND
                && (event.getItemStack().is(UnrealTrainMod.TRACK_BUILDER.get())
                || event.getItemStack().is(UnrealTrainMod.RAIL_PLACER.get()))) {
            event.setUseBlock(TriState.FALSE);
        }
    }

    public static void interact(ServerPlayer player, UseOnContext context) {
        if (!player.isAlive() || !player.getMainHandItem().is(UnrealTrainMod.TRACK_BUILDER.get())) {
            clear(player, Feedback.INVALID_ITEM);
            return;
        }
        if (!player.hasPermissions(2)) {
            clear(player, Feedback.NO_PERMISSION);
            return;
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && !session.matches(player)) {
            clear(player, Feedback.CLEARED);
            session = null;
        }
        if (player.isShiftKeyDown()) {
            clear(player, Feedback.CANCELLED);
            return;
        }
        if (context == null) {
            publish(player, session, Feedback.SELECT_POINT);
            return;
        }
        if (context.getClickedFace() != Direction.UP) {
            publish(player, session, Feedback.TOP_FACE);
            return;
        }
        var hit = context.getClickLocation();
        TrackVector target = new TrackVector(hit.x, hit.y + 0.3, hit.z);
        double reach = player.blockInteractionRange();
        if (!player.canInteractWithBlock(context.getClickedPos(), 0)
                || player.getEyePosition().distanceToSqr(hit) > reach * reach
                || !player.serverLevel().hasChunkAt(context.getClickedPos())
                || !validPosition(player.serverLevel(), target)) {
            publish(player, session, Feedback.INVALID_POINT);
            return;
        }
        TrackGraph graph = graph(player);
        if (graph == null) return;
        TrackPlacementEndpoint selected = snap(graph, target);
        if (!validPosition(player.serverLevel(), selected.position())) {
            publish(player, session, Feedback.INVALID_POINT);
            return;
        }
        if (session == null) {
            session = new Session(player, selected);
            session.gauge = selected.nodeId() == null ? DEFAULT_GAUGE : graph.node(selected.nodeId()).gauge();
            SESSIONS.put(player.getUUID(), session);
            publish(player, session, Feedback.START);
        } else if (refreshStart(player, graph, session)) {
            session.end = selected;
            Feedback result = validate(graph, session);
            session.ready = result == Feedback.READY;
            publish(player, session, result);
        }
    }

    public static void place(ServerPlayer player) {
        if (!player.isAlive() || !player.getMainHandItem().is(UnrealTrainMod.RAIL_PLACER.get())) {
            publish(player, SESSIONS.get(player.getUUID()), Feedback.INVALID_ITEM);
            return;
        }
        if (!player.hasPermissions(2)) {
            clear(player, Feedback.NO_PERMISSION);
            return;
        }
        if (player.isShiftKeyDown()) {
            clear(player, Feedback.CANCELLED);
            return;
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && !session.matches(player)) {
            clear(player, Feedback.CLEARED);
            session = null;
        }
        if (session == null || !session.ready) {
            publish(player, session, Feedback.LINE_NOT_READY);
            return;
        }
        var modelId = RailPlacerItem.selectedModel(player.getMainHandItem());
        if (modelId == null || RailModels.find(modelId) == null) {
            publish(player, session, modelId == null ? Feedback.SELECT_MODEL : Feedback.MISSING_MODEL);
            return;
        }
        confirm(player, session, modelId.toString());
    }

    private static TrackGraph graph(ServerPlayer player) {
        try {
            return TrackSavedData.get(player.serverLevel()).graph();
        } catch (IllegalStateException exception) {
            UnrealTrainMod.LOGGER.error("Cannot load tracks for construction in {}: {}",
                    player.serverLevel().dimension().location(), exception.getMessage());
            clear(player, Feedback.STORAGE);
            return null;
        }
    }

    private static TrackPlacementEndpoint snap(TrackGraph graph, TrackVector point) {
        TrackNode nearest = null;
        double best = SNAP_DISTANCE_SQUARED;
        for (TrackNode node : graph.nodes()) {
            double distance = distanceSquared(node.position(), point);
            if (distance < best || (distance == best && (nearest == null || node.id().compareTo(nearest.id()) < 0))) {
                nearest = node;
                best = distance;
            }
        }
        return nearest == null ? new TrackPlacementEndpoint(null, point)
                : new TrackPlacementEndpoint(nearest.id(), nearest.position());
    }

    private static double distanceSquared(TrackVector a, TrackVector b) {
        double x = a.x() - b.x(), y = a.y() - b.y(), z = a.z() - b.z();
        return x * x + y * y + z * z;
    }

    private static boolean validPosition(ServerLevel level, TrackVector point) {
        if (!Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z())
                || point.y() < level.getMinBuildHeight() || point.y() >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(point.x(), point.z())) return false;
        return level.hasChunkAt(BlockPos.containing(point.x(), point.y(), point.z()));
    }

    private static TrackPlacementEndpoint refresh(TrackGraph graph, TrackPlacementEndpoint endpoint) {
        return endpoint.nodeId() == null ? endpoint
                : new TrackPlacementEndpoint(endpoint.nodeId(), graph.node(endpoint.nodeId()).position());
    }

    private static boolean refreshStart(ServerPlayer player, TrackGraph graph, Session session) {
        try {
            session.start = refresh(graph, session.start);
            if (validPosition(player.serverLevel(), session.start.position())) return true;
        } catch (TrackGraphException exception) {
            // A command or another player may have removed the selected node.
        }
        clear(player, Feedback.STALE_START);
        return false;
    }

    private static Feedback validate(TrackGraph graph, Session session) {
        var a = session.start;
        var b = session.end;
        if (a.nodeId() != null && a.nodeId().equals(b.nodeId())) return Feedback.SAME_NODE;
        double gaugeA = a.nodeId() == null ? DEFAULT_GAUGE : graph.node(a.nodeId()).gauge();
        double gaugeB = b.nodeId() == null ? DEFAULT_GAUGE : graph.node(b.nodeId()).gauge();
        session.gauge = a.nodeId() != null ? gaugeA : gaugeB;
        if (a.nodeId() != null && b.nodeId() != null && gaugeA != gaugeB) return Feedback.GAUGE_MISMATCH;
        try {
            var geometry = new StraightTrackGeometry(new TrackPoint(a.position().x(), a.position().z()),
                    new TrackPoint(b.position().x(), b.position().z()),
                    new LinearGrade(a.position().y(), b.position().y()), session.gauge);
            return geometry.getLength() <= MAX_LENGTH ? Feedback.READY : Feedback.TOO_LONG;
        } catch (IllegalArgumentException exception) {
            return Feedback.INVALID_GEOMETRY;
        }
    }

    private static void confirm(ServerPlayer player, Session session, String modelId) {
        TrackGraph graph = graph(player);
        if (graph == null || !refreshStart(player, graph, session)) return;
        try {
            session.end = refresh(graph, session.end);
        } catch (TrackGraphException exception) {
            rejectEnd(player, session, Feedback.STALE_END);
            return;
        }
        if (!validPosition(player.serverLevel(), session.end.position())) {
            rejectEnd(player, session, Feedback.STALE_END);
            return;
        }
        Feedback result = validate(graph, session);
        if (result != Feedback.READY) {
            rejectEnd(player, session, result);
            return;
        }
        // All input validation precedes the graph's atomic commit; sync sees both endpoint nodes.
        UUID id = UUID.randomUUID();
        try {
            graph.addStraight(id, session.start, session.end, session.gauge, modelId);
        } catch (TrackGraphException exception) {
            rejectEnd(player, session, Feedback.INVALID_GEOMETRY);
            return;
        }
        clear(player, Feedback.CREATED);
        player.sendSystemMessage(Component.translatable("item.unrealtrainmod.track_builder.created", id.toString()));
    }

    private static void rejectEnd(ServerPlayer player, Session session, Feedback feedback) {
        session.end = null;
        session.ready = false;
        publish(player, session, feedback);
    }

    private static void publish(ServerPlayer player, Session session, Feedback feedback) {
        PacketDistributor.sendToPlayer(player, new TrackConstructionPayload(player.serverLevel().dimension().location(),
                session == null ? Phase.EMPTY : session.ready ? Phase.READY : Phase.START,
                session == null ? null : session.start, session == null ? null : session.end,
                session == null ? DEFAULT_GAUGE : session.gauge, feedback));
    }

    private static void clear(ServerPlayer player, Feedback feedback) {
        SESSIONS.remove(player.getUUID());
        publish(player, null, feedback);
    }

    private static void playerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Session session = SESSIONS.get(player.getUUID());
            if (session != null && !session.matches(player)) clear(player, Feedback.CLEARED);
        }
    }

    private static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer) SESSIONS.remove(event.getEntity().getUUID());
    }

    private static void playerCloned(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer) SESSIONS.remove(event.getOriginal().getUUID());
    }

    private static void playerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && SESSIONS.containsKey(player.getUUID())) {
            clear(player, Feedback.CLEARED);
        }
    }

    private static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) SESSIONS.values().removeIf(session -> session.level == level);
    }

    private static void serverStopped(ServerStoppedEvent event) { SESSIONS.clear(); }

    private static final class Session {
        final ServerPlayer player;
        final ServerLevel level;
        TrackPlacementEndpoint start;
        TrackPlacementEndpoint end;
        double gauge;
        boolean ready;

        Session(ServerPlayer player, TrackPlacementEndpoint start) {
            this.player = player;
            level = player.serverLevel();
            this.start = start;
        }

        boolean matches(ServerPlayer current) {
            return player == current && level == current.serverLevel() && current.isAlive() && current.hasPermissions(2);
        }
    }
}
