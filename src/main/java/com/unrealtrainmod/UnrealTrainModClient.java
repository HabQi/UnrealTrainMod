/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 * Original NeoForge MDK template material: see TEMPLATE_LICENSE.txt.
 */
package com.unrealtrainmod;

import com.unrealtrainmod.client.ClientModelManager;
import com.unrealtrainmod.client.PlacedModelRenderer;
import com.unrealtrainmod.client.StaticModelShader;
import com.unrealtrainmod.client.network.ClientPayloadHandlers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = UnrealTrainMod.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = UnrealTrainMod.MODID, value = Dist.CLIENT)
public class UnrealTrainModClient {
    @SubscribeEvent
    static void registerShaders(RegisterShadersEvent event) {
        StaticModelShader.register(event);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(UnrealTrainMod.PLACED_MODEL.get(), PlacedModelRenderer::new);
    }

    @SubscribeEvent
    static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(ClientModelManager.INSTANCE);
    }

    public UnrealTrainModClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(UnrealTrainModClient::onGameShuttingDown);
        NeoForge.EVENT_BUS.addListener(ClientPayloadHandlers::openModelSelector);
        NeoForge.EVENT_BUS.addListener(ClientPayloadHandlers::modelSelectionResult);
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        ClientModelManager.INSTANCE.close();
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        UnrealTrainMod.LOGGER.info("HELLO FROM CLIENT SETUP");
        UnrealTrainMod.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
