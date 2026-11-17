/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.*;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.*;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Shared shape cache and true chunk clipping, independent of vanilla/Iris GPU representations. */
final class NativeScissorsMeshes implements AutoCloseable {
    private record Key(double scale, List<NativeScissorsLayout.Path> paths, List<NativeTurnoutLayout.Part> rails, List<NativeScissorsGeometry.Rail> connections,
                       List<NativeDiamondLayout.Part> fixedParts, List<NativeScissorsLayout.Sleeper> sleepers, List<Source> resources) {}
    private static final Map<Key,Shared> SHAPES = new HashMap<>();
    private static final class Shared {
        final NativeScissorsGeometry.Result geometry;
        final List<NativeScissorsLayout.Sleeper> sleepers;
        private record Placement(String source, long x, long y, long z, long yaw) {}
        int references;
        Shared(NativeScissorsLayout layout, Map<String,List<Source>> originals) {
            var fixed = new LinkedHashMap<String,IndexedMesh>(); var blades = new LinkedHashMap<String,IndexedMesh>();
            var output = new RailMeshAccumulator(layout.origin()); var position = new Vector3f(); var normal = new Vector3f();
            try (var stage = NativeLayoutProfile.stage("geometry/scissorsSleepers")) {
                var seen = new HashSet<Placement>(); var kept = new ArrayList<NativeScissorsLayout.Sleeper>();
                var identities = new HashMap<String,String>(); double unit = 1e-6*layout.scale();
                for (var sleeper : layout.sleepers()) {
                    var center = sleeper.center(); double scale = layout.scale();
                    var parts = originals.get(sleeper.name());
                    if (parts == null) throw new IllegalArgumentException("Missing native r11 part: "+sleeper.name());
                    String identity = identities.computeIfAbsent(sleeper.name(),name -> NativeSourceIdentity.group(originals.get(name)));
                    double yaw = Math.atan2(Math.sin(sleeper.yaw()),Math.cos(sleeper.yaw()));
                    if (!seen.add(new Placement(identity,Math.round(center.x()/unit),Math.round(center.y()/unit),
                            Math.round(center.z()/unit),Math.round(yaw/1e-6)))) continue;
                    kept.add(sleeper);
                    var transform = new Matrix4f().translation((float)center.x(),(float)center.y(),(float)center.z())
                            .rotateY((float)sleeper.yaw()).translate(0,0,(float)(-.25*scale)).scale((float)scale);
                    for (var source : parts) NativeTurnoutMeshes.append(output,source,source.mesh(),transform,position,normal);
                }
                sleepers = List.copyOf(kept);
                if (stage != null) { stage.value("sourceInstances",layout.sleepers().size()); stage.value("keptInstances",sleepers.size()); stage.value("removedInstances",layout.sleepers().size()-sleepers.size()); }
                fixed.put("sleepers",NativeTurnoutMeshes.concatenate(output.finish().batches().stream().map(RailTrackMeshData.Batch::mesh).toList()));
            }
            var groups = new LinkedHashMap<String,RailMeshAccumulator>(); var zero = new TrackVector(0,0,0);
            try (var stage = NativeLayoutProfile.stage("geometry/scissorsFixedHardware")) {
                for (var part : layout.fixedParts()) {
                    var parts = originals.get(part.name());
                    if (parts == null) throw new IllegalArgumentException("Missing native r11 part: "+part.name());
                    var target = groups.computeIfAbsent(part.name(),name -> new RailMeshAccumulator(layout.origin()));
                    var transform = part.transform(zero,layout.scale());
                    for (var source : parts) {
                        var mesh = NativeDiamondRenderState.mesh(part,source);
                        NativeTurnoutMeshes.append(target,source,mesh,transform,position,normal);
                    }
                }
                groups.forEach((name,group) -> fixed.put(name,NativeTurnoutMeshes.concatenate(group.finish().batches().stream().map(RailTrackMeshData.Batch::mesh).toList())));
            }
            var railLayout = new NativeTurnoutLayout(zero,layout.scale(),layout.fixedRails(),0);
            var prepared = NativeTurnoutMeshes.prepare(railLayout,originals);
            try { fixed.put("turnout_rails",NativeTurnoutMeshes.concatenate(NativeTurnoutMeshes.build(railLayout,prepared,null,false,0)
                    .batches().stream().map(RailTrackMeshData.Batch::mesh).toList())); }
            finally { NativeTurnoutMeshes.release(prepared); }
            var connections = new RailMeshAccumulator(zero);
            try (var stage = NativeLayoutProfile.stage("geometry/scissorsConnectionSweep")) {
                for (var rail : layout.connections()) {
                    double center = rail.side()*NativeScissorsLayout.RAIL, start = -rail.length()/2, end = rail.length()/2;
                    var transform = new Matrix4f().translation((float)rail.position().x(),(float)rail.position().y(),(float)rail.position().z())
                            .rotateY((float)rail.yaw()).translate((float)(-center*layout.scale()),0,(float)(-start*layout.scale())).scale((float)layout.scale());
                    for (var source : originals.get(rail.side() > 0 ? "railL" : "railR")) {
                        var mesh = NativeTurnoutCurves.fixedRail(source.mesh(),start,end,center,rail.curve(),rail.startMiter(),rail.endMiter());
                        NativeTurnoutMeshes.append(connections,source,mesh,transform,position,normal);
                    }
                }
                fixed.put("connections",NativeTurnoutMeshes.concatenate(connections.finish().batches().stream().map(RailTrackMeshData.Batch::mesh).toList()));
            }
            for (int control = 0; control < NativeScissorsLayout.CONTROLS.size(); control++) {
                var core = layout.switches().get(NativeScissorsLayout.CONTROLS.get(control));
                var moving = NativeTurnoutMeshes.prepare(core,originals);
                try {
                    for (int i = 0; i < core.parts().size(); i++) blades.put(bladeName(control,i),
                            NativeTurnoutMeshes.join(moving.get(core.parts().get(i)).stream().map(Source::mesh).toList()));
                } finally { NativeTurnoutMeshes.release(moving); }
            }
            geometry = new NativeScissorsGeometry.Result(fixed,blades);
        }
    }
    private final NativeScissorsLayout layout;
    private final Key key;
    private final Shared shared;
    private final Map<String,List<Source>> sources = new LinkedHashMap<>();
    private final Map<Long,RailTrackMeshData> chunks = new LinkedHashMap<>();
    private boolean closed;
    NativeScissorsMeshes(NativeScissorsLayout layout, ClientRailModelManager.Model model) {
        var originals = NativeTurnoutModels.sources(model.parts());
        var names = new HashSet<String>();
        layout.sleepers().forEach(part -> names.add(part.name())); layout.fixedParts().forEach(part -> names.add(part.name()));
        names.addAll(List.of("railL","railR","L1","R1"));
        var resources = originals.entrySet().stream().filter(entry -> names.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey()).flatMap(entry -> entry.getValue().stream()).toList();
        this.layout = layout; key = new Key(layout.scale(),layout.paths(),layout.fixedRails(),layout.connections(),layout.fixedParts(),layout.sleepers(),resources);
        synchronized (SHAPES) {
            try (var stage = NativeLayoutProfile.stage("geometry/scissorsShapeCache")) {
                if (stage != null) stage.value("cacheHit", SHAPES.containsKey(key));
                shared = SHAPES.computeIfAbsent(key,k -> new Shared(layout,originals)); shared.references++;
            }
        }
        try {
            shared.geometry.blades().forEach((name,mesh) -> sources.put(name,List.of(NativeTurnoutModels.generated(name,mesh))));
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }
    Map<String,List<Source>> sources() { return sources; }
    NativeScissorsGeometry.Result geometry() { return shared.geometry; }
    List<NativeScissorsLayout.Sleeper> sleepers() { return shared.sleepers; }
    private static String bladeName(int control, int part) { return "scissors_blade_"+control+"_part_"+part; }
    Map<String,NativeTurnoutLayout> switches() {
        var result = new LinkedHashMap<String,NativeTurnoutLayout>();
        for (int control = 0; control < NativeScissorsLayout.CONTROLS.size(); control++) {
            String name = NativeScissorsLayout.CONTROLS.get(control); var core = layout.switches().get(name);
            var parts = new ArrayList<NativeTurnoutLayout.Part>();
            for (int i = 0; i < core.parts().size(); i++) {
                var part = core.parts().get(i);
                // Mesh deformation is already cached; preserve the ordinary turnout's linked motion and source origin.
                parts.add(new NativeTurnoutLayout.Part(bladeName(control,i),part.position(),part.yaw(),part.pitch(),part.localX(),part.localZ(),
                        0,0,null,null,null,part.motion()));
            }
            result.put(name,new NativeTurnoutLayout(core.origin(),core.scale(),parts,0));
        }
        return result;
    }
    RailTrackMeshData build(Long chunk) {
        if (chunk != null) return chunks.getOrDefault(chunk,new RailTrackMeshData(chunkOrigin(chunk),new TrackMeshData.Bounds(0,0,0,0,0,0),List.of()));
        var out = new RailMeshAccumulator(layout.origin());
        for (var mesh : shared.geometry.fixed().values()) out.append(NativeTurnoutModels.TEXTURE,false,mesh,(index,values) -> {
            int offset = index*8; for (int i = 0; i < 8; i++) values.add(mesh.vertices()[offset+i]);
        });
        return out.finish();
    }
    private TrackVector chunkOrigin(long chunk) {
        return new TrackVector(ChunkPos.getX(chunk)*16.0,Math.floor(layout.origin().y()/16)*16,ChunkPos.getZ(chunk)*16.0);
    }
    private static final class ChunkData {
        final TrackVector origin, offset;
        final FloatArrayList vertices = new FloatArrayList(); final IntArrayList colors = new IntArrayList(), indices = new IntArrayList();
        ChunkData(TrackVector origin, TrackVector layoutOrigin) {
            this.origin = origin; offset = NativeRailCurve.add(layoutOrigin,origin,-1);
        }
        void triangle(float[] a, int ai, float[] b, int bi, float[] c, int ci) {
            double ux = b[bi]-a[ai], uy = b[bi+1]-a[ai+1], uz = b[bi+2]-a[ai+2];
            double vx = c[ci]-a[ai], vy = c[ci+1]-a[ai+1], vz = c[ci+2]-a[ai+2];
            double nx = uy*vz-uz*vy, ny = uz*vx-ux*vz, nz = ux*vy-uy*vx;
            if (nx*nx+ny*ny+nz*nz < 1e-22) return;
            vertex(a,ai); vertex(b,bi); vertex(c,ci);
        }
        private void vertex(float[] data, int index) {
            indices.add(colors.size()); colors.add(0xFFFFFFFF);
            vertices.add((float)(data[index]+offset.x())); vertices.add((float)(data[index+1]+offset.y())); vertices.add((float)(data[index+2]+offset.z()));
            for (int i = 3; i < 8; i++) vertices.add(data[index+i]);
        }
        IndexedMesh mesh() { return new IndexedMesh(vertices.toFloatArray(),colors.toIntArray(),indices.toIntArray()); }
    }
    /** World models need chunk ownership; whole-model icons do not. */
    void prepareChunks() {
        try (var stage = NativeLayoutProfile.stage("geometry/scissorsChunkClipping")) {
            var builders = new Long2ObjectLinkedOpenHashMap<ChunkData>(); var origin = layout.origin();
            var polygon = List.of(new float[8],new float[8],new float[8]);
            long directTriangles = 0, boundaryTriangles = 0, outputTriangles = 0, maxTriangleChunks = 0;
            for (var group : shared.geometry.fixed().entrySet()) {
                var mesh = group.getValue();
                validateBounds(group.getKey(),mesh);
                var data = mesh.vertices(); var indices = mesh.indices();
                for (int triangle = 0; triangle < indices.length; triangle += 3) {
                    int a = indices[triangle]*8, b = indices[triangle+1]*8, c = indices[triangle+2]*8;
                    double minX = Math.min(data[a],Math.min(data[b],data[c])), maxX = Math.max(data[a],Math.max(data[b],data[c]));
                    double minZ = Math.min(data[a+2],Math.min(data[b+2],data[c+2])), maxZ = Math.max(data[a+2],Math.max(data[b+2],data[c+2]));
                    int firstX = (int)Math.floor((origin.x()+minX)/16), lastX = (int)Math.floor((origin.x()+maxX)/16);
                    int firstZ = (int)Math.floor((origin.z()+minZ)/16), lastZ = (int)Math.floor((origin.z()+maxZ)/16);
                    maxTriangleChunks = Math.max(maxTriangleChunks,((long)lastX-firstX+1)*((long)lastZ-firstZ+1));
                    if (firstX == lastX && firstZ == lastZ) {
                        chunk(builders,firstX,firstZ).triangle(data,a,data,b,data,c);
                        directTriangles++;
                        continue;
                    }
                    boundaryTriangles++;
                    System.arraycopy(data,a,polygon.get(0),0,8);
                    System.arraycopy(data,b,polygon.get(1),0,8);
                    System.arraycopy(data,c,polygon.get(2),0,8);
                    for (long x = firstX; x <= lastX; x++) for (long z = firstZ; z <= lastZ; z++) {
                        double left = x*16.0-origin.x(), right = (x+1)*16.0-origin.x();
                        double near = z*16.0-origin.z(), far = (z+1)*16.0-origin.z();
                        List<float[]> clipped = polygon;
                        if (minX < left) clipped = clip(clipped,0,left,true);
                        if (maxX > right) clipped = clip(clipped,0,right,false);
                        if (minZ < near) clipped = clip(clipped,2,near,true);
                        if (maxZ > far) clipped = clip(clipped,2,far,false);
                        if (clipped.size() < 3) continue;
                        var out = chunk(builders,(int)x,(int)z);
                        for (int i = 2; i < clipped.size(); i++) out.triangle(clipped.getFirst(),0,clipped.get(i-1),0,clipped.get(i),0);
                    }
                }
            }
            for (var entry : builders.long2ObjectEntrySet()) {
                var chunk = entry.getValue(); var out = new RailMeshAccumulator(chunk.origin); var mesh = chunk.mesh();
                outputTriangles += mesh.indices().length/3;
                out.append(NativeTurnoutModels.TEXTURE,false,mesh,(index,values) -> { int offset = index*8; for (int i = 0; i < 8; i++) values.add(mesh.vertices()[offset+i]); });
                chunks.put(entry.getLongKey(),out.finish());
            }
            if (stage != null) {
                stage.value("chunks",chunks.size()); stage.value("sourceTriangles",directTriangles+boundaryTriangles);
                stage.value("directTriangles",directTriangles); stage.value("boundaryTriangles",boundaryTriangles); stage.value("outputTriangles",outputTriangles);
                stage.value("maxTriangleChunks",maxTriangleChunks);
            }
        }
    }
    private void validateBounds(String group, IndexedMesh mesh) {
        var origin = layout.origin(); var bounds = layout.bounds(); double margin = .01*layout.scale();
        double minX = bounds.minX-origin.x()-margin, maxX = bounds.maxX-origin.x()+margin;
        double minZ = bounds.minZ-origin.z()-margin, maxZ = bounds.maxZ-origin.z()+margin;
        var data = mesh.vertices();
        for (int i = 0; i < data.length; i += 8) {
            double x = data[i], y = data[i+1], z = data[i+2];
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || x < minX || x > maxX || z < minZ || z > maxZ)
                throw new IllegalArgumentException("Invalid scissors geometry before chunk clipping: group="+group+", vertex="+i/8
                        +", position=["+x+","+y+","+z+"], x=["+minX+","+maxX+"], z=["+minZ+","+maxZ+"]");
        }
    }
    private ChunkData chunk(Long2ObjectLinkedOpenHashMap<ChunkData> builders, int x, int z) {
        long key = ChunkPos.asLong(x,z); var result = builders.get(key);
        if (result == null) {
            result = new ChunkData(chunkOrigin(key),layout.origin()); builders.put(key,result);
        }
        return result;
    }
    private static List<float[]> clip(List<float[]> input, int axis, double boundary, boolean positive) {
        return NativePlanarGeometry.clip(input,axis == 0 ? 1 : 0,axis == 2 ? 1 : 0,boundary,positive);
    }
    static void appendData(RailMeshAccumulator output, TrackVector origin, RailTrackMeshData data) {
        var offset = new Matrix4f().translation((float)(data.origin().x()-origin.x()),(float)(data.origin().y()-origin.y()),(float)(data.origin().z()-origin.z()));
        var position = new Vector3f(); var normal = new Vector3f();
        for (var batch : data.batches()) {
            var source = new Source("scissors_global",batch.texture(),batch.doubleSided(),batch.mesh());
            NativeTurnoutMeshes.append(output,source,source.mesh(),offset,position,normal);
        }
    }
    @Override public void close() {
        if (closed) return; closed = true;
        for (var group : sources.values()) for (var source : group) NativeTurnoutModels.release(source);
        sources.clear(); chunks.clear();
        synchronized (SHAPES) { if (--shared.references == 0) SHAPES.remove(key); }
    }
}
