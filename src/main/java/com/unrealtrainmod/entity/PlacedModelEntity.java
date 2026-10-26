/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.entity;

import com.unrealtrainmod.UnrealTrainMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
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
    // YZ25G's visible bounds at scale 0.01; kept on both sides for selection and culling.
    public static final float WIDTH = 3.217f;
    public static final float HEIGHT = 4.388f;
    public static final float LENGTH = 26.855f;

    public PlacedModelEntity(EntityType<? extends PlacedModelEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
    @Override protected void readAdditionalSaveData(CompoundTag tag) { setNoGravity(true); }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public void setYRot(float yaw) {
        super.setYRot(yaw);
        // Spawn packets and later updates set yaw after position; refresh the elongated selection box too.
        setBoundingBox(makeBoundingBox());
    }

    @Override
    protected AABB makeBoundingBox() {
        double yaw = Math.toRadians(getYRot());
        double halfX = (Math.abs(Math.cos(yaw)) * WIDTH + Math.abs(Math.sin(yaw)) * LENGTH) / 2;
        double halfZ = (Math.abs(Math.sin(yaw)) * WIDTH + Math.abs(Math.cos(yaw)) * LENGTH) / 2;
        return new AABB(getX() - halfX, getY(), getZ() - halfZ,
                getX() + halfX, getY() + HEIGHT, getZ() + halfZ);
    }

    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < 256 * 256; }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isShiftKeyDown() && player.getItemInHand(hand).is(UnrealTrainMod.EXAMPLE_ITEM.get())
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
