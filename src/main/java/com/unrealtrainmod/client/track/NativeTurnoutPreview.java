/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.assembly.SwitchPosition;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.compat.iris.IrisDrawScope;
import com.unrealtrainmod.client.renderer.preview.PreviewCamera;
import com.unrealtrainmod.client.renderer.preview.PreviewRenderer;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.track.AssemblyPlacementGeometry;
import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackPath;
import com.unrealtrainmod.track.assembly.TrackPort;
import com.unrealtrainmod.track.construction.TrackConstructionType;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.model.NativeTurnoutCatalog;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Cached native geometry shared by selector captures and full construction drafts. */
public final class NativeTurnoutPreview implements AutoCloseable {
    private final PreviewRenderer renderer = new PreviewRenderer();
    private final TrackStaticBuffers buffers;
    private final TrackDrawQueue queue = new TrackDrawQueue();
    private final List<TrackStaticMesh> meshes = new ArrayList<>();
    private NativeTurnoutRenderState state;
    private TrackAssemblyInstance assembly;
    private NativeTurnoutModels.Model model;
    private TrackAssemblyInstance sample;
    private ModelBounds bounds;

    public NativeTurnoutPreview(boolean gui) { buffers = new TrackStaticBuffers(gui); }

    public static TrackAssemblyInstance assembly(TrackAssemblyDefinition definition, TrackAssemblyDefinition.Geometry geometry,
                                                  TrackVector origin, double gauge) {
        var ports = new LinkedHashMap<String, TrackPort>();
        geometry.ports().forEach((name, port) -> ports.put(name, new TrackPort(id("port/" + name), port.outwardYaw())));
        var paths = new LinkedHashMap<String, TrackPath>();
        geometry.paths().forEach((name, parts) -> {
            var steps = new ArrayList<TrackConnection>();
            for (int i = 0; i < parts.size(); i++) steps.add(new TrackConnection(id("path/" + name + "/" + i), true));
            paths.put(name, new TrackPath(steps));
        });
        return new TrackAssemblyInstance(id("assembly"), definition, ports, paths, origin, 0, gauge,
                definition.initialState(), 0, null, geometry, NativeTurnoutCatalog.DEFAULT.toString());
    }

    private static UUID id(String name) { return UUID.nameUUIDFromBytes(("native-preview/" + name).getBytes(StandardCharsets.UTF_8)); }

    private TrackAssemblyInstance sample() {
        if (sample == null) {
            var origin = new TrackVector(0, 0, 0);
            var points = List.of(origin, new TrackVector(0, 0, 12), new TrackVector(4, 0, 12));
            var curves = java.util.Arrays.<TrackCurveParameters>asList(null, null, null);
            var geometry = AssemblyPlacementGeometry.create(TrackConstructionType.SIMPLE_TURNOUT, points, 1.435, curves, 0);
            sample = assembly(TrackAssemblyDefinition.CUSTOM, geometry, origin, 1.435);
        }
        return sample;
    }

    void prepare(TrackAssemblyInstance selected) {
        if (assembly == selected && model == NativeTurnoutModels.INSTANCE.model()) return;
        clear();
        assembly = selected; model = NativeTurnoutModels.INSTANCE.model();
        state = new NativeTurnoutRenderState(selected);
        var positions = new LinkedHashMap<String, Float>();
        selected.state().controls().forEach((key, value) -> positions.put(key,
                value == SwitchPosition.REVERSE ? 1f : 0f));
        var b = selected.bounds();
        for (int x = (int)Math.floor(b.minX / 16); x <= (int)Math.floor(b.maxX / 16); x++) {
            for (int z = (int)Math.floor(b.minZ / 16); z <= (int)Math.floor(b.maxZ / 16); z++) {
                meshes.addAll(state.previewGeometry(x, z, positions));
            }
        }
        meshes.add(new RailTrackGpuMesh(state.previewParts(positions)));
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (var mesh : meshes) if (!mesh.isEmpty()) {
            var box = mesh.bounds();
            minX = Math.min(minX, box.minX); minY = Math.min(minY, box.minY); minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX); maxY = Math.max(maxY, box.maxY); maxZ = Math.max(maxZ, box.maxZ);
        }
        bounds = new ModelBounds((float)minX, (float)minY, (float)minZ, (float)maxX, (float)maxY, (float)maxZ);
    }

    public void render(GuiGraphics graphics, int x, int y, int width, int height, PreviewCamera camera) {
        prepare(sample());
        renderer.render(graphics, x, y, width, height, bounds, camera, this::drawGui);
    }

    public NativeImage capture() {
        try {
            prepare(sample());
            return renderer.capture(bounds, -180, 65, false, PreviewRenderer.RAIL_THUMBNAIL_SAMPLE_SCALE, this::drawGui);
        } finally { clear(); }
    }

    private void drawGui(PoseStack pose) {
        try (var state = new TrackRenderState(null)) {
            enqueue(new Matrix4f(pose.last().pose()), 0, 0, 0, true);
            queue.drawPreview(state, RenderSystem.getProjectionMatrix());
        } finally { queue.clear(); }
    }

    void drawWorld(RenderLevelStageEvent event) {
        boolean iris = IrisCompat.active();
        try (var scope = iris ? IrisDrawScope.preview() : null;
             var renderState = iris ? null : new TrackRenderState(null)) {
            var camera = event.getCamera().getPosition();
            enqueue(new Matrix4f(event.getModelViewMatrix()), camera.x, camera.y, camera.z, false);
            if (iris) scope.beginTrackBatch();
            queue.draw(renderState, event.getProjectionMatrix(), null);
        } finally { queue.clear(); }
    }

    private void enqueue(Matrix4f view, double x, double y, double z, boolean gui) {
        buffers.beginView(); queue.begin();
        for (var mesh : meshes) if (!mesh.isEmpty()) {
            buffers.request(mesh, TrackRenderStats.STRUCTURE_ASSEMBLY);
            var origin = mesh.origin();
            queue.addStatic(mesh, new Matrix4f(view).translate((float)(origin.x() - x), (float)-y,
                    (float)(origin.z() - z)), TrackRenderStats.STRUCTURE_ASSEMBLY);
        }
        buffers.prepare(gui ? null : Minecraft.getInstance().level, null, Integer.MAX_VALUE, null);
        queue.resolve(buffers, null);
    }

    public void clear() {
        buffers.close(); queue.close();
        meshes.forEach(TrackRenderMesh::close); meshes.clear();
        if (state != null) { state.close(); state = null; }
        assembly = null; model = null;
    }

    @Override public void close() { try { clear(); } finally { renderer.close(); } }
}
