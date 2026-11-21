package com.example.financialmarket.block;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.init.BlockInit;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 市场方块（BT-7）：可放置，含 {@link MarketBlockEntity}。右键绑定持卡玩家；
 * 漏斗/其他模组管道（IItemHandler 能力）可往内输入物品，开市时按商品价售货、收益入绑定储蓄账户。
 */
public class MarketBlock extends Block implements EntityBlock {

    public MarketBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MarketBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        if (type == BlockInit.MARKET_BE_TYPE) {
            return (BlockEntityTicker<T>) (BlockEntityTicker<MarketBlockEntity>) (l, p, s, be) -> be.tick();
        }
        return null;
    }

    /** 右键绑定当前持卡玩家的储蓄卡（未持卡提示先办卡）。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        MarketEngine engine = MarketAttachment.getEngine(sl);
        if (engine == null) return InteractionResult.PASS;
        if (level.getBlockEntity(pos) instanceof MarketBlockEntity mbe) {
            boolean ok = mbe.bindCard(engine, sp);
            if (ok) {
                player.displayClientMessage(
                        Component.literal("✅ 市场方块已绑定储蓄卡 " + mbe.getBoundCardNumber()), false);
            } else {
                player.displayClientMessage(
                        Component.literal("⛔ 请先办理储蓄卡（未持卡无法绑定收款）"), false);
            }
        }
        return InteractionResult.sidedSuccess(true);
    }
}
