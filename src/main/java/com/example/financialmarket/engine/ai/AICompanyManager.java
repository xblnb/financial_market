package com.example.financialmarket.engine.ai;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.MarketState;
import com.example.financialmarket.engine.naming.AStockCodePool;
import com.example.financialmarket.engine.naming.CompanyNameGenerator;
import com.example.financialmarket.engine.random.SeedableRandom;

import java.util.*;

public class AICompanyManager {
    private final List<AICompanyData> companies = new ArrayList<>();

    /**
     * @param seed engineSeed（行情可复现基准）
     * @param pool 共享 A 股代码池（AI cnCode 与玩家 stockId 共用唯一命名空间，互不冲突）
     */
    public AICompanyManager(long seed, AStockCodePool pool) {
        // §0.3-4：该 Random(seed) 是已交付的资产/趋势/波动率/财务取数，绝不替换（命名只新增 SeedableRandom 子流）
        Random rand = new Random(seed);
        String[] industries = {"MINING", "TECH", "AGRICULTURE", "ENERGY", "LOGISTICS", "FINANCE", "CONSTRUCTION", "MAGIC"};
        String[] prefixes = {"深岩", "红石", "平原", "末地", "下界", "星辰", "虚空", "翡翠", "熔岩", "极光"};
        String[] suffixes = {"资源", "科技", "谷物", "能源", "物流", "资本", "建筑", "奥秘", "动力", "控股"};

        // N-2：命名专用 SeedableRandom 子流（engineSeed 派生，禁裸随机），公司名/代码确定性可复现
        SeedableRandom namingRng = new SeedableRandom(
                SeedableRandom.mix64(seed ^ "a_share_naming".hashCode()));

        for (int i = 0; i < 50; i++) {
            String id = "AI_" + i;
            // §0.3-4：保留已交付 rand 的消费顺序/次数（前缀+后缀 2 次、cnCode 1 次），
            // 仅“实际”公司名与 A 股代码改走命名系统，确保行业/资产/趋势/波动率/usCode 取数序列不被扰动
            rand.nextInt(prefixes.length);
            rand.nextInt(suffixes.length);
            String name = CompanyNameGenerator.generate(namingRng);
            String industry = industries[rand.nextInt(industries.length)];
            double asset = 30000 + rand.nextDouble() * 120000;
            double trend = (rand.nextDouble() - 0.2) * 0.06;
            double vol = 0.03 + rand.nextDouble() * 0.08;
            String usCode = generateUSCode(rand);
            int legacyCn = rand.nextInt(10000); // 保留 rand 消费；实际 cnCode 走共享池
            String cnCode = (pool != null) ? pool.allocateCode() : String.format("60%04d", legacyCn);
            // P0-7/A0-3：每公司从 engineSeed 派生子流种子，保证初始与季度演化同种子可复现
            long companySeed = SeedableRandom.mix64(seed ^ id.hashCode());
            companies.add(new AICompanyData(id, name, industry, asset, trend, vol, usCode, cnCode, companySeed));
        }
    }

    private String generateUSCode(Random rand) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append((char)('A' + rand.nextInt(26)));
        return sb.toString();
    }

    public List<AICompanyData> getAll() { return companies; }

    /**
     * DG-3（DATA_GUARD_SPEC §4.1）：按 stockId 取 AI 公司公允价值（均衡价 P_eq 的基本面锚）。
     * 未命中（玩家公司/无 AI 数据）→ 0（调用方回落 GBM 价）。确定性、零随机、只读。
     */
    public double fairValueOf(String stockId) {
        if (stockId == null) return 0.0;
        for (AICompanyData c : companies) {
            if (stockId.equals(c.getStockId())) return c.getFairValue();
        }
        return 0.0;
    }

    /** t4：某股票代码是否属于 AI 公司（用于退市时防御性区分，玩家公司不在 AI 注册表中）。 */
    public boolean hasStock(String stockId) {
        if (stockId == null) return false;
        for (AICompanyData c : companies) {
            if (stockId.equals(c.getStockId())) return true;
        }
        return false;
    }

    public void registerAllToMarket(MarketEngine engine) {
        for (AICompanyData c : companies) engine.registerAICompany(c);
    }

    // ✅ 修正：增加 inflation 参数，传入 AICompanyData.updateFundamentals
    public void updateAll(MarketState state, double riskAppetite, double inflation) {
        for (AICompanyData c : companies) {
            c.updateFundamentals(state, riskAppetite, inflation);
        }
    }

    /**
     * 读档注册 AI 公司：按 stockId 去重并替换（构造器已预建 50 个同 id 副本；若直接追加，
     * getAll() 将含 100 条同 stockId 记录 → Excellent24Index.rebalance 的 Top-24 会同时选中
     * 同公司双副本 → index_constituent 成分重复 → H2 主键冲突（冒烟阻断项）。替换语义保证
     * 持久化的基本面（restoreFundamentals）成为唯一权威。确定性、无随机。
     */
    public void addCompany(AICompanyData company) {
        if (company == null || company.getStockId() == null) return;
        java.util.Iterator<AICompanyData> it = companies.iterator();
        while (it.hasNext()) {
            if (company.getStockId().equals(it.next().getStockId())) it.remove();
        }
        companies.add(company);
    }
}