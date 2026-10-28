/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.StraightTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Rendering definition only; the client does not own or reconstruct the server's topology. */
public record TrackSegmentData(UUID id, TrackSegment.Type type, TrackVector start, TrackVector end,
                               double gauge, List<TrackPoint> controls, String modelId) {
    public TrackSegmentData {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        for (TrackVector point : List.of(start, end)) {
            if (!Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z())) {
                throw new IllegalArgumentException("Track endpoints must be finite");
            }
        }
        if (!Double.isFinite(gauge) || gauge <= 0) throw new IllegalArgumentException("Invalid track gauge");
        controls = List.copyOf(controls);
        if (controls.size() != (type == TrackSegment.Type.BEZIER ? 2 : 0)) {
            throw new IllegalArgumentException("Control count does not match track type");
        }
    }

    public static TrackSegmentData from(TrackGraph graph, TrackSegment segment) {
        List<TrackPoint> controls = segment.geometry() instanceof BezierTrackGeometry bezier
                ? List.of(bezier.control1(), bezier.control2()) : List.of();
        TrackNode start = graph.node(segment.startNodeId()), end = graph.node(segment.endNodeId());
        return new TrackSegmentData(segment.id(), segment.type(), start.position(), end.position(), start.gauge(), controls, segment.modelId());
    }

    public TrackGeometry createGeometry() {
        TrackPoint a = new TrackPoint(start.x(), start.z()), b = new TrackPoint(end.x(), end.z());
        LinearGrade grade = new LinearGrade(start.y(), end.y());
        return type == TrackSegment.Type.STRAIGHT ? new StraightTrackGeometry(a, b, grade, gauge)
                : new BezierTrackGeometry(a, controls.get(0), controls.get(1), b, grade, gauge);
    }
}
