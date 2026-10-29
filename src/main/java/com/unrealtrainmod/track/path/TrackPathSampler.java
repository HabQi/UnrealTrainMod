/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.path;

import com.unrealtrainmod.track.geometry.TrackPose;
import java.util.Objects;

/**
 * Strict finite-route sampling in blocks. Internal joints belong to the outgoing segment;
 * the final endpoint belongs to the last segment. Returned poses follow increasing route distance,
 * independently of movement direction, and contain no vehicle/model height offset or joint smoothing.
 */
public final class TrackPathSampler {
    private final TrackRoute route;

    public TrackPathSampler(TrackRoute route) {
        this.route = Objects.requireNonNull(route);
    }

    /** Finds a route-local cursor in O(log n). Out-of-range distances are never clamped or wrapped. */
    public TrackCursor locate(double s) {
        if (!Double.isFinite(s) || s < 0 || s > route.getLength()) {
            throw new IllegalArgumentException("Route distance must be finite and in [0, " + route.getLength() + "]");
        }
        int lower = 0, upper = route.connections().size();
        while (lower + 1 < upper) {
            int middle = lower + (upper - lower) / 2;
            if (s < route.startDistance(middle)) upper = middle;
            else lower = middle;
        }
        // Use the original segment length at the final endpoint, avoiding prefix-sum subtraction error.
        double local = s == route.getLength() ? route.geometry(lower).getLength() : s - route.startDistance(lower);
        return new TrackCursor(lower, local);
    }

    /** Converts a cursor from this route to global distance; a local endpoint is valid. */
    public double distance(TrackCursor cursor) {
        if (cursor == null) throw new IllegalArgumentException("Route cursor must not be null");
        int index = cursor.segmentIndex();
        if (index < 0 || index >= route.connections().size()) {
            throw new IllegalArgumentException("Cursor segmentIndex is outside the route: " + index);
        }
        double local = cursor.distance();
        double length = route.geometry(index).getLength();
        if (!Double.isFinite(local) || local < 0 || local > length) {
            throw new IllegalArgumentException("Cursor distance must be finite and in [0, " + length + "]");
        }
        return local == length ? route.startDistance(index + 1) : route.startDistance(index) + local;
    }

    /** Samples the track centerline using the route's direction and exact-joint convention. */
    public TrackPose sample(double s) {
        var cursor = locate(s);
        return route.geometry(cursor.segmentIndex()).sample(cursor.distance());
    }

    /** Canonicalizes local endpoints so sampling a cursor and its global distance agree. */
    public TrackPose sample(TrackCursor cursor) {
        return sample(distance(cursor));
    }
}
