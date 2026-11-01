/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.compat.rtm.script.RailScriptRuntime;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;

/**
 * Stops a filtered build immediately after a script failure and schedules the variant's cached meshes for rebuild.
 */
final class RailScriptFilter implements RailTrackMeshBuilder.Filter {
    private final RailScriptRuntime script;
    private final ClientRailModelManager.Model model;

    RailScriptFilter(ClientRailModelManager.Model model) {
        this.model = model;
        script = model.script();
    }

    @Override
    public boolean shouldRender(String part, long index, long count) {
        boolean visible = script.shouldRender(part, index, count);
        if (script.failed()) {
            ClientTrackCache.INSTANCE.invalidateScriptedModel(model);
            throw new Failure();
        }
        return visible;
    }

    /** The caller discards the interrupted mesh and rebuilds from the complete static geometry. */
    static final class Failure extends RuntimeException {
        Failure() { super(null, null, false, false); }
    }
}
