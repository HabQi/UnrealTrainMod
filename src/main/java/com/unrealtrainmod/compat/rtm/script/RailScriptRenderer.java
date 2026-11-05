/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Legacy {@code RailPartsRenderer} subset for static meshes and rigid turnout snapshots. */
public final class RailScriptRenderer extends RtmPartsRenderer {
    /** Limited read-only texture metadata for the common getModelObject().textures[0].material.texture idiom. */
    public static final class ModelObject {
        public final Texture[] textures;
        private ModelObject(List<RailTrackMeshBuilder.Source> sources) {
            textures = sources.stream().map(RailTrackMeshBuilder.Source::texture).distinct().map(Texture::new).toArray(Texture[]::new);
        }
    }

    public static final class Texture {
        public final Material material;
        private Texture(ResourceLocation texture) { material = new Material(texture); }
    }

    public static final class Material {
        public final ResourceLocation texture;
        private Material(ResourceLocation texture) { this.texture = texture; }
    }

    private final List<RailTrackMeshBuilder.Source> sources;
    private final Map<String, List<RailTrackMeshBuilder.Source>> parts = new LinkedHashMap<>();
    private final Map<String, List<RailTrackMeshBuilder.Source>> resolved = new HashMap<>();
    private final String label;
    private final ModelObject modelObject;
    private RailScriptDrawContext context;

    RailScriptRenderer(List<RailTrackMeshBuilder.Source> sources, String label) {
        this.sources = List.copyOf(sources);
        this.label = label;
        for (var source : sources) parts.computeIfAbsent(source.part(), ignored -> new ArrayList<>()).add(source);
        modelObject = new ModelObject(sources);
    }

    void context(RailScriptDrawContext context) { this.context = context; }

    @Override
    protected void registered(Parts parts) {
        for (String name : parts.names()) resolve(name);
    }

    private List<RailTrackMeshBuilder.Source> resolve(String name) {
        return resolved.computeIfAbsent(name, ignored -> {
            var found = parts.get(name);
            if (found == null) {
                for (var entry : parts.entrySet()) {
                    if (entry.getKey().trim().equalsIgnoreCase(name.trim())) { found = entry.getValue(); break; }
                }
            }
            if (found != null) return found;
            ScriptDiagnostics.missingPart(label, name);
            return List.of();
        });
    }

    @Override
    protected void draw(List<String> names) {
        current().draw(select(names));
    }

    private List<RailTrackMeshBuilder.Source> select(List<String> names) {
        var selected = new LinkedHashSet<RailTrackMeshBuilder.Source>();
        for (String name : names) selected.addAll(resolve(name));
        return List.copyOf(selected);
    }

    private RailScriptDrawContext current() {
        if (context == null) throw new IllegalStateException("Rail drawing outside a cached rail script build");
        return context;
    }

    public ModelObject getModelObject() { return modelObject; }

    public String[] getAllObjNames() { return parts.keySet().toArray(String[]::new); }

    public boolean isSwitchRail(Object tile) { return ((RailScriptTile)tile).getSwitch() != null; }

    /** A prepared native rail definition has a single model variant. */
    public int getCurrentRailIndex() { return 0; }

    /** Repeats the prepared geometry along the native centerline and applies shouldRenderObject. */
    public void renderStaticParts(Object tile, double x, double y, double z) { current().repeat(sources, x, y, z); }

    /** Explicit rigid samples, used by legacy scripts for the non-branching ends of a turnout. */
    public void renderRailMapStatic(RailScriptTile tile, RailScriptMap map, int split, int first, int last, Parts... groups) {
        if (split < 0) throw new IllegalArgumentException("Rail split must not be negative");
        var names = new ArrayList<String>();
        for (var group : groups) names.addAll(group.names());
        current().drawAlong(map, split, first, last, tile.getRailPositions()[0], select(names));
    }

    public int getBrightness(Object world, double x, double y, double z) {
        ScriptDiagnostics.unsupported("rail renderer.getBrightness", "rail vertices and rigid part instances sample native world light");
        return 0;
    }

    public void setBrightness(int brightness) { current().brightness(brightness); }

    public Object getWorld(Object tile) { return null; }

    public double getY(Object tile) { return ((RailScriptTile)tile).getStartPoint()[1]; }

    @Override
    void release() {
        context = null;
        resolved.clear();
        super.release();
    }
}
