/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.unrealtrainmod.client.texture.MaterialTexture;
import com.unrealtrainmod.client.texture.PbrSource;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.neoforged.fml.ModList;
import org.slf4j.LoggerFactory;

/** Only UTM's own texture class is registered. No Iris type appears in a linked signature. */
public final class IrisPbrBridge {
    private static MethodHandle acceptNormal, acceptSpecular, invalidate;
    private static int normalDefault, specularDefault;
    private static boolean initialized;
    private IrisPbrBridge() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        if (!ModList.get().isLoaded("iris")) return;
        try {
            var lookup = MethodHandles.publicLookup();
            var registryClass = Class.forName("net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry");
            var loaderClass = Class.forName("net.irisshaders.iris.pbr.loader.PBRTextureLoader");
            var consumerClass = Class.forName("net.irisshaders.iris.pbr.loader.PBRTextureLoader$PBRTextureConsumer");
            var typeClass = Class.forName("net.irisshaders.iris.pbr.texture.PBRType");
            var managerClass = Class.forName("net.irisshaders.iris.pbr.texture.PBRTextureManager");
            acceptNormal = lookup.unreflect(consumerClass.getMethod("acceptNormalTexture", AbstractTexture.class));
            acceptSpecular = lookup.unreflect(consumerClass.getMethod("acceptSpecularTexture", AbstractTexture.class));
            // Iris describes defaults as RRGGBBAA; NativeImage pixel integers are AABBGGRR.
            normalDefault = Integer.reverseBytes((int) typeClass.getMethod("getDefaultValue").invoke(typeClass.getField("NORMAL").get(null)));
            specularDefault = Integer.reverseBytes((int) typeClass.getMethod("getDefaultValue").invoke(typeClass.getField("SPECULAR").get(null)));
            invalidate = lookup.unreflect(managerClass.getMethod("onDeleteTexture", int.class)).bindTo(managerClass.getField("INSTANCE").get(null));
            var loader = Proxy.newProxyInstance(loaderClass.getClassLoader(), new Class<?>[] {loaderClass}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "toString" -> "UTM package PBR loader";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                };
                load((MaterialTexture)args[0], args[2]);
                return null;
            });
            lookup.unreflect(registryClass.getMethod("register", Class.class, loaderClass))
                    .bindTo(registryClass.getField("INSTANCE").get(null)).invoke(MaterialTexture.class, loader);
        } catch (Throwable exception) {
            invalidate = null;
            LoggerFactory.getLogger(IrisPbrBridge.class).error("Cannot register UTM Iris PBR loader; keeping base textures", exception);
        }
    }

    private static void load(MaterialTexture texture, Object consumer) throws Throwable {
        AbstractTexture normal = null, specular = null;
        try {
            normal = texture.auxiliary(PbrSource.Channel.NORMAL, normalDefault);
            specular = texture.auxiliary(PbrSource.Channel.SPECULAR, specularDefault);
            if (normal != null) acceptNormal.invoke(consumer, normal);
            if (specular != null) acceptSpecular.invoke(consumer, specular);
        } catch (Throwable exception) {
            if (normal != null) normal.close();
            if (specular != null) specular.close();
            throw exception;
        }
    }

    public static void invalidate(int id) {
        if (id < 0 || invalidate == null) return;
        try { invalidate.invoke(id); }
        catch (Throwable exception) { throw new IllegalStateException("Cannot invalidate UTM Iris PBR holder", exception); }
    }
}
