/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.assembly.TrackTransitionResolver;
import com.unrealtrainmod.track.assembly.TrackTraversalContext;
import com.unrealtrainmod.track.path.TrackCursor;
import com.unrealtrainmod.track.path.TrackPathSampler;
import com.unrealtrainmod.track.path.TrackRoute;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehiclePoseSolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-consist runtime cache. All edits and coordinate rebases belong to the owning server thread. */
final class TrainRouteWindow {
    private static final double REAR_MARGIN = 32;
    private final TrackRoute route;
    private final TrackPathSampler sampler;
    private final Set<UUID> nodes;
    private final Map<UUID, List<Integer>> segments;
    private final boolean startBoundary;
    private final boolean endBoundary;
    private final double preparedSpeed;
    private double progress;
    private boolean refreshRequested;

    private record Span(double min, double max) {}
    private record Slice(int first, int end) {}

    private TrainRouteWindow(TrackGraph graph, TrackRoute route, double progress,
                             boolean startBoundary, boolean endBoundary, double preparedSpeed) {
        this.route = route;
        this.sampler = new TrackPathSampler(route);
        this.progress = progress;
        this.startBoundary = startBoundary;
        this.endBoundary = endBoundary;
        this.preparedSpeed = preparedSpeed;
        var nodeIds = new HashSet<UUID>();
        var segmentIndices = new HashMap<UUID, List<Integer>>();
        for (int i = 0; i < route.connections().size(); i++) {
            var connection = route.connections().get(i);
            var segment = graph.segment(connection.segmentId());
            nodeIds.add(segment.startNodeId());
            nodeIds.add(segment.endNodeId());
            segmentIndices.computeIfAbsent(segment.id(), ignored -> new ArrayList<>()).add(i);
        }
        nodes = Set.copyOf(nodeIds);
        segmentIndices.replaceAll((id, indices) -> List.copyOf(indices));
        segments = Map.copyOf(segmentIndices);
    }

    static TrainRouteWindow initial(TrackGraph graph, TrainAnchor anchor, TrainConsist train) {
        var seed = new TrackConnection(anchor.segmentId(), anchor.fromStart());
        double length = graph.segment(anchor.segmentId()).geometry().getLength();
        double local = anchor.fromStart() ? anchor.distance() : length - anchor.distance();
        double min = 0, max = 0;
        for (double offset : TrainMotion.sampleOffsets(train)) {
            min = Math.min(min, offset);
            max = Math.max(max, offset);
        }
        for (var car : train.cars()) {
            var model = VehicleCatalog.find(car.modelId());
            min = Math.min(min, car.offset() + TrackOccupancyManager.rear(model));
            max = Math.max(max, car.offset() + TrackOccupancyManager.front(model));
        }
        double speed = train.planningSpeed();
        double ahead = Math.max(64, 2 * Math.abs(speed));
        double before = Math.max(0, (speed > 0 ? REAR_MARGIN : ahead) - (local + min));
        double after = Math.max(0, local + max + (speed > 0 ? ahead : REAR_MARGIN) - length);
        var extension = TrackRoute.extend(graph, List.of(seed), before, after, context(train, false));
        var window = new TrainRouteWindow(graph, extension.route(), 0,
                extension.startBoundary(), extension.endBoundary(), speed);
        window.setProgress(window.sampler().distance(new TrackCursor(extension.prefixCount(), local)));
        // Saved segment coordinates can land a few ulps beyond the newly accumulated finite limit.
        var range = TrainMotion.range(window.route().getLength(), train.cars());
        if (!range.contains(window.progress())) {
            if (window.startBoundary() && TrainMotion.sameDistance(window.progress(), range.min())) {
                window.setProgress(range.min());
            } else if (window.endBoundary() && TrainMotion.sameDistance(window.progress(), range.max())) {
                window.setProgress(range.max());
            }
        }
        // Initial binding/recovery is strict; the first read-only maintenance may discover trailing candidates.
        window.requestRefresh();
        return window;
    }

    TrackRoute route() { return route; }
    TrackPathSampler sampler() { return sampler; }
    double progress() { return progress; }
    boolean startBoundary() { return startBoundary; }
    boolean endBoundary() { return endBoundary; }
    boolean usesNode(UUID id) { return nodes.contains(id); }
    boolean usesSegment(UUID id) { return segments.containsKey(id); }
    List<Integer> occurrences(UUID id) { return segments.getOrDefault(id, List.of()); }
    void setProgress(double progress) { this.progress = progress; }
    void requestRefresh() { refreshRequested = true; }
    boolean refreshRequested() { return refreshRequested; }

    /** Includes the full chain between all member reference points, bodies, couplers and bogies. */
    boolean occupies(UUID segment, TrainConsist train) {
        var indices = occurrences(segment);
        if (indices.isEmpty()) return false;
        var core = core(train);
        return indices.stream().anyMatch(index -> index >= core.first() && index < core.end());
    }

    boolean occupiedPathsEnabled(TrackGraph graph, TrainConsist train) {
        return pathsEnabledAt(graph, train, progress, context(train, false));
    }

    static TrackTraversalContext context(TrainConsist train, boolean candidates) {
        return new TrackTraversalContext(train.cars().stream().map(TrainCarState::entityId).toList(), candidates, train.planningDirection() > 0);
    }

    boolean pathsEnabledAt(TrackGraph graph, TrainConsist train, double at, TrackTraversalContext context) {
        var occupied = core(train, at);
        return TrackTransitionResolver.isRouteEnabled(graph, route.connections().subList(occupied.first(), occupied.end()), context);
    }

    TrainRouteWindow copyAt(TrackGraph graph, double reference) {
        var copy = new TrainRouteWindow(graph, route, reference, startBoundary, endBoundary, preparedSpeed);
        copy.requestRefresh();
        return copy;
    }

    /** Returns a candidate only; the manager validates all member checkpoints before installing it. */
    TrainRouteWindow maintain(TrackGraph graph, TrainConsist train) {
        double speed = train.planningSpeed();
        var span = span(train);
        double ahead = Math.max(64, 2 * Math.abs(speed));
        double before = speed > 0 ? REAR_MARGIN : ahead;
        double after = speed > 0 ? ahead : REAR_MARGIN;
        // The lookahead also covers the next full tick before movement is split into substeps.
        double movement = speed / 20;
        boolean refill = refreshRequested || speed * preparedSpeed < 0
                || ahead > Math.max(64, 2 * Math.abs(preparedSpeed))
                || (!startBoundary && span.min() < before / 2 + Math.max(0, -movement))
                || (!endBoundary && route.getLength() - span.max() < after / 2 + Math.max(0, movement));
        if (!refill) return crop(graph, train);

        var core = core(train);
        double coreStart = sampler.distance(new TrackCursor(core.first(), 0));
        double coreEnd = sampler.distance(new TrackCursor(core.end() - 1, route.segmentLength(core.end() - 1)));
        double extendBefore = Math.max(0, before - (span.min() - coreStart));
        double extendAfter = Math.max(0, after - (coreEnd - span.max()));
        var extension = TrackRoute.extend(graph, route.connections().subList(core.first(), core.end()), extendBefore, extendAfter, context(train, true));
        var candidate = new TrainRouteWindow(graph, extension.route(), 0,
                extension.startBoundary(), extension.endBoundary(), speed);
        var reference = sampler.locate(progress);
        int mapped = extension.prefixCount() + reference.segmentIndex() - core.first();
        candidate.setProgress(candidate.sampler().distance(new TrackCursor(mapped, reference.distance())));
        boolean sameStart = startBoundary && candidate.startBoundary() && core.first() == 0 && extension.prefixCount() == 0;
        boolean sameEnd = endBoundary && candidate.endBoundary() && core.end() == route.connections().size()
                && extension.prefixCount() + core.end() - core.first() == candidate.route().connections().size();
        return preserveEndpoints(candidate, train.cars(), sameStart, sameEnd);
    }

    private TrainRouteWindow preserveEndpoints(TrainRouteWindow candidate, List<TrainCarState> cars,
                                               boolean sameStart, boolean sameEnd) {
        var oldRange = TrainMotion.range(route.getLength(), cars);
        var newRange = TrainMotion.range(candidate.route().getLength(), cars);
        // Keep exact whole-consist endpoints when the corresponding physical end is unchanged.
        if (sameStart && progress == oldRange.min()) {
            candidate.setProgress(newRange.min());
        } else if (sameEnd && progress == oldRange.max()) {
            candidate.setProgress(newRange.max());
        }
        return candidate;
    }

    private Span span(TrainConsist train) { return span(train, progress); }

    private Span span(TrainConsist train, double at) {
        double min = at, max = at;
        for (var car : train.cars()) {
            var spec = VehicleCatalog.find(car.modelId()).motionSpec();
            var range = VehiclePoseSolver.range(route.getLength(), spec);
            double reference = TrainMotion.carProgress(at, car.offset(), range);
            // Match solve()'s arithmetic order and exact endpoints; combined offsets can round
            // to the other side of a joint and accidentally omit a bogie's outgoing segment.
            double front = reference == range.max() && spec.frontBogieOffset() >= 0
                    ? route.getLength() : reference + spec.frontBogieOffset();
            double rear = reference == range.min() && spec.rearBogieOffset() <= 0
                    ? 0 : reference + spec.rearBogieOffset();
            min = Math.min(min, Math.min(reference, Math.min(front, rear)));
            max = Math.max(max, Math.max(reference, Math.max(front, rear)));
            var model = VehicleCatalog.find(car.modelId());
            min = Math.min(min, reference + TrackOccupancyManager.rear(model));
            max = Math.max(max, reference + TrackOccupancyManager.front(model));
        }
        // Whole-consist range checks own validity; absorb endpoint arithmetic rounding here.
        return new Span(Math.clamp(min, 0, route.getLength()), Math.clamp(max, 0, route.getLength()));
    }

    private Slice core(TrainConsist train) { return core(train, progress); }

    private Slice core(TrainConsist train, double at) {
        var span = span(train, at);
        var first = sampler.locate(span.min());
        // Keep the previous side of an exact joint for saved endpoint aliases and committed choices.
        int begin = first.segmentIndex();
        if (first.distance() == 0 && begin > 0) begin--;
        return new Slice(begin, sampler.locate(span.max()).segmentIndex() + 1);
    }

    private TrainRouteWindow crop(TrackGraph graph, TrainConsist train) {
        var span = span(train);
        double ahead = Math.max(64, 2 * Math.abs(preparedSpeed));
        double min = Math.max(0, span.min() - (preparedSpeed > 0 ? REAR_MARGIN : ahead));
        double max = Math.min(route.getLength(), span.max() + (preparedSpeed > 0 ? ahead : REAR_MARGIN));
        int first = sampler.locate(min).segmentIndex();
        int end = sampler.locate(max).segmentIndex() + 1;
        if (first == 0 && end == route.connections().size()) return this;
        var cropped = new TrainRouteWindow(graph, route.slice(first, end), 0,
                startBoundary && first == 0, endBoundary && end == route.connections().size(), preparedSpeed);
        // Map this exact occurrence; the same segment may appear many times in either snapshot.
        var reference = sampler.locate(progress);
        cropped.setProgress(cropped.sampler().distance(new TrackCursor(reference.segmentIndex() - first, reference.distance())));
        return preserveEndpoints(cropped, train.cars(), cropped.startBoundary(), cropped.endBoundary());
    }
}
