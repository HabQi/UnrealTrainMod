/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.compat.ir.IrControlDefinition;
import com.unrealtrainmod.compat.ir.IrInteractionRules;
import com.unrealtrainmod.compat.ir.IrReadout;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload.Feedback;
import com.unrealtrainmod.network.payload.TrainIrControlInputPayload;
import com.unrealtrainmod.network.payload.TrainIrControlInputPayload.Operation;
import com.unrealtrainmod.network.payload.TrainIrInteractionPayload;
import com.unrealtrainmod.network.payload.TrainIrInteractionResultPayload;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** One held control per player, shared by model controls, the panel and native HUD handles. */
public final class IrControlManager {
    private record Target(TrainEntity entity, String group, IrControlDefinition.Type type, int front,
                          UUID interaction, String component) {}
    private record Held(Target target, IrReadout driver, Entity mount, ResourceLocation model) {}
    private static final class Entry {
        final ServerPlayer player;
        long sequence;
        int silent;
        Held held;
        Entry(ServerPlayer player) { this.player = player; }
    }
    private final ServerLevel level;
    private final TrainManager manager;
    private final Map<UUID, Entry> entries = new HashMap<>();

    IrControlManager(ServerLevel level, TrainManager manager) { this.level = level; this.manager = manager; }

    public static void handle(TrainIrInteractionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            TrainManager.get(player.serverLevel()).irControls().modelInput(player, payload);
        }
    }

    private Entry entry(ServerPlayer player) {
        return entries.computeIfAbsent(player.getUUID(), ignored -> new Entry(player));
    }

    private boolean validPlayer(ServerPlayer player) {
        return player.serverLevel() == level && player.isAlive() && !player.isSpectator()
                && !player.hasDisconnected() && !player.isRemoved() && !player.isChangingDimension();
    }

    private boolean permitted(ServerPlayer player, TrainEntity entity, IrReadout driver) {
        if (!validPlayer(player) || entity.isRemoved() || entity.level() != level
                || !IrInteractionRules.inReach(player.getEyePosition(), entity.getBoundingBox())) return false;
        boolean driving = manager.drivers().authorized(player, entity);
        return driver != null ? driving : driving || IrInteractionRules.holdingKey(player);
    }

    private void modelInput(ServerPlayer player, TrainIrInteractionPayload payload) {
        var entry = entry(player);
        if (payload.sequence() <= entry.sequence) return;
        entry.sequence = payload.sequence();
        Feedback feedback = Feedback.NONE;
        try {
            // Release ownership before checking the key or target's continued availability.
            if (payload.operation() == Operation.END && matchesModel(entry.held, payload)) {
                var held = entry.held;
                try {
                    if (held.target().entity().getModelId().equals(held.model())
                            && permitted(player, held.target().entity(), held.driver())) {
                        feedback = apply(entry, held.target(), payload.value(), false, Operation.END);
                    }
                } finally { release(entry); }
            } else {
                if (!(level.getEntity(payload.vehicle()) instanceof TrainEntity entity)) throw new IllegalArgumentException("Missing vehicle");
                var vehicle = VehicleCatalog.find(entity.getModelId());
                if (vehicle == null) throw new IllegalArgumentException("Missing model");
                var modelControl = vehicle.ir().modelControls().get(payload.component());
                if (modelControl == null || !modelControl.interactive()) throw new IllegalArgumentException("Not an interactive component");
                var control = vehicle.ir().controls().get(modelControl.group());
                if (!permitted(player, entity, control.driver())) throw new IllegalArgumentException("No interaction permission");
                if (payload.operation() == Operation.HEARTBEAT) {
                    if (!matchesModel(entry.held, payload)) throw new IllegalArgumentException("Control is not held");
                    entry.silent = 0;
                } else {
                    var target = new Target(entity, modelControl.group(), modelControl.type(), 1, payload.interaction(), payload.component());
                    feedback = apply(entry, target, payload.value(), false, payload.operation());
                }
            }
        } catch (IllegalArgumentException exception) {
            release(entry);
            feedback = Feedback.IR_CONTROL_UNAVAILABLE;
        }
        reply(player, payload.vehicle(), payload.interaction(), payload.sequence(), feedback);
    }

    private static boolean matchesModel(Held held, TrainIrInteractionPayload payload) {
        return held != null && payload.vehicle().equals(held.target().entity().getUUID())
                && payload.interaction().equals(held.target().interaction()) && payload.component().equals(held.target().component());
    }

    Feedback panelInput(ServerPlayer player, TrainEntity entity, TrainIrControlInputPayload payload, int front) {
        var entry = entry(player);
        try {
            var control = VehicleCatalog.find(entity.getModelId()).ir().controls().get(payload.group());
            if (control == null || control.readOnly() || payload.operation() == Operation.HEARTBEAT) {
                throw new IllegalArgumentException("Not a panel control");
            }
            return apply(entry, new Target(entity, payload.group(), control.type(), front, null, null),
                    payload.value(), payload.pulse(), payload.operation());
        } catch (IllegalArgumentException exception) {
            release(entry);
            return Feedback.IR_CONTROL_UNAVAILABLE;
        }
    }

    private static boolean matches(Held held, Target target) {
        return held != null && held.target().entity() == target.entity()
                && Objects.equals(held.target().group(), target.group())
                && Objects.equals(held.target().interaction(), target.interaction())
                && Objects.equals(held.target().component(), target.component());
    }

    private Feedback apply(Entry entry, Target target, float value, boolean pulse, Operation operation) {
        if (!Float.isFinite(value) || value < 0 || value > 1
                || target.type() != IrControlDefinition.Type.CONTINUOUS && value != 0 && value != 1
                || pulse && (value != 1 || operation != Operation.CLICK)) throw new IllegalArgumentException("Invalid control value");
        if (operation == Operation.HEARTBEAT) throw new IllegalArgumentException("Unexpected heartbeat");
        if ((operation == Operation.UPDATE || operation == Operation.END) && !matches(entry.held, target)) {
            throw new IllegalArgumentException("Control is not held");
        }
        if (operation == Operation.BEGIN || operation == Operation.CLICK) release(entry);
        var entity = target.entity();
        var definition = VehicleCatalog.find(entity.getModelId()).ir();
        var control = definition.controls().get(target.group());
        var snapshot = entity.irSnapshot();
        float previous = definition.value(target.group(), snapshot.values(), entity.runtimeState(), snapshot.frontCoupled(), snapshot.rearCoupled());
        boolean press = target.type() == IrControlDefinition.Type.PRESS && !control.readOnly();
        Feedback feedback = Feedback.NONE;
        if (operation == Operation.END && press) {
            release(entry);
            return feedback;
        }
        if (control.readOnly()) {
            if (pulse) throw new IllegalArgumentException("Driving controls cannot pulse");
            feedback = manager.drivers().irDrivingTarget(entry.player, entity, control.driver(), value);
        } else if (operation == Operation.BEGIN && press) {
            if (value != 1) throw new IllegalArgumentException("Held press must be on");
            entity.holdIrControl(entry.player.getUUID(), target.group());
        } else entity.setIrControl(target.group(), value, pulse);
        if (feedback != Feedback.NONE) { release(entry); return feedback; }
        entry.silent = 0;
        if (operation == Operation.BEGIN) {
            entry.held = new Held(target, control.driver(), entry.player.getVehicle(), entity.getModelId());
            if (target.component() != null) entity.beginIrControlSound(entry.player.getUUID(), target.component());
            else entity.beginIrControlSound(entry.player.getUUID(), target.group(), null, target.front());
        } else if (operation == Operation.CLICK) {
            if (target.component() != null) entity.clickIrControlSound(target.component(), previous, value);
            else entity.clickIrControlSound(target.group(), null, target.front(), previous, value);
        } else if (operation == Operation.END) release(entry);
        return feedback;
    }

    void beginNative(ServerPlayer player, TrainEntity entity, IrReadout driver, int front) {
        var entry = entry(player);
        release(entry);
        var target = new Target(entity, null, IrControlDefinition.Type.CONTINUOUS, front, null, null);
        entry.held = new Held(target, driver, player.getVehicle(), entity.getModelId());
        entry.silent = 0;
        entity.beginIrControlSound(player.getUUID(), null, driver, front);
    }

    boolean holdingNative(ServerPlayer player, IrReadout driver) {
        var entry = entries.get(player.getUUID());
        return entry != null && entry.held != null && (entry.held.driver() == driver
                || entry.held.driver() == IrReadout.THROTTLE_BRAKE && (driver == IrReadout.THROTTLE || driver == IrReadout.TRAIN_BRAKE));
    }

    void touchDriver(ServerPlayer player) {
        var entry = entries.get(player.getUUID());
        if (entry != null && entry.held != null && entry.held.target().interaction() == null) entry.silent = 0;
    }

    void release(ServerPlayer player) {
        var entry = entries.get(player.getUUID());
        if (entry != null) release(entry);
    }

    private void release(Entry entry) {
        var held = entry.held;
        entry.held = null;
        if (held != null) held.target().entity().endIrControlSound(entry.player.getUUID());
    }

    void forget(UUID player) {
        var entry = entries.remove(player);
        if (entry != null) release(entry);
    }

    void tick() {
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!validPlayer(entry.player)) { release(entry); iterator.remove(); continue; }
            var held = entry.held;
            if (held == null) continue;
            var target = held.target();
            boolean valid = entry.player.getVehicle() == held.mount() && target.entity().getModelId().equals(held.model())
                    && permitted(entry.player, target.entity(), held.driver());
            if (target.interaction() == null) valid &= manager.drivers().authorized(entry.player, target.entity());
            if (!valid || ++entry.silent >= 60) {
                release(entry);
                if (target.interaction() != null) reply(entry.player, target.entity().getUUID(), target.interaction(), entry.sequence, Feedback.IR_CONTROL_UNAVAILABLE);
            }
        }
    }

    void close() { entries.values().forEach(this::release); entries.clear(); }

    private static void reply(ServerPlayer player, UUID vehicle, UUID interaction, long sequence, Feedback feedback) {
        PacketDistributor.sendToPlayer(player, new TrainIrInteractionResultPayload(vehicle, interaction, sequence, feedback));
    }
}
