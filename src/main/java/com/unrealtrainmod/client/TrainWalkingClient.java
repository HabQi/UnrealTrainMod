/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrainPassengerStatePayload;
import com.unrealtrainmod.network.payload.TrainWalkInputPayload;
import com.unrealtrainmod.train.PassengerPose;
import com.unrealtrainmod.train.TrainPassengerClientState;
import com.unrealtrainmod.train.TrainWalkStep;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleInteriorSpec;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Local walking prediction, input consumption and camera-facing local point maintenance. */
public final class TrainWalkingClient {
    private static final double CORRECTION_DECAY = 0.5;
    private static final double OBSERVER_SMOOTHING = 0.5;
    private static final double SNAP_DISTANCE = 1.5;
    private static final int SILENCE_LIMIT = 60;
    private record Pending(long sequence, float x, float z) {}

    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private static TrainPassengerStatePayload snapshot;
    private static long sequence;
    private static double predictedX, predictedZ, renderX, renderZ, lastRenderX, lastRenderZ;
    private static double correctionX, correctionZ;
    private static float inputX, inputZ;
    private static float mountBodyYaw;
    private static boolean mountYawSet;
    private static int silence;

    private TrainWalkingClient() {}

    public static void onState(TrackSyncEvent event) {
        if (!(event.payload() instanceof TrainPassengerStatePayload incoming)) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().equals(incoming.dimension())) return;
        var entry = TrainPassengerClientState.accept(incoming.player(), incoming.vehicle(), incoming.session(),
                incoming.sequence(), incoming.tick(), incoming.localX(), incoming.floorY(), incoming.localZ(),
                incoming.pose(), incoming.heightStep(), incoming.present());
        if (!incoming.present()) {
            if (mc.player != null && mc.player.getUUID().equals(incoming.player())
                    && snapshot != null && snapshot.session().equals(incoming.session())) {
                clearLocal();
            }
            return;
        }
        if (entry == null) return;
        if (mc.player == null || !mc.player.getUUID().equals(incoming.player())) return;
        acceptLocal(incoming);
    }

    private static void acceptLocal(TrainPassengerStatePayload incoming) {
        boolean newSession = snapshot == null || !snapshot.session().equals(incoming.session());
        snapshot = incoming;
        silence = 0;
        if (newSession) {
            pending.clear();
            sequence = incoming.sequence();
            predictedX = renderX = incoming.localX();
            predictedZ = renderZ = incoming.localZ();
            lastRenderX = renderX;
            lastRenderZ = renderZ;
            correctionX = correctionZ = 0;
            var vehicle = train();
            mountYawSet = vehicle != null;
            mountBodyYaw = mountYawSet ? vehicle.getYRot() : 0;
        } else {
            double oldX = predictedX, oldZ = predictedZ;
            pending.removeIf(input -> input.sequence() <= incoming.sequence());
            predictedX = incoming.localX();
            predictedZ = incoming.localZ();
            var train = train();
            var spec = train == null ? null : spec(train);
            if (spec != null && spec.enabled()) {
                for (var input : pending) {
                    var point = TrainWalkStep.move(spec, predictedX, predictedZ, input.x(), input.z());
                    predictedX = point.x();
                    predictedZ = point.z();
                }
            }
            // Preserve the displayed point; decay only the reconciliation error during client ticks.
            correctionX += oldX - predictedX;
            correctionZ += oldZ - predictedZ;
        }
    }

    /** Captures WASD after vanilla input refresh, then clears horizontal movement and jumping. */
    public static void movement(MovementInputUpdateEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || event.getEntity() != mc.player) return;
        var input = event.getInput();
        boolean trainPassenger = mc.player.getVehicle() instanceof TrainEntity;
        if (snapshot == null || !riding(mc)) {
            // Also covers the first tick before the boarding snapshot arrives.
            if (trainPassenger) {
                input.forwardImpulse = 0;
                input.leftImpulse = 0;
                input.up = input.down = input.left = input.right = false;
                input.jumping = false;
            }
            return;
        }
        float forward = input.forwardImpulse;
        float strafe = input.leftImpulse;
        if (mc.screen != null || mc.isPaused()) forward = strafe = 0;
        input.forwardImpulse = 0;
        input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = false;
        input.jumping = false;
        // Keep shiftKeyDown: the normal dismount packet ends the server session.

        var train = (TrainEntity) mc.player.getVehicle();
        if (!mountYawSet) {
            mountBodyYaw = train.getYRot();
            mountYawSet = true;
        }
        double yaw = Math.toRadians(mc.player.getYRot() + (train.getYRot() - mountBodyYaw));
        double body = Math.toRadians(train.getYRot());
        double worldX = -Math.sin(yaw) * forward + Math.cos(yaw) * strafe;
        double worldZ = Math.cos(yaw) * forward + Math.sin(yaw) * strafe;
        double localX = Math.cos(body) * worldX + Math.sin(body) * worldZ;
        double localZ = -Math.sin(body) * worldX + Math.cos(body) * worldZ;
        double length = Math.hypot(localX, localZ);
        if (length > 1) {
            localX /= length;
            localZ /= length;
        }
        inputX = (float) localX;
        inputZ = (float) localZ;
    }

    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.isPaused()) {
            inputX = inputZ = 0;
            return;
        }
        updateObservers(mc);
        if (snapshot == null) {
            TrainPassengerClientState.remove(mc.player.getUUID());
            return;
        }
        var train = train();
        if (train == null) {
            // The mount packet and the first state packet may arrive in either order.
            if (++silence > 5) clearLocal();
            return;
        }
        if (!mountYawSet) {
            mountBodyYaw = train.getYRot();
            mountYawSet = true;
        }
        if (++silence > SILENCE_LIMIT) {
            clearLocal();
            return;
        }
        if (mc.screen != null) inputX = inputZ = 0;
        var spec = spec(train);
        boolean moving = (inputX != 0 || inputZ != 0) && spec != null && spec.enabled()
                && pending.size() < TrainWalkInputPayload.MAX_PENDING_INPUTS;
        if (moving) {
            sequence++;
            PacketDistributor.sendToServer(new TrainWalkInputPayload(snapshot.session(), sequence, inputX, inputZ));
            pending.add(new Pending(sequence, inputX, inputZ));
            var point = TrainWalkStep.move(spec, predictedX, predictedZ, inputX, inputZ);
            predictedX = point.x();
            predictedZ = point.z();
        }
        TrainPassengerClientState.beginLocal(mc.player.getUUID(), train.getUUID(), snapshot.session());
        boolean snapped = Math.hypot(correctionX, correctionZ) > SNAP_DISTANCE;
        if (snapped) {
            correctionX = correctionZ = 0;
        } else {
            correctionX *= CORRECTION_DECAY;
            correctionZ *= CORRECTION_DECAY;
        }
        renderX = predictedX + correctionX;
        renderZ = predictedZ + correctionZ;
        if (spec != null && spec.enabled()) {
            renderX = spec.clampX(renderX);
            renderZ = spec.clampZ(renderZ);
            correctionX = renderX - predictedX;
            correctionZ = renderZ - predictedZ;
        }
        TrainPassengerClientState.setLocal(mc.player.getUUID(), train.getUUID(), snapshot.session(),
                predictedX, predictedZ, renderX, renderZ, snapped);
        train.positionRider(mc.player);
        if (snapshot.pose() != PassengerPose.LYING) {
            double moved = snapped ? 0 : Math.hypot(renderX - lastRenderX, renderZ - lastRenderZ);
            mc.player.walkAnimation.update((float) Math.min(moved * 4, 1), 1.0f);
        }
        lastRenderX = renderX;
        lastRenderZ = renderZ;
        inputX = inputZ = 0;
    }

    private static void updateObservers(Minecraft mc) {
        var iterator = TrainPassengerClientState.entries().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.idle++ > 100) {
                iterator.remove();
                continue;
            }
            if (mc.player != null && entry.player.equals(mc.player.getUUID())) continue;
            Player player = null;
            for (var candidate : mc.level.players()) {
                if (candidate.getUUID().equals(entry.player)) {
                    player = candidate;
                    break;
                }
            }
            if (player == null) continue;
            double oldX = entry.renderX, oldZ = entry.renderZ;
            entry.prevRenderX = oldX;
            entry.prevRenderZ = oldZ;
            double dx = entry.targetX - entry.renderX, dz = entry.targetZ - entry.renderZ;
            boolean snapped = Math.hypot(dx, dz) > SNAP_DISTANCE;
            if (snapped) {
                entry.renderX = entry.targetX;
                entry.renderZ = entry.targetZ;
                entry.prevRenderX = entry.renderX;
                entry.prevRenderZ = entry.renderZ;
                oldX = entry.renderX;
                oldZ = entry.renderZ;
            } else {
                entry.renderX += dx * OBSERVER_SMOOTHING;
                entry.renderZ += dz * OBSERVER_SMOOTHING;
            }
            if (player.getVehicle() instanceof TrainEntity train && train.getUUID().equals(entry.vehicle)) {
                train.positionRider(player);
                if (entry.pose != PassengerPose.LYING) {
                    double moved = Math.hypot(entry.renderX - oldX, entry.renderZ - oldZ);
                    player.walkAnimation.update((float) Math.min(moved * 4, 1), 1.0f);
                }
            }
        }
    }

    private static boolean riding(Minecraft mc) {
        return mc.player != null && mc.player.getVehicle() instanceof TrainEntity train
                && train.getUUID().equals(snapshot.vehicle());
    }

    /** Latest synced passenger snapshot, exposed so posture requests stay tied to this session. */
    static TrainPassengerStatePayload snapshot() {
        return snapshot;
    }

    private static TrainEntity train() {
        var mc = Minecraft.getInstance();
        if (snapshot == null || mc.level == null || mc.player == null
                || !mc.level.dimension().location().equals(snapshot.dimension())) return null;
        return mc.player.getVehicle() instanceof TrainEntity train && train.getUUID().equals(snapshot.vehicle())
                ? train : null;
    }

    private static VehicleInteriorSpec spec(TrainEntity train) {
        var model = VehicleCatalog.find(train.getModelId());
        return model == null ? null : model.interiorSpec();
    }

    static void clearLocal() {
        var mc = Minecraft.getInstance();
        if (mc.player != null) TrainPassengerClientState.remove(mc.player.getUUID());
        snapshot = null;
        pending.clear();
        sequence = 0;
        predictedX = predictedZ = renderX = renderZ = lastRenderX = lastRenderZ = 0;
        inputX = inputZ = 0;
        correctionX = correctionZ = 0;
        silence = 0;
        mountBodyYaw = 0;
        mountYawSet = false;
    }

    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) clear();
    }

    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    public static void clonePlayer(ClientPlayerNetworkEvent.Clone event) { clear(); }

    public static void clear() {
        clearLocal();
        TrainPassengerClientState.clear();
    }
}
