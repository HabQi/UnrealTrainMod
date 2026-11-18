/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import com.unrealtrainmod.client.ModelPreparation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single-owner cache: vehicles use the render thread; each rail reload prepares its own independent domain. */
public final class RtmScriptCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod RTM scripts");
    private record Entry(RtmScriptProgram program, IOException failure) {}
    private final Map<PackageAsset, Entry> programs = new HashMap<>();
    private final Map<PackageAsset, ModelPreparation.Ticket<Entry>> pending = new HashMap<>();

    /** The compiler owns a fresh engine until the complete program is handed to the render thread. */
    boolean request(PackageAsset asset) {
        publish(asset);
        if (programs.containsKey(asset) || pending.containsKey(asset)) return true;
        var ticket = ModelPreparation.script(() -> {
            try { return compile(asset); }
            catch (IOException exception) { return new Entry(null, exception); }
        }, ignored -> {});
        if (ticket == null) return false;
        pending.put(asset, ticket);
        ticket.whenReady(() -> publish(asset));
        return true;
    }

    private static Entry compile(PackageAsset asset) throws IOException {
        LOGGER.debug("RTM script compilation cache miss: {}!{}", asset.archive(), asset.entry());
        try { return new Entry(new RtmScriptProgram(RtmScriptSource.load(asset)), null); }
        catch (IOException exception) { throw exception; }
        catch (RuntimeException | LinkageError exception) { throw new IOException("Script compilation failed for " + asset, exception); }
    }

    private void publish(PackageAsset asset) {
        var ticket = pending.get(asset);
        if (ticket == null || !ticket.ready()) return;
        try {
            var entry = ticket.take();
            programs.put(asset, entry);
            if (entry.failure != null) LOGGER.warn("Cannot compile vehicle script {}; using static model", asset, entry.failure);
        } catch (RuntimeException exception) {
            programs.put(asset, new Entry(null, new IOException("Background script preparation failed for " + asset, exception)));
            LOGGER.warn("Cannot prepare vehicle script {}; using static model", asset, exception);
        } finally { ticket.close(); pending.remove(asset); }
    }

    RtmScriptProgram program(PackageAsset asset) throws IOException {
        publish(asset);
        var entry = programs.get(asset);
        if (entry == null) {
            try { entry = compile(asset); }
            catch (IOException exception) { entry = new Entry(null, exception); }
            programs.put(asset, entry);
        }
        if (entry.failure() != null) throw entry.failure();
        return entry.program();
    }

    /** Returns whether a shared program was compiled successfully without compiling it. */
    boolean isReady(PackageAsset asset) {
        publish(asset);
        var entry = programs.get(asset);
        return entry != null && entry.program() != null;
    }

    /** Returns whether compilation was attempted and failed. */
    boolean isFailed(PackageAsset asset) {
        publish(asset);
        var entry = programs.get(asset);
        return entry != null && entry.failure() != null;
    }

    public void clear() { pending.values().forEach(ModelPreparation.Ticket::close); pending.clear(); programs.clear(); }
}
