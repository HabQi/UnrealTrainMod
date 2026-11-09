/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.model.IrPickPart;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.renderer.IrModelRenderer;
import com.unrealtrainmod.client.gui.TrainDrivingOverlay;
import com.unrealtrainmod.compat.ir.IrControlDefinition;
import com.unrealtrainmod.compat.ir.IrReadout;
import com.unrealtrainmod.network.payload.TrainIrControlInputPayload.Operation;
import com.unrealtrainmod.train.TrainControlProfile;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Only the current driver session can operate the ridden car's authored model controls. */
public final class IrControlInteraction {
    private record Hit(IrPickPart part, IrControlDefinition control) {}
    private record Pending(float value, long sequence, int tick) {}
    private static final Map<String, Pending> pending = new HashMap<>();
    private static int ticks;
    private static Hit hovered, held;
    private static LoadedModel model;
    private static double grabY;
    private static float initial, target, sent;
    private static final float REACH = 4;

    private IrControlInteraction() {}

    private static boolean active() {
        var mc = Minecraft.getInstance();
        return TrainDrivingClient.usingCursor() && mc.options.getCameraType().isFirstPerson();
    }

    public static void pick(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES
                || com.unrealtrainmod.client.compat.iris.IrisCompat.shadowPass()) return;
        hovered = null;
        if (!active()) { release(); return; }
        var mc = Minecraft.getInstance();
        double x = mouseX(), y = mouseY();
        if (TrainDrivingOverlay.contains(x, y)) return;
        var entity = TrainDrivingClient.riddenVehicle();
        var loaded = ClientModelManager.INSTANCE.model(entity.getModelId());
        if (loaded != model) { reset(); model = loaded; }
        if (loaded == null || loaded.ir().controls().isEmpty()) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var vehiclePose = com.unrealtrainmod.client.compat.iris.IrisCompat.active()
                ? com.unrealtrainmod.client.compat.iris.IrisModelRenderer.vehiclePose(entity, partial) : entity.renderPose(partial);
        if (vehiclePose == null) return;
        // Unproject the actual world view, including FOV, aspect ratio and view bobbing.
        var inverseView = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert();
        float nx = (float)(2 * x / mc.getWindow().getGuiScaledWidth() - 1);
        float ny = (float)(1 - 2 * y / mc.getWindow().getGuiScaledHeight());
        var start = new Vector3f(nx, ny, -1).mulProject(inverseView);
        var direction = new Vector3f(nx, ny, 1).mulProject(inverseView).sub(start).normalize();
        var end = new Vector3f(direction).mul(REACH).add(start);
        var camera = event.getCamera().getPosition();
        var worldStart = camera.add(start.x, start.y, start.z);
        var worldEnd = camera.add(end.x, end.y, end.z);
        var block = mc.level.clip(new ClipContext(worldStart, worldEnd, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mc.player));
        float closest = block.getType() == HitResult.Type.MISS ? 1 : (float)(worldStart.distanceTo(block.getLocation()) / REACH);
        var body = vehiclePose.body();
        var p = body.position();
        var pose = new PoseStack();
        pose.translate(p.x() - camera.x, p.y() - camera.y, p.z() - camera.z);
        pose.mulPose(body.rotation());
        var frame = IrModelRenderer.sample(loaded, entity, vehiclePose, partial);
        for (var instance : loaded.instances()) {
            if (instance.irBinding() == null) continue;
            pose.pushPose();
            var transform = vehiclePose.partTransform(instance.definition());
            var position = transform.position();
            pose.translate(position.x(), position.y(), position.z());
            pose.mulPose(transform.rotation());
            for (var part : instance.irBinding().picking().get(instance.definition().role())) {
                pose.pushPose();
                try {
                    if (part.motion() != null && !IrModelRenderer.transform(part.motion(), frame, pose)) continue;
                    var matrix = pose.last().pose();
                    // Zero-scale animation frames contain no pickable surface.
                    if (Math.abs(matrix.determinant()) < 1e-10f) continue;
                    var inverse = new Matrix4f(matrix).invert();
                    var a = inverse.transformPosition(new Vector3f(start));
                    var b = inverse.transformPosition(new Vector3f(end));
                    if (!part.bounds().contains(a.x, a.y, a.z)
                            && part.bounds().clip(new Vec3(a.x, a.y, a.z), new Vec3(b.x, b.y, b.z)).isEmpty()) continue;
                    float distance = intersect(part, a, b.sub(a), closest);
                    if (distance < closest) {
                        closest = distance;
                        var control = part.group() == null ? null : loaded.ir().controls().get(part.group());
                        hovered = control == null ? null : new Hit(part, control);
                    }
                } finally { pose.popPose(); }
            }
            pose.popPose();
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

    private static float value(Hit hit) {
        var requested = pending.get(hit.control().group());
        return requested == null ? actual(hit.control().group()) : requested.value();
    }

    private static float actual(String group) {
        var entity = TrainDrivingClient.riddenVehicle();
        var snapshot = entity.irSnapshot();
        return model.ir().value(group, snapshot.values(), entity.runtimeState(), snapshot.frontCoupled(), snapshot.rearCoupled());
    }

    private static void request(Hit hit, float value, Operation operation) {
        long sequence = TrainDrivingClient.sendIr(hit.control().group(), value, false, operation);
        if (sequence >= 0) pending.put(hit.control().group(), new Pending(value, sequence, ticks));
    }

    private static float quantize(Hit hit, float value) {
        value = Math.clamp(value, 0, 1);
        var driver = hit.control().driver();
        if (driver == IrReadout.THROTTLE) return Math.round(value * TrainControlProfile.POWER_NOTCHES) / (float)TrainControlProfile.POWER_NOTCHES;
        if (driver == IrReadout.TRAIN_BRAKE) return Math.round(value * TrainControlProfile.BRAKE_NOTCHES) / (float)TrainControlProfile.BRAKE_NOTCHES;
        if (driver == IrReadout.REVERSER) return Math.round(value * 2) / 2f;
        if (driver == IrReadout.THROTTLE_BRAKE) {
            int steps = value >= 0.5f ? TrainControlProfile.POWER_NOTCHES : TrainControlProfile.BRAKE_NOTCHES;
            return 0.5f + Math.round((value - 0.5f) * 2 * steps) / (2f * steps);
        }
        return value;
    }

    public static boolean click(double x, double y) {
        if (!active() || TrainDrivingOverlay.contains(x, y) || hovered == null) return false;
        release();
        var hit = hovered;
        if (hit.part().type() == IrControlDefinition.Type.TOGGLE) {
            request(hit, value(hit) >= 0.5f ? 0 : 1, Operation.CLICK);
        } else {
            held = hit;
            grabY = y;
            initial = value(hit);
            target = sent = hit.part().type() == IrControlDefinition.Type.PRESS ? 1 : quantize(hit, initial);
            request(hit, target, Operation.BEGIN);
        }
        return true;
    }

    public static void drag(double y) {
        if (held != null && held.part().type() == IrControlDefinition.Type.CONTINUOUS) {
            target = quantize(held, initial + (float)((grabY - y) / 120));
        }
    }

    public static void tick() {
        ticks++;
        var state = TrainDrivingClient.state();
        if (state != null && model != null) pending.entrySet().removeIf(entry -> ticks - entry.getValue().tick() > 40
                || state.sequence() >= entry.getValue().sequence() && actual(entry.getKey()) == entry.getValue().value());
        if (!active()) { release(); hovered = null; return; }
        drag(mouseY());
        if (held != null && target != sent) {
            request(held, target, Operation.UPDATE);
            sent = target;
        }
    }

    public static boolean scroll(double x, double y, double delta) {
        if (!active() || TrainDrivingOverlay.contains(x, y) || hovered == null || held != null || delta == 0) return false;
        if (hovered.part().type() != IrControlDefinition.Type.CONTINUOUS) return true;
        var driver = hovered.control().driver();
        float step = driver == IrReadout.THROTTLE ? 1f / TrainControlProfile.POWER_NOTCHES
                : driver == IrReadout.TRAIN_BRAKE ? 1f / TrainControlProfile.BRAKE_NOTCHES
                : driver == IrReadout.REVERSER ? 0.5f
                : driver == IrReadout.THROTTLE_BRAKE ? 1f / (2 * (value(hovered) + delta * 0.001 >= 0.5
                        ? TrainControlProfile.POWER_NOTCHES : TrainControlProfile.BRAKE_NOTCHES)) : 0.05f;
        request(hovered, quantize(hovered, value(hovered) + (float)Math.signum(delta) * step), Operation.CLICK);
        return true;
    }

    public static void release() {
        if (held == null) return;
        var previous = held;
        held = null;
        request(previous, previous.part().type() == IrControlDefinition.Type.PRESS ? 0 : target, Operation.END);
    }

    public static void reset() { release(); hovered = null; model = null; pending.clear(); ticks = 0; }
    public static void reject() { held = null; hovered = null; pending.clear(); }

    public static void render(GuiGraphics graphics) {
        if (!active()) return;
        var hit = held != null ? held : hovered;
        if (hit == null) return;
        var type = hit.part().type();
        String key = type == IrControlDefinition.Type.PRESS ? "press" : type == IrControlDefinition.Type.TOGGLE ? "toggle" : "drag";
        var tip = Component.literal(hit.control().label()).append("  ")
                .append(String.format(Locale.ROOT, "%.2f", held != null ? target : value(hit)))
                .append("\n").append(Component.translatable("gui.unrealtrainmod.driving.ir.3d." + key));
        graphics.renderTooltip(Minecraft.getInstance().font, Minecraft.getInstance().font.split(tip, 240), (int)mouseX(), (int)mouseY());
    }

    private static double mouseX() {
        var mc = Minecraft.getInstance();
        return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
    }
    private static double mouseY() {
        var mc = Minecraft.getInstance();
        return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
    }
}
