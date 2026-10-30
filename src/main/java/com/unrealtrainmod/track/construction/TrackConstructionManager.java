/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.construction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.network.payload.TrackConstructionPayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Phase;
import com.unrealtrainmod.network.payload.OpenTrackCurvePayload;
import com.unrealtrainmod.network.payload.SetTrackCurvePayload;
import com.unrealtrainmod.network.payload.TrackCurveResultPayload;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackVerticalCurveParameters;
import com.unrealtrainmod.track.geometry.VerticalTrackGeometry;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackNode;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.block.TrackBlockManager;
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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(TrackConstructionManager.class);
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
                || event.getItemStack().is(UnrealTrainMod.TURNOUT_BUILDER.get())
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
            TurnoutConstructionManager.cancel(player);
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
            TurnoutConstructionManager.cancel(player);
            session = new Session(player, selected);
            session.gauge = selected.nodeId() == null ? DEFAULT_GAUGE : graph.node(selected.nodeId()).gauge();
            initializeHorizontal(graph, session, true);
            session.startVertical = savedVertical(graph, selected);
            session.startVerticalInferred = session.startVertical == null;
            if (session.startVerticalInferred) session.startVertical = inferVertical(graph, selected, 1);
            updateInferredVertical(graph, session);
            SESSIONS.put(player.getUUID(), session);
            publish(player, session, Feedback.START);
        } else if (refreshStart(player, graph, session)) {
            session.end = selected;
            initializeHorizontal(graph, session, false);
            session.endEdited = false;
            updateInferredHorizontal(session);
            session.endVertical = savedVertical(graph, selected);
            session.endVerticalEdited = false;
            session.endVerticalInferred = session.endVertical == null;
            if (session.endVerticalInferred) session.endVertical = inferVertical(graph, selected, 1);
            updateInferredVertical(graph, session);
            session.changed();
            Feedback result = validate(graph, session);
            publish(player, session, result);
        }
    }

    private static void initializeHorizontal(TrackGraph graph, Session session, boolean start) {
        var endpoint = start ? session.start : session.end;
        var saved = endpoint.nodeId() == null ? null : graph.node(endpoint.nodeId()).curve();
        var outward = outwardHorizontal(graph, endpoint);
        boolean inferred = saved == null && outward != null;
        TrackCurveParameters curve = saved;
        if (outward != null) {
            curve = saved == null ? new TrackCurveParameters(TrackVerticalCurveParameters.yaw(outward), 1)
                    : TrackPlacementGeometry.facing(saved, outward);
        }
        if (start) {
            session.startCurve = curve;
            session.startHorizontalDirected = outward != null;
            session.startHorizontalInferred = inferred;
        } else {
            session.endCurve = curve;
            session.endHorizontalDirected = outward != null;
            session.endHorizontalInferred = inferred;
        }
    }

    private static TrackVector outwardHorizontal(TrackGraph graph, TrackPlacementEndpoint endpoint) {
        if (endpoint.nodeId() == null) return null;
        var assembly = graph.assemblyAtPort(endpoint.nodeId());
        if (assembly != null) {
            double yaw = Math.toRadians(assembly.portAt(endpoint.nodeId()).outwardYaw());
            return new TrackVector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        var connections = graph.connections(endpoint.nodeId());
        if (connections.size() != 1) return null;
        var segment = graph.segment(connections.getFirst().segmentId());
        if (graph.assemblyForSegment(segment.id()) != null) return null;
        boolean start = segment.startNodeId().equals(endpoint.nodeId());
        var tangent = segment.geometry().sample(start ? 0 : segment.geometry().getLength()).tangent();
        double scale = (start ? -1 : 1) / Math.hypot(tangent.x(), tangent.z());
        return new TrackVector(tangent.x() * scale, 0, tangent.z() * scale);
    }

    private static void updateInferredHorizontal(Session session) {
        double length = session.end == null ? 1 : Math.clamp(Math.hypot(
                session.end.position().x() - session.start.position().x(),
                session.end.position().z() - session.start.position().z()) / 3,
                TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH);
        if (session.startHorizontalInferred && !session.startEdited) {
            session.startCurve = new TrackCurveParameters(session.startCurve.angle(), length);
        }
        if (session.endHorizontalInferred && !session.endEdited) {
            session.endCurve = new TrackCurveParameters(session.endCurve.angle(), length);
        }
    }

    private static TrackVerticalCurveParameters savedVertical(TrackGraph graph, TrackPlacementEndpoint endpoint) {
        return endpoint.nodeId() == null ? null : graph.node(endpoint.nodeId()).verticalCurve();
    }

    private static boolean flatPort(TrackGraph graph, TrackPlacementEndpoint endpoint) {
        return endpoint != null && endpoint.nodeId() != null && graph.assemblyAtPort(endpoint.nodeId()) != null;
    }

    private static double defaultVerticalLength(Session session) {
        if (session.end == null) return 1;
        try {
            return Math.clamp(TrackPlacementGeometry.horizontal(session.start.position(), session.end.position(),
                    session.gauge, session.startCurve, session.endCurve,
                    session.startHorizontalDirected, session.endHorizontalDirected).getLength() / 3,
                    TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH);
        } catch (IllegalArgumentException exception) {
            return 1; // The horizontal draft remains editable while invalid.
        }
    }

    private static TrackVerticalCurveParameters inferVertical(TrackGraph graph, TrackPlacementEndpoint endpoint, double length) {
        if (endpoint == null || endpoint.nodeId() == null) return null;
        var assembly = graph.assemblyAtPort(endpoint.nodeId());
        if (assembly != null) return new TrackVerticalCurveParameters(0, length, assembly.portAt(endpoint.nodeId()).outwardYaw());
        var connections = graph.connections(endpoint.nodeId());
        if (connections.size() != 1) return null;
        var segment = graph.segment(connections.get(0).segmentId());
        if (graph.assemblyForSegment(segment.id()) != null) return null;
        boolean start = segment.startNodeId().equals(endpoint.nodeId());
        var t = segment.geometry().sample(start ? 0 : segment.geometry().getLength()).tangent();
        return new TrackVerticalCurveParameters(t.y() / Math.hypot(t.x(), t.z()) * 1000, length,
                TrackVerticalCurveParameters.yaw(t));
    }

    private static void updateInferredVertical(TrackGraph graph, Session session) {
        session.startFlatPort = flatPort(graph, session.start);
        session.endFlatPort = flatPort(graph, session.end);
        double length = defaultVerticalLength(session);
        if (session.startVerticalInferred && !session.startVerticalEdited && session.startVertical != null) {
            var curve = session.startVertical;
            session.startVertical = new TrackVerticalCurveParameters(curve.grade(), length, curve.referenceYaw());
        }
        if (session.endVerticalInferred && !session.endVerticalEdited && session.endVertical != null) {
            var curve = session.endVertical;
            session.endVertical = new TrackVerticalCurveParameters(curve.grade(), length, curve.referenceYaw());
        }
        if (session.startFlatPort) session.startVertical = flatVertical(session.startVertical, length);
        if (session.endFlatPort) session.endVertical = flatVertical(session.endVertical, length);
    }

    private static TrackVerticalCurveParameters flatVertical(TrackVerticalCurveParameters curve, double defaultLength) {
        return new TrackVerticalCurveParameters(0, curve == null ? defaultLength : curve.length(),
                curve == null ? 0 : curve.referenceYaw());
    }

    private static TrackVector endpointTangent(Session session, boolean start,
                                                TrackCurveParameters startCurve, TrackCurveParameters endCurve) {
        return TrackPlacementGeometry.endpointTangent(session.start.position(),
                session.end == null ? null : session.end.position(), startCurve, endCurve, start, session.editorYaw,
                session.startHorizontalDirected, session.endHorizontalDirected);
    }

    private static TrackVerticalCurveParameters editorVertical(Session session, boolean start) {
        var tangent = endpointTangent(session, start, session.startCurve, session.endCurve);
        var curve = start ? session.startVertical : session.endVertical;
        double grade = 0;
        if (curve != null) grade = curve.gradeAlong(tangent);
        else if (session.end != null) {
            try {
                double h = TrackPlacementGeometry.horizontal(session.start.position(), session.end.position(),
                        session.gauge, session.startCurve, session.endCurve,
                        session.startHorizontalDirected, session.endHorizontalDirected).getLength();
                grade = (session.end.position().y() - session.start.position().y()) / h * 1000;
            } catch (IllegalArgumentException exception) {
                // Invalid horizontal drafts still allow editing either page.
            }
        }
        return new TrackVerticalCurveParameters(grade, curve == null ? defaultVerticalLength(session) : curve.length(),
                TrackVerticalCurveParameters.yaw(tangent));
    }

    public static void edit(ServerPlayer player) {
        if (TurnoutConstructionManager.hasDraft(player)) { TurnoutConstructionManager.edit(player); return; }
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
        updateInferredVertical(graph, session);
        Vec3 eye = player.getEyePosition();
        Vec3 rayEnd = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        double startHit = hitDistance(session.start, eye, rayEnd);
        double endHit = session.end == null ? Double.POSITIVE_INFINITY : hitDistance(session.end, eye, rayEnd);
        if (!Double.isFinite(Math.min(startHit, endHit))) {
            publish(player, session, Feedback.AIM_NODE);
            return;
        }
        boolean start = startHit <= endHit;
        TrackPlacementEndpoint selected = start ? session.start : session.end;
        if (!canEditPoint(player, selected)) {
            publish(player, session, Feedback.INVALID_POINT);
            return;
        }
        session.editorId = UUID.randomUUID();
        session.editorYaw = player.getYRot();
        session.editStart = start;
        TrackCurveParameters curve = start ? session.startCurve : session.endCurve;
        TrackCurveParameters initial = TrackPlacementGeometry.defaults(session.start.position(),
                session.end == null ? null : session.end.position(), session.startCurve, session.endCurve,
                start, player.getYRot(), session.startHorizontalDirected, session.endHorizontalDirected);
        Feedback feedback = session.end == null ? Feedback.START : validate(graph, session);
        publish(player, session, feedback);
        PacketDistributor.sendToPlayer(player, new OpenTrackCurvePayload(player.serverLevel().dimension().location(),
                session.revision, session.editorId, OpenTrackCurvePayload.ConstructionKind.TRACK, start ? 0 : 1, false, curve == null, initial,
                (start ? session.startVertical : session.endVertical) == null, flatPort(graph, selected), editorVertical(session, start)));
    }

    public static void editOrSwitch(ServerPlayer player, UseOnContext context) {
        Session session = SESSIONS.get(player.getUUID());
        if (player.isShiftKeyDown() || context == null || TurnoutConstructionManager.hasDraft(player)
                || (session != null && session.matches(player))) {
            edit(player);
        } else {
            TurnoutConstructionManager.switchAt(player, context);
        }
    }

    static double hitDistance(TrackPlacementEndpoint endpoint, Vec3 eye, Vec3 end) {
        var p = endpoint.position();
        AABB box = new AABB(p.x() - NODE_HIT_RADIUS, p.y() - NODE_HIT_RADIUS, p.z() - NODE_HIT_RADIUS,
                p.x() + NODE_HIT_RADIUS, p.y() + NODE_HIT_RADIUS, p.z() + NODE_HIT_RADIUS);
        if (box.contains(eye)) return 0;
        return box.clip(eye, end).map(eye::distanceToSqr).orElse(Double.POSITIVE_INFINITY);
    }

    static boolean canEditPoint(ServerPlayer player, TrackPlacementEndpoint endpoint) {
        TrackVector position = endpoint.position();
        if (!validPosition(player.serverLevel(), position)) return false;
        Vec3 eye = player.getEyePosition(), point = new Vec3(position.x(), position.y(), position.z());
        double distance = eye.distanceTo(point);
        if (distance > player.blockInteractionRange() + NODE_HIT_RADIUS) return false;
        var manager = TrackBlockManager.get(player.serverLevel());
        var hit = player.serverLevel().clip(new ClipContext(eye, point,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player) {
            @Override
            public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
                if (endpoint.nodeId() != null && state.is(UnrealTrainMod.TRACK_BLOCK.get())
                        && manager.isNodeTrackBlock(pos, endpoint.nodeId())) {
                    return Shapes.empty();
                }
                return super.getBlockShape(state, level, pos);
            }
        });
        return eye.distanceTo(hit.getLocation()) + 1e-6 >= distance;
    }

    public static void setCurve(ServerPlayer player, SetTrackCurvePayload payload) {
        if (payload.kind() == OpenTrackCurvePayload.ConstructionKind.TURNOUT) {
            TurnoutConstructionManager.setCurve(player, payload); return;
        }
        Session session = SESSIONS.get(player.getUUID());
        Feedback error = null;
        if (!player.isAlive() || !player.hasPermissions(2)) error = Feedback.NO_PERMISSION;
        else if (!player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())) error = Feedback.INVALID_ITEM;
        else if (payload.endpoint() < 0 || payload.endpoint() > 1 || session == null || !session.matches(player)
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
        if (!canEditPoint(player, endpoint)) {
            curveResult(player, payload, false, Feedback.INVALID_POINT);
            return;
        }
        TrackCurveParameters curve;
        TrackVerticalCurveParameters vertical;
        try {
            curve = new TrackCurveParameters(payload.angle(), payload.length());
            TrackCurveParameters startCurve = session.startCurve, endCurve = session.endCurve;
            if (payload.horizontalEdited()) {
                if (payload.start()) startCurve = payload.automatic() ? null : curve;
                else endCurve = payload.automatic() ? null : curve;
            }
            var tangent = endpointTangent(session, payload.start(), startCurve, endCurve);
            vertical = new TrackVerticalCurveParameters(payload.grade(), payload.verticalLength(),
                    TrackVerticalCurveParameters.yaw(tangent));
        } catch (IllegalArgumentException exception) {
            curveResult(player, payload, false, Feedback.INVALID_PARAMETERS);
            return;
        }
        if (flatPort(graph, endpoint) && payload.verticalEdited()
                && (payload.verticalAutomatic() || vertical.grade() != 0)) {
            curveResult(player, payload, false, Feedback.FLAT_PORT);
            return;
        }
        if (payload.automatic()) curve = null;
        if (payload.verticalAutomatic()) vertical = null;
        if (payload.start()) {
            if (payload.horizontalEdited()) {
                session.startCurve = curve;
                session.startEdited = true;
                session.startHorizontalInferred = false;
            }
            if (payload.verticalEdited()) {
                session.startVertical = vertical;
                session.startVerticalEdited = true;
                session.startVerticalInferred = false;
            }
        } else {
            if (payload.horizontalEdited()) {
                session.endCurve = curve;
                session.endEdited = true;
                session.endHorizontalInferred = false;
            }
            if (payload.verticalEdited()) {
                session.endVertical = vertical;
                session.endVerticalEdited = true;
                session.endVerticalInferred = false;
            }
        }
        updateInferredVertical(graph, session);
        session.changed();
        Feedback feedback = session.end == null ? Feedback.START : validate(graph, session);
        publish(player, session, feedback);
        curveResult(player, payload, true, feedback);
    }

    private static void curveResult(ServerPlayer player, SetTrackCurvePayload payload, boolean accepted, Feedback feedback) {
        PacketDistributor.sendToPlayer(player, new TrackCurveResultPayload(payload.editorId(), accepted, feedback));
    }

    public static void place(ServerPlayer player) {
        if (TurnoutConstructionManager.hasDraft(player)) { TurnoutConstructionManager.place(player); return; }
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
        var modelId = player.getMainHandItem().get(ModDataComponents.SELECTED_RAIL_MODEL.get());
        if (modelId == null || RailCatalog.find(modelId) == null) {
            publish(player, session, modelId == null ? Feedback.SELECT_MODEL : Feedback.MISSING_MODEL);
            return;
        }
        confirm(player, session, modelId.toString());
    }

    private static TrackGraph graph(ServerPlayer player) {
        try {
            return TrackBlockManager.get(player.serverLevel()).graph();
        } catch (IllegalStateException exception) {
            LOGGER.error("Cannot load tracks for construction in {}: {}",
                    player.serverLevel().dimension().location(), exception.getMessage());
            clear(player, Feedback.STORAGE);
            return null;
        }
    }

    static TrackPlacementEndpoint snap(TrackGraph graph, TrackVector point) {
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

    static boolean validPosition(ServerLevel level, TrackVector point) {
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
                    session.startCurve, session.endCurve, session.startVertical, session.endVertical,
                    session.startHorizontalDirected, session.endHorizontalDirected);
            return geometry.getLength() <= TrackPlacementGeometry.MAX_LENGTH ? Feedback.READY : Feedback.TOO_LONG;
        } catch (VerticalTrackGeometry.InvalidHandlesException exception) {
            return Feedback.VERTICAL_HANDLES;
        } catch (IllegalArgumentException exception) {
            return Feedback.INVALID_GEOMETRY;
        }
    }

    private static void confirm(ServerPlayer player, Session session, String modelId) {
        TrackGraph graph = graph(player);
        if (graph == null || !refreshStart(player, graph, session) || !refreshEnd(player, graph, session)) return;
        updateInferredVertical(graph, session);
        Feedback result = validate(graph, session);
        if (result != Feedback.READY) {
            publish(player, session, result);
            return;
        }
        // All input validation precedes the graph's atomic commit; sync sees both endpoint nodes.
        UUID id = UUID.randomUUID();
        try {
            graph.addPlacement(id, session.start, session.end, session.gauge, modelId,
                    session.startCurve, session.endCurve, session.startEdited, session.endEdited,
                    session.startVertical, session.endVertical, session.startVerticalEdited, session.endVerticalEdited,
                    session.startHorizontalDirected, session.endHorizontalDirected);
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
        session.endHorizontalDirected = false;
        session.endHorizontalInferred = false;
        session.endVertical = null;
        session.endVerticalEdited = false;
        session.endVerticalInferred = false;
        session.endFlatPort = false;
        updateInferredHorizontal(session);
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
                session != null && session.startHorizontalDirected, session != null && session.endHorizontalDirected,
                session == null ? null : session.startVertical, session == null ? null : session.endVertical,
                session != null && session.startFlatPort, session != null && session.endFlatPort,
                session != null && session.startVerticalInferred, session != null && session.endVerticalInferred,
                session == null ? Feedback.START : session.validation));
    }

    public static void cancel(ServerPlayer player) {
        if (SESSIONS.containsKey(player.getUUID())) clear(player, Feedback.CANCELLED);
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
        boolean startHorizontalDirected;
        boolean endHorizontalDirected;
        boolean startHorizontalInferred;
        boolean endHorizontalInferred;
        TrackVerticalCurveParameters startVertical;
        TrackVerticalCurveParameters endVertical;
        boolean startVerticalEdited;
        boolean endVerticalEdited;
        boolean startVerticalInferred;
        boolean endVerticalInferred;
        boolean startFlatPort;
        boolean endFlatPort;
        UUID revision = UUID.randomUUID();
        UUID editorId;
        boolean editStart;
        double editorYaw;
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
