package com.example.financialmarket.engine;

import com.example.financialmarket.engine.ai.AICompanyData;
import com.example.financialmarket.engine.banking.Government;
import com.example.financialmarket.engine.banking.CentralBank;
import com.example.financialmarket.engine.banking.InflationSystem;
import com.example.financialmarket.engine.random.SeedableRandom;
import java.util.*;

public class MarketSentiment {
    // P0-7：可播种 PRNG，使情绪噪声可复现
    private final SeedableRandom random;
    private final Map<String, Double> cache = new HashMap<>();
    private final Map<String, Double> fundamentalScores = new HashMap<>();
    private double globalSentiment = 0.7;
    private double policyExpectation = 0.5;

    // ----- P1-3 BSV 过度反应 / 反应不足（事件缓冲 + 预期修正）-----
    private static final int EVENT_BUFFER_N = 10;      // 每资产保留最近 N 个事件幅度
    private final Map<String, Deque<Double>> eventBuffer = new HashMap<>();
    private final Map<String, Double> expectedSentiment = new HashMap<>();
    private final Deque<Double> globalEventBuffer = new ArrayDeque<>();
    private double expectedGlobalSentiment = 0.7;
    private double kResp = 0.6;   // 反应因子
    private int thetaRun = 3;     // 成串阈值：run≥θ 视为过度反应
    private double kRev = 1.0;    // 反转系数
    private double kExp = 0.5;    // 预期渗透到 buyRatio 系数

    // ----- P1-4 情绪-价格正反馈 -----
    private double kFb = 0.05;    // 价格→情绪反馈系数，硬约束 <0.1
    private double globalKFb = 0.02;

    public MarketSentiment() {
        this(0L);
    }

    /** P0-7：以指定种子构造（engineSeed 派生子流）。 */
    public MarketSentiment(long seed) {
        this.random = new SeedableRandom(SeedableRandom.mix64(seed));
    }

    // 更新公司基本面评分（由外部调用）
    public void updateFundamentalScore(String assetId, double score) {
        fundamentalScores.put(assetId, Math.max(0, Math.min(100, score)));
    }

    public double getBuyRatio(String assetId) {
        // 基础情绪 = 全局情绪 × 基本面修正
        double fundamentalScore = fundamentalScores.getOrDefault(assetId, 50.0);
        // 基本面得分映射到 0.3~0.7 的基础买入倾向
        double fundamentalBias = 0.3 + (fundamentalScore / 100) * 0.4;

        // 均值回归
        double current = cache.getOrDefault(assetId, 0.5);
        double reversion = (fundamentalBias - current) * 0.05;

        // 独立噪声
        double individualNoise = random.nextGaussian() * 0.03;

        // 计算新值
        double newValue = current + reversion + individualNoise;
        // 限幅在 0.1~0.9 之间
        newValue = Math.max(0.1, Math.min(0.9, newValue));

        cache.put(assetId, newValue);
        // 最终 buyRatio = 资产情绪 × 全局情绪
        double buyRatio = newValue * globalSentiment;

        // P1-3 BSV 预期修正渗透：effectiveBuyRatio = clamp(buyRatio + k_exp·(expected − current), 0, 1)
        // 无 BSV 事件时 expected=current → 修正为 0，与 P0 行为逐位一致。
        double exp = expectedSentiment.getOrDefault(assetId, current);
        double adjusted = buyRatio + kExp * (exp - current);
        return Math.max(0.0, Math.min(1.0, adjusted));
    }

    /**
     * P1-3 显式读取 BSV 预期修正后的 buyRatio（供 t5/诊断直接观测动量/反转）。
     */
    public double getEffectiveBuyRatio(String assetId) {
        return getBuyRatio(assetId);
    }

    public void updateGlobalSentiment(CommodityMarket market, InflationSystem inflation,
                                      Government government, CentralBank centralBank,
                                      double nplRatio, double economicHealth) {
        double sum = 0;
        int count = 0;
        for (CommodityEntry entry : market.getAllCommodities()) {
            sum += entry.getPrice() / entry.getBasePrice();
            count++;
        }
        double commodityFactor = count > 0 ? (sum / count - 1.0) * 0.2 : 0;
        double inflationFactor = -inflation.getInflationRate() * 2.0;
        double policyFactor = (government.getFiscalStance() - 0.5) * 0.3;
        double nplFactor = -nplRatio * 3.0;
        double rateFactor = (centralBank.getDiscountRate() - 0.03) * -5.0;
        double cycleFactor = economicHealth * 0.2;

        double rawSentiment = 0.5 + commodityFactor + inflationFactor + policyFactor + nplFactor + rateFactor + cycleFactor;
        rawSentiment += random.nextGaussian() * 0.02;
        double prev = globalSentiment;
        globalSentiment = Math.max(0.3, Math.min(0.95, rawSentiment));
        // P1-3 记录 global 级事件（幅度 = 本次变化量）并触发预期修正
        recordGlobalEvent(globalSentiment - prev);
        policyExpectation = 0.5 + government.getFiscalStance() * 0.3;
        policyExpectation = Math.max(0.3, Math.min(0.7, policyExpectation));
    }

    public void adjustGlobalSentiment(double delta) {
        double prev = globalSentiment;
        globalSentiment = Math.max(0.05, Math.min(0.95, globalSentiment + delta));
        // P1-3 global 级事件
        recordGlobalEvent(globalSentiment - prev);
    }

    /**
     * P1-3/P1-4 事件注入点：调整资产情绪并记录事件到 BSV 事件缓冲、触发预期修正。
     * 被 SecuritiesCompany 新闻流（P1-3）与 applyPriceFeedback 价格回馈（P1-4）共用。
     *
     * @param assetId 资产 id。
     * @param delta   事件幅度（新闻 delta 或 k_fb·priceReturn）。
     */
    public void adjustAssetSentiment(String assetId, double delta) {
        double prev = cache.getOrDefault(assetId, 0.5);
        double updated = Math.max(0.1, Math.min(0.9, prev + delta));
        cache.put(assetId, updated);
        // P1-3 资产级事件缓冲 + 预期修正
        recordAssetEvent(assetId, updated - prev);
    }

    /**
     * P1-4 情绪-价格正反馈：价格变动反向推动情绪。
     * {@code sentiment.adjustAssetSentiment(assetId, k_fb·priceReturn)}。
     * 情绪再经 getBuyRatio 回 feed 到 agent 下单（P1-4 闭环）。
     *
     * @param assetId     资产 id。
     * @param priceReturn 价格收益率 (price − prevPrice)/prevPrice。
     */
    public void applyPriceFeedback(String assetId, double priceReturn) {
        // 硬约束 k_fb < 0.1 防发散
        double k = Math.min(0.1, Math.max(0.0, kFb));
        adjustAssetSentiment(assetId, k * priceReturn);
    }

    /**
     * P1-4 全局情绪-价格正反馈（可选）：用全局价格收益率回 feed 全局情绪。
     */
    public void applyGlobalPriceFeedback(double priceReturn) {
        double k = Math.min(0.1, Math.max(0.0, globalKFb));
        adjustGlobalSentiment(k * priceReturn);
    }

    // ---- P1-3 BSV 预期修正核心 ----

    /** 资产级事件：入缓冲、成串检测、ΔE=反应因子·(news−expected)，成串时切换为反转修正。 */
    private void recordAssetEvent(String assetId, double news) {
        Deque<Double> buf = eventBuffer.computeIfAbsent(assetId, k -> new ArrayDeque<>());
        buf.addLast(news);
        while (buf.size() > EVENT_BUFFER_N) buf.removeFirst();
        int run = countConsecutiveSameSign(buf);
        double current = cache.getOrDefault(assetId, 0.5);
        double exp = expectedSentiment.getOrDefault(assetId, current);
        double dE;
        if (run < thetaRun) {
            // 持续型（反应不足/动量）：小修正，趋势延续
            dE = kResp * (news - exp);
        } else {
            // 均值回归型（过度反应/反转）：k 放大 + 对同号做超调后反转修正
            dE = kResp * 1.5 * (news - exp) - kRev * Math.signum(news);
        }
        expectedSentiment.put(assetId, Math.max(0.0, Math.min(1.0, exp + dE)));
    }

    /** global 级事件：入缓冲、成串检测、预期修正（同上）。 */
    private void recordGlobalEvent(double news) {
        globalEventBuffer.addLast(news);
        while (globalEventBuffer.size() > EVENT_BUFFER_N) globalEventBuffer.removeFirst();
        int run = countConsecutiveSameSign(globalEventBuffer);
        double exp = expectedGlobalSentiment;
        double dE;
        if (run < thetaRun) {
            dE = kResp * (news - exp);
        } else {
            dE = kResp * 1.5 * (news - exp) - kRev * Math.signum(news);
        }
        expectedGlobalSentiment = Math.max(0.0, Math.min(1.0, exp + dE));
    }

    /** 统计缓冲中尾部连续同号事件个数（成串 run）。 */
    private int countConsecutiveSameSign(Deque<Double> buf) {
        if (buf.isEmpty()) return 0;
        int lastSign = buf.peekLast() >= 0 ? 1 : -1;
        int run = 0;
        for (Iterator<Double> it = buf.descendingIterator(); it.hasNext(); ) {
            double v = it.next();
            int s = v >= 0 ? 1 : -1;
            if (s != lastSign) break;
            run++;
        }
        return run;
    }

    // ---- P1-3/P1-4 参数可配置入口（含硬约束）----
    public void setKFb(double kFb) {
        // 硬约束 k_fb < 0.1 防发散
        this.kFb = Math.min(0.1, Math.max(0.0, kFb));
    }
    public void setGlobalKFb(double kFb) { this.globalKFb = Math.min(0.1, Math.max(0.0, kFb)); }
    public void setKResp(double kResp) { this.kResp = Math.max(0.0, kResp); }
    public void setThetaRun(int thetaRun) { this.thetaRun = Math.max(1, thetaRun); }
    public void setKRev(double kRev) { this.kRev = Math.max(0.0, kRev); }
    public void setKExp(double kExp) { this.kExp = Math.max(0.0, kExp); }

    public double getKFb() { return kFb; }
    public double getGlobalKFb() { return globalKFb; }
    public double getKResp() { return kResp; }
    public int getThetaRun() { return thetaRun; }
    public double getKRev() { return kRev; }
    public double getKExp() { return kExp; }

    /** P1-3 事件缓冲（只读诊断/持久化）。 */
    public Map<String, Deque<Double>> getEventBuffer() { return eventBuffer; }
    /** P1-3 资产级预期情绪（只读诊断/持久化）。 */
    public Map<String, Double> getExpectedSentiment() { return expectedSentiment; }
    public double getExpectedGlobalSentiment() { return expectedGlobalSentiment; }

    public double getGlobalSentiment() { return globalSentiment; }
    public double getPolicyExpectation() { return policyExpectation; }
}
