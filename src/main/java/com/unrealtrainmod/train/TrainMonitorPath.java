/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.network.payload.TrainRouteProfile;
import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackTransitionResolver;
import com.unrealtrainmod.track.path.TrackCursor;
import com.unrealtrainmod.track.path.TrackPathSampler;
import com.unrealtrainmod.track.path.TrackRoute;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Driver-owned read-only geometry cache, independent of the moving consist's route window. */
final class TrainMonitorPath {
    private static final double MARGIN = 64;
    private final Set<UUID> nodes = new HashSet<>();
    private List<TrainRouteProfile.Section> sections = List.of();
    private UUID epoch;
    private int direction;
    private double baseDistance;
    private boolean dirty = true;

    void invalidate() { dirty = true; }

    void trackChanged(TrackSegment segment) {
        if (nodes.contains(segment.startNodeId()) || nodes.contains(segment.endNodeId())) invalidate();
    }

    void assemblyChanged(TrackAssemblyInstance assembly) {
        for (var port : assembly.ports().values()) {
            if (nodes.contains(port.nodeId())) {
                invalidate();
                return;
            }
        }
    }

    TrainRouteProfile profile(TrackGraph graph, TrainConsist train, TrackConnection seed, double local, int facing) {
        double shift = facing * (train.monitorDistance() - baseDistance);
        if (dirty || !train.monitorEpoch().equals(epoch) || direction != facing || Math.abs(shift) >= MARGIN
                || (!sections.isEmpty() && (shift < sections.getFirst().startOffset() || shift > sections.getLast().endOffset()))) {
            rebuild(graph, train, seed, local, facing);
            shift = 0;
        }
        var visible = new ArrayList<TrainRouteProfile.Section>();
        for (var section : sections) {
            double start = section.startOffset() - shift, end = section.endOffset() - shift;
            if (end < -TrainRouteProfile.BEHIND || start > TrainRouteProfile.AHEAD) continue;
            // Keep original endpoints: clipping must preserve a segment's schematic slope.
            visible.add(new TrainRouteProfile.Section(start, end, section.grade(), section.radius()));
        }
        return new TrainRouteProfile(visible);
    }

    private void rebuild(TrackGraph graph, TrainConsist train, TrackConnection seed, double local, int facing) {
        dirty = false;
        epoch = train.monitorEpoch();
        direction = facing;
        baseDistance = train.monitorDistance();
        sections = List.of();
        nodes.clear();
        var current = graph.segment(seed.segmentId());
        nodes.add(current.startNodeId());
        nodes.add(current.endNodeId());
        if (!TrackTransitionResolver.isSegmentEnabled(graph, seed.segmentId())) return;
        double length = current.geometry().getLength();
        var preview = TrackRoute.preview(graph, seed,
                Math.max(0, TrainRouteProfile.BEHIND + MARGIN - local),
                Math.max(0, TrainRouteProfile.AHEAD + MARGIN - (length - local)), TrainRouteProfile.MAX_SECTIONS);
        var route = preview.route();
        double reference = new TrackPathSampler(route).distance(new TrackCursor(preview.prefixCount(), local));
        double start = -reference;
        var result = new ArrayList<TrainRouteProfile.Section>(route.connections().size());
        for (int i = 0; i < route.connections().size(); i++) {
            var step = route.connections().get(i);
            var segment = graph.segment(step.segmentId());
            nodes.add(segment.startNodeId());
            nodes.add(segment.endNodeId());
            var approximation = segment.profile();
            double end = start + route.segmentLength(i);
            result.add(new TrainRouteProfile.Section(start, end,
                    (step.fromStart() ? 1 : -1) * approximation.grade(),
                    (step.fromStart() ? 1 : -1) * approximation.radius()));
            start = end;
        }
        sections = List.copyOf(result);
    }
}
