/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network;

import com.unrealtrainmod.network.handler.ServerPayloadHandlers;
import com.unrealtrainmod.network.payload.ModelSelectionResultPayload;
import com.unrealtrainmod.network.payload.SelectTrainModelPayload;
import com.unrealtrainmod.network.payload.SelectRailModelPayload;
import com.unrealtrainmod.network.payload.RailModelSelectionResultPayload;
import com.unrealtrainmod.network.payload.TrackChunkPayload;
import com.unrealtrainmod.network.payload.TrackChunkUnloadPayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload;
import com.unrealtrainmod.network.payload.OpenTrackCurvePayload;
import com.unrealtrainmod.network.payload.SetTrackCurvePayload;
import com.unrealtrainmod.network.payload.TrackCurveResultPayload;
import com.unrealtrainmod.network.payload.TrackSegmentRemovePayload;
import com.unrealtrainmod.network.payload.TrackAssemblyPayload;
import com.unrealtrainmod.network.payload.TrackAssemblyStatePayload;
import com.unrealtrainmod.network.payload.TrackAssemblyRemovePayload;
import com.unrealtrainmod.network.payload.OpenTurnoutPayload;
import com.unrealtrainmod.network.payload.TurnoutActionPayload;
import com.unrealtrainmod.network.payload.TurnoutResultPayload;
import com.unrealtrainmod.track.construction.TurnoutConstructionManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ModPayloads {
    private ModPayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("9");
        registrar.playToClient(OpenTurnoutPayload.TYPE, OpenTurnoutPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToClient(TurnoutResultPayload.TYPE, TurnoutResultPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToServer(TurnoutActionPayload.TYPE, TurnoutActionPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) TurnoutConstructionManager.handle(player, payload);
        });
        registrar.playToClient(TrackAssemblyPayload.TYPE, TrackAssemblyPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToClient(TrackAssemblyStatePayload.TYPE, TrackAssemblyStatePayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToClient(TrackAssemblyRemovePayload.TYPE, TrackAssemblyRemovePayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToServer(SetTrackCurvePayload.TYPE, SetTrackCurvePayload.STREAM_CODEC,
                ServerPayloadHandlers::setTrackCurve);
        registrar.playToClient(OpenTrackCurvePayload.TYPE, OpenTrackCurvePayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToClient(TrackCurveResultPayload.TYPE, TrackCurveResultPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToServer(SelectRailModelPayload.TYPE, SelectRailModelPayload.STREAM_CODEC,
                ServerPayloadHandlers::selectRailModel);
        registrar.playToClient(RailModelSelectionResultPayload.TYPE, RailModelSelectionResultPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToServer(SelectTrainModelPayload.TYPE, SelectTrainModelPayload.STREAM_CODEC,
                ServerPayloadHandlers::selectTrainModel);
        registrar.playToClient(ModelSelectionResultPayload.TYPE, ModelSelectionResultPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new ModelSelectionResultEvent(payload)));
        registrar.playToClient(TrackChunkPayload.TYPE, TrackChunkPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToClient(TrackChunkUnloadPayload.TYPE, TrackChunkUnloadPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToClient(TrackSegmentRemovePayload.TYPE, TrackSegmentRemovePayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToClient(TrackConstructionPayload.TYPE, TrackConstructionPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
    }
}
