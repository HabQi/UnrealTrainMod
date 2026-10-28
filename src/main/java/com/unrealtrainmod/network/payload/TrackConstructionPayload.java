/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.Locale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Private, server-authoritative draft; never inserted into the synchronized track cache. */
public record TrackConstructionPayload(ResourceLocation dimension, int slot, Phase phase,
        TrackPlacementEndpoint start, TrackPlacementEndpoint end, double gauge, Feedback feedback)
        implements CustomPacketPayload {
    public enum Phase { EMPTY, START, READY }

    public enum Feedback {
        START, READY, CANCELLED, CLEARED, CREATED, SELECT_POINT, TOP_FACE, INVALID_POINT,
        INVALID_GEOMETRY, TOO_LONG, SAME_NODE, GAUGE_MISMATCH, STALE_START, STALE_END,
        STORAGE, NO_PERMISSION, INVALID_ITEM;

        public String translationKey() {
            return "item.unrealtrainmod.track_builder.feedback." + name().toLowerCase(Locale.ROOT);
        }
    }

    public static final Type<TrackConstructionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "track_construction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrackConstructionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension());
                buffer.writeVarInt(payload.slot());
                buffer.writeEnum(payload.phase());
                writeEndpoint(buffer, payload.start());
                writeEndpoint(buffer, payload.end());
                buffer.writeDouble(payload.gauge());
                buffer.writeEnum(payload.feedback());
            }, buffer -> new TrackConstructionPayload(buffer.readResourceLocation(), buffer.readVarInt(),
                    buffer.readEnum(Phase.class), readEndpoint(buffer), readEndpoint(buffer),
                    buffer.readDouble(), buffer.readEnum(Feedback.class)));

    private static void writeEndpoint(RegistryFriendlyByteBuf buffer, TrackPlacementEndpoint endpoint) {
        buffer.writeBoolean(endpoint != null);
        if (endpoint == null) return;
        var point = endpoint.position();
        buffer.writeDouble(point.x());
        buffer.writeDouble(point.y());
        buffer.writeDouble(point.z());
        buffer.writeBoolean(endpoint.nodeId() != null);
        if (endpoint.nodeId() != null) buffer.writeUUID(endpoint.nodeId());
    }

    private static TrackPlacementEndpoint readEndpoint(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) return null;
        TrackVector point = new TrackVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        return new TrackPlacementEndpoint(buffer.readBoolean() ? buffer.readUUID() : null, point);
    }

    @Override public Type<TrackConstructionPayload> type() { return TYPE; }
}
