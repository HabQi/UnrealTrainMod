/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.TrackSegmentData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TrackChunkPayload(ResourceLocation dimension, int chunkX, int chunkZ, boolean reset,
                                List<TrackSegmentData> segments) implements CustomPacketPayload {
    public static final int MAX_SEGMENTS = 128;
    public static final Type<TrackChunkPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "track_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrackChunkPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension());
                buffer.writeInt(payload.chunkX());
                buffer.writeInt(payload.chunkZ());
                buffer.writeBoolean(payload.reset());
                buffer.writeVarInt(payload.segments().size());
                for (var segment : payload.segments()) TrackPayloadCodec.write(buffer, segment);
            }, TrackChunkPayload::read);

    public TrackChunkPayload {
        segments = List.copyOf(segments);
        if (segments.size() > MAX_SEGMENTS) throw new IllegalArgumentException("Track batch exceeds 128 segments");
    }

    private static TrackChunkPayload read(RegistryFriendlyByteBuf buffer) {
        var dimension = buffer.readResourceLocation();
        int x = buffer.readInt(), z = buffer.readInt();
        boolean reset = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_SEGMENTS) throw new IllegalArgumentException("Invalid track batch size");
        List<TrackSegmentData> segments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) segments.add(TrackPayloadCodec.read(buffer));
        return new TrackChunkPayload(dimension, x, z, reset, segments);
    }

    @Override public Type<TrackChunkPayload> type() { return TYPE; }
}
