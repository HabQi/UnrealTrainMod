/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.IntSupplier;

/** Draw-time light-layer marker, uploaded by Iris rather than direct writes to its shader programs. */
public final class IrisEmissivePass {
    private static MethodHandle register;
    private static Object notifier;
    private static Runnable listener;
    private static boolean drawing;

    private IrisEmissivePass() {}

    static void setDrawing(boolean value) {
        if (drawing == value) return;
        drawing = value;
        if (listener != null) listener.run();
    }

    public static void registerUniforms(Object holder) {
        try {
            if (register == null) {
                var lookup = MethodHandles.lookup();
                var holderClass = Class.forName("net.irisshaders.iris.gl.uniform.DynamicUniformHolder");
                var notifierClass = Class.forName("net.irisshaders.iris.gl.state.ValueUpdateNotifier");
                notifier = MethodHandleProxies.asInterfaceInstance(notifierClass,
                        lookup.findStatic(IrisEmissivePass.class, "setListener",
                                MethodType.methodType(void.class, Runnable.class)));
                register = lookup.unreflect(holderClass.getMethod("uniform1i",
                        String.class, IntSupplier.class, notifierClass));
            }
            register.invoke(holder, "rtmEmissivePass", (IntSupplier) () -> drawing ? 1 : 0, notifier);
        } catch (Throwable exception) {
            throw new IllegalStateException("Cannot register UTM light-layer uniform with Iris", exception);
        }
    }

    private static void setListener(Runnable value) { listener = value; }
}
