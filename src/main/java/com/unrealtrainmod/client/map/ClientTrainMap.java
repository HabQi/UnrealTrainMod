/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.map;

import com.unrealtrainmod.network.payload.TrainMapUpdatePayload;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.TrackSegmentData;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.train.map.TrainMapData.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Owned by one screen/session; contains CPU polylines only, never world-rendering meshes. */
public final class ClientTrainMap {
    private static final int SAMPLES_PER_BLOCK = 2;
    public record TrackLine(TrackGeometry geometry, List<TrackVector> points, double minX, double minZ, double maxX, double maxZ) {
        static TrackLine create(TrackSegmentData data) {
            var geometry = data.createGeometry();
            var points = sample(geometry, 0, geometry.getLength(), data.type() == TrackSegment.Type.STRAIGHT);
            double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
            for (var point : points) {
                minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
                minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
            }
            return new TrackLine(geometry, points, minX, minZ, maxX, maxZ);
        }
    }
    public record Overlay(Occupancy occupancy, List<TrackVector> points) {}
    public final UUID session;
    public final ResourceLocation dimension;
    private Map<UUID, TrackLine> tracks = Map.of();
    private List<Train> trains = List.of();
    private List<Turnout> turnouts = List.of();
    private List<Occupancy> occupancy = List.of();
    private List<Overlay> overlays = List.of();
    private Map<UUID, Boolean> paths = Map.of();
    private final Batch topology = new Batch();
    private final Batch dynamic = new Batch();
    private boolean topologyReady, dynamicReady;
    private int receivedAt;
    private String error;

    public ClientTrainMap(UUID session, ResourceLocation dimension) { this.session = session; this.dimension = dimension; }
    public Map<UUID, TrackLine> tracks() { return tracks; }
    public List<Train> trains() { return trains; }
    public List<Turnout> turnouts() { return turnouts; }
    public List<Overlay> overlays() { return overlays; }
    public Map<UUID, Boolean> paths() { return paths; }
    public boolean ready() { return topologyReady && dynamicReady; }
    public String error() { return error; }
    public boolean stale(int now) { return error != null || (dynamicReady && now - receivedAt > 60); }
    public void fail(String key) { error = key; topology.entries.clear(); dynamic.entries.clear(); }
    public void clear() {
        tracks = Map.of(); trains = List.of(); turnouts = List.of(); occupancy = List.of();
        overlays = List.of(); paths = Map.of(); topology.entries.clear(); dynamic.entries.clear();
        topologyReady = false; dynamicReady = false;
    }

    public void accept(TrainMapUpdatePayload payload, int now) {
        if (error != null || !session.equals(payload.session()) || !dimension.equals(payload.dimension())) return;
        var batch = payload.channel() == TrainMapUpdatePayload.Channel.TOPOLOGY ? topology : dynamic;
        if (!batch.accept(payload)) return;
        if (payload.channel() == TrainMapUpdatePayload.Channel.TOPOLOGY) {
            var next = payload.reset() ? new LinkedHashMap<UUID, TrackLine>() : new LinkedHashMap<>(tracks);
            for (var entry : batch.entries) {
                if (entry instanceof Track value) next.put(value.segment().id(), TrackLine.create(value.segment()));
                else if (entry instanceof Removed value) next.remove(value.segmentId());
            }
            tracks = Map.copyOf(next);
            topologyReady = true;
        } else {
            var nextTrains = new ArrayList<Train>();
            var nextTurnouts = new ArrayList<Turnout>();
            var nextOccupancy = new ArrayList<Occupancy>();
            var nextPaths = new HashMap<UUID, Boolean>();
            for (var entry : batch.entries) {
                if (entry instanceof Train value) nextTrains.add(value);
                else if (entry instanceof Turnout value) nextTurnouts.add(value);
                else if (entry instanceof Occupancy value) nextOccupancy.add(value);
                else if (entry instanceof Path value) nextPaths.put(value.segmentId(), value.enabled());
            }
            nextTrains.sort(Comparator.comparing(Train::id));
            trains = List.copyOf(nextTrains);
            turnouts = List.copyOf(nextTurnouts);
            occupancy = List.copyOf(nextOccupancy);
            paths = Map.copyOf(nextPaths);
            dynamicReady = true;
            receivedAt = now;
        }
        batch.entries.clear();
        rebuildOverlays();
    }

    private void rebuildOverlays() {
        var result = new ArrayList<Overlay>();
        for (var value : occupancy) {
            var track = tracks.get(value.segmentId());
            if (track != null) result.add(new Overlay(value, sample(track.geometry(), value.start(), value.end(), false)));
        }
        // Conflicts are drawn last, above ordinary occupancy.
        result.sort(Comparator.comparing(value -> value.occupancy().otherTrain() != null));
        overlays = List.copyOf(result);
    }

    private static List<TrackVector> sample(TrackGeometry geometry, double start, double end, boolean straight) {
        int steps = straight ? 1 : Math.max(1, Math.min(512 * SAMPLES_PER_BLOCK, (int)Math.ceil((end - start) * SAMPLES_PER_BLOCK)));
        var points = new ArrayList<TrackVector>(steps + 1);
        for (int i = 0; i <= steps; i++) points.add(geometry.sample(start + (end - start) * i / steps).position());
        return List.copyOf(points);
    }

    private static final class Batch {
        private long applied = -1, sequence = -1;
        private int nextIndex, count;
        private boolean reset;
        private final List<Entry> entries = new ArrayList<>();
        boolean accept(TrainMapUpdatePayload payload) {
            if (payload.sequence() <= applied || payload.sequence() < sequence) return false;
            if (payload.index() == 0) {
                entries.clear(); sequence = payload.sequence(); nextIndex = 0;
                count = payload.count(); reset = payload.reset();
            }
            if (payload.sequence() != sequence || payload.index() != nextIndex
                    || payload.count() != count || payload.reset() != reset) return false;
            entries.addAll(payload.entries());
            nextIndex++;
            if (nextIndex != count) return false;
            applied = sequence;
            return true;
        }
    }
}
