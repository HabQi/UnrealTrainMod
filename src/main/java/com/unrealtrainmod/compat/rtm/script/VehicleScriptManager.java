/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.renderer.ModelDrawSink;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import com.unrealtrainmod.vehicle.VehiclePose;
import java.io.IOException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns isolated globals per vehicle slot and per preview queue/source/slot, with one vehicle compilation cache.
 */
public final class VehicleScriptManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleScriptManager.class);
    private record Handle(LoadedModel.Source source, VehicleScriptRuntime runtime) {}

    private static final RtmScriptCache PROGRAMS = new RtmScriptCache();
    private static final LinkedHashSet<PackageAsset> SCRIPTS = new LinkedHashSet<>();
    private static final LinkedHashSet<PackageAsset> PREWARM = new LinkedHashSet<>();
    private static long prewarmCalls, prewarmNanos, prewarmFailures;
    private static final Map<TrainEntity, EnumMap<VehiclePartRole, Handle>> ENTITIES = new IdentityHashMap<>();
    private static final Map<ModelDrawSink, Map<LoadedModel.Source, EnumMap<VehiclePartRole, Handle>>> PREVIEWS =
            new IdentityHashMap<>();
    private static Level world;

    private VehicleScriptManager() {}

    public record PrewarmMetrics(long calls, long nanos, long failures) {}

    /** World statistics consume this once; previews never consume shared-program diagnostics. */
    public static PrewarmMetrics drainPrewarmMetrics() {
        var metrics = new PrewarmMetrics(prewarmCalls, prewarmNanos, prewarmFailures);
        prewarmCalls = prewarmNanos = prewarmFailures = 0;
        return metrics;
    }

    /** Called after a successful model apply. No resource script code is evaluated here. */
    public static void schedulePrewarm(Collection<LoadedModel> models) {
        for (var model : models) for (var instance : model.instances()) {
            var script = instance.definition().source().rendererPath();
            if (script != null) SCRIPTS.add(script);
        }
        refillPrewarm();
    }

    private static void refillPrewarm() {
        for (var script : SCRIPTS) {
            if (!PROGRAMS.isReady(script) && !PROGRAMS.isFailed(script)) PREWARM.add(script);
        }
    }

    /** Compilation is indivisible; start at most one shared program in each frame. */
    private static void prewarmOne() {
        if (PREWARM.isEmpty()) return;
        var iterator = PREWARM.iterator();
        var script = iterator.next();
        iterator.remove();
        long start = System.nanoTime();
        Throwable failure = null;
        try {
            PROGRAMS.program(script);
        } catch (IOException | RuntimeException | LinkageError exception) {
            failure = exception;
            prewarmFailures++;
        }
        long nanos = System.nanoTime() - start;
        prewarmCalls++;
        prewarmNanos += nanos;
        if (failure == null) {
            LOGGER.info("Vehicle script prewarm {}!{} completed in {}ms; pending={}",
                    script.archive(), script.entry(), nanos / 1_000_000.0, PREWARM.size());
        } else {
            LOGGER.warn("Cannot prewarm vehicle script {}!{} after {}ms; using static models: {}",
                    script.archive(), script.entry(), nanos / 1_000_000.0, failure.getMessage());
            LOGGER.debug("Vehicle script prewarm failure", failure);
        }
    }

    public static boolean render(ModelDrawSink queue, TrainEntity entity, VehiclePose vehiclePose,
                                 LoadedModel.Instance instance, PoseStack pose, int light, float partialTick) {
        var definition = instance.definition();
        var script = definition.source().rendererPath();
        if (script == null) return false;
        if (!PROGRAMS.isReady(script)) return false;
        var source = instance.model();
        var role = definition.role();
        var model = definition.source().model();
        Handle handle = entity == null
                ? preview(queue, source, role, script, model)
                : entityHandle(entity, source, role, script, model);
        if (handle.runtime() == null) return false;
        return handle.runtime().render(queue, entity, vehiclePose, partialTick, pose, light);
    }

    private static Handle preview(ModelDrawSink queue, LoadedModel.Source source, VehiclePartRole role,
                                   PackageAsset script, PackageAsset model) {
        var scene = PREVIEWS.computeIfAbsent(queue, ignored -> new IdentityHashMap<>());
        var handles = scene.computeIfAbsent(source, ignored -> new EnumMap<>(VehiclePartRole.class));
        var handle = handles.get(role);
        if (handle != null) return handle;
        var created = create(source, role, script, model, "preview " + script.entry());
        handles.put(role, created);
        return created;
    }

    private static Handle entityHandle(TrainEntity entity, LoadedModel.Source source, VehiclePartRole role,
                                       PackageAsset script, PackageAsset model) {
        if (world != entity.level()) {
            clearInstances();
            world = entity.level();
            refillPrewarm();
        }
        var handles = ENTITIES.computeIfAbsent(entity, ignored -> new EnumMap<>(VehiclePartRole.class));
        var handle = handles.get(role);
        if (handle != null && handle.source() == source) return handle;
        close(handle);
        var created = create(source, role, script, model, entity.getUUID() + " " + role);
        handles.put(role, created);
        return created;
    }

    private static Handle create(LoadedModel.Source source, VehiclePartRole role, PackageAsset script,
                                 PackageAsset model, String label) {
        try {
            return new Handle(source, VehicleScriptRuntime.create(PROGRAMS, script, model, source, role, label));
        } catch (IOException | RuntimeException | LinkageError exception) {
            LOGGER.warn("Cannot initialise vehicle script {}!{} for {}: {}", script.archive(), script.entry(), label,
                    exception.getMessage());
            LOGGER.debug("Vehicle script initialisation failure", exception);
            return new Handle(source, null);
        }
    }

    public static void sweep() {
        var iterator = ENTITIES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getKey().isRemoved()) continue;
            entry.getValue().values().forEach(VehicleScriptManager::close);
            iterator.remove();
        }
    }

    public static void onFramePre(RenderFrameEvent.Pre event) {
        var current = Minecraft.getInstance().level;
        if (world != current) {
            clearInstances();
            world = current;
            refillPrewarm();
        }
        sweep();
        prewarmOne();
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel && (world == null || event.getLevel() == world)) clearInstances();
    }

    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clearInstances(); }

    /** Model reload or game shutdown: discard instances and the complete vehicle compilation generation. */
    public static void clear() {
        clearInstances();
        PROGRAMS.clear();
        SCRIPTS.clear();
    }

    private static void clearInstances() {
        ENTITIES.values().forEach(handles -> handles.values().forEach(VehicleScriptManager::close));
        ENTITIES.clear();
        PREVIEWS.values().forEach(VehicleScriptManager::closeScene);
        PREVIEWS.clear();
        PREWARM.clear();
        prewarmCalls = prewarmNanos = prewarmFailures = 0;
        world = null;
    }

    /** Queue clear is per-frame; only queue close releases its persistent preview state. */
    public static void releasePreview(ModelDrawSink queue) {
        var scene = PREVIEWS.remove(queue);
        if (scene != null) closeScene(scene);
    }

    private static void closeScene(Map<LoadedModel.Source, EnumMap<VehiclePartRole, Handle>> scene) {
        scene.values().forEach(handles -> handles.values().forEach(VehicleScriptManager::close));
    }

    private static void close(Handle handle) {
        if (handle != null && handle.runtime() != null) handle.runtime().close();
    }
}
