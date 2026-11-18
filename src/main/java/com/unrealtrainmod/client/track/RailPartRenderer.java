/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import java.util.Set;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.unrealtrainmod.client.compat.iris.IrisDrawScope;
import com.unrealtrainmod.client.compat.iris.IrisRenderTypes;
import com.unrealtrainmod.client.compat.iris.IrisVertices;
import com.unrealtrainmod.client.compat.iris.IrisGeometryCache;
import com.unrealtrainmod.client.compat.iris.IrisRenderStats;
import com.unrealtrainmod.client.StaticModelShader;
import java.util.BitSet;
import com.unrealtrainmod.client.renderer.instancing.InstanceBuffer;
import com.unrealtrainmod.client.renderer.instancing.InstancedModelShader;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailMeshAccumulator;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Rail-only rigid command submission. Shared source meshes belong to the rail model manager. */
final class RailPartRenderer implements AutoCloseable {
    private NativeTurnoutLoading.Trace loading;
    private boolean loadingPrepared, loadingInstances, loadingLight;
    void loading(NativeTurnoutLoading.Trace trace) {
        loading = trace; loadingPrepared = loadingInstances = loadingLight = false;
    }
    private record Key(SharedRailPart mesh, ResourceLocation texture, boolean doubleSided, long chunk, boolean moving) {}
    private record Draw(Key key, PoseStack.Pose pose, int light, PreparedPart prepared, long version) {}
    /** Upload history holds values, never a mutable prepared pose. */
    private record InstanceStamp(Key key, PreparedPart prepared, long version, int light) {}
    /** View-independent preparation; camera matrices, visibility and light remain draw-time inputs. */
    private static final class PreparedPart {
        private final PoseStack.Pose pose = new PoseStack().last();
        private TrackPartSnapshot.Part part;
        private AABB bounds;
        private long chunk;
        private boolean supported;
        private boolean batched;
        private SharedRailPart shared;
        private Key key;
        private long version;

        void clear() { part = null; bounds = null; shared = null; key = null; }
    }
    private record IrisFixedMember(Source source, Matrix4f pose) {}
    /** One immutable CPU source per loaded chunk; world pages own the corresponding GPU storage. */
    private static final class IrisFixedGroup implements AutoCloseable {
        final List<PreparedPart> members = new ArrayList<>();
        List<IrisFixedMember> captured = List.of();
        RailTrackGpuMesh mesh;
        boolean failed;
        com.unrealtrainmod.client.ModelPreparation.Ticket<com.unrealtrainmod.track.mesh.RailTrackMeshData> pending;

        boolean matches() {
            if (captured.size() != members.size()) return false;
            for (int i = 0; i < members.size(); i++) {
                var before = captured.get(i); var current = members.get(i);
                if (before.source() != current.part.source() || !before.pose().equals(current.pose.pose())) return false;
            }
            return true;
        }

        void build(long chunk, TrackVector anchor, double originY) {
            if (pending != null || failed) return;
            var identities = members.stream().map(member -> new IrisFixedMember(member.part.source(), new Matrix4f(member.pose.pose()))).toList();
            identities.forEach(member -> NativeTurnoutModels.retain(member.source()));
            var ticket = com.unrealtrainmod.client.ModelPreparation.geometry(() -> {
                var origin = new TrackVector((double)ChunkPos.getX(chunk) * 16, originY, (double)ChunkPos.getZ(chunk) * 16);
                var output = new RailMeshAccumulator(origin);
                var position = new Vector3f(); var normal = new Vector3f();
                for (var member : identities) {
                    var source = member.source(); var transform = member.pose();
                    var normals = transform.normal(new org.joml.Matrix3f());
                    output.append(source.texture(), source.doubleSided(), source.mesh(), (index, vertices) -> {
                        int v = index * 8; var data = source.mesh().vertices();
                        transform.transformPosition(position.set(data[v], data[v + 1], data[v + 2]));
                        normals.transform(normal.set(data[v + 5], data[v + 6], data[v + 7]));
                        if (normal.lengthSquared() > 0) normal.normalize();
                        vertices.add((float)(position.x + anchor.x() - origin.x()));
                        vertices.add((float)(position.y + anchor.y() - origin.y()));
                        vertices.add((float)(position.z + anchor.z() - origin.z()));
                        vertices.add(data[v + 3]); vertices.add(data[v + 4]); vertices.add(normal.x); vertices.add(normal.y); vertices.add(normal.z);
                    });
                }
                return output.finish();
            }, TrackPreparation::size, ignored -> {});
            if (ticket == null) { identities.forEach(member -> NativeTurnoutModels.release(member.source())); return; }
            captured = identities; pending = ticket;
            ticket.onRelease(() -> identities.forEach(member -> NativeTurnoutModels.release(member.source())));
            ticket.whenReady(() -> {
                try { mesh = new RailTrackGpuMesh(ticket.take()); }
                catch (RuntimeException exception) {
                    failed = true; ticket.close();
                    org.slf4j.LoggerFactory.getLogger(RailPartRenderer.class).warn("Cannot merge fixed Iris parts; retaining shared part draws", exception);
                }
                finally { pending = null; }
            });
        }

        @Override public void close() {
            failed = false;
            if (pending != null) { pending.close(); pending = null; }
            if (mesh != null) mesh.close();
            mesh = null;
            captured = List.of();
        }
    }
    private final InstanceBuffer fixedInstances = new InstanceBuffer();
    private final InstanceBuffer movingInstances = new InstanceBuffer();
    private List<InstanceStamp> uploadedFixed = List.of(), uploadedMoving = List.of();
    private final List<Draw> draws = new ArrayList<>(), fixedDraws = new ArrayList<>(), movingDraws = new ArrayList<>();
    private final Map<Key, List<Draw>> grouped = new LinkedHashMap<>();
    private final List<List<Draw>> activeGroups = new ArrayList<>();
    private TrackPartSnapshot classifiedSnapshot;
    private final BitSet movingParts = new BitSet();
    private ByteBufferBuilder fallback;
    private VertexBuffer irisFallback;
    private final TrackLighting lighting;
    private final TrackVector anchor;
    private final double meshOriginY;
    private final boolean nativeStyle;
    private final List<PreparedPart> preparedParts = new ArrayList<>();
    private final Map<Long, IrisFixedGroup> irisFixedGroups = new LinkedHashMap<>();
    private final Matrix4f preparedLocal = new Matrix4f();
    private TrackPartSnapshot preparedSnapshot, preparedPrevious;
    private float preparedFraction = Float.NaN;
    private int preparedPartCount;

    RailPartRenderer(TrackVector anchor, double meshOriginY) {
        this(anchor, meshOriginY, false);
    }

    RailPartRenderer(TrackVector anchor, double meshOriginY, boolean nativeStyle) {
        this.nativeStyle = nativeStyle;
        this.anchor = anchor;
        this.meshOriginY = meshOriginY;
        lighting = new TrackLighting(anchor);
    }

    /** Warm shared native sources before replacing the complete parent appearance. */
    boolean ready(TrackPartSnapshot snapshot, boolean iris, TrackRenderStats stats) {
        if (snapshot == null) return false;
        boolean ready = true;
        for (var part : snapshot.parts()) {
            var source = part.source();
            if (source.mesh().indices().length == 0) continue;
            ready &= com.unrealtrainmod.client.texture.MaterialTexture.ready(source.texture());
            if (iris && IrisGeometryCache.supportsPose(part.transform())) {
                ready &= ClientRailModelManager.INSTANCE.sharedPart(source.mesh()).boundsReady();
                var cached = IrisGeometryCache.request(source.mesh(), 0, source.mesh().indices().length, false);
                ready &= cached != null || !IrisGeometryCache.waiting(source.mesh(), 0, source.mesh().indices().length, false);
            } else ready &= ClientRailModelManager.INSTANCE.sharedPart(source.mesh()).ready(stats);
        }
        return ready;
    }

    int draw(TrackRenderState state, TrackPartSnapshot snapshot, TrackPartSnapshot before, float fraction,
             Set<Long> chunks, RenderLevelStageEvent event, ClientLevel level, int uploads, TrackRenderStats stats) {
        try (var binding = NativeTurnoutLoading.bind(loading)) {
            return drawNative(snapshot, before, fraction, chunks, event, level, uploads, stats, state);
        }
    }
    private int drawNative(TrackPartSnapshot snapshot, TrackPartSnapshot before, float fraction,
             Set<Long> chunks, RenderLevelStageEvent event, ClientLevel level, int uploads, TrackRenderStats stats, TrackRenderState state) {
        if (snapshot == null) return uploads;
        long groupStart = stats == null ? 0 : System.nanoTime();
        long lightStart = stats == null ? 0 : System.nanoTime();
        refreshLighting(level);
        if (stats != null) {
            long elapsed = System.nanoTime() - lightStart;
            stats.lightRefresh(elapsed, lighting.checkedSamples(), lighting.changedSamples());
            groupStart += elapsed;
        }
        var previous = snapshot.matches(before) ? before : null;
        classify(snapshot, previous);
        var camera = event.getCamera().getPosition();
        var modelView = new Matrix4f(event.getModelViewMatrix()).translate((float)(anchor.x() - camera.x),
                (float)(anchor.y() - camera.y), (float)(anchor.z() - camera.z));
        prepareParts(snapshot, previous, fraction);
        draws.clear(); activeGroups.clear();
        for (var group : grouped.values()) group.clear();
        for (int i = 0; i < preparedPartCount; i++) {
            var prepared = preparedParts.get(i);
            long chunk = prepared.chunk;
            if (!chunks.contains(chunk) || !level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) continue;
            if (prepared.part.source().mesh().indices().length == 0) continue;
            var shared = prepared.shared;
            if (!event.getFrustum().isVisible(prepared.bounds)) continue;
            if (StaticModelShader.state() != null && shared.gpu == null) {
                if (uploads == 0) continue;
                long uploadStart = stats == null ? 0 : System.nanoTime();
                shared.upload(stats);
                if (stats != null) groupStart += System.nanoTime() - uploadStart;
                uploads--;
            }
            var local = prepared.pose.pose();
            int light = lighting.sample(level, local.m30(), local.m31(), local.m32(), 0, 1, 0);
            var draw = new Draw(prepared.key, prepared.pose, light, prepared, prepared.version);
            draws.add(draw);
            var group = grouped.computeIfAbsent(draw.key, ignored -> new ArrayList<>());
            if (group.isEmpty()) activeGroups.add(group);
            group.add(draw);
        }
        if (stats != null) stats.dynamicParts(draws.size());
        if (draws.isEmpty()) {
            if (stats != null) stats.grouped(System.nanoTime() - groupStart);
            return uploads;
        }
        var groups = activeGroups;
        boolean instanced = StaticModelShader.state() != null && InstancedModelShader.usable();
        if (stats != null) stats.grouped(System.nanoTime() - groupStart);
        var fixed = fixedDraws; var moving = movingDraws;
        fixed.clear(); moving.clear();
        if (instanced) {
            for (var group : groups) (group.getFirst().key.moving ? moving : fixed).addAll(group);
            try (var operation = loadingInstances ? null : NativeTurnoutLoading.open(loading, "render/initialInstancePackingUpload")) {
                if (!sameInstances(fixed, uploadedFixed)) {
                    stream(fixedInstances, fixed, stats);
                    uploadedFixed = captureInstances(fixed);
                }
                if (!sameInstances(moving, uploadedMoving)) {
                    stream(movingInstances, moving, stats);
                    uploadedMoving = captureInstances(moving);
                }
            }
            loadingInstances = true;
        }
        long start = stats == null ? 0 : System.nanoTime();
        try {
            int fixedOffset = 0, movingOffset = 0;
            for (var group : groups) {
                boolean active = group.getFirst().key.moving;
                int offset = active ? movingOffset : fixedOffset;
                var instances = active ? movingInstances : fixedInstances;
                submit(state, group, instanced, instances, offset, modelView, event, stats);
                if (active) movingOffset += group.size(); else fixedOffset += group.size();
            }
        } finally {
            if (instanced) for (var group : groups) group.getFirst().key.mesh.gpu.releaseInstanceBuffer();
            if (stats != null) stats.submitted(System.nanoTime() - start);
        }
        return uploads;
    }

    int drawIris(TrackPartSnapshot snapshot, TrackPartSnapshot before, float fraction, Set<Long> chunks,
                 RenderLevelStageEvent event, ClientLevel level, TrackDrawQueue queue, int uploads, TrackRenderStats stats,
                 TrackWorldRenderer.BuildBudget budget, boolean shadow) {
        try (var binding = NativeTurnoutLoading.bind(loading)) {
            return drawIrisParts(snapshot, before, fraction, chunks, event, level, queue, uploads, stats, budget, shadow);
        }
    }
    private int drawIrisParts(TrackPartSnapshot snapshot, TrackPartSnapshot before, float fraction, Set<Long> chunks,
                 RenderLevelStageEvent event, ClientLevel level, TrackDrawQueue queue, int uploads, TrackRenderStats stats,
                 TrackWorldRenderer.BuildBudget budget, boolean shadow) {
        if (snapshot == null) return uploads;
        var previous = snapshot.matches(before) ? before : null;
        classify(snapshot, previous);
        prepareParts(snapshot, previous, fraction);
        var camera = event.getCamera().getPosition();
        var modelView = new Matrix4f(event.getModelViewMatrix()).translate((float)(anchor.x() - camera.x),
                (float)(anchor.y() - camera.y), (float)(anchor.z() - camera.z));
        IrisVertices writer = null;
        boolean lightPrepared = false;
        int drawn = batchIrisFixed(chunks, event, level, queue, budget, shadow, stats);
        for (int i = 0; i < preparedPartCount; i++) {
            var prepared = preparedParts.get(i);
            if (prepared.batched) continue;
            long chunk = prepared.chunk;
            if (!chunks.contains(chunk) || !level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) continue;
            var source = prepared.part.source();
            if (source.mesh().indices().length == 0) continue;
            if (!event.getFrustum().isVisible(prepared.bounds)) continue;
            if (!lightPrepared) { refreshLighting(level); lightPrepared = true; }
            long start = stats == null ? 0 : System.nanoTime();
            var local = prepared.pose.pose();
            int light = lighting.sample(level, local.m30(), local.m31(), local.m32(), 0, 1, 0);
            var gpu = prepared.supported
                    ? IrisGeometryCache.request(source.mesh(), 0, source.mesh().indices().length, false) : null;
            if (gpu == null && prepared.supported && IrisGeometryCache.waiting(source.mesh(), 0, source.mesh().indices().length, false)) continue;
            var type = IrisRenderTypes.get(source.texture(), source.doubleSided(), false, false);
            if (gpu != null) {
                var posedView = new Matrix4f(modelView).mul(local);
                queue.addCached(gpu, type, posedView, light, loading);
            } else {
                if (nativeStyle && stats != null) stats.nativeFallback(prepared.supported ? "iris_cache" : "iris_pose");
                if (writer == null) writer = new IrisVertices();
                if (fallback == null) fallback = new ByteBufferBuilder(1 << 16);
                var builder = new BufferBuilder(fallback, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                for (int index : source.mesh().indices()) writer.vertex(builder, source.mesh(), index, prepared.pose, light, false);
                if (irisFallback == null) irisFallback = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                irisFallback.bind();
                var built = builder.buildOrThrow();
                int bytes = built.vertexBuffer().remaining();
                try { irisFallback.upload(built); }
                finally { VertexBuffer.unbind(); }
                boolean changed;
                try (var operation = NativeTurnoutLoading.firstDraw(loading)) {
                    changed = IrisDrawScope.draw(irisFallback, type, modelView, event.getProjectionMatrix());
                }
                NativeTurnoutLoading.submitted(loading);
                IrisRenderStats.owned(source.mesh().indices().length / 3);
                IrisRenderStats.upload(bytes);
                if (stats != null) { stats.activeUpload(bytes); if (changed) stats.renderTypeSetup(); stats.irisShaderBind(); }
            }
            drawn++;
            if (gpu == null) IrisRenderStats.submit(start);
            if (stats != null && gpu == null) {
                stats.drawn(TrackRenderStats.RAIL_MODEL, TrackRenderStats.STRUCTURE_ASSEMBLY, 1,
                        source.mesh().indices().length, source.mesh().indices().length / 3,
                        0, System.nanoTime() - start);
                stats.ranges(1, 1, 0);
            }
        }
        if (stats != null) stats.dynamicParts(drawn);
        return uploads;
    }

    private void refreshLighting(ClientLevel level) {
        try (var operation = loadingLight ? null : NativeTurnoutLoading.open(loading, "render/initialPartLight")) {
            lighting.refresh(level);
        }
        loadingLight = true;
    }

    /** A part observed moving stays on the rigid path until the snapshot layout changes. */
    private void classify(TrackPartSnapshot snapshot, TrackPartSnapshot previous) {
        if (classifiedSnapshot == snapshot) return;
        if (!snapshot.matches(classifiedSnapshot)) movingParts.clear();
        if (previous != null) for (int i = 0; i < snapshot.parts().size(); i++) {
            if (!snapshot.parts().get(i).transform().equals(previous.parts().get(i).transform())) movingParts.set(i);
        }
        classifiedSnapshot = snapshot;
    }

    /** Prepare sources before WORLD.prepare/queue.resolve, so replacement pages never invalidate queued ranges. */
    private int batchIrisFixed(Set<Long> chunks, RenderLevelStageEvent event, ClientLevel level, TrackDrawQueue queue,
                               TrackWorldRenderer.BuildBudget budget, boolean shadow, TrackRenderStats stats) {
        for (var group : irisFixedGroups.values()) group.members.clear();
        for (int i = 0; i < preparedPartCount; i++) {
            var part = preparedParts.get(i);
            part.batched = false;
            if (part.part.source().mesh().indices().length == 0 || !part.supported
                    || part.part.moving() || movingParts.get(i) || !chunks.contains(part.chunk)
                    || !level.hasChunk(ChunkPos.getX(part.chunk), ChunkPos.getZ(part.chunk))) continue;
            irisFixedGroups.computeIfAbsent(part.chunk, ignored -> new IrisFixedGroup()).members.add(part);
        }
        int drawn = 0;
        var camera = event.getCamera().getPosition();
        var iterator = irisFixedGroups.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); var group = entry.getValue();
            if (group.members.isEmpty()) { group.close(); iterator.remove(); continue; }
            // A single source already needs one draw; expanding it would only add storage.
            if (group.members.size() == 1) {
                if (group.mesh != null) group.close();
                continue;
            }
            // Drop stale geometry immediately; pending replacement sources use individual cached draws.
            if ((group.mesh != null || group.pending != null || group.failed) && !group.matches()) group.close();
            boolean visible = group.mesh != null ? event.getFrustum().isVisible(group.mesh.bounds())
                    : group.members.stream().anyMatch(member -> event.getFrustum().isVisible(member.bounds));
            if (!visible) { group.members.forEach(member -> member.batched = true); continue; }
            if (group.mesh == null && !shadow && group.pending == null && !group.failed) {
                long start = System.nanoTime();
                try (var operation = NativeTurnoutLoading.open(loading, "render/irisFixedGroup")) { group.build(entry.getKey(), anchor, meshOriginY); }
                finally {
                    if (stats != null) stats.built(System.nanoTime() - start);
                }
            }
            if (group.mesh == null) continue;
            TrackStaticBuffers.WORLD.request(group.mesh, TrackRenderStats.STRUCTURE_ASSEMBLY);
            // Never submit both the replacement and its original parts in the same view.
            if (!TrackStaticBuffers.WORLD.ready(group.mesh)) continue;
            var origin = group.mesh.origin();
            // Shared track pages store absolute Y and chunk-relative X/Z, matching other static track sources.
            var modelView = new Matrix4f(event.getModelViewMatrix()).translate((float)(origin.x() - camera.x),
                    (float)-camera.y, (float)(origin.z() - camera.z));
            queue.addStatic(group.mesh, modelView, TrackRenderStats.STRUCTURE_ASSEMBLY);
            group.members.forEach(member -> member.batched = true);
            drawn += group.members.size();
            if (stats != null) { stats.candidate(); stats.visible(); }
        }
        return drawn;
    }

    private void prepareParts(TrackPartSnapshot snapshot, TrackPartSnapshot previous, float fraction) {
        if (preparedSnapshot == snapshot && preparedPrevious == previous && preparedFraction == fraction) return;
        if (!snapshot.matches(preparedSnapshot)) grouped.clear();
        try (var operation = loadingPrepared ? null : NativeTurnoutLoading.open(loading, "render/initialPartPreparation")) {
            int count = snapshot.parts().size();
            if (operation != null) operation.value("parts", count);
            for (int i = 0; i < count; i++) {
                if (i == preparedParts.size()) preparedParts.add(new PreparedPart());
                var prepared = preparedParts.get(i);
                var part = snapshot.parts().get(i);
                if (part.source().mesh().indices().length == 0) {
                    prepared.clear();
                    prepared.part = part;
                    continue;
                }
                snapshot.interpolate(previous, i, fraction, preparedLocal);
                if (prepared.part == null || prepared.part.source() != part.source()
                        || !prepared.pose.pose().equals(preparedLocal)) {
                    prepared.pose.pose().set(preparedLocal);
                    preparedLocal.normal(prepared.pose.normal());
                    if (prepared.part == null || prepared.part.source() != part.source())
                        prepared.shared = ClientRailModelManager.INSTANCE.sharedPart(part.source().mesh());
                    prepared.bounds = prepared.shared.transformedBounds(preparedLocal, anchor.x(), anchor.y(), anchor.z());
                    prepared.version++;
                    prepared.chunk = ChunkPos.asLong((int)Math.floor((anchor.x() + preparedLocal.m30()) / 16),
                            (int)Math.floor((anchor.z() + preparedLocal.m32()) / 16));
                    prepared.supported = IrisGeometryCache.supportsPose(preparedLocal);
                }
                boolean moving = part.moving() || movingParts.get(i);
                var key = prepared.key;
                if (key == null || key.mesh != prepared.shared || key.chunk != prepared.chunk || key.moving != moving
                        || !key.texture.equals(part.source().texture()) || key.doubleSided != part.source().doubleSided()) {
                    prepared.key = new Key(prepared.shared, part.source().texture(), part.source().doubleSided(), prepared.chunk, moving);
                }
                prepared.part = part;
            }
            for (int i = count; i < preparedPartCount; i++) preparedParts.get(i).clear();
            preparedPartCount = count;
            preparedSnapshot = snapshot;
            preparedPrevious = previous;
            preparedFraction = fraction;
        }
        loadingPrepared = true;
    }

    private static boolean sameInstances(List<Draw> current, List<InstanceStamp> previous) {
        if (current.size() != previous.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            var a = current.get(i); var b = previous.get(i);
            if (!a.key.equals(b.key) || a.prepared != b.prepared || a.version != b.version || a.light != b.light) return false;
        }
        return true;
    }

    private static List<InstanceStamp> captureInstances(List<Draw> draws) {
        var result = new ArrayList<InstanceStamp>(draws.size());
        for (var draw : draws) result.add(new InstanceStamp(draw.key, draw.prepared, draw.version, draw.light));
        return List.copyOf(result);
    }

    private static void stream(InstanceBuffer buffer, List<Draw> draws, TrackRenderStats stats) {
        long start = stats == null ? 0 : System.nanoTime();
        buffer.begin();
        for (var draw : draws) buffer.add(draw.pose, draw.light);
        if (stats != null) stats.packed(System.nanoTime() - start);
        start = stats == null ? 0 : System.nanoTime();
        buffer.upload();
        if (stats != null) {
            stats.gpuUpload(System.nanoTime() - start);
            stats.instanceUpload(buffer.uploadedBytes());
        }
    }

    private void submit(TrackRenderState state, List<Draw> group, boolean instanced, InstanceBuffer instances, int offset,
                        Matrix4f modelView, RenderLevelStageEvent event, TrackRenderStats stats) {
        var key = group.getFirst().key;
        // Match the chunk-relative fog coordinates of the existing fixed track shaders.
        var renderOrigin = new Vector3f((float)(anchor.x() - (double)ChunkPos.getX(key.chunk) * 16),
                (float)(anchor.y() - meshOriginY), (float)(anchor.z() - (double)ChunkPos.getZ(key.chunk) * 16));
        int count = key.mesh.source.indices().length;
        int calls;
        try (var operation = NativeTurnoutLoading.firstDraw(loading)) {
            if (operation != null) operation.value("path", instanced ? "instanced" : StaticModelShader.state() == null ? "buffered" : "sharedPart");
            if (instanced) {
                var shader = InstancedModelShader.state();
                shader.prepare(StaticModelShader.LEGACY_CUTOUT, true);
                shader.renderOrigin().set(renderOrigin);
                state.prepareParts(TrackRenderTypes.part(key.texture, key.doubleSided, true),
                        modelView, event.getProjectionMatrix());
                shader.renderOrigin().upload();
                key.mesh.gpu.drawInstanced(0, count, instances.handle(), instances.offsetOf(offset), group.size(), shader.attributes());
                calls = 1;
            } else {
                for (var draw : group) {
                    var shader = StaticModelShader.state();
                    if (shader == null) buffered(state, draw, modelView, renderOrigin, event, stats);
                    else {
                        shader.prepare(draw.pose, draw.light, StaticModelShader.LEGACY_CUTOUT, true);
                        shader.renderOrigin().set(renderOrigin);
                        state.prepareParts(TrackRenderTypes.part(key.texture, key.doubleSided, false),
                                modelView, event.getProjectionMatrix());
                        shader.modelMatrix().upload(); shader.normalMatrix().upload(); shader.light().upload();
                        shader.overlay().upload(); shader.alphaPass().upload(); shader.directionalLighting().upload();
                        shader.renderOrigin().upload();
                        key.mesh.gpu.draw(false, 0, count);
                    }
                }
                calls = group.size();
            }
        }
        NativeTurnoutLoading.submitted(loading);
        if (stats != null) {
            stats.ranges(calls, calls, 0);
            stats.drawn(TrackRenderStats.RAIL_MODEL, TrackRenderStats.STRUCTURE_ASSEMBLY,
                calls, count * group.size(), count / 3 * group.size(),
                StaticModelShader.state() == null ? 0 : stats.sharedPartVertices(key.mesh), 0);
        }
    }

    private void buffered(TrackRenderState state, Draw draw, Matrix4f modelView,
                          Vector3f renderOrigin, RenderLevelStageEvent event, TrackRenderStats stats) {
        if (nativeStyle && stats != null) stats.nativeFallback("native_shader");
        if (fallback == null) fallback = new ByteBufferBuilder(1 << 16);
        var builder = new BufferBuilder(fallback, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        var mesh = draw.key.mesh.source;
        var p = new Vector3f(); var n = new Vector3f();
        for (int index : mesh.indices()) {
            int v = index * 8; var vertices = mesh.vertices();
            draw.pose.pose().transformPosition(p.set(vertices[v], vertices[v + 1], vertices[v + 2]));
            draw.pose.normal().transform(n.set(vertices[v + 5], vertices[v + 6], vertices[v + 7])).normalize();
            builder.addVertex(p.x + renderOrigin.x, p.y + renderOrigin.y, p.z + renderOrigin.z,
                    mesh.colors()[index], vertices[v + 3], vertices[v + 4],
                    OverlayTexture.NO_OVERLAY, draw.light, n.x, n.y, n.z);
        }
        state.drawBuffered(TrackRenderTypes.textured(draw.key.texture, draw.key.doubleSided), builder.buildOrThrow(),
                new Matrix4f(modelView).translate(-renderOrigin.x, -renderOrigin.y, -renderOrigin.z), event.getProjectionMatrix());
        if (stats != null) stats.activeUpload(mesh.indices().length * DefaultVertexFormat.NEW_ENTITY.getVertexSize());
    }

    @Override public void close() {
        invalidateInstances();
        irisFixedGroups.clear();
        classifiedSnapshot = null;
        movingParts.clear();
        if (fallback != null) { fallback.close(); fallback = null; }
    }

    void invalidateInstances() {
        // Keep the immutable CPU groups on backend changes; pages are re-registered on demand.
        for (var group : irisFixedGroups.values()) if (group.mesh != null) group.mesh.close();
        clearInstances();
    }

    private void clearInstances() {
        loadingPrepared = loadingInstances = loadingLight = false;
        for (int i = 0; i < preparedPartCount; i++) preparedParts.get(i).clear();
        preparedPartCount = 0;
        preparedSnapshot = preparedPrevious = null;
        preparedFraction = Float.NaN;
        if (irisFallback != null) { irisFallback.close(); irisFallback = null; }
        fixedInstances.close(); movingInstances.close();
        uploadedFixed = uploadedMoving = List.of();
        draws.clear(); fixedDraws.clear(); movingDraws.clear(); activeGroups.clear(); grouped.clear();
    }

    void removeChunk(long chunk) {
        var group = irisFixedGroups.remove(chunk);
        if (group != null) group.close();
        clearInstances();
    }
}
