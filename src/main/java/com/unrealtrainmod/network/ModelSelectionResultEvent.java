/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network;

import com.unrealtrainmod.network.payload.ModelSelectionResultPayload;
import net.neoforged.bus.api.Event;

/** Clientbound result dispatch without loading any client classes from common registration. */
public final class ModelSelectionResultEvent extends Event {
    private final ModelSelectionResultPayload payload;

    public ModelSelectionResultEvent(ModelSelectionResultPayload payload) { this.payload = payload; }
    public ModelSelectionResultPayload payload() { return payload; }
}
