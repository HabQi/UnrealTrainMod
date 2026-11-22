/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.construction;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.item.RailPlacerItem;
import com.unrealtrainmod.network.payload.ConfirmTurnoutPayload;
import com.unrealtrainmod.network.payload.OpenTrackCurvePayload;
import com.unrealtrainmod.network.payload.OpenTrackCurvePayload.ConstructionKind;
import com.unrealtrainmod.network.payload.SetTrackCurvePayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.network.payload.TrackCurveResultPayload;
import com.unrealtrainmod.network.payload.TurnoutConstructionPayload;
import com.unrealtrainmod.track.AssemblyPlacementGeometry;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.TrackVerticalCurveParameters;
import com.unrealtrainmod.track.assembly.SwitchPosition;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.model.NativeTurnoutCatalog;
import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.track.model.RailDefinition;
import com.unrealtrainmod.train.TrainManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
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

/** Server-owned endpoint drafts and placement of the previewed layout. */
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
        if (context == null) {
            if (s == null) publish(player, null, Feedback.SELECT_POINT);
            else cycleDiagonal(player, s);
            return;
        }
        if (context.getClickedFace() != Direction.UP) { publish(player, s, Feedback.TOP_FACE); return; }
        var hit = context.getClickLocation();
        if (!player.canInteractWithBlock(context.getClickedPos(), 0)
                || player.getEyePosition().distanceToSqr(hit) > player.blockInteractionRange() * player.blockInteractionRange()
                || !player.serverLevel().hasChunkAt(context.getClickedPos())) {
            publish(player, s, Feedback.INVALID_POINT); return;
        }
        try {
            var graph = TrackBlockManager.get(player.serverLevel()).graph();
            var selected = TrackConstructionManager.snap(graph,
                    new TrackVector(hit.x, hit.y + TrackPlacementGeometry.DEFAULT_RAIL_SURFACE_OFFSET, hit.z));
            if (!TrackConstructionManager.canEditPoint(player, selected)) {
                publish(player, s, Feedback.INVALID_POINT); return;
            }
            if (selected.nodeId() != null) {
                if (graph.isAssemblyInternalNode(selected.nodeId())) { publish(player, s, Feedback.INVALID_NODE); return; }
                if (!horizontalConnections(graph, selected)) { publish(player, s, Feedback.NON_HORIZONTAL); return; }
            }
            boolean newDraft = s == null;
            if (newDraft) s = new Session(player);
            int index = Math.min(s.points.size(), 3);
            var retained = s.points.subList(0, index);
            double gauge = RailDefinition.STANDARD_GAUGE;
            boolean hasGauge = false;
            for (var point : retained) {
                var other = point.endpoint();
                if (Math.hypot(other.position().x() - selected.position().x(), other.position().z() - selected.position().z()) < 1e-6
                        || selected.nodeId() != null && selected.nodeId().equals(other.nodeId())) {
                    publish(player, s, Feedback.SAME_NODE); return;
                }
                if (Math.abs(other.position().y() - selected.position().y()) > 1e-6) {
                    publish(player, s, Feedback.NON_HORIZONTAL); return;
                }
                if (other.nodeId() != null) {
                    gauge = s.gauge;
                    hasGauge = true;
                }
            }
            if (selected.nodeId() != null) {
                double nodeGauge = graph.node(selected.nodeId()).gauge();
                if (hasGauge && gauge != nodeGauge) { publish(player, s, Feedback.GAUGE_MISMATCH); return; }
                gauge = nodeGauge;
            }
            var point = new SelectedPoint(selected, connections(graph, selected));
            if (index == s.points.size()) {
                s.points.add(point);
            } else {
                var replaced = s.points.set(index, point);
                if (!selected.equals(replaced.endpoint())) s.manual.remove(replaced.endpoint());
            }
            s.gauge = gauge;
            s.preferred = List.of();
            s.invalidate();
            if (newDraft) {
                TrackConstructionManager.cancel(player);
                SESSIONS.put(player.getUUID(), s);
            }
            publish(player, s, validate(player, s));
        } catch (TrackGraphException exception) { publish(player, s, failure(exception)); }
        catch (IllegalStateException exception) {
            LOGGER.error("Cannot select turnout point", exception);
            publish(player, s, Feedback.STORAGE);
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
            for (var selected : s.points) {
                var point = selected.endpoint();
                if (!TrackConstructionManager.validPosition(s.level, point.position())) return Feedback.INVALID_POINT;
                if (point.nodeId() == null) continue;
                var node = graph.node(point.nodeId());
                if (!node.position().equals(point.position()) || node.gauge() != s.gauge
                        || graph.isAssemblyInternalNode(node.id()) || !connections(graph, point).equals(selected.external())) {
                    return Feedback.INVALID_NODE;
                }
                if (!horizontalConnections(graph, point)) return Feedback.NON_HORIZONTAL;
            }
            return null;
        } catch (TrackGraphException exception) { return Feedback.INVALID_NODE; }
    }

    private static Feedback validate(ServerPlayer player, Session s) {
        try {
            var selection = RailPlacerItem.constructionSelection(player);
            if (!selection.equals(s.selection)) {
                var kind = selection.constructionType();
                if (kind != s.kind) s.manual.clear();
                s.selection = selection;
                s.kind = kind;
                s.preferred = List.of();
                s.invalidate();
            }
            var blocks = TrackBlockManager.get(s.level);
            var graph = blocks.graph();
            var stale = refresh(graph, s);
            if (stale != null) {
                if (!s.input.isEmpty()) s.invalidate();
                return s.validation = stale;
            }
            if (s.points.size() < 3) return s.validation = Feedback.TURNOUT_POINTS;
            if (s.selection.modelId() == null) return s.validation = Feedback.SELECT_MODEL;
            if (!RailCatalog.contains(s.selection.modelId())) return s.validation = Feedback.MISSING_MODEL;
            if (s.kind == null) return s.validation = Feedback.MODEL_UNSUPPORTED;
            if (s.points.size() != s.kind.pointCount()) return s.validation = Feedback.TURNOUT_POINT_COUNT;

            var input = s.points.stream().map(selected -> {
                var point = selected.endpoint();
                var saved = point.nodeId() == null ? null : graph.node(point.nodeId()).curve();
                return new TurnoutPlacementSolver.Endpoint(point, TrackConstructionManager.outwardHorizontal(graph, point),
                        saved, s.manual.get(point));
            }).toList();
            if (!input.equals(s.input)) {
                if (!s.input.isEmpty()) s.invalidate();
                s.input = input;
            }
            return s.validation = solve(blocks, s);
        } catch (TrackGraphException exception) { return s.validation = failure(exception); }
        catch (IllegalArgumentException exception) { return s.validation = Feedback.INVALID_GEOMETRY; }
        catch (IllegalStateException exception) {
            LOGGER.error("Cannot validate turnout", exception);
            return s.validation = Feedback.STORAGE;
        }
    }

    private static Feedback solve(TrackBlockManager blocks, Session s) {
        var waiting = s.kind == TrackConstructionType.SINGLE_CROSSOVER && NativeTurnoutCatalog.SINGLE_CROSSOVER_R11.equals(s.selection.modelId())
                ? Feedback.NATIVE_CROSSOVER_SOLVING : Feedback.TURNOUT_SOLVING;
        if (s.solveFeedback == null) {
            if (s.pendingSolve == null) {
                var input = s.input;
                var preferred = s.preferred;
                var kind = s.kind;
                double gauge = s.gauge;
                boolean nativeModel = NativeTurnoutCatalog.contains(s.selection.modelId());
                var cancelled = new AtomicBoolean();
                s.solveCancelled = cancelled;
                s.pendingSolve = CompletableFuture.supplyAsync(
                        () -> TurnoutPlacementSolver.solve(kind, nativeModel, gauge, input, preferred, cancelled::get),
                        net.minecraft.Util.backgroundExecutor());
                return waiting;
            }
            if (!s.pendingSolve.isDone()) return waiting;
            try {
                var result = s.pendingSolve.join();
                s.layout = result.primary();
                s.alternate = result.alternate();
                s.solveFeedback = result.feedback();
            } catch (CompletionException exception) {
                LOGGER.warn("Cannot resolve turnout draft for {}", s.player.getUUID(), exception.getCause());
                s.solveFeedback = Feedback.INVALID_GEOMETRY;
            } finally {
                s.pendingSolve = null;
                s.solveCancelled = null;
            }
            s.newRevision();
            var primary = checkLayout(blocks, s, s.layout);
            s.alternateReady = s.alternate != null && checkLayout(blocks, s, s.alternate) == Feedback.READY;
            if (s.preferred.isEmpty() && primary != Feedback.READY && s.alternateReady) {
                s.swapDiagonals();
                s.alternateReady = false;
                return Feedback.READY;
            }
            return primary;
        }
        return checkLayout(blocks, s, s.layout);
    }

    private static Feedback checkLayout(TrackBlockManager blocks, Session s, TurnoutPlacementSolver.Layout layout) {
        if (layout == null || layout.geometry() == null) return s.solveFeedback;
        try {
            var prepared = blocks.graph().prepareAssembly(s.kind, layout.points(), s.gauge, layout.curves(), layout.edited(),
                    s.selection.modelId().toString(), layout.geometry());
            blocks.validatePlacement(prepared.segments());
            return Feedback.READY;
        } catch (TrackGraphException exception) { return failure(exception); }
        catch (IllegalArgumentException exception) { return Feedback.INVALID_GEOMETRY; }
    }

    private static void cycleDiagonal(ServerPlayer player, Session s) {
        var revision = s.revision;
        var feedback = validate(player, s);
        if (revision.equals(s.revision) && s.alternate != null
                && feedback != Feedback.INVALID_NODE && feedback != Feedback.INVALID_POINT
                && feedback != Feedback.NON_HORIZONTAL && feedback != Feedback.STORAGE) {
            var blocks = TrackBlockManager.get(s.level);
            s.alternateReady = checkLayout(blocks, s, s.alternate) == Feedback.READY;
            if (s.alternateReady) {
                s.swapDiagonals();
                s.alternateReady = feedback == Feedback.READY;
                feedback = s.validation = Feedback.READY;
                s.newRevision();
            }
        }
        publish(player, s, feedback);
    }

    public static void edit(ServerPlayer player) {
        var s = SESSIONS.get(player.getUUID());
        if (player.isShiftKeyDown()) { cancel(player); return; }
        if (s == null || !s.matches(player)) { clear(player, Feedback.NO_DRAFT); return; }
        if (!player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())) { publish(player, s, Feedback.INVALID_ITEM); return; }
        validate(player, s);
        if (s.layout == null || s.validation == Feedback.INVALID_NODE || s.validation == Feedback.INVALID_POINT
                || s.validation == Feedback.NON_HORIZONTAL || s.validation == Feedback.STORAGE) {
            publish(player, s, s.validation); return;
        }
        Vec3 eye = player.getEyePosition(), end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        int selected = -1;
        double nearest = Double.POSITIVE_INFINITY;
        var points = s.layout.points();
        for (int i = 0; i < points.size(); i++) {
            double hit = TrackConstructionManager.hitDistance(points.get(i), eye, end);
            if (hit < nearest) { nearest = hit; selected = i; }
        }
        if (selected < 0) { publish(player, s, Feedback.AIM_NODE); return; }
        var point = points.get(selected);
        if (!TrackConstructionManager.canEditPoint(player, point)) { publish(player, s, Feedback.INVALID_POINT); return; }
        s.editorId = UUID.randomUUID();
        s.editPoint = selected;
        publish(player, s, s.validation);
        var initial = AssemblyPlacementGeometry.defaults(s.kind, points.stream().map(TrackPlacementEndpoint::position).toList(),
                s.layout.curves(), 0).get(selected);
        PacketDistributor.sendToPlayer(player, new OpenTrackCurvePayload(s.dimension(), s.revision, s.editorId,
                ConstructionKind.TURNOUT, selected, !s.manual.containsKey(point), initial, true, true,
                new TrackVerticalCurveParameters(0, 1, 0)));
    }

    public static void setCurve(ServerPlayer player, SetTrackCurvePayload payload) {
        var s = SESSIONS.get(player.getUUID());
        Feedback error = null;
        if (!player.isAlive() || !player.hasPermissions(2)) error = Feedback.NO_PERMISSION;
        else if (!player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())) error = Feedback.INVALID_ITEM;
        else if (s == null || !s.matches(player) || s.layout == null || !s.dimension().equals(payload.dimension())
                || !s.revision.equals(payload.revision()) || !payload.editorId().equals(s.editorId)
                || payload.endpoint() != s.editPoint) error = Feedback.STALE_EDIT;
        if (error == null) {
            validate(player, s);
            if (!s.revision.equals(payload.revision()) || !payload.editorId().equals(s.editorId)) error = Feedback.STALE_EDIT;
            else if (s.validation == Feedback.INVALID_NODE || s.validation == Feedback.INVALID_POINT
                    || s.validation == Feedback.NON_HORIZONTAL || s.validation == Feedback.STORAGE) error = s.validation;
            else if (!TrackConstructionManager.canEditPoint(player, s.layout.points().get(s.editPoint))) error = Feedback.INVALID_POINT;
            else if (payload.verticalEdited()) error = Feedback.INVALID_PARAMETERS;
        }
        TrackCurveParameters curve = null;
        if (error == null && payload.horizontalEdited() && !payload.automatic()) {
            try {
                curve = new TrackCurveParameters(payload.angle(), payload.length());
            } catch (IllegalArgumentException exception) { error = Feedback.INVALID_PARAMETERS; }
        }
        if (error != null) {
            PacketDistributor.sendToPlayer(player, new TrackCurveResultPayload(payload.editorId(), false, error));
            return;
        }
        if (payload.horizontalEdited()) {
            var point = s.layout.points().get(s.editPoint);
            if (curve == null) s.manual.remove(point);
            else s.manual.put(point, curve);
            s.preferred = s.layout.points();
            s.invalidate();
        } else {
            s.editorId = null;
        }
        var feedback = validate(player, s);
        publish(player, s, feedback);
        PacketDistributor.sendToPlayer(player, new TrackCurveResultPayload(payload.editorId(), true, feedback));
    }

    /** Native item interaction handles cancellation and re-sync when no client revision was supplied. */
    public static void place(ServerPlayer player) {
        var s = SESSIONS.get(player.getUUID());
        if (s == null) return;
        if (!s.matches(player)) { clear(player, Feedback.NO_PERMISSION); return; }
        if (!player.getMainHandItem().is(UnrealTrainMod.RAIL_PLACER.get())) { publish(player, s, Feedback.INVALID_ITEM); return; }
        if (player.isShiftKeyDown()) { cancel(player); return; }
        publish(player, s, validate(player, s));
    }

    public static void confirm(ServerPlayer player, ConfirmTurnoutPayload payload) {
        var s = SESSIONS.get(player.getUUID());
        if (s == null || !s.dimension().equals(payload.dimension())) return;
        if (!s.matches(player)) { clear(player, Feedback.NO_PERMISSION); return; }
        if (!player.getMainHandItem().is(UnrealTrainMod.RAIL_PLACER.get()) || player.isShiftKeyDown()) return;
        if (!player.canInteractWithBlock(payload.clicked(), 0) || !s.level.hasChunkAt(payload.clicked())) {
            publish(player, s, Feedback.INVALID_POINT); return;
        }
        var feedback = validate(player, s);
        if (feedback != Feedback.READY || !s.revision.equals(payload.revision())) {
            publish(player, s, feedback); return;
        }
        try {
            var graph = TrackBlockManager.get(s.level).graph();
            var layout = s.layout;
            var assembly = graph.createAssembly(s.kind, layout.points(), s.gauge, layout.curves(), layout.edited(),
                    s.selection.modelId().toString(), layout.geometry());
            clear(player, Feedback.CREATED);
            player.sendSystemMessage(Component.translatable("item.unrealtrainmod.turnout_builder.created." + s.kind.getSerializedName(),
                    assembly.id().toString()));
        } catch (TrackGraphException exception) { publish(player, s, failure(exception)); }
        catch (IllegalArgumentException exception) { publish(player, s, Feedback.INVALID_GEOMETRY); }
        catch (IllegalStateException exception) {
            LOGGER.error("Cannot place turnout", exception);
            publish(player, s, Feedback.STORAGE);
        }
    }

    private static Feedback failure(TrackGraphException exception) {
        return switch (exception.reason()) {
            case UNKNOWN_NODE, NODE_CONNECTED -> Feedback.INVALID_NODE;
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
            if (assembly.definition() == TrackAssemblyDefinition.DOUBLE_SLIP) {
                String path = TrackAssemblyDefinition.nextDoubleSlipPath(current);
                graph.setAssemblyState(assembly.id(), TrackAssemblyDefinition.doubleSlipState(path, current));
                var route = assembly.definition().routes().get(path);
                player.displayClientMessage(Component.translatable("item.unrealtrainmod.turnout_builder.switched",
                        Component.literal(route.entrance() + "↔" + route.exit()))
                        .withStyle(ChatFormatting.GREEN), true);
                return;
            }
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


    private static void message(ServerPlayer player, Feedback feedback, String detail) {
        player.displayClientMessage(Component.translatable(feedback.translationKey(), detail).withStyle(ChatFormatting.RED), true);
    }
    private static void publish(ServerPlayer player, Session s, Feedback feedback) {
        var layout = s == null ? null : s.layout;
        if (feedback == Feedback.READY && layout != null && layout.adjusted()) {
            feedback = Feedback.NATIVE_CROSSOVER_ADJUSTED;
        }
        List<TrackPlacementEndpoint> points = layout != null ? layout.points()
                : s == null ? List.of() : s.points.stream().map(SelectedPoint::endpoint).toList();
        PacketDistributor.sendToPlayer(player, new TurnoutConstructionPayload(player.serverLevel().dimension().location(),
                s == null ? new UUID(0, 0) : s.revision, s == null ? null : s.kind, points,
                s == null ? RailDefinition.STANDARD_GAUGE : s.gauge, layout == null ? List.of() : layout.curves(),
                layout != null && layout.geometry() != null, s != null && s.alternateReady,
                feedback, s == null ? Feedback.START : s.validation));
    }
    private static void clear(ServerPlayer player, Feedback feedback) {
        var removed = SESSIONS.remove(player.getUUID());
        if (removed != null) removed.cancelSolve();
        if (removed != null || feedback != Feedback.CANCELLED) publish(player, null, feedback);
    }
    private static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var s = SESSIONS.get(player.getUUID());
            if (s != null && !s.matches(player)) clear(player, Feedback.CLEARED);
            else if (s != null && (!RailPlacerItem.constructionSelection(player).equals(s.selection)
                    || s.pendingSolve != null && s.pendingSolve.isDone())) {
                publish(player, s, validate(player, s));
            }
        }
    }
    private static void discard(UUID id) {
        var removed = SESSIONS.remove(id); if (removed != null) removed.cancelSolve();
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) { discard(event.getEntity().getUUID()); }
    private static void clonePlayer(PlayerEvent.Clone event) { discard(event.getOriginal().getUUID()); }
    private static void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clear(player, Feedback.CLEARED);
    }
    private static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) SESSIONS.values().removeIf(s -> {
            if (s.level != level) return false; s.cancelSolve(); return true;
        });
    }
    private static void stop(ServerStoppedEvent event) { SESSIONS.values().forEach(Session::cancelSolve); SESSIONS.clear(); }

    private record SelectedPoint(TrackPlacementEndpoint endpoint, Set<UUID> external) {}

    private static final class Session {
        final ServerPlayer player;
        final ServerLevel level;
        final List<SelectedPoint> points = new ArrayList<>();
        final Map<TrackPlacementEndpoint, TrackCurveParameters> manual = new HashMap<>();
        RailCatalog.Selection selection = new RailCatalog.Selection(null, null);
        TrackConstructionType kind;
        List<TrackPlacementEndpoint> preferred = List.of();
        List<TurnoutPlacementSolver.Endpoint> input = List.of();
        double gauge = RailDefinition.STANDARD_GAUGE;
        UUID revision = UUID.randomUUID(), editorId;
        int editPoint;
        Feedback validation = Feedback.TURNOUT_POINTS;
        Feedback solveFeedback;
        TurnoutPlacementSolver.Layout layout, alternate;
        boolean alternateReady;
        CompletableFuture<TurnoutPlacementSolver.Result> pendingSolve;
        AtomicBoolean solveCancelled;
        Session(ServerPlayer player) {
            this.player = player;
            level = player.serverLevel();
        }
        void swapDiagonals() {
            var previous = layout;
            layout = alternate;
            alternate = previous.geometry() != null ? previous : null;
            preferred = layout.points();
            solveFeedback = Feedback.READY;
        }
        void cancelSolve() {
            if (solveCancelled != null) solveCancelled.set(true);
            if (pendingSolve != null) pendingSolve.cancel(false);
            pendingSolve = null;
            solveCancelled = null;
        }
        void invalidate() {
            cancelSolve();
            input = List.of();
            layout = null;
            alternate = null;
            alternateReady = false;
            solveFeedback = null;
            newRevision();
        }
        void newRevision() {
            revision = UUID.randomUUID();
            editorId = null;
        }
        ResourceLocation dimension() { return level.dimension().location(); }
        boolean matches(ServerPlayer p) {
            return p == player && p.isAlive() && p.hasPermissions(2) && level == p.serverLevel();
        }
    }
}
