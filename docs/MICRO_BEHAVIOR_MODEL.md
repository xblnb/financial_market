# MICRO_BEHAVIOR_MODEL — 微观订单簿 + GAN 行为金融模型与可行性

> - 作者：micro-expert（微观结构 / 订单簿 / GAN 专家）
> - 输入：`MODEL_AUDIT.md`（G-01~G-14 差距）、`ECON_IMPROVEMENT_PLAN.md`（P0/P1/P2 路线）、`ECON_RESEARCH.md`（候选方案综述）、当前引擎源码（`OrderBook` / `MultiAgentManager` / `PriceDiscoveryEngine` / `MarketSentiment` / `MacroEnvironment` / `MarketEngine`）
> - 约束：Minecraft NeoForge 1.21.1 `ServerLevel` 主线程 tick 驱动、单线程、离线、无 GPU、无真实市场数据源、资源受限、需可复现（可播种）。
> - 交付物：本文件（`MICRO_BEHAVIOR_MODEL.md`），为 `micro-macro-gran-team` 微观层契约 t1 的设计结论。

---

## 0. 结论速览（TL;DR）

1. **核心结论：订单簿微观结构（L2/盘口/订单流）本身就能内生复现行为金融 stylized facts（肥尾/波动聚集/羊群/杠杆效应），且远比 GAN 轻量、契合本模组。** 本模组应**以订单簿内生定价为微观层主引擎**，行为金融作为**参数化扰动注入订单生成端**。
2. **GAN 在本模组离线 ServerLevel tick 环境下「不可行 / 不划算」作为运行时引擎**，只可能在**离线开发期（非运行依赖）**作一次性统计校准的辅助工具。理由：主线程单线程、无 GPU、数据量小、收敛不稳定、且 GAN 产出的价格路径**无法与订单簿/成交反馈形成闭环**（它只能事后生成序列，不能即时撮合）。详细评估见 §3。
3. **推荐替代/互补路线（按契合度排序）**：
   - **第一优先**：订单簿内生定价 + 轻量行为因子（Cont–Bouchaud 羊群 / 前景理论 / BSV / 情绪-价格正反馈），全部 O(agent)/O(资产) 常数级，贴合主线程约束。 → 承接 `ECON_IMPROVEMENT_PLAN.md` 的 P1/P2。
   - **第二优先**：统计矩校准注入（CalibrationParams，离线从真实 LOB 统计先验硬编码参数）——把「真实分布的形状」打包进模组，**非运行依赖**，比 GAN 更稳。
   - **第三优先（可选，离线开发工具）**：GAN 的**判别器复用为异常检测**（对订单簿快照判定「是否像真实市场」），或 **generator-only / VAE** 在开发期预生成「种子行情模板」供冷启动。不建议作为运行时组件。
4. **一句话落地建议**：微观层「去 GAN 化」，用订单簿内生 + 行为因子 + 统计校准注入三条轻量线满足行为金融刻画；GAN 仅作离线统计校准的可选工具，不作为游戏运行时引擎。

---

## 1. 微观层目标与范围

### 1.1 契约要求

在 `ECON_IMPROVEMENT_PLAN.md` P1/P2 基础上，微观层需刻画的行为金融现象：

| 行为金融现象 | 对应差距 | 目标机制 |
|---|---|---|
| 肥尾（厚尾） | G-11 | 收益分布 `κ > 4`，尾部幂律 `μ_tail∈(2.5,3.5)` |
| 波动聚集 | G-11 | GARCH 式：`|r_t|`/`r_t²` 显著自相关 |
| 羊群效应 | G-05 | Cont–Bouchaud 簇状同步 → 泡沫/崩盘 |
| 情绪-价格正反馈 | G-07 | self-reinforcing loop → 泡沫/崩盘 |
| 损失厌恶/处置效应 | G-06 | 赢家拿不住、亏家扛着 |
| 杠杆效应 | G-11 | 下跌放大波动 |
| 长记忆 | G-11 | Hurst 指数偏 0.5 |
| 订单簿真实度 | G-09/G-10 | 撤单/过期、价格冲击、深度曲线 |

### 1.2 契约要求「订单簿本身能否内生复现」

**能。** 微观结构文献（Econophysics，见 `ECON_RESEARCH.md §4` 与来源）已证明：仅凭**连续竞价订单簿 + 异质交易者 + 事件到达强度**，无需任何神经网络，即可涌现出几乎全部 stylized facts：

- **肥尾与波动聚集**：集群式订单到达（Hawkes 自激励）+ 买卖失衡，产生收益尖峰簇。
- **羊群 / 泡沫 / 崩盘**：Cont–Bouchaud 自旋传染（有连接拓扑的 agent 同步买卖）在临界连接度下涌现幂律尾。
- **杠杆效应**：恐慌（`MacroEnvironment.v`）与下跌反馈到波动（EGARCH 不对称项）即可。

**结论**：订单簿内生定价是满足本契约**最轻量、最契合**的路径，且与 `ECON_IMPROVEMENT_PLAN.md` 的 P2-1（异质交易者内生定价）天然衔接。GAN 属于「用重武器解决轻问题」，本模组不需要它作为价格引擎。

---

## 2. 订单簿微观结构内生行为金融模型（主方案）

> 本节给出微观层「订单簿内生 + 行为因子」的具体模型。**不引入 GAN 作价格引擎。**

### 2.1 分层定位（承接宏观层）

```
L0 宏观环境 (MacroEnvironment) ──利率 r / 政策 p / 恐慌 v / 流动性乘子 / 周期相位 E(t)
        │  E(t) 作为环境向量喂给微观层
        ▼
L1 微观订单簿内生定价 (本层核心)
   ├─ 订单生成 (MultiAgentManager 升级): 四类 agent + 行为因子
   │     ├─ 基本面锚 anchor = fairValue·(1+β_gbm·(gbm/fair-1)+β_macro·(riskAppetite-0.5))
   │     ├─ 羊群 (Cont-Bouchaud): a ∈ (0,0.3), 同行业/聚落拓扑
   │     ├─ 前景理论: V(x)=x^α, V(x)=-λ·(-x)^β, λ≈2 (avgCost 参考点)
   │     ├─ BSV: 事件→预期修正 ΔE, 动量/反转切换
   │     └─ 情绪正反馈: globalSentiment↔价格 (k_fb<0.1)
   ├─ 订单流微观结构 (OrderFlowModel 新增)
   │     ├─ ZI 零智能噪声基底 (经验分布随机限价/市价)
   │     └─ Hawkes 事件强度 λ(t)=λ0+ΣK·e^{-β(t-t_i)} (订单到达聚集)
   └─ 订单簿撮合 (OrderBook 升级)
         ├─ 撤单/过期 (purgeExpired, 生命周期)
         ├─ 价格冲击/深度曲线 (matchWithSlippage 改造, 吃单越深冲击越大)
         └─ 内生定价 (价格由净订单流涌现, GBM 降级为噪声源)
        │
        ▼
L2 成交 → lastPrice → K线/指数 → 反馈给情绪 (正反馈闭环)
```

### 2.2 关键组件设计

| 组件 | 现状 | 改造 | 计算量 |
|---|---|---|---|
| **订单生成** | `MultiAgentManager.stepAgents` 锚定静态 fairValue，散户抛硬币（G-08） | 升级为混合锚 + 行为因子（§2.3） | O(agent) |
| **羊群拓扑** | 无（G-05） | 新增 `HerdingModel`：agent 按**行业/聚落**分组，净方向同步 | O(agent) |
| **订单生命周期** | `OrderBook.placeOrder` 永不撤单（G-09） | 新增 `purgeExpired` + 定期撤销重挂 | O(深度) |
| **价格冲击** | 纯随机滑点（G-10） | 按深度消耗的冲击函数 | O(档位) |
| **订单到达聚集** | 每 20 tick 固定 | 新增 `OrderFlowModel`：ZI 基底 + Hawkes 强度 | O(事件) |
| **波动聚集** | sigma 宏观恒定（G-11） | `PriceDiscoveryEngine` 加 GARCH(1,1) 条件方差 | O(1)/资产 |
| **杠杆效应** | 无 | EGARCH 不对称项或恐慌-波动反馈 | O(1)/资产 |

### 2.3 行为因子注入公式（承接 P1，落地细节）

以下全部只改 `MultiAgentManager` 决策函数与 `MarketSentiment`，不改价格模型：

1. **混合锚**（P0-4）：
   ```
   anchor = fairValue·(1 + β_gbm·(gbmPrice/fairValue − 1) + β_macro·(riskAppetite − 0.5))
   ```
   agent 限价锚从纯 fairValue 升级，宏观/GBM 首次进入下单端。

2. **Cont–Bouchaud 羊群**（P1-1）：每个 agent 为自旋 `+1/−1/0`，每步以概率 `a` 跟随群体净方向、以 `(1−a)` 独立决策；`a` 接近临界时簇状同步 → 幂律尾 + 波动聚集 + 长记忆。传染网络用 Minecraft 行业群/聚落作拓扑。

3. **前景理论 / 损失厌恶 / 处置效应**（P1-2）：
   ```
   V(x) = x^α  (x≥0)
   V(x) = −λ·(−x)^β  (x<0), λ≈2
   参考点 = currentPrice − avgCost
   r>0 → 提高卖出倾向 (处置效应, 赢家拿不住)
   r<0 → 降低卖出倾向 (亏家扛着)
   ```

4. **BSV 过度反应 / 反应不足**（P1-3）：事件流 → agent 预期修正 `ΔE = k_resp·(新闻 − 已有预期)`；按新闻是否成串在「持续型→反应不足/动量」与「均值回归型→过度反应/反转」间切换。

5. **情绪-价格正反馈**（P1-4）：`globalSentiment`/`buyRatio` 回馈到 agent 下单概率与方向，价格变动再推动情绪 → 泡沫/崩盘。`k_fb < 0.1` 防发散。

> 全部 O(agent) 常数级，与 `ECON_IMPROVEMENT_PLAN.md §3` 的参数包一致，可并入 `MarketEngine.tick`。

### 2.4 与宏观层的耦合接口

- 宏观层只通过**环境向量 `E(t) = {r, p, v, liquidityMultiplier, cyclePhase, riskAppetite}`** 影响微观层（`MacroEnvironment` 已提供 `getInterestRate/getPolicyUncertainty/getPanicIndex/getLiquidityMultiplier/getRiskAppetite/getDynamicDrift/getDynamicVolatility`）。
- 微观层订单簿内生价格**向上反馈**给情绪（`sentiment.adjustAssetSentiment`），但不反向修改宏观状态（避免循环耦合）。
- 该接口与 `macro-expert` 的 `MACRO_MODEL.md` 契约对齐：宏观为纯输入，微观为纯执行层。

### 2.5 可复现性（可播种）

- 用世界种子派生子流 PRNG（承接 `ECON_IMPROVEMENT_PLAN.md §6`），替换 `ThreadLocalRandom` 与裸 `new Random()`。
- 所有行为因子随机源走共享 `SeedableRandom`（SplitMix64），同种子同行情，便于 stylized facts 验收。

---

## 3. GAN 可行性评估（契约核心）

> 契约要求：评估 GAN 在此 MC 模组**离线 ServerLevel tick 环境**的可行性：训练成本/收敛性/可复现性/Server tick 约束（主线程单线程、无 GPU、离线）。

### 3.1 GAN 用于金融时序生成的研究现状

GAN 已被广泛用于金融时间序列 / 订单流生成，代表作与结论：

| 方向 | 代表工作 | 结论 / 局限 |
|---|---|---|
| WGAN-GP 生成价格路径 | [WGAN Financial Time Series (ING × Maastricht)](https://github.com/goksuavdan/WGAN_FinancialTimeSeries) | 能复现收益分布形态，但需 GPU 训练、收敛不稳 |
| GAN 校准订单流模型 | [Dynamic Calibration of Order Flow Models with GAN](https://dl.acm.org/doi/pdf/10.1145/3533271.3561777) | GAN 用于**校准** ZI/Hawkes 参数，非直接生成，量级轻 |
| GAN 金融数据生成的综述 | [金融数据生成综述 (IJE)](https://www.ije.ir/article_218154_0c6fa9104eebcadec0ea5c43329f1a97.pdf) | 指出模式坍塌、训练不稳定、样本需求大 |
| 模式坍塌风险 | [Fiechner 论文 (Natixis)](https://natixis.groupebpce.com/wp-content/uploads/2022/08/lucas-benedikt_fiechner_thesis-_risk_management_with_gan.pdf) | GAN 生成器易退化为单一模式，尤其小数据 |

**关键事实**：GAN 训练需要**大量多样样本 + 稳定的反向传播 + 较长的迭代**（典型数百~数千 epoch），且生成的是**静态序列**而非可与订单簿交互的运行时引擎。

### 3.2 逐项可行性评估（对照本模组约束）

| 约束维度 | GAN 表现 | 评估 |
|---|---|---|
| **主线程单线程** | 训练 + 前向传播需在 Server tick 内完成；反向传播每步计算量大 | ❌ **不可行**：训练会卡死主线程 |
| **无 GPU** | CPU-only 训练小 MLP/CNN 尚可，但收敛慢、耗时分钟~小时级 | ⚠️ **勉强但浪费**：每次迭代成本高 |
| **离线 / 无真实数据源** | GAN 判别器需要「真实分布」作为监督；本模组**没有真实市场数据**（只有自己生成的模拟数据） | ❌ **根本缺陷**：判别器无法学到「现实」vs「生成」，只能学到「已有模拟」vs「新生成」→ 无增益 |
| **数据量小** | 本模组每个资产只有几百根 tick K线（见 `financialmarket_data/AI_*.json`），远低于 GAN 所需 | ❌ 模式坍塌高风险 |
| **可复现性** | GAN 训练有随机性，需固定种子 + 固定架构才可复现，但训练本身不稳定 | ⚠️ 可复现但收敛不稳定 |
| **与订单簿闭环** | GAN 生成的是**离线序列**，无法在运行时与成交/情绪/宏观形成反馈闭环 | ❌ **结构性不匹配**：它替代不了订单簿内生定价 |
| **计算/内存** | 需维护生成器 + 判别器两个网络及其梯度 | ⚠️ 相对订单簿常数级是巨大开销 |

### 3.3 结论

**GAN 在本模组作为运行时价格/订单流引擎 = 不可行**，且即便强制接入也**无增益**：
- 没有真实市场数据 → 判别器监督信号缺失（无法判断「像不像现实」）。
- 主线程单线程 + 无 GPU → 训练成本不可接受。
- 生成的是离线序列 → 无法与订单簿/成交/情绪形成闭环，违背「订单簿内生定价」的方向。

**GAN 唯一可能的位置**是**离线开发期（非运行依赖）**：在开发机上用真实 LOB 统计先验训练，产出「校准参数包」（§4 第三优先），运行时只读参数、不跑网络。

---

## 4. GAN 的替代 / 互补方案（契约要求）

> 契约要求给出 generator-only / VAE / 扩散模型 / 统计矩校准 / 判别器作为异常检测，并说明哪种最契合。

| 替代方案 | 原理 | 契合度 | 理由 |
|---|---|---|---|
| **统计矩校准注入（CalibrationParams）** | 把真实 LOB 的统计分布参数（订单到达率、价差、深度、收益率分布、肥尾指数）硬编码打包进模组，离线按真实先验生成 | ★★★★★ **最契合** | 非运行依赖、零训练、可播种、直接把「真实分布形状」注入订单簿/订单流，满足 stylized facts |
| **订单簿内生 + 行为因子**（§2） | 无网络，纯微观结构涌现 | ★★★★★ **最契合** | 最轻量、贴合主线程，直接承接 P1/P2 |
| **generator-only（非对抗）** | 只保留生成器，去掉判别器，用 VAE 或扩散去噪目标训练 | ★★★☆☆ | 比标准 GAN 稳定，但仍需离线训练 + 运行前向传播，仍是「静态序列」非闭环 |
| **VAE** | 变分自编码，重构 + 潜空间先验 | ★★★☆☆ | 训练稳定，但生成样本偏平滑，难复现肥尾；仍非运行时引擎 |
| **扩散模型** | 逐步去噪生成，质量高但**前向采样极慢**（数十~数百步） | ★★☆☆☆ | CPU-only 运行采样不可接受 |
| **判别器复用为异常检测** | 训练判别器判定「订单簿快照是否像真实市场」，运行时对异常行情告警 | ★★★☆☆（可选增强） | 有趣，但**非本契约核心**（契约要生成行为，不是检测），且仍需离线训练真实数据 |

### 4.1 推荐排序与落地建议

1. **第一优先**：订单簿内生 + 行为因子（§2）——这是本模组微观层的主引擎，满足全部行为金融刻画，零网络开销。
2. **第二优先**：统计矩校准注入——作为「让分布更像现实」的**离线参数包**，补充订单簿真实性（承接 `ECON_IMPROVEMENT_PLAN.md P2-4`）。
3. **第三优先（可选）**：开发期用 generator-only/VAE 预生成「冷启动种子行情」或训练判别器作异常检测——**不作为游戏运行时组件**。

> 一句话：**GAN 系方法全部让位于订单簿内生定价 + 统计校准注入**。前者是运行时引擎，后者把「现实的形状」以参数形式注入，都在主线程约束内，且无训练、可播种、可复现。

---

## 5. 验收指标（Stylized Facts）

对接 `ECON_IMPROVEMENT_PLAN.md §7`，新增 `StylizedFactsValidator`，对 `getKlines/getRecentCloses` 序列 `r_t=ln(P_t/P_{t-1})` 计算：

| # | Stylized Fact | 度量 | 当前(GBM) | 目标(订单簿内生后) |
|---|---|---|---|---|
| 1 | 肥尾 | 峰度 κ | ≈3 | `κ>4`，尾部幂律 `μ_tail∈(2.5,3.5)` |
| 2 | 波动聚集 | `|r|`/`r²` Ljung-Box 自相关 | 无 | 显著拒绝零自相关 |
| 3 | 收益自相关≈0、绝对收益长程相关 | ACF + Hurst H | 无 | `r` ACF≈0，`|r|`/`r²` 显著正、慢衰减 |
| 4 | 杠杆效应 | corr(r,σ_{t+1}) 或 EGARCH 不对称 | 无 | 显著负相关 |
| 5 | 情绪-价格正反馈/泡沫 | 动量自相关 + 崩盘段左偏 skew | 无 | 泡沫-崩盘形态 + 负偏 |
| 6 | 订单簿真实度 | 深度/价差分布、大单冲击、量-波关系 | 随机滑点 | 符合经验分布、大单可测冲击 |

**验收流程**：固定种子跑固定 tick 数 → 基线（P0 订单簿+行为因子）→ 对比是否向现实区间移动；用 `seed` 参数化一键重跑（§2.5 可播种）。

---

## 6. 风险与缓解

| 风险 | 级别 | 影响 | 缓解 |
|---|---|---|---|
| 行为因子参数不当导致价格发散（k_fb/a） | 中 | 行情跑飞 | `k_fb<0.1`、`a<0.3` 硬上限；StylizedFacts 面板监控 |
| 订单簿内生定价改变行情形态 | 中 | 与现有 P0 基线偏差大 | 先接最小集（撤单+GARCH），再逐级加行为因子 |
| 统计校准参数来自外部数据 | 低 | 不匹配游戏世界观 | 用行业/商品属性归一化，保持可播种 |
| 主线程计算量 | 低 | tick 卡顿 | 全部 O(agent)/O(资产) 常数级，逐项 benchmark |
| GAN 误引入 | 中 | 训练卡死 + 无闭环增益 | 明确排除运行时 GAN，仅作离线可选工具（§4） |

---

## 7. 结论

- **订单簿内生 + 行为因子 + 统计校准注入** 是本模组微观层刻画行为金融的**最契合、最轻量、可落地**方案，满足契约对肥尾/波动聚集/羊群/杠杆效应/情绪正反馈的全部要求，且全部在主线程单线程、离线、可播种约束内（O(agent)/O(资产)）。
- **GAN 作为运行时价格/订单流引擎不可行**：无真实数据使判别器监督失效、CPU-only 训练成本高、且生成序列无法与订单簿闭环。GAN 仅可作**离线开发期的统计校准/异常检测可选工具**，不作为游戏运行时组件。
- 微观层与宏观层的耦合接口为**单向环境向量 E(t)**（宏观→微观），微观成交价仅向上反馈情绪，避免循环耦合，与 `MACRO_MODEL.md` 契约对齐。

---

## 8. 输入 / 来源索引

- 差距：`MODEL_AUDIT.md`（G-01~G-14）
- 路线：`ECON_IMPROVEMENT_PLAN.md`（P0/P1/P2，§3 参数包，§6 可播种，§7 stylized facts）
- 方案综述与来源链接：`ECON_RESEARCH.md`（§1.2 Cont-Bouchaud、§3.3 Hawkes、§4.2 stylized facts、§6 分级）
- 代码证据：`OrderBook.java`、`MarketDepth.java`、`MultiAgentManager.java`、`PriceDiscoveryEngine.java`、`MarketSentiment.java`、`MacroEnvironment.java`、`MarketEngine.java`
- GAN 现状（外部）：[WGAN Financial Time Series](https://github.com/goksuavdan/WGAN_Financial_TimeSeries) · [GAN Order Flow Calibration](https://dl.acm.org/doi/pdf/10.1145/3533271.3561777) · [金融数据生成综述](https://www.ije.ir/article_218154_0c6fa9104eebcadec0ea5c43329f1a97.pdf) · [GAN 模式坍塌 (Natixis)](https://natixis.groupebpce.com/wp-content/uploads/2022/08/lucas-benedikt_fiechner_thesis-_risk_management_with_gan.pdf) · [Econophysics: 实证事实与 ABM](https://ar5iv.labs.arxiv.org/html/0909.1974)
