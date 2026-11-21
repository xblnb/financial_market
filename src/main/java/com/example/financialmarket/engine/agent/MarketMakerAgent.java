package com.example.financialmarket.engine.agent;

import com.example.financialmarket.engine.OrderBook;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * LIQUIDITY_SPEC §5（LQ-1/LQ-4）纯做市商 MM-Agent（存货 Inventory 模型，synthetic 流动性设施）。
 *
 * <p><b>定位</b>：每资产 1 个专职义务做市商，与既有 {@code MARKET_MAKER} 池 agent（利润型、
 * deviation ±0.5 停供）并存但语义升级：<b>只要开市且价带允许就每 tick 双边报价、不停供</b>，
 * 承担「盘口常驻连续有量」的流动性义务（§5.1 义务性；修复 R-2 的停供缺陷）。
 *
 * <p><b>报价公式（用户存货模型，§5.1）</b>：
 * <pre>
 *   P_bid(t) = P_mid − S(t)/2 − γ·I(t)
 *   P_ask(t) = P_mid + S(t)/2 − γ·I(t)
 * </pre>
 * <ul>
 *   <li>{@code P_mid}：簿 mid（best bid/ask 均值；无双边回落 lastPrice，同 L2.5 midPriceOf）；</li>
 *   <li>{@code S(t)}：动态价差（§5.4/LQ-4 正指数勘误版 {@code S = α·e^(+β·|ln(Vb/Va)|)}，
 *       失衡大→价差扩（风控）、势均力敌→收敛 α 底（引成交）），钳 [tick, 5%×mid]；</li>
 *   <li>{@code I(t)}：signed 存货（+多 −空）。γ·I 把存货风险压回 0：I&gt;0 → 双侧报价整体下移促卖；
 *       I&lt;0 → 上移促买。γ 按每资产每 tick 确定性导出
 *       {@code γ = mmGammaBase × P_mid / max(targetDepth,1)}（0.001×mid/股单位刻度量级：
 *       I=1×targetDepth → 下移 0.1%×mid；I=±5×targetDepth 上限 → 0.5%×mid，见 mmInventoryCapMult）。</li>
 * </ul>
 *
 * <p><b>厚度 ≈ 总股本 5%（LQ-1，比率验收）</b>：目标单侧厚度
 * {@code targetDepth = max(一手, round(mmDepthFraction × floatSharesOf(assetId)))}，上界
 * {@code mmDepthCapShares} 防绝对量爆炸；厚度按 3~5 档分布、档距随档位递增、量随档距衰减，
 * top-of-book 最优档首 tick 即挂出。
 *
 * <p><b>两种出流模式</b>（同一报价内核，引擎按接线选一，勿同开以免双份报价）：
 * <ol>
 *   <li><b>ramp（自动随 agentOrders 同批）</b>：{@link #rampedFlows}——每 tick 按
 *       {@code perTickBudget = targetDepth / mmFillTicks(60)} 铺单、内部滚动估计未成交存量，
 *       60 tick 铺满后仅补足差额（顶格预算），存量有界；</li>
 *   <li><b>full-stack（引擎侧撤旧重挂）</b>：{@link #quoteFlows}——引擎先按 {@link #getTag()}
 *       撤本 MM 旧报价（OrderBook.cancelByTag，§5.1 撤单重挂），再取本方法整栈新报价
 *       （P_mid/S/I 每 tick 刷新）。</li>
 * </ol>
 *
 * <p><b>确定性 / 持久化</b>：独立子流 {@code seed = mix64(engineSeed ^ ("liq/mm/" + assetId).hashCode())}
 * （§7.3），禁用裸随机；关闭 LIQ（{@code liq.enabled=false}，由 MultiAgentManager 总开关承载）时本类
 * 不消费任何随机、不产生订单。状态 {@code save()/load()}（inventory/avgCost/rng 等）随引擎 "liq" blob
 * 持久化，旧档缺字段 contains 兜底重建空仓（§5.3/§7.2）。纯 Java + NBT，不依赖 Minecraft 运行时。
 *
 * <p>存货记账入口：MM 报价被成交时由 MarketEngine.attributeTrade 钩子调用
 * {@link #onFill(int, double, long)}（synthetic，无真实账户，I(t) 受 ±5×targetDepth 钳制防失控）。
 */
public class MarketMakerAgent {
    // ---------- 先验默认（LIQUIDITY_SPEC §5.1/§5.4；引擎侧 CalibrationParams 注入可覆盖，见 configure） ----------
    /** 目标厚度占流通股本比例（§5.1，LQ-1 比率验收基准）。 */
    public static final double DEFAULT_DEPTH_FRACTION = 0.05;
    /** 铺满节奏：60 tick ≈ 3s 铺满单侧 targetDepth（§5.1）。 */
    public static final int DEFAULT_FILL_TICKS = 60;
    /** 动态价差基准 α（相对 mid 的比例，§5.4 先验 0.004）。 */
    public static final double DEFAULT_SPREAD_ALPHA = 0.004;
    /** 动态价差失衡敏感度 β（§5.4 先验 0.6）。 */
    public static final double DEFAULT_SPREAD_BETA = 0.6;
    /** 存货风险系数基量（每 targetDepth 股 → γ·I ≈ 0.001×mid 下移；自适应 scale，见类注释）。 */
    public static final double DEFAULT_GAMMA_BASE = 0.001;
    /** 单侧厚度绝对上界（股，防 AI 大盘 5% 绝对量爆炸，§5.1 mmDepthCapShares）。 */
    public static final long DEFAULT_DEPTH_CAP_SHARES = 5_000_000L;
    /** 存货上限：|I(t)| ≤ mmInventoryCapMult × targetDepth（§5.1，先验 5）。 */
    public static final double DEFAULT_INVENTORY_CAP_MULT = 5.0;
    /** 动态价差上限：S ≤ mmSpreadMaxRel × mid（§5.4 钳制 [tick, 0.05×mid]）。 */
    public static final double DEFAULT_SPREAD_MAX_REL = 0.05;
    /** 档位数区间（3~5 档，档距随档位递增、量随档距衰减，§5.1）。 */
    public static final int DEFAULT_QUOTE_TIERS_MIN = 3;
    public static final int DEFAULT_QUOTE_TIERS_MAX = 5;
    /** floatSharesOf 未注入时的兜底流通股本（股；引擎 MarketEngine.floatSharesOf 注入后即被替换）。 */
    public static final long FALLBACK_FLOAT_SHARES = 1_000_000L;
    /** 成交/过期订单自动从存量估计移除时的订单龄上限（与 Order.AI_ORDER_EXPIRE_TICKS=100 对齐）。 */
    private static final int ORDER_EXPIRE_TICKS = 100;

    // ---------- 每 tick 每资产报价记录（内部滚动存量估计用） ----------
    private record Emission(long expireTick, long qty) {}

    private final String assetId;
    private final String tag;                       // "liq-mm:"+assetId（引擎 cancelByTag 定位本 MM 旧报价）
    private final SeedableRandom rng;               // §7.3 独立子流 derive("liq/mm/"+assetId)

    // 存货状态（§5.1 I(t)/avgCost）
    private long inventory;                         // signed：+ 多 − 空
    private double avgCost;                         // 加权平均建仓成本（审计/估值用，synthetic）
    // 配置（可经 configure/CalibrationParams 覆盖）
    private double mmDepthFraction = DEFAULT_DEPTH_FRACTION;
    private int mmFillTicks = DEFAULT_FILL_TICKS;
    private double mmSpreadAlpha = DEFAULT_SPREAD_ALPHA;
    private double mmSpreadBeta = DEFAULT_SPREAD_BETA;
    private double mmGammaBase = DEFAULT_GAMMA_BASE;
    private long mmDepthCapShares = DEFAULT_DEPTH_CAP_SHARES;
    private double mmInventoryCapMult = DEFAULT_INVENTORY_CAP_MULT;
    private double mmSpreadMaxRel = DEFAULT_SPREAD_MAX_REL;
    private int quoteTiersMin = DEFAULT_QUOTE_TIERS_MIN;
    private int quoteTiersMax = DEFAULT_QUOTE_TIERS_MAX;
    private boolean spreadAdaptive = true;          // liq.spreadAdaptive 子开关（false → 回落快照价差）

    // 审计/出流状态
    private long lastQuoteTick = -1;
    private double lastQuoteMid = 0.0;
    private long restingEstimate;                   // ramp 模式内部：本 MM 仍 resting 的存量估计
    private final Deque<Emission> emissions = new ArrayDeque<>();

    /**
     * @param assetId    资产 ID
     * @param engineSeed 引擎种子（派生 §7.3 "liq/mm/"+assetId 子流，确定性）
     */
    public MarketMakerAgent(String assetId, long engineSeed) {
        this.assetId = assetId;
        this.tag = "liq-mm:" + assetId;
        this.rng = new SeedableRandom(SeedableRandom.mix64(engineSeed ^ ("liq/mm/" + assetId).hashCode()));
        this.inventory = 0;
        this.avgCost = 0.0;
        this.restingEstimate = 0;
    }

    // ================= 只读访问（引擎/测试/持久化接线用） =================

    public String getAssetId() { return assetId; }

    /** 本 MM 报价标签（引擎按此 cancelByTag 撤旧报价重挂；Order.mmTag 对齐位，§5.2/§7.2）。 */
    public String getTag() { return tag; }

    /** signed 存货 I(t)：+ 多（净买超卖）/− 空（净卖超买）。 */
    public long getInventory() { return inventory; }

    /** 加权平均建仓成本（synthetic 审计）。 */
    public double getAvgCost() { return avgCost; }

    public long getLastQuoteTick() { return lastQuoteTick; }
    public double getLastQuoteMid() { return lastQuoteMid; }

    /** 当前 ramp 模式内部存量估计（仅审计/测试观察）。 */
    public long getRestingEstimate() { return restingEstimate; }

    public boolean isSpreadAdaptive() { return spreadAdaptive; }
    public void setSpreadAdaptive(boolean spreadAdaptive) { this.spreadAdaptive = spreadAdaptive; }

    /**
     * 目标单侧厚度（股）：max(一手, round(mmDepthFraction×floatShares))，绝对上界 mmDepthCapShares。
     * LQ-1 验收只比「MM 挂单量 / floatSharesOf」比率，单位在比率中抵消（§5.1/§8.2 换算缝）。
     */
    public long targetDepth(long floatShares) {
        long fs = Math.max(0L, floatShares);
        double raw = mmDepthFraction * fs;
        long lot = PriceDiscoveryEngine.A_SHARE_BOARD_LOT;
        long depth = Math.max(lot, Math.round(raw));
        depth = Math.min(depth, Math.max(lot, mmDepthCapShares));
        return depth;
    }

    /** 存货上限（股，signed 绝对值）：mmInventoryCapMult × targetDepth。 */
    public long inventoryCap(long floatShares) {
        return Math.max(PriceDiscoveryEngine.A_SHARE_BOARD_LOT,
                Math.round(mmInventoryCapMult * targetDepth(floatShares)));
    }

    // ================= 配置注入（CalibrationParams 由引擎侧接线；默认已含规格先验） =================

    public void configure(double spreadAlpha, double spreadBeta, double gammaBase, double depthFraction,
                          long depthCapShares, double inventoryCapMult, int fillTicks,
                          int tiersMin, int tiersMax) {
        if (spreadAlpha > 0) this.mmSpreadAlpha = spreadAlpha;
        if (spreadBeta >= 0) this.mmSpreadBeta = spreadBeta;
        if (gammaBase > 0) this.mmGammaBase = gammaBase;
        if (depthFraction > 0 && depthFraction <= 1.0) this.mmDepthFraction = depthFraction;
        if (depthCapShares > 0) this.mmDepthCapShares = depthCapShares;
        if (inventoryCapMult > 0) this.mmInventoryCapMult = inventoryCapMult;
        if (fillTicks > 0) this.mmFillTicks = fillTicks;
        if (tiersMin >= 1) this.quoteTiersMin = tiersMin;
        if (tiersMax >= tiersMin) this.quoteTiersMax = tiersMax;
    }

    // ================= LQ-4 动态价差（§5.4，正指数勘误版 +β；纯函数、无随机） =================

    /**
     * 动态价差公式（§5.4/LQ-4 与 §8.2 勘误）：{@code S(t) = α·e^(+β·|ln(Vb/Va)|)}。
     * 订单簿失衡大（Vb/Va 远离 1）→ 价差扩（风控）；势均力敌（|ln|→0）→ 价差收敛到 α 底（引成交）。
     * 返回<b>绝对价差</b>（价格单位），钳到 [tick(0.01), mmSpreadMaxRel×mid]。
     *
     * <p>本静态方法同时是引擎侧 ctx.spread / OrderPipeline 影响修正 cap 消费的动态价差唯一实现
     * （§5.4 消费端三处同源；引擎可 import 复用避免分叉）。
     *
     * @param alpha     基准 α（相对 mid 比例，先验 0.004）
     * @param beta      失衡敏感度 β（先验 0.6）
     * @param vb        最优买档量（Vb；缺侧时按 1 侧处理）
     * @param va        最优卖档量（Va）
     * @param mid       P_mid
     * @param maxRel    相对钳制上界（0.05）
     * @return 绝对价差（≥0.01 tick）
     */
    public static double adaptiveSpread(double alpha, double beta, double vb, double va, double mid, double maxRel) {
        double m = mid > 0 ? mid : 1.0;
        double ratio = 1.0;
        if (vb > 0 && va > 0) {
            ratio = vb / va;
        }
        double lnAbs = Math.abs(Math.log(ratio));
        double s = Math.max(0.0, alpha) * m * Math.exp(Math.max(0.0, beta) * lnAbs);
        double cap = Math.max(0.01, maxRel * m);
        return Math.max(0.01, Math.min(cap, s));
    }

    /** 实例版动态价差（用本 agent 参数），从簿快照最优档量 Vb/Va 计算（缺侧按 1 侧）。 */
    public double spreadOf(OrderBook book, double mid) {
        double vb = 0, va = 0;
        if (book != null) {
            OrderBook.OrderBookSnapshot snap = book.getSnapshot();
            if (!snap.bids.isEmpty()) vb = snap.bids.get(0).quantity;
            if (!snap.asks.isEmpty()) va = snap.asks.get(0).quantity;
        }
        return adaptiveSpread(mmSpreadAlpha, mmSpreadBeta, vb, va, mid, mmSpreadMaxRel);
    }

    /** 非自适应回落：快照价差（bestAsk−bestBid），无双边时按 α×mid 底。 */
    public double snapshotSpread(OrderBook book, double mid) {
        if (book != null) {
            OrderBook.OrderBookSnapshot snap = book.getSnapshot();
            if (!snap.bids.isEmpty() && !snap.asks.isEmpty()) {
                double s = snap.asks.get(0).price - snap.bids.get(0).price;
                if (s > 0) return Math.max(0.01, Math.min(mmSpreadMaxRel * mid, s));
            }
        }
        return Math.max(0.01, Math.min(mmSpreadMaxRel * mid, mmSpreadAlpha * mid));
    }

    // ================= 存货记账（引擎 attributeTrade 成交钩子调用，§5.1/§5.2） =================

    /**
     * MM 成交记账入口：更新 I(t)/avgCost，|I| 钳到 inventoryCap。
     *
     * @param signedShares &gt;0 = MM 买入（I 增，扣成本）；&lt;0 = MM 卖出（I 减）
     * @param fillPrice    成交价
     * @param floatShares  流通股本（cap 换算，与 targetDepth 同单位）
     */
    public void onFill(int signedShares, double fillPrice, long floatShares) {
        if (signedShares == 0) return;
        long cap = inventoryCap(floatShares);
        if (signedShares > 0) {
            // MM 买单成交：加权平均建仓成本
            long qty = Math.min(signedShares, Math.max(0, cap - inventory));
            if (qty > 0) {
                if (inventory > 0) {
                    avgCost = (avgCost * inventory + fillPrice * qty) / (inventory + qty);
                } else {
                    avgCost = fillPrice; // 空仓转多仓（或空头回补后转多）：新成本
                }
                inventory += qty;
            }
        } else {
            long qty = Math.min(-signedShares, Math.max(0, inventory + cap));
            inventory -= qty;
            if (inventory <= 0) avgCost = 0.0; // 清仓/转空后成本重置（synthetic 审计简化）
        }
        // 卖出(空头增持)侧 avgCost 语义：合成做市商不追踪空头成本，仅库存量钳制
        if (restingEstimate > 0) {
            restingEstimate = Math.max(0, restingEstimate - Math.abs(signedShares));
        }
    }

    // ================= 出流：内部报价内核（确定性；rng 仅档内微扰，独立子流） =================

    /** 从簿快照取 P_mid：best bid/ask 均值；无双边回落传入 fallback（lastPrice）。 */
    public static double midOf(OrderBook book, double fallback) {
        if (book != null) {
            OrderBook.OrderBookSnapshot snap = book.getSnapshot();
            if (!snap.bids.isEmpty() && !snap.asks.isEmpty()) {
                return (snap.bids.get(0).price + snap.asks.get(0).price) / 2.0;
            }
        }
        return fallback > 0 ? fallback : 0.0;
    }

    /**
     * 单侧出总量在档位间的确定性分配内核。按 3~5 档分布、档距随档位递增、量随档距衰减
     * （首档=最优档首 tick 即挂出），总量合计 ≈ totalQty。纯确定性 + 本 agent 子流微扰。
     *
     * @return 每档 [相对 mid 的符号偏移（正=卖侧）、该档量] 的档位骨架
     */
    private List<double[]> tierSkeleton(long totalQty) {
        int tiers = quoteTiersMin + rng.nextInt(quoteTiersMax - quoteTiersMin + 1);
        double[] w = new double[tiers];
        double sum = 0;
        for (int i = 0; i < tiers; i++) {
            // 量随档距衰减权重：近档 1.0、远档线性减（1 − i×0.16，floor 0.2），加确定性微扰
            double decay = Math.max(0.2, 1.0 - i * 0.16);
            w[i] = decay * (0.9 + rng.nextDouble() * 0.2);
            sum += w[i];
        }
        List<double[]> skeleton = new ArrayList<>(tiers);
        long lot = PriceDiscoveryEngine.A_SHARE_BOARD_LOT;
        long remaining = Math.max(0L, totalQty);
        for (int i = 0; i < tiers; i++) {
            double share = w[i] / sum;
            long qty = Math.round(remaining * share);
            // 一手化
            qty = qty >= lot ? (qty / lot) * lot : 0;
            skeleton.add(new double[]{i, qty});
            remaining -= qty;
        }
        // 余量优先补最优档（首 tick top-of-book 有量）
        if (remaining > 0 && !skeleton.isEmpty()) {
            double[] top = skeleton.get(0);
            long add = (remaining / lot) * lot;
            top[1] += add;
        }
        return skeleton;
    }

    /**
     * 完整报价栈（模式 B：引擎撤旧重挂后每 tick 调用；单侧合计 ≈ targetDepth）。
     *
     * @param mid          P_mid
     * @param spreadAbs    调用方提供的绝对价差 S(t)；≤0 时按本 agent 规则自算
     *                     （spreadAdaptive=true → 动态价差；否则快照价差）
     * @param floatShares  流通股本（targetDepth 换算）
     * @param tick         当前 tick（lastQuoteTick 审计）
     */
    public List<PriceDiscoveryEngine.OrderFlow> quoteFlows(String assetId, double mid, double spreadAbs,
                                                            long floatShares, OrderBook book, long tick) {
        List<PriceDiscoveryEngine.OrderFlow> flows = new ArrayList<>();
        if (mid <= 0) return flows;
        double s = spreadAbs > 0 ? spreadAbs
                : (spreadAdaptive ? spreadOf(book, mid) : snapshotSpread(book, mid));
        s = Math.max(0.01, Math.min(Math.max(0.01, mmSpreadMaxRel * mid), s));
        long depth = targetDepth(floatShares);
        emitStack(flows, assetId, mid, s, depth, depth, tick);
        return flows;
    }

    /**
     * ramp 出流（模式 A：随 agentOrders 同批自动注入；每 tick 只铺 perTickBudget，60 tick 铺满后补足差额，
     * 存量估计经成交(onFill)/超龄(100 tick)递减——有界、不无限堆积）。
     *
     * <p>口径：targetDepth 是<b>单侧</b>目标；存量估计 restingEstimate 与出流预算按<b>双侧合计</b>
     * （2×targetDepth）记账，避免「双侧同铺但只按单侧达标」造成厚度不足一半。
     */
    public List<PriceDiscoveryEngine.OrderFlow> rampedFlows(String assetId, double mid, double spreadAbs,
                                                            long floatShares, OrderBook book, long tick) {
        List<PriceDiscoveryEngine.OrderFlow> flows = new ArrayList<>();
        if (mid <= 0) return flows;
        rollExpired(tick);
        long depth = targetDepth(floatShares);
        if (depth <= 0) return flows;
        long lot = PriceDiscoveryEngine.A_SHARE_BOARD_LOT;
        long desiredTotal = 2L * depth;                       // 双侧合计目标
        // 铺单预算：每 tick ≤ 双侧目标/mmFillTicks；存量已达标时仅补足差额（顶格预算）
        long budgetTotal = Math.max(lot, desiredTotal / Math.max(1, mmFillTicks));
        long deficitTotal = Math.max(0, desiredTotal - restingEstimate);
        long placeTotal = Math.min(budgetTotal, deficitTotal);
        if (placeTotal < 2L * lot) return flows;              // 残余不足一手×2（一买一卖）→ 本 tick 不铺
        double s = spreadAbs > 0 ? spreadAbs
                : (spreadAdaptive ? spreadOf(book, mid) : snapshotSpread(book, mid));
        s = Math.max(0.01, Math.min(Math.max(0.01, mmSpreadMaxRel * mid), s));
        long perSide = placeTotal / 2L;                       // emitStack 的 totalQty 为单侧口径
        long placed = emitStack(flows, assetId, mid, s, perSide, depth, tick);
        if (placed > 0) {
            // 存量估计不得超出双侧目标（成交 onFill / 超龄 rollExpired 会递减）
            restingEstimate = Math.min(desiredTotal, restingEstimate + placed);
            emissions.addLast(new Emission(tick + ORDER_EXPIRE_TICKS, placed));
        }
        return flows;
    }

    /**
     * 按本 MM 参数生成两侧报价并 append 到 flows。
     *
     * @param totalQty    单侧出总量（分配到的 bid 档合计 ≈ ask 档合计 ≈ totalQty）
     * @return 实际挂出总量（两侧合计 = 2×totalQty 量级，扣除 tick 归整损耗）
     */
    private long emitStack(List<PriceDiscoveryEngine.OrderFlow> flows, String assetId, double mid,
                           double s, long totalQty, long depthShares, long tick) {
        if (totalQty <= 0 || mid <= 0) return 0;
        double halfSpread = s / 2.0;
        // 存货项：γ = mmGammaBase × mid / max(depthShares,1)，γ·I 下移/上移双侧（确定性，见类注释）
        double inventoryShift = inventoryGammaShift(mid, depthShares);
        List<double[]> skeleton = tierSkeleton(totalQty);
        long lot = PriceDiscoveryEngine.A_SHARE_BOARD_LOT;
        long placed = 0;
        for (double[] tier : skeleton) {
            int idx = (int) Math.round(tier[0]);
            long qty = (long) Math.round(tier[1]);
            if (qty <= 0) continue;
            // 档距随档位递增：近档 1.0×halfSpread 起，远档 (1 + 0.5×i) 倍
            double offset = halfSpread * (1.0 + 0.5 * idx);
            double bidPrice = mid - offset - inventoryShift;
            double askPrice = mid + offset - inventoryShift;
            // A 股 tick 归整 + 买卖价严格不交叉
            bidPrice = round2(bidPrice);
            askPrice = round2(Math.max(askPrice, bidPrice + PriceDiscoveryEngine.A_SHARE_TICK_SIZE));
            if (bidPrice <= 0 || askPrice <= 0) continue;
            // OrderFlow.size 为 int；qty ≤ 单档量（≤ targetDepth 量级，5%流通股 ≤ mmDepthCapShares=500万）在 int 域内
            flows.add(createOrder(true, bidPrice, (int) qty));
            flows.add(createOrder(false, askPrice, (int) qty));
            placed += qty * 2L;
        }
        lastQuoteTick = tick;
        lastQuoteMid = mid;
        return placed;
    }

    /**
     * γ·I 存货位移（价格单位）：I&gt;0 下移促卖、I&lt;0 上移促买。
     * γ = mmGammaBase × mid / max(depthShares, 1)（每 targetDepth 股 → 下移 0.001×mid），
     * I 受 ±mmInventoryCapMult×targetDepth 钳制 → |γ·I| ≤ 0.5%×mid（cap 内单调、确定性）。
     */
    private double inventoryGammaShift(double mid, long depthShares) {
        if (inventory == 0 || mid <= 0) return 0.0;
        double gamma = mmGammaBase * mid / Math.max(1, depthShares);
        double shift = gamma * inventory;
        // 安全阀：钳到 ±0.5%×mid（inventory cap 之外的理论余量）
        double cap = 0.005 * mid;
        return Math.max(-cap, Math.min(cap, shift));
    }

    /** 超龄（100 tick，与 Order 过期窗口一致）存量从 restingEstimate 滚出。 */
    private void rollExpired(long tick) {
        while (!emissions.isEmpty() && emissions.peekFirst().expireTick() <= tick) {
            Emission e = emissions.pollFirst();
            restingEstimate = Math.max(0, restingEstimate - e.qty());
        }
    }

    /**
     * 仅清报价台账（restingEstimate / emissions / lastQuote*），<b>保留存货 I(t)/avgCost</b>。
     * 供引擎在 §4.6 熔断撤单重挂（cancelAllSynthetic 清空本资产全部合成 resting 单后）与交易日边界调用：
     * 引擎清掉本 MM 旧报价后，本方法让 ramp 台账不再误判「旧报价在册」，下一 tick 即按 5% 目标重新 ramp
     * （重挂恢复期不暂停补单）；已累积的存货头寸不因清台而失真。确定性、无随机。
     */
    public void resetQuoteLedger() {
        restingEstimate = 0;
        emissions.clear();
        lastQuoteTick = -1;
        lastQuoteMid = 0.0;
    }

    /** 全量重建空仓（含存货 I(t)/avgCost；仅读档缺字段/特殊重置场景用，常规清台请用 {@link #resetQuoteLedger()}）。 */
    public void resetInventory() {
        inventory = 0;
        avgCost = 0.0;
        restingEstimate = 0;
        emissions.clear();
        lastQuoteTick = -1;
        lastQuoteMid = 0.0;
    }

    // ================= 持久化（随引擎 "liq" blob，§5.3/§7.2；contains 兜底） =================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("assetId", assetId);
        tag.putLong("inventory", inventory);
        tag.putDouble("avgCost", avgCost);
        tag.putLong("restingEstimate", restingEstimate);
        tag.putLong("lastQuoteTick", lastQuoteTick);
        tag.putDouble("lastQuoteMid", lastQuoteMid);
        tag.putLong("rngState", rng.getState());
        tag.putDouble("rngSpareGaussian", rng.getSpareGaussian());
        ListTag em = new ListTag();
        for (Emission e : emissions) {
            CompoundTag et = new CompoundTag();
            et.putLong("expireTick", e.expireTick());
            et.putLong("qty", e.qty());
            em.add(et);
        }
        tag.put("emissions", em);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("inventory")) inventory = tag.getLong("inventory");
        if (tag.contains("avgCost")) avgCost = tag.getDouble("avgCost");
        if (tag.contains("restingEstimate")) restingEstimate = tag.getLong("restingEstimate");
        if (tag.contains("lastQuoteTick")) lastQuoteTick = tag.getLong("lastQuoteTick");
        if (tag.contains("lastQuoteMid")) lastQuoteMid = tag.getDouble("lastQuoteMid");
        if (tag.contains("rngState")) rng.setState(tag.getLong("rngState"));
        if (tag.contains("rngSpareGaussian")) rng.setSpareGaussian(tag.getDouble("rngSpareGaussian"));
        emissions.clear();
        if (tag.contains("emissions", Tag.TAG_LIST)) {
            ListTag em = tag.getList("emissions", Tag.TAG_COMPOUND);
            for (int i = 0; i < em.size(); i++) {
                CompoundTag et = em.getCompound(i);
                emissions.addLast(new Emission(et.getLong("expireTick"), et.getLong("qty")));
            }
        }
    }

    // ================= 工具 =================

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static PriceDiscoveryEngine.OrderFlow createOrder(boolean isBuy, double price, int size) {
        PriceDiscoveryEngine.OrderFlow order = new PriceDiscoveryEngine.OrderFlow();
        order.timestamp = 0;
        order.isBuy = isBuy;
        order.price = price;
        order.size = size;
        return order;
    }
}
