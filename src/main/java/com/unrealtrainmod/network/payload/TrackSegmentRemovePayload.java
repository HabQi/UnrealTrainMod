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

public record TrackSegmentRemovePayload(ResourceLocation dimension, UUID segmentId) implements CustomPacketPayload {
    public static final Type<TrackSegmentRemovePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "track_segment_remove"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrackSegmentRemovePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension());
                buffer.writeUUID(payload.segmentId());
            }, buffer -> new TrackSegmentRemovePayload(buffer.readResourceLocation(), buffer.readUUID()));

    @Override public Type<TrackSegmentRemovePayload> type() { return TYPE; }
}
