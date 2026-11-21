package com.example.financialmarket.init;

import com.example.financialmarket.FinancialMarket;
import com.example.financialmarket.item.BankTabletItem;
import com.example.financialmarket.item.FinancialTabletItem;
import com.example.financialmarket.item.GovernmentTabletItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@EventBusSubscriber(modid = FinancialMarket.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ItemInit {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FinancialMarket.MODID);

    // 将物品定义为静态字段，确保在事件订阅前完成注册。
    // financial_tablet 用一个独立顶层类（打开客户端 MCEF 屏幕逻辑封装在类内，经 dist 判断），
    // 避免匿名类在服务端加载时引用客户端 Screen（RuntimeDistCleaner 崩溃）。
    public static final DeferredItem<Item> FINANCIAL_TABLET = ITEMS.register("financial_tablet",
            () -> new FinancialTabletItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> BANK_TABLET = ITEMS.register("bank_tablet",
            () -> new BankTabletItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> GOVERNMENT_TABLET = ITEMS.register("government_tablet",
            () -> new GovernmentTabletItem(new Item.Properties().stacksTo(1)));

    // t4：贸易方块物品（市场方块 + 交割方块，BlockItem 供放置；方块本体在 BlockInit 注册）
    public static final DeferredItem<Item> MARKET_BLOCK_ITEM = ITEMS.register("market_block",
            () -> new BlockItem(BlockInit.MARKET_BLOCK, new Item.Properties()));

    public static final DeferredItem<Item> DELIVERY_BLOCK_ITEM = ITEMS.register("delivery_block",
            () -> new BlockItem(BlockInit.DELIVERY_BLOCK, new Item.Properties()));

    // 交易所屏幕 + 交易终端（BlockItem 供放置；方块本体在 BlockInit 注册）
    public static final DeferredItem<Item> EXCHANGE_SCREEN_ITEM = ITEMS.register("exchange_screen",
            () -> new BlockItem(BlockInit.EXCHANGE_SCREEN, new Item.Properties()));

    public static final DeferredItem<Item> TRADING_TERMINAL_ITEM = ITEMS.register("trading_terminal",
            () -> new BlockItem(BlockInit.TRADING_TERMINAL, new Item.Properties()));

    @SubscribeEvent
    public static void buildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(FINANCIAL_TABLET);
            event.accept(BANK_TABLET);
            event.accept(GOVERNMENT_TABLET);
            event.accept(MARKET_BLOCK_ITEM);
            event.accept(DELIVERY_BLOCK_ITEM);
            event.accept(EXCHANGE_SCREEN_ITEM);
            event.accept(TRADING_TERMINAL_ITEM);
        }
    }
}