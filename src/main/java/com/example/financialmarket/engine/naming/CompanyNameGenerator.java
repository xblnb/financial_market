package com.example.financialmarket.engine.naming;

import com.example.financialmarket.engine.random.SeedableRandom;

/**
 * 中文公司名确定性生成器（N-1）。
 *
 * <p>公司名 = {@code 地域/名号词根 + 行业词根 + 组织后缀} 三段随机组合，符合 A 股上市公司
 * 中文名称习惯（如「星河电子科技」「东岳金融控股」「九鼎新能源股份」）。词库为编译期常量数组，
 * 纯 Java、无随机；随机性完全由调用方传入的 {@link SeedableRandom} 子流提供，保证同一 rng
 * 状态 → 同一公司名。
 *
 * <p>说明：本规则只用于 <b>AI 公司名</b> 的随机组合；玩家公司名由玩家 {@code /fmarket company
 * create <name>} 提供，不随机生成。
 */
public final class CompanyNameGenerator {

    private CompanyNameGenerator() {}

    /** 地域/名号词根（≥20）。 */
    private static final String[] NAME_ROOTS = {
            "华夏", "中原", "天枢", "星河", "东岳", "南岭", "昆仑", "东海", "北辰", "九鼎",
            "云泽", "鸿运", "长风", "曜石", "磐石", "沧海", "青岚", "紫垣", "苍梧", "玄黄",
            "泰山", "洞庭", "陇原", "昭华"
    };

    /** 行业词根（≥20）。 */
    private static final String[] INDUSTRY_ROOTS = {
            "电子", "医药", "能源", "机械", "钢铁", "建材", "金融", "物流", "农业", "科技",
            "信息", "通信", "化工", "汽车", "食品", "纺织", "矿业", "环保", "军工", "航天",
            "半导体", "生物", "智能", "新能源"
    };

    /** 组织后缀（≥10）。 */
    private static final String[] ORG_SUFFIX = {
            "股份", "科技", "控股", "集团", "实业", "电子", "能源", "生物", "新材", "装备",
            "精工", "智造"
    };

    /** 用传入 rng 连续三段独立取词组合公司名；同一 rng 状态 → 同名。 */
    public static String generate(SeedableRandom rng) {
        String root = NAME_ROOTS[rng.nextInt(NAME_ROOTS.length)];
        String industry = INDUSTRY_ROOTS[rng.nextInt(INDUSTRY_ROOTS.length)];
        String suffix = ORG_SUFFIX[rng.nextInt(ORG_SUFFIX.length)];
        return root + industry + suffix;
    }
}
