/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.payload.TrainPassengerPosePayload;
import com.unrealtrainmod.network.payload.TrainPassengerStatePayload;
import com.unrealtrainmod.network.payload.TrainWalkInputPayload;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleInteriorSpec;
import com.unrealtrainmod.vehicle.VehiclePose;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Vehicle-local walking sessions; driver entries never touch manual controls or driver limits. */
public final class TrainPassengerManager {
    public static final int MAX_PASSENGERS = 32;

    private final ServerLevel level;
    private final TrainManager manager;
    private final Map<UUID, TrainPassengerSession> sessions = new LinkedHashMap<>();

    TrainPassengerManager(ServerLevel level, TrainManager manager) {
        this.level = level;
        this.manager = manager;
    }

    public boolean isPassenger(ServerPlayer player, TrainEntity entity) {
        var session = sessions.get(player.getUUID());
        return session != null && session.role == TrainWalkRole.PASSENGER && session.player == player
                && session.vehicle.equals(entity.getUUID());
    }

    public boolean hasPassenger(TrainConsist train) {
        return sessions.values().stream().anyMatch(session -> session.role == TrainWalkRole.PASSENGER
                && train.member(session.vehicle) != null);
    }

    /** Server-side lookup used by {@link TrainEntity#getPassengerRidingPosition(Entity)}. */
    public TrainPassengerClientState.LocalPoint localPoint(Entity player, TrainEntity entity) {
        var session = sessions.get(player.getUUID());
        return session == null || !session.vehicle.equals(entity.getUUID())
                ? null : new TrainPassengerClientState.LocalPoint(session.localX, session.floorY, session.localZ);
    }

    /** Capture before startRiding can place the player at a default riding anchor. */
    static TrackVector boardingPoint(ServerPlayer player, TrainEntity entity) {
        return VehiclePose.worldToLocal(body(entity), new TrackVector(player.getX(), player.getY(), player.getZ()));
    }

    public boolean board(ServerPlayer player, TrainEntity entity) {
        if (!player.isAlive() || player.isSpectator() || player.isShiftKeyDown() || player.isPassenger()
                || entity.isRemoved()) return false;
        if (!player.getMainHandItem().is(UnrealTrainMod.TICKET.get())) {
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.ticket.required"), true);
            return false;
        }
        if (sessionsFor(entity).size() >= MAX_PASSENGERS) {
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.ticket.passenger_limit",
                    MAX_PASSENGERS), true);
            return false;
        }
        try {
            var train = manager.info(entity);
            if (!manager.drivingReady(train)) {
                player.displayClientMessage(Component.translatable("item.unrealtrainmod.ticket.not_ready"), true);
                return false;
            }
        } catch (TrainManager.StateException exception) {
            player.displayClientMessage(Component.translatable("commands.unrealtrainmod.train.state."
                    + exception.reason().name().toLowerCase(Locale.ROOT)), true);
            return false;
        }
        return board(player, entity, boardingPoint(player, entity), TrainWalkRole.PASSENGER);
    }

    /** Both roles use the ticket boarding sequence; controls and role limits belong to their callers. */
    boolean board(ServerPlayer player, TrainEntity entity, TrackVector local, TrainWalkRole role) {
        var existing = sessions.get(player.getUUID());
        if (existing != null) {
            if (!existing.exiting) return false;
            remove(existing);
        }
        var spec = enabledSpec(entity);
        if (spec == null) {
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.ticket.not_boardable"), true);
            return false;
        }
        var session = new TrainPassengerSession(player, UUID.randomUUID(), entity.getUUID(),
                spec.clampX(local.x()), spec.centerY(), spec.clampZ(local.z()), role);
        sessions.put(player.getUUID(), session);
        if (!player.startRiding(entity)) {
            sessions.remove(player.getUUID());
            return false;
        }
        entity.positionRider(player);
        send(session, player, true);
        return true;
    }

    public static void handleInput(TrainWalkInputPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TrainManager.get(player.serverLevel()).passengers().input(player, payload);
        }
    }

    public static void handlePose(TrainPassengerPosePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TrainManager.get(player.serverLevel()).passengers().pose(player, payload);
        }
    }

    /** Posture is session state; a height change only ever applies one bounded step while lying. */
    private void pose(ServerPlayer player, TrainPassengerPosePayload payload) {
        if (payload.heightStepDelta() < -1 || payload.heightStepDelta() > 1) return;
        var session = sessions.get(player.getUUID());
        if (session == null || session.player != player || !session.session.equals(payload.session())
                || !valid(session)) return;
        if (payload.heightStepDelta() == 0) {
            session.pose = payload.pose();
            if (session.pose == PassengerPose.LYING) {
                session.heightStep = Math.max(session.heightStep, PassengerPose.LYING_MIN_HEIGHT_STEP);
            }
        } else if (session.pose == PassengerPose.LYING && payload.pose() == PassengerPose.LYING) {
            session.heightStep = Mth.clamp(session.heightStep + payload.heightStepDelta(),
                    PassengerPose.LYING_MIN_HEIGHT_STEP, PassengerPose.MAX_HEIGHT_STEP);
        }
    }

    private void input(ServerPlayer player, TrainWalkInputPayload payload) {
        var session = sessions.get(player.getUUID());
        if (session == null || session.player != player || !session.session.equals(payload.session())
                || !valid(session) || payload.sequence() != session.acceptedSequence + 1
                || session.inputs.size() >= TrainWalkInputPayload.MAX_PENDING_INPUTS) return;
        float x = payload.localX(), z = payload.localZ();
        if (!Float.isFinite(x) || !Float.isFinite(z)) return;
        session.acceptedSequence = payload.sequence();
        // TrainWalkStep applies the same direction normalization as client prediction.
        session.inputs.addLast(payload);
    }

    /** Applies one bounded walking step per normal server tick, after the final committed vehicle pose. */
    void tick() {
        if (sessions.isEmpty()) return;
        var failed = new ArrayList<TrainPassengerSession>();
        for (var session : List.copyOf(sessions.values())) {
            if (session.exiting || !valid(session)) {
                failed.add(session);
                continue;
            }
            var entity = (TrainEntity) session.player.getVehicle();
            var input = session.inputs.pollFirst();
            if (input != null) {
                var spec = enabledSpec(entity);
                var point = TrainWalkStep.move(spec, session.localX, session.localZ,
                        input.localX(), input.localZ());
                session.localX = point.x();
                session.localZ = point.z();
                session.appliedSequence = input.sequence();
            }
            entity.positionRider(session.player);
        }
        for (var session : failed) close(session);
    }

    void publish() {
        if (sessions.isEmpty()) return;
        boolean observers = level.getGameTime() % 2 == 0;
        for (var session : List.copyOf(sessions.values())) {
            if (session.exiting || !valid(session)) continue;
            var entity = (TrainEntity) session.player.getVehicle();
            var payload = payload(session, true);
            PacketDistributor.sendToPlayer(session.player, payload);
            if (observers) {
                PacketDistributor.sendToPlayersTrackingEntity(entity, payload);
                PacketDistributor.sendToPlayersTrackingEntity(session.player, payload);
            }
        }
    }

    /** Sends the complete state for late observers when the entity or player enters tracking range. */
    void startTracking(ServerPlayer tracker, Entity target) {
        if (target instanceof TrainEntity entity) {
            for (var session : allSessionsFor(entity)) send(session, tracker, true);
        } else if (target instanceof ServerPlayer player) {
            var session = sessions.get(player.getUUID());
            if (session != null && session.player == player) send(session, tracker, true);
        }
    }

    private List<TrainPassengerSession> allSessionsFor(TrainEntity entity) {
        return sessions.values().stream().filter(session -> !session.exiting
                && session.vehicle.equals(entity.getUUID())).toList();
    }

    public void dismounted(ServerPlayer player, TrainEntity entity) {
        var session = sessions.get(player.getUUID());
        if (session == null || session.player != player
                || !session.vehicle.equals(entity.getUUID())) return;
        // LivingEntity.stopRiding removes the passenger before asking for the dismount position.
        // Keep the personal local point available for that call; the next manager tick releases it.
        session.exiting = true;
    }

    void vehicleRemoved(TrainEntity entity) {
        for (var session : List.copyOf(sessions.values())) {
            if (session.vehicle.equals(entity.getUUID())) close(session);
        }
    }

    void playerReplaced(UUID player) {
        var session = sessions.remove(player);
        if (session == null) return;
        var payload = payload(session, false);
        if (!session.player.hasDisconnected()) PacketDistributor.sendToPlayer(session.player, payload);
        if (!session.player.isRemoved()) PacketDistributor.sendToPlayersTrackingEntity(session.player, payload);
    }

    void closeAll() {
        for (var session : List.copyOf(sessions.values())) close(session);
    }

    private void close(TrainPassengerSession session) {
        remove(session);
        if (session.player.getVehicle() instanceof TrainEntity entity && entity.getUUID().equals(session.vehicle)) {
            session.player.stopRiding();
        }
    }

    private void remove(TrainPassengerSession session) {
        sessions.remove(session.player.getUUID());
        var payload = payload(session, false);
        if (!session.player.hasDisconnected()) PacketDistributor.sendToPlayer(session.player, payload);
        if (!session.player.isRemoved()) PacketDistributor.sendToPlayersTrackingEntity(session.player, payload);
        if (level.getEntity(session.vehicle) instanceof TrainEntity entity && !entity.isRemoved()) {
            PacketDistributor.sendToPlayersTrackingEntity(entity, payload);
        }
    }

    private void send(TrainPassengerSession session, ServerPlayer recipient, boolean present) {
        if (recipient.hasDisconnected()) return;
        PacketDistributor.sendToPlayer(recipient, payload(session, present));
    }

    private TrainPassengerStatePayload payload(TrainPassengerSession session, boolean present) {
        return new TrainPassengerStatePayload(session.player.getUUID(), session.vehicle, level.dimension().location(),
                session.session, present, session.appliedSequence, level.getGameTime(),
                session.localX, session.floorY, session.localZ, session.pose, session.heightStep, session.role);
    }

    private List<TrainPassengerSession> sessionsFor(TrainEntity entity) {
        return sessions.values().stream()
                .filter(session -> session.role == TrainWalkRole.PASSENGER && !session.exiting
                        && session.vehicle.equals(entity.getUUID())).toList();
    }

    private boolean valid(TrainPassengerSession session) {
        var player = session.player;
        return player.serverLevel() == level && player.isAlive() && !player.isSpectator() && !player.hasDisconnected()
                && !player.isRemoved() && !player.isChangingDimension()
                && player.getVehicle() instanceof TrainEntity entity && entity.getUUID().equals(session.vehicle)
                && !entity.isRemoved() && enabledSpec(entity) != null
                && (session.role != TrainWalkRole.DRIVER || manager.drivers().isDriver(player, entity));
    }

    private static VehicleInteriorSpec enabledSpec(TrainEntity entity) {
        var model = VehicleCatalog.find(entity.getModelId());
        var spec = model == null ? null : model.interiorSpec();
        return spec != null && spec.enabled() ? spec : null;
    }

    private static VehiclePose.Body body(TrainEntity entity) {
        var pose = entity.getRailPose();
        return pose != null ? pose.body() : new VehiclePose.Body(
                new TrackVector(entity.getX(), entity.getY(), entity.getZ()), entity.getYRot(), entity.getXRot());
    }
}
