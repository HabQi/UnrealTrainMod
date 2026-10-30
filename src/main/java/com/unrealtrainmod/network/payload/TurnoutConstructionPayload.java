/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Private three-point draft. Defaults carry server-inferred external tangents to the local editor. */
public record TurnoutConstructionPayload(ResourceLocation dimension, UUID revision,
        List<TrackPlacementEndpoint> points, double gauge, List<TrackCurveParameters> curves,
        List<TrackCurveParameters> defaults, List<Boolean> locked, Feedback feedback, Feedback validation)
        implements CustomPacketPayload {
    public static final Type<TurnoutConstructionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "turnout_construction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TurnoutConstructionPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeResourceLocation(p.dimension()); b.writeUUID(p.revision()); b.writeVarInt(p.points().size());
                for (int i = 0; i < p.points().size(); i++) {
                    TrackConstructionPayload.writeEndpoint(b, p.points().get(i));
                    TrackPayloadCodec.writeCurve(b, p.curves().get(i));
                    TrackPayloadCodec.writeCurve(b, p.defaults().get(i)); b.writeBoolean(p.locked().get(i));
                }
                b.writeDouble(p.gauge()); b.writeEnum(p.feedback()); b.writeEnum(p.validation());
            }, b -> {
                var dimension = b.readResourceLocation(); var revision = b.readUUID(); int count = b.readVarInt();
                if (count < 0 || count > 3) throw new IllegalArgumentException("Invalid turnout point count");
                var points = new ArrayList<TrackPlacementEndpoint>(); var curves = new ArrayList<TrackCurveParameters>();
                var defaults = new ArrayList<TrackCurveParameters>(); var locked = new ArrayList<Boolean>();
                for (int i = 0; i < count; i++) {
                    points.add(java.util.Objects.requireNonNull(TrackConstructionPayload.readEndpoint(b)));
                    curves.add(TrackPayloadCodec.readCurve(b));
                    defaults.add(java.util.Objects.requireNonNull(TrackPayloadCodec.readCurve(b))); locked.add(b.readBoolean());
                }
                return new TurnoutConstructionPayload(dimension, revision, points, b.readDouble(), curves, defaults,
                        locked, b.readEnum(Feedback.class), b.readEnum(Feedback.class));
            });
    @Override public Type<TurnoutConstructionPayload> type() { return TYPE; }
}
