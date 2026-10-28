/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.TrackCurveParameters;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpenTrackCurvePayload(ResourceLocation dimension, UUID revision, UUID editorId,
                                    boolean start, boolean automatic, TrackCurveParameters initial)
        implements CustomPacketPayload {
    public static final Type<OpenTrackCurvePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "open_track_curve"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTrackCurvePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension());
                buffer.writeUUID(payload.revision());
                buffer.writeUUID(payload.editorId());
                buffer.writeBoolean(payload.start());
                buffer.writeBoolean(payload.automatic());
                buffer.writeDouble(payload.initial().angle());
                buffer.writeDouble(payload.initial().length());
            }, buffer -> new OpenTrackCurvePayload(buffer.readResourceLocation(), buffer.readUUID(), buffer.readUUID(),
                    buffer.readBoolean(), buffer.readBoolean(), new TrackCurveParameters(buffer.readDouble(), buffer.readDouble())));

    @Override public Type<OpenTrackCurvePayload> type() { return TYPE; }
}
