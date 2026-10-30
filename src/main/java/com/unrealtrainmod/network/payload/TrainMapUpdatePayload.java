/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.train.TrainConsist;
import com.unrealtrainmod.train.map.TrainMapData.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Each sequence is assembled completely before replacing the corresponding client view. */
public record TrainMapUpdatePayload(UUID session, ResourceLocation dimension, Channel channel, long sequence,
                                    boolean reset, int index, int count, List<Entry> entries) implements CustomPacketPayload {
    public enum Channel { TOPOLOGY, DYNAMIC }
    public static final int MAX_ENTRIES = 128;
    public static final int MAX_BYTES = 48 * 1024;
    public static final Type<TrainMapUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_map_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainMapUpdatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.session());
                buffer.writeResourceLocation(value.dimension());
                buffer.writeEnum(value.channel());
                buffer.writeLong(value.sequence());
                buffer.writeBoolean(value.reset());
                buffer.writeVarInt(value.index());
                buffer.writeVarInt(value.count());
                buffer.writeVarInt(value.entries().size());
                for (var entry : value.entries()) writeEntry(buffer, entry);
            }, TrainMapUpdatePayload::read);

    public TrainMapUpdatePayload {
        entries = List.copyOf(entries);
        if (sequence < 0 || index < 0 || count < 1 || index >= count || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid map batch");
        }
        for (var entry : entries) {
            boolean topology = entry instanceof Track || entry instanceof Removed;
            if (topology != (channel == Channel.TOPOLOGY)) throw new IllegalArgumentException("Invalid map entry channel");
        }
    }

    private static TrainMapUpdatePayload read(RegistryFriendlyByteBuf buffer) {
        var session = buffer.readUUID();
        var dimension = buffer.readResourceLocation();
        var channel = buffer.readEnum(Channel.class);
        long sequence = buffer.readLong();
        boolean reset = buffer.readBoolean();
        int index = buffer.readVarInt(), count = buffer.readVarInt(), size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) throw new IllegalArgumentException("Invalid map entry count");
        var entries = new ArrayList<Entry>(size);
        for (int i = 0; i < size; i++) entries.add(readEntry(buffer));
        return new TrainMapUpdatePayload(session, dimension, channel, sequence, reset, index, count, entries);
    }

    /** Also used by the server's byte-budgeted batch builder. */
    public static void writeEntry(RegistryFriendlyByteBuf buffer, Entry entry) {
        switch (entry) {
            case Track value -> { buffer.writeByte(0); TrackPayloadCodec.write(buffer, value.segment()); }
            case Removed value -> { buffer.writeByte(1); buffer.writeUUID(value.segmentId()); }
            case Train value -> {
                buffer.writeByte(2);
                buffer.writeUUID(value.id());
                buffer.writeBoolean(value.running());
                buffer.writeEnum(value.reason());
                buffer.writeBoolean(value.restored());
                buffer.writeDouble(value.configuredSpeed());
                buffer.writeDouble(value.actualSpeed());
                buffer.writeVarInt(value.cars().size());
                for (var car : value.cars()) {
                    buffer.writeUUID(car.id());
                    buffer.writeResourceLocation(car.model());
                    buffer.writeBoolean(car.loaded());
                    buffer.writeEnum(car.source());
                    buffer.writeBoolean(car.position() != null);
                    if (car.position() != null) writeVector(buffer, car.position());
                    buffer.writeDouble(car.directionX());
                    buffer.writeDouble(car.directionZ());
                }
            }
            case Occupancy value -> {
                buffer.writeByte(3);
                buffer.writeUUID(value.trainId());
                writeOptionalId(buffer, value.otherTrain());
                buffer.writeUUID(value.segmentId());
                buffer.writeDouble(value.start());
                buffer.writeDouble(value.end());
            }
            case Turnout value -> {
                buffer.writeByte(4);
                buffer.writeUUID(value.id());
                writeVector(buffer, value.position());
                buffer.writeUtf(value.controls(), 4096);
                writeOptionalId(buffer, value.occupant());
            }
            case Path value -> { buffer.writeByte(5); buffer.writeUUID(value.segmentId()); buffer.writeBoolean(value.enabled()); }
        }
    }

    private static Entry readEntry(RegistryFriendlyByteBuf buffer) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> new Track(TrackPayloadCodec.read(buffer));
            case 1 -> new Removed(buffer.readUUID());
            case 2 -> {
                var id = buffer.readUUID();
                boolean running = buffer.readBoolean();
                var reason = buffer.readEnum(TrainConsist.StopReason.class);
                boolean restored = buffer.readBoolean();
                double configured = finite(buffer.readDouble()), actual = finite(buffer.readDouble());
                int size = buffer.readVarInt();
                if (size < 1 || size > 2) throw new IllegalArgumentException("Invalid map member count");
                var cars = new ArrayList<Car>(size);
                for (int i = 0; i < size; i++) {
                    var carId = buffer.readUUID();
                    var model = buffer.readResourceLocation();
                    boolean loaded = buffer.readBoolean();
                    var source = buffer.readEnum(PositionSource.class);
                    var position = buffer.readBoolean() ? readVector(buffer) : null;
                    double dx = finite(buffer.readDouble()), dz = finite(buffer.readDouble());
                    if ((source == PositionSource.LIVE || source == PositionSource.SAVED) != (position != null)) {
                        throw new IllegalArgumentException("Invalid map position source");
                    }
                    cars.add(new Car(carId, model, loaded, source, position, dx, dz));
                }
                yield new Train(id, running, reason, restored, configured, actual, cars);
            }
            case 3 -> {
                var train = buffer.readUUID();
                var other = readOptionalId(buffer);
                var segment = buffer.readUUID();
                double start = finite(buffer.readDouble()), end = finite(buffer.readDouble());
                if (start < 0 || end < start) throw new IllegalArgumentException("Invalid map interval");
                yield new Occupancy(train, other, segment, start, end);
            }
            case 4 -> new Turnout(buffer.readUUID(), readVector(buffer), buffer.readUtf(4096), readOptionalId(buffer));
            case 5 -> new Path(buffer.readUUID(), buffer.readBoolean());
            default -> throw new IllegalArgumentException("Unknown map entry");
        };
    }

    private static void writeVector(RegistryFriendlyByteBuf buffer, TrackVector value) {
        buffer.writeDouble(value.x()); buffer.writeDouble(value.y()); buffer.writeDouble(value.z());
    }
    private static TrackVector readVector(RegistryFriendlyByteBuf buffer) {
        return new TrackVector(finite(buffer.readDouble()), finite(buffer.readDouble()), finite(buffer.readDouble()));
    }
    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite map coordinate");
        return value;
    }
    private static void writeOptionalId(RegistryFriendlyByteBuf buffer, UUID value) {
        buffer.writeBoolean(value != null);
        if (value != null) buffer.writeUUID(value);
    }
    private static UUID readOptionalId(RegistryFriendlyByteBuf buffer) { return buffer.readBoolean() ? buffer.readUUID() : null; }
    @Override public Type<TrainMapUpdatePayload> type() { return TYPE; }
}
