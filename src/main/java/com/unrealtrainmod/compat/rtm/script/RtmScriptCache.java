/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single-owner cache: vehicles use the render thread; each rail reload prepares its own independent domain. */
public final class RtmScriptCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod RTM scripts");
    private record Entry(RtmScriptProgram program, IOException failure) {}
    private final Map<PackageAsset, Entry> programs = new HashMap<>();

    RtmScriptProgram program(PackageAsset asset) throws IOException {
        var entry = programs.get(asset);
        if (entry == null) {
            LOGGER.debug("RTM script compilation cache miss: {}!{}", asset.archive(), asset.entry());
            try {
                RtmScriptSource.Prepared prepared;
                try {
                    prepared = RtmScriptSource.load(asset);
                } catch (IOException | RuntimeException exception) {
                    throw new IOException("Script source read failed for " + asset + ": " + exception.getMessage(), exception);
                }
                entry = new Entry(new RtmScriptProgram(prepared), null);
            } catch (IOException exception) {
                entry = new Entry(null, exception);
            }
            programs.put(asset, entry);
        }
        if (entry.failure() != null) throw entry.failure();
        return entry.program();
    }

    public void clear() { programs.clear(); }
}
