/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TrackCurveResultPayload(UUID editorId, boolean accepted, Feedback feedback) implements CustomPacketPayload {
    public static final Type<TrackCurveResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "track_curve_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrackCurveResultPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.editorId());
                buffer.writeBoolean(payload.accepted());
                buffer.writeEnum(payload.feedback());
            }, buffer -> new TrackCurveResultPayload(buffer.readUUID(), buffer.readBoolean(), buffer.readEnum(Feedback.class)));

    @Override public Type<TrackCurveResultPayload> type() { return TYPE; }
}
