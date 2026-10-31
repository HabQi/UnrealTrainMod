/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.vehicle.VehiclePosePlayback;
import com.unrealtrainmod.vehicle.VehiclePoseSnapshot;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** One simulation clock per client world, independent of entity tracking and resource reloads. */
public final class TrainPoseClient {
    private record Pending(TrainEntity entity, VehiclePoseSnapshot snapshot) {}
    private static final ArrayList<Pending> pending = new ArrayList<>();
    private static Level level;
    private static VehiclePosePlayback.Clock clock = new VehiclePosePlayback.Clock();

    private TrainPoseClient() {}

    private static VehiclePosePlayback.Clock clock(Level world) {
        if (level != world) {
            clear();
            level = world;
        }
        return clock;
    }

    public static void enqueue(TrainEntity entity, VehiclePoseSnapshot snapshot) {
        clock(entity.level());
        pending.add(new Pending(entity, snapshot));
    }

    public static void discard(TrainEntity entity) {
        pending.removeIf(sample -> sample.entity() == entity);
    }

    /** Packet tasks precede this frame's client ticks; only observe time after those ticks finish. */
    public static void onFramePre(RenderFrameEvent.Pre event) {
        if (pending.isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            clear();
            return;
        }
        var playback = clock(mc.level);
        pending.removeIf(sample -> sample.entity().isRemoved() || sample.entity().level() != mc.level);
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(mc.level.tickRateManager().runsNormally());
        VehiclePoseSnapshot newestMotion = null;
        for (var sample : pending) {
            var snapshot = sample.snapshot();
            if (snapshot.predicting() && (newestMotion == null || snapshot.tick() > newestMotion.tick())) {
                newestMotion = snapshot;
            }
        }
        // Batched arrivals must initialize the shared clock from the freshest moving sample.
        if (newestMotion != null) playback.observe(newestMotion.tick(), partial, true);
        for (var sample : pending) {
            var snapshot = sample.snapshot();
            playback.observe(snapshot.tick(), partial, snapshot.predicting());
        }
        double time = playback.time(partial);
        for (var sample : pending) sample.entity().acceptRailPoseSnapshot(sample.snapshot(), time);
        pending.clear();
    }

    public static double time(Level world, float partialTick) {
        return clock(world).time(world.tickRateManager().runsNormally() ? partialTick : 1);
    }

    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level != null && !mc.isPaused() && mc.level.tickRateManager().runsNormally()) clock(mc.level).tick();
    }

    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() == level) clear();
    }

    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    public static void clear() {
        pending.clear();
        level = null;
        clock = new VehiclePosePlayback.Clock();
    }
}
