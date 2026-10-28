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

public record RailModelSelectionResultPayload(UUID requestId, ModelSelectionResultPayload.Result result) implements CustomPacketPayload {
    public static final Type<RailModelSelectionResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "rail_model_selection_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RailModelSelectionResultPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeUUID(payload.requestId()); buffer.writeEnum(payload.result()); },
            buffer -> new RailModelSelectionResultPayload(buffer.readUUID(), buffer.readEnum(ModelSelectionResultPayload.Result.class)));
    @Override public Type<RailModelSelectionResultPayload> type() { return TYPE; }
}
