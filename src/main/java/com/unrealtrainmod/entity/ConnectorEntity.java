/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.entity;

import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WiringManager;
import com.unrealtrainmod.wiring.WiringSavedData;
import com.unrealtrainmod.wiring.WiringSync;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Saved interaction projection of a connector in the dimension wiring graph. */
public final class ConnectorEntity extends Entity {
    private static final EntityDataAccessor<CompoundTag> STATE = SynchedEntityData.defineId(ConnectorEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private ConnectorData node;
    private boolean restored;
    public ConnectorEntity(EntityType<? extends ConnectorEntity> type, Level level) {
        super(type, level); noPhysics = true; setNoGravity(true);
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(STATE, new CompoundTag()); }
    public ConnectorData node() { return node; }
    public void apply(ConnectorData value) {
        node = value; var pos = value.origin(); setPos(pos.x, pos.y, pos.z);
        entityData.set(STATE, value.save()); setBoundingBox(value.bounds());
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (STATE.equals(key) && !entityData.get(STATE).isEmpty()) {
            node = ConnectorData.load(entityData.get(STATE)); setBoundingBox(node.bounds());
        }
    }
    @Override public void tick() {
        super.tick();
        if (!level().isClientSide && !restored) {
            restored = true;
            var authoritative = WiringSavedData.get((ServerLevel)level()).connector(getUUID());
            if (authoritative == null) discard(); else apply(authoritative);
        }
    }
    @Override protected AABB makeBoundingBox() { return node == null ? super.makeBoundingBox() : node.bounds(); }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Connector", 10)) apply(ConnectorData.load(tag.getCompound("Connector")));
        setNoGravity(true);
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) { if (node != null) tag.put("Connector", node.save()); }
    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < 256 * 256; }
    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server) return WiringManager.interact(this, server, hand);
        var stack = player.getItemInHand(hand);
        boolean action = stack.is(com.unrealtrainmod.UnrealTrainMod.TRACK_WRENCH.get())
                || stack.is(com.unrealtrainmod.UnrealTrainMod.WIRE.get())
                || player.isShiftKeyDown() && (stack.isEmpty() || stack.is(com.unrealtrainmod.UnrealTrainMod.INSULATOR.get()));
        return hand == InteractionHand.MAIN_HAND && player.mayBuild() && action ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
    @Override public void remove(RemovalReason reason) {
        super.remove(reason);
        if (reason.shouldDestroy() && level() instanceof ServerLevel server) {
            var graph = WiringSavedData.get(server);
            if (graph.connector(getUUID()) != null) { graph.removeConnector(getUUID()); WiringSync.changed(server); }
        }
    }
}
