# ECON_IMPROVEMENT_PLAN — Financial Market 经济引擎改进/替换方案

> - 整合人：architect（方案架构/整合）
> - 输入：`MODEL_AUDIT.md`（t1，14 条差距 G-01~G-14）+ `ECON_RESEARCH.md`（t2，候选方案综述）
> - 目标：把「差距诊断」与「候选方案」整合成**可落地、分阶段、可验证**的经济引擎改进/替换方案，供后续实施。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick K 线步进内有限计算量、JVM。
> - 配套产物（t1/t2 已交付）：`MODEL_AUDIT.md`、`ECON_RESEARCH.md`。本文件为其结论性整合。

---

## 0. 结论速览（TL;DR）

当前引擎「皮很全、里空转」：宏观、智能体、估值、订单簿名义上都存在，但**真正驱动价格的机制基本没有接线**。改造的**四条主线**：

1. **定价锚统一（G-01/G-03）**：让 fairValue 随基本面动态演化、让 GBM/宏观/行为因子**反馈到订单流与成交价**，消除「三套价格锚脱钩」。
2. **死代码接线（G-12）**：把 `AICompanyManager.updateAll`、`FundamentalEngine.step`、`MacroStateEngine.step`、`EconomicCycleEngine.tick`、`MacroEconomy`、`MarketSentiment.updateGlobalSentiment` 接入 `MarketEngine.tick`，或明确删除。
3. **行为金融因子轻量嵌入（G-05/06/07/08）**：Cont–Bouchaud 羊群、前景理论损失厌恶、BSV 过度反应/反应不足，以**参数化形式**嵌入现有 `MultiAgentManager.stepAgents()` 与 `MarketSentiment`，形成情绪→订单流→价格的正反馈（泡沫/崩盘）。
4. **订单簿微观结构（G-09/10/11/14）**：订单撤单/过期、价格冲击/深度曲线、波动率聚集（GARCH）、订单流 ZI/Hawkes，让盘口与行情符合 stylized facts。

**分级落地路线**（与 t2 的编号映射见 §2.0）：

| 级别 | 主题 | 对应差距 | 主要改动类 |
|---|---|---|---|
| **P0** | 改进现有 GBM/订单簿 | G-01/02/03/04/11/12 | `PriceDiscoveryEngine`、`MultiAgentManager`、`AICompanyData/Manager`、`MarketEngine`、`OrderBook` |
| **P1** | 引入行为金融因子 | G-05/06/07/08 | `MultiAgentManager`、`MarketSentiment`、新增 `HerdingModel`/`BehaviorUtility` |
| **P2** | 订单簿/ABM 微观结构重构 | G-09/10/11/13/14 | `OrderBook`、`PriceDiscoveryEngine`、新增 `OrderFlowModel`/`MarketImpact` |

---

## 1. 结构性根因与改造目标（整合 t1 差距）

### 1.1 三套价格锚完全脱钩（G-01，blocker）——最优先修复

`MODEL_AUDIT.md §2.1` 的核心结论：当前有**三个互不相关的价格**：

| 锚 | 由谁产生 | 现状 | 是否参与成交定价 |
|---|---|---|---|
| **fairValue** | `AICompanyData.updateFairValue()`（戈登+PE+PB 加权），构造时随机定死 | **静态**，基本面被冻结（G-02） | ❌ 只是 agent 挂单的**锚点**，锚本身不动 |
| **gbmPrice** | `PriceDiscoveryEngine.step()` GBM 欧拉离散化，`mu/sigma` 由 `MacroEnvironment` 驱动 | 外生宏观+高斯噪声，与交易无关 | ❌ 仅用于涨跌停校验与 `mu` 微调（`FEEDBACK_ALPHA=0.02`） |
| **lastPrice（成交价）** | `OrderBook.matchWithSlippage()` 撮合 | 完全由订单簿决定 | ✅ 真实成交价 |

结果：成交价被静态 fairValue「钉死」+ 均匀噪声，既不跟随宏观、也不随基本面演化、更无行为反馈 → 「上市冲高到 fairValue 后长期横盘」。

**改造目标**：把 fairValue 变成一个**随基本面/宏观/行为演化的动态锚**，并让 GBM/宏观/行为因子**真实注入 agent 的订单方向与价格**，从而传导到成交价。

### 1.2 公司基本面冻结（G-02，blocker）

`AICompanyManager.updateAll()`（`AICompanyManager.java:47`）已实现但**从未被调用**；`AICompanyData.updateFundamentals()`（`AICompanyData.java:77`）因此永不执行。EPS/收入/ROE/fairValue 全部等于构造时随机值 → 公司数据是「静态快照」。

**改造目标**：在 `MarketEngine.tick` 中按「季度/年度」节奏调用 `updateAll`，使基本面演化，并持久化（G-04）。

### 1.3 量纲错配（G-03，high）

`ipoPrice = initialAsset/totalShares`，而 fairValue 由 `eps/(r-g)` 等模型算出，常为 IPO 价的 2~4 倍（详见 `MODEL_AUDIT §2.3`）。价值 agent `if(deviation<0) buy` 会在上市初期疯狂买进把价格抬到静态 fairValue。

**改造目标**：统一口径，使 `ipoPrice ≈ fairValue`（构造时用同一估值模型初始化），消除「上市冲高+横盘」。

### 1.4 死代码接线（G-12，medium）

| 子系统 | 现状 | 建议 |
|---|---|---|
| `AICompanyManager.updateAll` | 仅声明 | ✅ 接入 `MarketEngine.tick`（P0） |
| `FundamentalEngine.step` | 仅 save/load 引用 | ✅ 接入（P0）：让公司 NAV 随行业商品价调整 |
| `MacroStateEngine.step` | 仅 save/load | ✅ 接入（P0）：产出 riskAppetite/creditImpulse/inflationPressure 供 P1 消费 |
| `EconomicCycleEngine.tick` | 字段存在，无调用 | ✅ 接入（P0 末段或 P1）：economicHealth → 行业/资产因子 |
| `MacroEconomy` | 全工程无实例化 | ⚠️ 接入（P0 可选）或**明确删除**（避免误导维护） |
| `MarketSentiment.updateGlobalSentiment` | 仅声明 | ✅ 接入（P0/P1）：产出 globalSentiment，并让 P1 行为因子消费它 |

> 接线原则：**先接最小必要集**（G-02 基本面 + G-12 sentiment），把「看起来先进但没跑」的子系统要么真正跑起来、要么删掉，避免继续空转误导。

### 1.5 行为金融缺口（G-05/06/07/08）

对照 `MODEL_AUDIT §4`，散户在估值偏离 ±15% 内用 `random.nextBoolean()` 抛硬币（`MultiAgentManager.java:270`），情绪 `getBuyRatio` 不被 agent 消费。**无羊群、无损失厌恶/处置效应、无情绪-价格正反馈**。

→ 全部在 **P1** 以轻量参数化方式嵌入（见 §3）。

### 1.6 微观结构与统计特征（G-09/10/11/14）

`OrderBook.placeOrder` 从不撤单，AI 每 20 tick 挂单，未成交单永久堆积 → 虚假深度（G-09）。sigma 由宏观统一驱动、状态无关 → 无波动聚集/杠杆效应/厚尾（G-11）。撮合滑点纯随机（G-10）。成分冻结 → 指数近平坦（G-14）。

→ 在 **P0**（GARCH+撤单）+ **P2**（ZI/Hawkes/价格冲击/内生定价）处理。

---

## 2. 分级落地路线

### 2.0 t2 编号映射说明

`ECON_RESEARCH.md §6` 的 P0/P1/P2/P3 与本方案的任务契约分档**不完全一致**。按任务契约口径（P0 改现有 GBM/订单簿 → P1 行为因子 → P2 微观结构重构），映射如下：

| 本方案级别 | 吸收 t2 的哪些项 | 调整原因 |
|---|---|---|
| P0 | t2-P0(1) GARCH、t2-P3(9) 可播种 PRNG、t2-P0(2) ZI 订单流基底；外加 t1 的 fairValue 动态化/量纲/接线 | 契约要求 P0 改进现有 GBM/订单簿，故把 fairValue 与接线这些**最高优先**项并入 P0 |
| P1 | t2-P1(3) Cont–Bouchaud 羊群、t2-P2(5) 前景理论/处置/过度自信、BSV 过度反应 | 契约要求 P1 行为因子 |
| P2 | t2-P1(4) 异质交易者内生定价、t2-P0(2) Hawkes 事件强度、t2-P2(6) 校准注入、t2-P3(7/8) 真实数据(仅作非运行注入) | 契约要求 P2 订单簿/ABM 微观结构重构 |
| 不引入 | t2-P3 完整回放 / LLM 多智能体 / 重型 ABM 平台 | 违背离线/主线程约束 |

---

### 2.1 P0 — 改进现有 GBM / 订单簿（最低成本、最高收益，建议首发）

> 目标：不重构，只把「会跑的路径」修对 + 让 fairValue 动起来。改动集中在现有类内部，风险最低。

#### P0-1 波动率聚集：GARCH(1,1) 替代恒定 sigma（对应 t2-P0(1)、G-11）

- **改动类**：`PriceDiscoveryEngine`（`step()`，`PriceDiscoveryEngine.java:163-196`）
- **做法**：给每个 `GbmState` 增加 `sigmaSq`（条件方差）状态，逐 20-tick 更新：
  `σ_t² = ω + α·ε_{t-1}² + β·σ_{t-1}²`，其中 `ε_{t-1}` 为上一步的标准化收益冲击；`ω,α,β` 用**可播种**随机数在资产注册时初始化（α∈(0.05,0.15), β∈(0.80,0.93), 保证 α+β<1），宏观 `getDynamicVolatility()` 作为**长期基线** ω 的缩放因子。`σ_t = √σ_t²` 喂给现有 GBM。
- **收益**：单变量、常数级计算，直接复现**波动聚集**（大波动后跟大波动）；不影响撮合/订单簿。
- **工作量/风险**：约 1 个类内改动，**低工作量、低风险**。

#### P0-2 修复定价锚：fairValue 动态演化 + GBM 与成交价打通（G-01/G-02/G-04）

- **改动类**：`MarketEngine.tick`、`AICompanyManager`、`AICompanyData`、`MultiAgentManager`
- **做法**：
  - 在 `MarketEngine.tick` 中**按季度/年度节奏**调用 `aiCompanyManager.updateAll(state, riskAppetite, inflation)`（接线 G-02），使 EPS/ROE/fairValue 随宏观、行业、通胀演化（`AICompanyData.updateFundamentals` 已有完整实现，只需被调用）。
  - `MultiAgentManager.stepAgents()` 中已用 `company.getEps()` 实时重算 fairValue（`MultiAgentManager.java:177-180`），基本面一动 fairValue 自然跟着动——**这是「让 fairValue 动态化」的最廉价入口**。
  - **把 `gbmPrice` 降级为噪声源而非独立价格**：让 agent 订单价的锚从「纯 fairValue」改为「fairValue 为主、gbmPrice/宏观为扰动的混合锚」（见 P0-4 的锚公式），使宏观加息/恐慌真实传导到成交价。
- **工作量/风险**：涉及 3~4 个类、改动量中等。**中等工作量、中风险**（涉及 tick 接线，需回归测试启动期与读档）。

#### P0-3 修复量纲：IPO 价 ≈ 内在价值（G-03）

- **改动类**：`AICompanyData` 构造（`AICompanyData.java:40-74`）
- **做法**：构造时先算一次 fairValue，再用 `ipoPrice = fairValue`（或在 `registerAICompany` 中让 `seedPrice` 用 fairValue 而非 IPO 价）。消除「上市冲高到 fairValue 再横盘」的系统性高估。
- **工作量/风险**：1 处初始化逻辑，**低工作量、低风险**。

#### P0-4 行为/宏观反馈到订单（定价锚统一的第一步，G-01）

- **改动类**：`MultiAgentManager` 四个决策方法（`MultiAgentManager.java:236-375`）
- **做法**：把 agent 的限价锚从 `fairValue` 升级为混合锚：
  `anchor = fairValue · (1 + β_gbm·(gbmPrice/fairValue − 1) + β_macro·(riskAppetite−0.5))`
  并把 `priceOffset` 的方向/大小接入 `sentiment.getBuyRatio(assetId)`。这样宏观与情绪在**下单端**就反馈进订单，再由订单簿撮合成交价。
- **工作量/风险**：决策层参数化改造，**中等工作量、中风险**（需调参避免振荡）。

#### P0-5 订单簿生命周期：撤单/过期（G-09，低成本先行项）

- **改动类**：`OrderBook`（`OrderBook.java:10`）+ `MarketEngine`（`MarketEngine.java:210-217`）
- **做法**：为 `Order` 增加 `placedTick`/`expireTicks`；`matchWithSlippage` 前置一步 `purgeExpired()` 清理超龄未成交单；AI 每 N 次（如 5 次）步骤撤销未成交委托并重挂。消除「陈旧单堆积成虚假深度」。
- **工作量/风险**：`OrderBook` + `Order` 少量字段，**低工作量、低风险**。

#### P0-6 死代码接线（G-12）

- **改动类**：`MarketEngine.tick`
- **接线顺序**（每个子系统加一行调用 + 传参）：
  1. `macroEngine.step(state, dataCollector)` → 产出 riskAppetite/creditImpulse/inflationPressure（P0 末段或 P1 用）
  2. `cycleEngine.tick(gameTime, creditSystem, commodityMarket)` → 产出 economicHealth（P1 行业/资产因子用）
  3. `fundEngine.step(state, dataCollector, macroEngine, companyManager, commodityMarket, aiCompanyManager)` → 公司 NAV 随行业商品价调整
  4. `sentiment.updateGlobalSentiment(commodityMarket, inflationSystem, government, centralBank, npl, economicHealth)` → 产出 globalSentiment
  5. `MacroEconomy`：**若 P1 不需要宏观总量则明确删除**；若保留，则实例化并接入 `tick(gameTime)` 提供 GDP/通胀给基本面。
- **工作量/风险**：纯接线 + 传参，**低~中工作量、中风险**（依赖 `dataCollector`/`creditSystem` 等依赖链完整）。

#### P0-7 可播种 PRNG（G-11 辅助，对应 t2 可复现性）

- **改动类**：`PriceDiscoveryEngine`、`MultiAgentManager`、`MacroEnvironment`、`AICompanyData`、`OrderBook`、`MarketSentiment`、`MarketEngine`
- **做法**：把 `ThreadLocalRandom`（`PriceDiscoveryEngine.java:188,128,135`）与裸 `new Random()`（`MacroEnvironment.java:17`、`MultiAgentManager.java:76`、`MarketSentiment.java:10`）**统一替换为可播种的共享 PRNG**。完整方案见 §6。P0 阶段先给 `MarketEngine` 注入一个**世界种子派生**的 `Random` 并沿构造链传递，替换 `ThreadLocalRandom` 即可达到「同种子同行情」。
- **工作量/风险**：系统化替换，**中工作量、中风险**（改动面广但机械）。

> **P0 验收**：同一种子运行两次，行情序列可复现；关闭基本面接线后 EPS/ROE 不再恒定；成交价在宏观加息/恐慌时出现可观察的方向性变化；订单簿不再无限堆积陈旧单。

---

### 2.2 P1 — 引入行为金融因子（强烈推荐，中等成本）

> 目标：让价格出现「人性化」的泡沫/崩盘、趋势/反转、波动聚集与长记忆。**所有因子以轻量参数化形式嵌入现有 step/tick，不做模型替换。**（行为因子轻量嵌入 tick() 的详细方案见 §3）

#### P1-1 Cont–Bouchaud 羊群自旋模型（t2-P1(3)，G-05/07）

- **改动类**：新增 `engine/behavior/HerdingModel.java` + `MultiAgentManager`
- **做法**：每个 agent 视为一个自旋（+1 买 / −1 卖 / 0 观望），每步以概率 `a` 跟随「邻居/群体」的净情绪、以概率 `(1−a)` 独立决策；`a` 接近临界值时簇状同步 → **幂律肥尾 + 波动聚集 + 长记忆**，正好补现有 GBM 最缺的三件事。传染网络可用 Minecraft 的**行业群**（同行业公司 agent 互相连接）或**玩家聚落**作拓扑，贴合游戏世界观。
- **轻量参数**：`a ∈ (0,0.3)`（羊群强度）、连接度、簇大小上限。

#### P1-2 前景理论 / 损失厌恶 / 处置效应（t2-P2(5)，G-06）

- **改动类**：新增 `engine/behavior/BehaviorUtility.java` + `MultiAgentManager` 决策层
- **做法**：给 agent 价值函数插入分段幂函数 `V(x)=x^α (x≥0)`、`V(x)=−λ·(−x)^β (x<0)`，`λ≈2`（损失厌恶）。用 `(currentPrice − avgCost)` 作**参考点**（`AgentState.avgCost` 已存在）：`r>0`（盈利）提高卖出倾向（处置效应，赢家拿不住）、`r<0`（亏损）降低卖出倾向（亏家扛着）。这只改 agent 效用/下单概率，**不动价格模型**。
- **轻量参数**：`λ, α, β`、处置效应强度 `k_dispo`。

#### P1-3 BSV 过度反应 / 反应不足（t2-P2(5)，G-05/07）

- **改动类**：`MarketSentiment` + `MultiAgentManager` 事件感知
- **做法**：把「新闻/事件流」映射为 agent 预期修正 `ΔE = 反应因子·(新闻 − 已有预期)`；因子随「新闻是否成串」在「持续型（→ 反应不足/动量）」与「均值回归型（→ 过度反应/反转）」之间切换 → 产生**短期动量 + 长期反转**。可复用现有 `MarketSentiment` 的 `adjustAssetSentiment`/`adjustGlobalSentiment` 作为事件注入点。
- **轻量参数**：反应因子 `k_resp`、成串阈值、动量/反转权重。

#### P1-4 情绪–价格正反馈（泡沫/崩盘）（G-07）

- **改动类**：`MarketSentiment` + `MultiAgentManager` + `PriceDiscoveryEngine`
- **做法**：让 `globalSentiment` 和资产级 `buyRatio` **回馈到 agent 下单概率与方向**（P0-4 已埋点），并让价格变动再反过来推动情绪（`sentiment.adjustAssetSentiment(assetId, Δ·priceReturn)`）——形成 self-reinforcing loop，复现现实中的**泡沫与崩盘**。这是把「算出来的情绪」真正消费掉的关键。
- **轻量参数**：反馈系数 `k_fb`（需 < 某阈值防发散）。

> **P1 验收**：在固定种子下，放大羊群强度 `a` 可观察到明显的「涨—追涨—再涨」泡沫段与随后崩盘；盈利/亏损 agent 的卖出倾向出现处置效应不对称；价格收益出现短期自相关（动量）与长期反转。

---

### 2.3 P2 — 订单簿 / ABM 微观结构重构（可作增强，中高成本）

> 目标：让订单簿与行情符合微观结构统计规律。可在 P0/P1 稳定后逐步替换。

#### P2-1 异质交易者内生定价（t2-P1(4)，G-01 彻底版）

- **改动类**：`MultiAgentManager`（扩展为 fundamentalist–chartist–noise 三类）+ `OrderBook`
- **做法**：把「外生 GBM 锚 + 弱反馈」改为**三类交易者净订单流驱动价格**，价格真正从订单簿内生涌现（承接 t1 §7「让 GBM 降级为噪声源」）。现有 `AgentType.VALUE/RETAIL/MOMENTUM` 已接近基本面/噪声/趋势三类，可**渐进替换**而非推倒重来。

#### P2-2 ZI 零智能订单流基底 + Hawkes 事件强度（t2-P0(2)）

- **改动类**：`OrderBook` + 新增 `engine/microstructure/OrderFlowModel.java`
- **做法**：
  - **ZI**：以经验分布随机生成限价/市价单作为「噪声基底」，混入现有 agent 订单，提升盘口真实度（证明「智能」不是必须，订单流分布才是关键）。
  - **Hawkes**：用自激励点过程 `λ(t)=λ0+ΣK·e^(−β(t−t_i))` 描述订单到达的聚集性，一次大单提高后续下单强度 → 波动聚集与流动性剧变。

#### P2-3 价格冲击 / 市场深度曲线（G-10）

- **改动类**：`OrderBook.matchWithSlippage`（`OrderBook.java:35-38`）
- **做法**：把「纯随机滑点 `(rand−0.5)·slippage·mid`」替换为**随深度消耗的价格冲击函数**（吃单越深、冲击越大），并按档位部分成交（市场深度曲线）。大单推动价格、深度有限，符合现实。

#### P2-4 校准注入（t2-P2(6)，非运行依赖）

- **改动类**：新增 `engine/calibration/CalibrationParams.java`
- **做法**：把 LOBSTER/真实市场的**统计分布参数**（订单到达、价差、深度、收益率分布）硬编码/打包进模组，离线时按真实统计先验生成。比纯手调参数科学。完整回放（需网络）**不引入**（t2-P3(7)）。

> **P2 验收**：订单簿出现合理的价差/深度分布、大单产生可测的价格冲击；Heston/GARCH + 跳跃项使收益分布趋近肥尾；指数（`Excellent24Index`）因成分基本面不再冻结而出现牛熊波动与波动聚集（G-14）。

---

## 3. 行为金融因子的轻量参数化嵌入（落地细节）

> 契约要求明确「哪些行为金融因子可轻量参数化嵌入现有 step()/tick()」。答案：**全部三个（羊群 / 损失厌恶 / BSV）都可只改动 `MultiAgentManager.stepAgents()` 的决策函数与 `MarketSentiment`，不改价格模型、不改订单簿**。

嵌入点与参数表：

| 因子 | 嵌入位置 | 轻量参数 | 数据结构 | 计算量 |
|---|---|---|---|---|
| **Cont–Bouchaud 羊群** | `MultiAgentManager.stepAgents()` 内，下单方向用 `herdingTendency` 扰动 `isBuy`；`HerdingModel.computeGroupBias(assetId, group)` | `a`（跟随概率）、连接度 `k`、簇上限 | 每资产每 agent 一个 `group` 标记（复用 `industry` 或新增 `group` 字段）；`HerdingModel` 维护群体净方向 | O(agent) |
| **前景理论/损失厌恶/处置** | `MultiAgentManager` 的 `retailDecision`/`valueDecision` 等，用 `prospectValue(currentPrice−avgCost)` 乘入下单倾向 | `λ≈2, α≈0.88, β≈0.88, k_dispo` | 已有 `AgentState.avgCost/holdings` | O(1)/agent |
| **BSV 过度反应/反应不足** | `MarketSentiment`（`adjustAssetSentiment`/`updateGlobalSentiment`）+ agent 预期修正 `ΔE` | `k_resp`、成串阈值 `θ_run`、动量/反转权重 | `MarketSentiment` 增加事件缓冲 | O(events) |
| **情绪-价格正反馈** | `MarketSentiment` ↔ `PriceDiscoveryEngine.updateLastPrice`/`MultiAgentManager` | `k_fb`（防发散阈值） | 复用 `getBuyRatio`/`globalSentiment` | O(1)/tick |

**推荐默认参数包**（可在 `MarketEngine` 构造处集中配置，也便于后续 UI 调参）：
```
herding.a          = 0.15      // 0~0.3，>0.25 进入强簇状
prospect.lambda    = 2.0
prospect.alpha     = 0.88
prospect.beta      = 0.88
disposition.k      = 1.2
bsv.k_resp         = 0.6
feedback.k_fb      = 0.05     // 必须 < ~0.1 防发散
```
> 参数全部来自 P1 因子，**收敛性依赖 k_fb 与 a 的取值**，P1 落地时应先做离线调参再定默认值。

---

## 4. 定价脱钩修复方案（契约核心）

### 4.1 让 fairValue 动态演化（G-01/G-02）

- **接线基本面**（P0-2）：`MarketEngine.tick` 按季度节奏调用 `aiCompanyManager.updateAll(state, riskAppetite, inflation)` → `AICompanyData.updateFundamentals` 更新收入/净利润/ROE/fairValue。这是 fairValue 从「静态」到「动态」的根本修复。
- **MultiAgentManager 实时重算**：`stepAgents()` 已用 `company.getEps()`/`getRevenueGrowth()` 每次重算 fairValue（`MultiAgentManager.java:177-180`），基本面一变锚就动，无需额外改动。

### 4.2 让行为/基本面反馈到订单与成交价（G-01/G-07）

三层闭环（价格锚统一）：

```
fairValue(基本面,动态) ──┐
gbmPrice(宏观,噪声源)  ──┼─→ 混合锚 anchor ──→ agent 订单方向/价格 ──→ 订单簿撮合 ──→ lastPrice(成交价)
sentiment/行为因子(P1) ──┘                                   ↑                              │
                                                              └──── 正反馈(泡沫/崩盘, P1-4) ←┘
```

1. **锚层**（P0-4）：`anchor = fairValue·(1 + β_gbm·(gbmPrice/fairValue−1) + β_macro·(riskAppetite−0.5))`，宏观/GBM 首次进入下单端。
2. **行为层**（P1）：羊群/前景/BSV 扰动 `isBuy` 与下单倾向（§3），形成非理性订单流。
3. **成交层**（P2）：订单簿内生定价 + 价格冲击，成交价反向影响情绪（正反馈）。
4. **反馈层**（P1-4）：`sentiment` 随 `priceReturn` 变化，再回feed到 agent → 泡沫/崩盘。

> 修复后，`gbmPrice` 不再是「独立价格」而是「宏观噪声锚」，成交价由「动态 fairValue + 行为 + 宏观 + 订单簿」共同决定，从根本上消除三锚脱钩。

### 4.3 量纲与持久化（G-03/G-04）

- P0-3 统一 `ipoPrice ≈ fairValue`。
- P0-2 + P0-7 让基本面变化**可持久化**：`MarketEngine.save()` 的 `aiCompanies` ListTag 目前只存 `asset`（`MarketEngine.java:384-395`），读档用 `new AICompanyData(...)` 重随机 → **必须扩充序列化**，把 EPS/ROE/fairValue/revenueGrowth/growthStage 等基本面字段一并存入，加载时恢复而非重随机（G-04）。H2 镜像表 `ai_company` 同步增加列（见 §5）。

---

## 5. 每级的兼容性：H2 持久化、MCEF UI、网络包

### 5.1 H2 持久化兼容（`MarketEngine.save/saveToH2/loadFromH2`）

| 级别 | 需要的新增/变更 | 兼容策略 |
|---|---|---|
| **P0** | ① `ai_company` 表新增基本面列（eps/roe/fair_value/revenue_growth/growth_stage）；② `engine_kv` 的 `engine_main` blob 增加 fairValue 与 PRNG 状态（`PriceDiscoveryEngine.save()` 需补 gbm.sigmaSq 等 GARCH 状态） | **前向兼容**：`loadFromH2` 读到旧 blob/旧表时，缺失字段用默认值重建并触发 `init()` 重初始化（`MarketAttachment.java:56-58` 已有「空资产→重新 init」兜底，可复用）。新增列用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 或 `MERGE` 前检查列存在。 |
| **P1** | ① `engine_kv` blob 增加行为因子参数与 `HerdingModel` 状态（若需跨存档保持）；② `ai_company` 可选加 `sentiment` | 参数包可写入 blob 随主状态整体序列化（现有整块 NBT 方案天然支持，只加字段）。表结构优先不动，避免 H2 迁移。 |
| **P2** | ① `OrderBook` 状态（撤单/过期、深度）需在 `save()` 中持久化（目前订单簿不存，读档重建）；② 若新增 `OrderFlowModel`/`MarketImpact` 状态，写入 blob | `OrderBook` 目前**不进 NBT/H2**，读档后为空重建。若要跨存档保持深度/未成交单，需在 `MarketEngine.save()` 增加订单簿序列化；否则读档后订单簿从空重建、由 agent 重新挂单，可接受但不连续。 |

### 5.2 MCEF UI / Web 兼容（`FinancialMCEFScreen`、`LocalWebServerManager`、`ClientMarketDataCache`）

- **P0**：fairValue 动态化后，`getKlines`/`getTechnicalKlines`/`CompanyDataResponsePacket` 返回的 EPS/ROE/fairValue **字段名与类型不变**，只是数值不再恒定 → **UI 零改动**。GARCH 只改价格路径，K 线结构不变。
- **P1**：新增的 `globalSentiment`/`buyRatio` 若要在 UI 展示，是**增量字段**（加到 `CompanyDataResponsePacket` 或 Web JSON），不破坏现有字段；可选新增，不是必须。
- **P2**：订单簿深度/价差结构若增强，`OrderBookSnapshotPacket`（`MarketEngine.java:271-287`）的 bids/asks 结构（price/quantity 列表）**保持不变**，只是深度分布更真实 → 前端兼容；若要展示 L2 多档，再扩展 packet。

### 5.3 网络包兼容（`network/packets/*`）

- 所有 P0/P1 的改动**不新增/不删除任何 packet 字段**（P0/P1 是服务端模型内部改动）。
- P1 若想在 UI 暴露情绪，**新增字段用默认值**（向后兼容），旧客户端读到缺失字段走默认。P2 扩展多档深度时才需版本化 packet。
- **结论**：P0/P1 完全网络兼容；P2 如需新增字段则保持向后兼容默认值，或另开新 packet。

---

## 6. 可复现随机源方案（可播种 PRNG 纳入世界种子）

### 6.1 现状问题

`MarketEngine` 在 `MarketAttachment.java:73` 用 `new MarketEngine(System.currentTimeMillis())` 作种子 → **不可复现**；且内部散落 `ThreadLocalRandom`（不可播种）与裸 `new Random()`（`MultiAgentManager.java:76`、`MacroEnvironment.java:17`、`MarketSentiment.java:10`），各模块随机流互不相关。

### 6.2 方案

1. **取世界种子**：在 `MarketAttachment.getOrCreateEngine(ServerLevel level)` 中用 `level.getSeed()`（Minecraft `ServerLevel` 提供世界种子），派生一个引擎种子 `engineSeed = mix64(level.getSeed() ^ SALT)`，传入 `MarketEngine(engineSeed)`。`MarketEngine(long seed)` 构造已接受 seed（`MarketEngine.java:79`）。
2. **共享可播种 PRNG 池**：在 `MarketEngine` 维护一个**带流的 PRNG**（如基于 `java.util.Random` 扩展，或用 `SplittableRandom`/自定义 SplitMix64），用 `seed = mix64(engineSeed + 资产/模块id)` 为每个资产与每个模块派生**子流**，替换所有 `ThreadLocalRandom`/`new Random()`。推荐实现一个轻量 `SeedableRandom`（SplitMix64）封装，约几十行。
3. **持久化 PRNG 状态**：`PriceDiscoveryEngine.save()/load()` 增存各资产 GARCH/GBM 状态与当前随机流位置（`MarketEngine.java:447-547`），保证读档后**行情序列连续、可继续复现**。
4. **测试**：`main` 或单测中，用同一种子跑 N 步，断言两次输出的成交价序列一致；不同种子应显著不同。

### 6.3 收益

- 世界种子固定 → 同一世界同一种子，行情完全可复现（利于调试、回放、验收 stylized facts）。
- 各模块独立子流 → 一处 PRNG 推进不影响其他模块，便于隔离测试。

---

## 7. 可验证性指标（Stylized Facts 度量）

> 目标：用现实金融时间序列四大实证事实，量化「改进是否更贴近现实」。**在 P0 落地后建立基线，P1/P2 逐级对比**。

在 `PriceDiscoveryEngine` 已有 `getKlines/getRecentCloses`（`PriceDiscoveryEngine.java:434-438`）基础上，新增一个**离线统计面板**（`engine/validation/StylizedFactsValidator.java`），对每个资产收盘价序列 `r_t = ln(P_t/P_{t−1})` 计算：

| # | Stylized Fact | 度量方式 | 当前(GBM)期望 | 目标 |
|---|---|---|---|---|
| 1 | **肥尾** | 峰度 `κ = E[(r−μ)⁴]/σ⁴`；或收益分布 QQ 图/尾部幂律指数 `μ_tail`（`P(|r|>x)~x^−μ`） | 高斯 κ≈3，无肥尾 | `κ > 4`，尾部幂律 `μ_tail∈(2.5,3.5)` |
| 2 | **波动聚集** | `|r_t|`/`r_t²` 的 Ljung–Box 自相关（前 20 阶）；GARCH 系数 α,β 显著 | 高斯，无自相关 | `LB` 显著拒绝零自相关；`α+β` 接近 1 |
| 3 | **收益自相关 ≈ 0，绝对收益长程自相关为正** | `r_t` 与 `r_t²`/`|r_t|` 的自相关函数（前 20 阶）；Hurst 指数 `H` | `r` 自相关≈0，但平方收益也无 | `r` 自相关≈0，`|r|`/`r²` 自相关显著正、衰减慢 |
| 4 | **杠杆效应** | 收益与后续波动负相关：`corr(r_t, σ_{t+1})` 或 EGARCH 不对称系数；波动上升跟随下跌 | σ 与价格无关，无杠杆 | 显著负相关（下跌放大波动） |
| 5 | **情绪-价格正反馈/泡沫** | P1 后：价格在上涨段出现动量自相关，崩盘段收益分布左偏（`skewness < 0`） | 无动量/反转结构 | 出现可观察的泡沫-崩盘形态与负偏 |
| 6 | **微观结构**（P2）| 订单簿深度/价差分布、大单价格冲击量、成交量-波动关系 | 随机滑点、无深度 | 价差/深度符合经验分布、大单可测冲击 |

**验收流程**：
1. P0 落地后，用固定种子跑固定 tick 数，输出基线四项指标（应为「接近高斯、无聚集」）。
2. P1/P2 落地后同种子重跑，对比指标是否向现实区间移动。
3. 用 `seed` 参数化一键重跑，保证可复现（§6）。

---

## 8. 实施顺序与依赖（建议）

```
P0-3 量纲  →  P0-2 fairValue动态化+接线  →  P0-1 GARCH  →  P0-4 混合锚  →  P0-7 PRNG  →  P0-5 撤单  →  P0-6 死码接线
       ↓              ↓
    （基线：StylizedFactsValidator 记录 P0 指标）
       ↓
P1-1 羊群 → P1-2 前景/处置 → P1-3 BSV → P1-4 情绪正反馈（对 P1 复测 stylized facts）
       ↓
P2-1 内生定价 → P2-2 ZI/Hawkes → P2-3 价格冲击 → P2-4 校准注入（对 P2 复测）
```

- **依赖**：P0 是后续所有级别的基础（修复锚与接线），**必须先做**；P1 依赖 P0-4 的情绪/锚埋点；P2 依赖 P0-1（GARCH 作波动基线）与 P1-4（行为订单流作 ZI/Hawkes 基底）。
- **每级可独立交付与验收**，不必一次性全部上线。

---

## 9. 风险与缓解

| 风险 | 级别 | 影响 | 缓解 |
|---|---|---|---|
| tick 接线引入回归（P0-6 涉及依赖链） | 中 | 启动/读档 NPE 或死循环 | 复用 `MarketAttachment` 的「空资产→重新 init」兜底；每接一个子系统单测一次 |
| 行为因子参数不当导致价格发散/振荡（P1-4 `k_fb`） | 中 | 行情失真或跑飞 | 默认参数先离线调参；`k_fb<0.1`、`a<0.3` 设硬上限；StylizedFacts 面板监控 |
| H2 旧存档不兼容（P0-2/P0-7 加字段） | 中 | 读档失败或字段缺失 | 列存在检查 + 缺失字段默认值 + 兜底重初始化；保持 NBT 降级镜像 |
| GARCH 长期方差漂移 | 低 | 波动异常 | 约束 `α+β<1`，长期锚定宏观 `getDynamicVolatility()` |
| P2 内生定价计算量 | 中 | 主线程卡顿 | 全部 O(agent)/O(asset) 常数级，P2 逐项 benchmark 后再并入 tick |

---

## 10. 与差距清单的对照（验收闭环）

| 差距 ID | 解决级别 | 方案条目 |
|---|---|---|
| G-01 定价脱钩 | P0+P1+P2 | §4 全链条（锚/行为/成交/反馈） |
| G-02 基本面冻结 | P0 | P0-2 接线 `updateAll` |
| G-03 量纲错配 | P0 | P0-3 统一 IPO 价 |
| G-04 存档不连续 | P0 | P0-2 + §5.1 扩充序列化 |
| G-05 无羊群 | P1 | P1-1 Cont–Bouchaud |
| G-06 无损失厌恶/处置 | P1 | P1-2 前景理论 |
| G-07 情绪不反馈 | P1 | P1-3/P1-4 BSV+正反馈 |
| G-08 散户抛硬币 | P1 | §3 前景+羊群替换 `nextBoolean` |
| G-09 订单堆积 | P0 | P0-5 撤单/过期 |
| G-10 无价格冲击 | P2 | P2-3 价格冲击/深度 |
| G-11 无波动聚集/厚尾 | P0+P2 | P0-1 GARCH、P2-2 ZI/Hawkes |
| G-12 死代码 | P0 | P0-6 接线 |
| G-13 行业/事件 | P1+P2 | P0-2 行业传导、P2-4 校准 |
| G-14 指数平坦 | P0+P2 | P0-2 fairValue 动态化 → 成分股波动 → 指数有牛熊 |

---

## 11. 输入/来源索引

- 差距：`MODEL_AUDIT.md`（t1，§1~§9）
- 方案综述与来源链接：`ECON_RESEARCH.md`（t2，§1~§7）
- 代码证据：`MarketEngine.java`、`PriceDiscoveryEngine.java`、`MultiAgentManager.java`、`AICompanyData.java`、`AICompanyManager.java`、`OrderBook.java`、`MacroEnvironment.java`、`MarketSentiment.java`、`MacroStateEngine.java`、`EconomicCycleEngine.java`、`MacroEconomy.java`、`FundamentalEngine.java`、`MarketAttachment.java`
