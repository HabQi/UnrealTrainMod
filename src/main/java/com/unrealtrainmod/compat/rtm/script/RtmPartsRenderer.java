/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Base object exposed to legacy scripts as the global {@code renderer}. */
public abstract class RtmPartsRenderer {
    private final Set<Parts> registeredParts = new LinkedHashSet<>();
    private final Map<Integer, Object> data = new HashMap<>();

    /** Submit the named parts in the current draw context. Mesh-generation callers may ignore draws. */
    protected abstract void draw(List<String> names);

    public Parts registerParts(Parts parts) {
        if (parts == null) throw new IllegalArgumentException("Parts must not be null");
        if (registeredParts.add(parts)) registered(parts);
        return parts;
    }

    /** Vehicle renderers can validate immediately against their associated model. */
    protected void registered(Parts parts) {}

    public Object getData(int id) { return data.getOrDefault(id, 0); }

    public void setData(int id, Object value) { data.put(id, value); }

    /** Release host references, including script values stored by setData; never call back into JavaScript. */
    void release() {
        data.clear();
        registeredParts.clear();
    }

    public float sigmoid(float progress) {
        // The one-argument renderer API only special-cases the endpoints, unlike NGTMath.sigmoid.
        if (progress == 0 || progress == 1) return progress;
        float centered = 5 * (progress - 0.5f);
        return (float)(0.5 + 0.5 * centered / Math.sqrt(1 + (double)centered * centered));
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
