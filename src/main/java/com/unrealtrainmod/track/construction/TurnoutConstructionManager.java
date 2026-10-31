/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.construction;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.network.payload.OpenTrackCurvePayload;
import com.unrealtrainmod.network.payload.OpenTrackCurvePayload.ConstructionKind;
import com.unrealtrainmod.network.payload.SetTrackCurvePayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.network.payload.TrackCurveResultPayload;
import com.unrealtrainmod.network.payload.TurnoutConstructionPayload;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.TrackVerticalCurveParameters;
import com.unrealtrainmod.track.TurnoutPlacementGeometry;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.SwitchPosition;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.train.TrainManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-only three-point drafts; selection and preview never change the saved graph. */
public final class TurnoutConstructionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurnoutConstructionManager.class);
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private TurnoutConstructionManager() {}

    public static void register(IEventBus bus) {
        bus.addListener(TurnoutConstructionManager::tick);
        bus.addListener(TurnoutConstructionManager::logout);
        bus.addListener(TurnoutConstructionManager::clonePlayer);
        bus.addListener(TurnoutConstructionManager::dimensionChanged);
        bus.addListener(TurnoutConstructionManager::unload);
        bus.addListener(TurnoutConstructionManager::stop);
    }

    public static boolean hasDraft(ServerPlayer player) { return SESSIONS.containsKey(player.getUUID()); }

    public static void cancel(ServerPlayer player) { clear(player, Feedback.CANCELLED); }

    public static void interact(ServerPlayer player, UseOnContext context) {
        if (!player.isAlive() || !player.hasPermissions(2)) { clear(player, Feedback.NO_PERMISSION); return; }
        if (!player.getMainHandItem().is(UnrealTrainMod.TURNOUT_BUILDER.get())) return;
        if (player.isShiftKeyDown()) { TrackConstructionManager.cancel(player); cancel(player); return; }
        Session s = SESSIONS.get(player.getUUID());
        if (s != null && !s.matches(player)) { clear(player, Feedback.CLEARED); s = null; }
        if (context == null) { publish(player, s, Feedback.SELECT_POINT); return; }
        if (context.getClickedFace() != Direction.UP) { publish(player, s, Feedback.TOP_FACE); return; }
        var hit = context.getClickLocation();
        if (!player.canInteractWithBlock(context.getClickedPos(), 0)
                || player.getEyePosition().distanceToSqr(hit) > player.blockInteractionRange() * player.blockInteractionRange()
                || !player.serverLevel().hasChunkAt(context.getClickedPos())) {
            publish(player, s, Feedback.INVALID_POINT); return;
        }
        try {
            var graph = TrackBlockManager.get(player.serverLevel()).graph();
            var selected = TrackConstructionManager.snap(graph, new TrackVector(hit.x, hit.y + 0.3, hit.z));
            if (!TrackConstructionManager.validPosition(player.serverLevel(), selected.position())
                    || !TrackConstructionManager.canEditPoint(player, selected)) {
                publish(player, s, Feedback.INVALID_POINT); return;
            }
            UUID external = null;
            if (selected.nodeId() != null) {
                var connections = graph.connections(selected.nodeId());
                if (graph.assemblyAtPort(selected.nodeId()) != null || connections.size() > 1) {
                    publish(player, s, Feedback.INVALID_NODE); return;
                }
                if (!connections.isEmpty()) {
                    external = connections.getFirst().segmentId();
                    if (Math.abs(externalTangent(graph, selected).y()) > 1e-6) {
                        publish(player, s, Feedback.NON_HORIZONTAL); return;
                    }
                }
            }
            if (s == null) {
                TrackConstructionManager.cancel(player);
                s = new Session(player); SESSIONS.put(player.getUUID(), s);
            } else {
                Feedback stale = refresh(graph, s);
                if (stale != null) { s.validation = stale; publish(player, s, stale); return; }
            }
            int index = Math.min(s.points.size(), 2);
            for (int i = 0; i < index; i++) {
                var other = s.points.get(i);
                if (other.position().equals(selected.position()) || (selected.nodeId() != null && selected.nodeId().equals(other.nodeId()))) {
                    publish(player, s, Feedback.SAME_NODE); return;
                }
                if (Math.abs(other.position().y() - selected.position().y()) > 1e-6) {
                    publish(player, s, Feedback.NON_HORIZONTAL); return;
                }
            }
            boolean hasNode = s.points.subList(0, index).stream().anyMatch(point -> point.nodeId() != null);
            double gauge = selected.nodeId() == null ? s.gauge : graph.node(selected.nodeId()).gauge();
            if (hasNode && selected.nodeId() != null && s.gauge != gauge) {
                publish(player, s, Feedback.GAUGE_MISMATCH); return;
            }
            if (!hasNode) s.gauge = gauge;
            var curve = selected.nodeId() == null ? null : graph.node(selected.nodeId()).curve();
            if (index == s.points.size()) {
                s.points.add(selected); s.curves.add(curve); s.edited.add(false); s.external.add(external);
            } else {
                s.points.set(index, selected); s.curves.set(index, curve); s.edited.set(index, false); s.external.set(index, external);
            }
            s.changed();
            publish(player, s, validate(player, s));
        } catch (IllegalStateException exception) {
            LOGGER.error("Cannot select turnout point", exception); publish(player, s, Feedback.STORAGE);
        }
    }

    /** Points outward from a selected node along its existing external line. */
    private static TrackVector externalTangent(TrackGraph graph, TrackPlacementEndpoint endpoint) {
        var segment = graph.segment(graph.connections(endpoint.nodeId()).getFirst().segmentId());
        boolean start = segment.startNodeId().equals(endpoint.nodeId());
        var t = segment.geometry().sample(start ? 0 : segment.geometry().getLength()).tangent();
        return new TrackVector(start ? t.x() : -t.x(), start ? t.y() : -t.y(), start ? t.z() : -t.z());
    }

    private static Feedback refresh(TrackGraph graph, Session s) {
        try {
            for (int i = 0; i < s.points.size(); i++) {
                var point = s.points.get(i);
                if (!TrackConstructionManager.validPosition(s.level, point.position())) return Feedback.INVALID_POINT;
                if (point.nodeId() == null) continue;
                var node = graph.node(point.nodeId()); var connections = graph.connections(node.id());
                UUID external = s.external.get(i);
                if (!node.position().equals(point.position()) || node.gauge() != s.gauge
                        || graph.assemblyAtPort(node.id()) != null || connections.size() != (external == null ? 0 : 1)
                        || (external != null && !external.equals(connections.getFirst().segmentId()))) return Feedback.INVALID_NODE;
                if (external != null && Math.abs(externalTangent(graph, point).y()) > 1e-6) return Feedback.NON_HORIZONTAL;
            }
            return null;
        } catch (TrackGraphException exception) { return Feedback.INVALID_NODE; }
    }

    private static void updateDefaults(TrackGraph graph, Session s) {
        s.defaults.clear(); s.locked.clear();
        var a = s.points.getFirst().position();
        var b = s.points.size() > 1 ? s.points.get(1).position() : null;
        var c = s.points.size() > 2 ? s.points.get(2).position() : null;
        for (int i = 0; i < s.points.size(); i++) {
            var point = s.points.get(i);
            boolean locked = s.external.get(i) != null;
            var automatic = i == 0 ? TurnoutPlacementGeometry.defaults(a, b, c, s.yaw)
                    : locked ? TrackPlacementGeometry.defaults(point.position(), a, s.yaw)
                    : TurnoutPlacementGeometry.endDefaults(a, point.position(), effective(s, 0));
            var saved = point.nodeId() == null ? null : graph.node(point.nodeId()).curve();
            double angle = i == 0 && saved != null ? saved.angle() : automatic.angle();
            if (locked) {
                var t = externalTangent(graph, point); double sign = i == 0 ? -1 : 1;
                angle = Math.toDegrees(Math.atan2(-sign * t.x(), sign * t.z()));
            } else if (i == 0 && saved != null && b != null) {
                double radians = Math.toRadians(angle);
                if (-Math.sin(radians) * (b.x() - a.x()) + Math.cos(radians) * (b.z() - a.z()) < 0) angle += 180;
            }
            s.defaults.add(new TrackCurveParameters(angle, automatic.length())); s.locked.add(locked);
        }
    }

    private static List<TrackCurveParameters> effective(Session s) {
        var curves = new ArrayList<TrackCurveParameters>();
        for (int i = 0; i < s.points.size(); i++) {
            curves.add(effective(s, i));
        }
        return curves;
    }

    private static TrackCurveParameters effective(Session s, int i) {
        var raw = s.curves.get(i); var automatic = s.defaults.get(i);
        if (i > 0 && raw == null && !s.locked.get(i)) return null;
        double angle = raw == null || s.locked.get(i) || (i == 0 && !s.edited.get(i)) ? automatic.angle() : raw.angle();
        return new TrackCurveParameters(angle, raw == null ? automatic.length() : raw.length());
    }

    private static Feedback validate(ServerPlayer player, Session s) {
        try {
            var blocks = TrackBlockManager.get(player.serverLevel()); var graph = blocks.graph();
            Feedback stale = refresh(graph, s);
            if (stale != null) return s.validation = stale;
            updateDefaults(graph, s);
            if (s.points.size() > 1) {
                var curves = effective(s);
                TurnoutPlacementGeometry.create(s.points.get(0).position(), s.points.get(1).position(),
                        s.points.size() > 2 ? s.points.get(2).position() : null, s.gauge, curves.get(0), curves.get(1),
                        s.points.size() > 2 ? curves.get(2) : null, s.locked.get(1),
                        s.points.size() > 2 && s.locked.get(2));
            }
            if (s.points.size() == 3) {
                var prepared = graph.prepareTurnout(s.points, s.gauge, effective(s), s.edited, null);
                blocks.validatePlacement(prepared.segments());
            }
            return s.validation = s.points.size() == 3 ? Feedback.READY : s.points.size() == 2 ? Feedback.TURNOUT_B : Feedback.TURNOUT_A;
        } catch (TrackGraphException exception) { return s.validation = failure(exception); }
        catch (IllegalArgumentException exception) { return s.validation = Feedback.INVALID_GEOMETRY; }
        catch (IllegalStateException exception) { LOGGER.error("Cannot validate turnout", exception); return s.validation = Feedback.STORAGE; }
    }

    public static void edit(ServerPlayer player) {
        var s = SESSIONS.get(player.getUUID());
        if (player.isShiftKeyDown()) { cancel(player); return; }
        if (s == null || !s.matches(player)) { clear(player, Feedback.NO_DRAFT); return; }
        if (!player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())) { publish(player, s, Feedback.INVALID_ITEM); return; }
        validate(player, s);
        if (s.validation == Feedback.INVALID_NODE || s.validation == Feedback.INVALID_POINT
                || s.validation == Feedback.NON_HORIZONTAL || s.validation == Feedback.STORAGE) {
            publish(player, s, s.validation); return;
        }
        Vec3 eye = player.getEyePosition(), end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        int selected = -1; double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < s.points.size(); i++) {
            double hit = TrackConstructionManager.hitDistance(s.points.get(i), eye, end);
            if (hit < nearest) { nearest = hit; selected = i; }
        }
        if (selected < 0) { publish(player, s, Feedback.AIM_NODE); return; }
        if (!TrackConstructionManager.canEditPoint(player, s.points.get(selected))) { publish(player, s, Feedback.INVALID_POINT); return; }
        s.editorId = UUID.randomUUID(); s.editPoint = selected;
        publish(player, s, s.validation);
        var initial = effective(s, selected);
        PacketDistributor.sendToPlayer(player, new OpenTrackCurvePayload(s.dimension(), s.revision, s.editorId,
                ConstructionKind.TURNOUT, selected, s.locked.get(selected), s.curves.get(selected) == null,
                initial == null ? s.defaults.get(selected) : initial, true, true, new TrackVerticalCurveParameters(0, 1, 0)));
    }

    public static void setCurve(ServerPlayer player, SetTrackCurvePayload payload) {
        var s = SESSIONS.get(player.getUUID()); Feedback error = null;
        if (!player.isAlive() || !player.hasPermissions(2)) error = Feedback.NO_PERMISSION;
        else if (!player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())) error = Feedback.INVALID_ITEM;
        else if (s == null || !s.matches(player) || !s.dimension().equals(payload.dimension())
                || !s.revision.equals(payload.revision()) || !payload.editorId().equals(s.editorId)
                || payload.endpoint() != s.editPoint || payload.endpoint() < 0 || payload.endpoint() >= s.points.size()) error = Feedback.STALE_EDIT;
        if (error == null) {
            validate(player, s);
            if (s.validation == Feedback.INVALID_NODE || s.validation == Feedback.INVALID_POINT
                    || s.validation == Feedback.NON_HORIZONTAL || s.validation == Feedback.STORAGE) error = s.validation;
            else if (!TrackConstructionManager.canEditPoint(player, s.points.get(s.editPoint))) error = Feedback.INVALID_POINT;
            else if (payload.verticalEdited()) error = Feedback.INVALID_PARAMETERS;
        }
        TrackCurveParameters curve = null;
        if (error == null) {
            try {
                curve = new TrackCurveParameters(payload.angle(), payload.length());
                if (s.locked.get(s.editPoint) && !payload.automatic()
                        && Math.abs(normalize(curve.angle() - s.defaults.get(s.editPoint).angle())) > 1e-6) error = Feedback.INVALID_PARAMETERS;
            } catch (IllegalArgumentException exception) { error = Feedback.INVALID_PARAMETERS; }
        }
        if (error != null) {
            PacketDistributor.sendToPlayer(player, new TrackCurveResultPayload(payload.editorId(), false, error)); return;
        }
        if (payload.horizontalEdited()) {
            s.curves.set(s.editPoint, payload.automatic() ? null : curve); s.edited.set(s.editPoint, true);
        }
        s.changed(); Feedback feedback = validate(player, s); publish(player, s, feedback);
        PacketDistributor.sendToPlayer(player, new TrackCurveResultPayload(payload.editorId(), true, feedback));
    }

    public static void place(ServerPlayer player) {
        var s = SESSIONS.get(player.getUUID());
        if (s == null) return;
        if (!s.matches(player)) { clear(player, Feedback.NO_PERMISSION); return; }
        if (!player.getMainHandItem().is(UnrealTrainMod.RAIL_PLACER.get())) { publish(player, s, Feedback.INVALID_ITEM); return; }
        if (player.isShiftKeyDown()) { cancel(player); return; }
        Feedback feedback = validate(player, s);
        if (feedback != Feedback.READY) { publish(player, s, feedback); return; }
        var model = player.getMainHandItem().get(ModDataComponents.SELECTED_RAIL_MODEL.get());
        if (model == null || RailCatalog.find(model) == null) {
            publish(player, s, model == null ? Feedback.SELECT_MODEL : Feedback.MISSING_MODEL); return;
        }
        try {
            var assembly = TrackBlockManager.get(player.serverLevel()).graph().createTurnout(s.points, s.gauge, effective(s), s.edited, model.toString());
            clear(player, Feedback.CREATED);
            player.sendSystemMessage(Component.translatable("item.unrealtrainmod.turnout_builder.created", assembly.id().toString()));
        } catch (TrackGraphException exception) { publish(player, s, failure(exception)); }
        catch (IllegalArgumentException exception) { publish(player, s, Feedback.INVALID_GEOMETRY); }
        catch (IllegalStateException exception) { LOGGER.error("Cannot place turnout", exception); publish(player, s, Feedback.STORAGE); }
    }

    private static Feedback failure(TrackGraphException exception) {
        return switch (exception.reason()) {
            case UNKNOWN_NODE, NODE_CONNECTED -> Feedback.INVALID_NODE;
            case BLOCKED_VOLUME -> Feedback.BLOCKED_VOLUME;
            case UNLOADED_VOLUME -> Feedback.UNLOADED_VOLUME;
            case VOLUME_TOO_LARGE -> Feedback.VOLUME_TOO_LARGE;
            case VEHICLE_PRESENT -> Feedback.VEHICLE_PRESENT;
            case INVALID_POSITION -> Feedback.INVALID_POINT;
            case GAUGE_MISMATCH -> Feedback.GAUGE_MISMATCH;
            default -> Feedback.INVALID_GEOMETRY;
        };
    }

    /** Called only from the server's native item interaction, never from an instance-ID request. */
    public static void switchAt(ServerPlayer player, UseOnContext context) {
        if (!player.isAlive() || !player.hasPermissions(2)) { message(player, Feedback.NO_PERMISSION, ""); return; }
        if (!player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get()) || context == null
                || !player.canInteractWithBlock(context.getClickedPos(), 0)
                || !player.serverLevel().hasChunkAt(context.getClickedPos())
                || player.getEyePosition().distanceToSqr(context.getClickLocation())
                    > player.blockInteractionRange() * player.blockInteractionRange()) {
            message(player, Feedback.INVALID_POINT, ""); return;
        }
        try {
            var blocks = TrackBlockManager.get(player.serverLevel());
            var graph = blocks.graph();
            var ids = blocks.segmentsAt(context.getClickedPos()).stream().map(t -> graph.assemblyForSegment(t.id()))
                    .filter(Objects::nonNull).map(TrackAssemblyInstance::id).distinct().toList();
            if (ids.size() != 1) {
                player.displayClientMessage(Component.translatable("item.unrealtrainmod.turnout_builder."
                        + (ids.isEmpty() ? "aim_turnout" : "ambiguous")).withStyle(ChatFormatting.RED), true);
                return;
            }
            TrainManager.get(player.serverLevel()).refreshTurnoutOccupancy();
            var assembly = graph.assembly(ids.getFirst());
            if (assembly.occupant() != null) {
                player.displayClientMessage(Component.translatable("item.unrealtrainmod.turnout_builder.occupied",
                        assembly.occupant().toString()).withStyle(ChatFormatting.RED), true); return;
            }
            var next = assembly.state().controls().get("main") == SwitchPosition.NORMAL
                    ? SwitchPosition.REVERSE : SwitchPosition.NORMAL;
            graph.setAssemblyState(assembly.id(), assembly.state().withControl("main", next));
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.turnout_builder.switched",
                    Component.translatable("commands.unrealtrainmod.turnout." + (assembly.definition() == TrackAssemblyDefinition.CUSTOM
                            ? next == SwitchPosition.NORMAL ? "line_one" : "line_two"
                            : next == SwitchPosition.NORMAL ? "straight" : "diverging")))
                    .withStyle(ChatFormatting.GREEN), true);
        } catch (IllegalStateException exception) { message(player, Feedback.STORAGE, ""); }
    }


    private static double normalize(double yaw) { return ((yaw % 360) + 540) % 360 - 180; }
    private static void message(ServerPlayer player, Feedback feedback, String detail) {
        player.displayClientMessage(Component.translatable(feedback.translationKey(), detail).withStyle(ChatFormatting.RED), true);
    }
    private static void publish(ServerPlayer player, Session s, Feedback feedback) {
        var curves = s == null ? new ArrayList<TrackCurveParameters>() : new ArrayList<>(s.curves);
        if (s != null && !curves.isEmpty() && curves.get(0) != null && s.defaults.size() == curves.size()) {
            curves.set(0, effective(s).get(0));
        }
        PacketDistributor.sendToPlayer(player, new TurnoutConstructionPayload(player.serverLevel().dimension().location(),
                s == null ? new UUID(0, 0) : s.revision, s == null ? List.of() : List.copyOf(s.points), s == null ? 1.435 : s.gauge,
                curves, s == null ? List.of() : List.copyOf(s.defaults),
                s == null ? List.of() : List.copyOf(s.locked), feedback, s == null ? Feedback.START : s.validation));
    }
    private static void clear(ServerPlayer player, Feedback feedback) {
        if (SESSIONS.remove(player.getUUID()) != null || feedback != Feedback.CANCELLED) publish(player, null, feedback);
    }
    private static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var s = SESSIONS.get(player.getUUID());
            if (s != null && !s.matches(player)) clear(player, Feedback.CLEARED);
        }
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) { SESSIONS.remove(event.getEntity().getUUID()); }
    private static void clonePlayer(PlayerEvent.Clone event) { SESSIONS.remove(event.getOriginal().getUUID()); }
    private static void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clear(player, Feedback.CLEARED);
    }
    private static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) SESSIONS.values().removeIf(s -> s.level == level);
    }
    private static void stop(ServerStoppedEvent event) { SESSIONS.clear(); }

    private static final class Session {
        final ServerPlayer player;
        final ServerLevel level;
        final double yaw;
        final List<TrackPlacementEndpoint> points = new ArrayList<>();
        final List<TrackCurveParameters> curves = new ArrayList<>(), defaults = new ArrayList<>();
        final List<Boolean> edited = new ArrayList<>(), locked = new ArrayList<>();
        final List<UUID> external = new ArrayList<>();
        double gauge = 1.435;
        UUID revision = UUID.randomUUID(), editorId;
        int editPoint;
        Feedback validation = Feedback.TURNOUT_A;
        Session(ServerPlayer player) { this.player = player; level = player.serverLevel(); yaw = player.getYRot(); }
        void changed() { revision = UUID.randomUUID(); editorId = null; }
        ResourceLocation dimension() { return level.dimension().location(); }
        boolean matches(ServerPlayer p) {
            return p == player && p.isAlive() && p.hasPermissions(2) && level == p.serverLevel();
        }
    }
}
