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
import net.minecraft.world.InteractionHand;

public record SelectTrainModelPayload(UUID requestId, InteractionHand hand, int hotbarSlot,
                                      ResourceLocation itemId, ResourceLocation modelId) implements CustomPacketPayload {
    public static final Type<SelectTrainModelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "select_train_model"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectTrainModelPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.requestId());
                buffer.writeEnum(payload.hand());
                buffer.writeVarInt(payload.hotbarSlot());
                buffer.writeUtf(payload.itemId().toString(), 256);
                buffer.writeUtf(payload.modelId().toString(), 256);
            },
            buffer -> new SelectTrainModelPayload(buffer.readUUID(), buffer.readEnum(InteractionHand.class),
                    buffer.readVarInt(), ResourceLocation.parse(buffer.readUtf(256)),
                    ResourceLocation.parse(buffer.readUtf(256))));

    @Override public Type<SelectTrainModelPayload> type() { return TYPE; }
}
