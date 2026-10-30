/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.construction;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.network.payload.OpenTurnoutPayload;
import com.unrealtrainmod.network.payload.TurnoutActionPayload;
import com.unrealtrainmod.network.payload.TurnoutResultPayload;
import com.unrealtrainmod.network.payload.TurnoutResultPayload.Feedback;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.SwitchPosition;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.train.TrainManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Transient, server-thread-only drafts. Preview never stages volumes or changes the graph. */
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

    public static void interact(ServerPlayer player, UseOnContext context) {
        if (player.isShiftKeyDown()) { clear(player, Feedback.CANCELLED); return; }
        if (!player.isAlive() || !player.hasPermissions(2)) { message(player, Feedback.NO_PERMISSION, ""); return; }
        if (!player.getMainHandItem().is(UnrealTrainMod.TURNOUT_BUILDER.get())) return;
        if (context == null || context.getClickedFace() != Direction.UP
                || !player.canInteractWithBlock(context.getClickedPos(), 0)) {
            message(player, Feedback.INVALID_POINT, ""); return;
        }
        var hit = context.getClickLocation();
        double reach = player.blockInteractionRange();
        if (player.getEyePosition().distanceToSqr(hit) > reach * reach) {
            message(player, Feedback.INVALID_POINT, ""); return;
        }
        try {
            var blocks = TrackBlockManager.get(player.serverLevel());
            var graph = blocks.graph();
            var origin = TrackConstructionManager.snap(graph, new TrackVector(hit.x, hit.y + 0.3, hit.z));
            if (!TrackConstructionManager.validPosition(player.serverLevel(), origin.position())
                    || !TrackConstructionManager.canEditPoint(player, origin)) {
                message(player, Feedback.INVALID_POINT, ""); return;
            }
            double gauge = 1.435, yaw = normalize(player.getYRot());
            UUID external = null;
            if (origin.nodeId() != null) {
                var node = graph.node(origin.nodeId());
                var connections = graph.connections(node.id());
                if (graph.assemblyAtPort(node.id()) != null || connections.size() > 1) {
                    message(player, Feedback.INVALID_NODE, ""); return;
                }
                gauge = node.gauge();
                if (!connections.isEmpty()) {
                    external = connections.getFirst().segmentId();
                    var segment = graph.segment(external);
                    boolean start = segment.startNodeId().equals(node.id());
                    var tangent = segment.geometry().sample(start ? 0 : segment.geometry().getLength()).tangent();
                    if (Math.abs(tangent.y()) > 1e-6) { message(player, Feedback.NON_HORIZONTAL, ""); return; }
                    // Out of the existing line, into the new turnout's A -> B direction.
                    yaw = normalize(Math.toDegrees(Math.atan2(start ? tangent.x() : -tangent.x(),
                            start ? -tangent.z() : tangent.z())));
                }
            }
            clear(player, Feedback.CANCELLED);
            var session = new Session(player, origin, gauge, yaw, external);
            SESSIONS.put(player.getUUID(), session);
            Feedback feedback = validate(player, session);
            session.ready = feedback == Feedback.READY;
            PacketDistributor.sendToPlayer(player, new OpenTurnoutPayload(session.dimension(), session.id, origin,
                    gauge, session.definition, yaw, external != null, feedback, session.detail));
        } catch (IllegalStateException exception) {
            LOGGER.error("Cannot open turnout construction", exception);
            message(player, Feedback.STORAGE, "");
        }
    }

    public static void handle(ServerPlayer player, TurnoutActionPayload action) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null || !s.id.equals(action.draftId()) || !s.dimension().equals(action.dimension())) {
            reply(player, action, Feedback.STALE_DRAFT, "", true); return;
        }
        if (action.sequence() <= s.sequence) return;
        if (!s.matches(player)) { clear(player, Feedback.INVALID_ITEM); return; }
        if (action.action() == TurnoutActionPayload.Action.CANCEL) {
            s.sequence = action.sequence(); clear(player, Feedback.CANCELLED); return;
        }
        boolean approved = s.ready && s.definition == action.definition() && s.yaw == action.yaw();
        s.sequence = action.sequence();
        if (!Double.isFinite(action.yaw()) || action.yaw() < -180 || action.yaw() > 180
                || (s.external != null && s.yaw != action.yaw())) {
            s.ready = false; reply(player, action, Feedback.INVALID_PARAMETERS, "", false); return;
        }
        s.definition = action.definition(); s.yaw = action.yaw();
        Feedback feedback = validate(player, s);
        s.ready = feedback == Feedback.READY;
        if (action.action() == TurnoutActionPayload.Action.CONFIRM && s.ready) {
            if (!approved) { s.ready = false; reply(player, action, Feedback.STALE_DRAFT, "", false); return; }
            try {
                var value = TrackBlockManager.get(player.serverLevel()).graph()
                        .createTurnout(s.origin, s.gauge, s.definition, s.yaw);
                SESSIONS.remove(player.getUUID());
                reply(player, action, Feedback.CREATED, value.id().toString(), true);
                return;
            } catch (TrackGraphException exception) { feedback = failure(exception); }
            catch (IllegalArgumentException exception) { feedback = Feedback.INVALID_PARAMETERS; s.detail = exception.getMessage(); }
            catch (IllegalStateException exception) { feedback = Feedback.STORAGE; }
            s.ready = false;
        }
        boolean closed = feedback == Feedback.INVALID_NODE;
        if (closed) SESSIONS.remove(player.getUUID());
        reply(player, action, feedback, s.detail, closed);
    }

    private static Feedback validate(ServerPlayer player, Session s) {
        s.detail = "";
        try {
            var blocks = TrackBlockManager.get(player.serverLevel());
            var graph = blocks.graph();
            if (s.origin.nodeId() != null) {
                var node = graph.node(s.origin.nodeId());
                var connections = graph.connections(node.id());
                if (!node.position().equals(s.origin.position()) || node.gauge() != s.gauge
                        || graph.assemblyAtPort(node.id()) != null || connections.size() != (s.external == null ? 0 : 1)
                        || (s.external != null && !s.external.equals(connections.getFirst().segmentId()))) {
                    return Feedback.INVALID_NODE;
                }
            }
            if (!player.canInteractWithBlock(BlockPos.containing(s.origin.position().x(),
                    s.origin.position().y(), s.origin.position().z()), 0)
                    || !TrackConstructionManager.canEditPoint(player, s.origin)) return Feedback.INVALID_POINT;
            var prepared = graph.prepareTurnout(s.origin, s.gauge, s.definition, s.yaw);
            blocks.validatePlacement(prepared.segments());
            return Feedback.READY;
        } catch (TrackGraphException exception) { return failure(exception); }
        catch (IllegalArgumentException exception) { s.detail = exception.getMessage(); return Feedback.INVALID_PARAMETERS; }
        catch (IllegalStateException exception) {
            LOGGER.error("Cannot validate turnout draft {}", s.id, exception); return Feedback.STORAGE;
        }
    }

    private static Feedback failure(TrackGraphException exception) {
        return switch (exception.reason()) {
            case UNKNOWN_NODE, NODE_CONNECTED -> Feedback.INVALID_NODE;
            case BLOCKED_VOLUME -> Feedback.BLOCKED_VOLUME;
            case UNLOADED_VOLUME -> Feedback.UNLOADED_VOLUME;
            case VOLUME_TOO_LARGE -> Feedback.VOLUME_TOO_LARGE;
            case VEHICLE_PRESENT -> Feedback.VEHICLE_PRESENT;
            case INVALID_POSITION -> Feedback.INVALID_POINT;
            default -> Feedback.INVALID_PARAMETERS;
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
                    Component.translatable("commands.unrealtrainmod.turnout." + (next == SwitchPosition.NORMAL ? "straight" : "diverging")))
                    .withStyle(ChatFormatting.GREEN), true);
        } catch (IllegalStateException exception) { message(player, Feedback.STORAGE, ""); }
    }

    private static double normalize(double yaw) { return ((yaw % 360) + 540) % 360 - 180; }
    private static void message(ServerPlayer player, Feedback feedback, String detail) {
        player.displayClientMessage(Component.translatable(feedback.translationKey(), detail).withStyle(
                feedback == Feedback.CANCELLED ? ChatFormatting.GREEN : ChatFormatting.RED), true);
    }
    private static void reply(ServerPlayer player, TurnoutActionPayload action, Feedback feedback, String detail, boolean closed) {
        PacketDistributor.sendToPlayer(player, new TurnoutResultPayload(action.dimension(), action.draftId(),
                action.sequence(), feedback, detail, closed));
    }
    private static void clear(ServerPlayer player, Feedback feedback) {
        var s = SESSIONS.remove(player.getUUID());
        if (s != null) PacketDistributor.sendToPlayer(player, new TurnoutResultPayload(s.dimension(), s.id,
                s.sequence, feedback, "", true));
    }
    private static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var s = SESSIONS.get(player.getUUID());
            if (s != null && !s.matches(player)) clear(player, Feedback.INVALID_ITEM);
        }
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer) SESSIONS.remove(event.getEntity().getUUID());
    }
    private static void clonePlayer(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer) SESSIONS.remove(event.getOriginal().getUUID());
    }
    private static void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clear(player, Feedback.STALE_DRAFT);
    }
    private static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) SESSIONS.values().removeIf(s -> s.level == level);
    }
    private static void stop(ServerStoppedEvent event) { SESSIONS.clear(); }

    private static final class Session {
        final UUID id = UUID.randomUUID();
        final ServerPlayer player;
        final ServerLevel level;
        final int slot;
        final TrackPlacementEndpoint origin;
        final double gauge;
        final UUID external;
        TrackAssemblyDefinition definition = TrackAssemblyDefinition.LEFT;
        double yaw;
        long sequence;
        boolean ready;
        String detail = "";
        Session(ServerPlayer player, TrackPlacementEndpoint origin, double gauge, double yaw, UUID external) {
            this.player = player; this.level = player.serverLevel();
            this.slot = player.getInventory().selected;
            this.origin = origin; this.gauge = gauge; this.yaw = yaw; this.external = external;
        }
        ResourceLocation dimension() { return level.dimension().location(); }
        boolean matches(ServerPlayer p) {
            return p == player && p.isAlive() && p.hasPermissions(2) && level == p.serverLevel()
                    && slot == p.getInventory().selected && p.getMainHandItem().is(UnrealTrainMod.TURNOUT_BUILDER.get());
        }
    }
}
