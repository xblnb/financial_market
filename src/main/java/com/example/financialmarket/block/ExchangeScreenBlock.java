package com.example.financialmarket.block;

import com.example.financialmarket.init.BlockInit;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 交易所屏幕方块（可拼接组合成显示屏；BER 渲染三种模式）。
 *
 * <p>继承 {@link HorizontalDirectionalBlock}：带水平朝向状态（{@code FACING}），使多块贴墙放置、朝向一致、
 * 拼接对齐（规范 §2.2）。右键循环切换显示模式（核心指数 / 大盘行情 / 市场总览），并提示玩家当前模式。
 *
 * <p><b>RuntimeDistCleaner 安全</b>：本块/服务端逻辑<b>不引用任何客户端 Screen/渲染类</b>；BER 为独立
 * {@code @OnlyIn(Dist.CLIENT)} 类，仅由客户端 {@code ClientRenderInit} 注册，服务端不会加载。
 */
public class ExchangeScreenBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public ExchangeScreenBlock(Properties properties) {
        super(properties);
        // 冒烟阻断项修复（FACING 崩溃根治）：HorizontalDirectionalBlock 本身<b>不注册</b> FACING 状态属性
        // （vanilla 的 Furnace/Barrel/Chest 均各自覆写 createBlockStateDefinition 显式 add(FACING)）。
        // 此前本类未覆写 → 状态定义无 facing → 渲染 getValue(FACING) 抛 IllegalArgument、
        // setblock 报 "does not have property"、模型加载报 Unknown property（08-29 三连崩 + 15:10 实机复现同栈根因）。
        // 默认朝向北（确定性；放置时按玩家朝向 setValue）。
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** 放置时按玩家水平朝向反方向（面向玩家；vanilla 标准模式，确定性）。 */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return BlockBehaviour.simpleCodec(ExchangeScreenBlock::new);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ExchangeScreenBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        if (type == BlockInit.EXCHANGE_SCREEN_BE_TYPE) {
            return (BlockEntityTicker<T>) (BlockEntityTicker<ExchangeScreenBlockEntity>)
                    (l, p, s, be) -> be.tick();
        }
        return null;
    }

    /** 右键循环切换显示模式（服务端），并提示当前模式名。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        if (level.getBlockEntity(pos) instanceof ExchangeScreenBlockEntity be) {
            int mode = be.cycleMode();
            String name = switch (mode) {
                case 0 -> "核心指数 (Index)";
                case 1 -> "大盘行情 (Board)";
                default -> "市场总览 (Overview)";
            };
            sp.displayClientMessage(Component.literal("📺 交易所屏幕模式：" + name), false);
        }
        return InteractionResult.sidedSuccess(true);
    }
}
