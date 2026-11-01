/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.renderer.instancing;

import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Five-second per-scene windows. CPU scopes overlap; GPU spans are sampled separately per pass. */
public final class InstancingStats implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod/Instancing");
    private static final long LOG_INTERVAL = 5_000_000_000L;
    private static final long IDLE_RESET = 1_000_000_000L;

    private static final class Counters {
        long requests, cutout, groups, covered, gpuCalls, fallback, translucentCalls, binds, switches;
        long vehicles, scripts, opaqueTris, translucentTris, bytes;
        long group, pack, upload, opaque, translucent, entity, pose, submit, script, enqueue;

        void add(Counters c) {
            requests+=c.requests; cutout+=c.cutout; groups+=c.groups; covered+=c.covered;
            gpuCalls+=c.gpuCalls; fallback+=c.fallback; translucentCalls+=c.translucentCalls;
            binds+=c.binds; switches+=c.switches; vehicles+=c.vehicles; scripts+=c.scripts;
            opaqueTris+=c.opaqueTris; translucentTris+=c.translucentTris; bytes+=c.bytes;
            group+=c.group; pack+=c.pack; upload+=c.upload; opaque+=c.opaque; translucent+=c.translucent;
            entity+=c.entity; pose+=c.pose; submit+=c.submit; script+=c.script; enqueue+=c.enqueue;
        }
    }

    private Counters frame = new Counters(), window = new Counters();
    private final Map<ResourceLocation, Integer> models = new LinkedHashMap<>();
    private final Map<ResourceLocation, Long> windowModels = new LinkedHashMap<>();
    private int frames;
    private long windowStart, lastFrame, windowId;
    private boolean configuredInstancing;
    private String transparency = "";
    private VehicleGpuTimer opaqueTimer, translucentTimer;
    private long maxOpaque, maxTranslucent, maxEntity;
    private long gpuOpaque, maxGpuOpaque, gpuTranslucent, maxGpuTranslucent;
    private int opaqueSamples, translucentSamples;

    public void beginFrame() {
        long now = System.nanoTime();
        boolean instancing = ClientConfig.USE_VEHICLE_INSTANCING.getAsBoolean();
        String mode = ClientConfig.USE_STATIC_TRANSLUCENT_BATCHES.getAsBoolean() ? "static"
                : ClientConfig.SORT_TRANSLUCENT_TRIANGLES.getAsBoolean() ? "sorted" : "unsorted";
        if (windowStart == 0 || now - lastFrame > IDLE_RESET
                || configuredInstancing != instancing || !transparency.equals(mode)) {
            configuredInstancing = instancing;
            transparency = mode;
            resetWindow(now);
        }
        frame = new Counters();
        models.clear();
        if (opaqueTimer == null) {
            opaqueTimer = new VehicleGpuTimer(true);
            translucentTimer = new VehicleGpuTimer(false);
        }
    }

    public void vehicle(ResourceLocation model) { frame.vehicles++; models.merge(model, 1, Integer::sum); }
    public void addEntity(long nanos) { frame.entity += nanos; }
    public void addPose(long nanos) { frame.pose += nanos; }
    public void addSubmission(long nanos) { frame.submit += nanos; }
    public void addScript(long nanos) { frame.scripts++; frame.script += nanos; }
    public void addEnqueue(long nanos) { frame.enqueue += nanos; }
    public void request() { frame.requests++; }
    public void instanceable() { frame.cutout++; }
    public void gpuDraw(int indices) { frame.gpuCalls++; frame.opaqueTris += indices / 3; }
    public void fallbackDraw(int indices) { frame.fallback++; frame.opaqueTris += indices / 3; }
    public void translucentDraw(int triangles) { frame.translucentCalls++; frame.translucentTris += triangles; }
    public void instancedDraw(int instances, int indices) {
        frame.groups++;
        frame.covered += instances;
        frame.opaqueTris += (long)(indices / 3) * instances;
    }
    public void addGrouping(long nanos) { frame.group += nanos; }
    public void addPacking(long nanos) { frame.pack += nanos; }
    public void addOpaque(long nanos) { frame.opaque += nanos; }
    public void addTranslucent(long nanos) { frame.translucent += nanos; }
    public void addShaderCounts(int binds, int switches) { frame.binds += binds; frame.switches += switches; }
    public void addUpload(int bytes, long nanos) { frame.bytes += bytes; frame.upload += nanos; }

    public void beginGpu(boolean opaque) {
        (opaque ? opaqueTimer : translucentTimer).begin(windowId, this);
    }

    public void endGpu(boolean opaque, boolean completed) {
        boolean drawn = opaque ? frame.groups + frame.gpuCalls + frame.fallback > 0 : frame.translucentCalls > 0;
        (opaque ? opaqueTimer : translucentTimer).end(completed && drawn);
    }

    void gpuSample(boolean opaque, long nanos) {
        if (opaque) {
            gpuOpaque += nanos;
            maxGpuOpaque = Math.max(maxGpuOpaque, nanos);
            opaqueSamples++;
        } else {
            gpuTranslucent += nanos;
            maxGpuTranslucent = Math.max(maxGpuTranslucent, nanos);
            translucentSamples++;
        }
    }

    public void endFrame(String scene) {
        frames++;
        window.add(frame);
        maxOpaque = Math.max(maxOpaque, frame.opaque);
        maxTranslucent = Math.max(maxTranslucent, frame.translucent);
        maxEntity = Math.max(maxEntity, frame.entity);
        models.forEach((model,count) -> windowModels.merge(model, (long)count, Long::sum));
        long now = System.nanoTime();
        lastFrame = now;
        if (now - windowStart < LOG_INTERVAL) return;
        LOGGER.info("Vehicle render [{}] frames={} window={}ms instancingConfigured={} transparency={} "
                        + "| perFrame vehicles={} requests={} cutout={} groups={} covered={} gpuCalls={} cpuFallback={} "
                        + "opaqueCalls={} translucentCalls={} opaqueTris={} translucentTris={} shaderBinds={} shaderSwitches={} upload={}B scriptCalls={} "
                        + "| cpu entity={} pose={} submit={} script={} enqueue={} opaque={} translucent={} group={} pack={} upload={} ms/frame "
                        + "maxEntity={}ms maxOpaque={}ms maxTranslucent={}ms "
                        + "| gpuOpaque={} samples={} avg={} max={} gpuTranslucent={} samples={} avg={} max={} | models={}",
                scene, frames, ms(now-windowStart), configuredInstancing, transparency,
                avg(window.vehicles), avg(window.requests), avg(window.cutout), avg(window.groups), avg(window.covered),
                avg(window.gpuCalls), avg(window.fallback), avg(window.groups+window.gpuCalls+window.fallback),
                avg(window.translucentCalls), avg(window.opaqueTris), avg(window.translucentTris), avg(window.binds),
                avg(window.switches), avg(window.bytes), avg(window.scripts),
                ms(window.entity/frames), ms(window.pose/frames), ms(window.submit/frames), ms(window.script/frames),
                ms(window.enqueue/frames), ms(window.opaque/frames), ms(window.translucent/frames),
                ms(window.group/frames), ms(window.pack/frames), ms(window.upload/frames),
                ms(maxEntity), ms(maxOpaque), ms(maxTranslucent),
                gpuState(opaqueSamples), opaqueSamples, sampleMs(gpuOpaque, opaqueSamples), sampleMs(maxGpuOpaque, opaqueSamples == 0 ? 0 : 1),
                gpuState(translucentSamples), translucentSamples, sampleMs(gpuTranslucent, translucentSamples),
                sampleMs(maxGpuTranslucent, translucentSamples == 0 ? 0 : 1), modelSummary());
        resetWindow(now);
    }

    private String modelSummary() {
        var result = new LinkedHashMap<String, String>();
        windowModels.forEach((id,count) -> {
            var definition = VehicleCatalog.find(id);
            result.put((definition == null ? "missing" : definition.name()) + " (" + id + ")", avg(count));
        });
        return result.toString();
    }

    private String gpuState(int samples) {
        return !opaqueTimer.supported() ? "unsupported" : samples == 0 ? "noSamples" : "sampled";
    }
    private String avg(long value) { return String.format(Locale.ROOT, "%.1f", value/(double)frames); }
    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos/1_000_000.0); }
    private static String sampleMs(long nanos, int samples) { return samples == 0 ? "n/a" : ms(nanos/samples) + "ms"; }

    private void resetWindow(long now) {
        windowId++;
        windowStart = now;
        frames = 0;
        window = new Counters();
        windowModels.clear();
        maxOpaque = maxTranslucent = maxEntity = gpuOpaque = maxGpuOpaque = gpuTranslucent = maxGpuTranslucent = 0;
        opaqueSamples = translucentSamples = 0;
    }

    @Override
    public void close() {
        if (opaqueTimer == null) return;
        var opaque = opaqueTimer;
        var translucent = translucentTimer;
        opaqueTimer = translucentTimer = null;
        if (opaque != null) {
            Runnable release = () -> { opaque.close(); translucent.close(); };
            if (RenderSystem.isOnRenderThread()) release.run();
            else RenderSystem.recordRenderCall(release::run);
        }
        models.clear();
        resetWindow(System.nanoTime());
        lastFrame = 0;
    }
}
