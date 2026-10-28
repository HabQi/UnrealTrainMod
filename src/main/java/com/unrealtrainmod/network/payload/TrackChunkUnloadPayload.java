/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TrackChunkUnloadPayload(ResourceLocation dimension, int chunkX, int chunkZ) implements CustomPacketPayload {
    public static final Type<TrackChunkUnloadPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "track_chunk_unload"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrackChunkUnloadPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension());
                buffer.writeInt(payload.chunkX());
                buffer.writeInt(payload.chunkZ());
            }, buffer -> new TrackChunkUnloadPayload(buffer.readResourceLocation(), buffer.readInt(), buffer.readInt()));

    @Override public Type<TrackChunkUnloadPayload> type() { return TYPE; }
}
