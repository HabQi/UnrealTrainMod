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
public record ConfirmTurnoutPayload(ResourceLocation dimension, UUID revision, BlockPos clicked)
        implements CustomPacketPayload {
    public static final Type<ConfirmTurnoutPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "confirm_turnout"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfirmTurnoutPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension()); buffer.writeUUID(payload.revision());
                buffer.writeBlockPos(payload.clicked());
            }, buffer -> new ConfirmTurnoutPayload(buffer.readResourceLocation(), buffer.readUUID(), buffer.readBlockPos()));
    @Override public Type<ConfirmTurnoutPayload> type() { return TYPE; }
}
