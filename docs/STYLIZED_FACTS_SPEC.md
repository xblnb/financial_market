# STYLIZED_FACTS_SPEC.md — 程式化事实全项达标（冷启动估值锚 / 合成流密度 / 成交活跃度）

> 作者角色：architect（方案架构 / 根因诊断与规格）
> 状态：requirements（t1）——产出规格，供 engine-dev / agent-dev 落地，test-engineer 以 MarsFactsHarness 验收，reviewer 复核守边界。
> 依据：t5 权威报告 `.verify/MARS_VERIFY.md`（§6.2/§9）+ 本规格独立源码审计（`MarketEngine` / `PriceDiscoveryEngine` / `MultiAgentManager` / `OrderFlowModel` / `OrderPipeline` / `OrderBook` / `AICompanyData` / `StylizedFactsValidator` / `MarsFactsHarness`）。
> 硬约束：SeedableRandom 确定性、H2/NBT 持久化兼容、不改已交付法币 M2 / 黄金 / GBM-GARCH 核心语义、程式化事实只验证不校准（validator 纯读不回写，避免循环论证）、不过度调参、开新存档测试。

---

## 0. 结论摘要

t5/t6 全链实证确认单一主形态：**纯合成流冷启动 → 价值型持续卖出 → A 股 -10% 跌停带锁死 + 成交稀疏 → tick 级收益序列退化**（大量 0 收益 + 跌停带跳变），导致：

- tick 级峰度极端（κ≈2.9 万，Rama Cont「κ>>3」被数量级放坏而非真实肥尾）；
- 稀疏序列下 Ljung-Box / 杠杆 / 量-波 等稀疏敏感事实不稳定（L2 全资产 PASS 仅 **2/7**：肥尾、粗粒化）；
- 行为支柱 4 项中羊群梯度 FAIL（稀疏订单流下行业同向浓度饱和 0.905 vs 0.933、梯度反向）、前景活动强度方向混合（跌停锁死期亏损代理更活跃、单量被压制）；处置 / 过度交易 PASS；
- L4 样本外两窗口同 PASS 仅 **13/42**（窗口间动力学异质）；
- 微观支柱（出清价差 / VR / EMH）PASS，但价差均值 5.45% 偏高、深度/波动特性仍偏离先验。

**根因链**（详见 §3）：① 冷启动市场价（历史 GBM 路径终点）与 fairValue 解耦且偏高 → VALUE 单向卖出；② 合成订单流密度不足（ZI 基底 0.02/tick、Hawkes λ0=0.005、VALUE_ONLY 阶段只放行 VALUE）→ 双侧流动性薄；③ 一旦单边卖压把价格推向跌停带且买侧无足够量 → 成交稀疏、价格钉死，收益序列退化。

**修复方向**（详见 §4，三杠杆点 + 边界保证）：① 冷启动估值锚合理化（fairValue 稳定合理、开盘价锚定 fairValue、价值型不单向卖出）；② 合成流密度提升（ZI/Hawkes/中观/做市商双侧足量、方向对称、价格带内）；③ 成交活跃度与价差/深度/波动特性改善（连续性 + 跌停带逃逸 + 双侧深度 + 波动率生理量级）。

**验收**（详见 §6）：以 `MarsFactsHarness <seed> <ticks> <assets>` 全项 PASS（harness 14/14，面板 L2 7/7 全资产、行为 4/4、L4 多数同 PASS、微观 3/3）为最终判据；SF-1..SF-5 逐项有明确可测判据。所有引擎改动必须保持确定性、持久化兼容、validator 零回写。

---

## 1. 术语与分层（引用 COLD_START_SPEC.md §0）

| 层 | 组件 | 作用 | 文件 |
|---|---|---|---|
| L1 | 价格发现 | GBM+GARCH 宏观演化 + 每资产差异化漂移 + 连续性钳制 + 卡尔曼软启动 | `engine/PriceDiscoveryEngine.java` |
| L2 | AI 智能体 | VALUE/MOMENTUM/RETAIL/MARKET_MAKER 按 fairValue/内生锚挂限价单 | `engine/agent/MultiAgentManager.java` |
| L2.5 | ZI+Hawkes | 对称随机限价/市价单基底 + 自激励聚集 | `engine/microstructure/OrderFlowModel.java` |
| L2.75 | 中观层 | 板块基金/机构/轮动大单（macro→meso→micro→盘口传导） | `engine/meso/*`、`MarketEngine.placeMesoOrders` |
| L3 | 撮合 | 价格/时间优先 + 深度冲击 + MarS 影响项 + A 股涨跌停带钳制 | `engine/OrderBook.java` |
| 流水线 | 合成订单统一出口 | OrderPipeline 影响修正/整手/带内校验/确定性时间戳 | `engine/microstructure/OrderPipeline.java` |
| 验证 | 只读统计 | StylizedFactsValidator 纯函数、零回写；MarsFactsHarness 真实类链驱动 | `engine/validation/*`、`.verify/harness/MarsFactsHarness.java` |

**验证口径**（`MarsFactsHarness.main(seed, ticks, assets)`，镜像 `MarketEngine.tick()` 全接线）：
- L2 核心事实：tick 级主口径（`computeCoreFacts`，7 项 × 资产数），全资产 PASS 才记「1/7」；
- 行为支柱：处置 / 过度交易 / 羊群 / 前景（引擎级度量）；
- 微观支柱：市场出清价差 / 价格发现 VR(5) / EMH 弱式动量；
- L3 只报告、L4 两窗口同 PASS 计数；
- harness 断言：羊群梯度（a=0.25 浓度 > a=0）、中观层生效、marS 隔离、确定性、持久化、随机源审计。

**验收权威**：`java MarsFactsHarness <seed> <ticks> <assets>`（固定 `engineSeed=20240701`，ticks=120000，assets=6），面板 + harness 断言共同判定。

---

## 2. 权威发现量化基线（t5，必须复测并改善的对照）

| 项 | t5 实测 | 目标 | 出处 |
|---|---|---|---|
| L2 肥尾 κ | 6/6 PASS（均值 2.9 万） | κ>4 保持，但需从「极端退化放坏」回归「生理肥尾」 | MARS_VERIFY §6.1 |
| L2 波动聚集 LB(\|r\|) | 5/6 | **7/7 全资产** | §6.1 |
| L2 收益无自相关 raw-LB | 1/6（0/1 两极，稀疏不稳定） | **7/7 全资产** | §6.1 |
| L2 杠杆效应 | 2/6 | **7/7 全资产** | §6.1 |
| L2 量-波正相关 | 2/6 | **7/7 全资产** | §6.1 |
| L2 长记忆 Hurst | 5/6（均值 0.552） | **7/7 全资产**（Hurst>0.5） | §6.1 |
| L2 粗粒化高斯性 | 6/6 | 保持 | §6.1 |
| 微观出清价差 | median=0.65% / mean=5.45% | median≤2%、mean 回落至近先验 | §6.1/§6.3 |
| 行为处置 | PASS 0.0004>0.0001 | 保持 | §6.2 |
| 行为过度交易 | PASS 0.147 | 保持 | §6.2 |
| 行为羊群 | **FAIL** 0.905 vs 0.933（梯度反向） | **PASS**（a>0 浓度 > a=0） | §6.2 |
| 行为前景 | **FAIL**（活动 gain<loss、单量被压制） | **PASS** | §6.2 |
| 微观 VR(5) | PASS 0.986 | 保持 | §6.3 |
| 微观 EMH 弱式 | PASS 0.00001 | 保持 | §6.3 |
| L4 两窗口同 PASS | **13/42** | 显著提升（目标多数同 PASS） | §6.4 |
| harness 断言 | 13 PASS / 1 FAIL（羊群梯度） | **14/14 PASS** | §0/§9 |

> 注：L4 目标以「同 PASS 项占比显著提升」为判据（阈值/结构不重调），最终以 reviewer 复核面板为准。

---

## 3. 根因诊断（源码审计 + t5/t6 实证）

### 3.1 RC-1 冷启动估值锚解耦 → 价值型单向卖出

**源码事实**：
- `AICompanyData.updateFairValue()`（`AICompanyData.java:155-183`）用 DCF+PE+PB 每股加权给出 `fairValue`；构造时 `ipoPrice = fairValue`（`:86`）。
- `MarketEngine.registerAICompany`（`:621-640`）以 `ipoPrice` 调 `addAsset(id, ipoPrice, vol, drift, industry, 60)`，并 `snap.setPrevClose(ipoPrice)`。
- `PriceDiscoveryEngine.generateHistoricalKlines`（`:258-299`）以 `ipoPrice=fairValue` 为起点、按注册波动率 vol(≈0.25) 与 drift 生成 **60 根 DAILY（=60×240×20=288,000 tick）** 的历史 GBM 路径，**末尾把 `lastPrices` 与 `gbm.price` 覆盖为该路径终点价**（`:296-298`）。
- `MultiAgentManager.valueDecision`（`:541-576`）：`deviation=(lastPrice−fairValue)/fairValue`；`|deviation|<0.02` 不操作，`deviation<0`→买、`>0`→卖。卖出分支要求 `holdings>=size`（已由持仓播种兜底）。

**推论（RC-1）**：开盘 `lastPrice` = 历史路径终点，几乎必然 ≠ fairValue，且因 288,000 tick 高波动路径，可显著偏离 fairValue（尤其上偏）。当 `lastPrice > fairValue`（deviation>0）时，**所有 VALUE 智能体（VALUE_ONLY 阶段唯一活跃类型）同向卖出**。`valueDecision` 无「接近价值即撤」之外的回落机制，买入侧要等价格跌破 fairValue 才出现 → 形成 **单边卖压**。GBM 宏观漂移为正时继续把 `gbm.price` 顶在 fairValue 之上 → 卖压持续，成为 t5「价值型持续卖出」的直接成因。

**缺陷点**：① 历史 K 线终点与 fairValue 解耦（价格锚 ≠ 价值锚）；② VALUE 决策对 `deviation` 的线性正反馈无方向平衡；③ 冷启动前 500 tick 仅 VALUE 活跃（`isAgentTypeActive` VALUE_ONLY），无对手方流动性。

### 3.2 RC-2 合成流密度不足 → 双侧流动性薄、订单到达稀疏

**源码事实**：
- `OrderFlowModel` 默认 ZI 基底到达率 `ziBaseRate=0.02`（每资产每 tick 2%），Hawkes 背景 `hawkesLambda0=0.005`、触发 `K=0.02`、`β=0.15`、`maxBranch=3`（`OrderFlowModel.java:53-56`）。`hawkesArrival` 概率 `min(1, λ·dt)`，`dt=20/24000≈8.3e-4` → 单笔到达概率极低。
- `MultiAgentManager.stepAgents`（`:462`）下单门 `assetRng.nextDouble() > tradeFrequency*0.3*riskAppetite` → 每 agent 每 tick 下单概率约 `0.3×~0.3×0.5≈4.5%`，而 6 资产每资产仅 ~24 agent（`initializeAgents` count≈20~40），且 VALUE_ONLY 阶段只 VALUE 可下单。
- 微观均值单量 1665 股（t5 实证），ZI 单笔均值 40 股、上限 60 股 → 单量小。
- `OrderPipeline.toOrder` 对 agent（urgency=0.5）与 zi/hawkes 限价单做带内校验：**当价格在跌停带附近、`bandActive=true` 且限价越带 → 订单被拒**（`OrderPipeline.java:129-134`），进一步压低接近跌停时的有效订单流。

**推论（RC-2）**：订单到达率 × 每单量 × 双向对称性 三者均低 → 每 tick 有效订单流稀疏，盘口双侧深度薄，无法吸收单边卖压，也无法为「卖出后价格回归 fairValue」提供足量对手方买盘。

### 3.3 RC-3 跌停带锁死 + 成交稀疏 → 收益序列退化

**源码事实**：
- 撮合 `walkBook`/`matchWithSlippage` 把成交价 `aShareClamp(…, limitUp, limitDown)` 钳到以 `prevClose`（=ipoPrice=fairValue）锚定的 ±10% 带内（`OrderBook.java:282/403`）。
- 当单边卖压把价格推到 `limitDown` 且**买侧 resting 量不足**时：卖单挂不到带内更低位置（带内拒绝/钳制），买单又因价格高于 fairValue 而不主动出现 → 成交笔数骤降，价格钉在跌停价。
- 收益序列在「正常波动区间」与「跌停价长时间钉死（0 收益）」之间跳变 → 大量 0 收益 + 跳变脉冲 → **峰度被极端放坏（κ≈2.9 万）**；Ljung-Box/杠杆/量-波在含大量 0 收益的序列上样本不稳定（0/1 两极）；成交量在跌停期归零 → 量-波正相关被破坏。

**推论（RC-3）**：RC-1（单边卖压）+ RC-2（流动性薄）共同触发 RC-3（跌停锁死）。三者级联即 t5 全链形态。修复必须从源头（RC-1 估值锚）与供给端（RC-2 密度）同时入手，仅靠后处理无法根治。

### 3.4 RC-4 行为/微观下游

- **羊群梯度 FAIL**：`herdConcentration` 由每 tick 行业同向比例（≥2 单才计入）聚合；稀疏订单流下单量少 → 少数几单即决定同向比例，a=0.25 与 a=0 的浓度都饱和到 ~0.9 且不可分辨（t5 §6.2）。订单密度提升 + 方向对称后梯度可分辨。
- **前景活动强度方向混合**：t5 实证「跌停锁死期亏损代理更活跃、单量被压制」——`prospectPropensity` 在 `avgCost>0` 时按盈亏缩放，但跌停锁死期 `avgCost` 参考点 + 强卖压使方向混合。修复 RC-1/RC-3 消除锁死后，`agentProspect` tally 将反映正常前景强度方向。
- **价差 mean 偏高**：`spreadSamples` 含双侧有档才记录；跌停/稀疏期 best bid/ask 拉距 → mean 被极端样本抬高。流动性提升后价差收敛。

---

## 4. 修复设计（三杠杆点 + 边界保证）

> 原则：从**模型机制**上消除退化（估值锚、密度、连续性），**不做 validator 阈值微调**（validator 保持只读，避免循环论证）；所有新增随机走 `SeedableRandom.derive` 子流；不改法币 M2 / 黄金 / GBM-GARCH 核心公式；**不做过度调参**——每个参数有单一用途与先验依据，改动仅限 cold-start 与订单流供给端。

### 4.1 Fix-1 冷启动估值锚合理化（RC-1）

目标：开盘价贴近 fairValue、value 型不单向卖出、fairValue 自身合理稳定。

| 桩 | 建议实现（engine-dev） | 依据 |
|---|---|---|
| F1.1 历史 K 线终点锚定 fairValue | `generateHistoricalKlines` 生成路径后，将**终点价钳/映射回 fairValue**（如最后若干 tick 以 `fairValue` 为锚收敛，或以 `endPrice = fairValue` 重写 `lastPrices/gbm.price`），使开盘 `lastPrice≈fairValue`（deviation≈0，VALUE 不触发单向卖出）。保持历史 K 线形状不变（只调终点/前 60 根日K），确定性由子流保证。 | RC-1 |
| F1.2 开盘价/prevClose 一致性 | `registerAICompany` 已 `setPrevClose(ipoPrice=fairValue)`；确保 `AssetSnapshot.lastPrice` 与 `gbm.price` 初始值同为 fairValue（而非历史路径终点），消除「prevClose=fairValue 但 lastPrice=路径终点」的量纲错配。 | RC-1 |
| F1.3 fairValue 冷启动稳定性 | `updateFairValue` 折现率 `0.08+debtRatio*0.1` 与 DCF 分母 `discountRate−growthRate`（growthRate 2%~8%）——确认冷启动 `discountRate>growthRate` 恒成立（`debtRatio∈[0.2,0.6]`→discountRate∈[0.10,0.14]），杜绝分母≤0 退化；`safeFairValue` 护栏（`MultiAgentManager.java:762`）已兜底，保持。 | RC-1 |
| F1.4 VALUE 决策方向平衡（可选，agent-dev） | `valueDecision` 对 `deviation` 加**死区 + 温和回落**：`|deviation|<2%` 不操作（已有），当价格显著高于 fairValue 时卖单限价不越过 fairValue 之下，避免把价格钉向跌停；本质仍由 F1.1 开盘锚定保证。**不改 fairValue 公式、不加新随机**。 | RC-1/RC-3 |

> 验收对应：SF-1。**本桩是「价值型不单向卖出」的根治点**——开盘价贴近 fairValue 后 VALUE 决策围绕价值双向，不再持续单向卖。

### 4.2 Fix-2 合成流密度提升（RC-2）

目标：订单到达足量、方向对称、带内有效，避免价格退化/跌停锁死/成交稀疏。

| 桩 | 建议实现（agent-dev / engine-dev） | 依据 |
|---|---|---|
| F2.1 ZI 基底密度 | 提升 `ziBaseRate`（如 0.02→0.05~0.08，先验：现实每资产每秒级订单到达）与 `ziLotMean`/`ziLotDispersion`（40/60→与盘口 depthAtBest 同量级的百股级），保持 `ziBuyProb=0.5` 对称。从 `engineSeed` 派生子流不变 → 确定性保持。 | RC-2 |
| F2.2 Hawkes 聚集 | 适度提升 `hawkesLambda0`/`K`（仍满足 `K·maxBranch<0.1` 硬约束），使聚集在订单密度提升后仍产生波动聚集信号（供 LB(\|r\|)、量-波）。 | RC-2/Rama Cont 波动聚集 |
| F2.3 做市商早介入 + 双侧报价 | `MARKET_MAKER` 提前到 `VALUE_MAKER`（t<500 即引入，或把 `VALUE_ONLY_END` 前移），保证开盘即有双侧深度吸收单边卖压；`marketMakerQuotes` 已多档双侧（`MultiAgentManager.java:688-732`），确保在 VALUE_ONLY 早期也注入。 | RC-2/RC-3 |
| F2.4 中观大单持续供给 | 确认 `mesoLayer` 宏观步持续产出（t5 实证平均 40567 股/单）；保持其在 `marS.enabled` 下的正常供给，作为流动性提供方补充（不改中观核心）。 | RC-2 |
| F2.5 带内有效订单 | 保持 OrderPipeline 带内校验，但确保 ZI/Hawkes 限价单贴近 best bid/ask（`ziLimitOffset` 1~3 tick）提高成交概率，减少被带内拒绝比例。 | RC-3 |

> 验收对应：SF-2。密度提升后订单流足量、双侧对称，价格在 fairValue 带内双向波动，不退化/不锁死/不稀疏。

### 4.3 Fix-3 成交活跃度与价差/深度/波动特性（RC-3 + Rama Cont 事实）

目标：成交连续、价差收窄、深度合理、波动率生理量级，使收益序列恢复真实肥尾（非退化放坏）。

| 桩 | 建议实现（engine-dev） | 依据 |
|---|---|---|
| F3.1 连续性钳制（已有） | 确认 `clampForContinuity`（`PriceDiscoveryEngine.java:216`，M=0.05）在 `step()` 与 OrderBook 成交价均生效；保持。 | RC-3 |
| F3.2 跌停带逃逸机制 | 当价格触及跌停带且存在**愿意在带内最高价买入**的对称需求（ZI 买 / 做市商买盘 / 中观买）时，允许成交恢复价格发现，避免长时间钉死。本质由 F2.3 双侧深度保证；不降低涨跌停带本身（`A_SHARE_LIMIT_RATIO=0.10` 不动）。 | RC-3 |
| F3.3 波动率生理量级 | 卡尔曼软启动 `getNoiseMultiplier=1−exp(−(t/τ)²)` 已修（τ=50）；确认宏观 `dynamicVolatility` 初始值合理（≈0.2~0.3 年化），避免冷启动初期波动过小造成收益序列大量 0。不改 GBM-GARCH 公式。 | RC-3/Rama Cont |
| F3.4 成交活跃度 | 密度提升（F2）+ 双侧流动性（F2.3）共同使 `tickVolumes` 连续非零、`spreadSamples` 双侧有档比例提高 → median 价差收敛、深度 profile 连续。 | RC-3/微观出清 |

> 验收对应：SF-3。成交活跃、价差/深度/波动特性改善，收益序列连续 → L2 稀疏敏感事实（raw-LB/杠杆/量-波）可全资产 PASS，κ 从极端退化回落到生理肥尾（κ>4 但仍远离正态 3）。

### 4.4 Fix-4 确定性 / 持久化 / 守边界（横切）

| 保证 | 内容 |
|---|---|
| 确定性 | 所有新增随机一律 `SeedableRandom.derive(...)`（`engineSeed` 派生）；不改 `OrderFlowModel`/`MultiAgentManager` 的子流派生基准（同种子可复现）；禁 `Math.random/new Random/ThreadLocalRandom`；`MarsFactsHarness.auditRandomSources` 必须 PASS。 |
| 持久化 | 新增状态（若 ZI/Hawkes 参数改为运行时配置、或新增冷启动锚定状态）一律入 `save()/load()` 并经 `contains` 兜底；旧档（仅 engineSeed）读档不失败；`init()` 与 `loadInternal` 严格分离——冷启动锚定只在 `init()` 执行，读档不重播。 |
| 守边界 | 不改法币 M2 / 黄金定价公式；不改 GBM-GARCH `step()` 公式（仅连续性钳制叠加项，已存在）；不改 `A_SHARE_LIMIT_RATIO=0.10` 涨跌停语义；validator 纯读零回写；`marS.enabled=false` 回落既有基线（因子隔离）。 |
| 不过度调参 | 每个新参数单一用途、有先验依据、集中在订单流供给端；不做 validator 阈值微调；改动收敛、可回滚（总开关/参数默认值）。 |

---

## 5. 边界与不可触碰清单

1. **法币 M2 / 黄金核心**：`CentralBank/InflationSystem/Bank/GoldPricingSystem` 零改动（grep 审计复核，MARS_VERIFY §7）。
2. **GBM-GARCH 核心公式**：`PriceDiscoveryEngine.step()` 的 GBM/GARCH 演化不改；仅保留连续性钳制叠加项。
3. **涨跌停语义**：`A_SHARE_LIMIT_RATIO=0.10`、`aShareClamp/withinBand` 不变。
4. **validator 只验证不校准**：`StylizedFactsValidator` 不改阈值、不回写任何引擎参数；面板保持只读统计。
5. **确定性 / 持久化 / 旧档兼容**：见 §4.4。
6. **AICompanyManager 的 `java.util.Random(seed)` 共享流**（行业/代码/趋势）消费顺序不得改动/替换（冷启动只新增独立子流，不得重排其取数）。

---

## 6. 验收标准（SF-0..N，开新存档，MarsFactsHarness 为准）

> 统一验证命令（engine-dev/agent-dev 每次改动后复测）：
> `gradlew.bat compileJava && gradlew.bat build -x test`
> `gradlew.bat -I .verify\harness\printcp.init.gradle printRuntimeClasspath`
> `javac -encoding UTF-8 -cp <runtime_cp> -d .verify/harness/mars-classes .verify/harness/MarsFactsHarness.java`
> `java -Xmx4g -cp <runtime_cp>;.verify/harness/mars-classes MarsFactsHarness 20240701 120000 6`

| 编号 | 验收项 | 判据（可测） | 归属 |
|---|---|---|---|
| **SF-0** | 全项达标（最终门） | `MarsFactsHarness` 输出 **14/14 PASS（0 FAIL）**；面板 L2 **7/7 全资产 PASS**、行为支柱 **4/4 PASS**、L4 两窗口同 PASS **≥ 全部事实中的显著多数**、微观支柱（出清/VR/EMH）PASS | test-engineer |
| **SF-1** | 冷启动估值锚 | ① 开盘价 `lastPrice≈fairValue`（deviation≈0，无单边卖压）；② VALUE 决策在开盘期双向（买/卖订单均有，非恒卖）；③ 无「价值型持续卖出→跌停锁死」形态（成交序列连续，`tickVolumes` 非长期为 0）；④ `safeFairValue`/`updateFairValue` 冷启动无退化（无 fairValue≤0/分母≤0 日志） | engine-dev |
| **SF-2** | 合成流密度 | ① 每资产每 tick 有效订单到达率显著提升（订单日志密度可观测）；② ZI 方向对称（`ziBuyProb=0.5` 保持，买/卖订单均产生）；③ 无价格退化/跌停锁死/成交稀疏（`spreadSamples` 双侧有档比例≥90%，`tickVolumes` 有成交的 tick 占比显著提升） | agent-dev |
| **SF-3** | 成交活跃度/价差/深度/波动 | ① 相对价差 median≤2%（L1）；② depthAtBest/depth profile 连续合理（L1 多档 + 不无限堆积）；③ κ>4 保持但显著回落（从 ~2.9 万降到生理肥尾量级，如 4~数百），不再由退化放坏；④ 成交量-波动率正相关、波动聚集、长记忆 Hurst 等 L2 事实全资产 PASS | engine-dev |
| **SF-4** | 程式化事实全达标 | 面板 L2 **7/7 全资产**、行为 **4/4**、微观 3/3、L4 多数同 PASS；Rama Cont 全部事实（肥尾/波动聚集/无自相关/杠杆/量-波/长记忆/粗粒化）达标；行为（处置/过度交易/羊群/前景）达标；微观（出清/价格发现/EMH 弱式）达标 | test-engineer |
| **SF-5** | 确定性/持久化/守边界 | ① 同种子两次运行 `chainHash` 一致（harness 断言 PASS）；② `save/load` 往返恢复行情、旧档（仅 engineSeed）读档不失败（harness 断言 PASS）；③ `marS.enabled=false` 隔离回落基线（断言 PASS）；④ grep 复核 M2/黄金/GBM-GARCH 核心零改动；⑤ validator 零回写（随机源审计 + 只读复核） | test-engineer / reviewer |

**SF-1..SF-5 均以「SF-0 最终门」为验收上限**：若某项修复满足 SF-1 但 SF-0 未全 PASS，则该修复有效但未达目标，须按 SF-0 逐项回查（不改 validator 阈值）。

---

## 7. 落地文件清单（按功能分派）

**engine-dev（Fix-1 + Fix-3 + F2.1/F2.2/F2.4）**
- `engine/PriceDiscoveryEngine.java`：`generateHistoricalKlines` 终点锚定 fairValue（F1.1）；确认连续性钳制/卡尔曼软启动生效（F3.1/F3.3）；`addAsset`/`registerAICompany` 初始价一致性（F1.2）。
- `engine/agent/MultiAgentManager.java`：确认 `safeFairValue`/`updateFairValue` 冷启动无退化（F1.3，只读复核）。
- `engine/microstructure/OrderFlowModel.java`：ZI 基底密度/单量、Hawkes 聚集参数（F2.1/F2.2，先验、单一用途）；`save/load` 同步（若参数改运行时配置）。
- `engine/MarketEngine.java`：`registerAICompany` 初始价/prevClose 一致性（F1.2）；中观持续供给确认（F2.4）；冷启动锚定只在 `init()` 执行。

**agent-dev（Fix-1 平衡 + Fix-2 密度 + F2.3）**
- `engine/agent/MultiAgentManager.java`：VALUE 决策方向平衡（F1.4，可选）；做市商提前到 `VALUE_MAKER`/`VALUE_ONLY` 早期双侧报价（F2.3）；确认 `isAgentTypeActive` 消费与持仓播种（已有）。
- `engine/microstructure/OrderFlowModel.java`：方向对称（`ziBuyProb`）、单量/限价贴近 best（F2.1/F2.5）。

**test-engineer（SF-0/SF-4/SF-5 验收）**
- 复测 `MarsFactsHarness` 全项；记录面板 L2/行为/微观/L4；复核确定性/持久化/隔离；将 t5 基线更新为本规格 §2 目标对照。

**reviewer（守边界复核）**
- grep 复核 M2/黄金/GBM-GARCH 零改动；validator 零回写；`marS.enabled=false` 隔离；不过度调参（新参数用途单一、可回滚）。

---

## 8. 风险与后续

- **风险 1**：ZI 密度提升若方向不对称或单量过大，可能放大冲击而非修复——必须保持 `ziBuyProb=0.5` 对称、单量与盘口 depth 同量级（F2.1/F2.5）。
- **风险 2**：`generateHistoricalKlines` 终点锚定会改变冷启动开盘价，若锚定方式引入新随机或非确定性，破坏 SF-5——必须用既有 `gbm.rng` 子流、确定性重写。
- **风险 3**：做市商提前介入可能使盘口「假深度」过厚——`marketMakerQuotes` 已有 `deviation` 门控（价格严重偏离价值时不报价），保持该门控。
- **风险 4**：L4 两窗口同 PASS 与订单密度/宏观步非平稳冲突——修复后先观察 `marsFactsHarness` 面板，若 L4 仍低但 L2 全 PASS，属动力学异质（如实报告，不调阈值）。
- **风险 5**：若某桩改动使 `marS.enabled=false` 基线漂移（因子隔离破坏）——所有 Fix 必须由 `marS`/`p2`/`orderFlow` 开关门控或仅在 cold-start 生效，复测隔离断言。

> **行号时效性**：本规格行号为审计快照时点版本，仓库正被并行实现者实时编辑。实现者以方法名/注释/语义为准，行号仅供定位。本规格作为需求与验收口径。
