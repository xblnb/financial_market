package com.example.financialmarket.block;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.init.BlockInit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 交割方块（BT-8）：市场方块（绑卡售货/收款） + 绑定一个期货合约，交割日对绑定合约实物交割。
 * 右键绑定持卡玩家；手持含 {@code bound_contract} NBT 的物品右键可绑定合约代码。
 */
public class DeliveryBlock extends MarketBlock {

    public DeliveryBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeliveryBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        if (type == BlockInit.DELIVERY_BE_TYPE) {
            return (BlockEntityTicker<T>) (BlockEntityTicker<DeliveryBlockEntity>) (l, p, s, be) -> be.tick();
        }
        return null;
    }

    /** 右键绑定持卡玩家；手持含 bound_contract NBT 的物品同时绑定合约代码。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        MarketEngine engine = MarketAttachment.getEngine(sl);
        if (engine == null) return InteractionResult.PASS;
        if (level.getBlockEntity(pos) instanceof DeliveryBlockEntity dbe) {
            boolean cardOk = dbe.bindCard(engine, sp);
            // 从手持物品 CUSTOM_DATA NBT 绑定合约代码（确定性方式，无需 GUI）
            ItemStack held = player.getMainHandItem();
            if (held != null && !held.isEmpty()) {
                CustomData cd = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
                if (cd.contains("bound_contract")) {
                    dbe.bindContract(cd.copyTag().getString("bound_contract"));
                }
            }
            if (cardOk) {
                player.displayClientMessage(
                        Component.literal("✅ 交割方块已绑定储蓄卡 " + dbe.getBoundCardNumber()), false);
            } else {
                player.displayClientMessage(
                        Component.literal("⛔ 请先办理储蓄卡（未持卡无法绑定收款）"), false);
            }
            String code = dbe.getBoundContractCode();
            if (code != null && !code.isEmpty()) {
                player.displayClientMessage(Component.literal("📦 交割合约: " + code
                        + (dbe.isDelivered() ? "（已交割）" : "")), false);
            } else {
                player.displayClientMessage(
                        Component.literal("ℹ️ 未绑定交割合约（手持含 bound_contract NBT 的物品右键绑定）"), false);
            }
        }
        return InteractionResult.sidedSuccess(true);
    }
}
