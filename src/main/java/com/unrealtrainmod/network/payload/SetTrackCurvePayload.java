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

/** Raw values are checked by the handler, allowing malformed input to receive an editing error. */
public record SetTrackCurvePayload(ResourceLocation dimension, UUID revision, UUID editorId,
                                   boolean start, boolean automatic, double angle, double length)
        implements CustomPacketPayload {
    public static final Type<SetTrackCurvePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "set_track_curve"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTrackCurvePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension());
                buffer.writeUUID(payload.revision());
                buffer.writeUUID(payload.editorId());
                buffer.writeBoolean(payload.start());
                buffer.writeBoolean(payload.automatic());
                buffer.writeDouble(payload.angle());
                buffer.writeDouble(payload.length());
            }, buffer -> new SetTrackCurvePayload(buffer.readResourceLocation(), buffer.readUUID(), buffer.readUUID(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readDouble(), buffer.readDouble()));

    @Override public Type<SetTrackCurvePayload> type() { return TYPE; }
}
