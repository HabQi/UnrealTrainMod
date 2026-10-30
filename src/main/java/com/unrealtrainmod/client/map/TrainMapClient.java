/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.map;

import com.unrealtrainmod.client.gui.screen.TrainMapScreen;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrainMapSessionPayload;
import com.unrealtrainmod.network.payload.TrainMapUpdatePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

public final class TrainMapClient {
    private TrainMapClient() {}
    public static void onSync(TrackSyncEvent event) {
        var mc = Minecraft.getInstance();
        if (event.payload() instanceof TrainMapSessionPayload payload) {
            if (mc.level == null || !mc.level.dimension().location().equals(payload.dimension())) return;
            if (payload.status() == TrainMapSessionPayload.Status.OPEN) {
                mc.setScreen(new TrainMapScreen(new ClientTrainMap(payload.session(), payload.dimension())));
            } else if (mc.screen instanceof TrainMapScreen screen && screen.view().session.equals(payload.session())) {
                if (payload.status() == TrainMapSessionPayload.Status.ERROR) screen.view().fail("error");
                else mc.setScreen(null);
            }
        } else if (event.payload() instanceof TrainMapUpdatePayload payload && mc.screen instanceof TrainMapScreen screen) {
            try { screen.view().accept(payload, mc.gui.getGuiTicks()); }
            catch (IllegalArgumentException exception) { screen.view().fail("error"); }
        }
    }

    private static void clearCache() {
        var mc = Minecraft.getInstance();
        // setScreen(null) is forbidden while Minecraft is tearing down a connection.
        if (mc.screen instanceof TrainMapScreen screen) screen.view().clear();
    }
    public static void onUnload(LevelEvent.Unload event) { if (event.getLevel().isClientSide()) clearCache(); }
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clearCache(); }
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof TrainMapScreen) mc.setScreen(null);
    }
}
