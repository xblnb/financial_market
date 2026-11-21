# ECON_P2_SPEC — P2 订单簿/ABM 微观结构重构落地实施规格（需求契约，供 t2/t3/t4 实施、t5 验证、t6 评审）

> - 角色：architect（方案架构 / P2 需求规格）为任务 t1 产出。
> - 输入：`ECON_IMPROVEMENT_PLAN.md` §2.3（P2-1~P2-4）+ §5.1（H2/NBT 兼容）+ §6（可播种 PRNG）+ §7（Stylized facts）+ 当前源码审计（本规格对照实际源码给出文件/行号/方法签名/默认参数包）。
> - 已交付前置（本规格的落地基座）：`ECON_P0_SPEC.md`（GARCH、混合锚、SeedableRandom、撤单、死码接线）+ `ECON_P1_SPEC.md`（羊群/前景/处置/BSV/情绪正反馈）。P2 **继承并依赖** P0 的可播种 PRNG 与 P1 的订单流失衡行为基底。
> - 用途：micro-dev 按本规格实现 t2（P2-1 内生定价 + P2-2 ZI/Hawkes）、t3（P2-3 价格冲击/深度）、t4（P2-4 校准注入 + StylizedFactsValidator 扩展）；test-engineer 按本规格实现 t5（P2 stylized facts 验证 + 构建/可复现）；reviewer 按本规格验收 t6。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick 步进内 O(agent)/O(asset) 常数级计算。
> - **可复现性硬约束（继承 P0/A0-3）**：同一种子同行情必须保持。所有新增随机一律使用 `SeedableRandom`（`engine/random/SeedableRandom.java`，SplitMix64），**禁用 `ThreadLocalRandom` / 裸 `new Random()` / `Math.random()`**。
> - **兼容边界硬约束（不动 P0/P1 已定稿语义）**：
>   1. **不修改** `PriceDiscoveryEngine.step()` 的 GBM/GARCH 公式（`PriceDiscoveryEngine.java:203-255`，尤其 `:247-253` 的 `price = price*exp(drift+σ√dt·ε)` 与 `σ_t²` 递归）；GBM/GARCH 仅作为**噪声地板 / 基本面锚**被 P2 消费。
>   2. **不修改** `AICompanyData.updateFairValue()` 公式（`AICompanyData.java:155-181`）。
>   3. **不修改** P1 已定稿的 `HerdingModel`（`engine/behavior/HerdingModel.java`）与 `BehaviorUtility`（`engine/behavior/BehaviorUtility.java`）的语义；P2 只在其**订单流输出之上**叠加 ZI/Hawkes 与价格冲击，不改其内部机制。
> - 范围：仅 P2 订单簿/ABM 微观结构。P0/P1 不在此规格内；不引入 t2-P3 的完整回放 / LLM 多智能体 / 重型 ABM 平台（违背离线/主线程约束）。

---

## 0. 目标与全局验收（对应团队 Goal）

> 让 Financial Market 模组的盘口与行情符合真实微观结构统计规律：**价差/深度分布、大单价格冲击、异质交易者内生定价、ZI/Hawkes 订单流、校准注入**；同时保持 P0 同种子可复现、H2 持久化、不动 P0/P1 已定稿的价格/行为模型语义。完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| A2-0 | 编译通过 | `./gradlew compileJava` 退出码 0 | 每项实施 |
| A2-1 | 可复现性不回归（继承 P0） | 同 `engineSeed` 跑固定 tick，两次成交价序列逐元素一致；新增随机全部走 `SeedableRandom`（全仓 `grep` 无 `Math.random`/`new Random`/`ThreadLocalRandom` 新增调用） | t2~t4 + t5 |
| A2-2 | **异质交易者内生定价（P2-1）** | 三类交易者（fundamentalist/chartist/noise）净订单流驱动成交价；价格从订单簿内生涌现（mid 锚参与下单），GBM 仅作噪声地板；关闭 P2（内生定价 flag=false）时价格路径与 P0/P1 可复现基线逐位一致 | **t2** + t5 |
| A2-3 | **ZI+Hawkes 订单流（P2-2）** | ZI 噪声订单混入 agent 订单（订单流分布真实度提升）；Hawkes 自激励使一次大单提高后续到达强度，出现**订单到达聚集**与流动性剧变；Hawkes 状态随存档可复现 | **t2** + t5 |
| A2-4 | **价格冲击 / 深度曲线（P2-3）** | `matchWithSlippage` 的纯随机滑点替换为**随深度消耗的价格冲击函数**，大单吃多档深度、部分成交、推动价格；可测出大单价格冲击（大单 vs 小单的冲击量级差异显著） | **t3** + t5 |
| A2-5 | **校准注入（P2-4）** | `CalibrationParams` 打包真实统计先验（价差/深度/订单到达/收益率分布），`OrderFlowModel`/`MarketImpact` 按其生成；离线时按真实统计先验生成，无网络依赖 | **t4** + t5 |
| A2-6 | **StylizedFactsValidator P2 扩展** | `computeP2Metrics` 输出价差/深度分布、价格冲击系数、肥尾（尾部幂律 μ_tail）、指数波动聚集（α+β 持久性）指标；对比 P0/P1 基线向现实区间移动 | **t4** + t5 |
| A2-7 | H2 / NBT 持久化不回归 | `OrderFlowModel`/Hawkes 的 RNG 状态随 `engine_kv.engine_main` blob 序列化/恢复；旧存档缺字段用 `contains` 兜底重建，读档不失败 | t2/t3/t4 + t5 |

**验收流程（t5 落地）**：固定 `engineSeed` 跑固定 tick，输出 P0 基线（已有）、P1 后（已有）、P2 后三套 `StylizedFactsValidator` 指标；以「P2 开/关」为开关轴验证内生定价与订单流贡献；以大单/小单对比验证价格冲击；两次同种子运行校验成交价序列哈希一致（A2-1）。

---

## 1. 总设计原则（约束所有实现）

1. **纯增量、可开关**：P2 全部新增 `engine/microstructure/` 与 `engine/calibration/` 包，改动集中在 `OrderBook`、`MultiAgentManager`、`MarketEngine` 的**接入点**。每个子项有独立 `enabled` flag；`P2 全关` 时行为与 P0/P1 可复现基线逐位一致（因子隔离）。
2. **内生定价 + GBM 降级为噪声地板**：不删 `PriceDiscoveryEngine.step` 的 GBM/GARCH，而是让 agent 下单锚从「静态 fairValue 混合锚」演进为「**订单簿 mid 内生锚** + fairValue 基本面锚」，价格由净订单流（三类交易者 + ZI/Hawkes）经订单簿撮合内生涌现；GBM 仅提供收益噪声地板与宏观趋势底。
3. **可复现**：每个新模块用 `SeedableRandom.mix64(engineSeed ^ "<Module>".hashCode())` 派生子流（沿用 P0 模式，见 `HerdingModel` 的 `seedBase`）。`OrderFlowModel`/`MarketImpact` 的随机、参数初始化均走子流，保证同种子同行情。
4. **O(agent)/O(asset) 常数级**：ZI/Hawkes 每资产每 tick 常数次随机判定与事件累积；价格冲击为深度档位 O(档位数)；校准参数为静态常量。禁止 per-tick 线性扫描大集合（除 agent 决策已有的 O(agent)）。
5. **不动 P0/P1 语义**：见文首硬约束清单。凡涉及 `PriceDiscoveryEngine.step` GBM/GARCH 公式、`AICompanyData.fairValue`、`HerdingModel`/`BehaviorUtility` 的改动一律拒绝。

---

## 2. P2-1 异质交易者内生定价（t2，核心，G-01 彻底版）

> 目标：把「外生 GBM 锚 + 弱反馈」改为**三类交易者净订单流驱动价格**。现有 `AgentType.VALUE/RETAIL/MOMENTUM/MARKET_MAKER`（`MultiAgentManager.java:16`）已接近 fundamentalist/chartist/noise/liquidity，做**渐进映射**而非推倒重来。

### 2.1 改动文件
- `src/main/java/com/example/financialmarket/engine/agent/MultiAgentManager.java`
- `src/main/java/com/example/financialmarket/engine/OrderBook.java`（新增 mid/净失衡读取，见 §2.4）

### 2.2 交易者类型映射（`TraderClass`）

在 `MultiAgentManager` 新增枚举，并映射现有 `AgentType`：

```java
public enum TraderClass {
    FUNDAMENTALIST,   // 基本面：VALUE → 依 fairValue 偏离下单（低频）
    CHARTIST,         // 趋势/图表：MOMENTUM → 依订单簿动量/价格趋势下单
    NOISE,            // 噪声/散户：RETAIL → 半随机，供 ZI 基底语义化
    LIQUIDITY_PROVIDER // 流动性/庄家：MARKET_MAKER → 双边挂单、吸单
}
public TraderClass traderClass(AgentType type); // 映射函数（确定性）
```

- `AgentState` 增加字段 `public TraderClass traderClass;`（在 `AgentState(AgentType, long)` 构造内由 `traderClass(type)` 赋值，纯确定性，不消耗随机）。
- **共享份额参数**（默认）：`fundamentalist 0.35 / chartist 0.30 / noise 0.25 / liquidity 0.10`，由 `MultiAgentManager` 构造处按 `engineRng` 分配 agent 数量（复用 `initializeAgents` 的种子派生，`MultiAgentManager.java:154-171`）。

### 2.3 内生定价锚（`OrderBook` 提供 mid）

`OrderBook` 新增读取（`OrderBook.java` 已有 `getSnapshot()`，`OrderBook.java:111`）：

```java
public synchronized double getMidPrice();      // (bestBid+bestAsk)/2；无双边时回落 lastPrice
public synchronized double getNetImbalance();  // Σ(买量)-Σ(卖量) 在最优 N 档内的净失衡（归一化到 [-1,1]）
public synchronized int getDepthLevels();      // 有效档位数（供深度曲线）
```

`MultiAgentManager` 内，内生定价锚替代 P0-4 的「fairValue 单锚」：

```java
// P2-1：内生锚 = anchorWeightMid·mid + anchorWeightFV·fairValueAnchor（按 P0-4 的混合锚算 fairValueAnchor）
double mid = orderBook != null ? orderBook.getMidPrice() : lastPrice;
double endogenousAnchor = endogenousWeightMid * mid + endogenousWeightFV * anchor;
```

- 四个决策方法（`valueDecision/retailDecision/momentumDecision/marketMakerDecision`，`MultiAgentManager.java:302-485`）的挂单价从 `anchor` 换成 `endogenousAnchor`（默认权重 `mid 0.5 / fairValue 0.5`）。
- **动量/趋势信号**改用订单簿净失衡与 mid 变化（`getNetImbalance` + `getMidPrice` 一阶差），替代纯 `priceChange`，使 chartist 真正从 LOB 内生提取信号。

### 2.4 净订单流 → 价格内生（不触碰 GBM 公式）

- `MultiAgentManager.stepAgents` 生成 `newOrders` 后，累加每资产**净失衡** `imbalance = (Σ买单量 − Σ卖单量)/(Σ量)`，存入新方法 `getOrderFlowImbalance(assetId)`（`ConcurrentHashMap<String,Double>`）。
- `MarketEngine.tick` 在撮合前把 `imbalance` 注入 `OrderBook.getNetImbalance()`（由 P2-3 的深度冲击消费），使大单失衡 → 撮合价向失衡方向移动（价格内生）。**不改 `PriceDiscoveryEngine.step` 的 GBM/GARCH 公式**——`updateLastPrice`（`PriceDiscoveryEngine.java:424`）已用 `FEEDBACK_ALPHA=0.02` 回馈 mu，保持不动。

### 2.5 验收（t2 自身）
- P2-1 关闭（`endogenous.enabled=false`）时，四个决策的挂单价回到 `anchor`，与 P0/P1 基线逐位一致（隔离）。
- 固定种子下，某资产净买失衡持续时成交价沿买方向漂移；净卖失衡持续时沿卖方向漂移（内生方向性，A2-2）。
- 同种子两次运行成交价序列一致（A2-1）。

---

## 3. P2-2 ZI 零智能订单流基底 + Hawkes 事件强度（t2，G-11/13）

> 目标：以经验分布随机生成限价/市价单作为「噪声基底」混入 agent 订单，并用自激励 Hawkes 点过程 `λ(t)=λ0+ΣK·e^(−β(t−t_i))` 描述订单到达聚集性（一次大单提高后续下单强度）。

### 3.1 改动文件
- 新增 `src/main/java/com/example/financialmarket/engine/microstructure/OrderFlowModel.java`（纯 Java，除 `SeedableRandom`/`CompoundTag` 外不依赖 Minecraft 运行时）
- 改 `src/main/java/com/example/financialmarket/engine/MarketEngine.java`（L2 后注入，`MarketEngine.java:250-282` 区间接入）
- 改 `src/main/java/com/example/financialmarket/engine/Order.java`（如需标识 ZI 订单来源，可选加 `source` 标记；非必需）

### 3.2 `OrderFlowModel` 数据结构与方法

```java
public class OrderFlowModel {
    // ---- ZI 基底 ----
    private final SeedableRandom ziRng;        // seed = mix64(engineSeed ^ "OrderFlow.ZI".hashCode())
    private final double ziBaseRate;           // 每资产每 tick 基础到达概率（默认 0.02）
    private final double ziBuyProb;            // 无信息对称（默认 0.5）
    private final double ziLotMean;            // 单笔股数均值（默认 40）
    private final double ziLotDispersion;      // 股数几何扩散（默认 60）
    private final boolean ziLimitVsMarket;     // 限价/市价比例（默认 0.8 限价）
    // ---- Hawkes 自激励 ----
    private final SeedableRandom hawkesRng;    // seed = mix64(engineSeed ^ "OrderFlow.Hawkes".hashCode())
    private final double hawkesLambda0;        // 背景强度（默认 0.005）
    private final double hawkesK;              // 触发增益（默认 0.02）
    private final double hawkesBeta;           // 指数衰减（默认 0.15）
    private final int hawkesMaxBranch;         // 保留最近触发次数（默认 3，防无限累加）
    private final Map<String, Deque<Long>> arrivalTimes; // assetId -> 最近 arrival tick
    private long currentTick;

    public OrderFlowModel(long engineSeed);                       // 全默认参数
    public OrderFlowModel(long engineSeed, CalibrationParams cal); // 用校准参数覆盖默认
    public void setCurrentTick(long tick);
    // ---- ZI ----
    public boolean shouldGenerateZiOrder(String assetId);  // ziRng.nextDouble() < ziBaseRate
    public boolean ziIsBuy(String assetId);                // ziRng.nextDouble() < ziBuyProb
    public int ziLotSize(String assetId);                  // 几何采样（泊松近似），≥1
    public boolean ziIsMarket(String assetId);             // ziRng.nextDouble() < ziLimitVsMarket
    public double ziLimitOffset(String assetId, double mid);// 限价偏移（买减/卖加，±tick 步长），ziRng 采样
    // ---- Hawkes ----
    public double intensity(String assetId);                // λ0 + Σ_{i∈arrivalTimes} K·e^{−β(t−t_i)}
    public boolean hawkesArrival(String assetId);          // hawkesRng.nextDouble() < min(1, λ(t)·dt)
    public void recordArrival(String assetId, long tick);  // 入队 + 截断到 hawkesMaxBranch
    // ---- 持久化 ----
    public CompoundTag save();
    public void load(CompoundTag tag);                     // contains 兜底
}
```

- **ZI 实现**：无信息、对称随机；大单概率按经验分布（股数几何分布）——证明「智能不是必须，订单流分布才是关键」。
- **Hawkes 实现**：背景强度 + 历史触发指数衰减；`arrivalTimes` 保留最近 `hawkesMaxBranch` 次，逐项累加 `K·e^{−β(t−t_i)}`；用 `min(1, λ·dt)` 做薄化采样（`dt = 20/24000`）。`recordArrival` 在每次 Hawkes 到达与大单成交后调用。
- **随机全部走子流**：ZI 用 `ziRng`、Hawkes 用 `hawkesRng`，两者独立，同 `engineSeed` 时序确定。

### 3.3 `MarketEngine.tick` 接入（L2 之后、L3 撮合之前）

在 `MarketEngine.tick`（`MarketEngine.java:250-282` 的 agent 订单提交循环之后、`:284` L3 撮合循环之前）插入：

```java
// P2-2：注入 ZI 基底 + Hawkes 订单流（可开关）
orderFlowModel.setCurrentTick(gameTime);
for (String assetId : state.getAssetIds()) {
    if (!orderFlowModel.enabled()) continue;
    OrderBook book = orderBooks.get(assetId);
    if (book == null) continue;
    double mid = book.getMidPrice();
    if (mid <= 0) continue;
    // ZI
    if (orderFlowModel.shouldGenerateZiOrder(assetId)) {
        boolean buy = orderFlowModel.ziIsBuy(assetId);
        int qty = orderFlowModel.ziLotSize(assetId);
        if (orderFlowModel.ziIsMarket(assetId)) {
            // 市价单：以最优对手价吃单（价格冲击由 L3 处理）
            Order m = new Order(ZI_PLAYER_ID, assetId, buy?MARKET_BUY:MARKET_SELL, qty, 0);
            book.placeOrder(m);
        } else {
            double price = mid + (buy? -orderFlowModel.ziLimitOffset(assetId,mid)
                                     : +orderFlowModel.ziLimitOffset(assetId,mid));
            book.placeOrder(new Order(ZI_PLAYER_ID, assetId, buy?LIMIT_BUY:LIMIT_SELL, qty, round2(price)));
        }
        orderFlowModel.recordArrival(assetId, gameTime);
    }
    // Hawkes 自激励到达
    if (orderFlowModel.hawkesArrival(assetId)) {
        orderFlowModel.recordArrival(assetId, gameTime);
        // 生成一笔跟随性订单（方向由 Hawkes 触发时的净失衡决定）
        boolean buy = book.getNetImbalance() >= 0;
        book.placeOrder(new Order(ZI_PLAYER_ID, assetId, buy?LIMIT_BUY:LIMIT_SELL,
                orderFlowModel.ziLotSize(assetId), round2(mid + (buy?-tickSize:tickSize))));
    }
}
```

- `ZI_PLAYER_ID` 用固定 UUID 常量（`UUID.fromString("00000000-0000-0000-0000-00000000ffff")`）标识 ZI 订单，避免污染真实玩家账本（P2 不引入玩家持仓结算）。
- 新增 `private final OrderFlowModel orderFlowModel;` 在 `MarketEngine(seed)` 构造（`MarketEngine.java:91-111`）中初始化：`this.orderFlowModel = new OrderFlowModel(seed, CalibrationParams.DEFAULT);`。

### 3.4 验收（t2 自身）
- `orderflow.enabled=false` 时订单流退化为纯 agent 订单，与 P0/P1 基线一致（隔离）。
- 固定种子下，构造一次大单后 `hawkesArrival` 概率在随后若干 tick 上升（自激励可观察）；`intensity(assetId)` 在 `recordArrival` 后显著高于背景 `λ0`。
- ZI 订单混入后盘口档位数/深度分布更饱满（与 P0 空深对比）。
- 同种子两次运行，`OrderFlowModel` 产生的订单序列（含 Hawkes 时序）一致（A2-1）。

---

## 4. P2-3 价格冲击 / 市场深度曲线（t3，G-10）

> 目标：把 `OrderBook.matchWithSlippage` 的纯随机滑点 `(rng−0.5)·slippage·mid`（`OrderBook.java:63-109`，尤其 `:88`）替换为**随深度消耗的价格冲击函数**，按档位部分成交（市场深度曲线）。大单推动价格、深度有限，符合现实。

### 4.1 改动文件
- 新增 `src/main/java/com/example/financialmarket/engine/microstructure/MarketImpact.java`（价格冲击/深度曲线，纯 Java）
- 改 `src/main/java/com/example/financialmarket/engine/OrderBook.java`（撮合逻辑）
- 改 `src/main/java/com/example/financialmarket/engine/MarketEngine.java`（`SLIPPAGE_FACTOR` 处接入，`MarketEngine.java:89,295,481`）

### 4.2 `MarketImpact` 数据结构与方法

```java
public class MarketImpact {
    private final double kSqrt;        // 平方根冲击系数（默认 0.0015）
    private final double kLinear;      // 线性冲击系数（默认 0.0005）
    private final int maxDepthLevels;  // 参与深度曲线的档位数（默认 5）
    private final double residualNoise; // 残余可复现噪声比例（默认 0，纯确定性冲击）
    private final SeedableRandom rng;  // seed = mix64(engineSeed ^ "MarketImpact".hashCode())

    public MarketImpact(long engineSeed);                       // 默认参数
    public MarketImpact(long engineSeed, CalibrationParams cal); // 校准参数覆盖

    /** 价格冲击：相对 mid 的冲击量 = sign·(kSqrt·√(s/depth0) + kLinear·(s/depth0)) */
    public double impact(double orderSize, double depth0, double midPrice, boolean isBuy);
    /** 第 levelIndex 档（0-based）的价格：买盘走 ask 侧向上、卖盘走 bid 侧向下，按 tickSize 步进 */
    public double levelPrice(int levelIndex, boolean isBuy, double midPrice, double tickSize);
    /** 单档可成交深度（随档位衰减，幂律 depth_k = depth0 · k^−exponent） */
    public int levelDepth(int levelIndex, int depth0, double depthDecayExponent);
}
```

- **冲击函数**（平方根法则，参照现实市场冲击）：`I = sign·(k_sqrt·√(s/d₀) + k_linear·(s/d₀))·mid`，其中 `s` 为吃单量、`d₀` 为最优档深度。小单冲击小（s/d₀→0）、大单冲击随 √(s/d₀) 次线性增长——大单 vs 小单冲击量级差异显著（A2-4）。
- **深度曲线**：`depth_k = depth0·k^−exponent`（默认 `exponent=0.7`），大单按档位**部分成交**（walk the book），每档成交后价格上移一档（`tickSize=0.01`），直到吃满 `maxDepthLevels` 或订单满足。剩余未成交部分留在订单簿或撤销（可配置）。

### 4.3 `OrderBook` 撮合改造（`matchWithSlippage`）

保留方法名与返回类型（避免破坏 `MarketEngine.tick:295` 与 `submitOrder:481` 的调用），但**内部冲击逻辑替换**：

```java
// 旧（OrderBook.java:88-90）：随机滑点，删除
//   double slippage = (rng.nextDouble()-0.5) * slippageFactor * midPrice;
// 新（P2-3）：确定性深度冲击
public synchronized List<Trade> matchWithDepthImpact(MarketImpact impact, double slippageFactor) {
    // 前置清理（保留 P0-5 撤单/过期，OrderBook.java:65-67）
    currentTick++; purgeSide(bids); purgeSide(asks);
    List<Trade> trades = new ArrayList<>();
    while (!bids.isEmpty() && !asks.isEmpty()) {
        double bestBid = bids.firstKey(), bestAsk = asks.firstKey();
        if (bestBid < bestAsk) break;
        // 用 MarketImpact 处理吃单方跨档深度，而非单点 mid+随机滑点
        // 大单按档位部分成交（levelPrice/levelDepth），净失衡注入冲击方向
    }
    return trades;
}
// 兼容入口：matchWithSlippage(double slippageFactor) 委托 matchWithDepthImpact(impact, slippageFactor)
```

- 具体规则：
  - **限价单交叉**（`bestBid >= bestAsk`）：成交价 = 冲击后的对手档价格（非随机 mid）。冲击方向由吃单方向决定，量级由吃单量/最优档深度与净失衡决定（`impact.impact(...)`）。
  - **市价单**（ZI 市价、玩家市价）：walk the book，逐档 `levelPrice/levelDepth` 部分成交，成交均价随吃单加深而恶化（深度曲线）。
  - 移除纯随机滑点对 `rng` 的依赖（保留 `rng` 字段仅用于 `residualNoise`，默认 0 → 撮合完全确定，A2-1 更稳）。
- 新增 `private MarketImpact impactModel;` 由构造注入（`OrderBook(long seed)` 内部创建 `new MarketImpact(seed)`）。

### 4.4 `MarketEngine` 接入
- 新增字段 `private final MarketImpact impactModel;`（构造 `MarketEngine.java:91` 初始化 `new MarketImpact(seed, CalibrationParams.DEFAULT)`）。
- 把 `book.matchWithSlippage(SLIPPAGE_FACTOR)` 两处（`MarketEngine.java:295` 与 `submitOrder:481`）改为 `book.matchWithDepthImpact(impactModel, SLIPPAGE_FACTOR)`。
- `SLIPPAGE_FACTOR`（`MarketEngine.java:89`）保留，作为残余噪声上限（默认 0.002，乘 `impactModel.residualNoise`；默认 residualNoise=0 → 影响为零，纯冲击）。

### 4.5 验收（t3 自身）
- 固定种子下，相同 `mid` 下大单（如 10× 最优档深度）成交均价明显劣于小单（大单价格冲击显著，A2-4）。
- 大单吃透多档深度时，`getSnapshot` 出现深度变薄（深度被消耗），而非 P0 的「虚假满深度」。
- 同种子两次运行成交价序列一致（A2-1）。

---

## 5. P2-4 校准注入（t4，非运行依赖）

> 目标：把 LOBSTER/真实市场的**统计分布参数**（订单到达、价差、深度、收益率分布）硬编码/打包进模组，离线时按真实统计先验生成。比纯手调参数科学。完整回放（需网络）**不引入**（t2-P3(7)）。

### 5.1 改动文件
- 新增 `src/main/java/com/example/financialmarket/engine/calibration/CalibrationParams.java`（静态分布参数，纯 Java，无随机）

### 5.2 `CalibrationParams` 结构

```java
public final class CalibrationParams {
    // 相对价差分布（真实市场先验）：中位相对价差
    public final double spreadMedian;          // 默认 0.002
    // 深度分布：最优档深度中位（相对当量），档位衰减幂律指数
    public final double depthAtBestMedian;     // 默认 500（当量）
    public final double depthDecayExponent;    // 默认 0.7
    // 订单到达：基础到达率 / 大单概率
    public final double orderArrivalRate;      // 默认 0.02（每资产每 tick）
    public final double largeOrderProb;        // 默认 0.05
    // 收益率分布：尾部幂律指数（肥尾）
    public final double returnTailMu;          // 默认 3.0（μ_tail ∈ (2.5,3.5) 目标区间）
    // 波动聚集：GARCH 持久性目标 α+β
    public final double volClusteringPersistence; // 默认 0.95

    public static final CalibrationParams DEFAULT = new CalibrationParams(); // 全默认（打包先验）
    // 各字段 getter（供 OrderFlowModel/MarketImpact 读取）
}
```

- 参数来自公开文献/LOBSTER 聚合统计（非运行依赖，仅先验），在类注释中注明来源性质。`OrderFlowModel` 构造用 `cal.spreadMedian/orderArrivalRate/depthDecayExponent` 覆盖默认；`MarketImpact` 用 `cal.depthDecayExponent/volClusteringPersistence` 对齐深度曲线与波动聚集目标。
- **校准注入方式**：作为**离线先验**打包；`OrderFlowModel`/`MarketImpact` 的 `CalibrationParams` 构造重载统一消费。不引入真实数据文件解析/网络（避免运行依赖）。

### 5.3 验收（t4 自身）
- `CalibrationParams.DEFAULT` 各字段在文献目标区间内（价差中位 ~0.002、深度衰减指数 >0.5、尾部 μ_tail ∈(2.5,3.5)）。
- `OrderFlowModel(seed, CalibrationParams.DEFAULT)` 与 `new OrderFlowModel(seed)` 行为一致（DEFAULT 即默认）。
- `./gradlew compileJava` 通过（A2-0）。

---

## 6. 参数包与调参约束（tuning 建议）

集中为 `MarketEngine` 内常量或独立 `MicrostructureParams` 常量类（推荐 `engine/microstructure/MicrostructureParams.java`，或并入 `CalibrationParams` 的实例字段）。默认值与硬约束：

```
// ===== P2-1 内生定价 =====
endogenous.enabled         = true
endogenous.anchorWeightMid = 0.5     // 订单簿 mid 锚权重
endogenous.anchorWeightFV  = 0.5     // fairValue 混合锚权重（需 ≈1）
endogenous.noiseShare      = 0.25    // noise 交易者占比
endogenous.chartistShare   = 0.30
endogenous.fundamentalShare= 0.35
endogenous.liquidityShare  = 0.10
// ===== P2-2 ZI / Hawkes =====
orderflow.enabled          = true
orderflow.ziBaseRate       = 0.02
orderflow.ziBuyProb        = 0.5
orderflow.ziLotMean        = 40
orderflow.ziLotDispersion  = 60
orderflow.ziLimitVsMarket  = 0.8
orderflow.hawkesLambda0    = 0.005
orderflow.hawkesK          = 0.02    // 硬约束：K·maxBranch < 0.1 防 λ 爆炸
orderflow.hawkesBeta       = 0.15
orderflow.hawkesMaxBranch  = 3
// ===== P2-3 价格冲击 =====
impact.kSqrt               = 0.0015  // 平方根冲击
impact.kLinear             = 0.0005
impact.maxDepthLevels      = 5
impact.depthDecayExponent  = 0.7
impact.tickSize            = 0.01
impact.residualNoise       = 0.0     // 默认纯确定性；>0 时由可播种 rng 采样（仍可复现）
// ===== P2-4 校准先验 =====
calibration.spreadMedian          = 0.002
calibration.depthAtBestMedian     = 500
calibration.depthDecayExponent    = 0.7
calibration.orderArrivalRate      = 0.02
calibration.largeOrderProb        = 0.05
calibration.returnTailMu          = 3.0
calibration.volClusteringPersistence = 0.95
```

- **收敛性依赖**：发散主要来自 Hawkes 强度（`K·maxBranch` 必须 < ~0.1，硬上限钳制）与内生锚权重（`anchorWeightMid+anchorWeightFV≈1`，避免 mid 反馈振荡）。价格冲击系数 `kSqrt/kLinear` 需保证单步冲击 < 涨跌停（复用 `MarketEngine` 的 `getLimitUp/getLimitDown` 校验）。
- **调参策略（t5 固定种子一键重跑对比）**：以 `enabled` 为开关轴（P2 关/开）验证内生定价与订单流贡献；以大单/小单对比验证冲击；若价格跑飞，优先降 `orderflow.hawkesK`、再降 `impact.kSqrt`、再降 `anchorWeightMid`。

---

## 7. 兼容性（继承 P0 §5、P1 §7，P2 增量）

### 7.1 网络包 / UI / Web
- P2 **不新增/不删除任何 packet 字段**。`OrderBookSnapshotPacket`（`network/packets/OrderBookSnapshotPacket.java`）的 bids/asks 结构（price/quantity 列表）**保持不变**——只是深度分布更真实，前端/Web 完全兼容（对齐 `ECON_IMPROVEMENT_PLAN` §5.2）。
- 若要展示 L2 多档/价差，为增量字段（加默认值，向后兼容），可选非必须；P2 首期不做。

### 7.2 H2 / NBT 持久化（对齐 §5.1 P2 行 + H2_MIGRATION.md）
- `engine_kv.engine_main` blob 随 `MarketEngine.save()`（`MarketEngine.java:533`）整体序列化。P2 增量在 `save()` 增加 `tag.put("orderFlow", orderFlowModel.save())`，在 `loadInternal`（`MarketEngine.java:689`）`if (tag.contains("orderFlow")) orderFlowModel.load(...)` 恢复。
- **`OrderFlowModel`/Hawkes 状态**（必须持久化，保证读档后行情连续可复现）：`ziRng`/`hawkesRng` 的 `getState()/setState()`（`SeedableRandom.java:89-92`）+ `arrivalTimes` 各资产最近 `hawkesMaxBranch` 个 tick。存 key：`ziRngState/hawkesRngState/arrivalTimes`。
- **`OrderBook` 状态**：当前不进 NBT/H2（`MarketEngine.save` 未序列化订单簿），读档后为空重建、由 agent+ZI 重新挂单。P2 **沿用该策略**（可接受的已知限制）：订单队列是瞬态、被 agent/ZI 每 tick 重放，持久化价值低且含玩家 UUID/timestamp 复杂；**关键是在 `OrderFlowModel` 持久化其 RNG 状态**，使读档后下一个 tick 的 ZI/Hawkes 到达序列连续、可复现。若后续要跨存档保持深度/未成交单，可在 `MarketEngine.save()` 增加 `OrderBook.save()/load()`（`contains` 兜底），标记为可选增强、本期不做。
- **`MarketImpact`**：纯函数（无状态），无需持久化；`CalibrationParams` 为静态先验，无需持久化。
- **旧存档兼容**：`loadInternal` 用 `tag.contains("orderFlow")` 兜底——旧存档缺该 key 时 `orderFlowModel` 用默认/空重建（复用 `MarketAttachment`「空资产→init」兜底模式），读档不失败（A2-7）。

### 7.3 可复现（P2 × SeedableRandom 结合点）
- `OrderFlowModel`：`ziRng = mix64(engineSeed ^ "OrderFlow.ZI".hashCode())`、`hawkesRng = mix64(engineSeed ^ "OrderFlow.Hawkes".hashCode())`（沿用 P0 派生模式，见 `HerdingModel.java:59`）。所有 ZI/Hawkes 随机判定走对应子流 → **同 `engineSeed` 下 ZI/Hawkes 时序完全确定**。
- `MarketImpact`：`rng = mix64(engineSeed ^ "MarketImpact".hashCode())`；默认 `residualNoise=0` 时撮合纯确定（不消耗随机），比 P0 的随机滑点更可复现。
- **验证**：t5 固定 `engineSeed` 两次运行，成交价序列哈希一致（A2-1）；`enabled=false` 时与 P0/P1 基线逐位一致（隔离）。

---

## 8. 对 t5（P2 stylized facts 验证 + 构建验证）的需求

1. **扩展 `StylizedFactsValidator`**（`engine/validation/StylizedFactsValidator.java`）增加 P2 专属指标（`computeP2Metrics` + `formatReportP2` + `runP2Validation`）：
   - **价差/深度分布**：`meanRelativeSpread`（平均相对价差 `(bestAsk−bestBid)/mid`）、`medianRelativeSpread`、`depthAtBest`（最优档深度）、`depthDecayExponent`（`log(depth_k)` vs `log(level)` 回归斜率，负值）。
   - **价格冲击**：`impactCoefficient`（从 `|Δprice|/price` 对净失衡/吃单量的回归系数）、`largeVsSmallImpact`（大单/小单冲击量级比）。
   - **肥尾**：已有 `kurtosis`，新增 `tailMu`（尾部幂律指数，`P(|r|>x)~x^{−μ_tail}`，目标 ∈(2.5,3.5)）。
   - **指数波动聚集**：`volClusteringPersistence`（GARCH 持久性 α+β 估计，或 `|r|` 自相关滞后 1..20 均值），目标向 `volClusteringPersistence≈0.95` 移动。
   - 对比 P0/P1 基线与 P2 后：价差/深度分布接近校准先验、大单冲击显著、肥尾/波动聚集进一步向现实移动。
   - **开关隔离（A2-2）**：同种子分别跑 P2 关 / 开，对比内生定价方向性、订单流饱满度、冲击量级，证明 P2 贡献。
2. **构建验证**：`./gradlew compileJava` 通过；`./gradlew build` 出包。
3. **整体冒烟**：`./gradlew runServer` 启动无 NPE、tick 推进。
4. **可复现脚本**：固定 `engineSeed` 一键重跑 N tick，成交价序列哈希两次一致（A2-1）。
5. **全仓随机源审计**：`grep -rn "Math.random\|new Random\|ThreadLocalRandom" src/main/java` 不得出现 P2 新增引入的调用（允许既有历史，P2 增量必须用 `SeedableRandom`）。

---

## 9. 实施顺序与依赖（供 scheduler）

```
t1 (本规格) ──→ t2 (P2-1 内生定价 + P2-2 ZI/Hawkes: OrderFlowModel) ──→ t3 (P2-3 价格冲击/深度: MarketImpact + OrderBook)
        └──→ t4 (P2-4 校准注入: CalibrationParams + StylizedFactsValidator P2 扩展) ──→ t5 (验证) ──→ t6 (评审)
```
- **依赖**：t2 依赖 t1；t3 依赖 t2（冲击消费净失衡）；t4 依赖 t2（`CalibrationParams` 被 `OrderFlowModel` 消费）与现有 `StylizedFactsValidator`；t5 依赖 t2/t3/t4；t6 依赖 t5。t2/t3 与 t4 的校准扩展可部分并行（t4 至少需 t2 的 `OrderFlowModel` 接口稳定后合入参数）。
- **优先级**：t2（内生定价 + ZI/Hawkes）为核心，做到位并通过 A2-2/A2-3；t3（价格冲击）其次，通过 A2-4；t4（校准 + 验证器扩展）最后收口 A2-5/A2-6/A2-7。

---

## 10. 与非目标边界（防范围蔓延）

- **不动** `PriceDiscoveryEngine.step` 的 GBM/GARCH 公式（`PriceDiscoveryEngine.java:203-255`，P0 已定稿）。
- **不动** `AICompanyData.updateFairValue` 公式与 `ipoPrice` 量纲（P0 已定稿）。
- **不动** P1 `HerdingModel`/`BehaviorUtility` 内部机制（只在其订单流输出上叠加）。
- **不引入** 完整回放（t2-P3(7)）/ LLM 多智能体 / 重型 ABM 平台。
- 订单队列跨存档持久化（`OrderBook.save/load`）为**可选增强、本期不做**（沿用「读档重建」既有行为，见 §7.2）。
- P2 全部为 O(agent)/O(asset) 常数级；逐项 benchmark 后再并入 tick（对齐 `ECON_IMPROVEMENT_PLAN` §9 风险「P2 内生定价计算量」）。

---

## 11. 输入/来源索引

- P2 定义：`ECON_IMPROVEMENT_PLAN.md` §2.3（P2-1~P2-4）、§5.1（H2/NBT 兼容）、§6（可播种 PRNG）、§7（Stylized facts）
- P0/P1 基座：`ECON_P0_SPEC.md`、`ECON_P1_SPEC.md`
- 代码证据：`OrderBook.java`、`Order.java`、`MarketEngine.java`、`PriceDiscoveryEngine.java`、`MultiAgentManager.java`、`AICompanyData.java`、`SeedableRandom.java`、`MarketSentiment.java`、`HerdingModel.java`、`StylizedFactsValidator.java`、`OrderBookSnapshotPacket.java`、`MarketState.java`
