/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.geometry.StraightTrackGeometry;
import com.unrealtrainmod.track.path.TrackCursor;
import com.unrealtrainmod.track.path.TrackPathSampler;
import com.unrealtrainmod.track.path.TrackRoute;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import com.unrealtrainmod.vehicle.VehicleMotionSpec;
import com.unrealtrainmod.vehicle.VehiclePoseSolver;
import java.util.ArrayList;
import java.util.List;

/** Route-coordinate arithmetic shared by running, coupling and recovery. No world state is mutated. */
final class TrainMotion {
    private TrainMotion() {}

    record Car(double offset, VehicleDefinition model, VehiclePoseSolver.Range range, double rear, double front) {}

    /** Fixed member parameters and route-length limits, rebuilt when the running window changes. */
    record Prepared(TrainRouteWindow window, List<Car> cars, VehiclePoseSolver.Range range, List<Double> sampleOffsets,
                    double leadingForward, double leadingReverse) {
        List<TrackOccupancyManager.Interval> footprint(double progress) {
            double min = progress, max = progress;
            for (var car : cars) {
                double reference = progress + car.offset();
                min = Math.min(min, reference + car.rear());
                max = Math.max(max, reference + car.front());
            }
            return TrackOccupancyManager.project(window.route(), min, max);
        }

        double leadingBogie(boolean forward) {
            return forward ? leadingForward : leadingReverse;
        }
    }

    static Prepared prepare(TrainConsist train) {
        var cars = new ArrayList<Car>(train.cars().size());
        var offsets = new ArrayList<Double>(train.cars().size() * 3);
        double min = Double.NEGATIVE_INFINITY, max = Double.POSITIVE_INFINITY;
        double leadingForward = Double.NEGATIVE_INFINITY, leadingReverse = Double.POSITIVE_INFINITY;
        for (var car : train.cars()) {
            var model = VehicleCatalog.find(car.modelId());
            var spec = model.motionSpec();
            int direction = car.direction();
            var range = VehiclePoseSolver.range(train.route().getLength(), spec, direction);
            cars.add(new Car(car.offset(), model, range,
                    TrackOccupancyManager.rear(model, direction), TrackOccupancyManager.front(model, direction)));
            min = Math.max(min, range.min() - car.offset());
            max = Math.min(max, range.max() - car.offset());
            offsets.add(car.offset());
            double front = car.offset() + direction * spec.frontBogieOffset(), rear = car.offset() + direction * spec.rearBogieOffset();
            offsets.add(front);
            offsets.add(rear);
            leadingForward = Math.max(leadingForward, Math.max(front, rear));
            leadingReverse = Math.min(leadingReverse, Math.min(front, rear));
        }
        return new Prepared(train.window(), List.copyOf(cars), new VehiclePoseSolver.Range(min, max), List.copyOf(offsets),
                leadingForward, leadingReverse);
    }

    static VehiclePoseSolver.Range range(TrainConsist train) {
        return range(train.route().getLength(), train.cars());
    }

    static VehiclePoseSolver.Range range(double routeLength, List<TrainCarState> cars) {
        double min = Double.NEGATIVE_INFINITY, max = Double.POSITIVE_INFINITY;
        for (var car : cars) {
            var range = VehiclePoseSolver.range(routeLength, VehicleCatalog.find(car.modelId()).motionSpec(), car.direction());
            min = Math.max(min, range.min() - car.offset());
            max = Math.min(max, range.max() - car.offset());
        }
        return new VehiclePoseSolver.Range(min, max);
    }

    static double carProgress(double reference, double offset, VehiclePoseSolver.Range range) {
        // Preserve exact per-car endpoints after the consist range's offset subtraction.
        if (reference == range.min() - offset) return range.min();
        if (reference == range.max() - offset) return range.max();
        return reference + offset;
    }

    static List<Double> sampleOffsets(TrainConsist train) {
        var offsets = new ArrayList<Double>(train.cars().size() * 3);
        for (var car : train.cars()) {
            var spec = VehicleCatalog.find(car.modelId()).motionSpec();
            offsets.add(car.offset());
            offsets.add(car.offset() + car.direction() * spec.frontBogieOffset());
            offsets.add(car.offset() + car.direction() * spec.rearBogieOffset());
        }
        return offsets;
    }

    static List<Double> sampleOffsets(VehicleMotionSpec spec, int direction) {
        return List.of(0.0, direction * spec.frontBogieOffset(), direction * spec.rearBogieOffset());
    }

    /** The complete swept consist must stay inside one genuinely straight route occurrence. */
    static boolean isSingleStraight(TrackGraph graph, Prepared motion, double from, double target) {
        if (Math.abs(target - from) <= 0.25) return false;
        double minReference = Math.min(from, target), maxReference = Math.max(from, target);
        double min = minReference, max = maxReference;
        for (var car : motion.cars()) {
            double first = carProgress(minReference, car.offset(), car.range());
            double last = carProgress(maxReference, car.offset(), car.range());
            min = Math.min(min, first + car.rear());
            max = Math.max(max, last + car.front());
        }
        var window = motion.window();
        if (min < 0 || max > window.route().getLength()) return false;
        int index = window.sampler().locate(min).segmentIndex();
        if (index != window.sampler().locate(max).segmentIndex()) return false;
        var connection = window.route().connections().get(index);
        // Segment.Type describes only horizontal alignment, which can also carry a vertical curve.
        return graph.segment(connection.segmentId()).geometry() instanceof StraightTrackGeometry;
    }

    static double nextStep(TrackRoute route, TrackPathSampler sampler, List<Double> offsets, double from, double target) {
        boolean forward = target > from;
        double next = forward ? Math.min(target, from + 0.25) : Math.max(target, from - 0.25);
        for (double offset : offsets) {
            // Only absorb endpoint rounding here; the caller has already checked the whole consist range.
            double sample = Math.clamp(from + offset, 0, route.getLength());
            if ((forward && sample == route.getLength()) || (!forward && sample == 0)) continue;
            var cursor = sampler.locate(sample);
            int index = cursor.segmentIndex();
            if (!forward && cursor.distance() == 0 && index > 0) index--;
            double boundary = sampler.distance(new TrackCursor(index, forward ? route.segmentLength(index) : 0)) - offset;
            if (forward && boundary > from) next = Math.min(next, boundary);
            else if (!forward && boundary < from) next = Math.max(next, boundary);
        }
        return next;
    }

    /** A saved physical anchor is checked at the member's expected occurrence, never the first match. */
    static boolean matchesAt(TrainRouteWindow window, TrainCarState car, double expected) {
        var anchor = car.anchor();
        if (anchor == null) return false;
        for (int index : window.occurrences(anchor.segmentId())) {
            if (window.route().connections().get(index).fromStart() != (anchor.fromStart() != car.reversed())
                    || anchor.distance() > window.route().segmentLength(index)) continue;
            if (sameDistance(distanceAt(window, index, anchor), expected)) return true;
        }
        return false;
    }

    /** Chooses the uniquely nearest occurrence for a single car being aligned during coupling. */
    static double nearestDistance(TrainRouteWindow window, TrainAnchor anchor, double expected, int direction) {
        var positions = new ArrayList<Double>();
        double best = Double.NaN, difference = Double.POSITIVE_INFINITY;
        for (int index : window.occurrences(anchor.segmentId())) {
            if (window.route().connections().get(index).fromStart() != (anchor.fromStart() == (direction > 0))
                    || anchor.distance() > window.route().segmentLength(index)) continue;
            double position = distanceAt(window, index, anchor);
            positions.add(position);
            double delta = Math.abs(position - expected);
            if (delta < difference) {
                best = position;
                difference = delta;
            }
        }
        if (positions.isEmpty()) throw new IllegalArgumentException("Anchor is not on this directed route");
        int nearest = 0;
        for (double position : positions) {
            if (sameDistance(Math.abs(position - expected), difference)) nearest++;
        }
        if (nearest != 1) throw new IllegalArgumentException("Anchor has equally near route occurrences");
        return best;
    }

    private static double distanceAt(TrainRouteWindow window, int index, TrainAnchor anchor) {
        double local = window.route().connections().get(index).fromStart()
                ? anchor.distance() : window.route().segmentLength(index) - anchor.distance();
        return window.sampler().distance(new TrackCursor(index, local));
    }

    static boolean sameDistance(double first, double second) {
        return Math.abs(first - second) <= Math.max(1e-9, 4 * Math.max(Math.ulp(first), Math.ulp(second)));
    }

    static TrainAnchor anchor(TrackRoute route, TrackPathSampler sampler, double progress, int direction) {
        var cursor = sampler.locate(progress);
        var connection = route.connections().get(cursor.segmentIndex());
        double local = connection.fromStart() ? cursor.distance() : route.segmentLength(cursor.segmentIndex()) - cursor.distance();
        return new TrainAnchor(connection.segmentId(), local, connection.fromStart() == (direction > 0));
    }
}
