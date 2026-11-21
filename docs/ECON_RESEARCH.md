# ECON_RESEARCH — 更贴近现实股市与行为经济学的模拟方案综述

> 任务: t2 (research-quant)
> 目标: 调研网上更先进、更贴近现实股市与行为经济学的经济/股市模拟方案,为改进 Financial Market 模组现有的 GBM 引擎提供候选方向与来源。
> 聚焦可落地约束: **Minecraft 服务器主线程 tick 驱动、无真实数据源、完全离线、Java/JVM 实现、需在单次 20-tick K 线步进内完成有限计算量。**

---

## 0. 现状速览(作为对比基线)

现有 `PriceDiscoveryEngine`(src/main/java/com/example/financialmarket/engine/PriceDiscoveryEngine.java)的核心定价模型是**几何布朗运动(GBM)**:

```
P(t+dt) = P(t) * exp( μ·dt + σ·√dt·ε ),  ε ~ N(0,1)
```

- 每 20 tick 步进一次,`dt = 20/24000`(年化)。
- `μ`、`σ` 由 `MacroEnvironment` 宏观因子统一驱动,**所有资产共用同一 σ**,无个股随机波动率。
- 价格扰动是**高斯白噪声**:无法产生波动聚集(volatility clustering)、肥尾(fat tails)、收益自相关、跳变(jump)。
- 成交价经 `MultiAgentManager` 生成订单流 → `OrderBook` 撮合(带滑点)决定,`updateLastPrice` 把成交价反馈回 `gbm.mu`(单一常数 `FEEDBACK_ALPHA=0.02`)。
- 随机源是 `ThreadLocalRandom`,**不可由世界种子复现**(离线可复现性差)。
- 历史 K 线用恒定波动率 + 高斯扰动预生成。

**结论性诊断(与行为金融/现实股市的关键差距)**
1. 高斯噪声 → 无肥尾、无极端行情、无闪崩,与现实收益率厚尾分布不符。
2. σ 恒定且全局同质 → 无波动聚集(现实是"波动一窝蜂")。
3. 无行为金融机制 → 羊群、过度反应/反应不足、处置效应、前景理论均缺失。
4. GBM 为外生"锚",智能体订单流对价格的反馈很弱(单一常数)。
5. 无真实数据注入通道(离线)。

以下所有候选方案都围绕弥合这些差距展开。

---

## 1. Agent-Based / ABM 市场模拟(异质智能体)

### 1.1 经典范式:SFI 人工股票市场(Arthur-Holland-LeBaron-Palmer-Tayler, 1997)
- **核心思想**:用遗传算法进化的"条件分类器"预测规则集合的智能体组成市场,智能体从经验中内生形成预期,价格由智能体行为涌现,而非预设模型。
- **与现有 GBM 差异**:价格是被"涌现"出来的(内生),而非先有 GBM 锚再叠加订单;预期是自适应的。
- **可落地性/成本**:**高**(成本较低)。实现"异质智能体 + 有限记忆的简单预测规则 + 集体行为驱动价格"完全可在单线程 tick 内完成。可参考现有 `MultiAgentManager` 的架构,把"规则池 + 每 agent 权重"替代固定的单一 GBM 锚。
- **来源**:
  - [Asset Pricing Under Endogenous Expectations in an Artificial Stock Market (Arthur et al., SFI)](http://web.stanford.edu/~judd/cef97/abstracts/arthur.html)
  - [Python 复现 felixschmitz/ArtificialStockMarketReproduction](https://github.com/felixschmitz/ArtificialStockMarketReproduction)
  - [SFI 历史综述](https://economics.hse.ru/en/det/histmet/news/1056057723.html)

### 1.2 异质交易者:基本面/图表/噪声交易者(fundamentalist–chartist–noise)
- **核心思想**:把智能体分成基本面者(锚定公司内在价值 fair value)、图表/技术者(追趋势/均值回归)、噪声交易者(随机)。异质规则相互作用即可涌现波动聚集、肥尾、羊群与泡沫崩盘(Chiarella, Iori, Perelló 等的 LOB 版)。
- **与现有 GBM 差异**:现有 `MultiAgentManager` 已有智能体,但定价仍被外生 GBM 主导;**本方案让价格直接由三类交易者的净订单涌现**,真正去中心化。
- **可落地性/成本**:**中高**(中等成本)。三类 agent 的决策规则简单,计算量小;与现有订单簿/撮合天然契合。
- **来源**:
  - [The impact of heterogeneous trading rules on the limit order book and order flows (Chiarella–Iori–Perelló)](https://www.scilit.net/publications/3a1da4e4a26518b294859e9157652cc1)
  - [Takahashi & Terano: Investors' Behavior in Financial Markets (JASSS)](https://legacy.econ.tuwien.ac.at/lva/compeco.se/artikel/jasss_2003_agent_based_approach_to_investor_behaviour.pdf)

### 1.3 现代开源/LLM 多智能体框架(可借鉴架构,非直接搬代码)
- **TwinMarket(NeurIPS 2025 / ICLR 2025 金融 AI 最佳论文)**:用 LLM 多智能体模拟社会经济系统,智能体带记忆、性格、社会交互。
- **StockSim**:订单级双模式 LLM 市场模拟器。
- **与现有 GBM 差异**:更真实的社会-经济耦合,但依赖 LLM 推理,不适合 Minecraft 主线程离线环境。
- **可落地性/成本**:**低**(直接落地成本高)。**借鉴点是其"人格/记忆/社交影响因子"的设计**,可压缩成轻量数值型 agent 参数。
- **来源**:
  - [GitHub FreedomIntelligence/TwinMarket](https://github.com/FreedomIntelligence/TwinMarket)
  - [StockSim (GitHub)](https://github.com/harrypapadakis/StockSim) / [StockSim 论文 arxiv:2507.09255](https://arxiv.org/abs/2507.09255)

### 1.4 已验证能复现统计规律的 ABM(强候选)
- **Cont & Bouchaud 羊群模型**(1.2/2.2 详述):单一订单/簇 + 连接度,即可涌现幂律肥尾与长记忆——是**成本/收益比最高**的补丁之一。
- **Lux & Marchesi 人工股市**:切换基本面/乐观/悲观三种态,涌现波动聚集与厚尾。
- **来源**:
  - [Lux–Marchesi 相关: Replicating agent-based simulation models of herding (Lancaster)](https://research.lancaster-university.uk/en/publications/replicating-agent-based-simulation-models-of-herding-in-financial/)
  - [High-frequency market sim & flash crash: agent-based (ar5iv 2208.13654)](https://ar5iv.labs.arxiv.org/html/2208.13654)

---

## 2. 行为金融模型(前景理论、羊群、过度反应等)

### 2.1 前景理论(Prospect Theory)
- **核心思想**(Kahneman–Tversky):人在**盈利区间风险厌恶、亏损区间风险追求**,且对亏损的痛感约为同额盈利快感的 2 倍(损失厌恶 λ≈2);概率被非线性扭曲。
- **在模拟中的用法**:让 agent 的价值函数 `V(x)=x^α (x≥0)`、`-λ(-x)^β (x<0)`,影响其"卖出/持有/加仓"决策(处置效应即其直接推论)。
- **与现有 GBM 差异**:现有 agent 无损失厌恶/参考点依赖;加入后能产生"持有亏损股、卖盈利股"的处置效应,改变订单流的时空分布。
- **可落地性/成本**:**高**(成本低)。只需在 agent 效用函数中插入一个分段幂函数,无需改变价格模型本身。
- **来源**:
  - [Prospect Theory and the Law of Small Numbers in the Evaluation of Asset Prices](https://www.econstor.eu/handle/10419/159380)
  - [BSV 行为模型(会计百科,过度反应/反应不足)](https://baike.esnai.cn/history.aspx?id=2195)

### 2.2 羊群效应 / 社交传染(Cont–Bouchaud 自旋模型)
- **核心思想**:每个 agent 是一个"自旋"(±1,买/卖/观望),以概率 `(1-a)` 独立决策、以概率 `a` 跟随邻居;簇在临界点附近形成 → 收益率呈**幂律肥尾**(P(|r|>x)~x^-μ,μ≈3)与**长记忆/波动聚集**。
- **与现有 GBM 差异**:这是**唯一能同时产出"肥尾 + 波动聚集"且计算极廉**的机制;不需要引入随机波动率。
- **可落地性/成本**:**高**(成本低,推荐优先落地)。Minecraft 世界本身有"玩家聚落/新闻事件"可当传染网络。
- **来源**:
  - [Cont & Bouchaud: Herd behavior and aggregate fluctuations in financial markets (cond-mat/9712318)](https://ar5iv.labs.arxiv.org/html/cond-mat/9712318)
  - [Volatility Cluster and Herding (cond-mat/0207280)](https://ar5iv.labs.arxiv.org/html/cond-mat/0207280)

### 2.3 过度反应 / 反应不足(BSV, Barberis–Shleifer–Vishny)
- **核心思想**:投资者对"好消息"有两种归因(持续性/均值回归),导致价格对新信息**反应不足(动量)**或**过度反应(反转)**,从而产生收益的可预测模式。
- **落地**:把"新闻/事件流"映射为 agent 预期修正,`ΔE = 反应因子·(新闻 − 已有预期)`,因子随"新闻是否成串"变化。
- **与现有 GBM 差异**:现有引擎的事件影响很弱;BSV 让事件产生**短期动量 + 长期反转**的现实形态。
- **可落地性/成本**:**中高**。
- **来源**:
  - [Barberis-Shleifer-Vishny 行为资产定价(UBT/Chicago Booth)](https://www.chicagobooth.edu/review/understanding-investor-sentiment)
  - [A Model of Investor Sentiment (BSV, 文献目录)](https://katalog.ulb.hhu.de/Record/9947668034806443)

### 2.4 处置效应 + 过度自信
- **核心思想**:投资者过早卖出盈利、过久持有亏损(处置效应,Shefrin–Statman);过度自信导致过度交易。
- **落地**:给 agent 加"参考成本"(买入均价),`r>0` 时提高卖出倾向、`r<0` 时降低卖出倾向;过度自信放大下单频率。
- **与现有 GBM 差异**:直接塑造订单流的"非理性但真实"结构。
- **可落地性/成本**:**高**(成本低)。
- **来源**:
  - [Overconfidence 综述(ORB Binghamton NEJCS)](https://orb.binghamton.edu/cgi/viewcontent.cgi?article=1143&context=nejcs)
  - [Hiroshi Takahashi & Takao Terano 行为 ABM 市场](https://legacy.econ.tuwien.ac.at/lva/compeco.se/artikel/jasss_2003_agent_based_approach_to_investor_behaviour.pdf)

---

## 3. 订单簿 / L2 限价订单簿(LOB)微观结构模型

### 3.1 现状匹配度
现有 `OrderBook`(bids/asks、撮合带滑点、涨跌停、熔断)已经是**真实的 L2 撮合引擎**。缺的不是撮合机制,而是**订单流的生成符合微观结构统计规律**(下单到达强度、价差/深度分布、自相关)。

### 3.2 零智能(Zero-Intelligence, Smith–Farmer–Gillemot)
- **核心思想**:仅随机生成限价/市价单(价格、数量服从经验分布),不需"理性";在正确供需下即可复现价差、深度、波动率缩放等统计事实。**证明"智能"不是必须的,订单流分布才是关键。**
- **与现有 GBM 差异**:现有 agent 订单来自规则决策;可混合 ZI 订单流作为"噪声"基底,提升盘口真实性。
- **可落地性/成本**:**高**(成本低)。
- **来源**:
  - [Statistical theory of the continuous double auction (Smith–Farmer–Gillemot)](https://ar5iv.labs.arxiv.org/html/cond-mat/0210475)

### 3.3 事件到达强度驱动(Hawkes / 自激励过程)
- **核心思想**:用 Hawkes 点过程(自激励:一次大单提高后续下单强度)模拟订单到达的**聚集性**,天然产生波动聚集与流动性剧变。
- **与现有 GBM 差异**:给订单流加入"事件自相关",比 GBM 的高斯噪声真实得多。
- **可落地性/成本**:**中**(需按 tick 更新强度 `λ(t)=λ0+Σ K·e^(-β(t-t_i))`,计算量可接受)。
- **来源**:
  - [A Deterministic LOB Simulator with Hawkes-Driven Order Flow (arxiv 2510.08085)](https://browse-export.arxiv.org/pdf/2510.08085)

### 3.4 数据基准与重建工具(用于校准,非运行依赖)
- **LOBSTER**:从 NASDAQ TotalView-ITCH 重建 L2 LOB 的数据库,提供 10 档盘口与逐笔消息。**离线时可用于校准**订单到达/价差分布参数,但模组本身无网络数据源。
- **ParSiWal**:Java 生态的事件驱动 LOB 模拟/回放平台(学术项目,偏实时 HFT)。
- **可落地性/成本**:**中低**。模组离线,不能实时拉取;但可把**从 LOBSTER 得到的统计分布参数硬编码/打包**进引擎,作为订单流生成的校准先验。
- **来源**:
  - [LOBSTER 产品说明(重建 NASDAQ L2 数据)](https://lobsterdata.com:443/info/docs/legal/LOBSTER_productInformation.pdf)
  - [Deep LOB forecasting microstructural guide (T&F, LOBSTER 数据)](https://www.tandfonline.com/doi/full/10.1080/14697688.2025.2522911)

### 3.5 可复用 Java 撮合/订单簿实现(参考或移植)
- **desmondsy/OrderbookEngine**:完整 Java LOB + 撮合引擎,支持 price-time / pro-rata / threshold pro-rata 三种撮合算法。
- **PriscillaOng12/Order-Book-Simulator**:重建交易所撮合引擎,维护盘口深度状态。
- **可落地性/成本**:**高**。现成 Java 撮合逻辑可参考增强现有 `OrderBook`(如加入价差报价改善、市价单、冰山单)。
- **来源**:
  - [OrderbookEngine (Java)](https://github.com/desmondsy/OrderbookEngine)
  - [Order-Book-Simulator](https://github.com/PriscillaOng12/Order-Book-Simulator)

---

## 4. 真实数据驱动(历史回放 / 真实数据注入 / Stylized Facts 复现)

### 4.1 真实数据注入(离线约束下的取舍)
- **核心思想**:用真实历史行情(如 LOBSTER、AlphaVantage)回放/注入,或在离线时将真实数据的**统计特征**固化进生成器。
- **与现有 GBM 差异**:现有引擎完全合成;注入真实路径最真实,但模组**无网络、需离线**,无法实时获取。
- **可落地性/成本**:**低→中**。完整回放不可行;但可采用 **"校准注入"**:把真实数据的风格化事实作为生成参数包打进模组。
- **来源**:
  - [websocket-trade-replayer(历史成交回放,模拟实时数据流)](https://github.com/i-d-lytvynenko/websocket-trade-replayer)
  - [DolphinDB Historical Data Replay 教程](https://raw.githubusercontent.com/dolphindb/Tutorials_EN/master/historical_data_replay.md)

### 4.2 Stylized Facts 复现(金融时间序列四大实证事实)
现实收益率应满足(GBM 高斯噪声全部违背):
1. **肥尾**(P(|r|>x)~x^-μ,μ≈3) — GBM 的 N(0,σ²√dt) 无厚尾。
2. **波动聚集**(大波动后跟大波动) — GBM σ 恒定,无聚集。
3. **收益自相关≈0,但绝对收益/平方收益自相关长程为正(长记忆)**。
4. **杠杆效应**(价格下跌时波动上升)。

对应工具/模型:
- **GARCH/EGARCH**:σ_t 由过去波动与冲击决定,`σ_t² = ω + α·ε_{t-1}² + β·σ_{t-1}²`。**单变量、逐 tick 可更新,是复现波动聚集的最轻量方案**,可直接替代现有恒定 σ。
- **Heston 随机波动率 + 跳跃扩散(Jump Diffusion)**:`dP/P = μ dt + √v_t dW₁ + J dN_t`,v_t 均值回归,含泊松跳跃 J → 同时得到随机波动 + 跳变 + 肥尾。
- **来源**:
  - [fsynth: Heston + Jump Diffusion 高保真合成金融数据 (GitHub)](https://github.com/welcra/fsynth)
  - [An asymmetric ARCH model: clustering & leverage effects](http://arxiv.org/pdf/1512.01916)
  - [Generating virtual scenarios of multivariate financial data (ar5iv 1802.01861)](https://ar5iv.labs.arxiv.org/html/1802.01861)

### 4.3 可复现的学术/开源时间序列生成器(JVM 可移植)
- **fsynth**(Python, Heston+Jump)、**JumpHMM.jl**(Julia, 泊松跳扩散 HMM):可作为**算法蓝本**,把数值方案(欧拉离散化 Heston、跳跃项采样)移植到 Java。
- **可落地性/成本**:**中高**。这些模型的离散化在单线程 tick 内完全可行;无需引入依赖,自实现即可。
- **来源**:
  - [fsynth](https://github.com/welcra/fsynth)
  - [JumpHMM.jl](https://github.com/varnerlab/JumpHMM.jl)

---

## 5. 开源/学术可借鉴的 Java/JVM 实现

| 项目 | 语言/平台 | 用途 | 借鉴点 | 来源 |
|---|---|---|---|---|
| **desmondsy/OrderbookEngine** | Java | LOB + 撮合 | 增强现有 OrderBook(多撮合算法) | [GitHub](https://github.com/desmondsy/OrderbookEngine) |
| **gowarrior/market-simulation** | Java | 市场模拟 + 策略回测 | 市场/策略分层架构 | [GitHub](https://github.com/gowarrior/market-simulation) |
| **harivilasp/Quantitative-Investment-Strategies-Platform** | Java | 回测 + AlphaVantage 数据 | 回测/组合层(数据源部分不采用) | [GitHub](https://github.com/harivilasp/Quantitative-Investment-Strategies-Platform) |
| **phelps-sg/jabm** | Java | Agent-Based Modelling 库 | ABM 智能体/仿真框架 | [jabm (relatedrepos)](https://relatedrepos.com/gh/phelps-sg/jabm) |
| **Mason / Repast / NetLogo** | Java 等 | 通用 ABM 平台 | 架构参考,不直接引入(太重) | [Self-Play & ABM 平台综述](https://pmc.ncbi.nlm.nih.gov/articles/PMC8201072/) |
| **StockSim** | Python/LLM | 多智能体订单级市场模拟 | 人格/记忆/社交因子设计 | [GitHub](https://github.com/harrypapadakis/StockSim) |
| **TwinMarket** | Python/LLM | 社会经济模拟 | 记忆/社交影响设计 | [GitHub](https://github.com/FreedomIntelligence/TwinMarket) |
| **felixschmitz/ArtificialStockMarketReproduction** | Python | SFI-ASM 复现 | 规则池进化智能体算法 | [GitHub](https://github.com/felixschmitz/ArtificialStockMarketReproduction) |

**可落地性总评**:纯 Java 项(OrderbookEngine、market-simulation、jabm、Mason)可直接参考或按需移植;LLM/Python 项只借鉴设计,不引入运行依赖。

---

## 6. 推荐方向与理由(聚焦 Minecraft 主线程/离线约束)

### 优先级排序

**P0 — 立即落地、成本最低、收益最高**
1. **引入随机/聚集波动率:用 GARCH(1,1) 替代恒定 σ**
   - `σ_t² = ω + α·ε_{t-1}² + β·σ_{t-1}²`。逐 20-tick 更新,单行数值公式。
   - 直接**复现波动聚集**;相对现有"全局统一恒定 σ"是纯收益、几乎零成本。
   - 理由:改动集中在 `PriceDiscoveryEngine.step()`,不触碰撮合/订单簿,风险最低。
2. **订单流加 ZI(零智能)噪声基底 + Hawkes 式事件强度**
   - 现有 L2 撮合引擎已具备;缺的是订单到达的聚集性。叠加自激励强度即可增强盘口真实性。
   - 理由:利用已有订单簿,低成本提升微观结构真实度。

**P1 — 强烈推荐,中等成本,带来"行为金融味"**
3. **Cont–Bouchaud 羊群自旋模型(传染性买卖簇)**
   - 单独即可涌现**幂律肥尾 + 波动聚集 + 长记忆**,正是现有 GBM 最缺的三件事。
   - 理由:单一机制覆盖多个 stylized facts;网络可用 Minecraft 的玩家聚落/城市/新闻事件作为拓扑,贴合游戏世界观。
4. **异质交易者(fundamentalist–chartist–noise)定价涌现**
   - 把"GBM 外生锚 + 订单反馈"改为**三类交易者净订单驱动价格**,让价格内生涌现。
   - 理由:去中心化定价是 ABM 的真实来源;与现有 MultiAgentManager 架构可渐进替换。

**P2 — 可作增强,成本中高**
5. **行为效用改造:前景理论(损失厌恶) + 处置效应 + 过度自信(BSV 过度反应/反应不足)**
   - 在 agent 效用函数加分段幂函数与参考点;事件流加"持续性/均值回归"归因 → 短期动量 + 长期反转。
   - 理由:直接塑造非理性订单流,提升"人性化"观感;需在 `MultiAgentManager` 内改动决策层。
6. **校准注入(非运行依赖):把 LOBSTER/真实市场的统计分布参数打包**
   - 订单到达、价差、深度、收益率分布等先验参数硬编码进模组,离线时按真实统计生成。
   - 理由:无网络也能"贴向真实",比纯手调参数科学。

**P3 — 不推荐在模组内直接引入**
7. 完整真实数据回放(需网络,违背离线约束)。
8. LLM 多智能体(TwinMarket/StockSim,需推理服务,违背主线程/离线)。
9. 重型 ABM 平台(Mason/Repast 整体引入,体积与依赖过大)。

### 落地架构建议(对接现有代码)
- **价格层改造**(`PriceDiscoveryEngine`):GBM μ/σ 保留但 σ 改为 GARCH 动态值,加入跳跃项(可选),并把"羊群/新闻事件因子"并入 drift。
- **智能体层改造**(`MultiAgentManager`):引入 3 类异质交易者 + 行为效用函数 + 传染网络,订单流由净情绪驱动,替代"外生 GBM 价 + 反馈常数"。
- **订单簿层**(`OrderBook`):补强价差/深度与订单到达强度,可选参考 OrderbookEngine 的多撮合算法。
- **可复现性**:把 `ThreadLocalRandom` 换成可播种的 `Random`/自定义 PRNG,纳入世界种子,使行情可复现。
- **线程/性能**:所有 P0–P2 均可用 O(agent 数) 或 O(资产数) 的单次 tick 循环完成,不阻塞主线程;GARCH/自旋/Hawkes 均为常数级更新。

---

## 7. 来源索引(完整链接)

### ABM / 多智能体
- [Arthur et al. SFI-ASM 摘要](http://web.stanford.edu/~judd/cef97/abstracts/arthur.html)
- [felixschmitz/ArtificialStockMarketReproduction](https://github.com/felixschmitz/ArtificialStockMarketReproduction)
- [TwinMarket (NeurIPS/ICLR 金融最佳论文)](https://github.com/FreedomIntelligence/TwinMarket)
- [StockSim (GitHub)](https://github.com/harrypapadakis/StockSim) · [论文](https://arxiv.org/abs/2507.09255)
- [ABM 平台综述(Self-Play & Trading)](https://pmc.ncbi.nlm.nih.gov/articles/PMC8201072/)
- [jabm (Java ABM)](https://relatedrepos.com/gh/phelps-sg/jabm)
- [High-frequency market sim & flash crash (ar5iv)](https://ar5iv.labs.arxiv.org/html/2208.13654)

### 行为金融
- [Prospect Theory & Law of Small Numbers](https://www.econstor.eu/handle/10419/159380)
- [Cont–Bouchaud 羊群模型 (cond-mat/9712318)](https://ar5iv.labs.arxiv.org/html/cond-mat/9712318)
- [Volatility Cluster and Herding (cond-mat/0207280)](https://ar5iv.labs.arxiv.org/html/cond-mat/0207280)
- [Lancaster: Replicating herding ABM 模型](https://research.lancaster-university.uk/en/publications/replicating-agent-based-simulation-models-of-herding-in-financial/)
- [Takahashi & Terano 行为 ABM 市场 (JASSS PDF)](https://legacy.econ.tuwien.ac.at/lva/compeco.se/artikel/jasss_2003_agent_based_approach_to_investor_behaviour.pdf)
- [Overconfidence 综述 (Binghamton)](https://orb.binghamton.edu/cgi/viewcontent.cgi?article=1143&context=nejcs)
- [BSV 行为模型(过度反应/反应不足)](https://baike.esnai.cn/history.aspx?id=2195)
- [Chicago Booth: Understanding Investor Sentiment](https://www.chicagobooth.edu/review/understanding-investor-sentiment)
- [Chiarella–Iori–Perelló 异质规则与 LOB](https://www.scilit.net/publications/3a1da4e4a26518b294859e9157652cc1)

### 订单簿 / LOB 微观结构
- [LOBSTER 产品说明](https://lobsterdata.com:443/info/docs/legal/LOBSTER_productInformation.pdf)
- [Statistical theory of the continuous double auction (Smith–Farmer–Gillemot)](https://ar5iv.labs.arxiv.org/html/cond-mat/0210475)
- [Hawkes-driven LOB simulator (arxiv 2510.08085)](https://browse-export.arxiv.org/pdf/2510.08085)
- [Deep LOB forecasting guide (T&F)](https://www.tandfonline.com/doi/full/10.1080/14697688.2025.2522911)
- [OrderbookEngine (Java LOB)](https://github.com/desmondsy/OrderbookEngine)
- [Order-Book-Simulator](https://github.com/PriscillaOng12/Order-Book-Simulator)

### 真实数据 / Stylized Facts / 时间序列生成
- [websocket-trade-replayer(历史回放)](https://github.com/i-d-lytvynenko/websocket-trade-replayer)
- [DolphinDB Historical Data Replay](https://raw.githubusercontent.com/dolphindb/Tutorials_EN/master/historical_data_replay.md)
- [fsynth (Heston + Jump Diffusion)](https://github.com/welcra/fsynth)
- [JumpHMM.jl (跳扩散 HMM)](https://github.com/varnerlab/JumpHMM.jl)
- [Asymmetric ARCH: clustering & leverage](http://arxiv.org/pdf/1512.01916)
- [Generating virtual scenarios of multivariate financial data](https://ar5iv.labs.arxiv.org/html/1802.01861)

### Java/JVM 可复用
- [OrderbookEngine](https://github.com/desmondsy/OrderbookEngine) · [market-simulation (Java)](https://github.com/gowarrior/market-simulation) · [Quantitative-Investment-Strategies-Platform (Java)](https://github.com/harivilasp/Quantitative-Investment-Strategies-Platform) · [jabm](https://relatedrepos.com/gh/phelps-sg/jabm)
