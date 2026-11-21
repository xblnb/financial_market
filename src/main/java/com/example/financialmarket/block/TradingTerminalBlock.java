package com.example.financialmarket.block;

import com.example.financialmarket.init.BlockInit;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * 交易终端方块（金融平板方块化，规范 §7）。
 *
 * <p>右键打开现有 MCEF {@code FinancialMCEFScreen}（与金融平板 {@code FinancialTabletItem} 用法一致），
 * 复用 {@code LocalHttpServer} 全套交易 UI。打开逻辑封装在 {@code @OnlyIn(Dist.CLIENT)} 方法内，
 * 服务端加载本类时 RuntimeDistCleaner 剔除该客户端引用，避免加载客户端 Screen 崩溃。
 *
 * <p>无世界面渲染（不贴图）；{@code TradingTerminalBlockEntity} 仅占位。继承
 * {@link HorizontalDirectionalBlock} 带水平朝向。
 */
public class TradingTerminalBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public TradingTerminalBlock(Properties properties) {
        super(properties);
        // 冒烟阻断项修复（FACING 崩溃根治）：HorizontalDirectionalBlock 本身不注册 FACING 状态属性，
        // 须显式 createBlockStateDefinition add(FACING)（vanilla Furnace/Barrel/Chest 同款模式）。
        // 此前未覆写 → 状态定义无 facing → 放置/渲染/模型加载报 Unknown property（与 exchange_screen 同病）。
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
        return BlockBehaviour.simpleCodec(TradingTerminalBlock::new);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TradingTerminalBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return null; // 无 tick（终端仅作交互入口）
    }

    /** 右键：客户端打开 MCEF 交易界面（与金融平板一致）。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level == null) return InteractionResult.PASS;
        if (level.isClientSide) {
            openScreen();
        }
        return InteractionResult.sidedSuccess(true);
    }

    @OnlyIn(Dist.CLIENT)
    private void openScreen() {
        try {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.example.financialmarket.screen.FinancialMCEFScreen());
        } catch (Throwable t) {
            System.err.println("[TradingTerminalBlock] 打开 MCEF 屏幕失败: " + t.getMessage());
        }
    }
}
