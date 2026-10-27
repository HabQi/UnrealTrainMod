/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.entity;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.TrainModels;
import com.unrealtrainmod.model.ModelBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** A stationary, saved display entity. Physics and driving are intentionally not implemented. */
public final class PlacedModelEntity extends Entity {
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(PlacedModelEntity.class, EntityDataSerializers.STRING);

    public PlacedModelEntity(EntityType<? extends PlacedModelEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MODEL_ID, "");
    }

    public ResourceLocation getModelId() {
        String value = entityData.get(MODEL_ID);
        return value.isEmpty() ? null : ResourceLocation.tryParse(value);
    }

    public void setModelId(ResourceLocation id) {
        entityData.set(MODEL_ID, id.toString());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (MODEL_ID.equals(key)) setBoundingBox(makeBoundingBox());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        String value = tag.getString("ModelId");
        var id = value.isEmpty() ? null : ResourceLocation.tryParse(value);
        // Keep valid IDs even when their package is temporarily absent.
        entityData.set(MODEL_ID, id == null ? "" : id.toString());
        setNoGravity(true);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("ModelId", entityData.get(MODEL_ID));
    }

    @Override
    public void setYRot(float yaw) {
        super.setYRot(yaw);
        // Spawn packets and later updates set yaw after position; refresh the elongated selection box too.
        setBoundingBox(makeBoundingBox());
    }

    @Override
    protected AABB makeBoundingBox() {
        var model = TrainModels.find(getModelId());
        var bounds = model == null ? ModelBounds.MISSING : model.bounds();
        double yaw = Math.toRadians(getYRot());
        double cosine = Math.cos(yaw), sine = Math.sin(yaw);
        double minX = Double.POSITIVE_INFINITY, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (int corner = 0; corner < 4; corner++) {
            double x = (corner & 1) == 0 ? bounds.minX() : bounds.maxX();
            double z = (corner & 2) == 0 ? bounds.minZ() : bounds.maxZ();
            double rotatedX = cosine * x - sine * z, rotatedZ = sine * x + cosine * z;
            minX = Math.min(minX, rotatedX); maxX = Math.max(maxX, rotatedX);
            minZ = Math.min(minZ, rotatedZ); maxZ = Math.max(maxZ, rotatedZ);
        }
        return new AABB(getX() + minX, getY() + bounds.minY(), getZ() + minZ,
                getX() + maxX, getY() + bounds.maxY(), getZ() + maxZ);
    }

    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < 256 * 256; }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isShiftKeyDown() && player.getItemInHand(hand).is(UnrealTrainMod.VEHICLE_PLACER.get())
                && player.mayBuild()) {
            if (!level().isClientSide) discard();
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof Player player && player.mayBuild()) {
            if (!level().isClientSide) discard();
            return true;
        }
        return false;
    }
}
