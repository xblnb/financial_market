package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.AssetSnapshot;
import com.example.financialmarket.engine.margin.MarginAccount;
import com.example.financialmarket.engine.margin.MarginManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record RequestMarginDataPacket() implements CustomPacketPayload {
    public static final Type<RequestMarginDataPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_margin"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, RequestMarginDataPacket> STREAM_CODEC = StreamCodec.unit(new RequestMarginDataPacket());

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(RequestMarginDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                // t3：从引擎 MarginManager 取账户（真实现价 + 持仓明细 + 风险率）
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine == null) return;
                MarginManager mm = engine.getMarginManager();
                MarginAccount acc = mm.getOrCreateAccount(sp.getUUID());
                Map<String, Double> prices = currentPrices(engine);
                double equity = acc.getEquity(prices);
                double liability = acc.getLiability(prices);
                double risk = liability > 0 ? Math.max(0, 1.0 - equity / liability) : 0.0;
                List<MarginDataResponsePacket.MarginPositionDTO> positions = new ArrayList<>();
                for (MarginAccount.PositionView v : acc.getPositionViews(prices)) {
                    positions.add(new MarginDataResponsePacket.MarginPositionDTO(
                            v.assetId(), v.quantity(), v.entryPrice(), v.isLong(), v.unrealizedPnl()));
                }
                // t2：多级风险区带 + 追保计数/宽限截止 + 追保需补金额 + 限额用量（AS-M1/AS-M3/AS-M2）
                String band = acc.getRiskBand() == null ? "NORMAL" : acc.getRiskBand().name();
                int streak = acc.getMarginCallStreak();
                long deadline = acc.getMarginCallDeadline();
                double requiredTopUp = liability > 0 ? Math.max(0.0, liability * mm.getMarginCallRatio() - equity) : 0.0;
                double limitUsage = computeLimitUsage(mm, acc, prices, equity, liability);
                sp.connection.send(new MarginDataResponsePacket(acc.getCollateral(), risk, positions,
                        band, streak, deadline, requiredTopUp, limitUsage));
            }
        });
    }

    /** AS-M2：限额用量 = 总负债/单资产名义/单资产融券量/集中度四项用量的最大比例（0 表示未用满）。 */
    private static double computeLimitUsage(MarginManager mm, MarginAccount acc, Map<String, Double> prices,
                                            double equity, double liability) {
        double max = 0.0;
        double totalAsset = equity + liability;
        if (mm.getMaxLiabilityPerAccount() > 0) {
            max = Math.max(max, liability / mm.getMaxLiabilityPerAccount());
        }
        for (MarginAccount.PositionView v : acc.getPositionViews(prices)) {
            double nominal = v.quantity() * prices.getOrDefault(v.assetId(), v.entryPrice());
            if (mm.getMaxPositionValuePerAsset() > 0) {
                max = Math.max(max, nominal / mm.getMaxPositionValuePerAsset());
            }
            if (!v.isLong() && mm.getMaxShortQtyPerAsset() > 0) {
                max = Math.max(max, (double) v.quantity() / mm.getMaxShortQtyPerAsset());
            }
            if (totalAsset > 0 && mm.getConcentrationLimit() > 0) {
                max = Math.max(max, (nominal / totalAsset) / mm.getConcentrationLimit());
            }
        }
        return max;
    }

    private static Map<String, Double> currentPrices(com.example.financialmarket.engine.MarketEngine engine) {
        Map<String, Double> map = new HashMap<>();
        for (String id : engine.getState().getAssetIds()) {
            AssetSnapshot snap = engine.getState().getAsset(id);
            if (snap != null) map.put(id, snap.getLastPrice());
        }
        return map;
    }
}
