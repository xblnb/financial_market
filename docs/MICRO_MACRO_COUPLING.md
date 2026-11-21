# MICRO_MACRO_COUPLING — 微观-宏观分层耦合整体架构方案

> - 产出：architect（分层耦合架构整合），任务 t4
> - 输入（已完成依赖）：`MICRO_BEHAVIOR_MODEL.md`（t1，微观订单簿内生 + 行为因子 +「去 GAN 化」）、`MACRO_MODEL.md`（t2，宏观三层模型 + MacroSnapshot 单向注入）、`REALWORLD_BALANCE.md`（t3，现实六种耦合模式 P1~P6）
> - 范围：把三份交付整合为一份**可落地、去 GAN 化、单向耦合、时间尺度分离**的「微观-宏观分层耦合」整体架构方案。
> - 交付物：本文件 `MICRO_MACRO_COUPLING.md`。
> - 约束（继承自团队）：Minecraft NeoForge 1.21.1 `ServerLevel` 主线程 tick 驱动、单线程、离线、无 GPU、无真实市场数据源、可播种复现。

---

## 0. 结论速览（TL;DR）

1. **架构形态**：**分层 + 单向数据流 + 条件桥接**。宏观层（低频）输出一个**只读环境向量 `E(t)`**，作为条件参数/开关注入微观层；微观层（每 tick）在既定环境内由**订单簿内生定价**涌现高频价格。微观**只向上反馈情绪**（成交价 → 情绪），绝不反向改宏观状态，从而**杜绝循环耦合**。
2. **微观层「去 GAN 化」**：采纳 t1 结论——GAN **不作为运行时组件**（无真实数据使判别器监督失效、CPU-only 训练成本不可接受、生成序列无法与订单簿闭环），仅作**离线开发期**的统计校准 / 异常检测可选工具。微观主引擎 = **订单簿内生定价 + 行为因子 + 统计校准注入**，全部 O(agent)/O(资产)。
3. **宏观层三层模型**（t2）：`CycleRegime`（马尔可夫切换 + 明斯基脆弱性）+ 泰勒规则 `MonetaryPolicy` + 升级 `MacroEnvironment`（环境张量），全部 O(1)/tick。
4. **时间尺度分离**：宏观每 `20 tick` 低频更新并打包 `MacroSnapshot`；微观每 tick 只做 O(1) 查表/插值消费，不承担宏观演化。
5. **现实六模式映射**：t3 的 P1~P6 全部映射到模组具体类（`MacroStateEngine` / `PriceDiscoveryEngine` / `OrderBook` / `MultiAgentManager` / `TransactionCosts` / `CircuitBreaker` / `MarginManager`），逐一给出改动清单与风险。
6. **兼容性**：H2 持久化、MCEF UI、网络包**前向兼容**（不增删 packet 字段、blob 加字段即整体序列化、UI 增量字段走默认值）。

---

## 1. 层间边界与单向数据流（架构核心）

### 1.1 双层边界

```
┌────────────────────────────────────────────────────────────────┐
│                      MACRO LAYER（低频 O(1)/tick）                │
│                                                                │
│  [A] CycleRegime     马尔可夫状态机 + 明斯基脆弱性 θ → 相位 Φ     │
│  [B] MonetaryPolicy  泰勒规则 + 平滑 → policyRate r              │
│  [C] MacroEnvironment 升级：r/panic v/vol σ/liquidity M/riskApp  │
└────────────────────────────────────────────────────────────────┘
            │  打包只读环境向量 E(t) = MacroSnapshot
            ▼  （宏观→微观唯一入口，每 20 tick 刷新）
┌────────────────────────────────────────────────────────────────┐
│                   MICRO LAYER（每 tick O(agent)/O(资产)）         │
│                                                                │
│   MultiAgentManager  消费 riskAppetite/discountRate/phase       │
│      │              （羊群/前景/BSV/情绪正反馈 + 统计校准注入）     │
│      ▼                                                        │
│   OrderBook / OrderFlowModel   订单簿内生定价 + Hawkes 到达聚集   │
│      ▼                                                        │
│   成交 → lastPrice → K线/指数                                    │
└────────────────────────────────────────────────────────────────┘
            │  （仅反向反馈）情绪 sentiment（情绪→价格正反馈闭环）
            ▼  （只影响微观自身，不修改宏观状态）
```

### 1.2 单向数据流规则（杜绝循环耦合）

| 方向 | 内容 | 载体 | 约束 |
|---|---|---|---|
| **宏观 → 微观** | 环境向量 `E(t)={r, policyRate, panic, volatility, liquidityMultiplier, cyclePhase, riskAppetite, fragility, regime, discountRate, inflation}` | `MacroSnapshot`（t2） | 宏观**不直接改价格**，只改微观 agent 的估值锚/风险偏好/波动基线/流动性 |
| **微观 → 宏观** | **仅情绪反馈**（`sentiment.adjustAssetSentiment`） | 内存内 `MarketSentiment` | 情绪只进入微观决策与正反馈闭环，**不写回宏观状态变量** |

> **反循环机制**：微观成交价/情绪是宏观变量的**下游观测**而非上游输入。宏观状态机（`CycleRegime`/`MonetaryPolicy`）的状态转移只由自身的脆弱性 θ、产出缺口 y_gap、通胀 π 驱动（t2 §3.1-3.2），**不读取任何微观成交价**。因此不存在「宏观↔微观↔宏观」的反馈回路，避免级联发散与难以调试的循环依赖。这正是现实「央行不直接读订单流、但订单流受政策影响」的分层本质。

### 1.3 接口契约（对齐 t2 与 t1）

- 微观层通过**一个方法**获取环境：`MacroSnapshot snapshot(long tick)`（放入升级后的 `MacroEnvironment` 或独立 `MacroService`）。
- 微观层**只读**这些字段，**禁止写回**（t2 §4.1）。
- 微观层向宏观暴露的只有**可观测统计**（供 UI/日志），不构成状态回写（t1 §2.4）。

---

## 2. 「去 GAN 化」结论的整合（采纳 t1）

### 2.1 运行时排除 GAN

整合 t1 §3 的逐项评估，确认 GAN 在本模组**离线 ServerLevel tick 环境不可行**，理由：

| 约束维度 | 评估 | 整合后结论 |
|---|---|---|
| 主线程单线程 | 训练+前向传播卡死 Server tick | ❌ 不可行 |
| 无 GPU / CPU-only | 收敛慢、分钟~小时级 | ⚠️ 勉强但浪费 |
| 无真实数据源 | 判别器无法学「现实 vs 生成」→ 无增益 | ❌ 根本缺陷 |
| 数据量小 | 每资产仅几百根 tick K线，模式坍塌高风险 | ❌ |
| 与订单簿闭环 | 生成静态序列，无法即时撮合 | ❌ 结构性不匹配 |

**结论**：**GAN 不作为运行时组件**。它替代不了订单簿内生定价，也满足不了「订单簿/成交/情绪/宏观」反馈闭环。

### 2.2 GAN 的保留位置（离线开发期，非运行依赖）

采纳 t1 §4 的替代排序，GAN 系方法**全部让位**于更轻量的路线：

| 优先级 | 微观层方案 | 运行时 | 说明 |
|---|---|---|---|
| 1 | 订单簿内生 + 行为因子（t1 §2） | ✅ 主引擎 | 零网络，承接 P1/P2 |
| 2 | 统计矩校准注入（CalibrationParams） | ✅ 离线参数包 | 把真实 LOB 分布形状硬编码，非运行依赖，可播种 |
| 3（可选） | generator-only/VAE **离线**预生成种子行情；判别器复用为**离线异常检测** | ❌ 非运行时 | 仅开发期工具，不在主线程 |

> 一句话整合：**微观层去 GAN 化**，用「订单簿内生 + 行为因子 + 统计校准注入」三条轻量线满足行为金融刻画（肥尾/波动聚集/羊群/杠杆/情绪正反馈）；GAN 仅作为离线开发期的可选校准/异常检测工具，绝不进入游戏运行时。

---

## 3. 整体接线图：宏观三层 × 微观三层

### 3.1 宏观层（t2，三层）

| 层 | 组件 | 数学模型 | 产出 |
|---|---|---|---|
| A | `CycleRegime`（替换/接线 `EconomicCycleEngine`） | 正弦骨架 + 马尔可夫状态机 + 明斯基脆弱性 θ | `phase` Φ、`regime` 离散态、`fragility` θ |
| B | `MonetaryPolicy`（新增） | 泰勒规则 + 平滑 `i = ρ·i_{t-1} + (1-ρ)[r*+π+1.5(π-π*)+0.5·y_gap]` | `policyRate` r |
| C | `MacroEnvironment`（升级） | OU 过程，`r_bar/v_bar` 由周期相位驱动；打包 `MacroSnapshot` | `E(t)` 全字段 |

### 3.2 微观层（t1，三层）

| 层 | 组件 | 机制 | 计算量 |
|---|---|---|---|
| 订单生成 | `MultiAgentManager` 升级 | 混合锚 + 羊群(Cont-Bouchaud) + 前景理论 + BSV + 情绪正反馈 | O(agent) |
| 订单流结构 | `OrderFlowModel`（新增） | ZI 噪声基底 + Hawkes 事件强度聚集 | O(事件) |
| 订单簿撮合 | `OrderBook` / `PriceDiscoveryEngine` 升级 | 撤单/过期 + 深度冲击 + 内生定价 + GARCH(1,1) + EGARCH 杠杆 | O(深度)/O(资产) |

### 3.3 完整接线时序（`MarketEngine.tick`，整合 t2 §5.3 + t1 §2）

```
MarketEngine.tick(level):
  # ---- 宏观层（低频，每 20 tick 执行一次）----
  1. macroEnv.step(tick)                 # 现有，升级为环境驱动源
  2. cycleRegime.tick(tick)              # 新增：周期相位/脆弱性
  3. monetaryPolicy.tick(tick)           # 新增：泰勒规则
  4. macroEngine.step(state, data)       # 接线 MacroStateEngine（riskAppetite/creditImpulse/inflationPressure）
  5. snapshot = macroEnv.snapshot(tick)  # 打包 MacroSnapshot=E(t)

  # ---- 微观层（每 tick 消费 E(t)）----
  6. priceEngine.step(snapshot)          # GBM/GARCH 消费 σ/μ（来自 E(t) 基线）
  7. 对每个资产：
     agentManager.stepAgents(..., snapshot)   # 消费 riskAppetite/discountRate/phase/fragility
     herdingModel.step(snapshot)              # 羊群强度 a 受 phase/fragility 调制
     orderFlowModel.step(snapshot)            # 订单到达强度受 volatility/panic 调制
  8. orderBook.match() → 成交价              # 价格由净订单流内生涌现
  9. sentiment.adjustAssetSentiment(成交价)   # 微观→微观情绪正反馈（不写回宏观）
```

> **关键**：宏观每 20 tick 只做一次打包；微观每 tick 读 `snapshot` 做 O(1) 查表/插值，不承担任何宏观演化计算（见 §4）。

---

## 4. 时间尺度分离策略

### 4.1 双层时间尺度

| 尺度 | 宏观层 | 微观层 |
|---|---|---|
| 更新频率 | 每 `20 tick`（t2 §6.1，`MacroEnvironment.java:86`） | 每 tick（AI 挂单 `MarketEngine.java:70`，订单簿连续撮合） |
| 计算量 | O(1)/tick（状态机+泰勒+打包） | O(agent)/O(资产)/O(深度)（订单簿内生定价） |
| 语义 | 低频、平滑、周期性的「环境」 | 高频、跳跃、微观的价格/流动性 |

### 4.2 对齐点

- **步长一致**：宏观 20 tick 与微观 AI 挂单 20 tick 的步长天然对齐（t2 §6.1），`snapshot` 每 20 tick 刷新即可，无需更高频。
- **消费方式**：微观每 tick 通过 `snapshot(tick)` 读取**当前缓存的环境向量**，只在字段边界做线性插值（如需亚步长平滑），不做宏观演化计算。
- **周期压缩**：现实基钦 ~3 年、朱格拉 ~9 年直接映射到游戏年太长；用 `cycleLengthScale`（可配置）让一个完整「繁荣→萧条→复苏」在约 1~2 个游戏日（24000~48000 tick）走完，保证玩家可见（t2 §6.1）。
- **可播种**：宏观所有随机源（周期初相/OU 冲击/货币冲击/状态转移）统一走世界种子派生的 `SeedableRandom`（替换裸 `new Random()` / `Math.random()`），保证同种子同周期、可验收（t1 §2.5 + t2 §6.2）。

---

## 5. 现实六种耦合模式 → 模组具体类映射（整合 t3）

t3 的 P1~P6 与 t1/t2 一一对应并落到具体类。**每模式给出改动清单 + 风险**。

### 模式 P1 —— 离散宏观 Regime 状态机 + 参数表
- **映射类**：`CycleRegime`（替换 `EconomicCycleEngine`）；`MacroStateEngine` 扩展 regime 参数表。
- **改动清单**：
  1. 新增 `CycleRegime`：5 态（BOOM/OVERHEAT/RECESSION/DEPRESSION/RECOVERY）+ 每态参数表（风险偏好、目标波动率、流动性强度、羊群偏置、冲击概率）。
  2. `MarketEngine.tick` 接线 `cycleRegime.tick(tick)`（替代现有死代码 `cycleEngine.tick`）。
  3. 用 `SeedableRandom` 派生初相与转移判定。
- **风险**：周期过长玩家无感 → `cycleLengthScale` 压缩（t2 §10）；转移判定参数不当 → 状态卡滞/跳变，需离线调参（t2 §10）。
- **来源**：[GitHub Macro_Regime_Switching_Model](https://github.com/oj0nathan/Macro_Regime_Switching_Model) · [HSE Markov Chain Asset Allocation](https://www.hse.ru/en/edu/vkr/1160302602?vision=enabled)

### 模式 P2 —— 「宏观状态向量 → 微观条件参数」桥接层
- **映射类**：`MacroStateEngine`（提升 `riskAppetite`/`creditImpulse`/`inflationPressure` 为一等桥接参数）；`MacroSnapshot` 承载；`PriceDiscoveryEngine`/`MultiAgentManager` 只读消费。
- **改动清单**：
  1. `MacroStateEngine.step` 真正接线（当前 `step` 从不被调用，产出恒 0），由 `MacroSnapshot` 驱动。
  2. 新增桥接字段：`volatilityRegime`（离散 低/正常/高/危机）、`liquidityMultiplier`（价差/深度/冲击缩放）、`crisisShock`（冲击脉冲）。
  3. `MultiAgentManager.stepAgents` 将 `riskAppetite` 等作为决策输入特征（t1 §2.3 混合锚 `β_macro·(riskAppetite−0.5)`）。
- **风险**：字段未接线恒 0 → 需在 `MarketEngine.tick` 显式调用并单测；微观过度依赖某字段 → 用参数硬上限兜底。
- **来源**：[Macro uncertainty & liquidity](https://www.sciencedirect.com/science/article/abs/pii/S1544612325011225) · [EPU & bond liquidity](https://www.sciencedirect.com/science/article/abs/pii/S0378426624002541)

### 模式 P3 —— 宏观冲击注入订单流（Event Injection）
- **映射类**：`MacroStateEngine`/`CycleRegime`（政策突变/危机触发）→ 向 `MultiAgentManager`/`OrderBook` 注入 `crisisShock` 短时脉冲。
- **改动清单**：
  1. `CycleRegime` 在 OVERHEAT→RECESSION 或脆弱性 θ≥θ_max 时置 `crisisShock=1`（衰减到 0）。
  2. `OrderFlowModel` 消费 `crisisShock` 放大 Hawkes 强度 + 买卖失衡（复现「公告后订单流集中反应」、跳变/闪崩）。
  3. 校准冲击幅度与衰减时长（半衰期参数）。
- **风险**：冲击过强 → 价格发散；冲击过弱 → 无感。需校准 + `k_fb<0.1` 硬上限（t1 §6）。
- **来源**：[Order flow & macro news (arXiv 2508.06788)](https://arxiv.org/pdf/2508.06788) · [Heterogeneous trader responses (arXiv 2505.01962)](https://arxiv.org/abs/2505.01962)

### 模式 P4 —— 顺周期流动性供给（Procyclical Liquidity）
- **映射类**：`OrderBook` / `TransactionCosts`（价差/深度/冲击成本缩放）。
- **改动清单**：
  1. `liquidityMultiplier`（来自 E(t)）驱动 `OrderBook` 报价深度与 `TransactionCosts` 滑点/冲击成本——衰退期价差扩、深度薄。
  2. 复用现有 `MarketDepth`/`OrderBook` 作为流动性刻画载体（t3 §3.2）。
- **风险**：顺周期过强放大波动聚集 → 与 GARCH 叠加过度；参数硬上限 + StylizedFacts 监控。
- **来源**：[Procyclicality of Dealer Market Making](https://d.repec.org/n?u=RePEc:pra:mprapa:129114&r=mac) · [HFT in turbulent periods](https://publikationen.ub.uni-frankfurt.de/frontdoor/index/index/year/2017/docId/43866)

### 模式 P5 —— 风险预算开关
- **映射类**：`MarginManager` / `ShortPosition` / `CircuitBreaker`。
- **改动清单**：
  1. 宏观衰退/危机 regime → 收紧 `MarginManager` 仓位上限、放大 `TransactionCosts`、提升 `CircuitBreaker` 触发灵敏度（目标波动率/风险预算开关）。
  2. 不实现真实风险平价优化，只借鉴「宏观 regime → 目标风险预算/上限」开关逻辑（t3 §2.1-B）。
- **风险**：上限收紧导致交易停滞 → 留可配置缓冲；CircuitBreaker 误触发 → 校准阈值。
- **来源**：[MFS Top-Down/Bottom-Up](https://www0.mfs.com/en-it/investment-professional/insights/portfolio-insights/macro-micro-construct-robust-risk-budgeting.html) · [Macro-Financial CVaR framework](https://www.mdpi.com/2227-7099/14/7/268/pdf)

### 模式 P6（前瞻，GAN 相关）—— 条件生成微观数据
- **映射类**：**非运行时**。作为**离线后台 tick 模拟器**的改进手段，由宏观状态向量做 condition 生成 tick 级订单流，供开发期测试。
- **改动清单**：
  1. 定位为**离线后台生成器**（不进主线程），由 t1 §4 的 generator-only/VAE（可选）承担。
  2. 采纳 t1「去 GAN 化」：P6 只作离线开发期数据增广/校准，不进入运行时闭环。
- **风险**：计算量/训练评估已由 t1 §3 判定运行时不可行，故本模式**明确不落地为运行时组件**（中低可实现性）。
- **来源**：[AAAI 2020 Order Streams GAN](https://mlanthology.org/aaai/2020/li2020aaai-generating/) · [DiffVolume LOB](https://dl.acm.org/doi/full/10.1145/3768292.3770413) · [GAN Market Simulations](https://arxiv.org/abs/2110.13287)

---

## 6. 兼容性（H2 / MCEF UI / 网络包）

对齐 `ECON_IMPROVEMENT_PLAN` §5 与 `H2_MIGRATION.md`。

### 6.1 H2 持久化
- **宏观新增状态**（相位 Φ、脆弱性 θ、政策利率惯性、OU 均值、regime）写入 `engine_kv` 的 `engine_main` blob（整块 NBT 天然支持加字段，t2 §8）。
- `MacroStateEngine.save/load` 目前返回空 NBT（`MacroStateEngine.java:28-29`）——接线后需真正序列化 `riskAppetite/creditImpulse/inflationPressure/volatilityRegime/liquidityMultiplier/crisisShock`。
- 旧存档缺字段 → 默认值重建（复用 `MarketAttachment` 空资产兜底，`MarketAttachment.java:56-58`）。**前向兼容**，无需改表结构。
- 微观行为因子参数 / `HerdingModel` 状态（若需跨存档保持）可写入 blob（t2 §8 + `ECON_IMPROVEMENT_PLAN` §5.1 P1）。
- 结论：**H2 兼容，仅加 blob 字段，不新增关系表列**（UI 需展示再增量加列，`H2_MIGRATION` §5/§8）。

### 6.2 MCEF UI / Web
- 宏观/微观改动**不改变 K线/技术指标字段名与类型**（`getKlines`/`getTechnicalKlines`/`CompanyDataResponsePacket` 的 EPS/ROE/fairValue 只是数值动态化，结构不变）→ **UI 零改动**（`ECON_IMPROVEMENT_PLAN` §5.2 P0）。
- 若要在 UI 展示情绪/宏观相位，是**增量字段**（加到 `CompanyDataResponsePacket` 或 Web JSON），不破坏现有字段，可选新增（`ECON_IMPROVEMENT_PLAN` §5.2 P1）。
- 订单簿深度增强时，`OrderBookSnapshotPacket`（`MarketEngine.java:271-287`）的 bids/asks 结构保持不变，只是深度分布更真实；L2 多档展示再扩展 packet（§5.2 P2）。

### 6.3 网络包
- 本方案 P1~P5 **不新增/不删除任何 packet 字段**（纯服务端模型内部改动）。
- 若 P1 在 UI 暴露情绪/宏观，**新增字段用默认值**（向后兼容，旧客户端读到缺失字段走默认）；P2 扩展多档深度时才需版本化 packet（`ECON_IMPROVEMENT_PLAN` §5.3）。
- 结论：**完全网络兼容**，无客户端强制升级。

---

## 7. 可验证的 Stylized Facts 指标

整合 t1 §5（微观）+ t2 §9（宏观），统一在 `StylizedFactsValidator`（新增 `engine/validation/StylizedFactsValidator.java`，t1 §5）对 `getKlines`/`getRecentCloses` 的 `r_t=ln(P_t/P_{t-1})` 计算。**可播种一键重跑**：固定种子跑固定 tick 数 → 基线（P0）→ 对比是否向现实区间移动。

| # | 指标 | 度量 | 当前(GBM) | 目标（订单簿内生+宏观耦合后） |
|---|---|---|---|---|
| 1 | 肥尾 | 峰度 κ | ≈3 | `κ>4`，尾部幂律 `μ_tail∈(2.5,3.5)` |
| 2 | 波动聚集 | `|r|`/`r²` Ljung-Box 自相关 | 无 | 显著拒绝零自相关 |
| 3 | 收益自相关≈0、绝对收益长程相关 | ACF + Hurst H | 无 | `r` ACF≈0，`|r|`/`r²` 显著正、慢衰减 |
| 4 | 杠杆效应 | corr(r,σ_{t+1}) 或 EGARCH 不对称 | 无 | 显著负相关 |
| 5 | 情绪-价格正反馈/泡沫 | 动量自相关 + 崩盘段左偏 skew | 无 | 泡沫-崩盘形态 + 负偏 |
| 6 | 订单簿真实度 | 深度/价差分布、大单冲击、量-波关系 | 随机滑点 | 符合经验分布、大单可测冲击 |
| 7 | 估值-利率负相关 | 泰勒规则 → discountRate → fairValue | 无 | 加息时成交价可观察下行（t2 §9 #2） |
| 8 | 周期相位可观察 | `phase` 驱动行业因子/情绪 | 无 | 玩家数小时游戏内看到繁荣→萧条切换（t2 §9 #3） |
| 9 | 泡沫-崩盘 | 明斯基脆弱性 θ 触发萧条 | 无 | 长期繁荣后突发崩盘（t2 §9 #4） |
| 10 | 可复现 | 播种宏观+微观随机源 | 有随机 | 同种子两次运行行情一致（t1 §2.5 + t2 §9 #5） |

**验收流程**（对齐 t1 §5 + t2 §9）：固定种子 → 跑 P0 基线 → 逐步加（撤单+GARCH → 羊群 → 前景/BSV → 情绪正反馈 → 宏观耦合）→ 每步复测 10 项指标 → 对比目标区间。同种子一键重跑保证可复现。

---

## 8. 风险与缓解（整合 t1 §6 + t2 §10 + t3）

| 风险 | 级别 | 影响 | 缓解 |
|---|---|---|---|
| 行为因子参数不当致价格发散（k_fb/a） | 中 | 行情跑飞 | `k_fb<0.1`、`a<0.3` 硬上限；StylizedFacts 面板监控 |
| 宏观周期相位驱动羊群过强 | 中 | 价格发散 | 宏观只微调参数基线，硬上限兜底（t2 §10） |
| 订单簿内生定价改变行情形态 | 中 | 与 P0 基线偏差大 | 先接最小集（撤单+GARCH），逐级加行为因子 |
| 泰勒规则参数不当 → 利率振荡 | 中 | 估值剧烈波动 | 平滑 ρ≈0.8；r 硬上限 [0.001,0.10]（t2 §10） |
| 周期过长玩家无感 | 中 | 玩家看不到宏观 | `cycleLengthScale` 压缩（t2 §6.1/§10） |
| 接线引入 NPE/回归 | 中 | 启动/读档失败 | 复用空资产兜底；每接一子系统单测 |
| 循环耦合风险 | 高 | 级联发散/难调试 | 明确微观只反馈情绪、宏观不读微观成交价（§1.2 反循环机制） |
| GAN 误引入 | 中 | 训练卡死+无闭环增益 | 明确排除运行时 GAN，仅离线可选工具（§2） |
| 统计校准参数来自外部数据 | 低 | 不匹配游戏世界观 | 用行业/商品属性归一化，保持可播种 |

---

## 9. 结论

本方案把 t1（微观去 GAN 化 + 订单簿内生定价）、t2（宏观三层模型 + MacroSnapshot 单向注入）、t3（现实六模式 P1~P6）整合为一套**可落地、去 GAN 化、单向耦合、时间尺度分离**的分层架构：

- **边界**：宏观层低频输出只读环境向量 `E(t)`，微观层每 tick O(1) 消费并订单簿内生定价；微观仅向上反馈情绪，**杜绝循环耦合**。
- **去 GAN 化**：GAN 不作运行时组件，仅离线开发期作统计校准/异常检测；微观主引擎为订单簿内生 + 行为因子 + 统计校准注入。
- **接线**：宏观三层（CycleRegime + MonetaryPolicy + MacroEnvironment）→ MacroSnapshot → 微观三层（MultiAgentManager + OrderFlowModel + OrderBook/PriceDiscoveryEngine），时序在 `MarketEngine.tick` 内对齐。
- **时间尺度**：宏观 20 tick 低频、微观每 tick O(1) 查表；`cycleLengthScale` 压缩周期使玩家可见。
- **现实映射**：P1~P6 全部落到具体类并给出改动清单与风险。
- **兼容**：H2 blob 加字段、MCEF UI 零改动、网络包不增删字段，全部前向兼容。
- **验收**：10 项 stylized facts 指标 + 可播种一键重跑。

---

## 10. 输入 / 来源索引

- 微观：`MICRO_BEHAVIOR_MODEL.md`（t1）
- 宏观：`MACRO_MODEL.md`（t2）
- 现实耦合：`REALWORLD_BALANCE.md`（t3）
- 兼容性基准：`ECON_IMPROVEMENT_PLAN.md`（§5 兼容、§6 可播种、§7 stylized facts）、`H2_MIGRATION.md`（H2 连接/迁移）
- 差距基准：`MODEL_AUDIT.md` / `AUDIT.md`（G-01~G-14）
