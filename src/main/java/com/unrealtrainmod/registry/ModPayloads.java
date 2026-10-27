/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.registry;

import com.unrealtrainmod.network.ModelSelectionResultEvent;
import com.unrealtrainmod.network.handler.ServerPayloadHandlers;
import com.unrealtrainmod.network.payload.ModelSelectionResultPayload;
import com.unrealtrainmod.network.payload.SelectTrainModelPayload;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ModPayloads {
    private ModPayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(SelectTrainModelPayload.TYPE, SelectTrainModelPayload.STREAM_CODEC,
                ServerPayloadHandlers::selectTrainModel);
        registrar.playToClient(ModelSelectionResultPayload.TYPE, ModelSelectionResultPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new ModelSelectionResultEvent(payload)));
    }
}
