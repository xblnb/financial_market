package com.example.financialmarket.init;

import com.example.financialmarket.FinancialMarket;
import com.example.financialmarket.block.DeliveryBlock;
import com.example.financialmarket.block.DeliveryBlockEntity;
import com.example.financialmarket.block.ExchangeScreenBlock;
import com.example.financialmarket.block.ExchangeScreenBlockEntity;
import com.example.financialmarket.block.MarketBlock;
import com.example.financialmarket.block.MarketBlockEntity;
import com.example.financialmarket.block.TradingTerminalBlock;
import com.example.financialmarket.block.TradingTerminalBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * 方块 / 方块实体注册（对照 {@link ItemInit} 先例）。
 *
 * <p>注册市场方块（{@code market_block}）与交割方块（{@code delivery_block}）及其方块实体类型，
 * 并给两个方块实体类型注册 {@code IItemHandler} 能力（漏斗/其他模组管道输入）。
 *
 * <p>本类经 {@code @EventBusSubscriber(Bus.MOD)} 自注册到 MOD 总线（用 {@link RegisterEvent} 注册方块/
 * 方块实体、{@link RegisterCapabilitiesEvent} 注册能力），无需改动 {@code FinancialMarket} 构造接线。
 * 方块值经 static 字段在注册 supplier 内赋值，供方块/方块实体/物品引用。
 *
 * <p><b>BT-10 隔离（tradeBlock.enabled）</b>：方块/方块实体的 {@link RegisterEvent} 注册在 MOD 加载期静态完成，
 * 无法按运行时引擎开关条件注册；tradeBlock.enabled 作为运行时开关（{@code MarketEngine.isTradeBlockEnabled()}）
 * 在<b>运行入口</b>叠加门控——{@code MarketBlockEntity.tick}（售货结算）与 {@code DeliveryBlockEntity.tryPhysicalDelivery}
 * （实物交割）在 tradeBlock.enabled=false 时直接返回、不执行，从而真正回落「不 tick 售货/不交割」基线
 * （对已放置方块同样生效）。
 */
@EventBusSubscriber(modid = FinancialMarket.MODID, bus = EventBusSubscriber.Bus.MOD)
public class BlockInit {
    private static final String MODID = FinancialMarket.MODID;

    public static final ResourceLocation MARKET_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(MODID, "market_block");
    public static final ResourceLocation DELIVERY_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(MODID, "delivery_block");
    public static final ResourceLocation MARKET_BE_ID = ResourceLocation.fromNamespaceAndPath(MODID, "market_block_entity");
    public static final ResourceLocation DELIVERY_BE_ID = ResourceLocation.fromNamespaceAndPath(MODID, "delivery_block_entity");
    public static final ResourceLocation EXCHANGE_SCREEN_ID = ResourceLocation.fromNamespaceAndPath(MODID, "exchange_screen");
    public static final ResourceLocation TRADING_TERMINAL_ID = ResourceLocation.fromNamespaceAndPath(MODID, "trading_terminal");
    public static final ResourceLocation EXCHANGE_SCREEN_BE_ID = ResourceLocation.fromNamespaceAndPath(MODID, "exchange_screen_entity");
    public static final ResourceLocation TRADING_TERMINAL_BE_ID = ResourceLocation.fromNamespaceAndPath(MODID, "trading_terminal_entity");

    /** 市场方块（注册时赋值，供方块实体/物品引用）。 */
    public static Block MARKET_BLOCK;
    /** 交割方块（注册时赋值，供方块实体/物品引用）。 */
    public static Block DELIVERY_BLOCK;
    /** 交易所屏幕方块（可拼接显示屏，BER 三模式）。 */
    public static Block EXCHANGE_SCREEN;
    /** 交易终端方块（金融平板方块化，MCEF）。 */
    public static Block TRADING_TERMINAL;
    public static BlockEntityType<MarketBlockEntity> MARKET_BE_TYPE;
    public static BlockEntityType<DeliveryBlockEntity> DELIVERY_BE_TYPE;
    public static BlockEntityType<ExchangeScreenBlockEntity> EXCHANGE_SCREEN_BE_TYPE;
    public static BlockEntityType<TradingTerminalBlockEntity> TRADING_TERMINAL_BE_TYPE;

    /** MOD 总线：注册方块与方块实体类型。 */
    @SubscribeEvent
    public static void onRegisterBlocks(RegisterEvent event) {
        if (event.getRegistryKey().equals(Registries.BLOCK)) {
            event.register(Registries.BLOCK, MARKET_BLOCK_ID, () ->
                    MARKET_BLOCK = new MarketBlock(BlockBehaviour.Properties.of().strength(2.0f, 6.0f)));
            event.register(Registries.BLOCK, DELIVERY_BLOCK_ID, () ->
                    DELIVERY_BLOCK = new DeliveryBlock(BlockBehaviour.Properties.of().strength(2.0f, 6.0f)));
            event.register(Registries.BLOCK, EXCHANGE_SCREEN_ID, () ->
                    EXCHANGE_SCREEN = new ExchangeScreenBlock(BlockBehaviour.Properties.of().strength(2.0f, 6.0f)));
            event.register(Registries.BLOCK, TRADING_TERMINAL_ID, () ->
                    TRADING_TERMINAL = new TradingTerminalBlock(BlockBehaviour.Properties.of().strength(2.0f, 6.0f)));
        } else if (event.getRegistryKey().equals(Registries.BLOCK_ENTITY_TYPE)) {
            event.register(Registries.BLOCK_ENTITY_TYPE, MARKET_BE_ID, () ->
                    MARKET_BE_TYPE = BlockEntityType.Builder.of(MarketBlockEntity::new, MARKET_BLOCK).build(null));
            event.register(Registries.BLOCK_ENTITY_TYPE, DELIVERY_BE_ID, () ->
                    DELIVERY_BE_TYPE = BlockEntityType.Builder.of(DeliveryBlockEntity::new, DELIVERY_BLOCK).build(null));
            event.register(Registries.BLOCK_ENTITY_TYPE, EXCHANGE_SCREEN_BE_ID, () ->
                    EXCHANGE_SCREEN_BE_TYPE = BlockEntityType.Builder.of(ExchangeScreenBlockEntity::new, EXCHANGE_SCREEN).build(null));
            event.register(Registries.BLOCK_ENTITY_TYPE, TRADING_TERMINAL_BE_ID, () ->
                    TRADING_TERMINAL_BE_TYPE = BlockEntityType.Builder.of(TradingTerminalBlockEntity::new, TRADING_TERMINAL).build(null));
        }
    }

    /** MOD 总线：注册方块实体 IItemHandler 能力（漏斗/管道输入）。 */
    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, MARKET_BE_TYPE,
                (be, side) -> ((MarketBlockEntity) be).getInventory());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, DELIVERY_BE_TYPE,
                (be, side) -> ((MarketBlockEntity) be).getInventory());
    }
}
