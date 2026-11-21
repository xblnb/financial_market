# MARS_PORT_SPEC — MarS 移植需求规格（三层智能体 / 市场影响模型 / 订单流水线 / 验证框架）

> - 角色：architect（方案架构 / MarS 移植规格）为任务 t1（requirements）产出。
> - 输入：微软 MarS 论文（[arxiv 2409.07486](https://arxiv.org/abs/2409.07486)，ICLR 2025）+ [github.com/microsoft/MarS](https://github.com/microsoft/MarS) 审计 + 当前源码审计（本规格对照实际源码给出文件/行号/方法签名）+ 已交付前置（`ECON_P0/P1/P2_SPEC.md`、`MICRO_AI_SPEC.md`、`ECON_FIAT_M2_SPEC.md`、`NETHERITE_GOLD_SPEC.md`、`A_SHARE_TRADING_SPEC.md`、`ECON_REMAINDER_SPEC.md`）。
> - 用途：agent-dev 按本规格实现 MP-1（t2，三层智能体体系）；engine-dev 按本规格实现 MP-2/MP-3（t3，市场影响模型 + 订单流水线）；test-engineer 按本规格实现 MP-4/MP-5（t4，验证框架 + 确定性/持久化/回归）；reviewer 按本规格验收（t5）。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick 步进内 O(agent+资产)/tick 常数级计算；**确定性（同 `engineSeed` 可复现）**；**H2/NBT 持久化兼容**；**不改已交付法币 M2 / 黄金核心语义**；**开新存档测试**。
> - 范围：仅团队 Goal 点名的 MarS 可移植点（三层智能体、市场影响、订单流水线、验证框架）。P0/P1/P2/A 股/法币 M2/黄金语义**不得回归**。

---

## 0. 结论速览（TL;DR）

| MarS 概念（论文/仓库） | 现状（代码审计） | 本规格动作 |
|---|---|---|
| ① 三层智能体（宏观央行/政策层、中观机构/板块层、微观做市商/方向性/流动性/背景噪声层） | 宏观层已交付（`CentralBank`/`Government`/`MacroEnvironment`/`EconomicCycleEngine`/`CrisisEngine`）；微观层已交付（`MultiAgentManager` 四类 + `OrderFlowModel` ZI/Hawkes）；**中观机构/板块层缺失**；三层间无「政策→机构→做市商→盘口」的显式分层传导 | **MP-1**：新增中观层（板块基金/机构投资者/板块轮动），把宏观政策折叠为确定性 `MacroSnapshot`，微观层规范化四角色，三层显式接线 |
| ② 市场影响模型 Δ=α·σ·√(Q/V)（下单量→价格影响，与波动率/深度有关） | `MarketImpact.impact()` 只有深度冲击 `k_sqrt·√(s/d₀)+k_linear·(s/d₀)`，**无 σ、无 V 项**；撮合滑点已替换为深度冲击（P2） | **MP-2**：新增 MarS 影响项 `Δ=α·σ·√(Q/V)`，叠加进 walkBook 与订单生成（挂单量→价格影响），σ 取资产 GARCH 波动、V 取滚动成交量 ADV |
| ③ 订单生成标准化流水线（价格/数量/方向/时间戳→订单簿） | 订单生成分散在 `MultiAgentManager.stepAgents`（OrderFlow）+ `MarketEngine.tick` L2/L2.5 直接 new `Order`，**无统一流水线**；`Order` 时间戳用 `System.currentTimeMillis()`（非确定，虽不参与撮合） | **MP-3**：新增 `OrderPipeline`（Intent→影响修正→校验/规范化→Order），全部 agent/中观订单走统一流水线，时间戳由 `tickCounter` 派生（确定） |
| ④ 验证框架：一个核心（程式化事实）/三大支柱（金融学/行为经济学/微观经济学）/四层级（定性→程式化事实→分布拟合行为→样本外） | `StylizedFactsValidator` 已有核心事实大部分（峰度/LB/自相关/杠杆/量-波相关/P2 价差深度冲击）；**缺**：收益率 raw-LB、长记忆 Hurst、粗粒化高斯性、行为支柱引擎级度量、微观支柱（价格发现/EMH）、L3 分布拟合、L4 样本外 | **MP-4**：扩展验证器为「一个核心/三大支柱/四层级」面板，**程式化事实只用验证不用校准**（纯读，不回写参数、不过度调参） |
| ⑤ 确定性/持久化/边界 | `SeedableRandom` 子流模式已确立；`MarketEngine.save/load`（NBT+H2 `engine_kv.engine_main`）已有；法币 M2/黄金已交付 | **MP-5**：新增状态（中观层、ADV、pipeline）纳入 save/load，`contains` 兜底；`marS.enabled` 总开关；不改 M2/黄金公式；开新存档测试 |

---

## 1. 全局验收（对应团队 Goal，MP-0..MP-6）

完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| MP-0 | 编译通过 + 可复现门 | `./gradlew compileJava` 退出码 0；同 `engineSeed` 跑固定 tick 两次，成交价序列逐元素一致；`grep -rn "Math.random\|new Random\|ThreadLocalRandom" src/main/java` 无本次新增调用 | 全部 + t4 |
| MP-1 | **三层智能体体系** | 宏观政策层（`MacroSnapshot` 确定性快照）+ 中观机构/板块层（板块基金/机构/轮动）+ 微观四角色（做市商/方向性/流动性/背景噪声）显式分层；政策→机构→做市商→盘口传导可观测；同种子可复现 | **agent-dev t2** + t4 |
| MP-2 | **市场影响模型 Δ=α·σ·√(Q/V)** | `MarketImpact` 新增 MarS 影响项；大单 vs 小单价格冲击差异显著（MP-2-4）；影响随波动率 σ 增大而增大、随成交量 V 增大而减小（MP-2-5/2-6）；P2 关闭时仍回退 P0 纯随机滑点（隔离） | **engine-dev t3** + t4 |
| MP-3 | **订单生成标准化流水线** | 全部合成订单（微观 agent / 中观 / ZI-Hawkes / 自营）经 `OrderPipeline` 生成；订单含确定性时间戳（tickCounter 派生）；限价/数量经 tick 0.01 / 整手 100 / 涨跌停带 / T+1 / 休市校验；P2/P0 关闭语义不回归 | **engine-dev t3** + t4 |
| MP-4 | **验证框架（一个核心/三大支柱/四层级）** | 核心程式化事实全项（峰度>4、LB(|r|) 显著、raw-LB 不显著、杠杆效应<0、量-波正相关、长记忆 Hurst>0.5、粗粒化高斯性）；行为支柱（处置效应/过度交易/羊群/前景理论）；微观支柱（出清价差/价格发现/EMH 弱式）；四层级（定性→程式化→分布拟合→样本外）输出；**只验证不校准** | **test-engineer t4** |
| MP-5 | 确定性/持久化/边界 | 新增状态全部入 `MarketEngine.save()/load()`（NBT + H2 `engine_kv.engine_main` blob），旧档缺字段 `contains` 兜底不失败；`marS.enabled=false` 时回落既有行为（因子隔离）；法币 M2/黄金验收用例照旧通过 | 全部 + t4 |
| MP-6 | 不回归门 | `P2`/A 股/法币/黄金既有验收用例照旧通过（gold 公式、M2 记账不变）；`P2` 关→P0 基线逐位一致保持 | t4 |

**验收流程（t4 落地）**：固定 `engineSeed` 开新存档，跑固定 N tick；输出 MarS stylized facts 面板（L2）+ 分布拟合（L3）+ 两窗口样本外对比（L4）；两次同种子运行成交价哈希一致（MP-0）；save→load 往返后行情连续（MP-5）；`marS.enabled=false` 与基线对比（隔离）；黄金/M2 回归用例照旧（MP-6）。

---

## 2. 现状诊断（代码审计结论，MarS 移植视角）

### 2.1 宏观层（政策层）——已交付，需「折叠为快照」

| 组件 | 位置 | 现状 |
|---|---|---|
| 央行 | `engine/banking/CentralBank.java` | `policyRate/discountRate/reserveRequirement/baseMoney/OMO/逆回购`；已接入 `MacroEnvironment.setPolicyRate`（`MarketEngine.java:1124-1125`） |
| 政府 | `engine/banking/Government.java` | `fiscalStance/treasury/taxRate`；`TaxSystem` 已征缴 |
| 宏观环境 | `engine/macro/MacroEnvironment.java` | OU r/p/v（`:138-164`）；`getDynamicDrift`（`:177`）/`getDynamicVolatility`（`:188`）/`getLiquidityMultiplier`（`:199`）/`getDiscountRate`（`:233`）/`getRiskAppetite`（`:242`）；`applyMacroTransmission`（`:260-271`）把周期/危机折叠进环境向量（MS-AI-6） |
| 经济周期 | `engine/macro/EconomicCycleEngine.java` + `engine/crisis/EconomicCycle.java` | `economicHealth` → `phaseOf`（EXPANSION/PEAK/CONTRACTION/TROUGH）→ 杠杆/波动/价差/深度/风险溢价乘子（纯函数） |
| 危机 | `engine/crisis/CrisisEngine.java` | `crisisLevel/currentCrisis` → 宏观张力 |
| 宏观状态 | `engine/MacroStateEngine.java` | `riskAppetite/creditImpulse/inflationPressure`（tick 已接线，`MarketEngine.java:1131`） |

**结论**：宏观政策层实体已齐，缺一个**确定性只读快照** `MacroSnapshot`（policyRate/reserveRequirement/fiscalStance/cyclePhase/crisisLevel/creditGrowth/m2Growth/inflation/liquidityMultiplier/riskAppetite/discountRate/dynamicDrift/dynamicVolatility），供中观/微观层按同一视图消费（MarS 的 Market Condition / Environment Model 对应物）。

### 2.2 中观层（机构/板块层）——**完全缺失**（本规格核心新增）

- 无板块基金、无机构投资者、无板块轮动；`AICompanyData.getIndustry()`（`engine/ai/AICompanyData.java:210`）有 8 行业（`AICompanyManager.java:21`：MINING/TECH/AGRICULTURE/ENERGY/LOGISTICS/FINANCE/CONSTRUCTION/MAGIC）但**行业信息只用于羊群分组**（`MultiAgentManager.java:370` `herding.setGroup(group, ...)`），无行业级组合/资金流。
- `SecuritiesManager`（自营/研报/回购）是玩家侧证券业务，非市场内生机构层。
- 微观层 20~40 agent/资产（`MarketEngine.java:470` `initializeAgents(id, 20+engineRng.nextInt(20))`），全部平铺在单资产上，**无跨资产/跨行业组合视角**。
- MarS 对照：Agent Network 的机构/板块代理（组合再平衡、板块轮动、风格切换）缺失 → 微观订单流缺乏「中观资金流」的真实性（板块同涨同跌、轮动、机构大单）。

### 2.3 微观层（做市商/方向性/流动性/背景噪声）——已交付，需规范化

| 角色 | 现状 | 位置 |
|---|---|---|
| 方向性-基本面 | `AgentType.VALUE` → `TraderClass.FUNDAMENTALIST` | `MultiAgentManager.java:16-36` |
| 方向性-趋势 | `MOMENTUM` → `CHARTIST` | 同上 |
| 流动性/散户 | `RETAIL` → `NOISE` | 同上 |
| 做市商 | `MARKET_MAKER` → `LIQUIDITY_PROVIDER`，多档双边报价 | `marketMakerQuotes` `:598-642` |
| 背景噪声 | `OrderFlowModel` ZI 零智能 + Hawkes 自激励 | `engine/microstructure/OrderFlowModel.java:30-106` |

- 决策函数：`stepAgents`（`:272-437`）、`valueDecision/retailDecision/momentumDecision/marketMakerQuotes`（`:451-642`）；产出 `PriceDiscoveryEngine.OrderFlow{isBuy,price,size}`。
- 行为因子：羊群 `HerdingModel`、前景/处置 `BehaviorUtility`（`engine/behavior/`）已接线。
- **结论**：角色齐全，但订单产出是「直接 new Order」，无统一流水线；需规范化角色枚举 + 统一订单产出路径（MP-3）。

### 2.4 市场影响模型——已有深度冲击，缺 MarS 的 σ√(Q/V)

- `engine/microstructure/MarketImpact.java:45-54`：`impact(cumulativeSize, depth0, midPrice, isBuy) = sign·(k_sqrt·√(s/d₀) + k_linear·(s/d₀))·mid`——**只依赖最优档深度 d₀**，与资产波动率 σ、市场成交量 V 无关。
- 消费点：`OrderBook.walkBook`（`OrderBook.java:225-278`，`:246` `impactModel.impact(cumQty, d0, midPrice, buyAggressor)`）；`matchWithSlippage`（`:299-353`）。
- σ 来源存在：`GbmState.sigmaSq`（`PriceDiscoveryEngine.java:71-77, 335-342`，GARCH 条件方差，可开方为 σ_t）。V 来源缺：无每资产滚动成交量统计（`tradePoints` 有成交点，`PriceDiscoveryEngine.java:638` `getTradePoints`）。
- **结论**：把 `MarketImpact` 扩展为 MarS 形式（新增 `impactMarS`，保留 `impact` 向后兼容），σ 取 `sqrt(GARCH sigmaSq)` 或宏观 `getDynamicVolatility`，V 取滚动 ADV（新增 `VolumeTracker`）。

### 2.5 订单生成——无标准化流水线

- `MarketEngine.tick` L2（`:1223-1269`）：`agentManager.consumeOrders(assetId)` → 逐 OrderFlow `new Order(UUID.randomUUID(), assetId, type, size, price)` → `withinAShareLimit` → `book.placeOrder`。
- L2.5（`:1272-1315`）：ZI/Hawkes 同样直接 `new Order(ZI_PLAYER_ID, ...)`。
- `Order` 构造（`engine/Order.java:47,54`）：`UUID.randomUUID()` + `System.currentTimeMillis()`——**时间戳非确定**（不参与撮合排序，撮合用 `placedTick`，但验证/统计若读 timestamp 则不可复现）。
- 校验分散：涨跌停在 L2 内联（`:1258`），整手/一手在别处；无统一入口。
- **结论**：新增 `OrderPipeline`，把「决策（Intent）→ 市场影响修正 → 校验/规范化 → Order（确定性时间戳）」收敛为唯一合成订单出口（MP-3）。

### 2.6 验证框架——核心事实大部分已有，缺三大支柱/四层级

`engine/validation/StylizedFactsValidator.java`（1482 行）已有：
- 核心事实：`computeMetrics`（`:114-183`）峰度/超额峰度、LB(|r|)/LB(r²) + p 值、acf(r)/acf(|r|)、杠杆效应 corr(r,r²₁)/corr(r,|r₁|)、短期动量/长期反转、`formatReport`（`:359-391`）。
- P2 面板：`P2Metrics`（`:708-749`）价差 mean/median、depthAtBest、depthDecay、impactCoeff、largeVsSmall、**volumeVolCorr（量-波相关）**、tailMu（Hill）、牛熊波动不对称；`computeP2Metrics`（`:1182`）、`formatReportP2`（`:1352-1377`）。
- 行为静态证明：`printDispositionAsymmetry`（`:1380-1404`）处置效应不对称。
- 引擎驱动：`runAgainstEngine`（`:1415`）/`runSimulated`（`:1437`）→ 但**不驱动真实 `MarketEngine.tick` 全链**（无 `ServerLevel`）。

**缺口**（对照用户「一个核心/三大支柱/四层级」）：
1. 收益率 raw 序列 Ljung-Box（无自相关检验项，只有 autocorr 展示）——**缺 raw-LB**。
2. 长记忆（Hurst 指数）——**缺**。
3. 粗粒化高斯性（聚合 k=1,5,20 峰度递减向 3）——**缺**。
4. 行为支柱引擎级度量：处置效应 sellP(gain)>sellP(loss)、过度交易（换手/成交频率）、羊群强度（群体净方向集中度）、前景理论损失厌恶——**缺引擎级**（只有静态函数证明）。
5. 微观支柱：市场出清价差（已有）、**价格发现效率**（相对 fairValue 的收敛/方差比）、**EMH 弱式**（raw 收益无自相关 + 无简单动量超额）——**缺后两者**。
6. L3 分布拟合（收益 Student-t 拟合、订单量分布、价差/深度 vs 校准先验）——**缺**。
7. L4 样本外（两窗口/两周期相位对比，参数不重调）——**缺**。
8. 四层级结构化输出（L1 定性断言 → L2 事实面板 → L3 拟合 → L4 样本外）——**缺组织**。

---

## 3. 需求契约：MP-1 — 三层智能体体系（agent-dev，t2）

### 3.1 改动文件

- 新增 `src/main/java/com/example/financialmarket/engine/macro/MacroSnapshot.java`（纯 Java 只读快照 record）
- 新增 `src/main/java/com/example/financialmarket/engine/meso/MesoLayerManager.java`（中观层总控）
- 新增 `src/main/java/com/example/financialmarket/engine/meso/SectorFundAgent.java`（板块基金）
- 新增 `src/main/java/com/example/financialmarket/engine/meso/InstitutionalAgent.java`（机构投资者）
- 新增 `src/main/java/com/example/financialmarket/engine/meso/SectorRotationModel.java`（板块轮动确定性模型）
- 修改 `src/main/java/com/example/financialmarket/engine/MarketEngine.java`（接线中观层 tick/save/load + `marS.enabled` 总开关）
- 修改 `src/main/java/com/example/financialmarket/engine/agent/MultiAgentManager.java`（可选：暴露四角色计数/聚合视图供中观只读消费）

### 3.2 需求

**1. 宏观政策快照 `MacroSnapshot`（确定性，纯函数）**
- 字段：`policyRate, reserveRequirement, fiscalStance, cyclePhase(EconomicCycle.Phase), crisisLevel, creditGrowth, m2Growth, inflationRate, liquidityMultiplier, riskAppetite, discountRate, dynamicDrift, dynamicVolatility`。
- 来源全部为**已有确定性量**：`CentralBank.getDiscountRate/getReserveRequirement`、`Government.getFiscalStance`、`cycleEngine.getEconomicHealth()`+`EconomicCycle.phaseOf`、`crisisEngine.getCrisisLevel()`、`creditSystem.getCreditGrowth()`、`inflationSystem.getM2Growth()`（若缺则用 `getInflationRate`）、`macroEnvironment` 的乘子/漂移/波动。
- 构造为**每宏观步（gameTime%20==0）由 MarketEngine 组装一次**的纯函数（无随机、无持久化——读档后以当期输入确定性重建，对齐 `MacroEnvironment.applyMacroTransmission` 的「衍生量不持久化」约定，`MacroEnvironment.java:62-71` 注释）。
- 方法：`MacroSnapshot build()`（静态工厂，参数为上述输入）或 `MacroEnvironment.buildSnapshot(...)`（由 implementer 定，注释说明）。

**2. 中观层：板块基金 `SectorFundAgent`（每行业一个，8+1=9 个）**
- 种子：`SeedableRandom.mix64(engineSeed ^ ("meso/sector/"+industry).hashCode())` 派生，禁裸随机。
- 状态（需持久化）：`aum`（板块资金规模）、`Map<assetId, weight>`（板块组合权重）、`sectorMomentum`（板块价格 EMA）、`targetWeight`（目标权重）、`lastRebalanceTick`。
- 每 tick（或每 N tick 低频，由 implementer 定，注释说明）：
  - 计算板块基本面聚合：`sectorFair = 加权平均(member.getFairValue() × member.getNetAssetValue()) / ΣnetAsset`；`sectorPrice = 加权平均(lastPrice)`（同权或 NAV 加权，确定性）。
  - 消费 `MacroSnapshot`：`discountRate↑ → sectorFair↓`（估值传导）；`riskAppetite↓/crisisLevel↑ → 目标仓位↓`（机构去杠杆）；`cyclePhase` 驱动轮动风格（繁荣→动量，收缩→反转，见 SectorRotationModel）。
  - 生成**聚合大单 Intent**（机构级单笔 5000~50000 股，低频），经 `OrderPipeline`（MP-3）进入订单簿，`synthetic=true`。
- 组合再平衡：目标权重按「板块相对估值分位 + 动量信号」确定性计算，偏离阈值触发再平衡（防每 tick 抖动；阈值=代码常量，不调参）。

**3. 中观层：机构投资者 `InstitutionalAgent`（5~10 个，风格=value/growth/momentum）**
- 种子派生同上（`"meso/inst/"+i`）。
- value 风格：向 `fairValue` 偏离回归（低估买/高估卖，低频大单）；growth 风格：向板块动量追涨（带止损/反转护栏）；momentum 风格：板块轮动跟随（SectorRotationModel 输出）。
- 杠杆：`leverage = clamp(1.0, 3.0, base × snapshot.liquidityMultiplier × cycleLeverageMultiplier)`（对齐 `EconomicCycle.leverageMultiplier`，`EconomicCycle.java:50-57`）；去杠杆强制抛售（明斯基中观传导）。
- 状态（需持久化）：`cash/holdings/avgCost/style/leverage`。

**4. 板块轮动模型 `SectorRotationModel`（纯函数 + SeedableRandom 子流）**
- 输入：各板块 `sectorMomentum`、相对估值分位（当前 sectorPrice/sectorFair 的横截面 z-score）、`MacroSnapshot.cyclePhase`。
- 输出：板块目标权重偏移（超配/低配），确定性。
- 规则（代码常量，注释引用现实依据）：扩张/顶部→动量轮动（强者恒强，羊群式板块聚集）；收缩/萧条→反转轮动（超跌板块低配恢复）；轮动速度受 `riskAppetite` 调制（恐慌时轮动停滞/资金撤离）。

**5. 三层传导接线（政策→机构→做市商→盘口）**
- `MarketEngine.tick` 中（宏观块之后、L2 之前）：`mesoLayer.tick(this, snapshot)`；中观层产出 Intent 队列 → MP-3 `OrderPipeline` → 对应资产 `OrderBook`。
- 微观层消费中观：`MultiAgentManager.stepAgents` 新增可选只读入参/读取「板块净资金流信号」（若实现则从 `MesoLayerManager.getSectorFlow(industry)` 读取，加到 chartist 信号/羊群偏置；**默认 0，不改变既有 P2 行为**，由 `marS.mesoToMicroBias` 开关控制）。
- 传导可观测性：每宏观步输出日志（确定性打印，供验证断言）：`[MESO] industry=MINING sectorPrice=.. sectorFair=.. rotation=OVER/UNDER flow=+.. aum=..`。
- 中观层总开关：`marS.enabled`（`MarketEngine` 新布尔，默认 true）；`false` 时 `mesoLayer` 不 tick、不产出，行为与既有基线一致（因子隔离，对齐 P2 开关模式 `MarketEngine.java:234-249`）。

**6. 持久化**：`MesoLayerManager.save()/load(CompoundTag)`（板块 AUM/权重/动量/轮动状态、机构现金/持仓/杠杆/风格；旧档缺字段 `contains` 兜底重建，读档不失败）；`MarketEngine.save()` 加 `tag.put("meso", mesoLayer.save())`，`loadInternal` 对应恢复。

### 3.3 验收（agent-dev 自证 → t4 复核）

- MP-1-1：`init()` 后存在 9 个板块基金（8 行业 + 1 汇总/PLAYER 视实现）与 ≥5 个机构；每行业基金持仓覆盖该行业资产。
- MP-1-2：中观订单流存在且**单笔量级显著大于微观**（中观均值 > 3×微观均值，固定种子统计）。
- MP-1-3：传导可观测——构造确定性输入（如注入高 policyRate）后，板块基金目标仓位下降、产出卖向订单（或杠杆下降），输出日志可断言。
- MP-1-4：板块轮动存在——长跑 N tick 后至少两个板块的权重/超配状态发生过变化（非恒定）。
- MP-1-5：确定性——同种子两次运行，中观日志序列逐行一致；无新增裸随机。
- MP-1-6：持久化——save→load 后板块 AUM/权重/机构持仓恢复，后续 tick 行情连续；旧档（无 `meso` 字段）load 不失败。
- MP-1-7：隔离——`marS.enabled=false` 时成交价序列与基线（无中观层）逐位一致（或差异仅来自新增随机子流消费次序——实现须保证关闭时不消费任何新随机源，见 §7）。

---

## 4. 需求契约：MP-2 — 市场影响模型 Δ=α·σ·√(Q/V)（engine-dev，t3）

### 4.1 改动文件

- 修改 `src/main/java/com/example/financialmarket/engine/microstructure/MarketImpact.java`（新增 MarS 影响项，保留既有 `impact/levelDepth` 向后兼容）
- 新增 `src/main/java/com/example/financialmarket/engine/microstructure/VolumeTracker.java`（每资产滚动成交量 ADV，纯 Java + SeedableRandom 无随机）
- 修改 `src/main/java/com/example/financialmarket/engine/OrderBook.java`（walkBook 叠加 MarS 影响项）
- 修改 `src/main/java/com/example/financialmarket/engine/PriceDiscoveryEngine.java`（新增只读 `getAssetVolatility(id)`）
- 修改 `src/main/java/com/example/financialmarket/engine/MarketEngine.java`（tick 维护 VolumeTracker、注入 σ/V；save/load）
- 修改 `src/main/java/com/example/financialmarket/engine/calibration/CalibrationParams.java`（新增 `marSAlpha`、`advWindowDays` 先验）

### 4.2 需求

**1. MarS 影响公式（新增方法，保留既有语义）**

```java
// MarketImpact 新增（参数取自 CalibrationParams 先验；residualNoise=0 时纯确定性）
public double impactMarS(double qty, double sigma, double volume, double midPrice, boolean isBuy) {
    double ratio = volume > 0 ? qty / volume : 0.0;
    double shock = marSAlpha * sigma * Math.sqrt(ratio);   // Δ = α·σ·√(Q/V)
    double amount = shock * midPrice;
    return isBuy ? amount : -amount;
}
```

- `marSAlpha`：`CalibrationParams` 新增字段，默认 0.01（注释引用平方根冲击律文献区间；**先验不拟合**）。
- 语义：下单量 Q 越大冲击越大（√ 次线性）、波动率 σ 越高冲击越大、市场成交量 V 越大冲击越小（深度越厚越能吸收）——「下单量对价格影响与波动率/深度有关」。

**2. σ 与 V 的来源**

- σ：`PriceDiscoveryEngine.getAssetVolatility(String assetId)` = `Math.sqrt(gbm.sigmaSq)`（GARCH 条件波动，`PriceDiscoveryEngine.java:335-342`）；GbmState 缺失时回落 `macroEnvironment.getDynamicVolatility()`。
- V：新增 `VolumeTracker`（每资产滚动 ADV）：在 `MarketEngine.tick` 的交易日边界或每 tick 聚合 `dailyTrades`/`tradeHistory` 成交量，维护 `Map<String, Double> advByAsset`（如近 `advWindowDays=5` 交易日均量；窗口为代码常量）。纯 Java、无随机、可持久化。

**3. 叠加进 walkBook（撮合侧影响）**

- `OrderBook.walkBook`（`OrderBook.java:246`）改为：
  ```java
  double impactVal = impactModel.impact(cumQty, d0, midPrice, buyAggressor)
                    + impactModel.impactMarS(aggRemaining, sigma, volume, midPrice, buyAggressor);
  ```
- `sigma/volume` 由 `MarketEngine.tick` L3 在 `matchWithSlippage` 前注入（新增 setter `book.setMarketContext(sigma, volume)` 或按资产传入；`impactEnabled=false`（P2 关闭）时仍回退 P0 纯随机滑点，`OrderBook.java:305-308` 语义不变）。
- 硬约束：MarS 影响叠加后成交价仍受 `aShareClamp` 涨跌停带 + `clampForContinuity`（`OrderBook.java:248-251`）钳制，不越带。

**4. 挂单量→价格影响（订单生成侧）**

- MP-3 `OrderPipeline` 对**市价/激进限价 Intent** 调用 `impactMarS(size, σ, V, anchorPrice, isBuy)` 修正目标价（大单挂单把价格推向不利方向，MarS Order Generator 的市场影响条件对应物）；影响修正幅度钳到 `[0, 2×spread]` 防过度。
- 该修正仅在 `marS.enabled && impactEnabled` 时生效；关闭时价格为原 anchor（隔离）。

### 4.3 验收

- MP-2-1：`impactMarS` 纯函数正确性——同输入同输出；qty↑→|Δ|↑ 且 √ 次线性（qty×4 → Δ×2）；σ↑→|Δ|↑；V↑→|Δ|↓；isBuy 符号正确。
- MP-2-2：walkBook 大单 vs 小单冲击差异显著（沿用 P2 验证方法，`.verify/harness/P2RealHarness.java` 模式）：`largeAvg > smallAvg` 且差值 ≥ 阈值（MP-2-4）。
- MP-2-3：σ 注入生效——构造 σ 高 vs 低的两个簿，同单量冲击均值不同（高 σ 冲击更大）。
- MP-2-4：V 注入生效——同 σ 同单量，薄市场（V 小）冲击 > 厚市场（V 大）。
- MP-2-5：确定性——同种子两次 run 成交价序列一致（MarS 影响无随机：residualNoise=0）。
- MP-2-6：持久化——`VolumeTracker` save→load 恢复 ADV，后续 tick 连续；旧档兜底。
- MP-2-7：隔离——`impactEnabled=false` 时撮合回退 P0 纯随机滑点（与基线逐位一致，F2 语义保持）。

---

## 5. 需求契约：MP-3 — 订单生成标准化流水线（engine-dev，t3）

### 5.1 改动文件

- 新增 `src/main/java/com/example/financialmarket/engine/microstructure/OrderPipeline.java`（订单流水线：Intent→影响修正→校验/规范化→Order）
- 修改 `src/main/java/com/example/financialmarket/engine/MarketEngine.java`（L2/L2.5/L3 订单生成改走 pipeline；`marS.enabled` 控制）
- 修改 `src/main/java/com/example/financialmarket/engine/Order.java`（可选：新增确定性 `genTimestamp` 字段或复用 `placedTick`；不动既有构造语义）
- 修改 `src/main/java/com/example/financialmarket/engine/microstructure/OrderFlowModel.java`（可选：ZI/Hawkes 产出改走 pipeline 入口，或保持直接 Order 但经 pipeline 校验）

### 5.2 需求

**1. `OrderPipeline` 标准化流水线（MarS Order Generator 对应物）**

```java
public class OrderPipeline {
    // Intent：方向/数量/参考价/激进程度/来源角色/时间戳（tickCounter 派生，确定）
    public record OrderIntent(String assetId, boolean isBuy, int quantity, double anchorPrice,
                              double urgency, String source, long genTick) {}

    public Order toOrder(OrderIntent intent, boolean synthetic);  // 统一出口
}
```

- 步骤（全部确定性）：
  1. **影响修正**（`marS.enabled && impactEnabled`）：按 `impactMarS(intent.quantity, σ, V, anchorPrice, isBuy)` 把 `anchorPrice` 推向不利方向（市价/激进单）；钳制 `[0, 2×spread]`。
  2. **规范化**：价格 `round2` 到 0.01（A 股 tick，`PriceDiscoveryEngine.A_SHARE_TICK_SIZE`）；数量取整手 100（`A_SHARE_BOARD_LOT`，`lotRound` 语义对齐 `MultiAgentManager.java:645-648`）。
  3. **校验**：涨跌停带（`withinAShareLimit` 语义复用 `MarketEngine.java:1258` 路径，或收拢进 pipeline）、休市拒单、数量>0。
  4. **时间戳**：`genTick = tickCounter`（确定性）；`placedTick = tickCounter`（撮合排序权威，`OrderBook.java:114` 语义保持）。`Order` 构造的 wall-clock `timestamp` 仅为显示字段（不动既有类语义，`Order.java:54`）。
  5. **产出**：`Order`（`synthetic` 标记；`playerId=null` 或 `ZI_PLAYER_ID`/中观专用固定 UUID）。

**2. 全部合成订单改走 pipeline**

- L2 微观 agent：`agentManager.consumeOrders(assetId)` 的 OrderFlow 包装为 `OrderIntent` → `pipeline.toOrder`（替代 `MarketEngine.java:1252-1267` 直接 new Order）。
- L2.5 ZI/Hawkes：`OrderFlowModel` 的 ZI/Hawkes 订单改走 pipeline（`MarketEngine.java:1284-1313`）；`ZI_PLAYER_ID` 固定 UUID 语义保持。
- 中观层（MP-1）：`MesoLayerManager` 产出 Intent → 同 pipeline。
- 自营/量化（`submitOrder` 路径）**不改**（玩家/自营路径已含 A 股校验，`ECON_REMAINDER_SPEC` R0-6）。
- 校验去重：pipeline 成为合成订单唯一校验点；`MarketEngine.tick` L2 内联 `withinAShareLimit` 若保留须与 pipeline 一致（推荐：L2 内联删除，统一走 pipeline，避免双路径漂移；P0 基线模式（aShare=false）下涨跌停校验回落 `priceEngine.getLimitUp/Down` 语义，`MarketEngine.java:1260-1264`）。

**3. 开关与隔离**

- `marS.enabled=false` 或 `orderFlowEnabled=false` 时：pipeline 不做影响修正（修正量=0）、时间戳仍 tickCounter（**不改变撮合语义**），订单序列与基线一致（随机消费次序不得变化——pipeline 本身不消耗随机）。
- `impactEnabled=false`（P2 关）时：pipeline 影响修正跳过，撮合回退 P0 滑点（`OrderBook.java:305-308`）。

### 5.3 验收

- MP-3-1：所有合成订单（微观/ZI/中观）`placedTick == tickCounter`、价格 0.01 步长、数量 100 整手、不越涨跌停带。
- MP-3-2：同种子两次 run，订单序列（价格/数量/方向/placedTick）逐元素一致（含时间戳确定性）。
- MP-3-3：pipeline 影响修正生效——大单 Intent 的产出限价与无修正路径不同（向不利方向偏移）。
- MP-3-4：`marS.enabled=false`/`impactEnabled=false` 时与既有基线一致（P2 关→P0 逐位一致保持）。
- MP-3-5：持久化——中观/ADV 状态经 pipeline 相关 save/load 恢复；旧档不失败。

---

## 6. 需求契约：MP-4 — 验证框架（一个核心/三大支柱/四层级）（test-engineer，t4）

### 6.1 改动文件

- 修改 `src/main/java/com/example/financialmarket/engine/validation/StylizedFactsValidator.java`（新增指标 + L3/L4 + 支柱面板）
- 新增 `.verify/harness/MarsFactsHarness.java`（真实类链：`MarketEngine.init` + 无 ServerLevel 的确定性子链驱动，沿用 `.verify/harness/EconRemainderHarness.java` / `P2RealHarness.java` 模式）
- 新增 `.verify/MARS_PORT_VERIFY.md`（t4 验证报告模板）

### 6.2 需求（四层级）

**L1 定性（复用 + 强化现有断言）**：有涨有跌（>20% 资产上涨且 >20% 下跌）、盘口双侧有档、价差>0 且 ≤2% median、多档阶梯、撤单生命周期、挂单不无限堆积、中观订单存在（MP-1-2）。多为 MS-AI-1/2/3/5 既有项，harness 断言化。

**L2 程式化事实（核心面板，新增 4 项）**：

| 事实 | 指标 | 阈值（先验，不校准） | 现状 |
|---|---|---|---|
| 肥尾 | 峰度 κ / 超额 | κ > 4（超额>1） | ✅ 已有 |
| 波动率聚集 | LB(|r|) p 值 | p < 0.05 显著 | ✅ 已有 |
| 收益率无自相关 | **raw 收益 Ljung-Box** | **p > 0.05（不显著）** | ❌ 新增（`ljungBox(r, mean(r), LAG_ORDER)` + p 值） |
| 杠杆效应 | corr(r, r²₁) | < 0 | ✅ 已有 |
| 成交量-波动率 | corr(vol,|r|) | > 0 | ✅ 已有（P2） |
| 长记忆 | **Hurst 指数（|r| 序列，R/S 或方差法）** | **> 0.5** | ❌ 新增 |
| 粗粒化高斯性 | **聚合尺度 k=1,5,20 峰度** | **单调递减向 3** | ❌ 新增 |
| 微观出清 | spread median / depthAtBest / depthDecay | 对齐 CalibrationParams 先验区间 | ✅ 已有（P2） |

**三大支柱面板**：
- 金融学支柱 = L2 全项。
- 行为经济学支柱（引擎级度量，新增）：
  - 处置效应：`sellP(持有且盈利) > sellP(持有且亏损)`——从 agent 决策统计（固定种子跑 N tick，记录卖出决策时盈亏状态比例）。
  - 过度交易：换手率/成交频率 vs 波动率（高波动期成交笔数放大）；或均持仓期 vs 现实区间（相对值断言，不做绝对校准）。
  - 羊群：群体净方向集中度（同一行业 agent 同 tick 同向比例，`HerdingModel` 已提供机制）；a=0 vs a>0 梯度（沿用 `runP1Validation` 梯度对比，`:449-478`）。
  - 前景理论：损失厌恶——亏损状态 agent 的下单量/频率相对盈利状态的变化（`prospectPropensity` 效应可测）。
- 微观经济学支柱（新增 2 项）：
  - 价格发现效率：相对 fairValue 偏离的半衰期（偏离序列自相关衰减）或方差比 VR(5)≈1（弱式随机游走）。
  - EMH 弱式：raw 收益无自相关（L2 raw-LB）+ 简单动量策略（过去 5 bar 收益排序，做多赢家/做空输家）无显著超额（|strategy return| ≤ 阈值）。

**L3 分布拟合行为（新增）**：
- 收益分布：Student-t 拟合（自由度 ν 估计，Hill 尾部指数对照 `hillTailMu`，`:1335-1349`）；报告拟合优度（KS 距离或对数似然），**只报告不校准**。
- 订单量分布：中观/微观订单量直方图 vs 幂律/对数正态对照（描述性）。
- 价差/深度分布：与 `CalibrationParams` 先验（`spreadMedian/depthAtBestMedian/depthDecayExponent`）距离报告（`cal` 对照已存在，`:786`）。
- 规则：**所有分布拟合只输出距离/优度，绝不把拟合参数回写引擎**（避免循环论证；`CalibrationParams` 为先验常量，`CalibrationParams.java:12-14` 注释重申）。

**L4 样本外（新增）**：
- 把固定种子长跑拆为两窗口（如 tick 0~N/2 与 N/2~N）或两个周期相位（EXPANSION 段 vs CONTRACTION 段，用 `MacroSnapshot.cyclePhase` 分窗）。
- 断言：L2 事实在**两个窗口都成立**（不重调参数）；L3 拟合参数在窗口 1 估计、窗口 2 检验（距离不显著恶化）。
- 目的：证明现象是「引擎结构涌现」而非「过拟合某段」；**不过度调参**（全程参数=代码常量/CalibrationParams 先验）。

### 6.3 验收

- MP-4-1：L2 面板 8 项全部有输出与 PASS/FAIL 判定；raw-LB/Hurst/粗粒化高斯性三项在真实链上有数值（允许在既定阈值内报告）。
- MP-4-2：行为支柱 4 项引擎级度量有输出（处置效应不对称、过度交易、羊群梯度、前景损失厌恶可测）。
- MP-4-3：微观支柱 3 项有输出（出清价差、价格发现/方差比、EMH 弱式动量无超额）。
- MP-4-4：L3 分布拟合有报告（Student-t ν、订单量、价差/深度 vs 先验距离），无回写。
- MP-4-5：L4 两窗口对比有输出，L2 事实两窗口一致（或差异可解释并记录）。
- MP-4-6：确定性——harness 同种子两次输出逐行一致；随机源审计通过（MP-0）。
- MP-4-7：`MarsFactsHarness` 驱动**真实类链**（MarketEngine.init + 微观/中观/订单簿真实类），非纯镜像重实现（对齐 `.verify/harness/P2RealHarness.java` 的 REAL-CLASS 约定）。

---

## 7. 确定性 / 持久化 / 边界（MP-5，全部实施者遵守）

### 7.1 确定性硬约束

1. 新增随机一律 `SeedableRandom` 子流：`mix64(engineSeed ^ "<module>".hashCode())` 或 `derive("<id>")`（对齐 `MarketImpact.java:34`、`OrderFlowModel.java:75-76` 模式）；**禁用 `ThreadLocalRandom`/裸 `new Random()`/`Math.random()`**（既有 `AICompanyManager.java:20` 的 `new Random(seed)` 为已交付取数，**不替换**）。
2. 订单时间戳确定性：pipeline `genTick/placedTick = tickCounter`；验证/统计不读 `Order.timestamp`（wall clock 显示字段）。
3. 随机消费次序确定性：中观层 tick 位置固定（宏观块之后、L2 之前）；`MesoLayerManager` 遍历顺序用确定性序（行业名排序或注册序），禁 `HashMap` keySet 迭代（对齐 `MultiAgentManager.java:280-284` 的教训）。
4. 浮点确定性：全部 double 运算同序同式（不依赖平台库如 `Math.pow` 精度争议——沿用既有引擎做法，无新依赖）。

### 7.2 持久化

- 新增状态全部入 `MarketEngine.save()/loadInternal()`：`tag.put("meso", ...)`、`tag.put("volumeTracker", ...)`；旧档缺字段 `contains` 兜底（对齐 `MarketEngine.java:2089-2102` 模式）。
- H2 通道：`engine_kv.engine_main` blob 自动承载（`MarketEngine.saveToH2/loadFromH2`，`:2399-2442`），无 schema 变更。
- 新存档测试：验证在**新开存档**进行（用户硬约束）。

### 7.3 边界（不可修改清单）

1. **法币 M2 核心语义**：`CentralBank` 基础货币/M0、`InflationSystem` M2 分层记账、`Bank` 信贷派生公式**不改**（`ECON_FIAT_M2_SPEC` 全部验收照旧）。
2. **黄金核心语义**：`GoldPricingSystem` 公式与黄金记账**不改**（`NETHERITE_GOLD_SPEC` 全部验收照旧）。
3. **P0/P1/P2 已定稿公式**：`PriceDiscoveryEngine.step` GBM/GARCH、`AICompanyData.updateFairValue`（DCF/PE/PB）、`HerdingModel`/`BehaviorUtility` 语义、`OrderBook` 价格优先/时间优先/涨跌停带/T+1 语义**不改**；MarS 影响只**叠加**不改公式。
4. **A 股规则**（AS）不改。
5. **因子隔离**：`marS.enabled` 总开关（默认 true）+ `impactEnabled`（既有）+ `orderFlowEnabled`（既有）组合关闭时，行为回落既有基线（P2 关→P0 逐位一致保持）。

---

## 8. 任务划分与验收总表

| 任务 | 责任 | 规格章节 | 交付物 |
|---|---|---|---|
| t2（MP-1） | agent-dev | §3 | `engine/meso/*` + `MacroSnapshot` + `MarketEngine` 接线 + save/load + 自证 harness |
| t3（MP-2/MP-3） | engine-dev | §4/§5 | `MarketImpact.impactMarS` + `VolumeTracker` + `OrderPipeline` + `OrderBook` 叠加 + 自证 harness |
| t4（MP-4/MP-5/MP-0/MP-6） | test-engineer | §6/§7 | `MarsFactsHarness` + validator 扩展 + `.verify/MARS_PORT_VERIFY.md` + 构建/可复现/持久化/回归报告 |
| t5 | reviewer | 全文 | 按 MP-0..MP-6 验收 |

**全局验收映射**：MP-0（t4 构建+复现门）→ MP-1（t2+t4）→ MP-2/MP-3（t3+t4）→ MP-4（t4）→ MP-5（全部+t4）→ MP-6（t4 回归）。

---

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 中观层引入振荡/发散（板块资金流自我强化） | 再平衡阈值 + 权重钳制（目标权重 ∈ [0.05,0.40]）+ 轮动速度受 riskAppetite 调制 + 杠杆上限 3.0；MP-1-3/1-4 监测 |
| MarS 影响过大导致价格跳空 | 叠加量钳到 `[0,2×spread]`；成交价仍受涨跌停带 + `clampForContinuity` 双钳制（`OrderBook.java:248-251`） |
| 随机消费次序变化破坏既有种子复现 | 中观层用独立派生种子流；`marS.enabled=false` 时**不消费任何新随机源**（MP-1-7）；`impactMarS/VolumeTracker/pipeline` 本身零随机 |
| 验证框架过度调参/循环论证 | 参数全部为代码常量或 `CalibrationParams` 先验；validator 纯读不回写；L4 两窗口强制；`CalibrationParams.java:12-14` 注释重申 |
| 破坏法币/黄金回归 | §7.3 不可修改清单 + t4 回归用例照旧（MP-6）；新增代码只在 `engine/meso`、`engine/microstructure`、`engine/macro`（只读快照） |
| 中观层/影响模型拖慢 tick | 中观每 N tick 低频（再平衡不每 tick）+ O(行业数+机构数) 常数级；`impactMarS` 为闭式；`VolumeTracker` 增量维护 |

---

## 10. 结论

- MarS 可移植点在纯 Java + `SeedableRandom` 下的落点：**中观机构/板块层（全新）+ 宏观政策快照（折叠既有）+ 微观四角色规范化（既有）** = 三层智能体体系（MP-1）；**Δ=α·σ·√(Q/V) 影响项叠加深度冲击**（MP-2）；**OrderPipeline 统一合成订单出口 + 确定性时间戳**（MP-3）；**一个核心/三大支柱/四层级验证面板，只验证不校准**（MP-4）；**确定性/持久化/边界按 §7 硬约束**（MP-5）。
- 全部验收以**固定 `engineSeed` 开新存档**运行；`marS.enabled` 关闭即回落既有基线；法币 M2 与黄金核心语义不动。
