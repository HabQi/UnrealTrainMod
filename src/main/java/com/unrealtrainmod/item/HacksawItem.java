/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.item;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.track.TrackGraphException;
import com.unrealtrainmod.track.geometry.TrackProjection;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.train.TrainManager;
import java.util.Comparator;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Track demolition is an explicit server action, never ordinary block mining. */
public final class HacksawItem extends Item {
    public HacksawItem(Properties properties) { super(properties); }

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, HacksawItem::leftClick);
        bus.addListener(HacksawItem::breakBlock);
    }

    private static void breakBlock(BlockEvent.BreakEvent event) {
        if (event.getState().is(UnrealTrainMod.TRACK_BLOCK.get())) event.setCanceled(true);
    }

    private static void leftClick(PlayerInteractEvent.LeftClickBlock event) {
        var level = event.getLevel();
        var pos = event.getPos();
        if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(UnrealTrainMod.TRACK_BLOCK.get())) return;
        // NeoForge still sends START_DESTROY_BLOCK when the client cancels mining.
        event.setCanceled(true);
        if (!(level instanceof ServerLevel server) || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        var player = event.getEntity();
        var stack = player.getMainHandItem();
        if (!stack.is(UnrealTrainMod.HACKSAW.get())) {
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.hacksaw.required"), true);
            return;
        }
        if (!player.mayBuild() || !player.canInteractWithBlock(pos, 0)
                || !level.mayInteract(player, pos) || !player.mayUseItemAt(pos, event.getFace(), stack)) return;
        var hit = player.pick(player.blockInteractionRange(), 1, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK || !blockHit.getBlockPos().equals(pos)) return;
        try {
            var manager = TrackBlockManager.get(server);
            var point = new TrackVector(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z);
            var segment = manager.segmentsAt(pos).stream().min(Comparator.comparingDouble(value -> {
                var geometry = value.geometry();
                return TrackProjection.squaredDistance(geometry.sample(TrackProjection.closestDistance(geometry, point)).position(), point);
            })).orElse(null);
            if (segment == null) return;
            var assembly = manager.graph().assemblyForSegment(segment.id());
            var targets = assembly == null ? List.of(segment.id()) : assembly.segments();
            if (!manager.mayDismantle(player, targets)) {
                player.displayClientMessage(Component.translatable("item.unrealtrainmod.hacksaw.denied"), true);
                return;
            }
            TrainManager.get(server).removeTrack(segment.id(), false);
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.hacksaw.removed"), true);
        } catch (TrackGraphException exception) {
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.hacksaw.blocked"), true);
        } catch (IllegalStateException exception) {
            player.displayClientMessage(Component.translatable("item.unrealtrainmod.hacksaw.storage_error"), true);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        lines.add(Component.translatable("item.unrealtrainmod.hacksaw.tooltip"));
    }
}
