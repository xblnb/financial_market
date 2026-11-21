package com.example.financialmarket.engine.microstructure;

import com.example.financialmarket.engine.Order;
import com.example.financialmarket.engine.PriceDiscoveryEngine;

import java.util.UUID;

/**
 * MarS 订单生成标准化流水线（MP-3，MarS Order Generator 对应物）：全部合成订单（微观 agent /
 * ZI-Hawkes / 中观）的统一出口。
 *
 * <p>流水线步骤（全部确定性，自身<b>零随机</b>——不消耗任何随机源，marS 关闭时随机消费次序不变）：
 * <ol>
 *   <li><b>影响修正</b>（{@code marS.enabled && impactEnabled} 时）：对市价/激进限价 Intent 按
 *       {@code Δ=α·σ·√(Q/V)}（{@link MarketImpact#impactMarS}）把 anchorPrice 推向不利方向
 *       （买→付更高、卖→收更少），幅度钳到 [0, 2×spread] 防过度冲击（spec §4.2.4）；</li>
 *   <li><b>规范化</b>：价格 round2 到 0.01（A 股 tick）；数量取整手 100（{@code A_SHARE_BOARD_LOT}，
 *       lotRound 语义对齐 MultiAgentManager 既有实现）。规范化随 marS 生效而生效（因子隔离：
 *       marS/impact 关闭时为纯透传，订单序列与既有基线逐位一致，MP-3-4）；</li>
 *   <li><b>校验</b>：数量&gt;0、休市拒单、涨跌停带（A 股锚 prevClose；回落 P0 动态阈值；市价单跳过——
 *       成交价由撮合钳制；zi/hawkes 限价单跳过——既有 L2.5 路径本就无带内校验，保持基线）；</li>
 *   <li><b>时间戳</b>：{@code genTick/placedTick = tickCounter}（确定性；撮合排序权威语义保持）；</li>
 *   <li><b>产出</b>：{@link Order}（synthetic 标记；playerId=null，合成流不参与真实 T+1 交收）。</li>
 * </ol>
 *
 * <p>语义约定：{@code urgency ≥ 1.0} = 市价单（限价 0，成交价由 OrderBook.walkBook 决定，含 MarS 影响项）；
 * {@code 0 < urgency < 1} = 激进限价单（影响修正生效）；{@code urgency == 0} = 被动限价单（不做影响修正）。
 *
 * <p>纯 Java（依赖 Order/PriceDiscoveryEngine 常量），随引擎种子构造；{@link Context} 由 MarketEngine
 * 每 tick 每资产确定性组装（σ=GARCH 波动、V=滚动 ADV、价差、涨跌停带、开关）。
 */
public class OrderPipeline {

    /**
     * 订单意图：方向/数量/参考价/激进程度/来源角色/时间戳（tickCounter 派生，确定）。
     *
     * @param assetId    资产 ID
     * @param isBuy      是否买单
     * @param quantity   数量（股）
     * @param anchorPrice 参考价（锚；市价单仅作影响计算参考，最终限价为 0）
     * @param urgency    激进程度：0=被动限价；(0,1)=激进限价；(≥1)=市价单
     * @param source     来源角色（"agent"/"zi"/"hawkes"/"meso"，供校验策略区分）
     * @param genTick    生成 tick（= tickCounter，确定性时间戳）
     * @param mmTag      做市商会话标签（"liq-mm:"+assetId；非 MM 流传 null）
     */
    public record OrderIntent(String assetId, boolean isBuy, int quantity, double anchorPrice,
                              double urgency, String source, long genTick, String mmTag) {
        /** 兼容既有 7 参构造（非 MM 流，mmTag=null），保持 meso/zi/hawkes/外部调用方签名不变。 */
        public OrderIntent(String assetId, boolean isBuy, int quantity, double anchorPrice,
                           double urgency, String source, long genTick) {
            this(assetId, isBuy, quantity, anchorPrice, urgency, source, genTick, null);
        }
    }

    /** 影响修正幅度钳制：≤ 2×相对价差（spec §4.2.4，防过度冲击）。 */
    private static final double MAX_IMPACT_SPREAD_MULT = 2.0;

    /**
     * ZI/Hawkes 合成订单流固定标识（对齐 MarketEngine 既有 ZI_PLAYER_ID 语义：不污染真实玩家账本，
     * 合成流不参与真实 T+1 交收）。spec §5.2 step 5「playerId=null 或 ZI_PLAYER_ID/中观专用固定 UUID」。
     */
    private static final UUID ZI_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-00000000ffff");

    private final MarketImpact impactModel;

    public OrderPipeline(long engineSeed) {
        this.impactModel = new MarketImpact(engineSeed);
    }

    /**
     * 每 tick 每资产注入的确定性上下文（由 MarketEngine 组装；无随机）。
     */
    public static final class Context {
        /** 资产 GARCH 条件波动率 σ（PriceDiscoveryEngine.getAssetVolatility）。 */
        public double sigma = 0.0;
        /** 市场成交量 V（VolumeTracker 滚动 ADV）。 */
        public double volume = 0.0;
        /** 相对价差（bestAsk−bestBid）/lastPrice，影响修正钳制上限用。 */
        public double spread = 0.002;
        /** 涨跌停带上限（A 股锚 prevClose；P0 回落 lastPrice×(1+ratio)；bandActive=false 时不校验）。 */
        public double limitUp = Double.MAX_VALUE;
        /** 涨跌停带下限。 */
        public double limitDown = -Double.MAX_VALUE;
        /** 涨跌停带是否生效（prevClose>0 或 P0 lastPrice>0）。 */
        public boolean bandActive = false;
        /** 最优买价（bestBid，>0 时有参考），用于激进限价单判定（越界吃单）。 */
        public double bestBid = 0.0;
        /** 最优卖价（bestAsk，>0 时有参考），用于激进限价单判定（越界吃单）。 */
        public double bestAsk = 0.0;
        /** A 股模式（休市拒单仅在 A 股模式下生效）。 */
        public boolean aShareEnabled = true;
        /** 是否开市（A 股日历；aShare 关闭时恒开，回落 P0 基线）。 */
        public boolean marketOpen = true;
        /**
         * MarS/impact 生效位（= marS.enabled && impactEnabled，由 MarketEngine 注入）：
         * false 时影响修正与整手规范化均为纯透传，订单序列与既有基线逐位一致（因子隔离，MP-3-4）。
         */
        public boolean marSEnabled = false;

        // ===== DG-1 价格笼子（DATA_GUARD_SPEC §2.2 第 3 处调用点，由 MarketEngine.buildPipelineContext 组装） =====
        /** 笼子是否生效（= dgCageEffective() && 簿锚有效 && 带已注入）。 */
        public boolean cageActive = false;
        /** 笼子上端（min(limitUp, lastPrice×(1+cageRatio))）。 */
        public double cageUp = Double.MAX_VALUE;
        /** 笼子下端（max(limitDown, lastPrice×(1−cageRatio))）。 */
        public double cageDown = -Double.MAX_VALUE;
        /**
         * 本流是否跳过笼子钳入：ZI/Hawkes/BROKER **市价型**报价跳过（与既有 zi/hawkes 跳过涨跌停带
         * 校验的语义对齐，OrderPipeline.java:138；市价单成交价由撮合钳制，生成侧不对限价 0 做处理）。
         */
        public boolean cageSkipsThisFlow = false;
        /**
         * 本 tick 内被钳入笼子的合成报价笔数（经 Context 回传引擎审计 → book.recordCageClamp）。
         * 每 tick 新建、非持久化；笼子关闭时恒为 0。
         */
        public long cageClampCount = 0;
    }

    /**
     * 统一合成订单出口。返回 {@code null} 表示订单被校验拒绝（休市/数量非法/越涨跌停带）。
     *
     * @param intent   订单意图
     * @param synthetic 是否合成订单流（代理/ZI/中观 true；不参与真实 T+1 交收）
     * @param ctx      本 tick 本资产上下文（确定性组装）
     */
    public Order toOrder(OrderIntent intent, boolean synthetic, Context ctx) {
        if (intent == null || ctx == null) return null;
        if (intent.quantity <= 0) return null;
        // 休市拒单（A 股时间模型 AS-1；aShare 关闭时恒开 → 回落 P0 基线）
        if (ctx.aShareEnabled && !ctx.marketOpen) return null;

        boolean isMarket = intent.urgency >= 1.0;
        // 规范化：数量取整手（A 股一手=100 股；lotRound 语义对齐 MultiAgentManager，随 marS 生效；
        // marS 关闭时数量原样透传，与既有基线逐位一致）
        int qty = ctx.marSEnabled ? lotRound(intent.quantity) : intent.quantity;
        double price;
        if (isMarket) {
            // 市价单：限价为 0，成交价由 OrderBook.walkBook 撮合决定（含 MarS 影响项叠加）
            price = 0.0;
        } else {
            price = round2(intent.anchorPrice);
            // 市场影响修正：仅对激进限价单（限价越界会立即吃单）且 marS/impact 开启时，
            // 把价格推向不利方向（大单挂单把价格推向不利方向，MarS Order Generator 对应物）。
            // 被动限价（做市商报价/zi 挂单）价格不越界 → 不做修正（保基线语义）。
            boolean crosses = (intent.isBuy && ctx.bestAsk > 0 && price >= ctx.bestAsk)
                    || (!intent.isBuy && ctx.bestBid > 0 && price <= ctx.bestBid);
            if (ctx.marSEnabled && intent.urgency > 0 && crosses
                    && ctx.sigma > 0 && ctx.volume > 0 && price > 0) {
                double impact = impactModel.impactMarS(qty, ctx.sigma, ctx.volume, price, intent.isBuy);
                double cap = MAX_IMPACT_SPREAD_MULT * ctx.spread * price;
                if (cap > 0) impact = Math.max(-cap, Math.min(cap, impact));
                price = round2(price + impact);
            }
            // 涨跌停带校验（A 股 AS-2：锚 prevClose；P0 回落动态阈值）。市价单跳过（成交价由撮合钳制）；
            // zi/hawkes 限价单跳过（既有 L2.5 路径无带内校验，保持基线行为）。
            if (ctx.bandActive && !"zi".equals(intent.source) && !"hawkes".equals(intent.source)
                    && !PriceDiscoveryEngine.aShareWithinBand(price, ctx.limitUp, ctx.limitDown)) {
                return null;
            }
            // DG-1 价格笼子（DATA_GUARD_SPEC §2.2 第 3 处调用点）：越笼子的合成报价「钳入笼子」而非丢弃
            // （保流动性：笼子不得成为系统性拒单器）。cageSkipsThisFlow（ZI/Hawkes/BROKER 市价型）跳过；
            // cageActive=false（dataGuard.cage=false）时零执行 → 与交付基线逐位一致（因子隔离）。
            if (ctx.cageActive && !ctx.cageSkipsThisFlow) {
                double c = Math.max(ctx.cageDown, Math.min(ctx.cageUp, price));
                if (c != price) {
                    price = round2(c);
                    ctx.cageClampCount++;   // 计数经 Context 回传引擎审计（长程 cageClampHits）
                }
            }
        }

        Order.Type type = intent.isBuy
                ? (isMarket ? Order.Type.MARKET_BUY : Order.Type.LIMIT_BUY)
                : (isMarket ? Order.Type.MARKET_SELL : Order.Type.LIMIT_SELL);
        // ZI/Hawkes 合成流保留固定 ZI_PLAYER_ID 标识（语义保持）；其余合成流 playerId=null
        UUID playerId = ("zi".equals(intent.source) || "hawkes".equals(intent.source)) ? ZI_PLAYER_ID : null;
        Order order = new Order(playerId, intent.assetId, type, qty, price, intent.genTick);
        order.setSynthetic(synthetic);
        // LIQ P2（§5.2）：保留做市商标签到 Order（成交归属/序列化用；非 MM 流传 null，不改既有语义）
        if (intent.mmTag() != null) order.setMmTag(intent.mmTag());
        return order;
    }

    /** 归整到 A 股整手（100 股）：不足一手按一手，否则向下取整到一手倍数（对齐 MultiAgentManager.lotRound）。 */
    private static int lotRound(int qty) {
        int lot = Math.max(1, PriceDiscoveryEngine.A_SHARE_BOARD_LOT);
        return qty >= lot ? (qty / lot) * lot : lot;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
