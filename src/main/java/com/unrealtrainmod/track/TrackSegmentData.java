/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Rendering definition only; the client does not own or reconstruct the server's topology. */
public record TrackSegmentData(UUID id, TrackSegment.Type type, TrackVector start, TrackVector end,
                               double gauge, List<TrackPoint> controls, List<TrackPoint> verticalControls, String modelId) {
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
        verticalControls = List.copyOf(verticalControls);
        if (controls.size() != (type == TrackSegment.Type.BEZIER ? 2 : 0)) {
            throw new IllegalArgumentException("Control count does not match track type");
        }
        if (!verticalControls.isEmpty() && verticalControls.size() != 2) {
            throw new IllegalArgumentException("Invalid vertical control count");
        }
    }

    public static TrackSegmentData from(TrackGraph graph, TrackSegment segment) {
        var definition = TrackGeometryDefinition.from(segment.geometry());
        TrackNode start = graph.node(segment.startNodeId()), end = graph.node(segment.endNodeId());
        return new TrackSegmentData(segment.id(), definition.type(), start.position(), end.position(), start.gauge(),
                definition.controls(), definition.verticalControls(), segment.modelId());
    }

    public TrackGeometry createGeometry() {
        return new TrackGeometryDefinition(controls, verticalControls).create(start, end, gauge);
    }
}
