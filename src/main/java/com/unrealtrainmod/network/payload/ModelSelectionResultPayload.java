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

public record ModelSelectionResultPayload(UUID requestId, Result result) implements CustomPacketPayload {
    public enum Result { SUCCESS, INVALID_ITEM, UNKNOWN_MODEL, NO_PERMISSION }

    public static final Type<ModelSelectionResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "model_selection_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ModelSelectionResultPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.requestId());
                buffer.writeEnum(payload.result());
            },
            buffer -> new ModelSelectionResultPayload(buffer.readUUID(), buffer.readEnum(Result.class)));

    @Override public Type<ModelSelectionResultPayload> type() { return TYPE; }
}
