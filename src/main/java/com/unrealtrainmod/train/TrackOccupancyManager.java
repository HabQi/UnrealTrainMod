/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.track.path.TrackRoute;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Committed footprints in physical segment coordinates, independent of travel direction and lap. */
public final class TrackOccupancyManager {
    public record Interval(UUID segmentId, double start, double end) {}
    public record Conflict(UUID trainId, UUID segmentId, double start, double end) {}
    private final Map<UUID, List<Interval>> trains = new HashMap<>();
    private final Map<UUID, Map<UUID, List<Interval>>> segments = new HashMap<>();

    static double rear(VehicleDefinition model) {
        var spec = model.motionSpec();
        return Math.min(0, Math.min(model.bounds().minZ(), Math.min(spec.rearCouplerOffset(), spec.rearBogieOffset())));
    }

    static double front(VehicleDefinition model) {
        var spec = model.motionSpec();
        return Math.max(0, Math.max(model.bounds().maxZ(), Math.max(spec.frontCouplerOffset(), spec.frontBogieOffset())));
    }

    /** The connected consist covers its members and the short gaps between them. */
    static List<Interval> footprint(TrainConsist train, double progress) {
        double min = progress, max = progress;
        for (var car : train.cars()) {
            var model = VehicleCatalog.find(car.modelId());
            double reference = progress + car.offset();
            min = Math.min(min, reference + rear(model));
            max = Math.max(max, reference + front(model));
        }
        return project(train.route(), min, max);
    }

    private static List<Interval> project(TrackRoute route, double min, double max) {
        min = Math.clamp(min, 0, route.getLength());
        max = Math.clamp(max, 0, route.getLength());
        var intervals = new ArrayList<Interval>();
        double offset = 0;
        for (int i = 0; i < route.connections().size(); i++) {
            var connection = route.connections().get(i);
            double length = route.segmentLength(i), next = offset + length;
            if (max >= offset && min <= next) {
                double start = Math.clamp(min - offset, 0, length);
                double end = Math.clamp(max - offset, 0, length);
                intervals.add(connection.fromStart() ? new Interval(connection.segmentId(), start, end)
                        : new Interval(connection.segmentId(), length - end, length - start));
            }
            offset = next;
        }
        return merge(intervals);
    }

    private static List<Interval> merge(List<Interval> input) {
        var sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(Interval::segmentId).thenComparingDouble(Interval::start));
        var result = new ArrayList<Interval>();
        for (var next : sorted) {
            if (!result.isEmpty()) {
                var previous = result.getLast();
                if (previous.segmentId().equals(next.segmentId()) && next.start() <= previous.end() + 1e-7) {
                    result.set(result.size() - 1, new Interval(previous.segmentId(), previous.start(), Math.max(previous.end(), next.end())));
                    continue;
                }
            }
            result.add(next);
        }
        return List.copyOf(result);
    }

    void update(UUID trainId, List<Interval> intervals) {
        remove(trainId);
        var merged = merge(intervals);
        trains.put(trainId, merged);
        merged.stream().collect(Collectors.groupingBy(Interval::segmentId)).forEach((segment, entries) ->
                segments.computeIfAbsent(segment, ignored -> new HashMap<>()).put(trainId, List.copyOf(entries)));
    }

    void remove(UUID trainId) {
        var previous = trains.remove(trainId);
        if (previous == null) return;
        for (var interval : previous) {
            var owners = segments.get(interval.segmentId());
            if (owners == null) continue;
            owners.remove(trainId);
            if (owners.isEmpty()) segments.remove(interval.segmentId());
        }
    }

    public List<Interval> intervals(UUID trainId) { return trains.getOrDefault(trainId, List.of()); }
    public Set<UUID> occupants(UUID segmentId) {
        var owners = segments.get(segmentId);
        return owners == null ? Set.of() : Set.copyOf(owners.keySet());
    }
    public boolean occupies(UUID trainId, UUID segmentId) { return occupants(segmentId).contains(trainId); }

    public List<Conflict> conflicts(UUID trainId) {
        var result = new ArrayList<Conflict>();
        for (var interval : intervals(trainId)) {
            for (var owner : segments.get(interval.segmentId()).entrySet()) {
                if (owner.getKey().equals(trainId)) continue;
                for (var other : owner.getValue()) {
                    double start = Math.max(interval.start(), other.start()), end = Math.min(interval.end(), other.end());
                    if (end - start > 1e-7) result.add(new Conflict(owner.getKey(), interval.segmentId(), start, end));
                }
            }
        }
        result.sort(Comparator.comparing(Conflict::segmentId).thenComparing(Conflict::trainId).thenComparingDouble(Conflict::start));
        return List.copyOf(result);
    }
}
