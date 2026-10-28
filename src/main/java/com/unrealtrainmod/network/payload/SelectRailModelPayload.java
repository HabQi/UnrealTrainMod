/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SelectRailModelPayload(UUID requestId, int hotbarSlot, ResourceLocation modelId) implements CustomPacketPayload {
    public static final Type<SelectRailModelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "select_rail_model"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectRailModelPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.requestId());
                buffer.writeVarInt(payload.hotbarSlot());
                buffer.writeResourceLocation(payload.modelId());
            }, buffer -> new SelectRailModelPayload(buffer.readUUID(), buffer.readVarInt(), buffer.readResourceLocation()));
    @Override public Type<SelectRailModelPayload> type() { return TYPE; }
}
