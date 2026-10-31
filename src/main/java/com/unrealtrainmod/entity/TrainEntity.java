/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.entity;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.TrainPoseClient;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.train.TrainAnchor;
import com.unrealtrainmod.train.TrainCarEnd;
import com.unrealtrainmod.train.TrainManager;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehiclePose;
import com.unrealtrainmod.vehicle.VehiclePosePlayback;
import com.unrealtrainmod.vehicle.VehiclePoseSnapshot;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.UUID;
import java.util.Locale;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Saved interaction/render proxy. Authoritative motion belongs to the level's TrainManager. */
public final class TrainEntity extends Entity {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrainEntity.class);
    private static final EntityDataAccessor<String> MODEL_ID =
            SynchedEntityData.defineId(TrainEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<CompoundTag> RAIL_POSE =
            SynchedEntityData.defineId(TrainEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private TrainAnchor railAnchor;
    private UUID trainId;
    private Tag invalidTrainId;
    private boolean invalidAnchor;
    private boolean frontCouplingEnabled, rearCouplingEnabled;
    private int lerpSteps;
    private double targetX, targetY, targetZ;
    private float targetYaw, targetPitch;
    private VehiclePose railPose, motionStartPose;
    private long motionStartTick = Long.MIN_VALUE, poseRevision;
    private VehiclePosePlayback playback;

    public TrainEntity(EntityType<? extends TrainEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MODEL_ID, "");
        builder.define(RAIL_POSE, new CompoundTag());
    }

    public ResourceLocation getModelId() {
        String value = entityData.get(MODEL_ID);
        return value.isEmpty() ? null : ResourceLocation.tryParse(value);
    }

    public void setModelId(ResourceLocation id) {
        entityData.set(MODEL_ID, id.toString());
    }

    public TrainAnchor getRailAnchor() { return railAnchor; }
    public UUID getTrainId() { return trainId; }
    public boolean hasInvalidTrainId() { return invalidTrainId != null; }

    public void setTrainId(UUID id) {
        trainId = id;
        invalidTrainId = null;
    }
    public VehiclePose getRailPose() { return railPose; }
    public boolean hasInvalidAnchor() { return invalidAnchor; }
    public boolean isCouplingEnabled(TrainCarEnd end) {
        return end == TrainCarEnd.FRONT ? frontCouplingEnabled : rearCouplingEnabled;
    }
    public void setCouplingEnabled(TrainCarEnd end, boolean enabled) {
        if (end == TrainCarEnd.FRONT) frontCouplingEnabled = enabled;
        else rearCouplingEnabled = enabled;
    }

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

    /** Publishes one complete authoritative sample, never independently timed body/bogie updates. */
    public void setRailPose(VehiclePose pose) {
        applyPose(pose);
        resetRailPoseHistory();
        syncRailPose(false);
    }

    /** Apply a committed pose; the consist manager publishes all members on the same server tick. */
    public void advanceRailPose(VehiclePose pose) {
        applyPose(pose);
    }

    /** Capture once before the manager accepts any substep of a complete simulation tick. */
    public void beginRailMotionTick() {
        motionStartPose = railPose;
        motionStartTick = level().getGameTime();
    }

    public void resetRailPoseHistory() {
        poseRevision++;
        motionStartPose = null;
        motionStartTick = Long.MIN_VALUE;
    }

    public void syncRailPose(boolean moving) {
        if (railPose == null) return;
        boolean predict = moving && motionStartPose != null && motionStartTick == level().getGameTime();
        var motion = predict ? VehiclePoseSnapshot.Motion.between(motionStartPose.body(), railPose.body())
                : VehiclePoseSnapshot.Motion.ZERO;
        entityData.set(RAIL_POSE, new VehiclePoseSnapshot(level().getGameTime(), poseRevision, railPose, motion, predict).save());
    }

    private void applyPose(VehiclePose pose) {
        railPose = pose;
        var body = pose.body();
        var p = body.position();
        setRailPose(p.x(), p.y(), p.z(), body.yaw(), body.pitch());
        // ClientLevel snapshots each rider's old position after ticking the vehicle. Moving a
        // client rider here would erase its interpolation interval before rideTick positions it.
        if (!level().isClientSide) {
            for (var passenger : getPassengers()) positionRider(passenger);
        }
    }

    public VehiclePose renderPose(float partialTick) {
        return playback == null ? railPose : playback.sample(TrainPoseClient.time(level(), partialTick));
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel server) {
            // Vanilla player NBT can restore a riding relationship, but never a driver session.
            for (var passenger : List.copyOf(getPassengers())) {
                if (passenger instanceof ServerPlayer player && !TrainManager.get(server).drivers().isDriver(player, this)) {
                    player.stopRiding();
                }
            }
        }
        if (level().isClientSide && playback != null) {
            // The shared clock advances in ClientTickEvent.Post, after this entity tick.
            applyPose(playback.sample(TrainPoseClient.time(level(), 1)));
        } else if (level().isClientSide && lerpSteps > 0) {
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
        if (level().isClientSide && railPose != null) return;
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

    @Override public double lerpTargetX() {
        return lerpSteps <= 0 ? getX() : targetX;
    }
    @Override public double lerpTargetY() {
        return lerpSteps <= 0 ? getY() : targetY;
    }
    @Override public double lerpTargetZ() {
        return lerpSteps <= 0 ? getZ() : targetZ;
    }
    @Override public float lerpTargetYRot() {
        return lerpSteps <= 0 ? getYRot() : targetYaw;
    }
    @Override public float lerpTargetXRot() {
        return lerpSteps <= 0 ? getXRot() : targetPitch;
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        playback = null;
        railPose = null;
        lerpSteps = 0;
        setOldPosAndRot();
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (MODEL_ID.equals(key)) setBoundingBox(makeBoundingBox());
        if (RAIL_POSE.equals(key) && level().isClientSide) {
            var snapshot = entityData.get(RAIL_POSE);
            if (snapshot.isEmpty()) {
                TrainPoseClient.discard(this);
                railPose = null;
                playback = null;
                lerpSteps = 0;
                setBoundingBox(makeBoundingBox());
            } else {
                TrainPoseClient.enqueue(this, VehiclePoseSnapshot.load(snapshot));
            }
        }
    }

    /** Applied once this frame's simulation ticks have finished, before camera and entity rendering. */
    public void acceptRailPoseSnapshot(VehiclePoseSnapshot incoming, double time) {
        if (playback == null) playback = new VehiclePosePlayback();
        if (!playback.accepts(incoming)) return;
        boolean reset = playback.accept(incoming, time);
        applyPose(playback.sample(time));
        if (reset) setOldPosAndRot();
        lerpSteps = 0;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        var id = ResourceLocation.tryParse(tag.getString("ModelId"));
        entityData.set(MODEL_ID, id == null ? "" : id.toString());
        railPose = motionStartPose = null;
        motionStartTick = Long.MIN_VALUE;
        poseRevision++;
        playback = null;
        lerpSteps = 0;
        entityData.set(RAIL_POSE, new CompoundTag());
        railAnchor = null;
        invalidAnchor = false;
        trainId = null;
        invalidTrainId = null;
        frontCouplingEnabled = tag.contains("FrontCouplingEnabled", Tag.TAG_BYTE)
                ? tag.getBoolean("FrontCouplingEnabled") : tag.getBoolean("CouplingEnabled");
        rearCouplingEnabled = tag.contains("RearCouplingEnabled", Tag.TAG_BYTE)
                ? tag.getBoolean("RearCouplingEnabled") : tag.getBoolean("CouplingEnabled");
        if (tag.contains("TrainId")) {
            if (tag.hasUUID("TrainId")) trainId = tag.getUUID("TrainId");
            else {
                invalidTrainId = tag.get("TrainId").copy();
                LOGGER.warn("Invalid train ID for vehicle {}", getUUID());
            }
        }
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
        if (tag.contains("BogiePose")) {
            try {
                if (!tag.contains("BogiePose", Tag.TAG_COMPOUND)) {
                    throw new IllegalArgumentException("BogiePose must be a compound");
                }
                railPose = VehiclePose.loadBogies(tag.getCompound("BogiePose"), currentBody());
                syncRailPose(false);
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Invalid bogie pose for vehicle {}: {}", getUUID(), exception.getMessage());
            }
        }
        setNoGravity(true);
        setBoundingBox(makeBoundingBox());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("ModelId", entityData.get(MODEL_ID));
        tag.putBoolean("FrontCouplingEnabled", frontCouplingEnabled);
        tag.putBoolean("RearCouplingEnabled", rearCouplingEnabled);
        if (trainId != null) tag.putUUID("TrainId", trainId);
        else if (invalidTrainId != null) tag.put("TrainId", invalidTrainId.copy());
        if (railAnchor != null) tag.put("RailAnchor", railAnchor.save());
        if (railPose != null) tag.put("BogiePose", railPose.saveBogies());
    }

    /** A dimension transfer copies NBT before removing the source entity. Do not copy its rail binding. */
    @Override
    public void restoreFrom(Entity source) {
        super.restoreFrom(source);
        if (source.level().dimension() != level().dimension()) {
            setTrainId(null);
            setRailAnchor(null);
            railPose = motionStartPose = null;
            motionStartTick = Long.MIN_VALUE;
            playback = null;
            lerpSteps = 0;
            entityData.set(RAIL_POSE, new CompoundTag());
            setBoundingBox(makeBoundingBox());
        }
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

    private VehiclePose.Body currentBody() {
        return new VehiclePose.Body(new TrackVector(getX(), getY(), getZ()), getYRot(), getXRot());
    }

    public AABB boundsAt(VehiclePose pose) {
        var model = VehicleCatalog.find(getModelId());
        return model == null ? VehiclePose.staticBounds(ModelBounds.MISSING, pose.body()) : pose.bounds(model);
    }

    /** The same authored-model bounds used by selection and by server motion-area checks. */
    public AABB boundsAt(double worldX, double worldY, double worldZ, float yawDegrees, float pitchDegrees) {
        var model = VehicleCatalog.find(getModelId());
        var body = new VehiclePose.Body(new TrackVector(worldX, worldY, worldZ), yawDegrees, pitchDegrees);
        if (model != null && railPose != null) {
            return new VehiclePose(body, railPose.frontBogie(), railPose.rearBogie()).bounds(model);
        }
        var bounds = model == null ? ModelBounds.MISSING : model.bounds();
        return VehiclePose.staticBounds(bounds, body).minmax(new AABB(worldX, worldY, worldZ, worldX, worldY, worldZ));
    }

    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < 256 * 256; }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            var stack = player.getItemInHand(hand);
            if (stack.is(UnrealTrainMod.CROWBAR.get())) return UnrealTrainMod.CROWBAR.get().useOnTrain(player, this);
            if (stack.is(UnrealTrainMod.VEHICLE_MOVER.get())) return UnrealTrainMod.VEHICLE_MOVER.get().useOnTrain(player, this);
        }
        if (player.isShiftKeyDown() && player.getItemInHand(hand).is(UnrealTrainMod.VEHICLE.get()) && player.mayBuild()) {
            return dismantle(player) ? InteractionResult.sidedSuccess(level().isClientSide) : InteractionResult.FAIL;
        }
        if (!player.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND && !player.isSpectator()) {
            if (level().isClientSide) return InteractionResult.SUCCESS;
            return TrainManager.get((ServerLevel) level()).drivers().board((ServerPlayer) player, this)
                    ? InteractionResult.CONSUME : InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return passenger instanceof Player && getPassengers().isEmpty();
    }

    /** Passenger ownership never gives the client authority to send vehicle position updates. */
    @Override public boolean isControlledByLocalInstance() { return !level().isClientSide; }

    @Override
    public Vec3 getPassengerRidingPosition(Entity passenger) {
        return passengerRidingPosition(currentBody());
    }

    /** Body-only view of the interpolation shared with the train renderer. */
    public VehiclePose.Body renderBody(float partialTick) {
        var pose = renderPose(partialTick);
        return pose == null ? null : pose.body();
    }

    public Vec3 passengerRidingPosition(VehiclePose.Body body) {
        var model = VehicleCatalog.find(getModelId());
        var bounds = model == null ? ModelBounds.MISSING : model.bounds();
        var local = new Vector3f(bounds.centerX(), bounds.minY() + bounds.height() * 0.25f,
                bounds.maxZ() - Math.min(2, bounds.length() * 0.2f));
        body.rotation().transform(local);
        var position = body.position();
        return new Vec3(position.x() + local.x, position.y() + local.y, position.z() + local.z);
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
        var seat = getPassengerRidingPosition(passenger);
        callback.accept(passenger, seat.x, seat.y, seat.z);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (level() instanceof ServerLevel server && passenger instanceof ServerPlayer player) {
            TrainManager.get(server).drivers().dismounted(player, this);
        }
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        var model = VehicleCatalog.find(getModelId());
        double width = model == null ? 1 : model.bounds().width();
        var seat = getPassengerRidingPosition(passenger);
        var side = VehiclePose.Body.rotation(getYRot(), 0).transform(new Vector3f((float)(width / 2 + 1), 0, 0));
        for (int sign : new int[] {1, -1}) {
            var target = seat.add(sign * side.x, 0, sign * side.z);
            var area = passenger.getBoundingBox().move(target.subtract(passenger.position()));
            if (level().hasChunkAt(BlockPos.containing(target)) && level().noCollision(passenger, area)) return target;
        }
        return super.getDismountLocationForPassenger(passenger);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof Player player && player.mayBuild()) {
            return dismantle(player);
        }
        return false;
    }

    private boolean dismantle(Player player) {
        if (level().isClientSide) return true;
        if (isRemoved()) return false;
        try {
            var failure = TrainManager.get((ServerLevel) level()).dismantleFailure(this);
            if (failure != null) {
                player.displayClientMessage(Component.translatable("commands.unrealtrainmod.train.state."
                        + failure.name().toLowerCase(Locale.ROOT)), true);
                return false;
            }
        } catch (TrainManager.StateException exception) {
            player.displayClientMessage(Component.translatable("commands.unrealtrainmod.train.state."
                    + exception.reason().name().toLowerCase(Locale.ROOT)), true);
            return false;
        }
        if (!player.getAbilities().instabuild) {
            var stack = new ItemStack(UnrealTrainMod.VEHICLE.get());
            var modelId = getModelId();
            if (modelId != null) stack.set(ModDataComponents.SELECTED_MODEL.get(), modelId);
            spawnAtLocation(stack);
        }
        discard();
        return true;
    }
}
