/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.block;

import com.mojang.serialization.MapCodec;
import com.unrealtrainmod.track.persistence.TrackSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** World occupancy and interaction; the existing track renderer supplies the appearance. */
public final class TrackBlock extends Block {
    public static final MapCodec<TrackBlock> CODEC = simpleCodec(TrackBlock::new);
    public static final IntegerProperty HEIGHT = IntegerProperty.create("height", 1, 16);
    private static final VoxelShape[] SHAPES = new VoxelShape[16];

    static {
        for (int i = 0; i < SHAPES.length; i++) SHAPES[i] = Block.box(0, 0, 0, 16, i + 1, 16);
    }

    public TrackBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HEIGHT, 16));
    }

    @Override
    public MapCodec<TrackBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HEIGHT);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) { return 0; }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(HEIGHT) - 1];
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !newState.is(this)) {
            TrackSavedData.get(serverLevel).blocks().removed(pos);
        }
    }
}
