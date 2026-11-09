/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.renderer.RenderBatch;
import com.unrealtrainmod.client.RenderCategory;
import com.unrealtrainmod.compat.ir.IrAnimatrix;
import com.unrealtrainmod.compat.ir.IrControlDefinition;
import com.unrealtrainmod.compat.ir.IrLightingDefinition;
import com.unrealtrainmod.compat.ir.IrModelComponent;
import com.unrealtrainmod.compat.ir.IrModelComponents;
import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import com.unrealtrainmod.model.ModelSource;
import com.unrealtrainmod.model.MaterialRenderMode;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.cache.ModelCache;
import com.unrealtrainmod.resource.cache.ModelCacheCodecs;
import com.unrealtrainmod.vehicle.VehiclePartRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Compiles object-level chains once, so frame rendering neither scans names nor duplicates animated objects. */
final class IrModelPreparer {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrModelPreparer.class);
    private record Resource(PackageAsset asset, float scale) {}
    private record Missing(ModelSource source, Resource resource, int track) {}
    private record Selection(VehiclePartRole role, IrModelComponent component, List<IrAnimationBinding> animations, IrLightingDefinition.Rule lighting) {}
    private record PackingKey(List<ModelPart> parts, boolean ordered) {}
    private record MaterialState(ResourceLocation texture, MaterialRenderMode mode, boolean doubleSided) {}
    private record VertexMaterial(float[] vertices, int[] colors, MaterialState material) {}

    private static final class PackingStats {
        int staticBefore, staticAfter, movingBefore, movingAfter;
        int staticMixed, movingMixed, staticBlend, movingBlend;
        int staticCutoutBuckets, movingCutoutBuckets;
        int componentOnly, animationOnly, combined, wheels;
        long trianglesBefore, trianglesAfter, newIndexBytes;
        final Set<MaterialState> materials = new HashSet<>();
        final Map<Integer, Integer> movingOpaqueHistogram = new TreeMap<>();

        void add(PartSelection before, PartSelection after, boolean moving) {
            int beforeCount = 0, afterCount = 0;
            for (var batch : before.batches()) if (batch.category() == RenderCategory.CUTOUT) {
                beforeCount++;
                trianglesBefore += batch.indexCount() / 3;
            }
            int mixed = 0, blend = 0;
            var buckets = new HashSet<VertexMaterial>();
            for (var batch : after.batches()) {
                if (batch.category() == RenderCategory.CUTOUT) {
                    afterCount++;
                    trianglesAfter += batch.indexCount() / 3;
                    var material = new MaterialState(batch.texture(), batch.mode(), batch.doubleSided());
                    materials.add(material);
                    buckets.add(new VertexMaterial(batch.mesh().vertices(), batch.mesh().colors(), material));
                } else if (batch.category() == RenderCategory.MIXED) mixed++;
                else blend++;
            }
            if (moving) {
                movingBefore += beforeCount;
                movingAfter += afterCount;
                movingMixed += mixed;
                movingBlend += blend;
                movingCutoutBuckets += buckets.size();
                movingOpaqueHistogram.merge(afterCount + mixed, 1, Integer::sum);
            } else {
                staticBefore += beforeCount;
                staticAfter += afterCount;
                staticMixed += mixed;
                staticBlend += blend;
                staticCutoutBuckets += buckets.size();
            }
        }
    }
    private final ModelCache cache;
    private final TexturePool textures;
    private final Map<Resource, IrAnimatrix> resources = new HashMap<>();
    private final Set<Missing> missing = new HashSet<>();

    IrModelPreparer(ModelCache cache, TexturePool textures) { this.cache = cache; this.textures = textures; }

    Map<IrVehicleDefinition, IrModelBinding> prepare(ModelSource source, List<ModelPart> parts,
            List<IrModelComponent> components, Set<IrVehicleDefinition> definitions, List<RenderBatch> batches) {
        var namedComponents = new HashMap<String, IrModelComponent>();
        for (var component : components) for (String name : component.objects()) namedComponents.put(name, component);
        var visibleNames = new HashSet<String>();
        for (var part : parts) if (part.visible()) visibleNames.add(part.name());
        var pickBounds = new HashMap<ModelPart, net.minecraft.world.phys.AABB>();
        for (var part : parts) if (part.visible() && !part.batches().isEmpty()) pickBounds.put(part, IrPickPart.bounds(part));
        var controlNames = new LinkedHashMap<String, List<String>>();
        for (var part : parts) {
            String key = IrModelComponents.controlKey(part.name());
            if (key != null) controlNames.computeIfAbsent(key, ignored -> new ArrayList<>()).add(part.name());
        }
        var result = new HashMap<IrVehicleDefinition, IrModelBinding>();
        // Shared configurations reuse the same packed index arrays; the cache ends with this source preparation.
        var packedSelections = new HashMap<PackingKey, PartSelection.Packed>();
        int bindingIndex = 0;
        for (var definition : definitions) {
            var stats = new PackingStats();
            var frames = new HashMap<Integer, IrAnimatrix>();
            var chains = new HashMap<String, List<IrAnimationBinding>>();
            for (int i = 0; i < definition.animations().size(); i++) {
                var animation = definition.animations().get(i);
                var resource = new Resource(animation.asset(), source.scale());
                if (!resources.containsKey(resource)) {
                    try {
                        resources.put(resource, cache.load(animation.asset(), "animation", "srt:" + Float.floatToIntBits(source.scale()),
                                ModelCacheCodecs.IR_ANIMATION, () -> IrAnimatrix.read(animation.asset(), source.scale())));
                    } catch (IOException | RuntimeException exception) {
                        LOGGER.warn("IR {}: animation {} cannot be loaded: {}", source.model(), animation.asset(), exception.getMessage());
                        resources.put(resource, null);
                    }
                }
                var data = resources.get(resource);
                if (data == null) continue;
                frames.put(i, data);
                var matched = new boolean[data.tracks().size()];
                for (var group : data.groups().entrySet()) {
                    if (!visibleNames.contains(group.getKey())) continue;
                    chains.computeIfAbsent(group.getKey(), ignored -> new ArrayList<>()).add(new IrAnimationBinding(i, data, group.getValue()));
                    matched[group.getValue()] = true;
                }
                for (int track = 0; track < matched.length; track++) if (!matched[track] && missing.add(new Missing(source, resource, track))) {
                    int index = track;
                    String name = data.groups().entrySet().stream().filter(entry -> entry.getValue() == index)
                            .map(Map.Entry::getKey).findFirst().orElseThrow();
                    LOGGER.warn("IR {}: animation {} track '{}' has no matching visible OBJ group", source.model(), animation.asset(), name);
                }
            }
            var staticParts = new EnumMap<VehiclePartRole, List<ModelPart>>(VehiclePartRole.class);
            var selected = new LinkedHashMap<Selection, List<ModelPart>>();
            for (var part : parts) {
                if (!part.visible()) continue;
                var component = namedComponents.get(part.name());
                if (component != null && component.kind() != IrModelComponent.Kind.WHEEL && !component.hide()
                        && component.degrees() == 0 && component.translation().x() == 0
                        && component.translation().y() == 0 && component.translation().z() == 0) component = null;
                var chain = chains.getOrDefault(part.name(), List.of());
                var role = component == null ? IrModelComponents.role(part.name()) : component.role();
                var lighting = definition.lighting().objects().get(part.name());
                if (component == null && chain.isEmpty() && lighting == null) staticParts.computeIfAbsent(role, ignored -> new ArrayList<>()).add(part);
                else selected.computeIfAbsent(new Selection(role, component, List.copyOf(chain), lighting), ignored -> new ArrayList<>()).add(part);
            }
            var staticSelections = new EnumMap<VehiclePartRole, PartSelection>(VehiclePartRole.class);
            var moving = new EnumMap<VehiclePartRole, List<IrModelPart>>(VehiclePartRole.class);
            for (var role : VehiclePartRole.values()) {
                var selection = pack(staticParts.getOrDefault(role, List.of()), false, packedSelections, stats);
                staticSelections.put(role, selection);
                batches.addAll(selection.batches());
                moving.put(role, new ArrayList<>());
            }
            selected.forEach((key, objects) -> {
                var selection = pack(objects, true, packedSelections, stats);
                batches.addAll(selection.batches());
                ResourceLocation flareTexture = null;
                if (key.lighting() != null && key.lighting().flare() != null && key.lighting().flare().enabled()) {
                    try { flareTexture = textures.flare(key.lighting().flare().texture()); }
                    catch (IOException | RuntimeException exception) {
                        LOGGER.warn("IR {}: disabling flare texture {}: {}", source.model(),
                                key.lighting().flare().texture(), exception.getMessage());
                    }
                }
                moving.get(key.role()).add(new IrModelPart(key.component(), selection, key.animations(), key.lighting(), flareTexture));
                if (key.component() == null) stats.animationOnly++;
                else if (key.animations().isEmpty()) stats.componentOnly++;
                else stats.combined++;
                if (key.component() != null && key.component().kind() == IrModelComponent.Kind.WHEEL) stats.wheels++;
            });
            moving.replaceAll((role, values) -> List.copyOf(values));
            var motions = new HashMap<ModelPart, IrModelPart>();
            moving.values().forEach(values -> values.forEach(value -> value.selection().parts().forEach(part -> motions.put(part, value))));
            var picking = new EnumMap<VehiclePartRole, List<IrPickPart>>(VehiclePartRole.class);
            for (var role : VehiclePartRole.values()) picking.put(role, new ArrayList<>());
            for (var part : parts) {
                if (!pickBounds.containsKey(part)) continue;
                String key = IrModelComponents.controlKey(part.name()), group = null;
                IrControlDefinition.Type type = null;
                if (key != null) {
                    var names = controlNames.get(key);
                    if (names.stream().noneMatch(name -> IrModelComponents.token(name, "NOINTERACT"))) {
                        group = names.stream().sorted().map(name -> IrModelComponents.attribute(name, "CG"))
                                .filter(java.util.Objects::nonNull).findFirst().orElse(key);
                        type = names.stream().anyMatch(name -> IrModelComponents.token(name, "PRESS")) ? IrControlDefinition.Type.PRESS
                                : names.stream().anyMatch(name -> IrModelComponents.token(name, "TOGGLE")) ? IrControlDefinition.Type.TOGGLE
                                : IrControlDefinition.Type.CONTINUOUS;
                    }
                }
                picking.get(IrModelComponents.role(part.name())).add(new IrPickPart(part, pickBounds.get(part), motions.get(part), group, type));
            }
            picking.replaceAll((role, values) -> List.copyOf(values));
            result.put(definition, new IrModelBinding(Map.copyOf(staticSelections), Map.copyOf(moving), Map.copyOf(frames), Map.copyOf(picking)));
            LOGGER.info("IR batching {} binding={}: staticCutout {} -> {}, animatedCutout {} -> {}; "
                            + "cutoutTriangles {} -> {}; cutoutStates={} animatedGroups={} newIndexBytes={} extraVertexBytes=0",
                    source.model(), ++bindingIndex, stats.staticBefore, stats.staticAfter, stats.movingBefore, stats.movingAfter,
                    stats.trianglesBefore, stats.trianglesAfter, stats.materials.size(), selected.size(), stats.newIndexBytes);
            if (ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean()) {
                LOGGER.info("IR batch layout {} binding={}: staticOpaque={} [cutout={} mixed={} blend={}]; "
                                + "animatedOpaque={} [cutout={} mixed={} blend={}]; "
                                + "transformGroups={} [componentOnly={} animationOnly={} combined={} wheels={}]; animatedOpaqueHistogram={} "
                                + "| cutoutPacking staticVertexMaterialGroups={} animatedVertexMaterialGroups={}",
                        source.model(), bindingIndex, stats.staticAfter + stats.staticMixed,
                        stats.staticAfter, stats.staticMixed, stats.staticBlend, stats.movingAfter + stats.movingMixed,
                        stats.movingAfter, stats.movingMixed, stats.movingBlend, selected.size(),
                        stats.componentOnly, stats.animationOnly, stats.combined, stats.wheels, stats.movingOpaqueHistogram,
                        stats.staticCutoutBuckets, stats.movingCutoutBuckets);
            }
        }
        return Map.copyOf(result);
    }

    private static PartSelection pack(List<ModelPart> objects, boolean ordered,
                                      Map<PackingKey, PartSelection.Packed> cache, PackingStats stats) {
        var original = ordered ? PartSelection.groupedInOrder(objects) : PartSelection.grouped(objects);
        var key = new PackingKey(List.copyOf(objects), ordered);
        var packed = cache.get(key);
        if (packed == null) {
            packed = original.compactCutout();
            cache.put(key, packed);
            stats.newIndexBytes += packed.indexBytes();
        }
        stats.add(original, packed.selection(), ordered);
        return packed.selection();
    }
}
