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
import com.unrealtrainmod.train.TrainDriverManager;
import com.unrealtrainmod.train.TrainManager;
import com.unrealtrainmod.train.TrainPassengerClientState;
import com.unrealtrainmod.train.TrainPassengerManager;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.vehicle.VehicleDoor;
import com.unrealtrainmod.vehicle.VehiclePose;
import com.unrealtrainmod.vehicle.VehiclePosePlayback;
import com.unrealtrainmod.vehicle.VehiclePoseSnapshot;
import com.unrealtrainmod.vehicle.VehicleRuntimeState;
import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import com.unrealtrainmod.compat.ir.IrVehicleSnapshot;
import com.unrealtrainmod.compat.ir.IrVehicleState;
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
    private static final EntityDataAccessor<CompoundTag> DOOR_STATE =
            SynchedEntityData.defineId(TrainEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> RUNTIME_STATE =
            SynchedEntityData.defineId(TrainEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Byte> LIGHTS =
            SynchedEntityData.defineId(TrainEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<CompoundTag> IR_STATE =
            SynchedEntityData.defineId(TrainEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Legacy RTM advances a door by one unit per tick for {@code MAX_DOOR_MOVE = 60} ticks. */
    private static final int DOOR_MOVE_TICKS = 60;
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
    /** Server-side transition bookkeeping shared by the synced state and late observers. */
    private final DoorState leftDoor = new DoorState(), rightDoor = new DoorState();
    private boolean doorStateLoaded;
    private VehicleRuntimeState runtimeState = VehicleRuntimeState.IDLE;
    private final IrVehicleState irState = new IrVehicleState();
    private IrVehicleSnapshot irSnapshot = IrVehicleSnapshot.EMPTY;

    public TrainEntity(EntityType<? extends TrainEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MODEL_ID, "");
        builder.define(RAIL_POSE, new CompoundTag());
        builder.define(DOOR_STATE, new CompoundTag());
        builder.define(RUNTIME_STATE, new CompoundTag());
        builder.define(LIGHTS, (byte) 0);
        builder.define(IR_STATE, new CompoundTag());
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
        if (id == null) setRuntimeState(VehicleRuntimeState.IDLE);
    }

    public VehicleRuntimeState runtimeState() { return runtimeState; }

    public IrVehicleSnapshot irSnapshot() { return irSnapshot; }

    /** Changes are published only when values, coupling ends or trigger edges actually change. */
    public void updateIrState(boolean front, boolean rear) {
        var definition = VehicleCatalog.find(getModelId());
        if (definition == null || definition.ir() == IrVehicleDefinition.NONE) return;
        irState.bind(definition.ir(), runtimeState, front, rear);
        if (irState.update(runtimeState, front, rear, level().getGameTime())) {
            irSnapshot = irState.snapshot();
            entityData.set(IR_STATE, irSnapshot.save());
        }
    }

    public boolean setIrControl(String group, float value, boolean pulse) {
        if (level().isClientSide) return false;
        updateIrState(irSnapshot.frontCoupled(), irSnapshot.rearCoupled());
        boolean changed = irState.set(group, value, pulse, level().getGameTime());
        updateIrState(irSnapshot.frontCoupled(), irSnapshot.rearCoupled());
        return changed;
    }

    /** Independent authored RTM front/tail texture layers; persisted and tracked for every observer. */
    public int lights() { return entityData.get(LIGHTS); }
    public boolean isHeadLightOn() { return (lights() & 1) != 0; }
    public boolean isTailLightOn() { return (lights() & 2) != 0; }

    public void setLight(boolean head, boolean enabled) {
        if (level().isClientSide) return;
        int bit = head ? 1 : 2;
        entityData.set(LIGHTS, (byte) (enabled ? lights() | bit : lights() & ~bit));
    }

    /** Server publication and lifecycle reset; equal values do not dirty tracking data. Never persisted in NBT. */
    public void setRuntimeState(VehicleRuntimeState state) {
        if (runtimeState.equals(state)) return;
        runtimeState = state;
        entityData.set(RUNTIME_STATE, state.save());
    }

    public VehiclePose getRailPose() { return railPose; }
    /** Monotonic pose reset marker used by render-time animations to ignore position jumps. */
    public long poseRevision() { return poseRevision; }
    public boolean hasInvalidAnchor() { return invalidAnchor; }
    public boolean isCouplingEnabled(TrainCarEnd end) {
        return end == TrainCarEnd.FRONT ? frontCouplingEnabled : rearCouplingEnabled;
    }
    public void setCouplingEnabled(TrainCarEnd end, boolean enabled) {
        if (end == TrainCarEnd.FRONT) frontCouplingEnabled = enabled;
        else rearCouplingEnabled = enabled;
    }

    /** Authoritative per-vehicle target; left and right use the vehicle's own forward direction. */
    public boolean isDoorOpen(VehicleDoor side) {
        // A client that has not received an update yet must read the default synced state, not a stale local flag.
        if (level().isClientSide) ensureDoorState();
        return door(side).open;
    }

    /** Server-only transition. Returns false when the requested target is already current. */
    public boolean setDoorOpen(VehicleDoor side, boolean open) {
        if (level().isClientSide) return false;
        var state = door(side);
        if (state.open == open) return false;
        long now = level().getGameTime();
        state.changeProgress = state.progressAt(now);
        state.open = open;
        state.changeTick = now;
        syncDoorState();
        return true;
    }

    /** Client render query, interpolated with the same partial tick as the vehicle pose. */
    public float doorMovement(VehicleDoor side, float partialTick) {
        if (!level().isClientSide) return isDoorOpen(side) ? 1.0f : 0.0f;
        ensureDoorState();
        // Sample the previous-to-current world tick interval even when this entity has stopped ticking.
        return door(side).progressAt(level().getGameTime() - 1.0 + partialTick);
    }

    private DoorState door(VehicleDoor side) { return side == VehicleDoor.LEFT ? leftDoor : rightDoor; }

    /** Client-side lazy read of the default state before the first synced update arrives. */
    private void ensureDoorState() {
        if (!doorStateLoaded) applyDoorState(entityData.get(DOOR_STATE));
    }

    /**
     * Adopts the synced transition. The server records the progress at the change tick, so a new observer
     * starts at the settled endpoint for parked vehicles and mid-transition when tracking begins during one.
     */
    private void applyDoorState(CompoundTag tag) {
        long now = level().getGameTime();
        leftDoor.load(tag, "Left", now);
        rightDoor.load(tag, "Right", now);
        doorStateLoaded = true;
    }

    private void syncDoorState() {
        var tag = new CompoundTag();
        leftDoor.save(tag, "Left");
        rightDoor.save(tag, "Right");
        entityData.set(DOOR_STATE, tag);
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
            // Vanilla player NBT can restore a riding relationship, but never a driver or passenger session.
            for (var passenger : List.copyOf(getPassengers())) {
                if (passenger instanceof ServerPlayer player
                        && !TrainManager.get(server).drivers().isDriver(player, this)
                        && !TrainManager.get(server).passengers().isPassenger(player, this)) {
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
        poseRevision++;
        doorStateLoaded = false;
        setRuntimeState(VehicleRuntimeState.IDLE);
        setOldPosAndRot();
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (MODEL_ID.equals(key)) {
            irState.clear(); irSnapshot = IrVehicleSnapshot.EMPTY;
            if (!level().isClientSide) entityData.set(IR_STATE, new CompoundTag());
            setBoundingBox(makeBoundingBox());
        }
        if (RAIL_POSE.equals(key) && level().isClientSide) {
            var snapshot = entityData.get(RAIL_POSE);
            if (snapshot.isEmpty()) {
                TrainPoseClient.discard(this);
                railPose = null;
                playback = null;
                lerpSteps = 0;
                // Clearing the pose also resets render-time wheel references.
                poseRevision++;
                setBoundingBox(makeBoundingBox());
            } else {
                TrainPoseClient.enqueue(this, VehiclePoseSnapshot.load(snapshot));
            }
        }
        if (DOOR_STATE.equals(key) && level().isClientSide) applyDoorState(entityData.get(DOOR_STATE));
        if (RUNTIME_STATE.equals(key) && level().isClientSide) {
            runtimeState = VehicleRuntimeState.load(entityData.get(RUNTIME_STATE));
        }
        if (IR_STATE.equals(key) && level().isClientSide) irSnapshot = IrVehicleSnapshot.load(entityData.get(IR_STATE));
    }

    /** Applied once this frame's simulation ticks have finished, before camera and entity rendering. */
    public void acceptRailPoseSnapshot(VehiclePoseSnapshot incoming, double time) {
        if (playback == null) playback = new VehiclePosePlayback();
        if (!playback.accepts(incoming)) return;
        boolean reset = playback.accept(incoming, time);
        if (poseRevision != incoming.revision()) {
            // Track the accepted authoritative reset generation so animation state drops position jumps.
            poseRevision = incoming.revision();
            reset = true;
        }
        applyPose(playback.sample(time));
        if (reset) setOldPosAndRot();
        lerpSteps = 0;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        var id = ResourceLocation.tryParse(tag.getString("ModelId"));
        entityData.set(MODEL_ID, id == null ? "" : id.toString());
        entityData.set(LIGHTS, (byte) (tag.getByte("Lights") & 3));
        try { irState.loadValues(tag.getCompound("IrControls")); }
        catch (IllegalArgumentException exception) {
            LOGGER.warn("Vehicle {} has invalid IR controls: {}", getUUID(), exception.getMessage());
            irState.clear();
        }
        irSnapshot = IrVehicleSnapshot.EMPTY;
        entityData.set(IR_STATE, new CompoundTag());
        railPose = motionStartPose = null;
        motionStartTick = Long.MIN_VALUE;
        poseRevision++;
        playback = null;
        lerpSteps = 0;
        entityData.set(RAIL_POSE, new CompoundTag());
        setRuntimeState(VehicleRuntimeState.IDLE);
        railAnchor = null;
        invalidAnchor = false;
        trainId = null;
        invalidTrainId = null;
        frontCouplingEnabled = tag.contains("FrontCouplingEnabled", Tag.TAG_BYTE)
                ? tag.getBoolean("FrontCouplingEnabled") : tag.getBoolean("CouplingEnabled");
        rearCouplingEnabled = tag.contains("RearCouplingEnabled", Tag.TAG_BYTE)
                ? tag.getBoolean("RearCouplingEnabled") : tag.getBoolean("CouplingEnabled");
        long doorNow = level().getGameTime();
        leftDoor.load(tag, "DoorLeft", doorNow);
        rightDoor.load(tag, "DoorRight", doorNow);
        doorStateLoaded = false;
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
        if (!level().isClientSide) syncDoorState();
        setNoGravity(true);
        setBoundingBox(makeBoundingBox());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("ModelId", entityData.get(MODEL_ID));
        tag.putByte("Lights", entityData.get(LIGHTS));
        tag.put("IrControls", irState.saveValues());
        tag.putBoolean("FrontCouplingEnabled", frontCouplingEnabled);
        tag.putBoolean("RearCouplingEnabled", rearCouplingEnabled);
        if (trainId != null) tag.putUUID("TrainId", trainId);
        else if (invalidTrainId != null) tag.put("TrainId", invalidTrainId.copy());
        if (railAnchor != null) tag.put("RailAnchor", railAnchor.save());
        if (railPose != null) tag.put("BogiePose", railPose.saveBogies());
        leftDoor.save(tag, "DoorLeft");
        rightDoor.save(tag, "DoorRight");
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
            var stack = player.getMainHandItem();
            boolean key = stack.is(UnrealTrainMod.KEY.get());
            boolean ticket = stack.is(UnrealTrainMod.TICKET.get());
            if (level().isClientSide) return key || ticket ? InteractionResult.SUCCESS : InteractionResult.PASS;
            if (key) return TrainManager.get((ServerLevel) level()).drivers().board((ServerPlayer) player, this)
                    ? InteractionResult.CONSUME : InteractionResult.FAIL;
            if (ticket) return TrainManager.get((ServerLevel) level()).passengers().board((ServerPlayer) player, this)
                    ? InteractionResult.CONSUME : InteractionResult.FAIL;
            return InteractionResult.PASS;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return passenger instanceof Player
                && getPassengers().size() < TrainDriverManager.MAX_DRIVERS + TrainPassengerManager.MAX_PASSENGERS;
    }

    /** Ordinary passengers and drivers stand on the car floor; riding only supplies the vehicle frame. */
    @Override
    public boolean shouldRiderSit() { return false; }

    /** Passenger ownership never gives the client authority to send vehicle position updates. */
    @Override public boolean isControlledByLocalInstance() { return !level().isClientSide; }

    @Override
    public Vec3 getPassengerRidingPosition(Entity passenger) {
        var walking = walkingLocal(passenger);
        if (walking != null) {
            return localToWorld(currentBody(), walking);
        }
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

    /** Camera anchor with the local point interpolated across the current client tick. */
    public Vec3 renderPassengerPosition(Player passenger, VehiclePose.Body body, float partialTick) {
        var walking = level().isClientSide
                ? TrainPassengerClientState.renderPoint(passenger.getUUID(), getUUID(), partialTick)
                : walkingLocal(passenger);
        if (walking != null) {
            return localToWorld(body, walking);
        }
        return passengerRidingPosition(body);
    }

    private static Vec3 localToWorld(VehiclePose.Body body, TrainPassengerClientState.LocalPoint local) {
        var point = VehiclePose.localToWorld(body, new TrackVector(local.x(), local.y(), local.z()));
        return new Vec3(point.x(), point.y(), point.z());
    }

    private TrainPassengerClientState.LocalPoint walkingLocal(Entity passenger) {
        if (!(passenger instanceof Player)) return null;
        if (level().isClientSide) return TrainPassengerClientState.renderPoint(passenger.getUUID(), getUUID());
        if (level() instanceof ServerLevel server) {
            return TrainManager.get(server).passengers().localPoint(passenger, this);
        }
        return null;
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
            TrainManager.get(server).passengers().dismounted(player, this);
            TrainManager.get(server).drivers().dismounted(player, this);
        }
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        var model = VehicleCatalog.find(getModelId());
        var bounds = model == null ? ModelBounds.MISSING : model.bounds();
        var body = currentBody();
        var seat = getPassengerRidingPosition(passenger);
        var local = VehiclePose.worldToLocal(body, new TrackVector(seat.x, seat.y, seat.z));
        int nearerSide = local.x() >= bounds.centerX() ? 1 : -1;
        for (int sign : new int[] {nearerSide, -nearerSide}) {
            double x = sign > 0 ? bounds.maxX() + 1 : bounds.minX() - 1;
            var point = VehiclePose.localToWorld(body, new TrackVector(x, local.y(), local.z()));
            var target = new Vec3(point.x(), point.y(), point.z());
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

    /**
     * Target plus the progress and tick when that target was last committed. Progress is a pure function of
     * game time, so the server does not need to tick every vehicle just to answer observers.
     */
    private static final class DoorState {
        private boolean open;
        private long changeTick;
        private float changeProgress;

        private float progressAt(double now) {
            double elapsed = Math.max(0.0, now - changeTick) / DOOR_MOVE_TICKS;
            double value = changeProgress + (open ? elapsed : -elapsed);
            return Mth.clamp((float)value, 0.0f, 1.0f);
        }

        private void save(CompoundTag tag, String prefix) {
            tag.putBoolean(prefix + "Open", open);
            tag.putLong(prefix + "Tick", changeTick);
            tag.putFloat(prefix + "Progress", changeProgress);
        }

        private void load(CompoundTag tag, String prefix, long now) {
            open = tag.getBoolean(prefix + "Open");
            changeTick = tag.contains(prefix + "Tick", Tag.TAG_LONG) ? tag.getLong(prefix + "Tick") : now;
            changeProgress = tag.contains(prefix + "Progress", Tag.TAG_FLOAT)
                    ? tag.getFloat(prefix + "Progress") : (open ? 1.0f : 0.0f);
            if (!Float.isFinite(changeProgress)) changeProgress = open ? 1.0f : 0.0f;
            changeProgress = Mth.clamp(changeProgress, 0.0f, 1.0f);
        }
    }
}
