/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.util.ArrayList;
import java.util.List;

/** Legacy RTM part-name group returned by {@code renderer.registerParts(new Parts(...))}. */
public final class Parts {
    private final List<String> names;

    public Parts(String... names) {
        var values = new ArrayList<String>(names.length);
        for (String name : names) {
            String value = name == null ? "" : name.trim();
            if (!value.isEmpty()) values.add(value);
        }
        this.names = List.copyOf(values);
    }

    public List<String> names() { return names; }

    public String[] getNames() { return names.toArray(String[]::new); }

    public String[] getObjNames() { return getNames(); }

    /** RTM scripts compare authored object names, usually without relying on case. */
    public boolean containsName(String name) {
        if (name == null) return false;
        String value = name.trim();
        for (String candidate : names) if (candidate.equalsIgnoreCase(value)) return true;
        return false;
    }

    public void render(Object renderer) {
        if (!(renderer instanceof RtmPartsRenderer target)) {
            throw new IllegalArgumentException("Parts.render requires an RTM script renderer");
        }
        target.renderParts(this);
    }

    /** Geometry is already prepared; registering resolves and validates the authored names. */
    public void prepare(RtmPartsRenderer renderer) { renderer.registerParts(this); }

    @Override public String toString() { return "Parts" + names; }
}
