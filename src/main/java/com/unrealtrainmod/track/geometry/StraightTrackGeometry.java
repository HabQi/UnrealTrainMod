/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.geometry;

import java.util.Objects;

/** A horizontal straight line with a constant grade. */
public final class StraightTrackGeometry implements TrackGeometry {
    private final TrackPoint start;
    private final TrackPoint end;
    private final LinearGrade grade;
    private final double length;
    private final TrackPose startPose;

    public StraightTrackGeometry(TrackPoint start, TrackPoint end, LinearGrade grade, double gauge) {
        this.start = Objects.requireNonNull(start);
        this.end = Objects.requireNonNull(end);
        this.grade = Objects.requireNonNull(grade);
        GeometrySupport.validateGauge(gauge);
        double horizontalLength = start.distanceTo(end);
        length = GeometrySupport.length(horizontalLength, grade);
        startPose = GeometrySupport.pose(start, grade.startHeight(), end.x() - start.x(), end.z() - start.z(),
                horizontalLength / length, grade.rise() / length, 0, gauge);
    }

    @Override
    public double getLength() {
        return length;
    }

    @Override
    public TrackPose sample(double distance) {
        double fraction = GeometrySupport.clampDistance(distance, length) / length;
        if (fraction == 0) return startPose;
        TrackPoint point = start.interpolate(end, fraction);
        return new TrackPose(new TrackVector(point.x(), grade.heightAt(fraction), point.z()),
                startPose.tangent(), startPose.lateral(), startPose.normal(), 0, startPose.gauge());
    }
}
