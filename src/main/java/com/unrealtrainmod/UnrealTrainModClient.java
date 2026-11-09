/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 * Original NeoForge MDK template material: see TEMPLATE_LICENSE.txt.
 */
package com.unrealtrainmod;

import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.TrainDrivingClient;
import com.unrealtrainmod.client.TrainPassengerPoseClient;
import com.unrealtrainmod.client.TrainWalkingClient;
import com.unrealtrainmod.client.TrainPoseClient;
import com.unrealtrainmod.client.ClientButtonTextureManager;
import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.ClientThumbnailManager;
import com.unrealtrainmod.client.PlacedModelRenderer;
import com.unrealtrainmod.client.TrainEntityRenderer;
import com.unrealtrainmod.client.StaticModelShader;
import com.unrealtrainmod.client.item.CachedItemRenderer;
import com.unrealtrainmod.client.item.CachedObjLoader;
import com.unrealtrainmod.client.sound.VehicleSoundManager;
import com.unrealtrainmod.client.gui.ActivationWarningOverlay;
import com.unrealtrainmod.client.gui.TrainDebugOverlay;
import com.unrealtrainmod.client.gui.screen.ModConfigScreen;
import com.unrealtrainmod.client.network.ClientPayloadHandlers;
import com.unrealtrainmod.client.map.TrainMapClient;
import com.unrealtrainmod.client.renderer.TrainModelRenderer;
import com.unrealtrainmod.client.renderer.instancing.InstancedModelShader;
import com.unrealtrainmod.client.track.ClientTrackCache;
import com.unrealtrainmod.client.track.ClientRailModelManager;
import com.unrealtrainmod.client.track.ClientTrackConstruction;
import com.unrealtrainmod.client.track.TrackConstructionRenderer;
import com.unrealtrainmod.client.track.ClientTurnoutConstruction;
import com.unrealtrainmod.client.track.TurnoutConstructionRenderer;
import com.unrealtrainmod.client.track.TrackWorldRenderer;
import com.unrealtrainmod.compat.rtm.script.VehicleScriptManager;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = UnrealTrainMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = UnrealTrainMod.MODID, value = Dist.CLIENT)
public class UnrealTrainModClient {
    @SubscribeEvent
    static void registerItemGeometry(ModelEvent.RegisterGeometryLoaders event) {
        event.register(ClientModelManager.id("cached_obj"), new CachedObjLoader());
    }

    @SubscribeEvent
    static void registerItemExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return CachedItemRenderer.get();
            }
        }, UnrealTrainMod.TRACK_WRENCH, UnrealTrainMod.HACKSAW, UnrealTrainMod.CROWBAR,
                UnrealTrainMod.VEHICLE_MOVER, UnrealTrainMod.KEY);
    }

    @SubscribeEvent
    static void onItemModelsBaked(ModelEvent.BakingCompleted event) { CachedItemRenderer.invalidate(); }

    @SubscribeEvent
    static void registerDrivingKeys(RegisterKeyMappingsEvent event) { TrainDrivingClient.registerKeys(event); }

    @SubscribeEvent
    static void registerPassengerKeys(RegisterKeyMappingsEvent event) { TrainPassengerPoseClient.registerKeys(event); }

    @SubscribeEvent
    static void registerShaders(RegisterShadersEvent event) {
        StaticModelShader.register(event);
        InstancedModelShader.register(event);
        com.unrealtrainmod.client.track.TrackMorphShader.register(event);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(UnrealTrainMod.CONNECTOR.get(), com.unrealtrainmod.client.wiring.ConnectorRenderer::new);
        event.registerEntityRenderer(UnrealTrainMod.PLACED_MODEL.get(), PlacedModelRenderer::new);
        event.registerEntityRenderer(UnrealTrainMod.TRAIN.get(), TrainEntityRenderer::new);
    }

    @SubscribeEvent
    static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        com.unrealtrainmod.client.compat.iris.IrisPbrBridge.initialize();
        event.registerReloadListener(ClientModelManager.INSTANCE);
        event.registerReloadListener(ClientRailModelManager.INSTANCE);
        event.registerReloadListener(ClientButtonTextureManager.INSTANCE);
        event.registerReloadListener(com.unrealtrainmod.client.wiring.WiringModelManager.INSTANCE);
        event.registerReloadListener(ClientThumbnailManager.INSTANCE);
        event.registerReloadListener(ClientTrackCache.INSTANCE);
        event.registerReloadListener(ClientTurnoutConstruction.INSTANCE);
        event.registerReloadListener(VehicleSoundManager.INSTANCE);
    }

    public UnrealTrainModClient(ModContainer container) {
        com.unrealtrainmod.client.wiring.WiringClient.register(NeoForge.EVENT_BUS);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        // Expose the client configuration in the Mods screen.
        container.registerExtensionPoint(IConfigScreenFactory.class, ModConfigScreen::new);
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
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::openingScreen);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::mousePressed);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::mouseScrolled);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::render);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::guiLayer);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::chatOverlay);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::unload);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::logout);
        NeoForge.EVENT_BUS.addListener(TrainDrivingClient::clonePlayer);
        NeoForge.EVENT_BUS.addListener(com.unrealtrainmod.client.TrainCursorClient::tick);
        NeoForge.EVENT_BUS.addListener(com.unrealtrainmod.client.TrainCursorClient::openingScreen);
        NeoForge.EVENT_BUS.addListener(com.unrealtrainmod.client.TrainCursorClient::render);
        NeoForge.EVENT_BUS.addListener(com.unrealtrainmod.client.TrainCursorClient::unload);
        NeoForge.EVENT_BUS.addListener(com.unrealtrainmod.client.TrainCursorClient::logout);
        NeoForge.EVENT_BUS.addListener(com.unrealtrainmod.client.TrainCursorClient::clonePlayer);
        NeoForge.EVENT_BUS.addListener(com.unrealtrainmod.client.IrControlInteraction::onSync);
        NeoForge.EVENT_BUS.addListener(TrainWalkingClient::onState);
        NeoForge.EVENT_BUS.addListener(TrainWalkingClient::movement);
        NeoForge.EVENT_BUS.addListener(TrainWalkingClient::tick);
        NeoForge.EVENT_BUS.addListener(TrainWalkingClient::unload);
        NeoForge.EVENT_BUS.addListener(TrainWalkingClient::logout);
        NeoForge.EVENT_BUS.addListener(TrainWalkingClient::clonePlayer);
        NeoForge.EVENT_BUS.addListener(TrainPassengerPoseClient::tick);
        NeoForge.EVENT_BUS.addListener(TrainPassengerPoseClient::unload);
        NeoForge.EVENT_BUS.addListener(TrainPassengerPoseClient::logout);
        NeoForge.EVENT_BUS.addListener(TrainPassengerPoseClient::clonePlayer);
        NeoForge.EVENT_BUS.addListener(TrainPassengerPoseClient::render);
        NeoForge.EVENT_BUS.addListener(TrainPoseClient::tick);
        NeoForge.EVENT_BUS.addListener(TrainPoseClient::onFramePre);
        NeoForge.EVENT_BUS.addListener(TrainPoseClient::unload);
        NeoForge.EVENT_BUS.addListener(TrainPoseClient::logout);
        NeoForge.EVENT_BUS.addListener(VehicleSoundManager.INSTANCE::onTick);
        NeoForge.EVENT_BUS.addListener(VehicleSoundManager.INSTANCE::onUnload);
        NeoForge.EVENT_BUS.addListener(VehicleSoundManager.INSTANCE::onLogout);
        NeoForge.EVENT_BUS.addListener(TrainMapClient::onSync);
        NeoForge.EVENT_BUS.addListener(TrainMapClient::onUnload);
        NeoForge.EVENT_BUS.addListener(TrainMapClient::onLogout);
        NeoForge.EVENT_BUS.addListener(TrainMapClient::onClone);
        NeoForge.EVENT_BUS.addListener(ClientPayloadHandlers::openModelSelector);
        NeoForge.EVENT_BUS.addListener(ClientPayloadHandlers::modelSelectionResult);
        NeoForge.EVENT_BUS.addListener(ClientPayloadHandlers::railSelectionResult);
        NeoForge.EVENT_BUS.addListener(com.unrealtrainmod.client.compat.iris.IrisCompat::onFramePre);
        NeoForge.EVENT_BUS.addListener(com.unrealtrainmod.client.compat.iris.IrisCompat::onFramePost);
        NeoForge.EVENT_BUS.addListener(TrainModelRenderer::onFramePre);
        NeoForge.EVENT_BUS.addListener(TrainModelRenderer::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(TrainModelRenderer::onLogout);
        NeoForge.EVENT_BUS.addListener(VehicleScriptManager::onFramePre);
        NeoForge.EVENT_BUS.addListener(VehicleScriptManager::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(VehicleScriptManager::onLogout);
        NeoForge.EVENT_BUS.addListener(ClientThumbnailManager.INSTANCE::onFramePre);
        NeoForge.EVENT_BUS.addListener(ClientThumbnailManager.INSTANCE::onFramePost);
        NeoForge.EVENT_BUS.addListener(CachedItemRenderer::onFramePost);
        NeoForge.EVENT_BUS.addListener(TrainModelRenderer::onRenderStage);
        NeoForge.EVENT_BUS.addListener(com.unrealtrainmod.client.IrControlInteraction::pick);
        NeoForge.EVENT_BUS.addListener(ClientTrackCache.INSTANCE::onSync);
        NeoForge.EVENT_BUS.addListener(ClientTrackCache.INSTANCE::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(ClientTrackCache.INSTANCE::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(ClientTrackCache.INSTANCE::onLogout);
        NeoForge.EVENT_BUS.addListener(ClientTrackCache.INSTANCE::onTick);
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
        com.unrealtrainmod.client.wiring.WiringClient.clear();
        com.unrealtrainmod.client.wiring.WiringModelManager.INSTANCE.close();
        VehicleSoundManager.INSTANCE.clear();
        TrainDrivingClient.clear();
        TrainWalkingClient.clear();
        TrainPoseClient.clear();
        VehicleScriptManager.clear();
        ClientThumbnailManager.INSTANCE.close();
        CachedItemRenderer.invalidate();
        ClientModelManager.INSTANCE.close();
        TrainModelRenderer.closeWorld();
        ClientTrackCache.INSTANCE.close();
        ClientRailModelManager.INSTANCE.close();
        ClientButtonTextureManager.INSTANCE.close();
        ClientTrackConstruction.INSTANCE.clear();
        ClientTurnoutConstruction.INSTANCE.clear();
    }
}
