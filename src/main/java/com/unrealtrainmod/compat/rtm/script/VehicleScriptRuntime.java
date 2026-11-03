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
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One isolated script instance for a single vehicle body/bogie slot; compiled code is shared. */
public final class VehicleScriptRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod RTM scripts");
    /** RTM wheel animation is distance based; the sample packs use roughly a 0.86 m wheel. */
    private static final double WHEEL_RADIUS = 0.43;
    private static final double MAX_FRAME_TRAVEL = 32.0;
    /** Ignores sub-micrometre interpolation noise so parked vehicles keep a stable wheel angle. */
    private static final double MIN_FRAME_TRAVEL = 1e-7;

    private final RtmScriptInstance instance;
    private final VehicleScriptRenderer renderer;
    private final LoadedModel.Source source;
    private final VehiclePartRole role;
    private final RtmScriptSource.Prepared prepared;
    private final String label;
    private final RtmVehicleAdapter adapter;
    private boolean failed;
    private boolean hasPosition;
    private double lastX, lastY, lastZ, distance;
    private long lastTime = Long.MIN_VALUE;
    private long lastPoseRevision = Long.MIN_VALUE;
    private float lastPartialTick = -1.0f;

    private VehicleScriptRuntime(RtmScriptInstance instance, VehicleScriptRenderer renderer, LoadedModel.Source source,
                                 VehiclePartRole role, RtmScriptSource.Prepared prepared, PackageAsset script, String label) {
        this.instance = instance;
        this.renderer = renderer;
        this.source = source;
        this.role = role;
        this.prepared = prepared;
        this.label = label;
        this.adapter = new RtmVehicleAdapter(script, role);
    }

    public static VehicleScriptRuntime create(RtmScriptCache cache, PackageAsset script, PackageAsset model, LoadedModel.Source source,
                                              VehiclePartRole role, String label)
            throws IOException {
        var program = cache.program(script);
        var renderer = new VehicleScriptRenderer(source, script + " model " + model);
        RtmScriptInstance instance = null;
        boolean transferred = false;
        try {
            instance = new RtmScriptInstance(program);
            instance.put("renderer", renderer);
            instance.eval();
            String renderClass = instance.renderClass();
            if (!RtmScript.VEHICLE_RENDERER.equals(renderClass)) {
                throw new IOException("Script entry check failed: renderClass '" + renderClass
                        + "'; expected " + RtmScript.VEHICLE_RENDERER);
            }
            if (!instance.hasFunction("init")) throw new IOException("Script entry check failed: missing init(par1, par2)");
            instance.init();
            if (!instance.hasFunction("render")) {
                throw new IOException("Script entry check failed: missing render(entity, pass, partialTick)");
            }
            ScriptDiagnostics.scriptScope("vehicle", script, renderClass
                    + "; render(entity, pass=0, partialTick) uses an isolated global, vehicle view, part draws, matrix and per-draw lighting state");
            var runtime = new VehicleScriptRuntime(instance, renderer, source, role, program.prepared, script, label);
            transferred = true;
            return runtime;
        } finally {
            if (!transferred) {
                if (instance != null) instance.close();
                renderer.release();
            }
        }
    }

    /** Returns true when the script took over this model instance, even if it chose to draw nothing. */
    public boolean render(ModelRenderQueue queue, TrainEntity entity, VehiclePose vehiclePose,
                          float partialTick, PoseStack pose, int light) {
        if (failed) return false;
        updateWheel(entity, vehiclePose, partialTick);
        var context = new ScriptDrawContext(queue, source, renderer.parts(), pose, light);
        var vehicleView = adapter.sample(entity, partialTick, wheelDegrees(), light);
        renderer.context(context);
        GL11.attach(context);
        boolean completed = false;
        try {
            instance.invoke("render", vehicleView, 0, partialTick);
            context.submit();
            completed = true;
            return true;
        } catch (Exception | LinkageError exception) {
            String location = RtmScript.describe(prepared, exception);
            LOGGER.warn("Vehicle script {} render failed at {}; using the static model instead: {}",
                    label, location, exception.getMessage());
            LOGGER.debug("Vehicle script render failure", exception);
            return false;
        } finally {
            GL11.detach();
            context.restore();
            renderer.context(null);
            if (!completed) close();
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
                // Local +Z is the authored forward direction and already includes pitch, so slopes accumulate
                // their full three-dimensional travel. Reversing produces a negative distance.
                var forward = new Vector3f(0, 0, 1).rotate(pose.body().rotation());
                double travel = dx * forward.x + dy * forward.y + dz * forward.z;
                if (Math.abs(travel) > MIN_FRAME_TRAVEL) distance += travel;
            }
        }
        lastX = p.x();
        lastY = p.y();
        lastZ = p.z();
        hasPosition = true;
    }

    /**
     * RTM's rear bogie faces backwards, so its model-local wheel angle has the opposite sign.
     * The result is in degrees for {@code GL11.glRotatef}.
     */
    private float wheelDegrees() {
        double sign = role == VehiclePartRole.REAR_BOGIE ? -1.0 : 1.0;
        double degrees = sign * distance / WHEEL_RADIUS * (180.0 / Math.PI);
        return (float)(degrees % 360.0);
    }

    @Override
    public void close() {
        failed = true;
        instance.close();
        renderer.release();
    }
}
