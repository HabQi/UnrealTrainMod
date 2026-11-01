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
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import javax.script.ScriptException;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns script engines per vehicle slot and per preview source. Geometry stays shared; only script globals
 * and wheel state are isolated.
 */
public final class VehicleScriptManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleScriptManager.class);
    private record Handle(LoadedModel.Source source, VehicleScriptRuntime runtime) {}

    private static final Map<TrainEntity, EnumMap<VehiclePartRole, Handle>> ENTITIES = new WeakHashMap<>();
    private static final Map<LoadedModel.Source, EnumMap<VehiclePartRole, Handle>> PREVIEWS = new IdentityHashMap<>();
    private static Level world;

    private VehicleScriptManager() {}

    public static boolean render(ModelRenderQueue queue, TrainEntity entity, VehiclePose vehiclePose,
                                 LoadedModel.Instance instance, PoseStack pose, int light, float partialTick) {
        var definition = instance.definition();
        var script = definition.source().rendererPath();
        if (script == null) return false;
        var source = instance.model();
        var role = definition.role();
        Handle handle = entity == null
                ? preview(source, role, script)
                : entityHandle(entity, source, role, script);
        if (handle == null || handle.runtime() == null) return false;
        return handle.runtime().render(queue, entity, vehiclePose, partialTick, pose, light);
    }

    private static Handle preview(LoadedModel.Source source, VehiclePartRole role, PackageAsset script) {
        var handles = PREVIEWS.computeIfAbsent(source, ignored -> new EnumMap<>(VehiclePartRole.class));
        var handle = handles.get(role);
        if (handle != null && handle.source() == source) return handle;
        close(handle);
        var created = create(source, role, script, "preview " + script.entry());
        handles.put(role, created);
        return created;
    }

    private static Handle entityHandle(TrainEntity entity, LoadedModel.Source source, VehiclePartRole role, PackageAsset script) {
        if (world != entity.level()) {
            clear();
            world = entity.level();
        }
        var handles = ENTITIES.computeIfAbsent(entity, ignored -> new EnumMap<>(VehiclePartRole.class));
        var handle = handles.get(role);
        if (handle != null && handle.source() == source) return handle;
        close(handle);
        var created = create(source, role, script, entity.getUUID() + " " + role);
        handles.put(role, created);
        return created;
    }

    private static Handle create(LoadedModel.Source source, VehiclePartRole role, PackageAsset script, String label) {
        try {
            return new Handle(source, VehicleScriptRuntime.create(script, source, role, label));
        } catch (IOException | ScriptException | NoSuchMethodException | RuntimeException | LinkageError exception) {
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

    public static void onFramePre(RenderFrameEvent.Pre event) { sweep(); }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (world == null || event.getLevel() == world) clear();
    }

    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    public static void clear() {
        ENTITIES.values().forEach(handles -> handles.values().forEach(VehicleScriptManager::close));
        ENTITIES.clear();
        PREVIEWS.values().forEach(handles -> handles.values().forEach(VehicleScriptManager::close));
        PREVIEWS.clear();
        world = null;
    }

    private static void close(Handle handle) {
        if (handle != null && handle.runtime() != null) handle.runtime().close();
    }
}
