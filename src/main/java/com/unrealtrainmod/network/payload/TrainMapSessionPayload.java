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

public record TrainMapSessionPayload(UUID session, ResourceLocation dimension, Status status) implements CustomPacketPayload {
    public enum Status { OPEN, CLOSE, ERROR, NO_PERMISSION }
    public static final Type<TrainMapSessionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_map_session"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainMapSessionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.session());
                buffer.writeResourceLocation(value.dimension());
                buffer.writeEnum(value.status());
            }, buffer -> new TrainMapSessionPayload(buffer.readUUID(), buffer.readResourceLocation(), buffer.readEnum(Status.class)));
    @Override public Type<TrainMapSessionPayload> type() { return TYPE; }
}
