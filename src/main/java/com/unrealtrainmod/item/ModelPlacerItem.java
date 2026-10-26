/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.UnrealTrainMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.gameevent.GameEvent;
import java.util.List;

public final class ModelPlacerItem extends Item {
    public ModelPlacerItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || !player.mayBuild() || context.getClickedFace() != Direction.UP) {
            return InteractionResult.FAIL;
        }
        var level = context.getLevel();
        var hit = context.getClickLocation();
        var target = context.getClickedPos().above();
        if (!player.mayUseItemAt(target, context.getClickedFace(), context.getItemInHand())
                || !level.getWorldBorder().isWithinBounds(target)) return InteractionResult.FAIL;
        if (!level.isClientSide) {
            var entity = UnrealTrainMod.PLACED_MODEL.get().create(level);
            if (entity == null) return InteractionResult.FAIL;
            float yaw = Math.round(player.getYRot() / 90f) * 90f;
            entity.moveTo(context.getClickedPos().getX() + 0.5, hit.y + 0.01,
                    context.getClickedPos().getZ() + 0.5, yaw, 0);
            if (!level.addFreshEntity(entity)) return InteractionResult.FAIL;
            level.playSound(null, entity.blockPosition(), SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.8f, 1);
            level.gameEvent(player, GameEvent.ENTITY_PLACE, entity.position());
        }
        // Reusable development tool; placing a display does not consume the item.
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        lines.add(Component.translatable("item.unrealtrainmod.example_item.place").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.unrealtrainmod.example_item.remove").withStyle(ChatFormatting.GRAY));
    }
}
