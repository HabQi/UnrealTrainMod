/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.construction;

import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.item.RailPlacerItem;
import com.unrealtrainmod.network.payload.TrackConstructionPayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Phase;
import com.unrealtrainmod.network.payload.OpenTrackCurvePayload;
import com.unrealtrainmod.network.payload.SetTrackCurvePayload;
import com.unrealtrainmod.network.payload.TrackCurveResultPayload;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackNode;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.TrackPlacementGeometry;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
    private static final double NODE_HIT_RADIUS = 0.2;
    private static final UUID EMPTY_REVISION = new UUID(0, 0);
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
                || event.getItemStack().is(UnrealTrainMod.RAIL_PLACER.get())
                || event.getItemStack().is(UnrealTrainMod.TRACK_WRENCH.get()))) {
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
            session.startCurve = savedCurve(graph, selected);
            SESSIONS.put(player.getUUID(), session);
            publish(player, session, Feedback.START);
        } else if (refreshStart(player, graph, session)) {
            session.end = selected;
            session.endCurve = savedCurve(graph, selected);
            session.endEdited = false;
            session.changed();
            Feedback result = validate(graph, session);
            publish(player, session, result);
        }
    }

    private static TrackCurveParameters savedCurve(TrackGraph graph, TrackPlacementEndpoint endpoint) {
        return endpoint.nodeId() == null ? null : graph.node(endpoint.nodeId()).curve();
    }

    public static void edit(ServerPlayer player) {
        if (!player.isAlive() || !player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())) {
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
        if (session == null || !session.matches(player)) {
            clear(player, Feedback.NO_DRAFT);
            return;
        }
        TrackGraph graph = graph(player);
        if (graph == null || !refreshStart(player, graph, session) || !refreshEnd(player, graph, session)) return;
        Vec3 eye = player.getEyePosition();
        Vec3 rayEnd = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        Vec3 visibleEnd = player.serverLevel().clip(new ClipContext(eye, rayEnd,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)).getLocation();
        double startHit = hitDistance(session.start, eye, visibleEnd);
        double endHit = session.end == null ? Double.POSITIVE_INFINITY : hitDistance(session.end, eye, visibleEnd);
        if (!Double.isFinite(Math.min(startHit, endHit))) {
            publish(player, session, Feedback.AIM_NODE);
            return;
        }
        boolean start = startHit <= endHit;
        TrackPlacementEndpoint selected = start ? session.start : session.end;
        if (!canEditPoint(player, selected.position())) {
            publish(player, session, Feedback.INVALID_POINT);
            return;
        }
        session.editorId = UUID.randomUUID();
        session.editStart = start;
        TrackCurveParameters curve = start ? session.startCurve : session.endCurve;
        TrackPlacementEndpoint other = start ? session.end : session.start;
        TrackCurveParameters initial = curve != null ? curve : TrackPlacementGeometry.defaults(
                selected.position(), other == null ? null : other.position(), player.getYRot());
        Feedback feedback = session.end == null ? Feedback.START : validate(graph, session);
        publish(player, session, feedback);
        PacketDistributor.sendToPlayer(player, new OpenTrackCurvePayload(player.serverLevel().dimension().location(),
                session.revision, session.editorId, start, curve == null, initial));
    }

    private static double hitDistance(TrackPlacementEndpoint endpoint, Vec3 eye, Vec3 end) {
        var p = endpoint.position();
        AABB box = new AABB(p.x() - NODE_HIT_RADIUS, p.y() - NODE_HIT_RADIUS, p.z() - NODE_HIT_RADIUS,
                p.x() + NODE_HIT_RADIUS, p.y() + NODE_HIT_RADIUS, p.z() + NODE_HIT_RADIUS);
        if (box.contains(eye)) return 0;
        return box.clip(eye, end).map(eye::distanceToSqr).orElse(Double.POSITIVE_INFINITY);
    }

    private static boolean canEditPoint(ServerPlayer player, TrackVector position) {
        if (!validPosition(player.serverLevel(), position)) return false;
        Vec3 eye = player.getEyePosition(), point = new Vec3(position.x(), position.y(), position.z());
        double distance = eye.distanceTo(point);
        if (distance > player.blockInteractionRange() + NODE_HIT_RADIUS) return false;
        var hit = player.serverLevel().clip(new ClipContext(eye, point,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return eye.distanceTo(hit.getLocation()) + 1e-6 >= distance;
    }

    public static void setCurve(ServerPlayer player, SetTrackCurvePayload payload) {
        Session session = SESSIONS.get(player.getUUID());
        Feedback error = null;
        if (!player.isAlive() || !player.hasPermissions(2)) error = Feedback.NO_PERMISSION;
        else if (!player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())) error = Feedback.INVALID_ITEM;
        else if (session == null || !session.matches(player)
                || !player.serverLevel().dimension().location().equals(payload.dimension())
                || !session.revision.equals(payload.revision()) || !payload.editorId().equals(session.editorId)
                || payload.start() != session.editStart || (!payload.start() && session.end == null)) {
            error = Feedback.STALE_EDIT;
        }
        if (error != null) {
            curveResult(player, payload, false, error);
            return;
        }
        TrackGraph graph = graph(player);
        if (graph == null) {
            curveResult(player, payload, false, Feedback.STORAGE);
            return;
        }
        if (!refreshStart(player, graph, session) || !refreshEnd(player, graph, session)) {
            curveResult(player, payload, false, Feedback.STALE_EDIT);
            return;
        }
        var endpoint = payload.start() ? session.start : session.end;
        if (!canEditPoint(player, endpoint.position())) {
            curveResult(player, payload, false, Feedback.INVALID_POINT);
            return;
        }
        TrackCurveParameters curve;
        try {
            // Even an automatic request must carry finite, bounded numeric values.
            curve = new TrackCurveParameters(payload.angle(), payload.length());
        } catch (IllegalArgumentException exception) {
            curveResult(player, payload, false, Feedback.INVALID_PARAMETERS);
            return;
        }
        if (payload.automatic()) curve = null;
        if (payload.start()) {
            session.startCurve = curve;
            session.startEdited = true;
        } else {
            session.endCurve = curve;
            session.endEdited = true;
        }
        session.changed();
        Feedback feedback = session.end == null ? Feedback.START : validate(graph, session);
        publish(player, session, feedback);
        curveResult(player, payload, true, feedback);
    }

    private static void curveResult(ServerPlayer player, SetTrackCurvePayload payload, boolean accepted, Feedback feedback) {
        PacketDistributor.sendToPlayer(player, new TrackCurveResultPayload(payload.editorId(), accepted, feedback));
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
        if (session == null || session.validation != Feedback.READY) {
            publish(player, session, Feedback.LINE_NOT_READY);
            return;
        }
        var modelId = RailPlacerItem.selectedModel(player.getMainHandItem());
        if (modelId == null || RailCatalog.find(modelId) == null) {
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
        session.validation = validateGeometry(graph, session);
        return session.validation;
    }

    private static Feedback validateGeometry(TrackGraph graph, Session session) {
        var a = session.start;
        var b = session.end;
        if (a.nodeId() != null && a.nodeId().equals(b.nodeId())) return Feedback.SAME_NODE;
        double gaugeA = a.nodeId() == null ? DEFAULT_GAUGE : graph.node(a.nodeId()).gauge();
        double gaugeB = b.nodeId() == null ? DEFAULT_GAUGE : graph.node(b.nodeId()).gauge();
        session.gauge = a.nodeId() != null ? gaugeA : gaugeB;
        if (a.nodeId() != null && b.nodeId() != null && gaugeA != gaugeB) return Feedback.GAUGE_MISMATCH;
        try {
            var geometry = TrackPlacementGeometry.create(a.position(), b.position(), session.gauge,
                    session.startCurve, session.endCurve);
            return geometry.getLength() <= TrackPlacementGeometry.MAX_LENGTH ? Feedback.READY : Feedback.TOO_LONG;
        } catch (IllegalArgumentException exception) {
            return Feedback.INVALID_GEOMETRY;
        }
    }

    private static void confirm(ServerPlayer player, Session session, String modelId) {
        TrackGraph graph = graph(player);
        if (graph == null || !refreshStart(player, graph, session) || !refreshEnd(player, graph, session)) return;
        Feedback result = validate(graph, session);
        if (result != Feedback.READY) {
            publish(player, session, result);
            return;
        }
        // All input validation precedes the graph's atomic commit; sync sees both endpoint nodes.
        UUID id = UUID.randomUUID();
        try {
            graph.addPlacement(id, session.start, session.end, session.gauge, modelId,
                    session.startCurve, session.endCurve, session.startEdited, session.endEdited);
        } catch (TrackGraphException exception) {
            Feedback feedback = switch (exception.reason()) {
                case BLOCKED_VOLUME -> Feedback.BLOCKED_VOLUME;
                case UNLOADED_VOLUME -> Feedback.UNLOADED_VOLUME;
                case VOLUME_TOO_LARGE -> Feedback.VOLUME_TOO_LARGE;
                case INVALID_POSITION -> Feedback.INVALID_POINT;
                default -> Feedback.INVALID_GEOMETRY;
            };
            publish(player, session, feedback);
            return;
        } catch (IllegalArgumentException exception) {
            session.validation = Feedback.INVALID_GEOMETRY;
            publish(player, session, Feedback.INVALID_GEOMETRY);
            return;
        }
        clear(player, Feedback.CREATED);
        player.sendSystemMessage(Component.translatable("item.unrealtrainmod.track_builder.created", id.toString()));
    }

    private static boolean refreshEnd(ServerPlayer player, TrackGraph graph, Session session) {
        if (session.end == null) return true;
        try {
            session.end = refresh(graph, session.end);
            if (validPosition(player.serverLevel(), session.end.position())) return true;
        } catch (TrackGraphException exception) {
            // The selected end can disappear while its preview is being edited.
        }
        rejectEnd(player, session, Feedback.STALE_END);
        return false;
    }

    private static void rejectEnd(ServerPlayer player, Session session, Feedback feedback) {
        session.end = null;
        session.endCurve = null;
        session.endEdited = false;
        session.validation = Feedback.START;
        session.changed();
        publish(player, session, feedback);
    }

    private static void publish(ServerPlayer player, Session session, Feedback feedback) {
        PacketDistributor.sendToPlayer(player, new TrackConstructionPayload(player.serverLevel().dimension().location(),
                session == null ? Phase.EMPTY : session.validation == Feedback.READY ? Phase.READY : Phase.START,
                session == null ? null : session.start, session == null ? null : session.end,
                session == null ? DEFAULT_GAUGE : session.gauge, feedback,
                session == null ? EMPTY_REVISION : session.revision,
                session == null ? null : session.startCurve, session == null ? null : session.endCurve,
                session == null ? Feedback.START : session.validation));
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
        TrackCurveParameters startCurve;
        TrackCurveParameters endCurve;
        boolean startEdited;
        boolean endEdited;
        UUID revision = UUID.randomUUID();
        UUID editorId;
        boolean editStart;
        double gauge;
        Feedback validation = Feedback.START;

        Session(ServerPlayer player, TrackPlacementEndpoint start) {
            this.player = player;
            level = player.serverLevel();
            this.start = start;
        }

        void changed() {
            revision = UUID.randomUUID();
            editorId = null;
        }

        boolean matches(ServerPlayer current) {
            return player == current && level == current.serverLevel() && current.isAlive() && current.hasPermissions(2);
        }
    }
}
