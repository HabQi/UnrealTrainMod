/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.renderer.ModelRenderQueue;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import com.unrealtrainmod.vehicle.VehiclePose;
import java.io.IOException;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One script engine and its mutable script state for a single vehicle body/bogie slot. */
public final class VehicleScriptRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod RTM scripts");
    /** RTM wheel animation is distance based; the sample packs use roughly a 0.86 m wheel. */
    private static final double WHEEL_RADIUS = 0.43;
    private static final double MAX_FRAME_TRAVEL = 32.0;

    private final ScriptEngine engine;
    private final VehicleScriptRenderer renderer;
    private final LoadedModel.Source source;
    private final VehiclePartRole role;
    private final RtmScriptSource.Prepared prepared;
    private final String label;
    private boolean failed;
    private boolean hasPosition;
    private double lastX, lastY, lastZ, distance;
    private long lastTime = Long.MIN_VALUE;
    private long lastPoseRevision = Long.MIN_VALUE;
    private float lastPartialTick = -1.0f;

    private VehicleScriptRuntime(ScriptEngine engine, VehicleScriptRenderer renderer, LoadedModel.Source source,
                                 VehiclePartRole role, RtmScriptSource.Prepared prepared, String label) {
        this.engine = engine;
        this.renderer = renderer;
        this.source = source;
        this.role = role;
        this.prepared = prepared;
        this.label = label;
    }

    public static VehicleScriptRuntime create(PackageAsset script, LoadedModel.Source source,
                                              VehiclePartRole role, String label)
            throws IOException, ScriptException, NoSuchMethodException {
        var prepared = RtmScriptSource.load(script);
        ScriptEngine engine = RtmScript.createEngine();
        var renderer = new VehicleScriptRenderer();
        engine.put("renderer", renderer);
        try {
            RtmScript.eval(engine, prepared);
        } catch (ScriptException exception) {
            throw new IOException("Script error at " + RtmScript.describe(prepared, exception)
                    + ": " + exception.getMessage(), exception);
        }
        String renderClass = RtmScript.renderClass(engine);
        if (renderClass.isEmpty()) throw new IOException("Script has no renderClass");
        if (!RtmScript.VEHICLE_RENDERER.equals(renderClass)) {
            throw new IOException("Unsupported renderClass '" + renderClass + "'; expected " + RtmScript.VEHICLE_RENDERER);
        }
        if (!RtmScript.hasFunction(engine, "init")) throw new IOException("Script has no init(par1, par2)");
        try {
            RtmScript.invoke(engine, "init", null, null);
        } catch (ScriptException exception) {
            throw new IOException("init failed at " + RtmScript.describe(prepared, exception)
                    + ": " + exception.getMessage(), exception);
        }
        if (!RtmScript.hasFunction(engine, "render")) throw new IOException("Script has no render(entity, pass, partialTick)");
        return new VehicleScriptRuntime(engine, renderer, source, role, prepared, label);
    }

    /** Returns true when the script took over this model instance, even if it chose to draw nothing. */
    public boolean render(ModelRenderQueue queue, Object entity, VehiclePose vehiclePose,
                          float partialTick, PoseStack pose, int light) {
        if (failed) return false;
        updateWheel(entity, vehiclePose, partialTick);
        var context = new ScriptDrawContext(queue, source, pose, prepared.primaryFile() + " [" + role + "]");
        renderer.context(context);
        renderer.wheelRotation(wheelDegrees());
        GL11.attach(context);
        try {
            RtmScript.invoke(engine, "render", entity, 0, partialTick);
            context.submit(light);
            return true;
        } catch (Exception exception) {
            failed = true;
            String location = exception instanceof ScriptException script
                    ? RtmScript.describe(prepared, script) : prepared.primaryFile();
            LOGGER.warn("Vehicle script {} failed at {}; using the static model instead: {}",
                    label, location, exception.getMessage());
            LOGGER.debug("Vehicle script render failure", exception);
            return false;
        } finally {
            GL11.detach();
            context.restore();
            renderer.context(null);
            renderer.wheelRotation(0.0f);
        }
    }

    private void updateWheel(Object entity, VehiclePose vehiclePose, float partialTick) {
        if (!(entity instanceof TrainEntity train)) return;
        var pose = vehiclePose != null ? vehiclePose : train.renderPose(partialTick);
        if (pose == null || train.level() == null) return;
        long revision = train.poseRevision();
        if (revision != lastPoseRevision) {
            lastPoseRevision = revision;
            hasPosition = false;
        }
        long time = train.level().getGameTime();
        if (time == lastTime && partialTick == lastPartialTick) return;
        lastTime = time;
        lastPartialTick = partialTick;
        var p = pose.body().position();
        if (hasPosition) {
            double dx = p.x() - lastX, dy = p.y() - lastY, dz = p.z() - lastZ;
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Placement, re-binding and teleports reset the reference instead of spinning the wheels.
            if (moved < MAX_FRAME_TRAVEL) {
                // Local +Z is the authored forward direction; reversing must turn the wheels backwards.
                double yaw = Math.toRadians(pose.body().yaw());
                distance += -dx * Math.sin(yaw) + dz * Math.cos(yaw);
            }
        }
        lastX = p.x();
        lastY = p.y();
        lastZ = p.z();
        hasPosition = true;
    }

    private float wheelDegrees() {
        double sign = role == VehiclePartRole.REAR_BOGIE ? -1.0 : 1.0;
        double degrees = distance / WHEEL_RADIUS * (180.0 / Math.PI) * sign;
        return (float)(degrees % 360.0);
    }

    @Override
    public void close() {
        failed = true;
        engine.getBindings(ScriptContext.ENGINE_SCOPE).clear();
    }
}
