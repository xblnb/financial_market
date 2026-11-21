# REALWORLD_BALANCE — 现实交易系统「微观-宏观」平衡模式综述与耦合设计模式

> 任务: t3 (realworld-analyst)
> 目标: 调研现实交易/量化系统如何平衡微观市场结构(订单簿/订单流/高频)与宏观总量模型(周期/利率/通胀),总结可借鉴到 Financial Market(MC)模组的「微观-宏观耦合」设计模式。
> 落地约束(与 ECON_RESEARCH.md 一致): **Minecraft 服务器主线程 tick 驱动、无真实数据源、完全离线、Java/JVM、需在单次 20-tick K 线步进内完成有限计算量。**

---

## 0. 结论速览(给架构师的一句话)

现实中不存在"微观吞噬宏观"或"宏观覆盖微观"的单一范式,而是**分层 + 条件桥接**:宏观层输出一个低频的"环境状态向量"(周期阶段 / 利率 / 风险偏好 / 波动率环境),作为**条件参数与开关**注入到微观层(订单流模型 / 做市 / 因子权重)中;微观层则在既定宏观环境内产生高频价格/流动性行为。两个方向各有代表,但成熟系统几乎都是**自上而下定环境 + 自下而上定价格**的组合。映射到 MC 模组:**`MacroStateEngine` / `EconomicCycleEngine`(宏观层)→ 输出条件参数 → 注入 `PriceDiscoveryEngine` + `OrderBook` + `MultiAgentManager`(微观层)**。

---

## 1. 自上而下(宏观驱动) vs 自下而上(微观订单流)的两层耦合

### 1.1 现实中的两层角色划分
- **宏观层(Top-Down)**:决定资产配置、因子择时、总杠杆、风险预算。低频(日/月/季),由经济周期、货币政策、通胀、利率、信用周期驱动。典型代表:宏观对冲基金、多资产配置(PIMCO、风险平价)、Regime-Switching 资产配置。
- **微观层(Bottom-Up)**:决定具体价格形成、订单簿撮合、执行时序、流动性提供。高频(秒/毫秒/tick),由订单流不平衡、限价单簿状态、做市库存、知情交易驱动。典型代表:高频做市商、订单流策略、执行算法。

**关键现实认知**:两层不在同一个时间尺度上竞争,而是**嵌套**——宏观层定义"在什么环境里交易/什么风险预算可用",微观层定义"在这个环境里如何报单/成交"。现实中几乎不存在一个模型同时内生处理两者;它们通过**状态向量 + 参数化开关**衔接。

### 1.2 两层耦合的代表性案例
- **PIMCO 的资产配置流程**把宏观观点(经济/周期/政策)→ 翻译成各资产类别与久期/凸性的风险敞口,再在微观执行层用交易/衍生品实现。宏观决定"买什么暴露",微观决定"怎么低滑点成交"。这是典型的"宏观定策略、微观定执行"。来源: [PIMCO 资产配置流程](https://www.pimco.com/lat/en/resources/video-library/media/inside-pimcos-asset-allocation-process)
- **MFS 的 Top-Down/Bottom-Up 风险预算**:明确主张用一个"宏观-微观构造"来做稳健的风险预算——宏观因子(利率、通胀、增长)设定总体风险约束,微观(个股/资产具体机会)在约束内优化。来源: [MFS — Using Top-Down and Bottom-Up Insights to Manage Risk](https://www0.mfs.com/en-it/investment-professional/insights/portfolio-insights/macro-micro-construct-robust-risk-budgeting.html)
- **Markov Regime-Switching 多资产配置**:用马尔可夫状态模型把宏观指标聚类成若干"regime",在每个 regime 内分配资产权重。证明小规模宏观指标集合即可系统性改善多资产配置。来源: [Macro_Regime_Switching_Model (GitHub)](https://github.com/oj0nathan/Macro_Regime_Switching_Model)、[HSE — Global Macroeconomic Regimes & Markov Chain Asset Allocation](https://www.hse.ru/en/edu/vkr/1160302602?vision=enabled)
- **FX 微观结构**强调"汇率由订单流决定"而非纯宏观基本面,但订单流本身又受宏观新闻(央行声明、数据发布)驱动——宏观作为订单流的**外生冲击源**进入微观价格形成。来源: [Returns and Order Flow Imbalances: Intraday Dynamics and Macroeconomic News Effects (arXiv 2508.06788)](https://arxiv.org/pdf/2508.06788#4#1)、[Exchange Rates and Order Flow in the Long Run](https://www.semanticscholar.org/paper/Exchange-Rates-and-Order-Flow-in-the-Long-Run-Boyer-Norden/642d05b75c5ce6a0f38ae1b78bfd8eb9dbfba845)

**→ 对 MC 的借鉴点**
- 在 `MacroEconomy`/`EconomicCycleEngine`(宏观)与 `PriceDiscoveryEngine`/`OrderBook`(微观)之间引入一个**显式桥接层**,宏观不再直接覆盖价格,而是输出"环境状态",由微观层消费。
- 用**离散 regime 状态机**(扩张/过热/衰退/萧条,或风险偏好高/中/低)替代当前 `MacroEconomy.tick()` 里的连续随机游走 `economicHealth`,让微观层能对"处于哪个阶段"做条件化,而非对一个连续浮点数做连续微调。

---

## 2. 宏观状态作为「参数 / 开关」注入微观策略(多因子 / 风险平价 / 宏观环境切换)

### 2.1 主流注入模式
现实系统把宏观状态注入微观的方式有三类,均可映射到 MC:

| 注入模式 | 现实实现 | 对 MC 的实现形态 |
|---|---|---|
| **A. 条件权重 / 择时** | 因子/资产权重随 macro regime 切换(regime 高增长→加风险资产,衰退→减杠杆)。Regime-Switching 资产配置、Regime-Driven 系统策略。 | `MacroStateEngine` 输出 regime → 乘以 `PriceDiscoveryEngine` 的漂移/波动率,或乘以智能体风险偏好权重。 |
| **B. 风险预算约束(开关/上限)** | 风险平价用波动率/相关性设定各资产风险贡献,宏观 regime 变化 → 目标波动率 / 杠杆 / VaR 上限收紧。宏观决定"允许多少风险"。 | 宏观衰退 → 收紧 `MarginManager`/仓位上限,放大 `TransactionCosts`/滑点,提升 `CircuitBreaker` 触发敏感性。 |
| **C. 条件参数化(连续注入)** | 宏观状态向量(利率、通胀、信用脉冲)直接作为微观模型的**条件输入**,改变微观参数的取值(如波动率、流动性、风险厌恶)。Cross-asset volatility regime 方法。 | `MacroStateEngine` 已有的 `riskAppetite`/`creditImpulse`/`inflationPressure` 直接作为 `MultiAgentManager` 各 agent 决策的输入特征。 |

### 2.2 代表来源
- **Regime-Driven Systematic Strategies**:明确用利率/波动率/通胀周期/风险情绪 / QE-QT 周期作为系统策略的开关。来源: [Regime-Driven Systematic Strategies — James Preston](https://www.thriftbooks.com/w/regime-driven-systematic-strategies-interest-rate-volatility-regimes-inflation-cycles-risk-sentiment-ecb-vs-fed-divergence-convergence-qt-qe-periods_james-preston/57921856/)、[Libristo 简介](https://www.libristo.de/en/book/regime-driven-systematic-strategies_50508313)
- **跨资产波动率 regime**:把波动率环境(低/正常/高)作为全局状态,资产相关性、杠杆、策略类别随之变化。来源: [Convex — Cross-Asset Volatility Regime](https://convextrade.com/glossary/cross-asset-volatility-regime)、[Volatility Regime Shift](https://convextrade.com/glossary/volatility-regime-shift)
- **方差风险溢价(VRP)与 regime**:在随机波动率模型中引入宏观/市场状态,改进波动率与风险溢价预测。来源: [The Variance Risk Premium in Crude Oil Futures Markets (OVX, Stochastic Volatility)](https://www.unil.ch/events/1649240142374)
- **宏观不确定性与流动性**:管理情绪/宏观不确定性上升会压低股票流动性——宏观状态直接作用于微观流动性。来源: [Managerial sentiment, macroeconomic uncertainty, and stock liquidity (ScienceDirect)](https://www.sciencedirect.com/science/article/abs/pii/S1544612325011225)、[Economic policy uncertainty and corporate bond liquidity (ScienceDirect)](https://www.sciencedirect.com/science/article/abs/pii/S0378426624002541)

**→ 对 MC 的借鉴点**
- **把 `MacroStateEngine` 已产出的 `riskAppetite`/`creditImpulse`/`inflationPressure` 提升为一等公民**:它们是天然的"宏观→微观桥接参数",应作为 `MarketState` 上被微观层读取的条件特征,而不是仅在引擎内部被 self 更新。
- **引入波动率/流动性 regime 状态**:当前 `MacroStateEngine` 只有风险偏好类连续值;建议补一个离散 `volatilityRegime`(低/正常/高/危机),它同时驱动微观波动率尺度与宏观风险预算,实现"宏观-微观共享的周期感"。
- **风险平价思想落地**:不要求实现真实风险平价优化,可借鉴其"宏观 regime → 目标风险预算/仓位上限"的开关逻辑,接入现有 `MarginManager` 与 `ShortPosition`。

---

## 3. 订单流与宏观状态的联动;做市商 / 流动性提供者在周期不同阶段的微观行为

### 3.1 订单流 ↔ 宏观新闻
现实核心机制:宏观新闻(央行、FOMC、就业/通胀数据)是**外生冲击**,冲击到达后微观订单流在几分钟/秒内重新定价。低延迟交易者围绕宏观公告展开租金争夺;订单流不平衡(OFI)在公告后显著反应。来源:
- [Interaction between Returns and Order Flow Imbalances: Endogeneity, Intraday Variations, and Macroeconomic News](https://arxiv.org/pdf/2508.06788v1)
- [Heterogeneous Trader Responses to Macroeconomic Surprises: Simulating Order Flow Dynamics (arXiv 2505.01962)](https://arxiv.org/abs/2505.01962?context=q-fin.PM)
- [Rent seeking by low-latency traders: evidence from trading on macroeconomic announcements](https://dro.deakin.edu.au/articles/journal_contribution/Rent_seeking_by_low-latency_traders_evidence_from_trading_on_macroeconomic_announcements/21025945)
- [Trading activity of VIX futures and options around FOMC announcements](https://www.sciencedirect.com/science/article/abs/pii/S1057521924002539)

### 3.2 做市商/流动性 vs 周期
核心现实:做市商流动性供给是**顺周期(procyclical)**的——景气时积极提供流动性、库存风险小;压力/衰退时削减报价、扩大价差、抽走流动性,进一步放大波动。市场压力下 HFT 的角色是"双刃剑":平时提供流动性,动荡期可能加剧极端波动。来源:
- [Procyclicality of Dealer Market Making (MPRA)](https://d.repec.org/n?u=RePEc:pra:mprapa:129114&r=mac)
- [What drives liquidity? Identifying shocks to market makers' supply of liquidity and their role in economic fluctuations](https://www.semanticscholar.org/paper/What-drives-liquidity-Identifying-shocks-to-market-Goldberg/5c6e32729ae1489601b05fab6f333c5a09734994)
- [The ambivalent role of high-frequency trading in turbulent market periods](https://publikationen.ub.uni-frankfurt.de/frontdoor/index/index/year/2017/docId/43866)
- [High Frequency Trading and Extreme Price Movements](https://www.aeaweb.org/conference/2016/retrieve.php?pdfid=20602&tk=9sTR8Tf2)
- [Bond market liquidity risks (Macrosynergy)](https://macrosynergy.com/research/bond-market-liquidity-risks/)
- [Should Exchanges impose Market Maker obligations](https://www.unsw.edu.au/business/sites/default/files/seminars-conferences/Venkataraman.SydneyPaper.pdf)

**→ 对 MC 的借鉴点**
- **让做市/流动性随宏观周期顺周期**:在 `OrderBook`/`TransactionCosts` 中加入"流动性供给强度"参数,由宏观 regime 驱动——衰退期价差扩大、深度变浅、冲击成本上升,自然放大波动(吻合现实顺周期 + 波动聚集)。
- **宏观新闻→订单流冲击**:`MacroStateEngine` 在政策突变(利率调整/危机)时,向 `MultiAgentManager`/`OrderBook` 注入一个**短时冲击脉冲**,让订单流在"公告后"集中反应,而不是静态 GBM 平滑过渡——这直接弥补 ECON_RESEARCH 指出的"无跳变/无闪崩"缺陷。
- **已有 `MarketDepth`/`OrderBook` 可作为流动性刻画载体**:把宏观顺周期流动性注入其中即可。

---

## 4. 学术与业界的「两层 / 分层建模框架」(市场微观结构 + 宏观状态混合)

### 4.1 代表性框架
- **异质交易者 + LOB(Chiarella-Iori-Perelló 等)**:基本面/图表/噪声交易者异质规则在限价单簿上涌现波动聚集、肥尾、泡沫崩盘;宏观/环境通过改变各类交易者的比例或风险偏好进入。来源: [The impact of heterogeneous trading rules on the limit order book and order flows](https://www.scilit.net/publications/3a1da4e4a26518b294859e9157652cc1)(已在 ECON_RESEARCH 引用)
- **两代理 FX 堆栈(Research + Execution Gatekeeper)**:研究代理(宏观观点)→ 产出信号;执行把关代理(微观)决定是否/如何执行。是"宏观定想法、微观把关执行"的工程化落地。来源: [Build a Two-Agent FX Stack: Research Agent + Execution Gatekeeper](https://fxmacrodata.com/hi/articles/build-a-two-agent-fx-stack-research-and-execution-gatekeeper)
- **层级深度强化学习(如 VWAP 优化)**:上层做策略选择、下层做执行参数优化——分层控制结构。来源: [Hierarchical Deep Reinforcement Learning for VWAP Strategy Optimization](https://ieeexplore.ieee.org/document/10336391)
- **数据驱动 Macro-Financial CVaR 框架**:从 regime 检测到决策规则的端到端两层(宏观金融状态 → 组合风险约束 → 资产配置)。来源: [From Regime Detection to Decision Rules: Data-Driven Macro-Financial CVaR Framework](https://www.mdpi.com/2227-7099/14/7/268/pdf)
- **双层/层次化通用模式**:业界普遍用"宏观层定风险敞口 + 微观层定执行/价格"的嵌套结构(见 §1.2 的 PIMCO/MFS 案例)。

### 4.2 面向 MC 的结构性结论
现实两层框架的统一形态是一个**「状态向量 → 条件化微观模型 → 价格」**流水线:
```
宏观状态(regime/利率/通胀/风险偏好/波动率环境)
   │  (低频, 每 N ticks 更新)
   ▼
桥接参数(风险预算/流动性强度/冲击/智能体风险权重)
   │  (每 tick 可读)
   ▼
微观模型(订单簿 + 订单流 + 做市 + 异质 agent)
   ▼
价格/成交(高频涌现)
```

**→ 对 MC 的落地形态**
- 新增一个 `MacroMicroBridge`(或复用 `MacroStateEngine`)作为分层接口:宏观层写 `MarketState` 上的条件字段,微观层(`PriceDiscoveryEngine`/`OrderBook`/`MultiAgentManager`/`TransactionCosts`/`CircuitBreaker`)只读这些字段。
- 该桥接在**每 20-tick K 线步进内只做 O(1) 的参数换算**(regime 查表 + 线性插值),满足离线单线程约束。

---

## 5. 推荐的「微观-宏观耦合」设计模式(综合推荐)

综合以上调研,给出对 MC 模组最落地的一组设计模式(按优先级):

### 模式 P1 —— **离散宏观 Regime 状态机 + 参数表(Low-Tech, 高可落地)**
- **核心思想**:宏观层用有限个离散 regime(如 扩张/过热/衰退/萧条,或 低风险/中性/高风险)取代连续随机游走;每个 regime 携带一张**参数表**(风险偏好、目标波动率、流动性强度、冲击概率、智能体情绪偏置)。
- **现实中如何实现**:Regime-Switching 资产配置、Regime-Driven 系统策略均按状态取参数/权重(见 §2)。
- **对 MC 借鉴点/可实现性**:替换/扩展现有 `MacroEconomy.economicHealth` 随机游走为状态机;状态机在 `EconomicCycleEngine` 内步进,查表开销 O(1)。**可实现性:高**。
- **来源**:[GitHub Macro_Regime_Switching_Model](https://github.com/oj0nathan/Macro_Regime_Switching_Model)、[HSE Markov Chain Asset Allocation](https://www.hse.ru/en/edu/vkr/1160302602?vision=enabled)

### 模式 P2 —— **「宏观状态向量 → 微观条件参数」桥接层(Bridge)**
- **核心思想**:宏观层输出 `riskAppetite/creditImpulse/inflationPressure/volatilityRegime` 等**条件向量**到 `MarketState`;微观层把这些作为输入特征,不做跨层强制覆盖。
- **现实中如何实现**:宏观不确定/情绪影响流动性(§2.2);FX 订单流受宏观新闻驱动(§1.2)。
- **对 MC 借鉴点/可实现性**:将 `MacroStateEngine` 的连续量提升为桥接参数,微观层(agent/订单簿/成本)只读消费。**可实现性:高**。
- **来源**:[MacroStateEngine 现状(src/.../engine/MacroStateEngine.java)](src/main/java/com/example/financialmarket/engine/MacroStateEngine.java)、[Macro uncertainty & liquidity](https://www.sciencedirect.com/science/article/abs/pii/S1544612325011225)

### 模式 P3 —— **宏观冲击注入订单流(Event Injection)**
- **核心思想**:政策突变/危机时,宏观层向微观层注入**短时冲击脉冲**,让订单流在"公告后"集中反应(跳变、闪崩、成交激增),而非平滑 GBM。
- **现实中如何实现**:宏观公告(央行/FOMC/数据)→ 订单流不平衡显著反应,低延迟交易者争夺(§3.1)。
- **对 MC 借鉴点/可实现性**:在 `MacroStateEngine` 政策变动/`CrisisEngine` 触发时,向 `MultiAgentManager`/`OrderBook` 注入冲击信号。**可实现性:中高**(需校准幅度与衰减)。
- **来源**:[Order flow & macro news (arXiv 2508.06788)](https://arxiv.org/pdf/2508.06788)、[Heterogeneous trader responses (arXiv 2505.01962)](https://arxiv.org/abs/2505.01962?context=q-fin.PM)

### 模式 P4 —— **顺周期流动性供给(Procyclical Liquidity)**
- **核心思想**:做市商/流动性供给强度随周期变化——景气时流动性厚、衰退时价差扩、深度薄,放大波动聚集与肥尾。
- **现实中如何实现**:Dealer market making 的顺周期(§3.2)。
- **对 MC 借鉴点/可实现性**:把 regime 映射到 `OrderBook`/`TransactionCosts` 的价差/深度/冲击成本参数。**可实现性:高**(直接复用现有成本/深度类)。
- **来源**:[Procyclicality of Dealer Market Making](https://d.repec.org/n?u=RePEc:pra:mprapa:129114&r=mac)、[HFT in turbulent periods](https://publikationen.ub.uni-frankfurt.de/frontdoor/index/index/year/2017/docId/43866)

### 模式 P5 —— **风险预算开关(宏观定"允许多少风险",微观定"怎么成交")**
- **核心思想**:宏观层设定风险预算/仓位上限/目标波动率作为约束,微观层在执行/成交层面在约束内优化。
- **现实中如何实现**:风险平价、Top-Down/Bottom-Up 风险预算(§1.2/§2.1)。
- **对 MC 借鉴点/可实现性**:宏观衰退时收紧 `MarginManager`/`ShortPosition` 上限、提高 `CircuitBreaker` 灵敏度。**可实现性:高**。
- **来源**:[MFS Top-Down/Bottom-Up](https://www0.mfs.com/en-it/investment-professional/insights/portfolio-insights/macro-micro-construct-robust-risk-budgeting.html)、[Macro-Financial CVaR framework](https://www.mdpi.com/2227-7099/14/7/268/pdf)

### 模式 P6(前瞻,GAN 相关)—— **条件生成微观市场数据(Conditional Generative Microstructure)**
- **核心思想**:用条件生成模型(条件 GAN/扩散模型)按宏观状态生成逼真的订单流/订单簿 tick,为微观层/测试提供"处于某 regime 的市场数据"。业界已有 GAN 生成订单流、扩散模型生成 LOB 的研究。
- **现实中如何实现**:GAN 生成逼真股票订单流、扩散模型做 LOB 体积生成(见 GAN 可行性专项)。
- **对 MC 借鉴点/可实现性**:作为**离线 tick 模拟器**的改进手段,由宏观状态向量做 condition,生成 tick 级订单流喂给 `OrderBook`。**可实现性:中低**(计算量与训练需专门评估——由 micro-expert 的 t1 深入;见 ECON_RESEARCH GAN 可行性)。**建议定位为后台离线生成器**,不在主线程 tick 内跑生成网络。
- **来源**:[Generating Realistic Stock Market Order Streams (AAAI 2020, Li et al.)](https://mlanthology.org/aaai/2020/li2020aaai-generating/)、[DiffVolume: Diffusion Models for Volume Generation in LOB](https://dl.acm.org/doi/full/10.1145/3768292.3770413)、[Deep Learning in Quantitative Trading — GANs (Cambridge)](https://www.cambridge.org/core/elements/deep-learning-in-quantitative-trading/C39DE06D255470F6232BC97E2E5474E7)、[Towards Realistic Market Simulations: a GANs Approach](https://arxiv.org/abs/2110.13287)

---

## 6. 给架构师 / 微宏观专家的接口提示

- **宏观层应向 `MarketState` 公开**:`riskAppetite`、`creditImpulse`、`inflationPressure`(已有)、建议新增 `volatilityRegime`(离散)、`liquidityMultiplier`(价差/深度/冲击缩放)、`crisisShock`(冲击脉冲)。
- **微观层应消费而不覆盖**:`PriceDiscoveryEngine`、`OrderBook`、`MultiAgentManager`、`TransactionCosts`、`CircuitBreaker`、`MarginManager` 只读这些桥接字段。
- **时间尺度分离**:宏观层低频(每 N ticks)更新状态向量;微观层每 tick 只做 O(1) 查表/插值消费,不承担宏观演化计算。
- **GAN 归属**:只作为**离线、后台、种子可复现**的 tick/订单流生成器(由 micro-expert t1 评估),绝不进入主线程实时循环。

---

## 附:来源链接汇总
- 两层耦合 / 资产配置: [PIMCO](https://www.pimco.com/lat/en/resources/video-library/media/inside-pimcos-asset-allocation-process) · [MFS Top-Down/Bottom-Up](https://www0.mfs.com/en-it/investment-professional/insights/portfolio-insights/macro-micro-construct-robust-risk-budgeting.html)
- Regime 切换资产配置: [GitHub Macro_Regime_Switching_Model](https://github.com/oj0nathan/Macro_Regime_Switching_Model) · [HSE Markov Chain](https://www.hse.ru/en/edu/vkr/1160302602?vision=enabled) · [Regime-Driven Systematic Strategies](https://www.libristo.de/en/book/regime-driven-systematic-strategies_50508313)
- 波动率/流动性 regime: [Convex Cross-Asset Vol Regime](https://convextrade.com/glossary/cross-asset-volatility-regime) · [VRP & Stochastic Vol](https://www.unil.ch/events/1649240142374)
- 宏观不确定性与流动性: [Managerial sentiment & liquidity](https://www.sciencedirect.com/science/article/abs/pii/S1544612325011225) · [EPU & bond liquidity](https://www.sciencedirect.com/science/article/abs/pii/S0378426624002541)
- 订单流 ↔ 宏观新闻: [arXiv 2508.06788](https://arxiv.org/pdf/2508.06788) · [Heterogeneous trader responses arXiv 2505.01962](https://arxiv.org/abs/2505.01962?context=q-fin.PM) · [Rent seeking by low-latency traders](https://dro.deakin.edu.au/articles/journal_contribution/Rent_seeking_by_low-latency_traders_evidence_from_trading_on_macroeconomic_announcements/21025945) · [FOMC VIX futures activity](https://www.sciencedirect.com/science/article/abs/pii/S1057521924002539) · [FX microstructure: order flow & exchange rates](https://www.163.com/dy/article/L4OOFFF005568W0A.html)
- 做市/流动性 vs 周期: [Procyclicality of Dealer Market Making](https://d.repec.org/n?u=RePEc:pra:mprapa:129114&r=mac) · [What drives liquidity?](https://www.semanticscholar.org/paper/What-drives-liquidity-Identifying-shocks-to-market-Goldberg/5c6e32729ae1489601b05fab6f333c5a09734994) · [HFT in turbulent periods](https://publikationen.ub.uni-frankfurt.de/frontdoor/index/index/year/2017/docId/43866) · [HFT & Extreme Price Movements](https://www.aeaweb.org/conference/2016/retrieve.php?pdfid=20602&tk=9sTR8Tf2) · [Bond liquidity risks](https://macrosynergy.com/research/bond-market-liquidity-risks/)
- 分层/双层框架: [Two-Agent FX Stack](https://fxmacrodata.com/hi/articles/build-a-two-agent-fx-stack-research-and-execution-gatekeeper) · [Hierarchical DRL for VWAP](https://ieeexplore.ieee.org/document/10336391) · [Macro-Financial CVaR framework](https://www.mdpi.com/2227-7099/14/7/268/pdf) · [Heterogeneous trading rules & LOB](https://www.scilit.net/publications/3a1da4e4a26518b294859e9157652cc1)
- GAN / 生成式微观结构(跨域参考): [AAAI 2020 Order Streams GAN](https://mlanthology.org/aaai/2020/li2020aaai-generating/) · [DiffVolume LOB](https://dl.acm.org/doi/full/10.1145/3768292.3770413) · [GANs in Quant Trading](https://www.cambridge.org/core/elements/deep-learning-in-quantitative-trading/C39DE06D255470F6232BC97E2E5474E7) · [GAN Market Simulations](https://arxiv.org/abs/2110.13287)
