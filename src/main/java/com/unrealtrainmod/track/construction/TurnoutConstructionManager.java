/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.construction;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.item.TurnoutBuilderItem;
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
import com.unrealtrainmod.track.AssemblyPlacementGeometry;
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
import java.util.Set;
import java.util.stream.Collectors;
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

/** Server-only typed drafts; selection and preview never change the saved graph. */
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
        if (!(player.getMainHandItem().getItem() instanceof TurnoutBuilderItem tool)) return;
        if (player.isShiftKeyDown()) { TrackConstructionManager.cancel(player); cancel(player); return; }
        var kind = tool.constructionType();
        if (!kind.implemented()) {
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.turnout_builder.unimplemented")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
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
            if (selected.nodeId() != null) {
                if (graph.isAssemblyInternalNode(selected.nodeId())) { publish(player, s, Feedback.INVALID_NODE); return; }
                if (!horizontalConnections(graph, selected)) { publish(player, s, Feedback.NON_HORIZONTAL); return; }
            }
            Set<UUID> external = connections(graph, selected);
            boolean newDraft = s == null || s.kind != kind;
            if (newDraft) {
                s = new Session(player, kind);
            } else {
                Feedback stale = refresh(graph, s);
                if (stale != null) { s.validation = stale; publish(player, s, stale); return; }
            }
            int index = Math.min(s.points.size(), kind.pointCount() - 1);
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
            if (newDraft) {
                TrackConstructionManager.cancel(player);
                SESSIONS.put(player.getUUID(), s);
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

    private static Set<UUID> connections(TrackGraph graph, TrackPlacementEndpoint point) {
        return point.nodeId() == null ? Set.of() : graph.connections(point.nodeId()).stream()
                .map(value -> value.segmentId()).collect(Collectors.toSet());
    }

    private static boolean horizontalConnections(TrackGraph graph, TrackPlacementEndpoint point) {
        for (var connection : graph.connections(point.nodeId())) {
            var geometry = graph.segment(connection.segmentId()).geometry();
            if (Math.abs(geometry.sample(connection.fromStart() ? 0 : geometry.getLength()).tangent().y()) > 1e-6) return false;
        }
        return true;
    }

    private static Feedback refresh(TrackGraph graph, Session s) {
        try {
            for (int i = 0; i < s.points.size(); i++) {
                var point = s.points.get(i);
                if (!TrackConstructionManager.validPosition(s.level, point.position())) return Feedback.INVALID_POINT;
                if (point.nodeId() == null) continue;
                var node = graph.node(point.nodeId());
                if (!node.position().equals(point.position()) || node.gauge() != s.gauge
                        || graph.isAssemblyInternalNode(node.id()) || !connections(graph, point).equals(s.external.get(i))) {
                    return Feedback.INVALID_NODE;
                }
                if (!horizontalConnections(graph, point)) return Feedback.NON_HORIZONTAL;
            }
            return null;
        } catch (TrackGraphException exception) { return Feedback.INVALID_NODE; }
    }

    private static void updateDefaults(TrackGraph graph, Session s) {
        for (int i = 0; i < s.points.size(); i++) {
            if (s.edited.get(i)) continue;
            var point = s.points.get(i);
            int partner = AssemblyPlacementGeometry.partner(s.kind, i);
            var other = partner < s.points.size() ? s.points.get(partner).position() : null;
            var automatic = TrackPlacementGeometry.defaults(point.position(), other, s.yaw);
            var outward = TrackConstructionManager.outwardHorizontal(graph, point);
            var saved = point.nodeId() == null ? null : graph.node(point.nodeId()).curve();
            if (saved != null) {
                var toward = outward != null ? outward : other == null ? null
                        : new TrackVector(other.x() - point.position().x(), 0, other.z() - point.position().z());
                s.curves.set(i, toward == null ? saved : TrackPlacementGeometry.facing(saved, toward));
            } else {
                s.curves.set(i, outward == null ? null : new TrackCurveParameters(
                        Math.toDegrees(Math.atan2(-outward.x(), outward.z())), automatic.length()));
            }
        }
        s.defaults.clear();
        s.defaults.addAll(AssemblyPlacementGeometry.defaults(s.kind,
                s.points.stream().map(TrackPlacementEndpoint::position).toList(), s.curves, s.yaw));
    }

    private static Feedback validate(ServerPlayer player, Session s) {
        try {
            var blocks = TrackBlockManager.get(player.serverLevel()); var graph = blocks.graph();
            Feedback stale = refresh(graph, s);
            if (stale != null) return s.validation = stale;
            updateDefaults(graph, s);
            if (s.points.size() == s.kind.pointCount()) {
                var prepared = graph.prepareAssembly(s.kind, s.points, s.gauge, s.curves, s.edited, s.yaw, null);
                blocks.validatePlacement(prepared.segments());
                return s.validation = Feedback.READY;
            }
            AssemblyPlacementGeometry.create(s.kind, s.points.stream().map(TrackPlacementEndpoint::position).toList(),
                    s.gauge, s.curves, s.yaw);
            return s.validation = switch (s.kind) {
                case DIAMOND_CROSSING -> Feedback.CROSSING_POINT;
                case SINGLE_CROSSOVER -> Feedback.CROSSOVER_POINT;
                default -> s.points.size() == 2 ? Feedback.TURNOUT_B : Feedback.TURNOUT_A;
            };
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
        var initial = s.defaults.get(selected);
        PacketDistributor.sendToPlayer(player, new OpenTrackCurvePayload(s.dimension(), s.revision, s.editorId,
                ConstructionKind.TURNOUT, selected, s.curves.get(selected) == null,
                initial, true, true, new TrackVerticalCurveParameters(0, 1, 0)));
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
            var graph = TrackBlockManager.get(player.serverLevel()).graph();
            var assembly = graph.createAssembly(s.kind, s.points, s.gauge, s.curves, s.edited, s.yaw, model.toString());
            clear(player, Feedback.CREATED);
            player.sendSystemMessage(Component.translatable("item.unrealtrainmod." + builderKey(s.kind) + ".created",
                    assembly.id().toString()));
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
            if (assembly.state().controls().isEmpty()) {
                player.displayClientMessage(Component.translatable("commands.unrealtrainmod.turnout.no_controls")
                        .withStyle(ChatFormatting.GRAY), true);
                return;
            }
            if (assembly.occupant() != null) {
                player.displayClientMessage(Component.translatable("item.unrealtrainmod.turnout_builder.occupied",
                        assembly.occupant().toString()).withStyle(ChatFormatting.RED), true); return;
            }
            var current = assembly.state();
            if (!current.controls().containsKey("main")) {
                boolean straight = current.controls().values().stream().allMatch(position -> position == SwitchPosition.NORMAL);
                var next = straight ? SwitchPosition.REVERSE : SwitchPosition.NORMAL;
                var updated = current;
                for (String control : current.controls().keySet()) updated = updated.withControl(control, next);
                graph.setAssemblyState(assembly.id(), updated);
                player.displayClientMessage(Component.translatable("item.unrealtrainmod.turnout_builder.switched",
                        Component.translatable("commands.unrealtrainmod.turnout." + (next == SwitchPosition.NORMAL
                                ? "crossover_straight" : "crossover_diverging")))
                        .withStyle(ChatFormatting.GREEN), true);
                return;
            }
            var next = current.controls().get("main") == SwitchPosition.NORMAL
                    ? SwitchPosition.REVERSE : SwitchPosition.NORMAL;
            graph.setAssemblyState(assembly.id(), current.withControl("main", next));
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.turnout_builder.switched",
                    Component.translatable("commands.unrealtrainmod.turnout." + (next == SwitchPosition.NORMAL ? "line_one" : "line_two")))
                    .withStyle(ChatFormatting.GREEN), true);
        } catch (IllegalStateException exception) { message(player, Feedback.STORAGE, ""); }
    }


    private static String builderKey(TrackConstructionType kind) {
        return switch (kind) {
            case DIAMOND_CROSSING -> "diamond_crossing_builder";
            case SINGLE_CROSSOVER -> "single_crossover_builder";
            default -> "turnout_builder";
        };
    }

    private static void message(ServerPlayer player, Feedback feedback, String detail) {
        player.displayClientMessage(Component.translatable(feedback.translationKey(), detail).withStyle(ChatFormatting.RED), true);
    }
    private static void publish(ServerPlayer player, Session s, Feedback feedback) {
        PacketDistributor.sendToPlayer(player, new TurnoutConstructionPayload(player.serverLevel().dimension().location(),
                s == null ? new UUID(0, 0) : s.revision, s == null ? TrackConstructionType.SIMPLE_TURNOUT : s.kind,
                s == null ? List.of() : List.copyOf(s.points), s == null ? 1.435 : s.gauge,
                s == null ? List.of() : new ArrayList<>(s.curves), s == null ? 0 : s.yaw, feedback, s == null ? Feedback.START : s.validation));
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
        final TrackConstructionType kind;
        final double yaw;
        final List<TrackPlacementEndpoint> points = new ArrayList<>();
        final List<TrackCurveParameters> curves = new ArrayList<>(), defaults = new ArrayList<>();
        final List<Boolean> edited = new ArrayList<>();
        final List<Set<UUID>> external = new ArrayList<>();
        double gauge = 1.435;
        UUID revision = UUID.randomUUID(), editorId;
        int editPoint;
        Feedback validation = Feedback.TURNOUT_A;
        Session(ServerPlayer player, TrackConstructionType kind) {
            this.player = player; this.kind = kind; level = player.serverLevel(); yaw = player.getYRot();
        }
        void changed() { revision = UUID.randomUUID(); editorId = null; }
        ResourceLocation dimension() { return level.dimension().location(); }
        boolean matches(ServerPlayer p) {
            return p == player && p.isAlive() && p.hasPermissions(2) && level == p.serverLevel();
        }
    }
}
