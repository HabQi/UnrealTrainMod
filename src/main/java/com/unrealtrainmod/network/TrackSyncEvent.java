/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.Event;

/** Dispatches clientbound track data without loading client classes on a dedicated server. */
public final class TrackSyncEvent extends Event {
    private final CustomPacketPayload payload;

    public TrackSyncEvent(CustomPacketPayload payload) { this.payload = payload; }
    public CustomPacketPayload payload() { return payload; }
}
