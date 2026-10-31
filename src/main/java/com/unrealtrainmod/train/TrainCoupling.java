/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** One occupied pair of vehicle-local couplers. Member-list order does not encode adjacency. */
public record TrainCoupling(End first, End second) {
    public record End(UUID vehicle, TrainCarEnd end) {}

    public End peer(End end) {
        if (first.equals(end)) return second;
        return second.equals(end) ? first : null;
    }

    static void validate(List<TrainCarState> cars, List<TrainCoupling> couplings) {
        if (couplings.size() != cars.size() - 1) throw new IllegalArgumentException("Expected a connected train chain");
        var members = new HashMap<UUID, TrainCarState>();
        for (var car : cars) members.put(car.entityId(), car);
        var used = new HashSet<End>();
        for (var link : couplings) {
            var a = members.get(link.first.vehicle());
            var b = members.get(link.second.vehicle());
            if (a == null || b == null || a == b || !used.add(link.first) || !used.add(link.second)) {
                throw new IllegalArgumentException("Invalid or reused train coupler");
            }
            if (a.direction() * link.first.end().sign() != -b.direction() * link.second.end().sign()) {
                throw new IllegalArgumentException("Coupler orientations disagree");
            }
        }
        if (components(cars, couplings).size() != 1) throw new IllegalArgumentException("Disconnected or cyclic train chain");
    }

    /** Components and their members retain the original member-list order. */
    static List<List<TrainCarState>> components(List<TrainCarState> cars, List<TrainCoupling> couplings) {
        var adjacent = new HashMap<UUID, List<UUID>>();
        for (var car : cars) adjacent.put(car.entityId(), new ArrayList<>(2));
        for (var link : couplings) {
            adjacent.get(link.first.vehicle()).add(link.second.vehicle());
            adjacent.get(link.second.vehicle()).add(link.first.vehicle());
        }
        var groups = new HashMap<UUID, Integer>();
        var result = new ArrayList<List<TrainCarState>>();
        for (var car : cars) {
            if (groups.containsKey(car.entityId())) continue;
            int group = result.size();
            result.add(new ArrayList<>());
            var queue = new ArrayDeque<UUID>();
            groups.put(car.entityId(), group);
            queue.add(car.entityId());
            while (!queue.isEmpty()) {
                for (var next : adjacent.get(queue.removeFirst())) {
                    if (groups.putIfAbsent(next, group) == null) queue.addLast(next);
                }
            }
        }
        for (var car : cars) result.get(groups.get(car.entityId())).add(car);
        return result;
    }
}
