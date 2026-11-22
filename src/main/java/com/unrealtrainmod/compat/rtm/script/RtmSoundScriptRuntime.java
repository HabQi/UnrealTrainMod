/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.compat.rtm.RtmVehicleSounds;
import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.script.ScriptException;

/** One client-side instance of the original NJ01 sound updater. */
public final class RtmSoundScriptRuntime implements AutoCloseable {
    private final RtmScriptInstance instance;
    private final RtmScriptProgram program;
    private final Input input;

    @FunctionalInterface
    public interface LoopOutput { void set(String id, double volume, double pitch); }

    public static RtmScriptCache cache() {
        return new RtmScriptCache(asset -> {
            byte[] bytes;
            try (var input = asset.open()) { bytes = input.readAllBytes(); }
            if (!RtmVehicleSounds.verifiedScript(bytes)) throw new IOException("NJ01 sound script no longer matches the supported revision");
            return new RtmScriptSource.Prepared(asset.entry(), new String(bytes, StandardCharsets.UTF_8), List.of());
        });
    }

    public static RtmSoundScriptRuntime request(RtmScriptCache cache, PackageAsset asset, LoopOutput output) throws IOException {
        if (!cache.isReady(asset) && !cache.isFailed(asset)) {
            cache.request(asset);
            return null;
        }
        return new RtmSoundScriptRuntime(cache.program(asset), output);
    }

    private RtmSoundScriptRuntime(RtmScriptProgram program, LoopOutput output) throws IOException {
        this.program = program;
        input = new Input(output);
        instance = new RtmScriptInstance(program);
        boolean ready = false;
        try {
            instance.eval();
            if (!instance.hasFunction("onUpdate")) throw new IOException("Missing NJ01 sound onUpdate(su)");
            ready = true;
        } finally {
            if (!ready) close();
        }
    }

    public void tick(float speedKmh, int notch) throws IOException {
        input.speed = speedKmh;
        input.notch = notch;
        try { instance.invoke("onUpdate", input); }
        catch (ScriptException | NoSuchMethodException | RuntimeException | LinkageError exception) {
            throw program.failure("sound update", exception);
        }
    }

    @Override public void close() { instance.close(); }

    public static final class Input {
        private final LoopOutput output;
        private float speed;
        private int notch;

        private Input(LoopOutput output) { this.output = output; }

        public float getSpeed() { return speed; }
        public int getNotch() { return notch; }
        public boolean isComplessorActive() { return false; }
        public void playSound(String namespace, String id, double volume, double pitch) {
            output.set(namespace + ':' + id, volume, pitch);
        }
        public void stopSound(String namespace, String id) { output.set(namespace + ':' + id, 0, 1); }
    }
}
