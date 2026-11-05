/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.payload.TrainControlInputPayload;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload.Feedback;
import com.unrealtrainmod.vehicle.VehicleDoor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Up to three independent driver sessions share each consist's authoritative controls. */
public final class TrainDriverManager {
    public static final int MAX_DRIVERS = 3;
    private final ServerLevel level;
    private final TrainManager manager;
    // Player keys provide direct input lookup; insertion order records boarding priority within each train.
    private final Map<UUID, TrainDriverSession> sessions = new LinkedHashMap<>();

    TrainDriverManager(ServerLevel level, TrainManager manager) { this.level = level; this.manager = manager; }

    private List<TrainDriverSession> sessionsFor(TrainConsist train) {
        return sessions.values().stream().filter(session -> session.train == train).toList();
    }

    public boolean hasDriver(TrainConsist train) {
        return sessions.values().stream().anyMatch(session -> session.train == train);
    }

    public boolean isDriver(ServerPlayer player, TrainEntity entity) {
        var session = sessions.get(player.getUUID());
        return session != null && session.player == player && session.vehicle.equals(entity.getUUID());
    }

    public boolean board(ServerPlayer player, TrainEntity entity) {
        if (!player.isAlive() || player.isSpectator() || player.isShiftKeyDown() || player.isPassenger()
                || sessions.containsKey(player.getUUID())) return false;
        if (!player.getMainHandItem().is(UnrealTrainMod.KEY.get())) {
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.key.required"), true);
            return false;
        }
        try {
            var train = manager.prepareDriving(entity);
            var aboard = sessionsFor(train);
            if (aboard.size() >= MAX_DRIVERS) {
                player.displayClientMessage(Component.translatable("item.unrealtrainmod.key.driver_limit", MAX_DRIVERS), true);
                return false;
            }
            if (!player.startRiding(entity)) return false;
            if (aboard.isEmpty()) {
                boolean emergency = train.control().emergencyBrake();
                var control = train.commandControlled() ? TrainControlProfile.control(
                        0, train.running() || emergency ? 0 : TrainControlProfile.BRAKE_NOTCHES,
                        train.control().reverser(), emergency) : train.control();
                train.startManual(control);
            }
            var session = new TrainDriverSession(player, train, entity.getUUID());
            sessions.put(player.getUUID(), session);
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
                && !player.isRemoved() && !player.isChangingDimension()
                && player.getVehicle() instanceof TrainEntity entity && entity.getUUID().equals(session.vehicle)
                && manager.knownTrain(session.vehicle) == session.train && manager.drivingReady(session.train);
    }

    private void input(ServerPlayer player, TrainControlInputPayload payload) {
        var session = sessions.get(player.getUUID());
        if (session == null || session.player != player || !session.id.equals(payload.session()) || !valid(session)
                || payload.sequence() <= session.sequence) return;
        session.sequence = payload.sequence();
        session.silentTicks = 0;
        var action = payload.action();
        if (action == TrainControlInputPayload.Action.HEARTBEAT) return;
        if (action == TrainControlInputPayload.Action.POWER_SET
                && (payload.notch() < 0 || payload.notch() > TrainControlProfile.POWER_NOTCHES)) return;
        if (action == TrainControlInputPayload.Action.BRAKE_SET
                && (payload.notch() < 0 || payload.notch() > TrainControlProfile.BRAKE_NOTCHES)) return;
        var train = session.train;
        var old = train.control();
        int power = TrainControlProfile.powerNotch(old), brake = TrainControlProfile.brakeNotch(old);
        var reverser = old.reverser();
        boolean emergency = old.emergencyBrake();
        boolean controlChange = true;
        Feedback feedback = Feedback.NONE;
        switch (action) {
            case HANDLE_UP, HANDLE_DOWN -> {
                if (emergency) feedback = Feedback.EMERGENCY_LOCKED;
                else if (action == TrainControlInputPayload.Action.HANDLE_UP) {
                    if (brake > 0) brake = 0;
                    else power = Math.min(TrainControlProfile.POWER_NOTCHES, power + 1);
                } else {
                    if (power > 0) power = 0;
                    else brake = Math.min(TrainControlProfile.BRAKE_NOTCHES, brake + 1);
                }
            }
            case POWER_SET, BRAKE_SET, POWER_UP, POWER_DOWN, BRAKE_UP, BRAKE_DOWN -> {
                if (emergency) feedback = Feedback.EMERGENCY_LOCKED;
                else if (action == TrainControlInputPayload.Action.POWER_SET) power = payload.notch();
                else if (action == TrainControlInputPayload.Action.BRAKE_SET) brake = payload.notch();
                else if (action == TrainControlInputPayload.Action.POWER_UP) power = Math.min(TrainControlProfile.POWER_NOTCHES, power + 1);
                else if (action == TrainControlInputPayload.Action.POWER_DOWN) power = Math.max(0, power - 1);
                else if (action == TrainControlInputPayload.Action.BRAKE_UP) brake = Math.min(TrainControlProfile.BRAKE_NOTCHES, brake + 1);
                else brake = Math.max(0, brake - 1);
                // A positive request selects that handle; releasing to zero never restores the other one.
                if (!emergency) {
                    if ((action == TrainControlInputPayload.Action.POWER_SET && power > 0)
                            || action == TrainControlInputPayload.Action.POWER_UP) brake = 0;
                    if ((action == TrainControlInputPayload.Action.BRAKE_SET && brake > 0)
                            || action == TrainControlInputPayload.Action.BRAKE_UP) power = 0;
                }
            }
            case REVERSER_NEXT -> {
                if (train.running() || power != 0 || brake != 0) feedback = Feedback.REVERSER_LOCKED;
                else {
                    int direction = train.member(session.vehicle).direction();
                    reverser = reverser.inFrame(direction).next().inFrame(direction);
                }
            }
            case EMERGENCY_APPLY -> { emergency = true; power = brake = 0; }
            case EMERGENCY_RELEASE -> {
                if (emergency && train.running()) feedback = Feedback.EMERGENCY_LOCKED;
                else if (emergency) { emergency = false; power = brake = 0; }
            }
            // Only the car the driver rides; the administrator door command's rules apply unchanged.
            case DOOR_LEFT_OPEN, DOOR_LEFT_CLOSE, DOOR_RIGHT_OPEN, DOOR_RIGHT_CLOSE -> {
                controlChange = false;
                boolean left = action == TrainControlInputPayload.Action.DOOR_LEFT_OPEN
                        || action == TrainControlInputPayload.Action.DOOR_LEFT_CLOSE;
                boolean open = action == TrainControlInputPayload.Action.DOOR_LEFT_OPEN
                        || action == TrainControlInputPayload.Action.DOOR_RIGHT_OPEN;
                ((TrainEntity) session.player.getVehicle()).setDoorOpen(left ? VehicleDoor.LEFT : VehicleDoor.RIGHT, open);
            }
            case HEARTBEAT -> { }
        }
        if (controlChange && feedback == Feedback.NONE) train.setControl(TrainControlProfile.control(power, brake, reverser, emergency));
        for (var driver : sessionsFor(train)) send(driver, true, driver == session ? feedback : Feedback.NONE);
    }

    /** Counts only normal simulation ticks, independent of paused/frozen server time. */
    void tick() {
        var failed = new ArrayList<TrainDriverSession>();
        for (var session : sessions.values()) {
            if (!valid(session) || ++session.silentTicks >= 60) failed.add(session);
        }
        removeFailedSessions(failed);
    }

    private void removeFailedSessions(List<TrainDriverSession> failed) {
        var affected = new LinkedHashSet<TrainConsist>();
        // Remove the entire failed batch before dismount callbacks or remaining-driver checks.
        for (var session : failed) {
            sessions.remove(session.player.getUUID());
            affected.add(session.train);
        }
        for (var session : failed) finish(session);
        for (var train : affected) if (!hasDriver(train)) train.safetyBrake();
    }

    void publish() {
        if (level.getGameTime() % 2 == 0) {
            for (var session : sessions.values()) send(session, true, Feedback.NONE);
        }
    }

    public void dismounted(ServerPlayer player, TrainEntity entity) {
        var session = sessions.get(player.getUUID());
        if (session == null || session.player != player || !session.vehicle.equals(entity.getUUID())) return;
        // Player.rideTick clears the sneak flag only after stopRiding has removed the passenger.
        boolean manualExit = player.isShiftKeyDown() && player.isAlive() && !player.isSpectator()
                && !player.hasDisconnected() && !player.isRemoved() && !player.isChangingDimension()
                && player.serverLevel() == level && !entity.isRemoved();
        if (manualExit) close(session);
        else removeFailedSessions(sessionsFor(session.train).stream()
                .filter(value -> value == session || !valid(value) || value.silentTicks >= 60).toList());
    }

    void stoppedAtEnd(TrainConsist train) {
        for (var session : sessionsFor(train)) {
            send(session, true, Feedback.NONE);
            session.player.displayClientMessage(Component.translatable("commands.unrealtrainmod.train.state.end_of_route"), true);
        }
    }

    /** The reference train's drivers take priority; overflow leaves only after the merge commits. */
    void coupled(TrainConsist first, TrainConsist second, TrainConsist combined) {
        var ordered = new ArrayList<>(sessionsFor(first));
        ordered.addAll(sessionsFor(second));
        var source = ordered.isEmpty() ? first : ordered.getFirst().train;
        var vehicle = ordered.isEmpty() ? first.cars().getFirst().entityId() : ordered.getFirst().vehicle;
        var reverser = source.control().reverser().inFrame(source.member(vehicle).direction())
                .inFrame(combined.member(vehicle).direction());
        boolean emergency = first.control().emergencyBrake() || second.control().emergencyBrake();
        var control = TrainControlProfile.control(0, emergency ? 0 : TrainControlProfile.BRAKE_NOTCHES, reverser, emergency);
        if (ordered.isEmpty()) {
            combined.setControl(control);
            return;
        }
        combined.startManual(control);
        for (int i = 0; i < ordered.size(); i++) {
            var session = ordered.get(i);
            if (i >= MAX_DRIVERS) {
                close(session);
                session.player.displayClientMessage(Component.translatable("item.unrealtrainmod.key.coupling_overflow", MAX_DRIVERS), true);
            } else {
                session.train = combined;
                send(session, true, Feedback.NONE);
                session.player.displayClientMessage(Component.translatable("item.unrealtrainmod.crowbar.coupled"), true);
            }
        }
    }

    /** Each new component gets one control state, and every driver stays with their original car. */
    void split(TrainConsist original, List<TrainConsist> parts) {
        var aboard = sessionsFor(original);
        var control = original.control();
        for (var component : parts) {
            var drivers = aboard.stream().filter(session -> component.member(session.vehicle) != null).toList();
            var vehicle = drivers.isEmpty() ? component.cars().getFirst().entityId() : drivers.getFirst().vehicle;
            var reverser = control.reverser().inFrame(original.member(vehicle).direction())
                    .inFrame(component.member(vehicle).direction());
            var parked = TrainControlProfile.control(0, control.emergencyBrake() ? 0 : TrainControlProfile.BRAKE_NOTCHES,
                    reverser, control.emergencyBrake());
            if (drivers.isEmpty()) component.setControl(parked);
            else component.startManual(parked);
            for (var session : drivers) {
                session.train = component;
                send(session, true, Feedback.NONE);
            }
        }
    }

    void close(TrainConsist train) { for (var session : sessionsFor(train)) close(session); }

    private void close(TrainDriverSession session) {
        sessions.remove(session.player.getUUID());
        finish(session);
    }

    private void finish(TrainDriverSession session) {
        var train = session.train;
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

    void closeAll() { for (var session : List.copyOf(sessions.values())) close(session); }

    private void send(TrainDriverSession session, boolean open, Feedback feedback) {
        var train = session.train;
        int direction = train.member(session.vehicle).direction();
        PacketDistributor.sendToPlayer(session.player, new TrainControlSyncPayload(session.id, level.dimension().location(),
                train.id(), session.vehicle, open, session.sequence, TrainControlProfile.powerNotch(train.control()),
                TrainControlProfile.brakeNotch(train.control()),
                train.control().reverser().inFrame(direction), train.control().emergencyBrake(),
                direction * train.actualSpeed(), direction * train.dynamics().acceleration(), direction * train.dynamics().grade(),
                direction * train.dynamics().tractionForce(), train.dynamics().brakeForce(),
                train.stopReason(), feedback));
    }
}
