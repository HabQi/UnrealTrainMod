/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.construction.TrackConstructionType;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SelectRailModelPayload(UUID requestId, int hotbarSlot, ResourceLocation modelId,
                                     TrackConstructionType variant) implements CustomPacketPayload {
    public static final Type<SelectRailModelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "select_rail_model"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectRailModelPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.requestId());
                buffer.writeVarInt(payload.hotbarSlot());
                buffer.writeResourceLocation(payload.modelId());
                buffer.writeBoolean(payload.variant() != null);
                if (payload.variant() != null) buffer.writeEnum(payload.variant());
            }, buffer -> new SelectRailModelPayload(buffer.readUUID(), buffer.readVarInt(), buffer.readResourceLocation(),
                    buffer.readBoolean() ? buffer.readEnum(TrackConstructionType.class) : null));
    @Override public Type<SelectRailModelPayload> type() { return TYPE; }
}
