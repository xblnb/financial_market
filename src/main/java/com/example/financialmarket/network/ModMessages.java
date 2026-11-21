package com.example.financialmarket.network;

import com.example.financialmarket.FinancialMarket;
import com.example.financialmarket.engine.OrderBook;
import com.example.financialmarket.network.packets.*;
import com.example.financialmarket.screen.FinancialTabletScreen;
import com.example.financialmarket.screen.BankTabletScreen;
import com.example.financialmarket.web.ClientMarketDataCache;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(modid = FinancialMarket.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModMessages {
    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("financialmarket");

        // Client -> Server
        registrar.playToServer(RequestStockDataPacket.ID, RequestStockDataPacket.STREAM_CODEC, RequestStockDataPacket::handle);
        registrar.playToServer(RequestKlinePacket.ID, RequestKlinePacket.STREAM_CODEC, RequestKlinePacket::handle);
        registrar.playToServer(PlaceOrderPacket.ID, PlaceOrderPacket.STREAM_CODEC, PlaceOrderPacket::handle);
        registrar.playToServer(CancelOrderPacket.ID, CancelOrderPacket.STREAM_CODEC, CancelOrderPacket::handle);
        registrar.playToServer(RequestMarketListPacket.ID, RequestMarketListPacket.STREAM_CODEC, RequestMarketListPacket::handle);
        registrar.playToServer(RequestPortfolioPacket.ID, RequestPortfolioPacket.STREAM_CODEC, RequestPortfolioPacket::handle);
        registrar.playToServer(RequestCommodityPricePacket.ID, RequestCommodityPricePacket.STREAM_CODEC, RequestCommodityPricePacket::handle);
        registrar.playToServer(CommodityBuyPacket.ID, CommodityBuyPacket.STREAM_CODEC, CommodityBuyPacket::handle);
        registrar.playToServer(CommoditySellPacket.ID, CommoditySellPacket.STREAM_CODEC, CommoditySellPacket::handle);
        registrar.playToServer(CompanyActionPacket.ID, CompanyActionPacket.STREAM_CODEC, CompanyActionPacket::handle);
        registrar.playToServer(RequestCompanyDataPacket.ID, RequestCompanyDataPacket.STREAM_CODEC, RequestCompanyDataPacket::handle);
        registrar.playToServer(CompanyAreaPacket.ID, CompanyAreaPacket.STREAM_CODEC, CompanyAreaPacket::handle);
        registrar.playToServer(DepositPacket.ID, DepositPacket.STREAM_CODEC, DepositPacket::handle);
        registrar.playToServer(WithdrawPacket.ID, WithdrawPacket.STREAM_CODEC, WithdrawPacket::handle);
        registrar.playToServer(LoanRequestPacket.ID, LoanRequestPacket.STREAM_CODEC, LoanRequestPacket::handle);
        registrar.playToServer(RepayLoanPacket.ID, RepayLoanPacket.STREAM_CODEC, RepayLoanPacket::handle);
        registrar.playToServer(EnableQuantStrategyPacket.ID, EnableQuantStrategyPacket.STREAM_CODEC, EnableQuantStrategyPacket::handle);
        registrar.playToServer(DisableQuantStrategyPacket.ID, DisableQuantStrategyPacket.STREAM_CODEC, DisableQuantStrategyPacket::handle);
        registrar.playToServer(SaveQuantScriptPacket.ID, SaveQuantScriptPacket.STREAM_CODEC, SaveQuantScriptPacket::handle);
        registrar.playToServer(RequestQuantScriptPacket.ID, RequestQuantScriptPacket.STREAM_CODEC, RequestQuantScriptPacket::handle);
        registrar.playToServer(MarginOrderPacket.ID, MarginOrderPacket.STREAM_CODEC, MarginOrderPacket::handle);
        registrar.playToServer(MarginDepositPacket.ID, MarginDepositPacket.STREAM_CODEC, MarginDepositPacket::handle);
        registrar.playToServer(RequestMarginDataPacket.ID, RequestMarginDataPacket.STREAM_CODEC, RequestMarginDataPacket::handle);
        registrar.playToServer(RequestLeveragedNoteDataPacket.ID, RequestLeveragedNoteDataPacket.STREAM_CODEC, RequestLeveragedNoteDataPacket::handle);
        registrar.playToServer(LeveragedNoteOrderPacket.ID, LeveragedNoteOrderPacket.STREAM_CODEC, LeveragedNoteOrderPacket::handle);
        registrar.playToServer(LeveragedNoteIssuePacket.ID, LeveragedNoteIssuePacket.STREAM_CODEC, LeveragedNoteIssuePacket::handle);
        registrar.playToServer(RequestLoanDataPacket.ID, RequestLoanDataPacket.STREAM_CODEC, RequestLoanDataPacket::handle);
        registrar.playToServer(GovernmentActionPacket.ID, GovernmentActionPacket.STREAM_CODEC, GovernmentActionPacket::handle);
        registrar.playToServer(FinancialWarPacket.ID, FinancialWarPacket.STREAM_CODEC, FinancialWarPacket::handle);
        registrar.playToServer(BankCreatePacket.ID, BankCreatePacket.STREAM_CODEC, BankCreatePacket::handle);
        registrar.playToServer(BondBuyPacket.ID, BondBuyPacket.STREAM_CODEC, BondBuyPacket::handle);
        // ===== 银行平板交互包（AS-2/AS-4/AS-5/AS-6；服务端权威，客户端仅发包） =====
        registrar.playToServer(BankDepositPacket.ID, BankDepositPacket.STREAM_CODEC, BankDepositPacket::handle);
        registrar.playToServer(BankLoanPacket.ID, BankLoanPacket.STREAM_CODEC, BankLoanPacket::handle);
        registrar.playToServer(CentralBankCreatePacket.ID, CentralBankCreatePacket.STREAM_CODEC, CentralBankCreatePacket::handle);
        registrar.playToServer(CentralBankPolicyPacket.ID, CentralBankPolicyPacket.STREAM_CODEC, CentralBankPolicyPacket::handle);
        // BT-6/AS-6：银行利率/参数管理（仅银行拥有者可改，服务端权威）
        registrar.playToServer(BankRatePacket.ID, BankRatePacket.STREAM_CODEC, BankRatePacket::handle);
        // 连接审计修复（P3）：银行平板初始数据请求此前未注册 → 打开即发的请求到不了服务端（「暂无银行数据」）
        registrar.playToServer(RequestBankDataPacket.ID, RequestBankDataPacket.STREAM_CODEC, RequestBankDataPacket::handle);
        // 冒烟阻断项修复：储蓄卡开卡（客户端仅发包、服务端权威发卡；真实玩家发卡入口）
        registrar.playToServer(CardIssuePacket.ID, CardIssuePacket.STREAM_CODEC, CardIssuePacket::handle);

        // Server -> Client
        registrar.playToClient(StockDataResponsePacket.ID, StockDataResponsePacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    ClientMarketDataCache.getInstance().updateKline(packet.assetId(), packet.klines(), packet.tradePoints());
                    if (Minecraft.getInstance().screen instanceof FinancialTabletScreen screen)
                        screen.updateStockData(packet);
                }));
        registrar.playToClient(PortfolioSyncPacket.ID, PortfolioSyncPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    if (Minecraft.getInstance().screen instanceof FinancialTabletScreen screen)
                        screen.updatePortfolio(packet.holdings(), packet.cash());
                }));
        registrar.playToClient(MarketListPacket.ID, MarketListPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    ClientMarketDataCache.getInstance().updateStockList(packet.stocks());
                    // 如果当前打开的是旧版屏幕，也兼容更新
                    if (Minecraft.getInstance().screen instanceof FinancialTabletScreen screen)
                        screen.updateMarketList(packet);
                }));
        registrar.playToClient(CommodityPriceSyncPacket.ID, CommodityPriceSyncPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    if (Minecraft.getInstance().screen instanceof FinancialTabletScreen screen)
                        screen.updateCommodityPrices(packet.prices());
                }));
        registrar.playToClient(CompanyDataResponsePacket.ID, CompanyDataResponsePacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    if (Minecraft.getInstance().screen instanceof FinancialTabletScreen screen)
                        screen.updateCompanyData(packet.company());
                }));
        registrar.playToClient(OrderBookSnapshotPacket.ID, OrderBookSnapshotPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    // 将 OrderBookSnapshotPacket 转换为 OrderBook.OrderBookSnapshot
                    OrderBook.OrderBookSnapshot snap = new OrderBook.OrderBookSnapshot();
                    for (var l : packet.bids()) snap.bids.add(new OrderBook.OrderBookLevel(l.price(), l.quantity()));
                    for (var l : packet.asks()) snap.asks.add(new OrderBook.OrderBookLevel(l.price(), l.quantity()));
                    ClientMarketDataCache.getInstance().updateDepth(packet.assetId(), snap);
                }));
        registrar.playToClient(MarginDataResponsePacket.ID, MarginDataResponsePacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    if (Minecraft.getInstance().screen instanceof FinancialTabletScreen screen)
                        screen.updateMarginData(packet.balance(), packet.risk());
                }));
        registrar.playToClient(LeveragedNoteListPacket.ID, LeveragedNoteListPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> ClientMarketDataCache.getInstance().updateLeveragedNotes(packet.notes())));
        registrar.playToClient(LeveragedNotePositionSyncPacket.ID, LeveragedNotePositionSyncPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> ClientMarketDataCache.getInstance().updateLeveragedNotePositions(packet.positions())));
        registrar.playToClient(LoanDataResponsePacket.ID, LoanDataResponsePacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    if (Minecraft.getInstance().screen instanceof FinancialTabletScreen screen)
                        screen.updateLoanData(packet.loans());
                }));
        registrar.playToClient(BankDataResponsePacket.ID, BankDataResponsePacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    if (Minecraft.getInstance().screen instanceof FinancialTabletScreen screen)
                        screen.updateBankData(packet.banks(), packet.cash(), packet.loans());
                    // AS-7：修正银行平板 s2c 路由（此前 BankDataResponsePacket 仅路由到金融平板）
                    if (Minecraft.getInstance().screen instanceof BankTabletScreen screen) {
                        screen.updateBankData(packet.banks(), packet.cash(), packet.loans());
                        // AS-4/BT-7：转发央行实时政策数据（贴现率/准备金率/基础货币/玩家归属）
                        screen.updateCentralBankData(packet.centralBank());
                        // BT-9/BT-10：banking.enabled 信号驱动交互/只读回落（不注册交互控件）
                        screen.setBankingEnabled(packet.bankingEnabled());
                    }
                }));
        registrar.playToClient(GovernmentDataResponsePacket.ID, GovernmentDataResponsePacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    if (Minecraft.getInstance().screen instanceof FinancialTabletScreen screen)
                        screen.updateGovernmentData(packet.treasury(), packet.totalDebt(), packet.inflation(), packet.baseMoney(), packet.discountRate());
                }));
        registrar.playToClient(QuantScriptDataPacket.ID, QuantScriptDataPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    if (Minecraft.getInstance().screen instanceof FinancialTabletScreen screen)
                        screen.updateQuantScript(packet.fileName(), packet.code());
                }));

        // ===== t9：交易链路（下单/撤单/持仓/成交）响应包 =====
        registrar.playToClient(OrdersSyncPacket.ID, OrdersSyncPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (OrdersSyncPacket.OrderEntry o : packet.orders()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("orderId", o.orderId());
                        m.put("assetId", o.assetId());
                        m.put("name", o.name());
                        m.put("side", o.side());
                        m.put("type", o.type());
                        m.put("price", o.price());
                        m.put("quantity", o.quantity());
                        m.put("remaining", o.remaining());
                        m.put("status", o.status());
                        m.put("time", o.time());
                        list.add(m);
                    }
                    ClientMarketDataCache.getInstance().updateOrders(list);
                }));
        registrar.playToClient(PositionsSyncPacket.ID, PositionsSyncPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (PositionsSyncPacket.PositionEntry p : packet.positions()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("assetId", p.assetId());
                        m.put("name", p.name());
                        m.put("quantity", p.quantity());
                        m.put("available", p.available());
                        m.put("frozen", p.frozen());
                        m.put("avgCost", p.avgCost());
                        m.put("lastPrice", p.lastPrice());
                        m.put("marketValue", p.marketValue());
                        m.put("profit", p.profit());
                        m.put("profitPct", p.profitPct());
                        list.add(m);
                    }
                    ClientMarketDataCache.getInstance().updatePositions(list);
                }));
        registrar.playToClient(TradesSyncPacket.ID, TradesSyncPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (TradesSyncPacket.TradeEntry t : packet.trades()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("time", t.time());
                        m.put("assetId", t.assetId());
                        m.put("name", t.name());
                        m.put("side", t.side());
                        m.put("price", t.price());
                        m.put("quantity", t.quantity());
                        list.add(m);
                    }
                    ClientMarketDataCache.getInstance().updateTrades(list);
                }));
        registrar.playToClient(TradingResultPacket.ID, TradingResultPacket.STREAM_CODEC,
                (packet, ctx) -> ctx.enqueueWork(() -> {
                    // 下单/撤单结果：瞬态 UI 反馈（前端经 bridge 的 updateTradingResult 展示）。
                    // 权威交易状态已由上方 OrdersSync/PositionsSync/TradesSync 刷新到 ClientMarketDataCache。
                    ClientMarketDataCache.getInstance(); // no-op 占位：结果仅作提示，状态以各 Sync 包为准
                }));
    }
}