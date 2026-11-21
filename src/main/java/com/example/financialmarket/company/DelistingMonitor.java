package com.example.financialmarket.company;

import com.example.financialmarket.engine.AssetSnapshot;
import com.example.financialmarket.engine.MarketEngine;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * A-6/§7.1：退市/摘牌监控（DelistingMonitor 从占位落地）。
 *
 * <p>由 {@code MarketEngine.tick} 周期调用（DELIST_CHECK_TICKS，默认每季度）。扫描全部 LISTED 玩家公司，
 * 命中任一确定性阈值即触发退市：连续亏损 / 净资产过低 / 长期破发。执行时向股东回购清偿、
 * 清除持股、从市场移除资产。全程确定性、无随机。
 */
public class DelistingMonitor {
    /** 净资产下限（低于触发退市）。 */
    public static final double DELIST_MIN_NAV = 1000;
    /** 连续亏损期数（≥ 触发退市）。 */
    public static final int PROFIT_LOSS_QUARTERS = 2;
    /** 股价低于发行价比例（低于触发破发判定）。 */
    public static final double DELIST_PRICE_FLOOR = 0.20;
    /** 破发持续交易日数（≥ 触发退市）。 */
    public static final int PRICE_FLOOR_DAYS = 10;

    private DelistingMonitor() {
    }

    /** 检查全部 LISTED 玩家公司，命中阈值即执行退市。 */
    public static void check(ServerLevel level, MarketEngine engine) {
        if (level == null || engine == null) return;
        List<CompanyData> listed = new ArrayList<>(engine.getCompanyManager().getByStatus(CompanyStatus.LISTED));
        for (CompanyData company : listed) {
            evaluate(company, engine, level);
        }
    }

    /** 单公司判定 + 执行退市。触发退市返回 true。 */
    public static boolean evaluate(CompanyData company, MarketEngine engine, ServerLevel level) {
        if (company == null || company.getStatus() != CompanyStatus.LISTED || engine == null) return false;
        String reason = triggerReason(company, engine);
        if (reason == null) return false;
        // 执行退市（确定性）：
        double lastPrice = lastPriceOf(engine, company.getStockId(), company.getIpoPrice());
        double buyoutPrice = Math.max(company.getIpoPrice(), lastPrice);
        engine.delistBuyout(company, level, buyoutPrice);
        engine.removeAsset(company.getStockId());
        engine.getCompanyManager().unregisterStockId(company);
        company.setStockId(null);
        company.setStatus(CompanyStatus.DELISTED);
        company.setIpoState(CompanyIpoState.NONE);
        System.out.println("🏁 公司 " + company.getName() + " 退市（原因：" + reason
                + "，回购价 " + buyoutPrice + "）");
        return true;
    }

    /** 确定性判定阈值；命中返回中文原因，未命中返回 null。 */
    private static String triggerReason(CompanyData company, MarketEngine engine) {
        // 1. 连续亏损（reports 最近 N 期）
        List<CompanyData.CompanyFinancialReport> reports = company.getReports();
        if (reports.size() >= PROFIT_LOSS_QUARTERS) {
            boolean allLoss = true;
            for (int i = reports.size() - PROFIT_LOSS_QUARTERS; i < reports.size(); i++) {
                if (reports.get(i).recentProfit() >= 0) {
                    allLoss = false;
                    break;
                }
            }
            if (allLoss) return "连续 " + PROFIT_LOSS_QUARTERS + " 期亏损";
        }
        // 2. 净资产过低
        if (company.getNetAssetValue() < DELIST_MIN_NAV) return "净资产低于 " + DELIST_MIN_NAV;
        // 3. 长期破发（持续低于发行价 20%，用 lowPriceDays 计数，确定性）
        double last = lastPriceOf(engine, company.getStockId(), company.getIpoPrice());
        boolean belowFloor = company.getIpoPrice() > 0 && last > 0
                && last < company.getIpoPrice() * DELIST_PRICE_FLOOR;
        int lowDays = belowFloor ? company.getLowPriceDays() + 1 : 0;
        company.setLowPriceDays(lowDays);
        if (lowDays >= PRICE_FLOOR_DAYS) {
            return "股价低于发行价 20% 持续 " + PRICE_FLOOR_DAYS + " 日";
        }
        return null;
    }

    private static double lastPriceOf(MarketEngine engine, String stockId, double fallback) {
        if (stockId == null) return fallback;
        AssetSnapshot snap = engine.getState().getAsset(stockId);
        return snap != null && snap.getLastPrice() > 0 ? snap.getLastPrice() : fallback;
    }
}
