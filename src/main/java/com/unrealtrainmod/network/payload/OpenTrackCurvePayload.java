/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackVerticalCurveParameters;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpenTrackCurvePayload(ResourceLocation dimension, UUID revision, UUID editorId,
                                    ConstructionKind kind, int endpoint, boolean horizontalLocked, boolean automatic, TrackCurveParameters initial,
                                    boolean verticalAutomatic, boolean verticalLocked, TrackVerticalCurveParameters initialVertical)
        implements CustomPacketPayload {
    public enum ConstructionKind { TRACK, TURNOUT }
    public boolean start() { return endpoint == 0; }
    public boolean turnout() { return kind == ConstructionKind.TURNOUT; }
    public static final Type<OpenTrackCurvePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "open_track_curve"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTrackCurvePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension());
                buffer.writeUUID(payload.revision());
                buffer.writeUUID(payload.editorId());
                buffer.writeEnum(payload.kind());
                buffer.writeVarInt(payload.endpoint());
                buffer.writeBoolean(payload.horizontalLocked());
                buffer.writeBoolean(payload.automatic());
                buffer.writeDouble(payload.initial().angle());
                buffer.writeDouble(payload.initial().length());
                buffer.writeBoolean(payload.verticalAutomatic());
                buffer.writeBoolean(payload.verticalLocked());
                TrackPayloadCodec.writeVertical(buffer, payload.initialVertical());
            }, buffer -> new OpenTrackCurvePayload(buffer.readResourceLocation(), buffer.readUUID(), buffer.readUUID(),
                    buffer.readEnum(ConstructionKind.class), buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(), new TrackCurveParameters(buffer.readDouble(), buffer.readDouble()),
                    buffer.readBoolean(), buffer.readBoolean(), java.util.Objects.requireNonNull(TrackPayloadCodec.readVertical(buffer))));

    @Override public Type<OpenTrackCurvePayload> type() { return TYPE; }
}
