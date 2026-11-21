package com.example.financialmarket.engine.crisis;

import com.example.financialmarket.engine.MarketSentiment;
import com.example.financialmarket.engine.banking.BankManager;
import com.example.financialmarket.engine.banking.CreditSystem;
import com.example.financialmarket.engine.banking.Government;
import com.example.financialmarket.engine.banking.InflationSystem;
import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;

/**
 * 危机引擎（t4：去裸随机保可复现 + 接入 tick + 持久化 + 消费经济周期相位）。
 *
 * <p>确定性：危机判定改为<strong>纯确定性阈值函数</strong>——{@code crisisLevel} 完全由基本面
 * 指标（不良率 / 债务 / 通胀 / 情绪）+ 经济周期相位加项驱动，无任何未播种随机扰动
 * （R0-3，同种子可复现）。保留 {@code CrisisEngine(long seed)} 与 {@link SeedableRandom} 子流
 * 供未来需要随机成分的扩展。
 */
public class CrisisEngine {
    private double crisisLevel = 0;
    private CrisisType currentCrisis = CrisisType.NONE;
    private int crisisTicks = 0;
    private double creditGrowth = 0;
    private final long seed;
    private final SeedableRandom rng;

    public CrisisEngine() {
        this(0L);
    }

    public CrisisEngine(long seed) {
        this.seed = seed;
        this.rng = new SeedableRandom(SeedableRandom.mix64(seed));
    }

    public enum CrisisType {
        NONE,
        BANK_RUN,           // 银行挤兑
        CREDIT_CRUNCH,      // 信贷紧缩
        STOCK_CRASH,        // 股灾
        SOVEREIGN_DEBT,     // 主权债务危机
        INFLATION_SPIRAL,   // 恶性通胀
        DEPRESSION          // 大萧条
    }

    /**
     * 每 20 tick 宏观节奏调用。确定性：危机等级由基本面指标 + 经济周期相位加项驱动，无随机。
     *
     * @param economicHealth 经济周期引擎的 economicHealth（用于相位 → 危机倾向加项）
     */
    public void tick(MarketSentiment sentiment, BankManager bankManager,
                     Government government, InflationSystem inflation,
                     CreditSystem creditSystem, double economicHealth) {
        // 计算各项危机指标
        double nplRisk = creditSystem.getNplRatio() * 5;
        double debtRisk = creditSystem.getTotalDebt() / (government.getTreasury() + 1) * 0.1;
        double inflationRisk = Math.max(0, inflation.getInflationRate() - 0.1) * 10;
        double sentimentRisk = (0.5 - sentiment.getGlobalSentiment()) * 4;

        crisisLevel = nplRisk * 0.3 + debtRisk * 0.3 + inflationRisk * 0.2 + sentimentRisk * 0.2;
        // 经济周期相位加项（确定性）：收缩/萧条提高危机倾向（R-C）
        crisisLevel += EconomicCycle.crisisPressure(EconomicCycle.phaseOf(economicHealth));
        crisisLevel = Math.max(0, crisisLevel);

        // 记录信贷增速（供 getCreditGrowth 读取，不再恒 0）
        this.creditGrowth = creditSystem.getCreditGrowth();

        // 危机状态判定
        if (crisisLevel > 12 && currentCrisis != CrisisType.DEPRESSION) {
            currentCrisis = CrisisType.DEPRESSION;
            crisisTicks = 24000 * 5; // 持续5天
            government.setFiscalStance(1.0); // 政府干预
        } else if (crisisLevel > 8 && nplRisk > 3) {
            currentCrisis = CrisisType.CREDIT_CRUNCH;
            crisisTicks = 24000 * 2;
        } else if (crisisLevel > 6 && inflation.getInflationRate() > 0.2) {
            currentCrisis = CrisisType.INFLATION_SPIRAL;
            crisisTicks = 24000 * 3;
        } else if (government.isBankrupt()) {
            currentCrisis = CrisisType.SOVEREIGN_DEBT;
            crisisTicks = 24000 * 4;
        } else if (crisisLevel > 4 && sentiment.getGlobalSentiment() < 0.2) {
            currentCrisis = CrisisType.STOCK_CRASH;
            crisisTicks = 24000;
        }

        // 危机影响
        if (currentCrisis != CrisisType.NONE) {
            sentiment.adjustGlobalSentiment(-0.03);
            crisisTicks--;
            if (crisisTicks <= 0) {
                currentCrisis = CrisisType.NONE;
                crisisLevel *= 0.5;
            }
        }
    }

    /** 信贷增速（由 tick 从 CreditSystem 读取，非死代码）。 */
    public double getCreditGrowth() {
        return creditGrowth;
    }

    public double getCrisisLevel() { return crisisLevel; }
    public CrisisType getCurrentCrisis() { return currentCrisis; }

    // ========== 危机 → 市场传导（MS-AI-6：非仅 adjustSentiment；不动 crisisLevel 公式，保证黄金/回放语义不变） ==========

    /** 危机等级归一化到 [0,1]（确定性线性）。 */
    public double getMarketTension() {
        if (crisisLevel <= 0) return 0.0;
        if (crisisLevel >= 12.0) return 1.0;
        return crisisLevel / 12.0;
    }

    /** 危机 → 价差扩大乘子（&gt;1，恐慌时买卖价差走阔）。 */
    public double getSpreadMultiplier() {
        return 1.0 + getMarketTension() * 0.60;
    }

    /** 危机 → 盘口深度乘子（&lt;1，恐慌时深度骤降）。 */
    public double getDepthMultiplier() {
        return Math.max(0.20, 1.0 - getMarketTension() * 0.50);
    }

    /** 危机 → 强制去杠杆/降杠杆乘子（&lt;1，去杠杆闭环）。 */
    public double getDeleverageMultiplier() {
        return Math.max(0.20, 1.0 - getMarketTension() * 0.60);
    }

    /** 危机 → agent 风险厌恶增量（加到风险厌恶系数）。 */
    public double getRiskAversionDelta() {
        return getMarketTension() * 0.40;
    }

    /** 持久化危机状态（随 MarketEngine.save() 的 "crisis" 字段，R0-5）。 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("crisisLevel", crisisLevel);
        tag.putString("currentCrisis", currentCrisis.name());
        tag.putInt("crisisTicks", crisisTicks);
        tag.putDouble("creditGrowth", creditGrowth);
        tag.putLong("seed", seed);
        return tag;
    }

    /** 恢复危机状态；旧档缺字段用 contains 兜底重建，读档不失败（R0-5）。 */
    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("crisisLevel")) crisisLevel = tag.getDouble("crisisLevel");
        if (tag.contains("currentCrisis")) {
            try {
                currentCrisis = CrisisType.valueOf(tag.getString("currentCrisis"));
            } catch (IllegalArgumentException e) {
                currentCrisis = CrisisType.NONE;
            }
        }
        if (tag.contains("crisisTicks")) crisisTicks = tag.getInt("crisisTicks");
        if (tag.contains("creditGrowth")) creditGrowth = tag.getDouble("creditGrowth");
    }
}
