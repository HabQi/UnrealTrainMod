/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.util.List;

/** Base object exposed to legacy scripts as the global {@code renderer}. */
public abstract class RtmPartsRenderer {
    /** Submit the named parts in the current draw context. Mesh-generation callers may ignore draws. */
    protected abstract void draw(List<String> names);

    public Parts registerParts(Parts parts) {
        if (parts == null) throw new IllegalArgumentException("Parts must not be null");
        return parts;
    }

    public void renderParts(Parts parts) {
        if (parts == null) return;
        draw(parts.names());
    }

    /** The prepared model carries each part's own texture, so explicit binds are not replayed. */
    public void bindTexture(Object texture) {
        ScriptDiagnostics.unsupported("renderer.bindTexture", "parts keep their prepared textures");
    }
}
