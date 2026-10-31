/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import com.unrealtrainmod.vehicle.VehiclePosePlayback;
import com.unrealtrainmod.vehicle.VehiclePoseSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** One simulation clock per client world, independent of entity tracking and resource reloads. */
public final class TrainPoseClient {
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

    public static double receiveTime(Level world, VehiclePoseSnapshot snapshot) {
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(world.tickRateManager().runsNormally());
        var playback = clock(world);
        playback.observe(snapshot.tick(), partial, snapshot.predicting());
        return playback.time(partial);
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
        level = null;
        clock = new VehiclePosePlayback.Clock();
    }
}
