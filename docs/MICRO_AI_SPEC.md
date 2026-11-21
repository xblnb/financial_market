# MICRO_AI_SPEC — 市场微观结构 / AI 行为根因诊断与验收规格

> 作者：architect（方案架构 / 根因诊断与规格）
> 背景：用户实测发现 ①所有股票一致下跌、②盘口薄/无人交易、③挂单不符合现实交易逻辑、④明斯基/金融周期特征缺失。
> 目标：逐条定位根因（确切代码位置 + 数据流），定义可复现验收 MS-AI-0..N。
> 硬约束：确定性可复现（SeedableRandom 子流，禁裸随机）；H2/NBT 持久化兼容；**不改已交付法币 M2 / 黄金核心语义**；开新存档测试。
> 交付物：本文件（MICRO_AI_SPEC.md），为实施 / 验证 / 评审的契约来源。

---

## 0. 结论速览（TL;DR）

| 现象 | 根因定位 | 严重度 | 修复方向 |
|---|---|---|---|
| ①所有股票一致下跌 | `MultiAgentManager.stepAgents` 用**戈登模型重算 fairValue**，高成长股 `growth > discountRate` → 分母为负 → fairValue 恒被钳到 `0.1` 地板；且该戈登 fairValue **与 `AICompanyData.getFairValue()`（DCF 体系）互相打架**，所有股票共享同一宏观折现率 → 系统性地把全部股票锚到 ~0.1 的卖压 | **Blocker** | 弃用戈登重算，统一用 `company.getFairValue()`；修复 `growth ≥ discountRate` 的退化解；给 fairValue 加量纲/下限护栏；按行业/基本面产生差异化锚 |
| ②盘口薄/无人交易 | 单边报单（VALUE/RETAIL/MOMENTUM 每 agent 只出一单）+ 做市商仅 10% 占比且仅 `rng<0.2` 概率出一档买+一档卖；ZI 单笔均值仅 40 股、到达率 2%；卖压价格跌破 A 股跌停带被 `withinAShareLimit` 拒单 → 卖侧无真实报单 | **Blocker** | 做市商多档双边报价 + 报价生命周期（撤单/重挂）；ZI 量级与真实深度匹配；修复「卖压被跌停带整批吞掉」导致买卖单量极不均 |
| ③挂单不符合现实逻辑 | 单侧单笔、档位不成阶梯、价差 `priceOffset` 不随 spread/深度分层、无冰山/无撤单挂重、锚价坍塌到 0.05~0.15 远离基本面 | **High** | 分层限价/量档位、双边差额、价差按 spread & 深度、订单生命周期、锚价贴近合理估值 |
| ④明斯基时刻缺失 | `CrisisEngine` 只调全局情绪 −0.03、只置危机类型，**不回传到** agent 杠杆/保证金/盘口；agent.leverage 只随流动性乘子微调、无杠杆堆积→强平传导；经济周期相位不改变 agent 风险偏好/盘口深度 | **High** | 建立「繁荣期杠杆堆积 → 收缩期强制去杠杆/抛售」闭环；周期相位 → agent 风险偏好/仓位/盘口深度/波动；危机 → 恐慌→盘口 | 

---

## 1. 数据流全貌（从宏观到成交到显示）

```
MacroEnvironment.step (20 tick)
   ├─ r / p / v  OU/跳跃/CIR
   ├─ getDynamicDrift()  μ(t) = 0.08 −1.2·r −0.5·p +0.02   (~+0.048, 所有股票共用)
   ├─ getDynamicVolatility() σ(t) = 0.20 + 0.008·v
   ├─ getDiscountRate() = 0.04 + r + p·0.2        ──┐
   └─ getRiskAppetite() = 1 − (v−5)/75              │
                                                     ▼
PriceDiscoveryEngine.step (每 tick)                 MultiAgentManager.stepAgents(assetId, gbmPrice, lastPrice, book, liqMult)
   ├─ gbm.mu = μ(t)  (所有资产同一值 → 一致)          ├─ fairValue = eps·(1+growth)/(discountRate−max(0.01,growth))
   ├─ GARCH σ_t                                     ├─ anc  = fairValue·(1+0.3·(gbm/fair−1)+0.3·(riskApp−0.5))   [钳到 [0.5,1.5]×fair]
   ├─ newPrice = gbm.price·exp(μ·dt+σ·√dt·ε)         ├─ endogenousAnchor = 0.5·mid + 0.5·anchor
   └─ clampForContinuity(±5%)                       ├─ deviation = (lastPrice−fairValue)/fairValue
                                                     └─ 各 agent 合成 OrderFlow(isBuy, price=anchor·(1±off), size)
                                                           │
   MarketEngine.tick  L2: consumeOrders → placeOrder(order)  合成流 order.setSynthetic(true)
                     L2.5: OrderFlowModel ZI/Hawkes → placeOrder
                     L3: book.matchWithSlippage → walkBook → Trade → aShareClamp(涨跌停)/clampForContinuity
                           → updateDailySnapshot: state.getAsset(id).setLastPrice(trade.price)
                           → priceEngine.updateLastPrice → gbm.mu += 0.02·(last−gbm)/gbm   (反馈)
                           → addTradePoint → K线(前端)
   用户看到的 K线/盘口/最新价 = state 里按「成交价 lastPrice」演化
```

**关键**：用户看到的价格是「订单簿撮合成交价」（`AssetSnapshot.lastPrice`，由 L3 成交驱动），而非 GBM 基准价。因此「一致下跌」主要由 **agent 订单流方向**（卖压）决定，而 agent 方向又由 `deviation = (lastPrice − fairValue)/fairValue` 决定。→ fairValue 坍塌是所有现象的「总开关」。

---

## 2. 根因①：所有股票一致下跌（无系统性上涨）

### 2.1 直接根因 — fairValue 戈登重算坍塌到 0.1 地板
- 位置：`MultiAgentManager.stepAgents` :304-307
  ```java
  double eps = company.getEps();
  double growth = company.getRevenueGrowth();
  double fairValue = eps * (1 + growth) / (discountRate - Math.max(0.01, growth));
  fairValue = Math.max(0.1, fairValue);   // ← 分母为负 → fairValue<0 → 恒钳到 0.1
  ```
  - `discountRate = 0.04 + r + p·0.2`（`MacroEnvironment.java:197-199`），初始 ≈ `0.04+0.035+0.004 = 0.079`。
  - `growth = company.getRevenueGrowth()`：初始 `0.05 + rnd·0.25`（`AICompanyData.java:66`），约 88% 公司 `growth > 0.079` → `discountRate − growth < 0` → `fairValue < 0` → 恒为 `0.1`。
- 传导：`:331` `deviation = (lastPrice − fairValue)/fairValue`；fairValue=0.1 而 lastPrice≈ipoPrice（大）→ deviation 巨正 → `:441` valueDecision `deviation > 0 → sell` → 全市场卖压。
- **双重冲突**：`AICompanyData.updateFairValue()`（DCF+PE+PB，:155-183）算出的 `getFairValue()` 是「意图内在价值」，且 `ipoPrice = this.fairValue`（:86）；但 `stepAgents` **完全忽略 `company.getFairValue()`**，另起戈登模型 → 两套 fairValue 语义打架。

### 2.2 为什么「一致」（无差异）
- `PriceDiscoveryEngine.step` :306-318：`gbm.mu = macroEnv.getDynamicDrift()` — **对每一资产都写同一个 μ**，`addAsset` 传入的每资产 `drift`（`registerAICompany`/`MarketEngine.java:481-482, 512-513` 生成的 ±0.01）**被覆盖丢弃**。→ 宏观路径完全共同模（common mode），无任何资产差异性来源。

### 2.3 澄清「宏观漂移」角色
- `getDynamicDrift()` 数值 ≈ **+0.048（正）**，本身就**不会**把股票拉跌（`MacroEnvironment.java:155-157`）。「用户怀疑宏观漂移一致拉跌」的**真凶是**：①全局共用同一 μ（一致性，2.2），②fairValue 坍塌（下跌方向，2.1）。二者叠加才出现「一致下跌、无上涨」。

### 2.4 修复方向（指到位置）
1. `stepAgents` :304-307：弃用戈登重算，改为 `fairValue = company.getFairValue()`（已有 DCF 体系）；或保留戈登但加**安全护栏**：`discountRate − growth <= ε` 时用 `fairValue = eps·K`（合理 B/P 上限）而非 `0.1`。
2. 给每资产 fairValue 引入**行业/基本面差异化**（复用 `growthStage/profitabilityScore/financialHealthScore`）与**宏观折现率联动**（`getDiscountRate` 单一不变式，与 2.1 一致），使不同股票出现不同 fairValue → 有买有卖。
3. `PriceDiscoveryEngine.step` :318：每资产 μ = 宏观μ + **该资产基本面/动量偏移**（从 fairValue 相对 GBM 的偏离派生），恢复资产差异化，避免共同模（同时保证累加不破坏可复现）。
4. 加 fairValue **下限护栏**（例如 ≥ 0.5×bookValuePerShare 或 ≥ 行业分位），禁止任意 `0.1` 地板。

---

## 3. 根因②：盘口薄 / 无人交易

### 3.1 单边 / 单笔报单
- `MultiAgentManager` 每 agent 每次决策只产**一单**（`valueDecision`/`retailDecision`/`momentumDecision` 各返回单个 OrderFlow；`stepAgents` :390-407）。
- 做市商（MARKET_MAKER）仅在 `assetRng.nextDouble() < 0.20·riskAppetite` 时触发（:401），且占比仅 `liquidityShare=0.10`（`BehaviorParams` :28,84）。`marketMakerQuotes` :581-599 只出**一档买 + 一档卖**（`bidSize/askSize≈500-4000`），无多档、无撤单挂重 → 盘口深度=单档薄壁。

### 3.2 ZI/Hawkes 量级过小
- `OrderFlowModel`：`ziBaseRate=0.02`（每资产每 tick 2% 到单）、`ziLotMean=40`（单笔均值 40 股，:35,137-141）、`hawkesLambda0=0.005`、`K=0.02`。单笔 40 股对于一个 ipoPrice>1 的 A 股是「零头」，叠加后总在盘量极低 → 盘口薄、成交稀疏。

### 3.3 卖压被涨跌停带整体吞掉 → 卖侧无单
- `stepAgents` 卖单价格 ≈ `anchor·(1+0.01..0.02)`；anchor 因 fairValue 坍塌（2.1）落到 0.05~0.15，或 `0.5·mid+0.5·anchor`（`endogenousAnchor` :344-350）≈ `0.5·lastPrice`。这些卖价 **远低于跌停带** `prevClose·0.9`。
- `MarketEngine.tick` :1234-1242：`if (!withinAShareLimit(order)) continue;` → 卖单被**成批拒单**，永远不入簿 → 盘口卖侧只剩做市商/ZI 薄壁；「有人想卖但挂不进」→ 盘口单边/无人交易。

### 3.4 深度/价差为何异常
- `spread = (bestAsk−bestBid)/gbmPrice`（:300-301），由冷启动 `seedColdStartBook`（`MarketEngine.java:389-415`）±0.01 五档 + 做市商 `halfSpread=max(spread,0.004)`（:588）决定；一旦冷启动单过期（`purgeExpired`，`OrderBook` :177,289），只剩做市商单档 → 价差/深度不稳定失真。
- `marketMakerQuotes` 不随 `walkBook` 深度/订单量生成多档，导致「价差合理但深度单薄」或「深度只在 ±1 tick」。

### 3.5 修复方向
1. 做市商：多档（3~5 档）双边报价，量随深度曲线递减，加入撤单/重挂生命周期。
2. 修复 3.3：让卖压价格能落入涨跌停带（fairValue 合理后 anchor 不再坍塌）；或对「超带拒单」改为**贴带挂单排队**（已有端点可挂逻辑，落到实处）。
3. ZI/Hawkes 量级放大到真实深度（与 `CalibrationParams` 深度先验一致），并有到达聚集；弱化单点 40 股。
4. 冷启动五档在交易时段不一次过期清空，改为滚动补充。

---

## 4. 根因③：挂单不符合现实交易逻辑

| 现实要求 | 现状缺陷（位置） | 修复方向 |
|---|---|---|
| 多档阶梯 | 每 agent 单笔；做市商单档买+卖（:581-599） | 多档价/量阶梯 |
| 双边差额、价差随 spread/深度 | VALUE `priceOffset` 固定 ±0.01~0.02（:452），与 spread 无关；MOMENTUM 用 spread（:553-555）但无深度分层 | 价差按 `spread` 与档位深度分层 |
| 量级与流动性匹配 | VALUE 1000-5000、RETAIL 20-800、MOMENTUM 500-5000（:445,494,546）——无整手/无冰山、量级不随盘口 | 量按 lot 取整、按盘口深度缩放 |
| 撤单/挂重/生命周期 | agent 单永不主动撤，仅靠 `purgeExpired`（`OrderBook:177`）整体清超龄单 | 智能撤单 + 按价微调重挂 |
| 价格锚真实 | anchor 因 fairValue 坍塌到 0.05~0.15（2.1），远离基本面 | 锚贴近合理估值（2.4 修复） |
| 不高频傻帽 | RETAIL/MOMENTUM 每 agent 每决策一单，`|dev|<0.02` 才判定（VALUE），但 RETAIL/MOMENTUM 无绝对偏离门控，交易过于随意 | 加绝对偏离门控 + 概率理性 |

---

## 5. 根因④：明斯基时刻 / 金融周期特征缺失

### 5.1 CrisisEngine 只有「纸面」危机，无市场传导
- `CrisisEngine.tick` :51-96：`crisisLevel` 由 NPL/债务/通胀/情绪算得，但**只**:89 `sentiment.adjustGlobalSentiment(-0.03)`、`setFiscalStance`；**没有任何路径把危机回传到 agent 杠杆/保证金/盘口/订单流**。`currentCrisis`（DEPRESSION/CRASH 等）在股本市场完全无下游消费者（`CrisisEngine.load/save` 只存状态）。
- `creditGrowth` 计算后仅 `getCreditGrowth()` 读出（:66,99），无 agent/盘口消费。

### 5.2 agent 杠杆无「堆积→去杠杆」闭环
- `AgentState.leverage` 预设（MOMENTUM 1.5 / MARKET_MAKER 2.0，:86,94），只在 `stepAgents` :293 `agent.leverage = max(1, agent.leverage·liquidityMultiplier)` 随流动性**微调**，**无繁荣期加杠杆、收缩期强制降杠杆/抛售**的机制 → 明斯基「投机性繁荣 → 突然杠杆崩溃」缺失。
- `MarginManager` / `LeveragedNoteMarket`（`MarketEngine` 字段 :93-94）为**玩家侧**系统，**未接线到 AI agent** 或股本微观结构。

### 5.3 周期相位不改变微观行为
- `EconomicCycle.phaseOf(economicHealth)` + `crisisPressure`（`EconomicCycle.java` :21-35）只向 `crisisLevel` 加 1~2，**不改变** agent 风险偏好/仓位/盘口深度/波动。
- `getRiskAppetite()` 只由恐慌 VIX 驱动（`MacroEnvironment.java:204-208`），与信贷扩张/周期相位无关；`agent.riskAversion`（:290-292）只由 panic 与流动性驱动。

### 5.4 修复方向（明斯基/周期）
1. 建立**周期相位 → agent 行为**传导：`EconomicCycleEngine` 相位（expansion/peak/contraction/trough）驱动 agent 风险偏好/杠杆/交易频率/盘口深度；繁荣期（expansion/peak）agent 加杠杆、压缩价差、加大深度；收缩/萧条强制降杠杆、扩大价差、抛售。
2. 建立**杠杆堆积 → 去杠杆**闭环：`agent.leverage` 在繁荣期随信用扩张上升，收缩期随 `liquidityMultiplier` 与危机等级强制回降 → 触发集中抛售（明斯基 moment）。
3. `CrisisEngine` 输出危机等级/类型 → 喂给 `MarketSentiment` / agent 风险厌恶 / 盘口（恐慌 → 价差扩大、深度下降），形成「危机 → 盘口 → 抛售」真实传导。
4. 危机/杠杆变量**可持久化**（纳入 MarketEngine.save/load 的 NBT/H2 主体），读档后周期连续（沿用 `CrisisEngine.save/load` 既有骨架）。

---

## 6. 验收标准（MS-AI-0..N）

> 全部验收：**固定世界种子**开新存档，跑固定 tick 数；确定性（同种子同结果）；每项不破坏法币 M2 / 黄金核心语义（黄金公式、M2 记账不变）。

### MS-AI-0 可复现性（前置门）
- [ ] 同种子 + 同注册时序 → 同行情、同盘口（跨进程一致）。
- 验证：固定 seed 跑 N tick，两次结果序列逐位一致；所有随机源走 `SeedableRandom` 子流，`-grep Math.random|ThreadLocalRandom|new Random` 在 `engine/agent` 与 `engine/behavior` 无命中。

### MS-AI-1 市场有涨有跌（非一致单边）
- [ ] 观察窗口内（≥ 300 根 TICK bar）：同时存在「上涨」与「下跌」的资产（涨跌家数显著非 0/全，例如 **>20% 资产上涨 且 >20% 下跌**）；无「全市场同一涨跌幅」现象。
- [ ] 单资产对数收益序列出现**正负交替**，非单向漂移；无「恒跌停/恒涨停」定格。
- 根因对应：2.1 (fairValue 不坍塌)、2.2/2.4 (μ 差异化)。

### MS-AI-2 盘口有效深度（非薄无交易）
- [ ] 任意资产任意时段（交易中）最优五档**双侧均有挂单**（bid/ask 各 ≥1 档，且两侧总在盘量之比在合理区间，例如单侧 ≤ 4×另一侧）。
- [ ] 每资产日均成交笔数 ≥ 阈值（例如 ≥ 20 笔/游戏日）；盘口不再「单边挂单、另一侧恒空」。
- [ ] 深度随价格分布合理（远离 best 的档位量递减），无「仅 ±1 tick 一档」。

### MS-AI-3 价差/档位/量级符合现实
- [ ] best bid/ask **价差 > 0 且 ≤ 阈值**（如 ≤ best×0.02），且随波动/深度合理变化（恐慌时扩大）。
- [ ] 挂单价为 0.01 整数倍（A 股 tick），卖价>买价，买卖档位**阶梯上行/下行**无交叉、无乱序。
- [ ] 单笔量级与盘口深度匹配（非恒定 40/极值）；存在**多档**价格（≥3 档/侧）。

### MS-AI-4 fairValue 合理性（遏制坍塌）
- [ ] `stepAgents` 的 `fairValue` 与 `company.getFairValue()`（DCF 体系）一致（或经明确护栏），**无 `0.1` 地板坍塌**；对所有样本 `fairValue ≥ 0.5×bookValuePerShare` 且 `fairValue ≤ 合理上限`。
- [ ] 高成长股 `growth ≥ discountRate` 时不再得到负值/0.1（有安全分母护栏）。

### MS-AI-5 挂单生命周期
- [ ] agent/做市商订单有撤单/重挂逻辑（非只靠整体 `purgeExpired`）；撤单后盘口不出现「挂单无限堆积的假深度」。
- [ ] 订单成交/过期行为确定性（同种子同结果）。

### MS-AI-6 明斯基/金融周期特征
- [ ] 存在「周期相位 → agent 杠杆/风险偏好/盘口深度/波动」的显式传导；繁荣期（expansion/peak）agent 平均杠杆/深度高于收缩/萧条期。
- [ ] 存在**杠杆堆积→强制去杠杆/抛售**事件：某一周期阶段出现集中卖压 + 价差扩大 + 深度骤降（可被观测为盘口/波动的显著恶化），其后恢复正常。
- [ ] 危机（`CrisisEngine` 触发）真实传导到市场（恐慌 → 价差扩大、深度下降、情绪下行），而非只改一个 `-0.03` 情绪值。

### MS-AI-7 持久化兼容（H2/NBT）
- [ ] 上述新状态（agent 杠杆/周期相位/危机/订单生命周期计数器/校准参数）纳入 `MarketEngine.save()/load()` 主体（NBT/H2 blob），存读档后行情/盘口**连续**，读旧档（缺新字段）不失败（contains 兜底）。
- [ ] 黄金定价 `GoldPricingSystem` 与法币 M2 记账路径**未被修改**（回放验证存在性不变）。

### MS-AI-8 不改变已交付语义（回归门）
- [ ] 法币 M2 核心（`ECON_FIAT_M2_SPEC`）与黄金（`NETHERITE_GOLD_SPEC`）验收用例照旧通过；本次改动仅作用于股本微观结构 / agent 行为 / 危机与周期传导。
- [ ] `P2` 总开关 / `endogenous` / `orderFlow` 子开关未来仍可关闭 → 回落既有基线（因子隔离保留）。

---

## 7. 风险与隔离

| 风险 | 缓解 |
|---|---|
| fairValue 修复改变既有行情基线 | 先落 `MS-AI-4` 护栏（最小改动），再逐级放开差异化锚；保留 P2 开关用于基线对比 |
| 做市商多档/周期传导引入发散 | O(agent)/O(资产) 常数级；硬上限（价差/杠杆/波动率）；`MS-AI-6` 监测 |
| 周期→杠杆堆积过强导致崩盘 | 杠杆上限 + 去杠杆梯度软启动 |
| 破坏法币/黄金语义 | 隔离：仅改 `engine/agent`、`engine/microstructure`、`engine/crisis`、`engine/macro`(仅新增周期→agent 传导读取)，不动 GoldPricingSystem / M2 记账 |

---

## 8. 结论

- **①一致下跌的总开关**：`MultiAgentManager.stepAgents` 戈登 fairValue 坍塌到 `0.1` + 全市场共用同一宏观 μ（`PriceDiscoveryEngine.step`），二者叠加 → 系统性单边卖压、无差异化。**修复 = 统一用 `company.getFairValue()` + 安全分母护栏 + 资产差异化 μ。**
- **②盘口薄/无人交易**：单边单笔报单 + 做市商单档薄壁 + ZI 量级过小（40 股/2%）+ 卖压被涨跌停带整批拒单（`withinAShareLimit`）。
- **③挂单不符合现实**：无多档、差价不随 spread/深度、量级不匹配、无撤单生命周期、锚价坍塌到 0.05~0.15。
- **④明斯基缺失**：`CrisisEngine` 无市场传导、agent.preverage 无堆积→去杠杆闭环、周期相位不改变 agent 行为。
- 全部修复须满足 MS-AI-0..8（确定性/持久化/不破坏法币 M2 与黄金语义）。
