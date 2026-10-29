/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.path.TrackCursor;
import com.unrealtrainmod.track.path.TrackPathSampler;
import com.unrealtrainmod.track.path.TrackRoute;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleMotionSpec;
import com.unrealtrainmod.vehicle.VehiclePoseSolver;
import java.util.ArrayList;
import java.util.List;

/** Route-coordinate arithmetic shared by running, coupling and recovery. No world state is mutated. */
final class TrainMotion {
    private TrainMotion() {}

    static VehiclePoseSolver.Range range(TrainConsist train) {
        double min = Double.NEGATIVE_INFINITY, max = Double.POSITIVE_INFINITY;
        for (var car : train.cars()) {
            var range = VehiclePoseSolver.range(train.route().getLength(), VehicleCatalog.find(car.modelId()).motionSpec());
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
            offsets.add(car.offset() + spec.frontBogieOffset());
            offsets.add(car.offset() + spec.rearBogieOffset());
        }
        return offsets;
    }

    static List<Double> sampleOffsets(VehicleMotionSpec spec) {
        return List.of(0.0, spec.frontBogieOffset(), spec.rearBogieOffset());
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

    static double distance(TrackRoute route, TrackPathSampler sampler, TrainAnchor anchor) {
        int index = route.connections().indexOf(new TrackConnection(anchor.segmentId(), anchor.fromStart()));
        if (index < 0) throw new IllegalArgumentException("Anchor is not on this directed route");
        return sampler.distance(new TrackCursor(index,
                anchor.fromStart() ? anchor.distance() : route.segmentLength(index) - anchor.distance()));
    }

    static TrainAnchor anchor(TrackRoute route, TrackPathSampler sampler, double progress) {
        var cursor = sampler.locate(progress);
        var connection = route.connections().get(cursor.segmentIndex());
        double local = connection.fromStart() ? cursor.distance() : route.segmentLength(cursor.segmentIndex()) - cursor.distance();
        return new TrainAnchor(connection.segmentId(), local, connection.fromStart());
    }
}
