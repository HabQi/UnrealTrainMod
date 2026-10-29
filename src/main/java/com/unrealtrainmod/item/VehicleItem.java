/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackProjection;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.train.TrainAnchor;
import com.unrealtrainmod.train.TrainManager;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

/** A consumable vehicle item; the server resolves placement from the clicked track's owners. */
public final class VehicleItem extends Item {
    public VehicleItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        var level = context.getLevel();
        var stack = context.getItemInHand();
        var clicked = context.getClickedPos();
        if (player == null || !player.mayBuild() || context.getClickedFace() != Direction.UP) {
            return InteractionResult.FAIL;
        }
        if (!level.getBlockState(clicked).is(UnrealTrainMod.TRACK_BLOCK.get())) {
            return fail(level, player, "item.unrealtrainmod.vehicle.no_track");
        }
        var modelId = stack.get(ModDataComponents.SELECTED_MODEL.get());
        var model = VehicleCatalog.find(modelId);
        if (model == null) {
            return fail(level, player, modelId == null
                    ? "item.unrealtrainmod.vehicle.unselected" : "item.unrealtrainmod.vehicle_placer.missing");
        }
        if (!player.mayUseItemAt(clicked, Direction.UP, stack) || !level.mayInteract(player, clicked)) {
            return InteractionResult.FAIL;
        }
        // Clients do not access the authoritative track graph or create a predicted entity.
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.SUCCESS;

        var hit = context.getClickLocation();
        var point = new TrackVector(hit.x, hit.y, hit.z);
        TrackPose closest = null;
        TrackSegment closestSegment = null;
        double closestDistance = 0;
        double bestError = Double.POSITIVE_INFINITY;
        for (var segment : TrackBlockManager.get(serverLevel).segmentsAt(clicked)) {
            var geometry = segment.geometry();
            double distance = TrackProjection.closestDistance(geometry, point);
            var pose = geometry.sample(distance);
            double error = TrackProjection.squaredDistance(pose.position(), point);
            if (error < bestError) {
                closest = pose;
                closestSegment = segment;
                closestDistance = distance;
                bestError = error;
            }
        }
        if (closest == null) return fail(level, player, "item.unrealtrainmod.vehicle.no_track");

        var tangent = closest.tangent();
        double playerYaw = Math.toRadians(player.getYRot());
        double direction = -Math.sin(playerYaw) * tangent.x() + Math.cos(playerYaw) * tangent.z() < 0 ? -1 : 1;
        var entity = UnrealTrainMod.TRAIN.get().create(level);
        if (entity == null) return InteractionResult.FAIL;
        entity.setModelId(modelId);
        try {
            var failure = TrainManager.get(serverLevel).place(entity,
                    new TrainAnchor(closestSegment.id(), closestDistance, direction > 0),
                    target -> player.mayUseItemAt(target, Direction.UP, stack) && level.mayInteract(player, target));
            if (failure != null) return fail(level, player,
                    "commands.unrealtrainmod.train.state." + failure.name().toLowerCase(Locale.ROOT));
        } catch (TrainManager.StateException exception) {
            return fail(level, player,
                    "commands.unrealtrainmod.train.state." + exception.reason().name().toLowerCase(Locale.ROOT));
        }
        if (!player.getAbilities().instabuild) stack.shrink(1);
        level.playSound(null, entity.blockPosition(), SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.8f, 1);
        level.gameEvent(player, GameEvent.ENTITY_PLACE, entity.position());
        return InteractionResult.CONSUME;
    }

    private static InteractionResult fail(Level level, Player player, String key) {
        if (!level.isClientSide) player.displayClientMessage(Component.translatable(key), true);
        return InteractionResult.FAIL;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        var modelId = stack.get(ModDataComponents.SELECTED_MODEL.get());
        var model = VehicleCatalog.find(modelId);
        lines.add(Component.translatable("item.unrealtrainmod.vehicle_placer.selected",
                modelId == null ? Component.translatable("item.unrealtrainmod.vehicle_placer.none")
                        : Component.literal(model == null ? modelId.toString() : model.name())).withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("item.unrealtrainmod.vehicle_placer.select").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.unrealtrainmod.vehicle.place").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.unrealtrainmod.vehicle.remove").withStyle(ChatFormatting.GRAY));
    }
}
