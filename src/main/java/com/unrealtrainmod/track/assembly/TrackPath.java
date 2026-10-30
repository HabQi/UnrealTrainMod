/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

import com.unrealtrainmod.track.TrackConnection;
import java.util.ArrayList;
import java.util.List;

/** Ordered traversal from the definition's entrance port to its exit port. */
public record TrackPath(List<TrackConnection> steps) {
    public TrackPath {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("Empty assembly path");
        if (steps.stream().map(TrackConnection::segmentId).distinct().count() != steps.size()) {
            throw new IllegalArgumentException("Assembly path repeats a segment");
        }
    }

    public List<TrackConnection> reversedSteps() {
        var result = new ArrayList<TrackConnection>(steps.size());
        for (int i = steps.size() - 1; i >= 0; i--) {
            var step = steps.get(i);
            result.add(new TrackConnection(step.segmentId(), !step.fromStart()));
        }
        return List.copyOf(result);
    }
}
