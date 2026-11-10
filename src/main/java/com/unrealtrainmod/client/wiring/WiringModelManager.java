/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.mojang.blaze3d.platform.NativeImage;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.model.TextureLoader;
import com.unrealtrainmod.client.texture.MaterialTexture;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.ResourceIds;
import com.unrealtrainmod.wiring.WiringCatalog;
import com.unrealtrainmod.wiring.WiringDefinition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.LoggerFactory;

public final class WiringModelManager extends SimplePreparableReloadListener<WiringModelManager.Prepared> {
    public static final WiringModelManager INSTANCE = new WiringModelManager();
    public record Model(WiringDefinition definition, List<WiringGeometry.Batch> batches,
                        WiringGeometry.Material firstTexture, List<WiringGeometry.Draw> draws) {
        public Model(WiringDefinition definition, List<WiringGeometry.Batch> batches, WiringGeometry.Material firstTexture) {
            this(definition, batches, firstTexture, WiringGeometry.identity(batches));
        }
    }
    record Prepared(Map<ResourceLocation, Model> models, Map<ResourceLocation, NativeImage> images) {}
    private record MeshKey(PackageAsset model, boolean smoothing) {}
    private final Map<ResourceLocation, Model> models = new HashMap<>();
    private final java.util.Set<ResourceLocation> textures = new java.util.HashSet<>();
    private WiringModelManager() {}
    public Model model(ResourceLocation id) { return models.get(id); }

    @Override protected Prepared prepare(ResourceManager resources, ProfilerFiller profiler) {
        var result = new HashMap<ResourceLocation, Model>(); var images = new HashMap<ResourceLocation, NativeImage>();
        var loadedImages = new HashMap<PackageAsset, ResourceLocation>(); var meshes = new HashMap<MeshKey, ModelMesh>();
        try {
            for (boolean wire : new boolean[]{false, true}) for (var definition : WiringCatalog.all(wire)) {
                try {
                    var bindings = new HashMap<String, WiringGeometry.Material>();
                    for (var entry : definition.textures().entrySet()) {
                        var configured = entry.getValue(); var asset = configured.asset(); var image = loadedImages.get(asset);
                        if (image == null) {
                            var pixels = TextureLoader.readBase(asset);
                            image = ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID,
                                    "textures/wiring/" + ResourceIds.hash(asset.toString()));
                            loadedImages.put(asset, image); images.put(image, pixels);
                        }
                        // Match the existing RTM material rules. Shared textures may contain unrelated glass texels;
                        // those texels must not disable depth writes for an opaque pole or bridge pier.
                        bindings.put(entry.getKey(), new WiringGeometry.Material(image, configured.transparent(),
                                !definition.culling(), configured.light()));
                    }
                    var white = new WiringGeometry.Material(TextureLoader.WHITE, false, !definition.culling(), false);
                    var first = definition.textures().isEmpty() ? white
                            : bindings.get(definition.textures().keySet().iterator().next());
                    var batches = new ArrayList<WiringGeometry.Batch>();
                    // Legal empty connector/script models bypass mesh preparation only in this subsystem.
                    if (!definition.empty()) {
                        var key = new MeshKey(definition.model(), definition.smoothing()); var mesh = meshes.get(key);
                        if (mesh == null) {
                            mesh = ModelMesh.prepare(definition.geometry(), .01f, definition.smoothing()); meshes.put(key, mesh);
                        }
                        for (var part : mesh.parts()) if (part.visible()) for (var batch : part.batches()) {
                            var material = bindings.getOrDefault(batch.material().name(), bindings.getOrDefault("default", white));
                            if (batch.material().doubleSided() && !material.doubleSided()) material = new WiringGeometry.Material(
                                    material.texture(), material.translucent(), true, material.light());
                            boolean reducedAlpha = batch.material().alpha() < 1
                                    || java.util.Arrays.stream(batch.mesh().colors()).anyMatch(color -> (color >>> 24) < 255);
                            if (reducedAlpha && !material.translucent()) material = new WiringGeometry.Material(
                                    material.texture(), true, material.doubleSided(), material.light());
                            batches.add(new WiringGeometry.Batch(part.name(), material, batch.mesh()));
                        }
                    }
                    result.put(definition.id(), new Model(definition, List.copyOf(batches), first));
                } catch (IOException | RuntimeException exception) {
                    LoggerFactory.getLogger(WiringModelManager.class).warn("Unable to prepare wiring model {}: {}",
                            definition.config(), exception.getMessage());
                }
            }
            return new Prepared(result, images);
        } catch (RuntimeException | Error e) { images.values().forEach(NativeImage::close); throw e; }
    }

    @Override protected void apply(Prepared prepared, ResourceManager resources, ProfilerFiller profiler) {
        var pending = new HashMap<>(prepared.images);
        try {
            close();
            for (var entry : prepared.images.entrySet()) {
                var pixels = pending.remove(entry.getKey()); MaterialTexture texture;
                try { texture = new MaterialTexture(pixels); }
                catch (RuntimeException | Error e) { pixels.close(); throw e; }
                try { Minecraft.getInstance().getTextureManager().register(entry.getKey(), texture); }
                catch (RuntimeException | Error e) { texture.close(); throw e; }
                textures.add(entry.getKey());
            }
            models.putAll(prepared.models);
        } catch (RuntimeException | Error e) { close(); throw e; }
        finally { pending.values().forEach(NativeImage::close); }
    }
    public void close() {
        WiringClient.clearRuntimes();
        WiringRenderCache.clear();
        com.unrealtrainmod.wiring.WiringScriptPolicy.clear();
        com.unrealtrainmod.client.blueprint.BlueprintClient.clear();
        var manager = Minecraft.getInstance().getTextureManager(); textures.forEach(manager::release);
        textures.clear(); models.clear();
    }
}
