/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.model.PartSelection;
import java.util.HashMap;
import java.util.Map;

/** Per-runtime model lookup shared by registration diagnostics and subsequent draw calls. */
final class ScriptPartResolver {
    private final LoadedModel.Source source;
    private final String modelLabel;
    private final Map<String, PartSelection> resolved = new HashMap<>();

    ScriptPartResolver(LoadedModel.Source source, String modelLabel) {
        this.source = source;
        this.modelLabel = modelLabel;
    }

    void clear() { resolved.clear(); }

    PartSelection selection(String name) {
        if (resolved.containsKey(name)) return resolved.get(name);
        var selection = source.partsByName().get(name);
        if (selection == null) {
            for (var entry : source.partsByName().entrySet()) {
                if (entry.getKey().trim().equalsIgnoreCase(name.trim())) {
                    selection = entry.getValue();
                    break;
                }
            }
        }
        if (selection == null) ScriptDiagnostics.missingPart(modelLabel, name);
        // Cache misses as well, so an optional missing part is not searched again each frame.
        resolved.put(name, selection);
        return selection;
    }
}
