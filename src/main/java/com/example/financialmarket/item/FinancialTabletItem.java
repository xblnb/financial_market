package com.example.financialmarket.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 财务平板物品：右键打开 MCEF 看盘/交易界面（仅客户端）。
 * 打开屏幕的逻辑封装在 @OnlyIn(Dist.CLIENT) 方法内，服务端加载本类时 RuntimeDistCleaner 会剔除该客户端引用，
 * 从而避免 "Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER" 崩溃。
 */
public class FinancialTabletItem extends Item {
    public FinancialTabletItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            openScreen();
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @OnlyIn(Dist.CLIENT)
    private void openScreen() {
        try {
            net.minecraft.client.Minecraft.getInstance().setScreen(new com.example.financialmarket.screen.FinancialMCEFScreen());
        } catch (Throwable t) {
            System.err.println("[FinancialTabletItem] 打开 MCEF 屏幕失败: " + t.getMessage());
        }
    }
}
