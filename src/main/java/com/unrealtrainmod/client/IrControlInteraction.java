/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.model.IrPickPart;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.renderer.IrModelRenderer;
import com.unrealtrainmod.client.renderer.TrainModelRenderer;
import com.unrealtrainmod.client.gui.TrainDrivingOverlay;
import com.unrealtrainmod.compat.ir.IrControlDefinition;
import com.unrealtrainmod.compat.ir.IrInteractionRules;
import com.unrealtrainmod.compat.ir.IrReadout;
import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload.Feedback;
import com.unrealtrainmod.network.payload.TrainIrControlInputPayload.Operation;
import com.unrealtrainmod.network.payload.TrainIrInteractionPayload;
import com.unrealtrainmod.network.payload.TrainIrInteractionResultPayload;
import com.unrealtrainmod.train.TrainControlProfile;
import com.unrealtrainmod.vehicle.VehiclePose;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Model picking and requests have no dependency on boarding or driver synchronization. */
public final class IrControlInteraction {
    private record Hit(TrainEntity entity, LoadedModel model, IrPickPart part, IrControlDefinition control) {
        Key key() { return new Key(entity.getUUID(), control.group()); }
    }
    private record Key(UUID vehicle, String group) {}
    private record Pending(Hit hit, float value, UUID interaction, long sequence, int tick, boolean acknowledged) {}
    private static final Map<Key, Pending> pending = new HashMap<>();
    private static int ticks, heartbeat;
    private static long sequence;
    private static Hit hovered, held;
    private static UUID interaction;
    private static double grabX, grabY;
    private static float initial, target, sent;

    private IrControlInteraction() {}

    private static boolean active() {
        return TrainCursorClient.modelInput() && Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    private static boolean permitted(Hit hit) {
        var player = Minecraft.getInstance().player;
        if (player == null || hit.entity().isRemoved()
                || ClientModelManager.INSTANCE.model(hit.entity().getModelId()) != hit.model()
                || !IrInteractionRules.inReach(player.getEyePosition(), hit.entity().getBoundingBox())) return false;
        boolean driving = TrainDrivingClient.state() != null && TrainDrivingClient.riddenVehicle() == hit.entity();
        return hit.control().readOnly() ? driving : driving || IrInteractionRules.holdingKey(player);
    }

    public static void pick(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES
                || com.unrealtrainmod.client.compat.iris.IrisCompat.shadowPass()) return;
        hovered = null;
        if (!active()) { release(); return; }
        var mc = Minecraft.getInstance();
        double x = TrainCursorClient.mouseX(), y = TrainCursorClient.mouseY();
        if (TrainDrivingOverlay.contains(x, y)) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var inverseView = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert();
        float nx = (float)(2 * x / mc.getWindow().getGuiScaledWidth() - 1);
        float ny = (float)(1 - 2 * y / mc.getWindow().getGuiScaledHeight());
        var start = new Vector3f(nx, ny, -1).mulProject(inverseView);
        var end = new Vector3f(nx, ny, 1).mulProject(inverseView).sub(start).normalize().mul((float)IrInteractionRules.REACH);
        var camera = event.getCamera().getPosition();
        var worldStart = camera.add(start.x, start.y, start.z);
        var worldEnd = camera.add(end.x, end.y, end.z);
        var block = mc.level.clip(new ClipContext(worldStart, worldEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        float closest = block.getType() == HitResult.Type.MISS ? 1
                : (float)(worldStart.distanceTo(block.getLocation()) / worldStart.distanceTo(worldEnd));
        var area = new AABB(worldStart, worldEnd).inflate(IrInteractionRules.REACH);
        for (var entity : mc.level.getEntitiesOfClass(TrainEntity.class, area)) {
            if (!IrInteractionRules.inReach(camera, entity.getBoundingBox())) continue;
            var loaded = ClientModelManager.INSTANCE.model(entity.getModelId());
            if (loaded == null || loaded.ir() == IrVehicleDefinition.NONE) continue;
            var vehiclePose = com.unrealtrainmod.client.compat.iris.IrisCompat.active()
                    ? com.unrealtrainmod.client.compat.iris.IrisModelRenderer.vehiclePose(entity, partial) : entity.renderPose(partial);
            var pose = new PoseStack();
            if (vehiclePose != null) {
                var p = vehiclePose.body().position();
                pose.translate(p.x() - camera.x, p.y() - camera.y, p.z() - camera.z);
                pose.mulPose(vehiclePose.body().rotation());
            } else {
                pose.translate(Mth.lerp((double)partial, entity.xOld, entity.getX()) - camera.x,
                        Mth.lerp((double)partial, entity.yOld, entity.getY()) - camera.y,
                        Mth.lerp((double)partial, entity.zOld, entity.getZ()) - camera.z);
                pose.mulPose(VehiclePose.Body.rotation(Mth.rotLerp(partial, entity.yRotO, entity.getYRot()),
                        Mth.lerp(partial, entity.xRotO, entity.getXRot())));
            }
            var frame = IrModelRenderer.sample(loaded, entity, vehiclePose, partial);
            for (var instance : loaded.instances()) {
                if (instance.irBinding() == null) continue;
                pose.pushPose();
                try {
                    TrainModelRenderer.transformInstance(instance, vehiclePose, pose);
                    for (var part : instance.irBinding().picking().get(instance.definition().role())) {
                        pose.pushPose();
                        try {
                            if (part.motion() != null && !IrModelRenderer.transform(part.motion(), frame, pose)) continue;
                            var matrix = pose.last().pose();
                            if (Math.abs(matrix.determinant()) < 1e-10f) continue;
                            var inverse = new Matrix4f(matrix).invert();
                            var a = inverse.transformPosition(new Vector3f(start));
                            var b = inverse.transformPosition(new Vector3f(end));
                            if (!part.bounds().contains(a.x, a.y, a.z)
                                    && part.bounds().clip(new Vec3(a.x, a.y, a.z), new Vec3(b.x, b.y, b.z)).isEmpty()) continue;
                            float distance = intersect(part, a, b.sub(a), closest);
                            if (distance < closest) {
                                closest = distance;
                                hovered = part.control() == null || !part.control().interactive() ? null
                                        : new Hit(entity, loaded, part, loaded.ir().controls().get(part.control().group()));
                            }
                        } finally { pose.popPose(); }
                    }
                } finally { pose.popPose(); }
            }
        }
    }

    /** Segment/triangle intersection, double sided to match selection from inside a cab. */
    private static float intersect(IrPickPart part, Vector3f origin, Vector3f direction, float closest) {
        for (var batch : part.geometry().batches()) {
            var mesh = batch.mesh();
            var v = mesh.vertices();
            var indices = mesh.indices();
            for (int i = batch.firstIndex(); i < batch.firstIndex() + batch.indexCount(); i += 3) {
                int a = indices[i] * 8, b = indices[i + 1] * 8, c = indices[i + 2] * 8;
                float ex = v[b] - v[a], ey = v[b + 1] - v[a + 1], ez = v[b + 2] - v[a + 2];
                float fx = v[c] - v[a], fy = v[c + 1] - v[a + 1], fz = v[c + 2] - v[a + 2];
                float px = direction.y * fz - direction.z * fy;
                float py = direction.z * fx - direction.x * fz;
                float pz = direction.x * fy - direction.y * fx;
                float det = ex * px + ey * py + ez * pz;
                if (Math.abs(det) < 1e-9f) continue;
                float tx = origin.x - v[a], ty = origin.y - v[a + 1], tz = origin.z - v[a + 2];
                float u = (tx * px + ty * py + tz * pz) / det;
                if (u < 0 || u > 1) continue;
                float qx = ty * ez - tz * ey, qy = tz * ex - tx * ez, qz = tx * ey - ty * ex;
                float w = (direction.x * qx + direction.y * qy + direction.z * qz) / det;
                if (w < 0 || u + w > 1) continue;
                float t = (fx * qx + fy * qy + fz * qz) / det;
                if (t >= 0 && t < closest) closest = t;
            }
        }
        return closest;
    }

    private static float actual(Hit hit) {
        var snapshot = hit.entity().irSnapshot();
        return hit.model().ir().value(hit.control().group(), snapshot.values(), hit.entity().runtimeState(), snapshot.frontCoupled(), snapshot.rearCoupled());
    }

    private static float value(Hit hit) {
        var requested = pending.get(hit.key());
        return requested == null ? actual(hit) : requested.value();
    }

    private static void request(Hit hit, UUID id, float value, Operation operation) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        PacketDistributor.sendToServer(new TrainIrInteractionPayload(hit.entity().getUUID(), hit.part().key(), id, ++sequence, value, operation));
        if (operation != Operation.HEARTBEAT) pending.put(hit.key(), new Pending(hit, value, id, sequence, ticks, false));
        heartbeat = 0;
    }

    private static float quantize(Hit hit, float value) {
        value = Math.clamp(value, 0, 1);
        var driver = hit.control().driver();
        int notches = hit.model().ir().throttleNotches();
        if (driver == IrReadout.THROTTLE) return Math.round(value * notches) / (float)notches;
        if (driver == IrReadout.TRAIN_BRAKE) return Math.round(value * TrainControlProfile.BRAKE_NOTCHES) / (float)TrainControlProfile.BRAKE_NOTCHES;
        if (driver == IrReadout.REVERSER) return Math.round(value * 2) / 2f;
        if (driver == IrReadout.THROTTLE_BRAKE) {
            int steps = value >= 0.5f ? notches : TrainControlProfile.BRAKE_NOTCHES;
            return 0.5f + Math.round((value - 0.5f) * 2 * steps) / (2f * steps);
        }
        return value;
    }

    public static void click(double x, double y) {
        if (!active() || TrainDrivingOverlay.contains(x, y) || hovered == null || !permitted(hovered)) return;
        release();
        var id = UUID.randomUUID();
        var hit = hovered;
        if (hit.part().control().type() == IrControlDefinition.Type.TOGGLE) {
            request(hit, id, value(hit) >= 0.5f ? 0 : 1, Operation.CLICK);
        } else {
            held = hit;
            interaction = id;
            grabX = x;
            grabY = y;
            initial = value(hit);
            target = sent = hit.part().control().type() == IrControlDefinition.Type.PRESS ? 1 : quantize(hit, initial);
            request(hit, id, target, Operation.BEGIN);
        }
    }

    public static void drag(double x, double y) {
        if (held != null && held.part().control().type() == IrControlDefinition.Type.CONTINUOUS) {
            target = quantize(held, initial + (float)(((x - grabX) * 1.5 + y - grabY) / 120));
        }
    }

    public static void tick() {
        ticks++;
        pending.entrySet().removeIf(entry -> entry.getValue().hit().entity().isRemoved() || ticks - entry.getValue().tick() > 40
                || entry.getValue().acknowledged() && actual(entry.getValue().hit()) == entry.getValue().value());
        if (!active()) { release(); hovered = null; return; }
        if (held == null) return;
        if (!permitted(held)) { release(); hovered = null; return; }
        if (target != sent) {
            request(held, interaction, target, Operation.UPDATE);
            sent = target;
        } else if (++heartbeat >= 10) request(held, interaction, target, Operation.HEARTBEAT);
    }

    public static void scroll(double x, double y, double delta) {
        if (!active() || TrainDrivingOverlay.contains(x, y) || hovered == null || !permitted(hovered)
                || held != null || delta == 0 || hovered.part().control().type() != IrControlDefinition.Type.CONTINUOUS) return;
        var driver = hovered.control().driver();
        int notches = hovered.model().ir().throttleNotches();
        float current = value(hovered);
        float direction = -(float)Math.signum(delta);
        float step = driver == IrReadout.THROTTLE ? 1f / notches
                : driver == IrReadout.TRAIN_BRAKE ? 1f / TrainControlProfile.BRAKE_NOTCHES
                : driver == IrReadout.REVERSER ? 0.5f
                : driver == IrReadout.THROTTLE_BRAKE ? 1f / (2 * (current > 0.5f || current == 0.5f && direction > 0
                        ? notches : TrainControlProfile.BRAKE_NOTCHES)) : 0.05f;
        request(hovered, UUID.randomUUID(), quantize(hovered, current + direction * step), Operation.CLICK);
    }

    public static void release() {
        if (held == null) return;
        var previous = held;
        var id = interaction;
        held = null;
        interaction = null;
        request(previous, id, previous.part().control().type() == IrControlDefinition.Type.PRESS ? 0 : target, Operation.END);
    }

    public static void reset() { release(); hovered = null; pending.clear(); ticks = heartbeat = 0; }

    public static void onSync(TrackSyncEvent event) {
        if (!(event.payload() instanceof TrainIrInteractionResultPayload result)) return;
        boolean relevant = held != null && held.entity().getUUID().equals(result.vehicle()) && interaction.equals(result.interaction());
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var requested = entry.getValue();
            if (!entry.getKey().vehicle().equals(result.vehicle()) || !requested.interaction().equals(result.interaction())
                    || requested.sequence() > result.sequence()) continue;
            relevant = true;
            if (result.feedback() != Feedback.NONE) iterator.remove();
            else entry.setValue(new Pending(requested.hit(), requested.value(), requested.interaction(), requested.sequence(), requested.tick(), true));
        }
        if (relevant && result.feedback() != Feedback.NONE) {
            if (held != null && interaction.equals(result.interaction())) { held = null; interaction = null; }
            var player = Minecraft.getInstance().player;
            if (player != null) player.displayClientMessage(Component.translatable("gui.unrealtrainmod.driving."
                    + result.feedback().name().toLowerCase(Locale.ROOT)), true);
        }
    }

    public static void render(GuiGraphics graphics) {
        if (!active() || Minecraft.getInstance().options.hideGui) return;
        var hit = held != null ? held : hovered;
        if (hit == null) return;
        var type = hit.part().control().type();
        String key = !permitted(hit) ? hit.control().readOnly() ? "driving_required" : "key_required"
                : type == IrControlDefinition.Type.PRESS ? "press" : type == IrControlDefinition.Type.TOGGLE ? "toggle" : "drag";
        var tip = Component.literal(hit.control().label()).append("  ")
                .append(String.format(Locale.ROOT, "%.2f", held != null ? target : value(hit)))
                .append("\n").append(Component.translatable("gui.unrealtrainmod.driving.ir.3d." + key));
        var font = Minecraft.getInstance().font;
        graphics.renderTooltip(font, font.split(tip, 240),
                (int)TrainCursorClient.mouseX(), (int)TrainCursorClient.mouseY());
    }
}
