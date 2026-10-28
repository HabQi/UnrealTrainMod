/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 * Original NeoForge MDK template material: see TEMPLATE_LICENSE.txt.
 */
package com.unrealtrainmod;

import com.unrealtrainmod.loading.ResourceLoader;
import com.unrealtrainmod.command.TrackCommands;
import com.unrealtrainmod.entity.PlacedModelEntity;
import com.unrealtrainmod.item.ModelPlacerItem;
import com.unrealtrainmod.item.TrackBuilderItem;
import com.unrealtrainmod.item.TrackWrenchItem;
import com.unrealtrainmod.item.RailPlacerItem;
import com.unrealtrainmod.track.construction.TrackConstructionManager;
import com.unrealtrainmod.track.block.TrackBlock;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.registry.ModPayloads;
import com.unrealtrainmod.network.TrackSyncManager;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(UnrealTrainMod.MODID)
public class UnrealTrainMod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "unrealtrainmod";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "unrealtrainmod" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "unrealtrainmod" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<PlacedModelEntity>> PLACED_MODEL = ENTITY_TYPES.register(
            "placed_model", () -> EntityType.Builder.<PlacedModelEntity>of(PlacedModelEntity::new, MobCategory.MISC)
                    .sized(1, 1)
                    .clientTrackingRange(16).updateInterval(20).fireImmune().build(MODID + ":placed_model"));
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "unrealtrainmod" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "unrealtrainmod:example_block", combining the namespace and path
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    public static final DeferredBlock<TrackBlock> TRACK_BLOCK = BLOCKS.registerBlock("track", TrackBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(0.5f).sound(SoundType.METAL)
                    .noOcclusion().noLootTable().pushReaction(PushReaction.BLOCK)
                    .isSuffocating((state, level, pos) -> false).isViewBlocking((state, level, pos) -> false));
    // Creates a new BlockItem with the id "unrealtrainmod:example_block", combining the namespace and path
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    public static final DeferredItem<ModelPlacerItem> VEHICLE_PLACER = ITEMS.registerItem(
            "vehicle_placer", ModelPlacerItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<TrackBuilderItem> TRACK_BUILDER = ITEMS.registerItem(
            "track_builder", TrackBuilderItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<RailPlacerItem> RAIL_PLACER = ITEMS.registerItem(
            "rail_placer", RailPlacerItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<TrackWrenchItem> TRACK_WRENCH = ITEMS.registerItem(
            "track_wrench", TrackWrenchItem::new, new Item.Properties().stacksTo(1));

    // Creates a creative tab with the id "unrealtrainmod:example_tab" for the example item, that is placed after the combat tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.unrealtrainmod")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> VEHICLE_PLACER.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(VEHICLE_PLACER.get());
                output.accept(TRACK_BUILDER.get());
                output.accept(RAIL_PLACER.get());
                output.accept(TRACK_WRENCH.get());
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public UnrealTrainMod(IEventBus modEventBus, ModContainer modContainer) {
        ResourceLoader.load(FMLPaths.MODSDIR.get());
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        ModDataComponents.TYPES.register(modEventBus);
        modEventBus.addListener(ModPayloads::register);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (UnrealTrainMod) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.addListener(TrackCommands::register);
        TrackSyncManager.register(NeoForge.EVENT_BUS);
        TrackConstructionManager.register(NeoForge.EVENT_BUS);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
