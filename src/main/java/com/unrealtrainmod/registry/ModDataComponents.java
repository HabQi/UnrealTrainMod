/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.registry;

import com.unrealtrainmod.UnrealTrainMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, UnrealTrainMod.MODID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> SELECTED_MODEL =
            TYPES.register("selected_model", () -> DataComponentType.<ResourceLocation>builder()
                    .persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC).build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> SELECTED_RAIL_MODEL =
            TYPES.register("selected_rail_model", () -> DataComponentType.<ResourceLocation>builder()
                    .persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC).build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> SELECTED_CONNECTOR_MODEL =
            TYPES.register("selected_connector_model", () -> DataComponentType.<ResourceLocation>builder()
                    .persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC).build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> SELECTED_WIRE_MODEL =
            TYPES.register("selected_wire_model", () -> DataComponentType.<ResourceLocation>builder()
                    .persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC).build());

    private ModDataComponents() {}

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.unrealtrainmod.blueprint.BlueprintDefinition>> BLUEPRINT =
            TYPES.register("blueprint", () -> DataComponentType.<com.unrealtrainmod.blueprint.BlueprintDefinition>builder()
                    .persistent(com.unrealtrainmod.blueprint.BlueprintDefinition.CODEC)
                    .networkSynchronized(net.minecraft.network.codec.StreamCodec.of(
                            (net.minecraft.network.RegistryFriendlyByteBuf b, com.unrealtrainmod.blueprint.BlueprintDefinition d) -> b.writeNbt(d.save()),
                            b -> com.unrealtrainmod.blueprint.BlueprintDefinition.load(java.util.Objects.requireNonNull(b.readNbt())))).build());
}
