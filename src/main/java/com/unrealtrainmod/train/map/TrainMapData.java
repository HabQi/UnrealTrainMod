/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train.map;

import com.unrealtrainmod.track.TrackSegmentData;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.train.TrainConsist;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Immutable monitor values, independent of entity tracking and runtime route windows. */
public final class TrainMapData {
    private TrainMapData() {}

    public sealed interface Entry permits Track, Removed, Train, TrainFragment, Occupancy, Turnout, Path {}
    public record Track(TrackSegmentData segment) implements Entry {}
    public record Removed(UUID segmentId) implements Entry {}
    public enum PositionSource { LIVE, SAVED, MISSING_ANCHOR, MISSING_TRACK, INVALID_ANCHOR }
    public record Car(UUID id, ResourceLocation model, boolean loaded, PositionSource source,
                      TrackVector position, double directionX, double directionZ) {}
    public record Train(UUID id, boolean running, TrainConsist.StopReason reason, boolean restored,
                        double configuredSpeed, double actualSpeed, List<Car> cars) implements Entry {
        public Train { cars = List.copyOf(cars); }
    }
    /** A byte-budgeted contiguous slice; only complete trains are exposed to the screen. */
    public record TrainFragment(Train train, int totalCars, int firstCar) implements Entry {
        public TrainFragment {
            if (totalCars < 1 || firstCar < 0 || firstCar >= totalCars || train.cars().isEmpty()
                    || train.cars().size() > totalCars - firstCar) {
                throw new IllegalArgumentException("Invalid map train fragment range");
            }
        }
    }
    /** otherTrain is non-null for a conflict; distances always use the physical segment's start. */
    public record Occupancy(UUID trainId, UUID otherTrain, UUID segmentId, double start, double end) implements Entry {}
    public record Turnout(UUID id, TrackVector position, String controls, UUID occupant) implements Entry {}
    public record Path(UUID segmentId, boolean enabled) implements Entry {}
}
