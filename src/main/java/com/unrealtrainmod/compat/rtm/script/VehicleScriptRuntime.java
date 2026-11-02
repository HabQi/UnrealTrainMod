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
import com.unrealtrainmod.vehicle.VehicleDoor;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import com.unrealtrainmod.vehicle.VehiclePose;
import java.io.IOException;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One script engine and its mutable script state for a single vehicle body/bogie slot. */
public final class VehicleScriptRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod RTM scripts");
    /** RTM wheel animation is distance based; the sample packs use roughly a 0.86 m wheel. */
    private static final double WHEEL_RADIUS = 0.43;
    private static final double MAX_FRAME_TRAVEL = 32.0;
    /** Ignores sub-micrometre interpolation noise so parked vehicles keep a stable wheel angle. */
    private static final double MIN_FRAME_TRAVEL = 1e-7;

    private final ScriptEngine engine;
    private final VehicleScriptRenderer renderer;
    private final LoadedModel.Source source;
    private final VehiclePartRole role;
    private final RtmScriptSource.Prepared prepared;
    private final String label;
    private final RtmVehicleView vehicleView;
    private boolean failed;
    private boolean hasPosition;
    private double lastX, lastY, lastZ, distance;
    private long lastTime = Long.MIN_VALUE;
    private long lastPoseRevision = Long.MIN_VALUE;
    private float lastPartialTick = -1.0f;

    private VehicleScriptRuntime(ScriptEngine engine, VehicleScriptRenderer renderer, LoadedModel.Source source,
                                 VehiclePartRole role, RtmScriptSource.Prepared prepared, PackageAsset script, String label) {
        this.engine = engine;
        this.renderer = renderer;
        this.source = source;
        this.role = role;
        this.prepared = prepared;
        this.label = label;
        this.vehicleView = new RtmVehicleView(script);
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
        ScriptDiagnostics.scriptScope("vehicle", script, renderClass
                + "; render(entity, pass=0, partialTick) uses a vehicle view, part draws, matrix and per-draw lighting state");
        return new VehicleScriptRuntime(engine, renderer, source, role, prepared, script, label);
    }

    /** Returns true when the script took over this model instance, even if it chose to draw nothing. */
    public boolean render(ModelRenderQueue queue, Object entity, VehiclePose vehiclePose,
                          float partialTick, PoseStack pose, int light) {
        if (failed) return false;
        updateWheel(entity, vehiclePose, partialTick);
        var context = new ScriptDrawContext(queue, source, pose, light, prepared.primaryFile() + " [" + role + "]");
        vehicleView.update(entity instanceof TrainEntity train ? train.tickCount : 0, light);
        renderer.context(context);
        renderer.wheelRotation(wheelDegrees());
        float doorLeft = 0.0f, doorRight = 0.0f;
        if (role == VehiclePartRole.BODY && entity instanceof TrainEntity train) {
            doorLeft = train.doorMovement(VehicleDoor.LEFT, partialTick);
            doorRight = train.doorMovement(VehicleDoor.RIGHT, partialTick);
        }
        renderer.doorMovement(doorLeft, doorRight);
        GL11.attach(context);
        try {
            RtmScript.invoke(engine, "render", entity == null ? null : vehicleView, 0, partialTick);
            context.submit();
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
            renderer.doorMovement(0.0f, 0.0f);
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
        engine.getBindings(ScriptContext.ENGINE_SCOPE).clear();
    }
}
