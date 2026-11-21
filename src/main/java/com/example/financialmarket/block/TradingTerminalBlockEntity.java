package com.example.financialmarket.block;

import com.example.financialmarket.init.BlockInit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 交易终端方块实体（规范 §7）：占位实体，保证服务端有实体/可破坏性。
 * 无业务状态（FACING 由 blockstate 承担），且无世界面渲染（渲染交给 MCEF 浏览器）。
 */
public class TradingTerminalBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity {

    public TradingTerminalBlockEntity(BlockPos pos, BlockState state) {
        this(BlockInit.TRADING_TERMINAL_BE_TYPE, pos, state);
    }

    protected TradingTerminalBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                                         BlockPos pos, BlockState state) {
        super(type, pos, state);
    }
}
