# A_SHARE_TRADING_SPEC — A 股交易规则需求规格（需求契约，供 t2/t3/t4 实施、t5 验证、t6 评审）

> - 角色：architect（方案架构 / 交易规则需求规格）为任务 t1 产出。
> - 输入：团队 Goal + 当前源码审计（本规格对照实际源码给出文件/方法/字段/默认参数）。已交付前置基座：`ECON_P0_SPEC.md`（GBM/GARCH、SeedableRandom、撤单、死码接线）+ `ECON_P1_SPEC.md`（羊群/前景/处置/BSV/情绪）+ `ECON_P2_SPEC.md`（内生定价、ZI/Hawkes、深度冲击、校准注入）+ H2 持久化（`MarketEngine.save/load` + `engine_kv` NBT blob）。
> - 用途：engine-dev 按本规格实现 t2（交易日历/时段模型）、t3（±10% 涨跌停 + 排队语义）、t4（T+1 + 一手100股 + 价格/时间优先撮合）；test-engineer 按本规格实现 t5（整体验证 + 构建/可复现/持久化）；reviewer 按本规格验收 t6。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick 步进内 O(agent)/O(asset)/O(订单) 常数级计算。**时间权威用引擎 tick 计数器（非墙钟）**，保证同种子可复现。
> - **可复现性硬约束（继承 P0/A0-3）**：同一种子同行情必须保持。所有新增随机一律走 `SeedableRandom`（`engine/random/SeedableRandom.java`，SplitMix64），**禁用 `ThreadLocalRandom` / 裸 `new Random()` / `Math.random()`**。交易日历 / 时段 / 排队 / T+1 / 优先撮合全部为确定性逻辑，不引入任何新随机源。
> - **兼容边界硬约束（不动 P0/P1/P2 已定稿语义）**：
>   1. **不修改** `PriceDiscoveryEngine.step()` 的 GBM/GARCH 公式（`PriceDiscoveryEngine.java:203-254`）与 `updateLastPrice()`（`:424-434`）的 `FEEDBACK_ALPHA=0.02` 回馈；GBM 每 tick 照常步进（即使休市），仅作为噪声地板 / 基本面锚。
>   2. **不修改** P2-3 `MarketImpact`（`engine/microstructure/MarketImpact.java`）与 `OrderBook.walkBook()` 的**深度冲击 / 档位部分成交**作为**价格形成**机制；A 股规则只在其上叠加（a）成交价涨跌停钳制、（b）价格/时间优先的**成交顺序**保证、（c）涨停/跌停排队。
>   3. **不修改** P1 `HerdingModel` / `BehaviorUtility`、P2 `OrderFlowModel`（ZI/Hawkes）内部机制；休市时段仅**跳过其挂单/撮合接入点**，不改内部公式。
> - 范围：仅 A 股交易规则（时间模型、涨跌停、T+1、一手、撮合优先序）。不引入 T+0 改 T+1 之外的回测/复权/ST/创业板 20% 档位等超范围特性。

---

## 0. 目标与全局验收（对应团队 Goal）

> 让 Financial Market 模组落地 A 股风格交易规则：**适配时间模型**（一个交易日=5 分钟实时、交易日内全天连续可交易无午休、每 5 个交易日休市 2 个交易日）+ **真实 A 股规则**（±10% 固定涨跌停 + 涨停/跌停排队语义、T+1 交收、100 股一手整数倍、价格优先 + 时间优先撮合、连续竞价）；同时保持 P0/P1/P2 已交付的价格/行为/微观结构模型语义、同种子可复现、H2 持久化兼容。完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| A3-0 | 编译通过 | `./gradlew compileJava` 退出码 0 | 每项实施 |
| A3-1 | 可复现性不回归（继承 P0） | 同 `engineSeed` 跑固定 tick，两次成交价序列逐元素一致；新增逻辑无任何新随机（全仓 `grep` 无 `Math.random`/`new Random`/`ThreadLocalRandom` 新增调用；交易日历/时段/排队/T+1/撮合优先序为纯确定性函数） | t2~t4 + t5 |
| A3-2 | **时间模型（t2）** | 一个交易日 = 6000 tick（20tps×300s=5 分钟实时）；每 5 个交易日休市 2 个交易日（7 期周期：期 0-4 开市、5-6 休市）；休市时段无新订单、无撮合（L2/L2.5/L3 跳过），开市时段全天连续可交易（无午休）；交易日序号（`tradingDayNumber`）单调递增用于 T+1 与 prevClose 锚定 | **t2** + t5 |
| A3-3 | **±10% 固定涨跌停 + 排队（t3）** | 涨跌停**固定 ±10%**（不再随宏观恐慌 5/7/10% 动态变化），锚定**前一交易日收盘价 prevClose**（非 lastPrice）；涨停价=round2(prevClose×1.10)、跌停价=round2(prevClose×0.90)；成交价钳制在 [跌停,涨停] 区间；涨停时买单/跌停时卖单**排队不成交**（排队语义可测），价格不再越过涨跌停 | **t3** + t5 |
| A3-4 | **T+1 交收（t4）** | 当日买入的股票**当日不可卖**，下一交易日（`tradingDayNumber+1`）起可卖；卖出校验使用「可用（可卖）数量」而非总持仓；交易日边界执行冻结→可用结转；代理/ZI 合成订单流不参与真实账户交收（P2 语义保持） | **t4** + t5 |
| A3-5 | **100 股一手整数倍（t4）** | 买单数量必须是 100 的整数倍（一手=100 股），非 100 整数倍的买单下单被拒绝；卖单允许任意正整数（含零股，对齐真实 A 股「卖可零股」规则） | **t4** + t5 |
| A3-6 | **价格优先 + 时间优先 + 连续竞价（t4）** | 撮合顺序：高价买单/低价卖单优先；同价格先到先成交（时间优先）；订单到达即撮合（连续竞价，无集合竞价）；成交价在涨跌停带内 | **t4** + t5 |
| A3-7 | **隔离（不回归）** | A 股模式关闭（`aShare.enabled=false`）时，行为与 P0/P1/P2 基线一致（交易日切换回落 `gameTime/24000`、涨跌停回落 `lastPrice` 锚 + 宏观动态阈值、无 T+1/一手/新撮合约束） | t2~t4 + t5 |
| A3-8 | **H2 / NBT 持久化兼容** | 交易日历（period/tradingDayNumber/prevClose 锚）、T+1 账本（frozen/available）随 `engine_kv.engine_main` blob 序列化/恢复；旧存档缺字段用 `contains` 兜底重建，读档不失败 | t2~t4 + t5 |

**验收流程（t5 落地）**：固定 `engineSeed` 跑固定 tick 两次，成交价序列哈希一致（A3-1）；以 `aShare.enabled` 为开关轴验证时间模型/涨跌停/T+1/一手/优先撮合的贡献（开 vs 关对比）；用确定性逻辑链复现涨跌停排队、T+1 冻结→可用结转、100 整数倍拒绝、同价先到先成交；`./gradlew build` 出包。

---

## 1. 总设计原则（约束所有实现）

1. **纯增量、可开关**：A 股规则集中在新增 `engine/time/` 与 `engine/settlement/` 包 + `MarketEngine`/`OrderBook`/`Order`/`PlayerEconomy` 的接入点。全局开关 `aShare.enabled`（默认 true）；关闭时回落 P0/P1/P2 基线（因子隔离，A3-7）。
2. **时间权威 = 引擎 tick 计数器**：不用墙钟、不用 `gameTime/24000`（Minecraft 昼夜）作为交易时段依据，改用一个单调递增的交易日历 `TradingCalendar`（纯函数 of tick）。保证同 tick 数 → 同时段 → 同行情，可复现。
3. **不改 GBM/GARCH 公式**：`PriceDiscoveryEngine.step()` 每 tick 照常步进（休市亦步进，产生「隔夜跳空」，属基本面演化）；A 股规则只控制**交易会话门控**（何时允许挂单/撮合）与**成交价钳制/成交顺序**。
4. **定价 vs 成交序分离**：A 股「价格/时间优先」约束**成交顺序**（哪些盘口单先成交、按什么顺序）；P2-3 深度冲击仍决定**成交价形成**（print price）。两者正交叠加，不互相破坏。
5. **确定性**：交易日历、时段门控、涨跌停带、排队、T+1 结转、一手校验、优先撮合全部为确定性逻辑，不消耗任何随机源（可复现）。
6. **O(agent)/O(asset)/O(订单) 常数级**：日历为 O(1)；涨跌停带为 O(1)；T+1 账本为 HashMap O(1) 读写、交易日边界一次遍历；一手/优先校验为 O(1)。禁止 per-tick 线性扫描大集合。

---

## 2. AS-1 交易日历 / 时段模型（t2）

> 目标：把「交易日 = `gameTime/24000`（Minecraft 昼夜）」改为「一个交易日 = 5 分钟实时（6000 tick），每 5 个交易日休市 2 个交易日，交易日内全天连续可交易（无午休）」。

### 2.1 改动文件
- 新增 `src/main/java/com/example/financialmarket/engine/time/TradingCalendar.java`（纯 Java，无 Minecraft 运行时依赖，除 `CompoundTag` 用于持久化）
- 改 `src/main/java/com/example/financialmarket/engine/MarketEngine.java`（交易日切换逻辑 `:281-298`、会话门控 `tick` 内 L2/L2.5/L3、`performTPlus1Settlement` `:494-499`、`save/load`）
- 改 `src/main/java/com/example/financialmarket/engine/PriceDiscoveryEngine.java`（仅当日切换重置/prevClose 锚定接入，**不改 GBM 公式**）

### 2.2 `TradingCalendar` 数据结构与方法

```java
public class TradingCalendar {
    // ---- 时段参数（固定常量，可复现） ----
    public static final long TRADING_DAY_TICKS = 6000;   // 5 分钟实时 @ 20tps（300s×20）
    public static final int  TRADING_DAYS_PER_WEEK = 5;  // 每周期开市交易日数
    public static final int  CLOSED_DAYS_PER_WEEK  = 2;  // 每周期休市日数
    public static final int  WEEK_PERIODS = TRADING_DAYS_PER_WEEK + CLOSED_DAYS_PER_WEEK; // =7

    private long period;          // 当前期序号 = tick / TRADING_DAY_TICKS（单调）
    private long tradingDayNumber;// 已开市的交易日序号（只在开市期边界 +1，单调，用于 T+1/prevClose）
    private boolean marketOpen;   // 本期是否开市

    public TradingCalendar();                             // period=0, tradingDayNumber=1, open=true
    /** 每 tick 推进：由外部以引擎 tick 计数器调用；纯函数演化，无随机。 */
    public void advance(long tick);
    public boolean isMarketOpen();
    public long getTradingDayNumber();
    public long getPeriod();
    /** 是否为「本期期首」边界（用于日重置与 T+1 结转）。 */
    public boolean isTradingDayStart();
    /** 是否为「本期期末 / 进入休市」边界（用于 prevClose 落定）。 */
    public boolean isSessionEnd();

    // ---- 持久化 ----
    public CompoundTag save();
    public void load(CompoundTag tag);   // contains 兜底
}
```

- **期序号**：`period = tick / TRADING_DAY_TICKS`；**开市判定**：`marketOpen = (period % WEEK_PERIODS) < TRADING_DAYS_PER_WEEK`（期 0-4 开、期 5-6 休，首个期开市）。
- **tradingDayNumber**：仅在「开市期」边界递增（`isTradingDayStart()` 为 true 时 `tradingDayNumber++`）。休市期不递增。
- **deterministic**：advance 只读入 tick，无随机，同 tick 数 → 同时段 → 同序号（可复现）。

### 2.3 `MarketEngine` 接入（会话门控）

用引擎自增 tick 计数器 `tickCounter`（`MarketEngine.java:267`）作为时间权威（不用 `gameTime`，因为 `gameTime` 可被 `/time` 改动而破坏时段确定性）。

- 构造 `MarketEngine` 时初始化 `private final TradingCalendar calendar = new TradingCalendar();`（或从 `loadInternal` 恢复）。
- `tick()` 开头：`calendar.advance(tickCounter);`
- **交易日边界**（替代 `:281-298` 的 `newDay = gameTime / TICKS_PER_DAY` 逻辑）：
  - `if (calendar.isSessionEnd())`：对每资产落定 `prevClose = 当日收盘价`（A3-3 锚），供次日涨跌停与指标。
  - `if (calendar.isTradingDayStart())`：执行 T+1 结转（委托 t4 的 T+1 账本）+ 每资产日重置（`dailyTrades.clear()`、`todayOpen/high/low/volume` 清零、`hasTodayTrade=false`、熔断复位）+ 清空上一交易日盘口遗留队列（可选）。
- **休市门控**：`if (!calendar.isMarketOpen()) { /* 跳过 L2 智能体挂单、L2.5 ZI/Hawkes 注入、L3 撮合 */ return; }`（放在宏观/价格步进之后、L2 之前；`priceEngine.step()` 照常在休市时执行，见 §1.3）。
  - 具体：`tick()` 内 `priceEngine.step()` 等宏观/价格演化**保留**（休市也走，隔夜跳空）；从「L2 智能体决策」到「L3 撮合」「盘口推送」整段在休市时跳过。
- **submitOrder（玩家下单）**：休市时直接拒绝（`calendar.isMarketOpen()==false` → 提示「休市中」并 return）。
- 保留 `TICKS_PER_DAY=24000`（`MarketEngine.java:79`）**仅**作为 P0 基线的回落路径（A3-7）；A 股模式开启时不再用其作为交易日边界。

### 2.4 验收（t2 自身）
- `aShare.enabled=true` 时，固定 tick：期序号/开市判定符合「5 开 2 休」；休市期 `submitOrder` 被拒、tick 无撮合、无新单入簿。
- 交易日内全天连续可交易（无午休）：开市期 6000 tick 内每个 tick 均可撮合/挂单。
- 休市时段 `priceEngine` 的 GBM 仍步进（隔夜跳空可测），`lastPrice` 不变（无成交）。
- 同种子两次运行：日历序列（period/开市/序号）逐元素一致（A3-1）。
- `aShare.enabled=false` 时，交易日切换回落 `gameTime/24000`，与 P0/P1/P2 基线一致（A3-7）。

---

## 3. AS-2 ±10% 固定涨跌停 + 涨停/跌停排队语义（t3）

> 目标：把当前「随宏观恐慌动态 5/7/10%、锚 lastPrice」的涨跌停改为**固定 ±10%、锚前一交易日收盘价 prevClose**，并在订单簿实现**涨停/跌停排队**（涨停时买单排至涨停价但无卖盘不成交、价格冻结）。

### 3.1 改动文件
- 改 `src/main/java/com/example/financialmarket/engine/MarketEngine.java`（下单校验 `:364-367` 与 `:610-616`、`getLimitUp/getLimitDown` 接入）
- 改 `src/main/java/com/example/financialmarket/engine/OrderBook.java`（撮合成交价涨跌停钳制、涨停/跌停排队保留）
- 新增 `src/main/java/com/example/financialmarket/engine/limits/PriceLimitBand.java`（纯 Java：涨停/跌停带计算 + 校验 + 钳制，可复现）

### 3.2 `PriceLimitBand` 数据结构与方法

```java
public final class PriceLimitBand {
    public static final double LIMIT_RATIO = 0.10;  // 固定 ±10%（A 股主板）
    public static final double TICK_SIZE   = 0.01;  // 报价最小变动 0.01

    /** 涨停价 = round2(prevClose × (1+LIMIT_RATIO)) */
    public static double limitUp(double prevClose);
    /** 跌停价 = round2(prevClose × (1-LIMIT_RATIO)) */
    public static double limitDown(double prevClose);
    /** 报价是否落在涨跌停带 [limitDown, limitUp] 内（含端点，端点=涨停/跌停价可下单排队） */
    public static boolean withinBand(double price, double limitUp, double limitDown);
    /** 钳制：把成交价/报价夹到 [limitDown, limitUp]（越界取端点，A3-3 价格不再越过涨跌停） */
    public static double clamp(double price, double limitUp, double limitDown);
    private static double round2(double v); // Math.round(v*100)/100.0
}
```

- 全部 static 纯函数，无状态、无随机 → 可复现。
- **端点含界**：涨停价 / 跌停价本身可下单（排队），`withinBand` 为闭区间。

### 3.3 涨跌停锚定与校验改造

- **锚定 prevClose**：每资产用 `AssetSnapshot.prevClose`（`AssetSnapshot.java:6`）作为涨跌停基准，**不再用 `lastPrice`**（当前 `MarketEngine.java:610`、`:364-367` 均用 lastPrice，须改）。
- `MarketEngine` 下单校验（两处：agent 单 `:364-367`、玩家 `submitOrder` `:610-616`）改为：
  ```java
  double prevClose = state.getAsset(assetId).getPrevClose();
  double up = PriceLimitBand.limitUp(prevClose);
  double down = PriceLimitBand.limitDown(prevClose);
  if (!PriceLimitBand.withinBand(order.getLimitPrice(), up, down)) { /* 拒绝，提示超出涨跌停 */ return; }
  ```
- `PriceDiscoveryEngine.getLimitUp/getLimitDown`（`:453-459`）在 A 股模式返回固定 `PriceLimitBand.LIMIT_RATIO`（`0.10`）；关闭模式回落 `macroEnv.getLimitUpThreshold()`（基线，A3-7）。**不改 MacroEnvironment 动态阈值方法本身**，只改消费端。
- 保留对 `lastPrice` 的既有限制作为**安全钳制**（防止极端漂移），但涨跌停带以 prevClose 为准。

### 3.4 涨停/跌停排队语义（OrderBook）

- **排队**：当 `bestAsk`（涨停时卖盘空）为 null 时，涨停价买单自然在 `bids` 队列中**排队不成交**（P0/P1/P2 现有撮合只吃 `bestBid>=bestAsk` 的交叉，无卖盘即不撮合——排队已隐式成立）。本规格要求显式保证并使其**可测**：
  - 涨停时，新买单挂在涨停价入 `bids`（`placeOrder` 不拒绝，`withinBand` 含端点），持续累积成队列。
  - 一旦有卖单（≤涨停价）到达，撮合按价格/时间优先吃掉**最老**的涨停买单（先到先成交）。
  - 跌停时对称：跌停价卖单排队，等待 ≥跌停价的买单吃掉最老卖单。
- **成交价钳制**：`OrderBook.walkBook` 计算的 `fillPrice`（`OrderBook.java:143`）在 A 股模式经 `PriceLimitBand.clamp(fillPrice, up, down)` 钳制——价格永远落在 [跌停,涨停]，不会越过涨跌停打印（A3-3）。
- **可测队列**：提供确定性观察（供 t5）——同种子下，构造「涨停无卖盘」场景，涨停买单挂单后 `getSnapshot()` 的 bids 顶端=涨停价、量随挂单累积；`lastPrice` 稳定在涨停价不越界。

### 3.5 验收（t3 自身）
- 固定种子：某资产 prevClose=P，涨停价=round2(P×1.10)、跌停价=round2(P×0.90)；涨停价买单挂入排队不成交、成交价不越过涨停价。
- 构造「涨停无卖盘」：买入持续挂至涨停，`lastPrice` 停在涨停价；注入一笔涨停价卖单后，最老买单先成交（时间优先，A3-6 交集）。
- 越界报价（>涨停或 <跌停）被拒绝，代理单与玩家单一致。
- 同种子两次运行：成交价/排队序列一致（A3-1）。
- `aShare.enabled=false`：涨跌停回落 `lastPrice` 锚 + 宏观动态阈值，与基线一致（A3-7）。

---

## 4. AS-3 T+1 交收（t4）

> 目标：落地真实 A 股 T+1——当日买入的股票当日不可卖，下一交易日（`tradingDayNumber+1`）起可卖。当前 `SettlementSystem` 与 `performTPlus1Settlement` 基本为空壳（`SettlementSystem.addTrade` 从未被调用；`performTPlus1Settlement` 仅清空 `pendingSettlements`）。

### 4.1 改动文件
- 新增 `src/main/java/com/example/financialmarket/engine/settlement/T1SettlementLedger.java`（纯 Java，可持久化；玩家→资产→冻结/可用数量账本）
- 改 `src/main/java/com/example/financialmarket/engine/SettlementSystem.java`（可选：并入 T1 账本或标注废弃）
- 改 `src/main/java/com/example/financialmarket/engine/MarketEngine.java`（撮合成交后按 `order.playerId` 记账；交易日边界结转）
- 改 `src/main/java/com/example/financialmarket/network/packets/PlaceOrderPacket.java`（卖出下单时校验可用数量）
- 改 `src/main/java/com/example/financialmarket/attachment/PlayerEconomy.java`（可选：`getAvailableShares` 委托账本；**不改 Holding 序列化格式**，避免破坏旧档兼容）

### 4.2 交收语义

- **成交记账**：撮合产生的每笔成交，按**真实账户**归属结算：
  - **买入成交**（买单所属 playerId 映射到真实 `PlayerEconomy`）：成交时**现金扣减**（或冻结）；股票计入 `frozen`（当日冻结，不可卖）。
  - **卖出成交**：成交时**现金入账**；股票从 `available`（可用可卖）扣减。
  - **代理/ZI 合成订单流**（playerId 为随机 UUID / `ZI_PLAYER_ID`，`MarketEngine.java:93`）：**不参与真实账户交收**（P2 语义保持）——它们是抽象订单流驱动，无真实持仓。
- **可用 vs 冻结**：
  - `available(玩家,资产)` = 可卖数量（前一交易日及更早买入、已解冻）。
  - `frozen(玩家,资产)` = 当日买入、当日不可卖。
  - 卖出下单校验：`available(玩家,资产) >= 卖出数量`，否则拒绝（提示「可用不足 / T+1 冻结」）。
- **交易日边界结转**（`TradingCalendar.isTradingDayStart()`，每开市期首）：`available += frozen; frozen = 0;`（所有上一交易日买入解冻可卖）。
- **成本基准**：`PlayerEconomy.addHolding/removeHolding` 的 avgCost 逻辑保留不动；T+1 账本只管**数量可用性**，与成本基准解耦。

### 4.3 `T1SettlementLedger` 数据结构

```java
public class T1SettlementLedger {
    // 玩家UUID -> 资产 -> 数量
    private final Map<UUID, Map<String, Integer>> frozen;    // 当日买入冻结
    private final Map<UUID, Map<String, Integer>> available; // 可卖可用

    public void onBuyFill(UUID playerId, String assetId, int qty);   // frozen += qty；现金扣减由 MarketEngine 结算
    public void onSellFill(UUID playerId, String assetId, int qty);  // available -= qty；现金入账
    public boolean canSell(UUID playerId, String assetId, int qty);  // available >= qty
    public int getAvailable(UUID playerId, String assetId);
    public int getFrozen(UUID playerId, String assetId);
    /** 交易日边界结转：available += frozen; frozen = 0（对全部玩家/资产） */
    public void settleTPlus1();

    public CompoundTag save();
    public void load(CompoundTag tag);   // contains 兜底，旧档重建为空
}
```

- 均为 O(1) HashMap 读写；`settleTPlus1` 每交易日一次全遍历（O(玩家×资产)，非 per-tick）。
- 确定性：不消耗随机。
- **与撮合接线**：`Trade` 目前无玩家归属（`Trade.java`）。实现时可在 `MarketEngine` 撮合后，按产生该成交的 `Order.playerId` 记账（`Order.java:14` 已带 playerId）。代理/ZI 单的 playerId 不在真实经济 → 跳过（见 §4.2）。

### 4.4 验收（t4 自身）
- 同一交易日内：买入→立即可查 `frozen` 增加、`available` 不增；同一交易日内尝试卖出该股被拒（T+1）。
- 下一交易日边界（`isTradingDayStart`）后：`available` 增加、`frozen` 清零；此时可正常卖出。
- 卖出校验：`available < 卖出量` 拒绝；`available >= 卖出量` 放行并按价格/时间优先撮合。
- 同种子两次运行：T+1 账本演化序列一致（A3-1）。
- 旧存档缺 `t1Ledger` key：读档用空账本重建、不失败（A3-8）。

---

## 5. AS-4 100 股一手整数倍（t4）

> 目标：买单必须为 100 的整数倍（一手=100 股）；卖单允许任意正整数（含零股，对齐真实 A 股「买入整手、卖出可零股」）。

- **校验点**：
  - 玩家买单：`PlaceOrderPacket` 处理（`PlaceOrderPacket.java:34-53`）与 `submitOrder`（`MarketEngine.java:603`）校验 `quantity > 0 && quantity % 100 == 0`，否则拒绝并提示「买入须 100 股整数倍」。
  - 玩家卖单：`quantity > 0` 即合法（但须过 T+1 可用校验，见 §4）。
  - 代理 / ZI 合成订单：**不强制**一手（合成订单流保持 P2 语义，见 §1.3 / §4.2；若强制会改变 P2-2 订单流分布）。
- 提供常量 `public static final int BOARD_LOT = 100;`（建议置于 `PriceLimitBand` 或新 `TradingParams`）。
- 验收：100 整数倍买单放行、101/150/199 等被拒；卖单 1~N 任意正整数放行（配合可用校验）；同种子两次一致。

---

## 6. AS-5 价格优先 + 时间优先撮合 + 连续竞价（t4）

> 目标：撮合顺序为价格优先（高价买单/低价卖单优先）、时间优先（同价先到先成交）；订单到达即撮合（连续竞价）。当前 `OrderBook` 用 `TreeMap`（bids 降序取最高、asks 升序取最低 → 价格优先）+ `ConcurrentLinkedQueue` FIFO（同价时间优先）**已基本满足**，本规格要求显式保证 + 可测。

### 6.1 现有结构核对（已满足项）
- 价格优先：`OrderBook.java:12-13`（bids `Comparator.reverseOrder()`、asks 升序），撮合循环取 `bestBid/bestAsk`（`:184-187`）→ 高价买/低价卖优先。
- 时间优先：每个价格档 `ConcurrentLinkedQueue` FIFO（`placeOrder` `:73` 追加尾部，`walkBook` `:146` `pQueue.peek()` 取队首最老）→ 同价先到先成交。
- 连续竞价：撮合在每 tick（`MarketEngine.tick` L3 `:414-449`）与每笔下单（`submitOrder` `:618`）即时执行 → 订单到达即撮合。

### 6.2 本规格要求的增量保证
- **显式确认**：在 `OrderBook` 注释/文档标注价格-时间优先已由数据结构保证；确保 `walkBook` 按同价 FIFO 顺序逐单成交（已是 `pQueue.peek()`）。
- **成交价钳制**：`walkBook` 的 `fillPrice`（`OrderBook.java:143`）经 `PriceLimitBand.clamp` 夹到涨跌停带（§3.4），连续竞价成交价不越界。
- **涨停/跌停排队**下同价先到先成交：涨停买单队列中，新卖单到后最老买单先成交（时间优先，§3.4）。

### 6.3 验收（t4 自身）
- 同价两个买单（先 A 后 B）：后续卖单成交时 A 先于 B 成交（时间优先）。
- 不同价两个买单（高价 X、低价 Y）：成交时 X 先于 Y（价格优先）。
- 成交价始终在 [跌停,涨停] 带内（A3-6）。
- 同种子两次运行：成交序列（含顺序）一致（A3-1）。

---

## 7. 参数包与调参约束

集中为 `MarketEngine` 内常量或独立 `TradingParams` 常量类。默认值与硬约束：

```
// ===== A 股时间模型（AS-1） =====
aShare.enabled               = true
calendar.tradingDayTicks     = 6000   // 一个交易日 = 5 分钟实时 @20tps（300s×20）
calendar.tradingDaysPerWeek  = 5
calendar.closedDaysPerWeek   = 2
// ===== 涨跌停（AS-2） =====
limits.ratio                 = 0.10   // 固定 ±10%
limits.tickSize              = 0.01
limits.anchor                = PREV_CLOSE   // 前一交易日收盘价
// ===== T+1（AS-3） =====
settlement.tPlus1            = true
settlement.agentSynthetic    = SKIP   // 代理/ZI 合成订单流不参与真实账户交收
// ===== 一手（AS-4） =====
lots.boardLot                = 100
lots.enforceBuyMultipleOf100 = true   // 只强制买；卖允许零股
```

- **隔离**：`aShare.enabled=false` 时全部回落基线（交易日=`gameTime/24000`、涨跌停=宏观动态阈值锚 lastPrice、无 T+1/一手/新撮合约束），A3-7。
- **不引入**：ST 5%、创业板/科创板 20%、北交所 30% 档位；集合竞价（仅连续竞价）；除权除息复权。

---

## 8. 兼容性（继承 P0/P1/P2 各节，A 股增量）

### 8.1 网络包 / UI / Web
- **不新增/不删除任何 packet 字段**。`OrderBookSnapshotPacket` 的 bids/asks 结构（price/quantity 列表）保持不变——只是涨停排队/休市时深度分布不同，前端/Web 完全兼容。
- 若要展示「休市/涨停/跌停/T+1 冻结」状态，为增量字段（加默认值，向后兼容），可选非必须，A 股首期不做。
- `PlaceOrderPacket` 仅**增加服务端校验**（一手、T+1 可用、休市拒绝），不改线格式。

### 8.2 H2 / NBT 持久化（对齐 H2_MIGRATION.md）
- `engine_kv.engine_main` blob 随 `MarketEngine.save()`（`MarketEngine.java:670-738`）整体序列化。A 股增量在 `save()` 增加 `tag.put("tradingCalendar", calendar.save())` + `tag.put("t1Ledger", t1Ledger.save())`；`loadInternal`（`:834`）用 `if (tag.contains(...)) ... load(...)` 恢复，旧档缺字段用默认/空重建（读档不失败，A3-8）。
- **`TradingCalendar` 状态**（必须持久化，保证读档后时段/序号连续可复现）：`period` / `tradingDayNumber` / `marketOpen`。
- **`T1SettlementLedger` 状态**（必须持久化，保证读档后 T+1 冻结/可用不丢失）：`frozen` / `available` 两张表（playerUUID→asset→qty）。
- **prevClose 锚定**：`AssetSnapshot.prevClose` 已在 `state` blob 持久化（`MarketEngine.java:681` `state.save()`）；A 股在日界落定 prevClose 后随 state 保存。旧存档缺 prevClose 时 `AssetSnapshot` 构造已默认 `prevClose=lastPrice`（`AssetSnapshot.java:21`），读档不失败。
- **`PriceLimitBand`**：纯 static 函数，无状态，无需持久化。

### 8.3 可复现（A 股 × 确定性）
- 交易日历 `period/tradingDayNumber/marketOpen` = `f(tick)` 纯函数；涨跌停带、排队、T+1 结转、一手校验、优先撮合全部无随机 → 同 `engineSeed` + 同 tick 数完全确定。
- **验证**：t5 固定 `engineSeed` 两次运行，成交价/排队/T+1 序列哈希一致（A3-1）；`aShare.enabled=false` 与 P0/P1/P2 基线逐位一致（隔离，A3-7）。

---

## 9. 对 t5（整体验证 + 构建验证）的需求

1. **构建验证**：`./gradlew compileJava` 通过；`./gradlew build` 出包（A3-0）。
2. **可复现脚本**：固定 `engineSeed` 一键重跑 N tick（跨越休市/交易日边界），成交价/排队序列哈希两次一致（A3-1）。
3. **时间模型验证（A3-2）**：确定性逻辑链或静态核对——期序号/开市判定符合「5 开 2 休」；休市期无撮合/无新单；开市期全天连续；`tradingDayNumber` 只在开市期边界递增。
4. **涨跌停/排队验证（A3-3）**：确定性逻辑链——prevClose 锚定涨停/跌停价；涨停无卖盘买单排队、成交价不越界；注入涨停卖单后最老买单先成交。
5. **T+1 验证（A3-4）**：确定性逻辑链——当日买入计入 frozen、当日不可卖；交易日边界 frozen→available；卖出校验可用量。
6. **一手/优先序验证（A3-5/A3-6）**：100 整数倍买单放行、非整数倍拒绝；同价先到先成交、不同价高价/低价优先；成交价在涨跌停带内。
7. **隔离验证（A3-7）**：`aShare.enabled=false` 回落基线；对比 A 股开/关成交路径差异，证明贡献。
8. **持久化验证（A3-8）**：静态核对 `save/load` 接线 + `contains` 兜底；旧档缺 `tradingCalendar/t1Ledger` 读档不失败。
9. **随机源审计**：`grep -rn "Math.random\|new Random\|ThreadLocalRandom" src/main/java` 不得出现 A 股规则新增引入的调用（允许既有历史；A 股增量必须零新随机）。

---

## 10. 实施顺序与依赖（供 scheduler）

```
t1 (本规格) ──→ t2 (交易日历/时段: TradingCalendar + MarketEngine 门控)
        └──→ t3 (±10% 涨跌停 + 排队: PriceLimitBand + OrderBook/校验改造) ──→ t4 (T+1 + 一手 + 优先撮合: T1SettlementLedger + 校验) ──→ t5 (验证) ──→ t6 (评审)
```
- **依赖**：t2 依赖 t1；t3 依赖 t2（prevClose 锚定 / 日界落定由 t2 的日历边界驱动）；t4 依赖 t2（T+1 结转用 `isTradingDayStart`）与 t3（涨停排队与一手/优先序交互）；t5 依赖 t2/t3/t4；t6 依赖 t5。
- **优先级**：t2（时间模型）为核心基座（涨跌停锚、T+1 结转、日重置都依赖它）；t3（涨跌停/排队）其次；t4（T+1/一手/优先序）收口。
- **注**：A 股规则新增随机为零（全部确定性），不新增 SeedableRandom 子流；若实现需要任何随机，一律走既有 `SeedableRandom` 派生模式（P0 §6）并登记，否则违反 A3-1。

---

## 11. 与非目标边界（防范围蔓延）

- **不动** `PriceDiscoveryEngine.step()` 的 GBM/GARCH 公式（`PriceDiscoveryEngine.java:203-254`）与 `updateLastPrice`（`:424-434`）；休市时 GBM 照常步进（隔夜跳空属基本面演化）。
- **不动** `MarketImpact`（P2-3）与 `walkBook` 深度冲击作为**价格形成**；A 股只加成交价钳制与成交序保证。
- **不动** P1 `HerdingModel`/`BehaviorUtility`、P2 `OrderFlowModel`（ZI/Hawkes）内部机制；休市仅跳过接入点。
- **不引入**：ST/创业板/科创板/北交所不同涨跌停档位；集合竞价；复权/除权除息；做空/融资融券 T+0 特例；历史回放。
- 全部分支为 O(agent)/O(asset)/O(订单) 常数级；无 per-tick 线性扫描（除交易日边界一次结转）。
- A 股模式**默认开启**但**可关**（`aShare.enabled=false` 回落基线，保证既有行为与验证不回归）。

---

## 12. 输入/来源索引

- 团队 Goal（时间模型 + 四项 A 股规则 + 保持 P0/P1/P2 + 可复现 + H2）
- 代码证据：`MarketEngine.java`、`OrderBook.java`、`Order.java`、`Trade.java`、`PriceDiscoveryEngine.java`、`AssetSnapshot.java`、`SettlementSystem.java`、`PlaceOrderPacket.java`、`PlayerEconomy.java`、`Holding.java`、`MacroEnvironment.java`、`engine/microstructure/MarketImpact.java`、`engine/microstructure/OrderFlowModel.java`、`engine/calibration/CalibrationParams.java`、`engine/random/SeedableRandom.java`
- 既有规格：`ECON_P0_SPEC.md`、`ECON_P1_SPEC.md`、`ECON_P2_SPEC.md`、`H2_MIGRATION.md`
- 现实 A 股规则依据（本规格引用的公开交易规则）：主板 ±10% 涨跌停、T+1 交收、100 股一手买入整数倍（卖出可零股）、价格优先+时间优先、连续竞价。
