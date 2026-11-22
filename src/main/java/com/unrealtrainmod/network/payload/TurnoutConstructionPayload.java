/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.construction.TrackConstructionType;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Private draft. Controls are present only once the server has assigned ports. */
public record TurnoutConstructionPayload(ResourceLocation dimension, UUID revision, TrackConstructionType kind,
        List<TrackPlacementEndpoint> points, double gauge, List<TrackCurveParameters> curves, boolean resolved, boolean alternate,
        Feedback feedback, Feedback validation)
        implements CustomPacketPayload {
    public static final Type<TurnoutConstructionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "turnout_construction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TurnoutConstructionPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeResourceLocation(p.dimension());
                b.writeUUID(p.revision());
                b.writeBoolean(p.kind() != null);
                if (p.kind() != null) b.writeEnum(p.kind());
                b.writeVarInt(p.points().size());
                for (var point : p.points()) TrackConstructionPayload.writeEndpoint(b, point);
                b.writeBoolean(p.ordered());
                for (var curve : p.curves()) TrackPayloadCodec.writeCurve(b, curve);
                b.writeDouble(p.gauge());
                b.writeBoolean(p.resolved());
                b.writeBoolean(p.alternate());
                b.writeEnum(p.feedback());
                b.writeEnum(p.validation());
            }, b -> {
                var dimension = b.readResourceLocation();
                var revision = b.readUUID();
                var kind = b.readBoolean() ? b.readEnum(TrackConstructionType.class) : null;
                int count = b.readVarInt();
                if (count < 0 || count > 4) throw new IllegalArgumentException("Invalid structure draft point count");
                var points = new ArrayList<TrackPlacementEndpoint>(count);
                var curves = new ArrayList<TrackCurveParameters>(count);
                for (int i = 0; i < count; i++) {
                    points.add(java.util.Objects.requireNonNull(TrackConstructionPayload.readEndpoint(b)));
                }
                if (b.readBoolean()) {
                    if (kind == null || count != kind.pointCount()) throw new IllegalArgumentException("Incomplete ordered structure");
                    for (int i = 0; i < count; i++) curves.add(TrackPayloadCodec.readCurve(b));
                }
                return new TurnoutConstructionPayload(dimension, revision, kind, points, b.readDouble(), curves,
                        b.readBoolean(), b.readBoolean(), b.readEnum(Feedback.class), b.readEnum(Feedback.class));
            });
    public boolean ordered() { return !curves.isEmpty(); }
    @Override public Type<TurnoutConstructionPayload> type() { return TYPE; }
}
