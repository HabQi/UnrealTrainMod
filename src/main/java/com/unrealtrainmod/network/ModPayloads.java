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
import com.unrealtrainmod.network.payload.TrainDebugPayload;
import com.unrealtrainmod.network.payload.TurnoutConstructionPayload;
import com.unrealtrainmod.network.payload.TrainMapSessionPayload;
import com.unrealtrainmod.network.payload.TrainMapUpdatePayload;
import com.unrealtrainmod.train.map.TrainMapService;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ModPayloads {
    private ModPayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("14");
        registrar.playBidirectional(TrainMapSessionPayload.TYPE, TrainMapSessionPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.flow() == PacketFlow.CLIENTBOUND) NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload));
                    else TrainMapService.handleSession(payload, context);
                });
        registrar.playToClient(TrainMapUpdatePayload.TYPE, TrainMapUpdatePayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToClient(TrainDebugPayload.TYPE, TrainDebugPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
        registrar.playToClient(TurnoutConstructionPayload.TYPE, TurnoutConstructionPayload.STREAM_CODEC,
                (payload, context) -> NeoForge.EVENT_BUS.post(new TrackSyncEvent(payload)));
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
