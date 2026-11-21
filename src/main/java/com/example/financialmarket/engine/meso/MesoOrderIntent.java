package com.example.financialmarket.engine.meso;

/**
 * MarS 中观层订单意图（MP-1 产出，供 MP-3 OrderPipeline 消费的统一 Intent 形态）。
 *
 * <p>与规格 MP-3 §5.2 {@code OrderPipeline.OrderIntent} 字段对齐
 * （assetId/isBuy/quantity/anchorPrice/urgency/source/genTick），engine-dev 实现 OrderPipeline 时
 * 可直接映射：{@code new OrderPipeline.OrderIntent(i.assetId(), i.isBuy(), i.quantity(), i.price(),
 * i.urgency(), i.source(), i.genTick())}。
 *
 * <p>确定性：{@code genTick = MarketEngine.tickCounter}（时间权威，非 wall-clock）；价格已按 A 股
 * 0.01 步长 round2 规范化，数量已按整手 100 归整（本层产出即规范，不依赖 pipeline 再修正）。
 *
 * @param assetId    目标资产 id
 * @param isBuy      方向（true=买）
 * @param quantity   数量（整手 100 整数倍）
 * @param price      参考/目标限价（0.01 步长）
 * @param urgency    激进程度 [0,1]（预留，供 MP-3 影响修正使用；本层当前产出为限价单）
 * @param source     来源角色（"sector/&lt;industry&gt;" 或 "inst/&lt;i&gt;/&lt;style&gt;"，可观测）
 * @param genTick    确定性生成 tick（= MarketEngine.tickCounter）
 */
public record MesoOrderIntent(
        String assetId,
        boolean isBuy,
        int quantity,
        double price,
        double urgency,
        String source,
        long genTick
) {
    /** 便捷构造：本层默认 urgency = 0.5（中等激进，与限价单语义一致）。 */
    public MesoOrderIntent(String assetId, boolean isBuy, int quantity, double price, String source, long genTick) {
        this(assetId, isBuy, quantity, price, 0.5, source, genTick);
    }
}
