package com.example.financialmarket.client;

import com.example.financialmarket.FinancialMarket;
import com.example.financialmarket.client.blockentity.ExchangeScreenBlockEntityRenderer;
import com.example.financialmarket.init.BlockInit;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端渲染器注册（交易所屏幕 BER，规范 §5.3）。
 *
 * <p>仅客户端（{@code value = Dist.CLIENT}）。{@code EntityRenderersEvent.RegisterRenderers} 在 MOD
 * 总线、客户端逻辑侧触发，因此声量为 {@code Bus.MOD} 且 {@code value = Dist.CLIENT}，服务端<b>不会加载</b>
 * 本类（其对客户端渲染类的引用被 RuntimeDistCleaner 隔离）。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = FinancialMarket.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ClientRenderInit {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BlockInit.EXCHANGE_SCREEN_BE_TYPE,
                ExchangeScreenBlockEntityRenderer::new);
    }
}
