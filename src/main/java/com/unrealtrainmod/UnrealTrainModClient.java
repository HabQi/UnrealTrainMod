/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 * Original NeoForge MDK template material: see TEMPLATE_LICENSE.txt.
 */
package com.unrealtrainmod;

import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.TrainDrivingClient;
import com.unrealtrainmod.client.TrainPoseClient;
import com.unrealtrainmod.client.ClientButtonTextureManager;
import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.ClientThumbnailManager;
import com.unrealtrainmod.client.PlacedModelRenderer;
import com.unrealtrainmod.client.TrainEntityRenderer;
import com.unrealtrainmod.client.StaticModelShader;
import com.unrealtrainmod.client.gui.ActivationWarningOverlay;
import com.unrealtrainmod.client.gui.TrainDebugOverlay;
import com.unrealtrainmod.client.network.ClientPayloadHandlers;
import com.unrealtrainmod.client.map.TrainMapClient;
import com.unrealtrainmod.client.renderer.TrainModelRenderer;
import com.unrealtrainmod.client.track.ClientTrackCache;
import com.unrealtrainmod.client.track.ClientRailModelManager;
import com.unrealtrainmod.client.track.ClientTrackConstruction;
import com.unrealtrainmod.client.track.TrackConstructionRenderer;
import com.unrealtrainmod.client.track.ClientTurnoutConstruction;
import com.unrealtrainmod.client.track.TurnoutConstructionRenderer;
import com.unrealtrainmod.client.track.TrackWorldRenderer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = UnrealTrainMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = UnrealTrainMod.MODID, value = Dist.CLIENT)
public class UnrealTrainModClient {
    @SubscribeEvent
    static void registerDrivingKeys(RegisterKeyMappingsEvent event) { TrainDrivingClient.registerKeys(event); }

    @SubscribeEvent
    static void registerShaders(RegisterShadersEvent event) {
        StaticModelShader.register(event);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(UnrealTrainMod.PLACED_MODEL.get(), PlacedModelRenderer::new);
        event.registerEntityRenderer(UnrealTrainMod.TRAIN.get(), TrainEntityRenderer::new);
    }

    @SubscribeEvent
    static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(ClientModelManager.INSTANCE);
        event.registerReloadListener(ClientRailModelManager.INSTANCE);
        event.registerReloadListener(ClientButtonTextureManager.INSTANCE);
        event.registerReloadListener(ClientThumbnailManager.INSTANCE);
        event.registerReloadListener(ClientTrackCache.INSTANCE);
        event.registerReloadListener(ClientTurnoutConstruction.INSTANCE);
    }

    public UnrealTrainModClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        // Expose the client configuration in the Mods screen.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(UnrealTrainModClient::onGameShuttingDown);
        NeoForge.EVENT_BUS.addListener(ActivationWarningOverlay::render);
        NeoForge.EVENT_BUS.addListener(TrainDebugOverlay::render);
        NeoForge.EVENT_BUS.addListener(TrainDebugOverlay::onSync);
        NeoForge.EVENT_BUS.addListener(TrainDebugOverlay::onUnload);
        NeoForge.EVENT_BUS.addListener(TrainDebugOverlay::onLogout);
        NeoForge.EVENT_BUS.addListener(TrainDebugOverlay::onClone);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::onSync);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::tick);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::keyPressed);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::mousePressed);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::movement);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::render);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::unload);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::logout);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::clonePlayer);
        NeoForge.EVENT_BUS.addListener(TrainPoseClient::tick);
        NeoForge.EVENT_BUS.addListener(TrainPoseClient::unload);
        NeoForge.EVENT_BUS.addListener(TrainPoseClient::logout);
        NeoForge.EVENT_BUS.addListener(TrainMapClient::onSync);
        NeoForge.EVENT_BUS.addListener(TrainMapClient::onUnload);
        NeoForge.EVENT_BUS.addListener(TrainMapClient::onLogout);
        NeoForge.EVENT_BUS.addListener(TrainMapClient::onClone);
        NeoForge.EVENT_BUS.addListener(ClientPayloadHandlers::openModelSelector);
        NeoForge.EVENT_BUS.addListener(ClientPayloadHandlers::modelSelectionResult);
        NeoForge.EVENT_BUS.addListener(ClientPayloadHandlers::railSelectionResult);
        NeoForge.EVENT_BUS.addListener(TrainModelRenderer::onFramePre);
        NeoForge.EVENT_BUS.addListener(ClientThumbnailManager.INSTANCE::onFramePre);
        NeoForge.EVENT_BUS.addListener(ClientThumbnailManager.INSTANCE::onFramePost);
        NeoForge.EVENT_BUS.addListener(TrainModelRenderer::onRenderStage);
        NeoForge.EVENT_BUS.addListener(ClientTrackCache.INSTANCE::onSync);
        NeoForge.EVENT_BUS.addListener(ClientTrackCache.INSTANCE::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(ClientTrackCache.INSTANCE::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(ClientTrackCache.INSTANCE::onLogout);
        NeoForge.EVENT_BUS.addListener(TrackWorldRenderer::render);
        NeoForge.EVENT_BUS.addListener(ClientTrackConstruction.INSTANCE::onSync);
        NeoForge.EVENT_BUS.addListener(ClientTrackConstruction.INSTANCE::onTick);
        NeoForge.EVENT_BUS.addListener(ClientTrackConstruction.INSTANCE::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(ClientTrackConstruction.INSTANCE::onClone);
        NeoForge.EVENT_BUS.addListener(ClientTrackConstruction.INSTANCE::onLogout);
        NeoForge.EVENT_BUS.addListener(TrackConstructionRenderer::render);
        NeoForge.EVENT_BUS.addListener(ClientTurnoutConstruction.INSTANCE::onSync);
        NeoForge.EVENT_BUS.addListener(ClientTurnoutConstruction.INSTANCE::onTick);
        NeoForge.EVENT_BUS.addListener(ClientTurnoutConstruction.INSTANCE::onUnload);
        NeoForge.EVENT_BUS.addListener(ClientTurnoutConstruction.INSTANCE::onLogout);
        NeoForge.EVENT_BUS.addListener(ClientTurnoutConstruction.INSTANCE::onClone);
        NeoForge.EVENT_BUS.addListener(TurnoutConstructionRenderer::render);
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        TrainDrivingClient.clear();
        TrainPoseClient.clear();
        ClientThumbnailManager.INSTANCE.close();
        ClientModelManager.INSTANCE.close();
        TrainModelRenderer.closeWorld();
        ClientTrackCache.INSTANCE.close();
        ClientRailModelManager.INSTANCE.close();
        ClientButtonTextureManager.INSTANCE.close();
        ClientTrackConstruction.INSTANCE.clear();
        ClientTurnoutConstruction.INSTANCE.clear();
    }
}
