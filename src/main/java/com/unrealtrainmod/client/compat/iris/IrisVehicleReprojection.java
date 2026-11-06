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
import org.joml.Matrix4f;

/** Rigid-body history for the local passenger's car; Iris owns all uniform uploads. */
public final class IrisVehicleReprojection {
    private static final Matrix4f VIEW_TO_PREVIOUS = new Matrix4f();
    private static final float[] MATRIX_VALUES = new float[16];
    private static TrainEntity vehicle;
    private static CameraType cameraType;
    private static Matrix4f lastView;
    private static long frame, sampledFrame = -2;
    private static boolean valid, drawing;
    private static MethodHandle registerMarker, registerMatrix;
    private static Object markerNotifier, perFrame;
    private static Runnable markerListener;

    private IrisVehicleReprojection() {}

    static void beginFrame() {
        var minecraft = Minecraft.getInstance();
        if (!IrisCompat.active() || minecraft.level == null || minecraft.player == null
                || minecraft.getCameraEntity() != minecraft.player
                || !(minecraft.player.getVehicle() instanceof TrainEntity train) || train.isRemoved()) {
            reset();
            return;
        }
        var nextCamera = minecraft.options.getCameraType();
        if (vehicle != train || cameraType != nextCamera) {
            reset();
            vehicle = train;
            cameraType = nextCamera;
        }
        frame++;
        valid = drawing = false;
        VIEW_TO_PREVIOUS.identity();
    }

    /** The pose contains the body transform, before per-part script transforms are applied. */
    static boolean capture(TrainEntity train, PoseStack pose, boolean shadow) {
        if (shadow || train == null || train != vehicle) return false;
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.getVehicle() != train
                || minecraft.getCameraEntity() != minecraft.player) return false;
        // Input can change camera mode after RenderFrameEvent.Pre, before world drawing.
        var nextCamera = minecraft.options.getCameraType();
        if (cameraType != nextCamera) {
            cameraType = nextCamera;
            lastView = null;
            valid = false;
        }
        if (sampledFrame == frame) return valid;
        var view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
        if (lastView != null && sampledFrame == frame - 1) {
            VIEW_TO_PREVIOUS.set(view).invert();
            lastView.mul(VIEW_TO_PREVIOUS, VIEW_TO_PREVIOUS);
            valid = VIEW_TO_PREVIOUS.isFinite();
            if (!valid) VIEW_TO_PREVIOUS.identity();
        }
        lastView = view;
        sampledFrame = frame;
        return valid;
    }

    /** Called by RenderType setup/clear, so buffered vertices carry their draw-time marker. */
    static void setDrawing(boolean value) {
        boolean next = value && valid && !IrisCompat.shadowPass();
        if (drawing == next) return;
        drawing = next;
        if (markerListener != null) markerListener.run();
    }

    public static void reset() {
        vehicle = null;
        cameraType = null;
        lastView = null;
        frame = 0;
        sampledFrame = -2;
        valid = drawing = false;
        VIEW_TO_PREVIOUS.identity();
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
            registerMarker.invoke(holder, "rtmTemporalEntity", (IntSupplier) () -> drawing ? 1 : 0, markerNotifier);
            // Deferred/TAA first consume this after the car's main-view capture. The array uniform
            // also uploads an identity matrix on first use, rather than treating it as already cached.
            registerMatrix.invoke(holder, perFrame, "rtmViewToPreviousView",
                    (Supplier<float[]>) () -> VIEW_TO_PREVIOUS.get(MATRIX_VALUES));
        } catch (Throwable exception) {
            throw new IllegalStateException("Cannot register UTM vehicle reprojection uniforms with Iris", exception);
        }
    }

    private static void setMarkerListener(Runnable listener) { markerListener = listener; }
}
