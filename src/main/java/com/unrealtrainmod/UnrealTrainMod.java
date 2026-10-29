/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 * Original NeoForge MDK template material: see TEMPLATE_LICENSE.txt.
 */
package com.unrealtrainmod;

import com.unrealtrainmod.loading.ResourceLoader;
import com.unrealtrainmod.command.TrackCommands;
import com.unrealtrainmod.command.TrainCommands;
import com.unrealtrainmod.train.TrainManager;
import com.unrealtrainmod.entity.PlacedModelEntity;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.item.ModelPlacerItem;
import com.unrealtrainmod.item.VehicleItem;
import com.unrealtrainmod.item.TrackBuilderItem;
import com.unrealtrainmod.item.TrackWrenchItem;
import com.unrealtrainmod.item.RailPlacerItem;
import com.unrealtrainmod.track.construction.TrackConstructionManager;
import com.unrealtrainmod.track.block.TrackBlock;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.network.ModPayloads;
import com.unrealtrainmod.network.TrackSyncManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(UnrealTrainMod.MODID)
public class UnrealTrainMod {
    public static final String MODID = "unrealtrainmod";
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<PlacedModelEntity>> PLACED_MODEL = ENTITY_TYPES.register(
            "placed_model", () -> EntityType.Builder.<PlacedModelEntity>of(PlacedModelEntity::new, MobCategory.MISC)
                    .sized(1, 1)
                    .clientTrackingRange(16).updateInterval(20).fireImmune().build(MODID + ":placed_model"));
    public static final DeferredHolder<EntityType<?>, EntityType<TrainEntity>> TRAIN = ENTITY_TYPES.register(
            "train", () -> EntityType.Builder.<TrainEntity>of(TrainEntity::new, MobCategory.MISC)
                    .sized(1, 1)
                    .clientTrackingRange(16).updateInterval(2).fireImmune().build(MODID + ":train"));
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<TrackBlock> TRACK_BLOCK = BLOCKS.registerBlock("track", TrackBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(0.5f).sound(SoundType.METAL)
                    .noOcclusion().noLootTable().pushReaction(PushReaction.BLOCK)
                    .isSuffocating((state, level, pos) -> false).isViewBlocking((state, level, pos) -> false));

    public static final DeferredItem<ModelPlacerItem> VEHICLE_PLACER = ITEMS.registerItem(
            "vehicle_placer", ModelPlacerItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<VehicleItem> VEHICLE = ITEMS.registerItem(
            "vehicle", VehicleItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<TrackBuilderItem> TRACK_BUILDER = ITEMS.registerItem(
            "track_builder", TrackBuilderItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<RailPlacerItem> RAIL_PLACER = ITEMS.registerItem(
            "rail_placer", RailPlacerItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<TrackWrenchItem> TRACK_WRENCH = ITEMS.registerItem(
            "track_wrench", TrackWrenchItem::new, new Item.Properties().stacksTo(1));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MOD_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.unrealtrainmod"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> VEHICLE_PLACER.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(VEHICLE_PLACER.get());
                output.accept(TRACK_BUILDER.get());
                output.accept(RAIL_PLACER.get());
                output.accept(TRACK_WRENCH.get());
                output.accept(VEHICLE.get());
            }).build());

    public UnrealTrainMod(IEventBus modEventBus) {
        ResourceLoader.load(FMLPaths.MODSDIR.get(), FMLPaths.CONFIGDIR.get());

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        ModDataComponents.TYPES.register(modEventBus);
        modEventBus.addListener(ModPayloads::register);
        CREATIVE_MODE_TABS.register(modEventBus);

        NeoForge.EVENT_BUS.addListener(TrackCommands::register);
        NeoForge.EVENT_BUS.addListener(TrainCommands::register);
        TrackBlockManager.register(NeoForge.EVENT_BUS);
        TrackSyncManager.register(NeoForge.EVENT_BUS);
        TrackConstructionManager.register(NeoForge.EVENT_BUS);
        TrainManager.register(NeoForge.EVENT_BUS);
    }
}
