/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.item;

import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.wiring.WiringCatalog;
import com.unrealtrainmod.wiring.WiringManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class WiringItem extends Item {
    private final boolean wire;
    public WiringItem(boolean wire, Properties properties) { super(properties); this.wire = wire; }
    public boolean wire() { return wire; }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayer player) {
            if (player.isShiftKeyDown()) WiringManager.cancel(player);
            else if (!wire) WiringManager.place(player, context);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown() && player instanceof ServerPlayer server) WiringManager.cancel(server);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        var id = stack.get(wire ? ModDataComponents.SELECTED_WIRE_MODEL.get() : ModDataComponents.SELECTED_CONNECTOR_MODEL.get());
        var model = WiringCatalog.find(id);
        lines.add(Component.translatable("wiring.unrealtrainmod.selected", model == null ? (id == null ? "—" : id.toString()) : model.name())
                .withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("wiring.unrealtrainmod.select_hint").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(wire ? "wiring.unrealtrainmod.wire_hint" : "wiring.unrealtrainmod.connector_hint")
                .withStyle(ChatFormatting.GRAY));
    }
}
