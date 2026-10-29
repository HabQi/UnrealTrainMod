/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.entity;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** A saved rail vehicle with a fixed placement pose; driving and animation are separate future work. */
public final class TrainEntity extends Entity {
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(TrainEntity.class, EntityDataSerializers.STRING);

    public TrainEntity(EntityType<? extends TrainEntity> type, Level level) {
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
        var id = ResourceLocation.tryParse(tag.getString("ModelId"));
        entityData.set(MODEL_ID, id == null ? "" : id.toString());
        setNoGravity(true);
        setBoundingBox(makeBoundingBox());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("ModelId", entityData.get(MODEL_ID));
    }

    @Override
    public void setYRot(float yaw) {
        super.setYRot(yaw);
        setBoundingBox(makeBoundingBox());
    }

    @Override
    public void setXRot(float pitch) {
        super.setXRot(pitch);
        setBoundingBox(makeBoundingBox());
    }

    @Override
    protected AABB makeBoundingBox() {
        var model = VehicleCatalog.find(getModelId());
        var bounds = model == null ? ModelBounds.MISSING : model.bounds();
        double yaw = Math.toRadians(getYRot()), pitch = Math.toRadians(getXRot());
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? bounds.minX() : bounds.maxX();
            double y = (corner & 2) == 0 ? bounds.minY() : bounds.maxY();
            double z = (corner & 4) == 0 ? bounds.minZ() : bounds.maxZ();
            // Match the renderer's Y(-yaw) * X(pitch) rotation, including the model's authored origin.
            double tiltedY = cp * y - sp * z, tiltedZ = sp * y + cp * z;
            double rotatedX = cy * x - sy * tiltedZ, rotatedZ = sy * x + cy * tiltedZ;
            minX = Math.min(minX, rotatedX); maxX = Math.max(maxX, rotatedX);
            minY = Math.min(minY, tiltedY); maxY = Math.max(maxY, tiltedY);
            minZ = Math.min(minZ, rotatedZ); maxZ = Math.max(maxZ, rotatedZ);
        }
        return new AABB(getX() + minX, getY() + minY, getZ() + minZ,
                getX() + maxX, getY() + maxY, getZ() + maxZ);
    }

    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < 256 * 256; }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isShiftKeyDown() && player.getItemInHand(hand).is(UnrealTrainMod.VEHICLE.get()) && player.mayBuild()) {
            dismantle(player);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof Player player && player.mayBuild()) {
            dismantle(player);
            return true;
        }
        return false;
    }

    private void dismantle(Player player) {
        if (level().isClientSide || isRemoved()) return;
        if (!player.getAbilities().instabuild) {
            var stack = new ItemStack(UnrealTrainMod.VEHICLE.get());
            var modelId = getModelId();
            if (modelId != null) stack.set(ModDataComponents.SELECTED_MODEL.get(), modelId);
            spawnAtLocation(stack);
        }
        discard();
    }
}
