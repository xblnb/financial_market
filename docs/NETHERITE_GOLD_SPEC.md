# NETHERITE_GOLD_SPEC — 下界合金锭模拟现代黄金体系（需求规格）

> - 产出：architect（方案架构 / 黄金体系需求规格）为任务 `t1`（requirements）产出。
> - 输入：`ECON_FIAT_M2_SPEC.md`（法币化已落地）、`COMMODITY_FUTURES_SPEC.md`（商品/期货已落地）、
>   `MACRO_MODEL.md`、`ECON_REMAINDER_SPEC.md`、`REALWORLD_BALANCE.md` + 当前源码审计（本规格对照真实源码给出文件/行号/方法签名）。
> - 用途：engine-dev 按本规格实现 `t2`（央行黄金储备 + 黄金定价机制）、`t3`（避险/抗通胀传导 + 交易 + 前端/API）；
>   test-engineer 按本规格实现 `t4`（整体验证与构建）；reviewer 按本规格 `t5` 验收。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、单线程、确定性可复现（同 `engineSeed`）、O(玩家+银行+资产) 常数级计算；
>   **保持 P0/P1/P2 已交付经济引擎 / A股 / 商品 / 期货核心公式不变**；**H2 持久化兼容**（`engine_kv` blob，读档缺字段兜底不失败）。
> - 范围：仅本团队 Goal 的「下界合金锭模拟现代黄金体系」五条需求（①央行实物储备 ②黄金定价机制 ③避险/抗通胀传导 ④黄金交易 ⑤与法币 M2/央行/通胀/股市/商品协同）。
>   不改动 `CommodityEntry.updatePrice`、`FuturesMarket` 期现收敛/结算、`PriceDiscoveryEngine` A股涨跌停/GBM、`InflationSystem.update`、
>   `MarketSentiment` 情绪—价格反馈等既有公式语义；黄金是叠加在「现有商品价格输入 × 新增黄金专用驱动 + 新增央行黄金储备账本」之上的**正交增量**。
> - 现代黄金参照：百度百科「黄金定价体系」——金价以伦敦金定盘价为基准，但**非固定汇率金本位**；随美元、实际利率、通胀、
>   地缘/市场避险情绪、央行购金、M2 与商品价格等浮动。本体系把「下界合金锭」从普通商品（基价 60）升格为「现代黄金」角色，
>   但**保留**其法币化定位（≠ 货币实物锚，不等于现金，不进入 M2），仅扮演「央行实物储备 / 避险资产 / 抗通胀价值储存」职能。

---

## 0. 目标与全局验收（对应团队 Goal 五条）

完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| NG-0 | 编译通过 | `./gradlew compileJava` 退出码 0 | t2/t3 + t5 |
| NG-1 | 央行黄金储备账户 | `CentralBank` 持有 `goldReserve`（下界合金锭数）与 `goldCash`（购金专用法币余额）；`goldMarketBuy / goldMarketSell` 调节储备，账本为确定性、可对账（储备增减 = 买卖净额），且**不改变** `baseMoney / reserveRequirement / discountRate` 的法币核心语义 | t2 + t5 |
| NG-2 | 黄金定价（锚定但可波动） | 新增 `GoldPricingSystem`：`goldPrice = goldBenchmark × goldMultiplier`；`goldMultiplier` 由避险/通胀/实际利率/央行购金驱动，**非固定汇率**；输出有界 `[GOLD_FLOOR, GOLD_CAP]`；同 `engineSeed` 同 tick 序列可复现 | t2 + t5 |
| NG-3 | 避险/抗通胀传导 | 市场恐慌（`MarketSentiment.globalSentiment` 下降 / `CrisisEngine.crisisLevel` 升）或通胀走高 / 实际利率转负时，`goldMultiplier` 趋升（黄金价格趋涨）；黄金作为价值储存（抗通胀、避险）被观测 | t3 + t5 |
| NG-4 | 黄金交易 | 下界合金锭经 `CommodityMarket.buy/sell`（现货）与 `FuturesMarket`（期货，品种码 `NETHERITE_INGOT`）可交易；玩家可购金储值（持有下界合金锭作为价值储存）；前端/API 暴露黄金现货价 | t3 + t5 |
| NG-5 | 与法币/央行/通胀/股市/商品协同 | 央行黄金储备与法币现金储备（`goldCash`）联动但不印钞（不改变 `baseMoney`/M2）；黄金不计入 M2；黄金价作为价值储存与股市/商品/通胀信号协同（黄金价被 `MarketEngine` 与前端消费） | t2/t3 + t5 |
| NG-6 | 核心公式不回归 | A股 `PriceDiscoveryEngine`/`OrderBook`、商品 `CommodityEntry.updatePrice`、期货 `FuturesMarket` 定价/结算/强平公式逐位不变；黄金增量经「对 netherite 条目注入黄金专用 `macroFactor`/需求输入 + 新增独立储备账本」实现；`goldEnabled=false` 时与现状逐位一致 | t2/t3 + t5 |
| NG-7 | 确定性可复现 | 同 `engineSeed` 跑固定 tick，`goldPrice`、`goldReserve`、`goldCash`、`goldMultiplier` 序列一致；零 `Math.random` | t2/t3 + t5 |
| NG-8 | H2 持久化兼容 | 黄金状态随 `MarketEngine.save()/load()` 与 `saveToH2()/loadFromH2()` 序列化；旧存档缺字段用默认值重建（读档不失败） | t2/t3 + t5 |

**验收流程（t5 落地）**：固定 `engineSeed` 跑固定 tick，输出 `goldPrice / goldMultiplier / goldReserve / goldCash / 市场恐慌(globalSentiment) / inflationRate / 实际利率 / 央行净购金`；
分别对①恐慌注入(调低 `globalSentiment`)→断言 gold 上行；②通胀升高 → gold 上行；③实际利率转负 → gold 上行；④央行净购金 → gold 上行；
⑤`buyGold/sellGold` 对账；⑥`goldEnabled=false` → 与法币化基线逐位一致。全部通过 NG-*。

---

## 1. 现状诊断（代码审计）

| 类别 | 文件 | 现状 | 问题（黄金体系视角） |
|---|---|---|---|
| `CurrencySystem` | `engine/banking/CurrencySystem.java` | `BASE_ITEM=NETHERITE_INGOT`、`EXCHANGE_RATE=32`（保留仅为 UI 兼容），`deposit/withdraw` 恒返回 `false`（法币化已废除固定汇率实物锚） | 下界合金锭已**不是**货币锚，但也尚未扮演「现代黄金」角色；只是一个普通商品 |
| `CommodityRegistry` | `engine/CommodityRegistry.java` | `rarityTier("netherite")=60.0`（`§rarityTier`，第 144 行）；`autoRegister` 动态注册全部物品；`refreshBasePrices` 以配方成本重算基价 | 下界合金锭基价约 60，作为普通商品定价；**无黄金专属驱动**（避险/通胀/实际利率/央行购金） |
| `CommodityMarket` | `engine/CommodityMarket.java` | `buy/sell`（走玩家库存）、`tick`（`applyMacro` 1+0.5×通胀+0.5×M2增长 应用到全部条目）、`getCommodity(item)` | 无黄金专用因素；`applyMacro` 对 netherite 也用统一宏观因子 |
| `CommodityEntry` | `engine/CommodityEntry.java` | `price = clamp(anchorTerm×macroFactor, costAnchor×[0.2,5.0])`；`anchorTerm=costAnchor×(1+0.2×excess)`；`setMacroFactor(f)` 重算价（第 115 行） | `macroFactor` 是黄金注入价格的**合法现有输入点**（不改公式，只改输入） |
| `FuturesMarket` | `engine/futures/FuturesMarket.java` | `registerUnderlyings`：可交付可堆叠物品 Top-24 作期货品种；`productCode(item)`=路径大写（第 100 行）→ 下界合金锭代号 `NETHERITE_INGOT`；期现收敛/结算/强平已落地 | 下界合金锭已可作期货标的；**无黄金专属定价**，期货价锚商品现货 |
| `CentralBank` | `engine/banking/CentralBank.java` | `baseMoney=10_000_000`、`discountRate`、`reserveRequirement`、`bankReserves`、`buyBonds/sellBonds/reverseRepo/emergencyLending` | **无黄金储备账户**；需新增 `goldReserve`（实物下界合金锭）与 `goldCash`（购金法币余额） |
| `InflationSystem` | `engine/banking/InflationSystem.java` | `inflationRate=0.6×M2增长+0.4×价格指数变化`（`update`，第 22 行） | 通胀数据可供黄金定价驱动（抗通胀） |
| `MarketSentiment` | `engine/MarketSentiment.java` | `globalSentiment`（0.3~0.95，`updateGlobalSentiment` 第 83 行）；`getGlobalSentiment()`；BSV 事件/反馈 | `globalSentiment` 是「市场恐慌」的最佳代理；恐慌→黄金上行 |
| `CrisisEngine` | `engine/crisis/CrisisEngine.java` | `getCrisisLevel()` | `crisisLevel` 是恐慌/危机强度代理（避险传导） |
| `MarketEngine` | `engine/MarketEngine.java` | `tick()`（第 877 行）：`runDailyFiat`（通胀/M2/财政，第 1377 行）、`commodityMarket.tick`（第 975 行）、`futuresMarket.tick`（第 992 行）、`sentiment.updateGlobalSentiment`（第 1023 行）、`centralBank.updateGuardrailInputs`（第 1331 行）；`getCentralBank()`（第 1665 行）；`save()`（第 1708 行）/`saveToH2()`（第 2192 行）/`loadFromH2()`（第 2220 行） | 无黄金系统实例；无黄金状态持久化；需新增 `goldSystem` 及接线与持久化字段 |

**根因**：下界合金锭已法币化（不再是货币锚），但仍是「普通商品」——无黄金专属避险/抗通胀定价、无央行黄金储备账本、无「价值储存」传导。本规格把其**升格**为现代黄金角色，作为正交增量叠加，不改既有核心公式。

---

## 2. 黄金体系架构设计

```
┌────────────────────────────── 黄金体系（GOLD LAYER，O(1)/交易日，确定性）──────────────────────────────┐
│  [GoldPricingSystem]（新增，engine/gold/GoldPricingSystem.java）                                        │
│    输入(只读)：MarketSentiment.globalSentiment · CrisisEngine.crisisLevel · InflationSystem.inflationRate  │
│                · CentralBank.discountRate(政策名义利率) · CentralBank.goldCash → 央行净购金 ·              │
│                  EconomicCycleEngine.economicHealth · InflationSystem.m2Growth                             │
│   基准：goldBenchmark = 下界合金锭下界(稀有度档) 60.0（锚定基准，非金本位固定汇率）                          │
│   输出：goldMultiplier = clamp(1 + w_sh·避险 + w_infl·通胀 + w_rr·实际利率 + w_cb·央行购金, FLOOR, CAP)      │
│         goldPrice      = goldBenchmark × goldMultiplier（黄金价，非固定，有界）                           │
│   落地：netherite 商品条目.setMacroFactor(goldMultiplier)（复用既有 macroFactor 输入，不改 updatePrice）    │
│         → 下界合金锭现货价围绕成本锚 × goldMultiplier 浮动（锚定但可波动）                                   │
│  [CentralBank]（扩展，engine/banking/CentralBank.java）                                                 │
│    goldReserve(下界合金锭数) · goldCash(购金专用法币余额) · goldMarketBuy(amount)/goldMarketSell(amount)   │
│    → 买卖调节储备；goldReserve 增减 = 净买卖额；goldCash 与之镜像（不印钞：不改变 baseMoney/M2）               │
│  [CommodityMarket] 下界合金锭现货（t2/t3 复用，已可用 buy/sell，getCommodity(NETHERITE_INGOT)）            │
│  [FuturesMarket] 下界合金锭期货 NETHERITE_INGOT（t2/t3 复用，已可用，期现收敛锚商品现货）                     │
│  [MarketEngine] 接线：goldSystem.tick(engine) 在 commodityMarket.tick 之后，注入 macroFactor + 估值         │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```
**单向依赖**：黄金层只读消费宏观/情绪/央行/危机输出；黄金层 → netherite 商品 `macroFactor` 输入（不改商品公式）。
**不改变**：A股 `PriceDiscoveryEngine`、`OrderBook`、`CommodityEntry.updatePrice`、`FuturesMarket` 期现/结算/强平公式、`InflationSystem.update`、`MarketSentiment` 反馈公式。

---

## 3. 需求分解（分阶段，供 t2/t3/t4 落地）

### P1【t2】央行黄金储备账户（需求①）

**3.1.1 改动文件**
- `src/main/java/com/example/financialmarket/engine/banking/CentralBank.java`
- `src/main/java/com/example/financialmarket/engine/MarketEngine.java`（接线 + 持久化 + 暴露）

**3.1.2 字段/常量**
```java
// CentralBank 新增
private double goldReserve;              // 央行持有的下界合金锭实物数（黄金储备）
private double goldCash;                 // 央行购金专用法币余额（非印钞：不改变 baseMoney/M2）
private double goldHeldCashOutflow;      // 累计购金流出（对账/诊断）
private double goldMarketBuyAmount;      // 当日净购金（供 GoldPricingSystem 读取，交易日重置）
public static final double DEFAULT_GOLD_CASH = 2_000_000; // 初始购金法币余额（非 baseMoney）
```

**3.1.3 方法签名**
```java
// 央行购金/抛金：用专用法币余额 goldCash 与黄金储备 goldReserve 镜像调节，不印钞（不改 baseMoney/M2）。
public boolean goldMarketBuy(double amount);       // amount>0 且 goldCash>=amount*goldPrice 时：
                                                    // goldReserve+=amount; goldCash-=amount.goldPrice;
                                                    // goldMarketBuyAmount+=amount; 返回 true
public boolean goldMarketSell(double amount);      // amount>0 且 goldReserve>=amount 时：
                                                    // goldReserve-=amount; goldCash+=amount.goldPrice; 返回 true
public double getGoldReserve();
public double getGoldCash();
public double getGoldMarketBuyAmount();            // 当日净购金（供定价读取）
public void resetGoldMarketFlow();                 // 交易日边界清零（在 runDailyFiat/交易日开始时调用）
```

**3.1.4 验收（NG-1 / NG-5 / NG-7 / NG-8）**
- 央行获初始 `goldReserve`（如 `DEFAULT_GOLD_RESERVE=200` 块折算锭）与 `goldCash=2_000_000`；`baseMoney / reserveRequirement / discountRate` **不**因购金而变（非印钞）。
- `goldMarketBuy(amount)`：扣 `goldCash`、加 `goldReserve`；余额不足返回 `false` 且不改变任何状态（原子性、确定性）。
- `goldMarketSell(amount)` 镜像。
- 对账：`ΔgoldReserve == netBuy−netSell`；`ΔgoldCash == (netSell−netBuy)×goldPrice`；`goldCash >= 0`。
- 与法币协同：黄金储备不进 `bankReserves`、不进 M2；央行持有黄金被 `GoldPricingSystem` 估值（`goldReserve×goldPrice`）仅作诊断/前后端展示。
- `goldEnabled=false` 时以上方法为 no-op / 不接线，与基线一致。

---

### P2【t2】黄金定价机制（锚定但可波动）（需求②）

**3.2.1 新增文件**
- `src/main/java/com/example/financialmarket/engine/gold/GoldPricingSystem.java`（黄金层单例，引擎种子派生子流保持可复现）

**3.2.2 常量（`gold.*` 可调）**
```java
public static final double GOLD_BENCHMARK = 60.0;       // 锚定基准 = 下界合金锭下界(稀有度档, CommodityRegistry §rarityTier)
public static final double GOLD_FLOOR = 0.5;            // goldMultiplier 下界（防金价崩塌）
public static final double GOLD_CAP   = 5.0;            // goldMultiplier 上界（防金价抛物线发散）
public static final double PANIC_THRESHOLD = 0.45;      // 市场恐慌阈值（globalSentiment < 此值视为避险启动）
public static final double W_SAFE_HAVEN  = 1.2;         // 避险权重
public static final double W_INFLATION   = 0.8;         // 通胀(抗通胀)权重
public static final double W_REAL_RATE   = 1.5;         // 实际利率(负实际利率)权重
public static final double W_CB_BUY      = 0.9;         // 央行净购金权重
public static final double CB_BUY_SCALE  = 1000.0;      // 净购金归一化分母（≈ 典型日购金）
```

**3.2.3 状态/方法签名**
```java
public class GoldPricingSystem {
    private double goldBenchmark;   // = GOLD_BENCHMARK（锚定基准）
    private double goldPrice;       // = goldBenchmark × goldMultiplier（黄金价，有界）
    private double goldMultiplier;  // 1.0（中性）
    private double prevGoldPrice;   // 上一交易日黄金价（供期货/前端昨结）
    // 只读输入快照（诊断/持久化）：inflationRate · realRate · globalSentiment · crisisLevel · netCbBuy
    public void tick(MarketEngine engine);           // 交易日边界一次，读取只读宏观 + 计算 + 落到 netherite 条目
    public double goldPrice();
    public double goldMultiplier();
    public double goldBenchmark();
    public double realRate(CentralBank cb, InflationSystem inf); // policyRate − inflationRate
    private double computeSafeHaven(MarketSentiment s, CrisisEngine c);  // 避险分量
    private double computeInflation(InflationSystem inf);               // 抗通胀分量
    private double computeRealRate(CentralBank cb, InflationSystem inf);// 实际利率分量
    private double computeCbBuy(CentralBank cb);                        // 央行净购金分量
}
```

**3.2.4 定价公式（确定性、无随机）**
```
realRate      = policyRate − inflationRate
safeHaven     = W_SAFE_HAVEN × clamp((PANIC_THRESHOLD − globalSentiment)/PANIC_THRESHOLD, 0, 1)
                + W_SAFE_HAVEN × 0.8 × crisisLevel
inflation     = W_INFLATION × clamp(inflationRate, 0, 0.5)          // 高通胀 → 抗通胀买金
realRateTerm  = W_REAL_RATE × clamp((0 − realRate), 0, 0.10)        // 负实际利率 → 持有黄金
cbBuy         = W_CB_BUY × clamp(netCbBuy/CB_BUY_SCALE, 0, 1)      // 央行净购金 → 金价上行
goldMultiplier= clamp(1 + safeHaven + inflation + realRateTerm + cbBuy, GOLD_FLOOR, GOLD_CAP)
goldPrice     = goldBenchmark × goldMultiplier
```
> 对照百度百科「黄金定价体系」：金价非固定汇率；受避险、通胀、实际利率、央行购金等驱动。本公式产出一个**有界、可波动、非固定**的黄金价，锚定基准价 `goldBenchmark=60`，不回到金本位固定汇率。

**3.2.5 落到 netherite 商品（不改商品公式）**
- `GoldPricingSystem.tick(engine)` 在 `commodityMarket.tick` 之后调用：对 `engine.getCommodityMarket().getCommodity(Items.NETHERITE_INGOT)` 调用 `entry.setMacroFactor(goldMultiplier)`（复用既有 `macroFactor` 输入，`CommodityEntry.updatePrice` 公式逐位不变）。
- 于是下界合金锭现货价 = `costAnchor×(1+0.2×excess)×goldMultiplier`，围绕成本锚随 goldMultiplier 波动（锚定但可波动、非固定汇率）。

**3.2.6 验收（NG-2 / NG-7）**
- `goldPrice` 由上述公式闭式生成，`goldMultiplier∈[GOLD_FLOOR,GOLD_CAP]`，同 `engineSeed` 同 tick 可复现。
- 驱动项每项可被单一变量触发（见 P3），单调方向正确；基准价恒定 `60`（锚定）。
- `goldEnabled=false`：不 tick、不注入 macroFactor，netherite 走既有统一 `applyMacro`（与基线一致）。

---

### P3【t3】黄金避险/抗通胀传导（需求③）

**3.3.1 传导规则（确定性）**
| 场景 | 观测输入 | 传导 | 期望 |
|---|---|---|---|
| 市场恐慌 | `MarketSentiment.getGlobalSentiment()` 低于 `PANIC_THRESHOLD` | `safeHaven` 分量上升 | 黄金价格趋涨 |
| 危机 | `CrisisEngine.getCrisisLevel()` 上升 | `safeHaven` 分量上升（+0.8×crisisLevel） | 黄金价格趋涨 |
| 高通胀 | `InflationSystem.getInflationRate()` 走高 | `inflation` 分量上升 | 黄金价格趋涨（抗通胀） |
| 负实际利率 | `policyRate−inflationRate < 0` | `realRateTerm` 上升 | 黄金价格趋涨（实际利率下行持有黄金） |
| 央行购金 | `CentralBank.getGoldMarketBuyAmount()>0` | `cbBuy` 分量上升 | 黄金价格趋涨 |
| 平稳期 | `globalSentiment` 高、通胀低、realRate>0、无净购金 | 各分量≈0 → `goldMultiplier≈1` | 黄金价格回归锚定基准价 |

**3.3.2 价值储存角色**
- 下界合金锭（黄金）作为抗通胀价值储存：`inflationRate` 高时黄金价趋涨，玩家持有下界合金锭可对冲法币购买力下降（与 `ECON_FIAT_M2` 的法币体系中「M2 增长→通胀→购买力下降」协同）。
- 避险：股市恐慌（`globalSentiment` 降 / `crisisLevel` 升）时黄金价趋涨，与股市呈弱相关/负向避险（`REALWORLD_BALANCE` 黄金避险定位）。

**3.3.3 验收（NG-3 / NG-7）**
- 对固定 `engineSeed` 跑基准盘，再分别注入「调低 `globalSentiment`」「升高 `inflationRate`」「使 `policyRate−inflationRate<0`」「央行净购金>0」四种单因子扰动，断言 `goldMultiplier` 较基线单调上升且 `goldPrice` 上行。
- `goldMultiplier` 保持有界；平稳期回落至 ≈1。

---

### P4【t3】黄金交易 + 玩家储值 + 前端/API（需求④）

**3.4.1 现货（商品市场，复用）**
- 下界合金锭经 `CommodityMarket.buy(player, Items.NETHERITE_INGOT, qty)/sell(...)` 交易（玩家库存）；已有 `buy/sell`（`§buy/sell`）。玩家以现货价购金存入背包作为价值储存。
- 无需改动商品市场核心；仅确保 `getCommodity(NETHERITE_INGOT)` 现货价为黄金 `goldPrice`（经 P2 macroFactor 注入）。

**3.4.2 期货（复用）**
- 下界合金锭作为可交付可堆叠物品，已进入 `FuturesMarket.registerUnderlyings` 的 Top-24 品种（`productCode`=`NETHERITE_INGOT`）；期货价以商品现货（黄金价）为锚，经期现收敛 + 持有成本演变（公式不变）。

**3.4.3 玩家储值（黄金账户，供前端/API）**
- 玩家持有下界合金锭：作为价值储存，不进入 M2（黄金不是货币）。可在 `CommodityMarket` 现货买/卖、`FuturesMarket` 期货开/平仓。
- 建议新增只读查询（供前端 `FinancialTabletScreen` / Web API）：
  - `MarketEngine.getGoldPrice()` / `goldSystem.goldPrice()`（黄金现货价）
  - `goldSystem.prevGoldPrice()`（昨结，供 K 线/涨跌）
  - `CentralBank.getGoldReserve()` / `getGoldCash()`（央行黄金储备）
  - `CentralBank.getGoldMarketBuyAmount()`（当日净购金）

**3.4.4 验收（NG-4 / NG-6）**
- 下界合金锭现货 `buy/sell` 前后玩家库存/现金一致；期货 `NETHERITE_INGOT` 开平仓/结算走既有规则。
- 前端/API 可读到 `goldPrice / prevGoldPrice / goldReserve / goldCash / 净购金`。
- 不改变 `CommodityMarket.buy/sell`、`FuturesMarket` 交易/结算公式（NG-6）。

---

### P5【t2/t3】协同 + 持久化 + 确定性（需求⑤ + NG-5/7/8）

**3.5.1 与法币 M2/央行/通胀/股市/商品协同**
| 协同点 | 机制 | 不改变 |
|---|---|---|
| 央行 | `goldCash`（购金专用法币余额）与 `goldReserve` 镜像；购金不印钞（`baseMoney/M2` 不变） | `CentralBank` 法币语义 |
| 通胀 | `InflationSystem.inflationRate` → 黄金定价 `inflation` 分量（抗通胀） | `InflationSystem.update` 公式 |
| M2 | 黄金不进入 M2；`m2Growth` 经 `inflation`（通胀=M2增长派生）间接反映 | M2 口径 |
| 股市 | `globalSentiment`/`crisisLevel`（股市风险偏好）→ 黄金避险分量 | `MarketSentiment`/`PriceDiscoveryEngine` |
| 商品 | 下界合金锭现货价 = 成本锚×`goldMultiplier`；黄金为商品一极 | `CommodityEntry.updatePrice` |
| 期货 | 黄金期货锚现货（期现收敛） | `FuturesMarket` |

**3.5.2 接线（MarketEngine）**
- 新增字段 `private final GoldPricingSystem goldSystem;`（构造时以引擎种子派生子流：`new GoldPricingSystem(seed)`）。
- 新增 `goldEnabled` 开关（默认 `true`），`setGoldEnabled/isGoldEnabled`。
- `init()`：`goldEnabled` 时注入 netherite 目标（幂等）；`tick()` 在 `commodityMarket.tick` 之后调用 `goldSystem.tick(this)`（交易日边界；`isTradingDayStart()` 时 `centralBank.resetGoldMarketFlow()`；`runDailyFiat` 内/后调 `goldSystem.tick`，使黄金价先用当日通胀/实际利率/央行购金）。
- `getGoldSystem()`、`getGoldPrice()`、`getGoldReserve()` 暴露。

**3.5.3 持久化（H2 兼容）**
- `CentralBank` 新增持久化字段：`goldReserve / goldCash / goldMarketBuyAmount`（`MarketEngine.save()` 的 `fiat.centralBank` tag 追加；`load()` 用 `contains` 兜底，旧档缺字段默认值重建）。
- 新增 `goldSystem` 持久化字段：`goldPrice / goldMultiplier / prevGoldPrice / goldBenchmark / 输入快照`（追加到 `save()` blob；`load()` / `loadFromH2()` 用 `contains` 兜底）。
- `save()`（第 1708 行）、`saveToH2()`（第 2192 行）、`load()`/`loadFromH2()`（第 2220 行）均为本地累加，不改既有字段格式；旧存档读档不失败。

**3.5.4 验收（NG-5 / NG-7 / NG-8）**
- 黄金状态随 save/load 与 H2 持久化往返一致（同 seed 往返后 `goldPrice` 逐位一致）。
- 旧存档（无黄金字段）load 后 `goldReserve=DEFAULT_GOLD_RESERVE`、`goldCash=DEFAULT_GOLD_CASH`、`goldMultiplier=1`。
- 同 seed 同 tick 确定性可复现（NG-7）。

---

## 4. 改动文件与方法签名总表

| 文件 | 改动 | 关键方法（新增/改动签名） |
|---|---|---|
| `engine/banking/CentralBank.java` | 新增黄金储备账户 | `goldMarketBuy(double)`/`goldMarketSell(double)`/`getGoldReserve()`/`getGoldCash()`/`getGoldMarketBuyAmount()`/`resetGoldMarketFlow()` |
| `engine/gold/GoldPricingSystem.java` | **新增**黄金层单例 | `tick(engine)`/`goldPrice()`/`goldMultiplier()`/`goldBenchmark()`/`realRate(cb,inf)`/`computeSafeHaven/getComputeInflation/computeRealRate/computeCbBuy` |
| `engine/MarketEngine.java` | 接线 + 持久化 + 暴露 | `goldSystem` 字段/`setGoldEnabled()`/`isGoldEnabled()`/`getGoldSystem()`/`getGoldPrice()`/`getGoldReserve()`；`tick()` 内调用；`save()/load()/saveToH2()/loadFromH2()` 追加字段 |
| `engine/CommodityMarket.java` | **无核心改动**（仅注入黄金 `macroFactor`），只读 `getCommodity(NETHERITE_INGOT)` | 复用既有 `getCommodity` |
| `engine/futures/FuturesMarket.java` | **无核心改动**（黄金期货已作为品种），复用期现收敛 | 复用既有 `registerUnderlyings/updateFuturesPrices/close/open` |
| `engine/CommodityRegistry.java` | **无改动**（`rarityTier("netherite")=60` 作为黄金基准锚） | 读 `rarityTier` 作为 `goldBenchmark` 来源 |
| `engine/banking/InflationSystem.java`、`engine/MarketSentiment.java`、`engine/crisis/CrisisEngine.java` | **只读消费**（不改公式） | 读 `getInflationRate()/getGlobalSentiment()/getCrisisLevel()/getDiscountRate()` |
| `web`/`screen/FinancialTabletScreen.java` | 前端/API 暴露黄金价、央行黄金储备（只读） | 读 `getGoldPrice/prevGoldPrice/goldReserve/goldCash/净购金` |

> 新增目录：`src/main/java/com/example/financialmarket/engine/gold/`（GoldPricingSystem）。
> **不改动**：`CommodityEntry.updatePrice`、`FuturesMarket` 结算/强平、`PriceDiscoveryEngine`、`OrderBook`、`InflationSystem.update`、`MarketSentiment` 反馈。

---

## 5. 确定性 & 时间权威

- 时间权威：`MarketEngine.tick` 的 `tickCounter` 与 `TradingCalendar`（`isTradingDayStart/isSessionEnd`）为唯一时间权威；黄金定价在**交易日边界**执行一次，逐日演化，无每 tick 抖动。
- 零随机：黄金定价为闭式确定性公式，无 `Math.random`；`GoldPricingSystem` 的种子仅用于派生（如未来子流），当前公式零随机。
- 可复现：同 `engineSeed` 跑固定 tick，黄金价/储备序列逐位一致（NG-7）。
- 有界：`goldMultiplier∈[GOLD_FLOOR,GOLD_CAP]=[0.5,5.0]`，黄金价有限，防发散。

---

## 6. 与既有模块边界（核心公式不回归）

| 既有模块 | 承诺 |
|---|---|
| A股 `PriceDiscoveryEngine` / `OrderBook` / 涨跌停 / GBM / 熔断 | 不改 |
| 商品 `CommodityEntry.updatePrice`/`applyMacro`/`decay`/成本锚 | 不改公式；仅对 netherite 条目注入黄金 `macroFactor` 输入 |
| 期货 `FuturesMarket` 期现收敛/涨跌停/逐日结算/强平/交割 | 不改 |
| 通胀 `InflationSystem.update`、M2 口径 | 不改；黄金不进 M2 |
| 情绪 `MarketSentiment` 反馈/BSV | 不改；只读 `getGlobalSentiment` |
| 央行法币语义 `baseMoney/reserveRequirement/discountRate` | 不改；黄金储备为独立正交账本（goldCash/goldReserve），不印钞 |

`goldEnabled=false` 时：不实例化黄金层、不注入 `macroFactor`、不持久化黄金字段（或持久化全默认），下界合金锭回到普通商品（基价 60，走既有 `applyMacro`），与法币化基线逐位一致（NG-6）。

---

## 7. 参数表（`gold.*` 可配置，默认值见 §3.2.2/§3.1.2）

| 参数 | 默认 | 说明 |
|---|---|---|
| `gold.enabled` | true | 黄金体系总开关（false → 基线同构） |
| `gold.benchmark` | 60.0 | 锚定基准价（= 下界合金锭下界稀有度档） |
| `gold.floor` | 0.5 | goldMultiplier 下界 |
| `gold.cap` | 5.0 | goldMultiplier 上界 |
| `gold.w.safeHaven` | 1.2 | 避险权重 |
| `gold.w.inflation` | 0.8 | 抗通胀权重 |
| `gold.w.realRate` | 1.5 | 实际利率权重 |
| `gold.w.cbBuy` | 0.9 | 央行购金权重 |
| `gold.panicThreshold` | 0.45 | 市场恐慌阈值（globalSentiment） |
| `gold.cbBuyScale` | 1000.0 | 净购金归一化分母 |
| `gold.defaultGoldReserve` | 200.0 | 央行初始黄金储备（锭数） |
| `gold.defaultGoldCash` | 2_000_000 | 央行初始购金法币余额（非 baseMoney） |

---

## 8. 全局验收流程（t5 落地入口）

1. **构建**：`./gradlew compileJava` 退出码 0（NG-0）。
2. **基线对照**：`goldEnabled=false`（或新旧存档）下，下界合金锭基价 60、走 `applyMacro`，与法币化基线逐位一致（NG-6）。
3. **央行储备对账**：`goldMarketBuy(10)`/`goldMarketSell(4)` 后 `goldReserve` 净 +6、`goldCash` 镜像余额≥0、`baseMoney/M2` 不变（NG-1/NG-5）。
4. **定价**：固定 seed 跑盘，输出 `goldPrice/goldMultiplier`；断言在 `[benchmark×0.5, benchmark×5.0]`；`benchmark` 恒 60（NG-2）。
5. **避险/抗通胀传导**：分别注入恐慌/高通胀/负实际利率/央行净购金四类扰动，断言 `goldMultiplier` 单调上行（NG-3）。
6. **交易**：现货 buy/sell 走库存；期货 `NETHERITE_INGOT` 开平仓结算；前端/API 可读黄金价与储备（NG-4）。
7. **确定性**：同 seed 两次跑固定 tick，黄金序列一致（NG-7）。
8. **H2**：save→load→saveToH2→loadFromH2 往返，黄金状态逐位一致；旧档缺字段默认重建（NG-8）。

---

## 9. 边界与风险

- **非金本位**：黄金有基准锚（60）但**不**作固定汇率、**不**进入货币供给/结算（不是现金）。法币 M2 与黄金严格两轨（黄金不进 M2、不兑换现金），呼应「现代黄金已脱离金本位」。
- **不改核心公式**：全部黄金驱动走「netherite 商品 `macroFactor` 输入 + 独立 goldReserve/goldCash 账本」，不触碰 `updatePrice`/期货/通胀/情绪公式；评审须逐位比对 `goldEnabled=false` 与基线。
- **决定性局限**：黄金为一条**单向**叠加线；全球金价的多重均衡/套利在单市场确定性引擎中不建模，仅按基准锚+宏观传导近似（参照百度百科「黄金定价体系」的核心驱动，非完整现实套利）。
- **H2 兼容**：所有新增持久化字段用 `contains` 兜底；旧存档读档不失败；不改变既有 `fiat.centralBank` 等字段格式（累加）。
- **性能**：黄金定价每交易日一次常数级；`getCommodity(NETHERITE_INGOT)` 为 Map 查找；无新增 O(资产) 全量扫描引入黄金层（除非前端查询），满足 O(1) 约束。
