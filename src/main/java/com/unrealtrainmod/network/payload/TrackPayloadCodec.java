/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackSegmentData;
import com.unrealtrainmod.track.geometry.TrackPoint;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;

final class TrackPayloadCodec {
    private TrackPayloadCodec() {}

    static void writeCurve(RegistryFriendlyByteBuf buffer, TrackCurveParameters curve) {
        buffer.writeBoolean(curve != null);
        if (curve != null) {
            buffer.writeDouble(curve.angle());
            buffer.writeDouble(curve.length());
        }
    }

    static TrackCurveParameters readCurve(RegistryFriendlyByteBuf buffer) {
        return buffer.readBoolean() ? new TrackCurveParameters(buffer.readDouble(), buffer.readDouble()) : null;
    }

    static void write(RegistryFriendlyByteBuf buffer, TrackSegmentData data) {
        buffer.writeUUID(data.id());
        buffer.writeEnum(data.type());
        writeVector(buffer, data.start());
        writeVector(buffer, data.end());
        buffer.writeDouble(data.gauge());
        for (TrackPoint control : data.controls()) {
            buffer.writeDouble(control.x());
            buffer.writeDouble(control.z());
        }
        buffer.writeBoolean(data.modelId() != null);
        if (data.modelId() != null) buffer.writeResourceLocation(net.minecraft.resources.ResourceLocation.parse(data.modelId()));
    }

    static TrackSegmentData read(RegistryFriendlyByteBuf buffer) {
        var id = buffer.readUUID();
        var type = buffer.readEnum(TrackSegment.Type.class);
        TrackVector start = readVector(buffer), end = readVector(buffer);
        double gauge = buffer.readDouble();
        List<TrackPoint> controls = type == TrackSegment.Type.BEZIER
                ? List.of(new TrackPoint(buffer.readDouble(), buffer.readDouble()),
                        new TrackPoint(buffer.readDouble(), buffer.readDouble())) : List.of();
        String modelId = buffer.readBoolean() ? buffer.readResourceLocation().toString() : null;
        return new TrackSegmentData(id, type, start, end, gauge, controls, modelId);
    }

    private static void writeVector(RegistryFriendlyByteBuf buffer, TrackVector vector) {
        buffer.writeDouble(vector.x());
        buffer.writeDouble(vector.y());
        buffer.writeDouble(vector.z());
    }

    private static TrackVector readVector(RegistryFriendlyByteBuf buffer) {
        return new TrackVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
}
