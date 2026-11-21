package com.example.financialmarket.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

public class BankTabletItem extends Item {
    public BankTabletItem(Properties properties) {
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
            net.minecraft.client.Minecraft.getInstance().setScreen(new com.example.financialmarket.screen.BankTabletScreen());
        } catch (Throwable t) {
            System.err.println("[BankTabletItem] 打开银行屏幕失败: " + t.getMessage());
        }
    }
}