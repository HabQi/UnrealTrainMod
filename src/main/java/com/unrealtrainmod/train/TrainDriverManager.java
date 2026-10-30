/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.payload.TrainControlInputPayload;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload.Feedback;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** One driver per consist. Owned and ticked by the level's TrainManager. */
public final class TrainDriverManager {
    private final ServerLevel level;
    private final TrainManager manager;
    private final Map<UUID, TrainDriverSession> sessions = new HashMap<>();

    TrainDriverManager(ServerLevel level, TrainManager manager) { this.level = level; this.manager = manager; }

    public boolean hasDriver(TrainConsist train) { return sessions.containsKey(train.id()); }

    public boolean isDriver(ServerPlayer player, TrainEntity entity) {
        return sessions.values().stream().anyMatch(session -> session.player == player
                && session.vehicle.equals(entity.getUUID()));
    }

    public boolean board(ServerPlayer player, TrainEntity entity) {
        if (!player.isAlive() || player.isSpectator() || player.isPassenger() || !entity.getPassengers().isEmpty()
                || sessions.values().stream().anyMatch(session -> session.player == player)) return false;
        try {
            var train = manager.prepareDriving(entity);
            if (!player.startRiding(entity)) return false;
            boolean emergency = train.control().emergencyBrake();
            int handle = train.running() || emergency ? 0 : -TrainControlProfile.BRAKE_NOTCHES;
            var session = new TrainDriverSession(player, train, entity.getUUID(), handle);
            train.startManual(TrainControlProfile.control(handle, train.control().reverser(), emergency));
            sessions.put(train.id(), session);
            send(session, true, Feedback.NONE);
            return true;
        } catch (TrainManager.StateException exception) {
            player.displayClientMessage(Component.translatable("commands.unrealtrainmod.train.state."
                    + exception.reason().name().toLowerCase(Locale.ROOT)), true);
            return false;
        }
    }

    public static void handleInput(TrainControlInputPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TrainManager.get(player.serverLevel()).drivers().input(player, payload);
        }
    }

    private boolean valid(TrainDriverSession session) {
        var player = session.player;
        return player.serverLevel() == level && player.isAlive() && !player.isSpectator() && !player.hasDisconnected()
                && player.getVehicle() instanceof TrainEntity entity && entity.getUUID().equals(session.vehicle)
                && manager.knownTrain(session.vehicle) == session.train && manager.drivingReady(session.train);
    }

    private void input(ServerPlayer player, TrainControlInputPayload payload) {
        var session = sessions.values().stream().filter(value -> value.player == player && value.id.equals(payload.session()))
                .findFirst().orElse(null);
        if (session == null || !valid(session) || payload.sequence() <= session.sequence
                || !TrainControlProfile.validHandle(payload.handle())) return;
        session.sequence = payload.sequence();
        session.silentTicks = 0;
        var train = session.train;
        var old = train.control();
        Feedback feedback = Feedback.NONE;
        if (payload.emergency() && !old.emergencyBrake()) {
            // A pending/rejected reverser intention must never prevent the emergency key from working.
            session.handle = 0;
            train.setControl(TrainControlProfile.control(0, old.reverser(), true));
        } else if (payload.reverser() != old.reverser() && (train.running() || session.handle != 0 || payload.handle() != 0)) {
            feedback = Feedback.REVERSER_LOCKED;
        } else if (old.emergencyBrake() && (payload.handle() != 0 || !payload.emergency() && train.running())) {
            feedback = Feedback.EMERGENCY_LOCKED;
        } else {
            session.handle = payload.emergency() ? 0 : payload.handle();
            train.setControl(TrainControlProfile.control(session.handle, payload.reverser(), payload.emergency()));
        }
        send(session, true, feedback);
    }

    /** Counts only normal simulation ticks, independent of paused/frozen server time. */
    void tick() {
        for (var session : List.copyOf(sessions.values())) {
            if (!valid(session) || ++session.silentTicks >= 60) {
                session.train.safetyBrake();
                close(session.train);
            }
        }
    }

    void publish() {
        if (level.getGameTime() % 2 == 0) {
            for (var session : sessions.values()) send(session, true, Feedback.NONE);
        }
    }

    public void dismounted(ServerPlayer player, TrainEntity entity) {
        var session = sessions.values().stream().filter(value -> value.player == player && value.vehicle.equals(entity.getUUID()))
                .findFirst().orElse(null);
        if (session != null) {
            session.train.safetyBrake();
            close(session.train);
        }
    }

    void close(TrainConsist train) {
        var session = sessions.remove(train.id());
        if (session == null) return;
        if (!session.player.hasDisconnected()) {
            send(session, false, Feedback.NONE);
            if (train.stopReason() != TrainConsist.StopReason.STOPPED) {
                session.player.displayClientMessage(Component.translatable("commands.unrealtrainmod.train.state."
                        + train.stopReason().name().toLowerCase(Locale.ROOT)), true);
            }
        }
        if (session.player.getVehicle() instanceof TrainEntity entity && entity.getUUID().equals(session.vehicle)) {
            session.player.stopRiding();
        }
    }

    void closeAll() { for (var session : List.copyOf(sessions.values())) close(session.train); }

    private void send(TrainDriverSession session, boolean open, Feedback feedback) {
        var train = session.train;
        PacketDistributor.sendToPlayer(session.player, new TrainControlSyncPayload(session.id, level.dimension().location(),
                train.id(), session.vehicle, open, session.sequence, session.handle, train.control().reverser(),
                train.control().emergencyBrake(), train.actualSpeed(), train.dynamics().acceleration(), train.dynamics().grade(),
                train.dynamics().tractionForce(), train.dynamics().brakeForce(), train.dynamics().tractionPower(),
                train.stopReason(), feedback));
    }
}
