/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Confirms the actual resolved draft revision displayed when the placer was clicked. */
public record ConfirmSingleCrossoverPayload(ResourceLocation dimension, UUID revision, BlockPos clicked)
        implements CustomPacketPayload {
    public static final Type<ConfirmSingleCrossoverPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "confirm_single_crossover"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfirmSingleCrossoverPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension()); buffer.writeUUID(payload.revision());
                buffer.writeBlockPos(payload.clicked());
            }, buffer -> new ConfirmSingleCrossoverPayload(buffer.readResourceLocation(), buffer.readUUID(), buffer.readBlockPos()));
    @Override public Type<ConfirmSingleCrossoverPayload> type() { return TYPE; }
}
