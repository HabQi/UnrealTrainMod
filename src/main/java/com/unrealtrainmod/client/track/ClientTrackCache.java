/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrackChunkPayload;
import com.unrealtrainmod.network.payload.TrackChunkUnloadPayload;
import com.unrealtrainmod.network.payload.TrackSegmentRemovePayload;
import com.unrealtrainmod.track.TrackSegmentData;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.assembly.SwitchPosition;
import com.unrealtrainmod.compat.rtm.script.RailScriptRuntime;
import com.unrealtrainmod.compat.rtm.script.RailTurnoutView;
import com.unrealtrainmod.compat.rtm.script.RailDrawSnapshot;
import com.unrealtrainmod.compat.rtm.script.RailStaticSnapshot;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.network.payload.TrackAssemblyPayload;
import com.unrealtrainmod.network.payload.TrackAssemblyStatePayload;
import com.unrealtrainmod.network.payload.TrackAssemblyRemovePayload;
import java.util.Collections;
import java.util.ArrayList;
import org.joml.Matrix4f;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Client-thread cache. Geometry is shared by UUID; each owning chunk keeps its own meshes. */
public final class ClientTrackCache implements ResourceManagerReloadListener, AutoCloseable {
    public static final ClientTrackCache INSTANCE = new ClientTrackCache();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ClientTrackCache.class);
    final Map<UUID, Entry> entries = new LinkedHashMap<>();
    final Map<Long, Set<UUID>> chunks = new LinkedHashMap<>();
    final Map<UUID, AssemblyEntry> assemblies = new LinkedHashMap<>();
    private final Map<UUID, TrackAssemblyStatePayload> states = new HashMap<>();
    private final Set<UUID> deletedAssemblies = new HashSet<>();
    /** Failed script variants awaiting a static rebuild; identity keeps reloaded variants apart. */
    private final Set<ClientRailModelManager.Model> scriptFailures =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private ClientLevel world;
    private long ticks;
    private double displayTime;

    double time(float partial) {
        var mc = Minecraft.getInstance();
        if (!mc.isPaused() && world.tickRateManager().runsNormally()) displayTime = ticks - 1 + partial;
        return displayTime;
    }

    public void onTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (world == null || mc.level != world || mc.isPaused() || !world.tickRateManager().runsNormally()) return;
        ticks++;
        for (var entry : assemblies.values()) {
            boolean prepared = entry.prepared();
            for (var turnout : entry.turnouts) {
                if (ClientConfig.TURNOUT_ANIMATION_DURATION_SECONDS.get() == 0) turnout.animation.finish();
                if (prepared) turnout.captureDynamic(ticks);
            }
        }
    }

    void collectStats(TrackRenderStats stats, double time) {
        var script = RailScriptRuntime.drainMetrics();
        if (stats != null) {
            int moving = 0;
            for (var entry : assemblies.values()) if (entry.moving(time)) moving++;
            stats.animation(moving, script.calls(), script.nanos());
        }
    }

    private ClientTrackCache() {}

    public void onSync(TrackSyncEvent event) {
        if (event.payload() instanceof TrackAssemblyPayload payload && accepts(payload.dimension())) {
            var data = payload.assembly();
            if (deletedAssemblies.contains(data.id())) return;
            var latest = states.get(data.id());
            if (latest != null && latest.revision() > data.revision()) data = state(data, latest);
            var entry = assemblies.get(data.id());
            if (entry == null) { entry = new AssemblyEntry(data); assemblies.put(data.id(), entry); }
            else if (data.revision() > entry.data.revision()) entry.setState(data);
            entry.chunks.add(ChunkPos.asLong(payload.chunkX(), payload.chunkZ()));
            states.put(data.id(), new TrackAssemblyStatePayload(payload.dimension(), data.id(), entry.data.state(), entry.data.revision()));
        } else if (event.payload() instanceof TrackAssemblyStatePayload payload && accepts(payload.dimension())) {
            if (deletedAssemblies.contains(payload.id())) return;
            var latest = states.get(payload.id());
            if (latest != null && latest.revision() >= payload.revision()) return;
            states.put(payload.id(), payload);
            var entry = assemblies.get(payload.id());
            if (entry != null) entry.setState(state(entry.data, payload));
        } else if (event.payload() instanceof TrackAssemblyRemovePayload payload && accepts(payload.dimension())) {
            deletedAssemblies.add(payload.id()); states.remove(payload.id());
            var entry = assemblies.remove(payload.id());
            if (entry != null) entry.close();
        } else if (event.payload() instanceof TrackChunkPayload payload && accepts(payload.dimension())) {
            long chunk = ChunkPos.asLong(payload.chunkX(), payload.chunkZ());
            if (payload.reset()) removeChunk(chunk);
            if (payload.segments().isEmpty()) return;
            Set<UUID> members = chunks.computeIfAbsent(chunk, ignored -> new LinkedHashSet<>());
            for (TrackSegmentData data : payload.segments()) {
                Entry entry = entries.get(data.id());
                if (entry == null) {
                    entry = new Entry(data);
                    entries.put(data.id(), entry);
                } else if (!entry.data.equals(data)) {
                    entry.replace(data);
                }
                members.add(data.id());
                entry.chunks.add(chunk);
            }
        } else if (event.payload() instanceof TrackChunkUnloadPayload payload && accepts(payload.dimension())) {
            removeChunk(ChunkPos.asLong(payload.chunkX(), payload.chunkZ()));
        } else if (event.payload() instanceof TrackSegmentRemovePayload payload && accepts(payload.dimension())) {
            removeSegment(payload.segmentId());
        }
    }

    private boolean accepts(ResourceLocation dimension) {
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null || !current.dimension().location().equals(dimension)) return false;
        if (world != current) setWorld(current);
        return true;
    }

    private void removeChunk(long chunk) {
        var iterator = assemblies.values().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.chunks.remove(chunk)) continue;
            closeMeshes(entry.meshes.remove(chunk));
            closeMeshes(entry.tongues.remove(chunk));
            for (var turnout : entry.turnouts) {
                var mesh = turnout.meshes.remove(chunk);
                if (mesh != null) mesh.close();
                if (turnout.parts != null) turnout.parts.invalidateInstances();
            }

            if (entry.chunks.isEmpty()) { entry.close(); iterator.remove(); }
        }
        Set<UUID> members = chunks.remove(chunk);
        if (members == null) return;
        for (UUID id : members) {
            Entry entry = entries.get(id);
            entry.chunks.remove(chunk);
            TrackRenderMesh mesh = entry.meshes.remove(chunk);
            if (mesh != null) mesh.close();
            if (entry.chunks.isEmpty()) {
                entry.close();
                entries.remove(id);
            }
        }
    }

    private void removeSegment(UUID id) {
        Entry entry = entries.remove(id);
        if (entry == null) return;
        for (long chunk : entry.chunks) {
            Set<UUID> members = chunks.get(chunk);
            members.remove(id);
            if (members.isEmpty()) chunks.remove(chunk);
        }
        entry.close();
    }

    public void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel level) setWorld(level);
    }

    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == world) close();
    }

    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        close();
    }

    // Same-dimension player clones keep the ClientLevel and its chunk cache; do not clear on Clone.
    private void setWorld(ClientLevel level) {
        close();
        world = level;
    }

    ClientLevel world() { return world; }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        invalidateMeshes();
    }

    /** Backend switches retain CPU geometry, scripts and animation timelines. */
    public void invalidateGpuBuffers() {
        for (var entry : entries.values()) entry.meshes.values().forEach(TrackRenderMesh::close);
        for (var entry : assemblies.values()) {
            entry.meshes.values().forEach(group -> group.forEach(TrackRenderMesh::close));
            entry.tongues.values().forEach(group -> group.forEach(TrackRenderMesh::close));
            for (var turnout : entry.turnouts) {
                turnout.meshes.values().forEach(TrackRenderMesh::close);
                if (turnout.parts != null) turnout.parts.invalidateInstances();
            }
        }
        TrackStaticBuffers.WORLD.close();
        TrackWorldRenderer.releaseDrawQueue();
    }

    public void invalidateMeshes() {
        for (Entry entry : entries.values()) entry.close();
        for (var entry : assemblies.values()) entry.close();
        scriptFailures.clear();
        TrackWorldRenderer.releaseDrawQueue();
    }

    /** Marks every mesh built from this prepared model for a static rebuild after its script failed. */
    public void invalidateScriptedModel(ClientRailModelManager.Model model) {
        if (model != null) scriptFailures.add(model);
    }

    /**
     * Closes affected meshes between frames, never while a cache map is being iterated. The next build sees the
     * failed runtime and uses the full static geometry.
     */
    public void flushScriptFailures() {
        if (scriptFailures.isEmpty()) return;
        for (Entry entry : entries.values()) {
            if (scriptFailures.contains(ClientRailModelManager.INSTANCE.model(entry.data.modelId()))) entry.close();
        }
        for (var entry : assemblies.values()) {
            // Turnout views own independent script globals, including both crossover ends.
            if (entry.turnouts.isEmpty()
                    && scriptFailures.contains(ClientRailModelManager.INSTANCE.model(entry.data.modelId()))) entry.close();
        }
        scriptFailures.clear();
    }

    @Override
    public void close() {
        for (Entry entry : entries.values()) entry.close();
        for (var entry : assemblies.values()) entry.close();
        assemblies.clear(); states.clear(); deletedAssemblies.clear();
        scriptFailures.clear();
        entries.clear();
        chunks.clear();
        TrackStaticBuffers.WORLD.close();
        TrackWorldRenderer.releaseDrawQueue();
        world = null;
        ticks = 0; displayTime = -1;
        // The statistics window keys on the level object; release it with the cache.
        TrackRenderStats.reset();
        RailScriptRuntime.drainMetrics();
    }

    private static TrackAssemblyInstance state(TrackAssemblyInstance value, TrackAssemblyStatePayload state) {
        return value.withState(state.state(), state.revision());
    }

    private static void closeMeshes(List<TrackRenderMesh> meshes) {
        if (meshes == null) return;
        for (var mesh : meshes) mesh.close();
    }

    static final class AssemblyEntry implements AutoCloseable {
        TrackAssemblyInstance data;
        final List<TrackGeometry> geometries;
        final Set<Long> chunks = new LinkedHashSet<>();
        final Map<Long, List<TrackRenderMesh>> meshes = new HashMap<>();
        final Map<Long, List<TrackRenderMesh>> tongues = new HashMap<>();
        final List<TurnoutEntry> turnouts;
        boolean scriptFailed, scriptActive, missingReported;
        long scriptPrepareFrame = -1;
        private int prepareCursor;

        AssemblyEntry(TrackAssemblyInstance data) {
            this.data = data;
            turnouts = RailTurnoutView.of(data).stream().map(view -> new TurnoutEntry(this, view)).toList();
            var segments = new LinkedHashMap<UUID, TrackGeometry>();
            for (var entry : data.paths().entrySet()) {
                var parts = data.geometry().paths().get(entry.getKey());
                var steps = entry.getValue().steps();
                for (int i = 0; i < steps.size(); i++) {
                    var step = steps.get(i);
                    var part = parts.get(i);
                    segments.putIfAbsent(step.segmentId(), step.fromStart() ? part : part.reversed());
                }
            }
            geometries = List.copyOf(segments.values());
        }

        AABB broadBounds() {
            var model = ClientRailModelManager.INSTANCE.model(data.modelId());
            return model == null ? data.bounds() : data.bounds().inflate(model.padding() * model.scaleForGauge(data.gauge()));
        }

        void setState(TrackAssemblyInstance data) {
            for (var turnout : turnouts) turnout.setState(data);
            this.data = data;
        }

        boolean moving(double time) {
            return (scriptActive || data.definition() == TrackAssemblyDefinition.CUSTOM)
                    && turnouts.stream().anyMatch(turnout -> turnout.animation.moving(time));
        }

        boolean prepared() { return !turnouts.isEmpty() && turnouts.stream().allMatch(TurnoutEntry::prepared); }

        TurnoutEntry nextPreparation() {
            for (int i = 0; i < turnouts.size(); i++) {
                var turnout = turnouts.get(prepareCursor);
                prepareCursor = (prepareCursor + 1) % turnouts.size();
                if (!turnout.prepared()) return turnout;
            }
            return null;
        }

        void activateScripts() {
            clearFallback();
            scriptActive = true;
        }

        void failScripts(TurnoutEntry turnout, String phase, Throwable failure) {
            if (scriptFailed) return;
            LOGGER.warn("Turnout {}/{} failed at {}; using complete assembly fallback appearance: {}",
                    data.id(), turnout.view.end(), phase, failure == null ? "script capture failed" : failure.getMessage());
            if (failure != null) LOGGER.debug("Turnout view failure", failure);
            scriptFailed = true;
            scriptActive = false;
            for (var child : turnouts) child.close();
        }

        private void clearFallback() {
            meshes.values().forEach(ClientTrackCache::closeMeshes);
            tongues.values().forEach(ClientTrackCache::closeMeshes);
            meshes.clear(); tongues.clear();
        }

        @Override public void close() {
            clearFallback();
            turnouts.forEach(TurnoutEntry::close);
            scriptFailed = scriptActive = missingReported = false;
            scriptPrepareFrame = -1;
            prepareCursor = 0;
        }
    }

    /** One stable script/timeline per rendering end; ownership and chunk lifetime remain on the parent. */
    static final class TurnoutEntry implements AutoCloseable {
        final AssemblyEntry owner;
        final RailTurnoutView view;
        final Set<Long> chunks;
        final Map<Long, TrackStaticMesh> meshes = new HashMap<>();
        final TurnoutAnimation animation;
        RailStaticSnapshot staticSnapshot;
        RailDrawSnapshot previousSnapshot, currentSnapshot;
        double previousSnapshotTime, currentSnapshotTime;
        long captureTick = Long.MIN_VALUE;
        float capturedMovement = Float.NaN;
        RailPartRenderer parts;
        RailScriptRuntime assemblyScript;
        boolean scriptAttempted;

        TurnoutEntry(AssemblyEntry owner, RailTurnoutView view) {
            this.owner = owner; this.view = view; chunks = owner.chunks;
            animation = new TurnoutAnimation(position(owner.data));
        }

        private float position(TrackAssemblyInstance data) {
            return data.state().controls().get(view.control()) == SwitchPosition.REVERSE ? 1 : 0;
        }

        boolean prepared() { return staticSnapshot != null && currentSnapshot != null && parts != null; }

        void setState(TrackAssemblyInstance data) {
            float target = position(data);
            double time = INSTANCE.displayTime;
            if (target != position(owner.data) && currentSnapshot != null) {
                float fraction = snapshotFraction(time);
                var previous = currentSnapshot.matches(previousSnapshot) ? previousSnapshot : null;
                var rebased = new ArrayList<RailDrawSnapshot.Part>();
                for (int i = 0; i < currentSnapshot.parts().size(); i++) {
                    rebased.add(new RailDrawSnapshot.Part(currentSnapshot.parts().get(i).source(),
                            currentSnapshot.interpolate(previous, i, fraction, new Matrix4f())));
                }
                previousSnapshot = currentSnapshot = new RailDrawSnapshot(rebased);
                previousSnapshotTime = currentSnapshotTime = time;
            }
            animation.target(target, time, ClientConfig.TURNOUT_ANIMATION_DURATION_SECONDS.get());
        }

        float snapshotFraction(double time) {
            if (currentSnapshotTime <= previousSnapshotTime) return 1;
            return (float)Math.clamp((time - previousSnapshotTime) / (currentSnapshotTime - previousSnapshotTime), 0, 1);
        }

        void captureDynamic(double time) {
            if (!scriptAttempted || assemblyScript == null || owner.scriptFailed || captureTick == INSTANCE.ticks) return;
            float movement = animation.position(time);
            if (currentSnapshot != null && movement == capturedMovement) return;
            captureTick = INSTANCE.ticks;
            var model = ClientRailModelManager.INSTANCE.model(owner.data.modelId());
            var snapshot = assemblyScript.captureAssemblyDynamic(movement, model.scaleForGauge(owner.data.gauge()), model.period());
            if (snapshot == null) { owner.failScripts(this, "dynamic capture", null); return; }
            previousSnapshot = currentSnapshot;
            previousSnapshotTime = currentSnapshotTime;
            currentSnapshot = snapshot;
            currentSnapshotTime = time;
            capturedMovement = movement;
            if (animation.instant() || previousSnapshot == null || !snapshot.matches(previousSnapshot)) {
                previousSnapshot = snapshot;
                previousSnapshotTime = time;
            }
        }

        @Override public void close() {
            meshes.values().forEach(TrackRenderMesh::close);
            meshes.clear();
            staticSnapshot = null;
            previousSnapshot = currentSnapshot = null;
            captureTick = Long.MIN_VALUE;
            capturedMovement = Float.NaN;
            if (parts != null) { parts.close(); parts = null; }
            if (assemblyScript != null) assemblyScript.close();
            assemblyScript = null;
            scriptAttempted = false;
        }
    }

    static final class Entry implements AutoCloseable {
        TrackSegmentData data;
        TrackGeometry geometry;
        final Set<Long> chunks = new HashSet<>();
        final Map<Long, TrackRenderMesh> meshes = new HashMap<>();
        RailStaticSnapshot staticSnapshot;
        boolean missingReported;

        private Entry(TrackSegmentData data) {
            this.data = data;
            geometry = data.createGeometry();
        }

        private void replace(TrackSegmentData data) {
            TrackGeometry updated = data.createGeometry();
            close();
            this.data = data;
            geometry = updated;
            missingReported = false;
        }

        AABB broadBounds(int chunkX, int chunkZ) {
            double padding = (data.gauge() + 0.9) / 2 + 0.4;
            var model = ClientRailModelManager.INSTANCE.model(data.modelId());
            if (model != null) padding = Math.max(padding, model.padding() * model.scaleForGauge(data.gauge()));
            double x = (double)chunkX * 16, z = (double)chunkZ * 16;
            double verticalPadding = model == null ? 0.4 : Math.max(0.4, padding);
            double minY = Math.min(data.start().y(), data.end().y());
            double maxY = Math.max(data.start().y(), data.end().y());
            for (var control : data.verticalControls()) {
                minY = Math.min(minY, control.z());
                maxY = Math.max(maxY, control.z());
            }
            return new AABB(x - padding, minY - verticalPadding, z - padding,
                    x + 16 + padding, maxY + verticalPadding, z + 16 + padding);
        }

        @Override
        public void close() {
            for (TrackRenderMesh mesh : meshes.values()) mesh.close();
            meshes.clear();
            staticSnapshot = null;
        }
    }
}
