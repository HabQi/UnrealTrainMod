/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.track.assembly.SwitchPosition;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipLayout;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import com.unrealtrainmod.track.mesh.RailMeshAccumulator;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import com.unrealtrainmod.track.mesh.TrackMeshData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static com.unrealtrainmod.track.mesh.NativeRailCurve.add;
import static com.unrealtrainmod.track.mesh.NativeRailCurve.dot;

/** Preparation and ownership for slip rails. World submission uses the ordinary native turnout caches. */
public final class NativeDoubleSlipRenderState implements AutoCloseable {
    public static final String THUMBNAIL_RECIPE = "native-double-slip-r11-full-sleepers-25";
    final NativeDoubleSlipLayout layout;
    final Map<String, NativeTurnoutRenderState> switches = new LinkedHashMap<>();
    final Map<Long, RailTrackMeshData> fixed;
    NativeTurnoutLoading.Trace loading;

    NativeDoubleSlipRenderState(NativeDoubleSlipLayout base, ClientRailModelManager.Model model) {
        loading = NativeTurnoutLoading.current();
        var originals = NativeTurnoutModels.sources(model.parts());
        var preparedGeometry = NativeDoubleSlipFittingMeshes.prepare(base,originals);
        layout = preparedGeometry.layout();
        var chunks = new LinkedHashMap<Long, RailMeshAccumulator>();
        var position = new Vector3f(); var normal = new Vector3f();
        bakeFixed(layout,originals,preparedGeometry.sources(),layout.origin(),(sources,matrix) -> {
            String part = sources.getFirst().part();
            double limit = 10*layout.scale();
            boolean split = part.startsWith("guard");
            var owners = split ? NativeTurnoutMeshes.meshChunks(layout.origin(),matrix,sources,limit)
                    : List.of(NativeTurnoutMeshes.meshOwner(layout.origin(),matrix,sources,limit));
            for (long chunk : owners) {
                var origin = chunkOrigin(layout,chunk);
                var output = chunks.computeIfAbsent(chunk,ignored -> new RailMeshAccumulator(origin));
                var transform = translation(layout.origin(),origin).mul(matrix);
                for (var source : sources) {
                    if (split) NativeTurnoutMeshes.appendRailChunk(output,source,source.mesh(),transform,position,normal);
                    else NativeTurnoutMeshes.append(output,source,source.mesh(),transform,position,normal);
                }
            }
        });
        var data = new LinkedHashMap<Long, RailTrackMeshData>();
        chunks.forEach((chunk, output) -> data.put(chunk, output.finish()));
        fixed = Map.copyOf(data);
        try {
            for (var control : layout.controls()) try (var prepared = new PreparedControl(layout, control, originals)) {
                switches.put(control.name(), new NativeTurnoutRenderState(prepared.layout, prepared.sources, TrackStaticBuffers.WORLD));
            }
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    private static TrackVector chunkOrigin(NativeDoubleSlipLayout layout, long chunk) {
        return new TrackVector(ChunkPos.getX(chunk)*16.0, Math.floor(layout.origin().y()/16)*16, ChunkPos.getZ(chunk)*16.0);
    }

    private static Matrix4f translation(TrackVector from, TrackVector to) {
        return new Matrix4f().translation((float)(from.x()-to.x()),(float)(from.y()-to.y()),(float)(from.z()-to.z()));
    }

    private static void bakeFixed(NativeDoubleSlipLayout layout, Map<String,List<Source>> originals, List<Source> fittings,
                                  TrackVector origin, BiConsumer<List<Source>,Matrix4f> output) {
        var shapes = new LinkedHashMap<NativeTurnoutCurves.SlipShape,List<Source>>();
        try {
            for (var rail : layout.fixed()) {
                var frame = Frame.of(rail,layout.scale(),0);
                var sources = shapes.computeIfAbsent(frame.shape(), ignored -> frame.sources(originals));
                output.accept(sources,frame.transform(origin,layout.scale()));
            }
        } finally {
            for (var sources : shapes.values()) sources.forEach(NativeTurnoutModels::release);
        }
        var transform = translation(layout.origin(),origin);
        for (var source : fittings) output.accept(List.of(source),transform);
    }

    RailTrackMeshData fixedData(long chunk) {
        var data = fixed.get(chunk);
        return data != null ? data : new RailTrackMeshData(chunkOrigin(layout, chunk), new TrackMeshData.Bounds(0,0,0,0,0,0), List.of());
    }

    void attachLoading(NativeTurnoutLoading.Trace trace) {
        loading = trace;
        switches.values().forEach(state -> state.attachLoading(trace));
    }

    void removeChunk(long chunk) { switches.values().forEach(state -> state.removeChunk(chunk)); }
    void invalidateGpu() { switches.values().forEach(NativeTurnoutRenderState::invalidateGpu); }
    @Override public void close() {
        switches.values().forEach(NativeTurnoutRenderState::close);
        switches.clear();
    }

    record Frame(TrackVector start, TrackVector end, double yaw, double length, NativeTurnoutCurves.SlipShape shape) {
        static Frame of(NativeDoubleSlipLayout.Strip rail, double scale, float visibleFrom) {
            var start = rail.start().point(); var end = rail.end().point();
            var chord = add(end,start,-1);
            double length = NativeDoubleSlipLayout.length(chord), yaw = NativeDoubleSlipLayout.yaw(chord);
            var forward = new TrackVector(Math.sin(yaw),0,Math.cos(yaw));
            var right = NativeDoubleSlipLayout.lateral(forward);
            var shape = new NativeTurnoutCurves.SlipShape(section(rail.start(), right, forward),
                    section(rail.end(), right, forward), quantized(length/scale), rail.side(), visibleFrom);
            return new Frame(start,end,yaw,length,shape);
        }
        private static NativeTurnoutCurves.SlipSection section(NativeDoubleSlipLayout.Section section, TrackVector right,
                                                               TrackVector forward) {
            var normal = section.across();
            return new NativeTurnoutCurves.SlipSection(quantized(dot(normal,right)), quantized(dot(normal,forward)),
                    quantized(section.width()),quantized(section.footLimit()),quantized(section.bevel()),quantized(section.footRelief()));
        }
        private static float quantized(double value) { return (float)(Math.rint(value*1e6)*1e-6); }
        Matrix4f transform(TrackVector origin, double scale) {
            return new Matrix4f().translation((float)(start.x()-origin.x()),(float)(start.y()-origin.y()),
                    (float)(start.z()-origin.z())).rotateY((float)yaw).scale((float)scale);
        }
        List<Source> sources(Map<String, List<Source>> originals) {
            return originals.get(shape.side() > 0 ? "railL" : "railR").stream()
                    .map(source -> NativeTurnoutModels.slipStrip(source,shape)).toList();
        }
        NativeTurnoutLayout.Motion motion(NativeTurnoutLayout.Motion next, double rotation, boolean opensInNormal) {
            return NativeTurnoutLayout.Motion.horizontal(next,end,length,yaw,rotation,opensInNormal);
        }
    }

    private static final class PreparedControl implements AutoCloseable {
        final NativeTurnoutLayout layout;
        final Map<String,List<Source>> sources = new LinkedHashMap<>();
        PreparedControl(NativeDoubleSlipLayout slip, NativeDoubleSlipLayout.Control control, Map<String,List<Source>> originals) {
            var parts = new ArrayList<NativeTurnoutLayout.Part>();
            try {
                for (var blade : control.blades()) {
                    NativeTurnoutLayout.Motion next = null;
                    for (var link : blade.links().reversed()) {
                        if (link.visibleFrom() == 1) break;
                        var frame = Frame.of(link.strip(),slip.scale(),link.visibleFrom());
                        next = frame.motion(next,link.rotation(),blade.opensInNormal());
                        String name = Integer.toString(parts.size());
                        sources.put(name,frame.sources(originals));
                        parts.add(new NativeTurnoutLayout.Part(name,frame.start(),frame.yaw(),0,0,0,0,0,null,null,null,next));
                    }
                }
            } catch (RuntimeException | Error failure) { close(); throw failure; }
            layout = new NativeTurnoutLayout(slip.origin(),slip.scale(),parts,parts.size());
        }
        @Override public void close() {
            for (var group : sources.values()) group.forEach(NativeTurnoutModels::release);
        }
    }

    static ClientRailModelManager.Model model(ClientRailModelManager.Model original) {
        var originals = NativeTurnoutModels.sources(original.parts());
        var preparedGeometry = NativeDoubleSlipFittingMeshes.prepare(
                NativeDoubleSlipLayout.build(NativeDoubleSlipLayout.example(),1.435),originals);
        var layout = preparedGeometry.layout();
        var origin = new TrackVector(0,0,0);
        var output = new RailMeshAccumulator(origin);
        var position = new Vector3f(); var normal = new Vector3f();
        bakeFixed(layout,originals,preparedGeometry.sources(),origin,(sources,transform) -> {
            for (var source : sources) NativeTurnoutMeshes.append(output,source,source.mesh(),transform,position,normal);
        });
        var positions = TrackAssemblyDefinition.DOUBLE_SLIP.initialState().controls();
        for (var control : layout.controls()) try (var prepared = new PreparedControl(layout,control,originals)) {
            var sources = NativeTurnoutMeshes.prepare(prepared.layout,prepared.sources);
            try {
                float movement = positions.get(control.name()) == SwitchPosition.REVERSE ? 1 : 0;
                NativeCrossoverMeshes.appendData(output,origin,
                        NativeTurnoutMeshes.build(prepared.layout,sources,null,true,movement));
            } finally { NativeTurnoutMeshes.release(sources); }
        }
        var data = output.finish(); var bounds = data.bounds();
        var batches = data.batches().stream().map(batch ->
                new ClientRailModelManager.Batch(batch.mesh(),batch.texture(),batch.doubleSided())).toList();
        return new ClientRailModelManager.Model(batches,original.parts(),original.padding(),
                new ModelBounds((float)bounds.minX(),(float)bounds.minY(),(float)bounds.minZ(),
                        (float)bounds.maxX(),(float)bounds.maxY(),(float)bounds.maxZ()),
                original.format(),original.period(),false,null);
    }
}
