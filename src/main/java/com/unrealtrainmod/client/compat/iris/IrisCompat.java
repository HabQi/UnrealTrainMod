/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.unrealtrainmod.client.track.ClientTrackCache;
import com.unrealtrainmod.client.track.TrackWorldRenderer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import java.util.IdentityHashMap;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.slf4j.LoggerFactory;

/** The optional Iris boundary. No Iris classes are linked when the mod is absent. */
public final class IrisCompat {
    private static MethodHandle inUse, shadowPass, modelView, projection, frustum;
    private static MethodHandle wrapEntity, handActive, originalBuffer;
    private static Class<?> bufferWrapper;
    private static final IdentityHashMap<RenderType, RenderType> ENTITY_TYPES = new IdentityHashMap<>();
    private static final MethodHandle[] getIdentity = new MethodHandle[3], setIdentity = new MethodHandle[3];
    private static boolean initialized, active;
    private static VertexFormat entityFormat;

    private IrisCompat() {}

    private static void initialize() {
        initialized = true;
        if (!ModList.get().isLoaded("iris")) return;
        try {
            var lookup = MethodHandles.publicLookup();
            var apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            var api = apiClass.getMethod("getInstance").invoke(null);
            var renderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            entityFormat = (VertexFormat)Class.forName("net.irisshaders.iris.vertices.IrisVertexFormats").getField("ENTITY").get(null);
            inUse = lookup.unreflect(apiClass.getMethod("isShaderPackInUse")).bindTo(api);
            shadowPass = lookup.unreflect(apiClass.getMethod("isRenderingShadowPass")).bindTo(api);
            modelView = lookup.unreflectGetter(renderer.getField("MODELVIEW"));
            projection = lookup.unreflectGetter(renderer.getField("PROJECTION"));
            frustum = lookup.unreflectGetter(renderer.getField("FRUSTUM"));
            var capturedClass = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            var captured = capturedClass.getField("INSTANCE").get(null);
            String[] getters = {"getCurrentRenderedEntity", "getCurrentRenderedBlockEntity", "getCurrentRenderedItem"};
            String[] setters = {"setCurrentEntity", "setCurrentBlockEntity", "setCurrentRenderedItem"};
            for (int i = 0; i < 3; i++) {
                getIdentity[i] = lookup.unreflect(capturedClass.getMethod(getters[i])).bindTo(captured);
                setIdentity[i] = lookup.unreflect(capturedClass.getMethod(setters[i], int.class)).bindTo(captured);
            }
            var wrapper = Class.forName("net.irisshaders.iris.layer.OuterWrappedRenderType");
            bufferWrapper = Class.forName("net.irisshaders.iris.layer.BufferSourceWrapper");
            originalBuffer = lookup.unreflect(bufferWrapper.getMethod("getOriginal"));
            var handClass = Class.forName("net.irisshaders.iris.pathways.HandRenderer");
            handActive = lookup.unreflect(handClass.getMethod("isActive")).bindTo(handClass.getField("INSTANCE").get(null));
            var shard = Class.forName("net.irisshaders.iris.layer.EntityRenderStateShard").getField("INSTANCE").get(null);
            wrapEntity = MethodHandles.insertArguments(lookup.unreflect(wrapper.getMethod("wrapExactlyOnce",
                    String.class, RenderType.class, RenderStateShard.class)), 2, shard).bindTo("utm:cached_entity");
        } catch (ReflectiveOperationException exception) {
            inUse = null;
            LoggerFactory.getLogger(IrisCompat.class).error("Cannot connect to the installed Iris API", exception);
        }
    }

    public static boolean active() { return active; }

    public static boolean handPass() {
        if (!active) return false;
        try { return (boolean)handActive.invoke(); }
        catch (Throwable exception) { throw new IllegalStateException("Cannot read Iris hand state", exception); }
    }

    /** Only unwrap Iris's known forwarding layer; retain the underlying source's own flush policy. */
    static MultiBufferSource.BufferSource itemBuffer(MultiBufferSource buffers) {
        try {
            while (bufferWrapper.isInstance(buffers)) buffers = (MultiBufferSource)originalBuffer.invoke(buffers);
        } catch (Throwable exception) { throw new IllegalStateException("Cannot unwrap Iris item buffers", exception); }
        return buffers instanceof MultiBufferSource.BufferSource source ? source : null;
    }

    static void flushItemBuffer(MultiBufferSource.BufferSource source) {
        // Deferred batches can change CapturedRenderingState while they are submitted.
        int[] identity = currentIdentity();
        try { source.endBatch(); }
        finally { restoreTrackIdentity(identity); }
    }

    /** Cached entity geometry must not overtake vertices already queued by an entity renderer. */
    public static void flushEntityBuffers(MultiBufferSource buffers) {
        var source = itemBuffer(buffers);
        if (source != null) flushItemBuffer(source);
    }

    static int entityVertexSize() { return entityFormat.getVertexSize(); }

    public static int[] currentIdentity() {
        int[] previous = new int[3];
        currentIdentity(previous);
        return previous;
    }

    static void currentIdentity(int[] target) {
        try {
            for (int i = 0; i < 3; i++) target[i] = (int) getIdentity[i].invoke();
        } catch (Throwable exception) { throw new IllegalStateException("Cannot read Iris entity context", exception); }
    }

    static RenderType entityType(RenderType type) {
        return ENTITY_TYPES.computeIfAbsent(type, value -> {
            try { return (RenderType) wrapEntity.invoke(value); }
            catch (Throwable exception) { throw new IllegalStateException("Cannot wrap Iris entity draw", exception); }
        });
    }

    static void clearTypes() { ENTITY_TYPES.clear(); }

    public static void restoreTrackIdentity(int[] previous) {
        try {
            for (int i = 0; i < 3; i++) setIdentity[i].invoke(previous[i]);
        } catch (Throwable exception) { throw new IllegalStateException("Cannot restore Iris entity context", exception); }
    }

    public static boolean shadowPass() {
        if (!active) return false;
        try { return (boolean) shadowPass.invoke(); }
        catch (Throwable exception) { throw new IllegalStateException("Cannot read Iris shadow state", exception); }
    }

    public static void onFramePre(RenderFrameEvent.Pre event) {
        com.unrealtrainmod.client.GpuResidency.beginFrame();
        if (!initialized) initialize();
        boolean next = false;
        if (inUse != null) {
            try { next = (boolean) inUse.invoke(); }
            catch (Throwable exception) { throw new IllegalStateException("Cannot read Iris shader state", exception); }
        }
        if (next != active) {
            if (!next) {
                com.unrealtrainmod.client.texture.MaterialTexture.releasePbr();
                IrisMaterials.clear();
            } else com.unrealtrainmod.client.texture.MaterialTexture.invalidatePbr();
            com.unrealtrainmod.client.wiring.WiringRenderCache.clear();
            active = next;
            ClientTrackCache.INSTANCE.invalidateGpuBuffers();
            com.unrealtrainmod.client.renderer.TrainModelRenderer.clearWorld();
            com.unrealtrainmod.client.renderer.TrainModelRenderer.resetStatistics();
            IrisModelRenderer.clear();
            LoggerFactory.getLogger(IrisCompat.class).info("World rendering backend: {}", active ? "Iris" : "native");
        }
        IrisVehicleReprojection.beginFrame();
        IrisGeometryCache.beginFrame();
        if (active) com.unrealtrainmod.client.ClientModelManager.INSTANCE.prewarmIris();
        IrisRenderStats.beginFrame();
        TrackWorldRenderer.beginFrame();
        com.unrealtrainmod.client.wiring.WiringRenderCache.beginFrame();
        com.unrealtrainmod.client.texture.PbrLoadQueue.beginFrame(active);
    }

    public static void onFramePost(RenderFrameEvent.Post event) {
        if (active && Minecraft.getInstance().level != null) IrisGeometryCache.endFrame();
        com.unrealtrainmod.client.wiring.WiringRenderCache.endFrame();
        com.unrealtrainmod.client.GpuResidency.endFrame();
        com.unrealtrainmod.client.texture.PbrStats.logIfDue();
    }

    /** Called by the optional mixin before Iris copies the opaque shadow depth. */
    public static void renderTrackShadows() {
        if (!active) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        try {
            var event = new RenderLevelStageEvent(RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS,
                    minecraft.levelRenderer, new com.mojang.blaze3d.vertex.PoseStack(),
                    new Matrix4f((Matrix4f) modelView.invoke()), new Matrix4f((Matrix4f) projection.invoke()),
                    0, minecraft.getTimer(), minecraft.gameRenderer.getMainCamera(), (Frustum) frustum.invoke());
            TrackWorldRenderer.renderShadow(event);
            com.unrealtrainmod.client.track.TurnoutConstructionRenderer.renderShadow(event);
            com.unrealtrainmod.client.wiring.WiringClient.renderShadow(event);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new IllegalStateException("Cannot read Iris shadow view", exception);
        }
    }
}
