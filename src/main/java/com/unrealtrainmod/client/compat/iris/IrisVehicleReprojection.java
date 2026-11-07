/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.entity.TrainEntity;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;

/** Two stable rigid-body history slots for the cars nearest the camera. Iris owns uniform uploads. */
public final class IrisVehicleReprojection {
    private static final class History {
        private TrainEntity vehicle;
        private final Matrix4f lastView = new Matrix4f(), viewToPrevious = new Matrix4f();
        private final float[] values = new float[16];
        private long sampledFrame = -2;
        private boolean valid;

        private void assign(TrainEntity next) {
            if (vehicle == next) return;
            vehicle = next;
            sampledFrame = -2;
            valid = false;
            viewToPrevious.identity();
        }

        private void capture(PoseStack pose) {
            if (sampledFrame == frame) return;
            var view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
            if (sampledFrame == frame - 1) {
                viewToPrevious.set(view).invert();
                lastView.mul(viewToPrevious, viewToPrevious);
                valid = viewToPrevious.isFinite();
                if (!valid) viewToPrevious.identity();
            }
            lastView.set(view);
            sampledFrame = frame;
        }
    }

    private static final History[] CARS = {new History(), new History()};
    private static ClientLevel level;
    private static Entity cameraEntity;
    private static CameraType cameraType;
    private static long frame;
    private static boolean selected;
    private static int drawing;
    private static MethodHandle registerMarker, registerMatrix;
    private static Object markerNotifier, perFrame;
    private static Runnable markerListener;

    private IrisVehicleReprojection() {}

    static void beginFrame() {
        var minecraft = Minecraft.getInstance();
        if (!IrisCompat.active() || minecraft.level == null || minecraft.getCameraEntity() == null) {
            reset();
            return;
        }
        frame++;
        selected = false;
        setDrawing(0);
        for (var car : CARS) {
            car.valid = false;
            car.viewToPrevious.identity();
        }
    }

    /** Select after camera setup, once per main view. Keep retained cars in their existing slots. */
    private static void selectTargets() {
        var minecraft = Minecraft.getInstance();
        var nextCamera = minecraft.options.getCameraType();
        if (level != minecraft.level || cameraEntity != minecraft.getCameraEntity() || cameraType != nextCamera) {
            for (var car : CARS) car.assign(null);
            level = minecraft.level;
            cameraEntity = minecraft.getCameraEntity();
            cameraType = nextCamera;
        }
        var position = minecraft.gameRenderer.getMainCamera().getPosition();
        TrainEntity first = null, second = null;
        double firstDistance = Double.POSITIVE_INFINITY, secondDistance = Double.POSITIVE_INFINITY;
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof TrainEntity train) || train.isRemoved()) continue;
            double distance = train.getBoundingBox().distanceToSqr(position);
            if (distance < firstDistance || distance == firstDistance && (first == null || train.getId() < first.getId())) {
                second = first;
                secondDistance = firstDistance;
                first = train;
                firstDistance = distance;
            } else if (distance < secondDistance || distance == secondDistance && (second == null || train.getId() < second.getId())) {
                second = train;
                secondDistance = distance;
            }
        }
        for (var car : CARS) if (car.vehicle != first && car.vehicle != second) car.assign(null);
        assignTarget(first);
        assignTarget(second);
        selected = true;
    }

    private static void assignTarget(TrainEntity train) {
        if (train == null) return;
        for (var car : CARS) if (car.vehicle == train) return;
        for (var car : CARS) if (car.vehicle == null) {
            car.assign(train);
            return;
        }
    }

    /** The pose contains the body transform, before per-part script transforms are applied. */
    static int capture(TrainEntity train, PoseStack pose, boolean shadow) {
        if (shadow || train == null) return 0;
        if (!selected) selectTargets();
        for (int i = 0; i < CARS.length; i++) {
            var car = CARS[i];
            if (car.vehicle != train) continue;
            car.capture(pose);
            return car.valid ? i + 1 : 0;
        }
        return 0;
    }

    /** Called by RenderType setup/clear, so buffered vertices carry their draw-time marker. */
    static void setDrawing(int slot) {
        int next = slot != 0 && CARS[slot - 1].valid && !IrisCompat.shadowPass() ? slot : 0;
        if (drawing == next) return;
        drawing = next;
        if (markerListener != null) markerListener.run();
    }

    public static void reset() {
        for (var car : CARS) car.assign(null);
        level = null;
        cameraEntity = null;
        cameraType = null;
        frame = 0;
        selected = false;
        drawing = 0;
        markerListener = null;
    }

    /** Invoked while Iris builds uniforms, before IrisCompat's first frame initialization. */
    public static void registerUniforms(Object holder) {
        try {
            if (registerMarker == null) {
                var lookup = MethodHandles.lookup();
                var holderClass = Class.forName("net.irisshaders.iris.gl.uniform.DynamicUniformHolder");
                var notifierClass = Class.forName("net.irisshaders.iris.gl.state.ValueUpdateNotifier");
                var frequencyClass = Class.forName("net.irisshaders.iris.gl.uniform.UniformUpdateFrequency");
                markerNotifier = MethodHandleProxies.asInterfaceInstance(notifierClass,
                        lookup.findStatic(IrisVehicleReprojection.class, "setMarkerListener",
                                MethodType.methodType(void.class, Runnable.class)));
                perFrame = frequencyClass.getField("PER_FRAME").get(null);
                registerMarker = lookup.unreflect(holderClass.getMethod("uniform1i",
                        String.class, IntSupplier.class, notifierClass));
                registerMatrix = lookup.unreflect(holderClass.getMethod("uniformMatrixFromArray",
                        frequencyClass, String.class, Supplier.class));
            }
            registerMarker.invoke(holder, "rtmTemporalEntity", (IntSupplier) () -> drawing, markerNotifier);
            // Deferred/TAA first consume this after the car's main-view capture. The array uniform
            // also uploads an identity matrix on first use, rather than treating it as already cached.
            registerMatrix.invoke(holder, perFrame, "rtmViewToPreviousView",
                    (Supplier<float[]>) () -> CARS[0].viewToPrevious.get(CARS[0].values));
            registerMatrix.invoke(holder, perFrame, "rtmViewToPreviousView2",
                    (Supplier<float[]>) () -> CARS[1].viewToPrevious.get(CARS[1].values));
        } catch (Throwable exception) {
            throw new IllegalStateException("Cannot register UTM vehicle reprojection uniforms with Iris", exception);
        }
    }

    private static void setMarkerListener(Runnable listener) { markerListener = listener; }
}
