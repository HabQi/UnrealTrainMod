/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.network.payload.OpenTrackCurvePayload.ConstructionKind;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Raw values are checked by the handler, allowing malformed input to receive an editing error. */
public record SetTrackCurvePayload(ResourceLocation dimension, UUID revision, UUID editorId,
                                   ConstructionKind kind, int endpoint, boolean horizontalEdited, boolean verticalEdited,
                                   boolean automatic, double angle, double length,
                                   boolean verticalAutomatic, double grade, double verticalLength)
        implements CustomPacketPayload {
    public boolean start() { return endpoint == 0; }
    public static final Type<SetTrackCurvePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "set_track_curve"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTrackCurvePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension());
                buffer.writeUUID(payload.revision());
                buffer.writeUUID(payload.editorId());
                buffer.writeEnum(payload.kind());
                buffer.writeVarInt(payload.endpoint());
                buffer.writeBoolean(payload.horizontalEdited());
                buffer.writeBoolean(payload.verticalEdited());
                buffer.writeBoolean(payload.automatic());
                buffer.writeDouble(payload.angle());
                buffer.writeDouble(payload.length());
                buffer.writeBoolean(payload.verticalAutomatic());
                buffer.writeDouble(payload.grade());
                buffer.writeDouble(payload.verticalLength());
            }, buffer -> new SetTrackCurvePayload(buffer.readResourceLocation(), buffer.readUUID(), buffer.readUUID(),
                    buffer.readEnum(ConstructionKind.class), buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readBoolean(), buffer.readDouble(), buffer.readDouble()));

    @Override public Type<SetTrackCurvePayload> type() { return TYPE; }
}
