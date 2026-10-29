/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.entity;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.train.TrainAnchor;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Saved interaction/render proxy. Authoritative motion belongs to the level's TrainManager. */
public final class TrainEntity extends Entity {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrainEntity.class);
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(TrainEntity.class, EntityDataSerializers.STRING);
    private TrainAnchor railAnchor;
    private boolean invalidAnchor;
    private int lerpSteps;
    private double targetX, targetY, targetZ;
    private float targetYaw, targetPitch;

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

    public TrainAnchor getRailAnchor() { return railAnchor; }
    public boolean hasInvalidAnchor() { return invalidAnchor; }

    public void setRailAnchor(TrainAnchor anchor) {
        railAnchor = anchor;
        invalidAnchor = false;
    }

    /** Updates a running proxy without resetting interpolation history or recomputing bounds per angle. */
    public void setRailPose(double x, double y, double z, float yaw, float pitch) {
        super.setYRot(yaw);
        super.setXRot(pitch);
        setPos(x, y, z);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide && lerpSteps > 0) {
            double fraction = 1.0 / lerpSteps;
            setRailPose(Mth.lerp(fraction, getX(), targetX), Mth.lerp(fraction, getY(), targetY),
                    Mth.lerp(fraction, getZ(), targetZ),
                    (float)Mth.rotLerp(fraction, (double)getYRot(), targetYaw),
                    (float)Mth.lerp(fraction, (double)getXRot(), targetPitch));
            lerpSteps--;
        }
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        if (steps <= 0 || !level().isClientSide) {
            setRailPose(x, y, z, yaw, pitch);
            setOldPosAndRot();
            lerpSteps = 0;
            return;
        }
        targetX = x;
        targetY = y;
        targetZ = z;
        targetYaw = yaw;
        targetPitch = pitch;
        lerpSteps = steps;
    }

    @Override public double lerpTargetX() { return lerpSteps > 0 ? targetX : getX(); }
    @Override public double lerpTargetY() { return lerpSteps > 0 ? targetY : getY(); }
    @Override public double lerpTargetZ() { return lerpSteps > 0 ? targetZ : getZ(); }
    @Override public float lerpTargetYRot() { return lerpSteps > 0 ? targetYaw : getYRot(); }
    @Override public float lerpTargetXRot() { return lerpSteps > 0 ? targetPitch : getXRot(); }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        lerpSteps = 0;
        setOldPosAndRot();
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
        railAnchor = null;
        invalidAnchor = false;
        if (tag.contains("RailAnchor")) {
            try {
                if (!tag.contains("RailAnchor", Tag.TAG_COMPOUND)) {
                    throw new IllegalArgumentException("RailAnchor must be a compound");
                }
                railAnchor = TrainAnchor.load(tag.getCompound("RailAnchor"));
            } catch (IllegalArgumentException exception) {
                invalidAnchor = true;
                LOGGER.warn("Invalid rail anchor for vehicle {}: {}", getUUID(), exception.getMessage());
            }
        }
        setNoGravity(true);
        setBoundingBox(makeBoundingBox());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("ModelId", entityData.get(MODEL_ID));
        if (railAnchor != null) tag.put("RailAnchor", railAnchor.save());
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
        return boundsAt(getX(), getY(), getZ(), getYRot(), getXRot());
    }

    /** The same authored-model bounds used by selection and by server motion-area checks. */
    public AABB boundsAt(double worldX, double worldY, double worldZ, float yawDegrees, float pitchDegrees) {
        var model = VehicleCatalog.find(getModelId());
        var bounds = model == null ? ModelBounds.MISSING : model.bounds();
        double yaw = Math.toRadians(yawDegrees), pitch = Math.toRadians(pitchDegrees);
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
        return new AABB(worldX + minX, worldY + minY, worldZ + minZ,
                worldX + maxX, worldY + maxY, worldZ + maxZ);
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
