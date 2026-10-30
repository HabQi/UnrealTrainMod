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
import com.unrealtrainmod.track.geometry.VerticalTrackGeometry;
import java.util.List;

/** Shared lossless definition for graph construction, storage, inspection and client reconstruction. */
public record TrackGeometryDefinition(List<TrackPoint> controls, List<TrackPoint> verticalControls) {
    public TrackGeometryDefinition {
        controls = List.copyOf(controls);
        verticalControls = List.copyOf(verticalControls);
        if ((!controls.isEmpty() && controls.size() != 2)
                || (!verticalControls.isEmpty() && verticalControls.size() != 2)) {
            throw new IllegalArgumentException("A Bezier definition needs two controls");
        }
    }

    public TrackSegment.Type type() {
        return controls.isEmpty() ? TrackSegment.Type.STRAIGHT : TrackSegment.Type.BEZIER;
    }

    public static TrackGeometry horizontal(TrackGeometry geometry) {
        return geometry instanceof VerticalTrackGeometry vertical ? vertical.horizontal() : geometry;
    }

    public static TrackGeometryDefinition from(TrackGeometry geometry) {
        var flat = horizontal(geometry);
        var controls = flat instanceof BezierTrackGeometry bezier
                ? List.of(bezier.control1(), bezier.control2()) : List.<TrackPoint>of();
        var verticalControls = geometry instanceof VerticalTrackGeometry vertical
                ? List.of(vertical.control1(), vertical.control2()) : List.<TrackPoint>of();
        return new TrackGeometryDefinition(controls, verticalControls);
    }

    public TrackGeometry create(TrackVector start, TrackVector end, double gauge) {
        var a = new TrackPoint(start.x(), start.z());
        var b = new TrackPoint(end.x(), end.z());
        var grade = verticalControls.isEmpty() ? new LinearGrade(start.y(), end.y()) : new LinearGrade(0, 0);
        TrackGeometry flat = controls.isEmpty() ? new StraightTrackGeometry(a, b, grade, gauge)
                : new BezierTrackGeometry(a, controls.get(0), controls.get(1), b, grade, gauge);
        return verticalControls.isEmpty() ? flat : new VerticalTrackGeometry(flat, start.y(), end.y(),
                verticalControls.get(0), verticalControls.get(1));
    }
}
