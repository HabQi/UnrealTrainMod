/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.model.TrainModels;
import com.unrealtrainmod.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

public final class ModelPlacerItem extends Item {
    public ModelPlacerItem(Properties properties) { super(properties); }

    public static ResourceLocation selectedModel(ItemStack stack) {
        return stack.get(ModDataComponents.SELECTED_MODEL.get());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // The client interaction listener opens the independent screen.
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || !player.mayBuild() || context.getClickedFace() != Direction.UP) {
            return InteractionResult.FAIL;
        }
        var level = context.getLevel();
        var modelId = selectedModel(context.getItemInHand());
        if (TrainModels.find(modelId) == null) {
            if (!level.isClientSide) player.displayClientMessage(Component.translatable(
                    modelId == null ? "item.unrealtrainmod.vehicle_placer.unselected" : "item.unrealtrainmod.vehicle_placer.missing"), true);
            return InteractionResult.FAIL;
        }
        var hit = context.getClickLocation();
        var target = context.getClickedPos().above();
        if (!player.mayUseItemAt(target, context.getClickedFace(), context.getItemInHand())
                || !level.getWorldBorder().isWithinBounds(target)) return InteractionResult.FAIL;
        if (!level.isClientSide) {
            var entity = UnrealTrainMod.PLACED_MODEL.get().create(level);
            if (entity == null) return InteractionResult.FAIL;
            entity.setModelId(modelId);
            float yaw = Math.round(player.getYRot() / 90f) * 90f;
            entity.moveTo(context.getClickedPos().getX() + 0.5, hit.y,
                    context.getClickedPos().getZ() + 0.5, yaw, 0);
            if (!level.addFreshEntity(entity)) return InteractionResult.FAIL;
            level.playSound(null, entity.blockPosition(), SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.8f, 1);
            level.gameEvent(player, GameEvent.ENTITY_PLACE, entity.position());
        }
        // The placer is reusable.
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        var modelId = selectedModel(stack);
        var model = TrainModels.find(modelId);
        lines.add(Component.translatable("item.unrealtrainmod.vehicle_placer.selected",
                modelId == null ? Component.translatable("item.unrealtrainmod.vehicle_placer.none")
                        : Component.literal(model == null ? modelId.toString() : model.name())).withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("item.unrealtrainmod.vehicle_placer.select").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.unrealtrainmod.vehicle_placer.place").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.unrealtrainmod.vehicle_placer.remove").withStyle(ChatFormatting.GRAY));
    }
}
