# Financial Market 经济引擎模型审计 —— 现实股市 / 行为金融差距分析

- 审计人：engine-analyst（经济引擎代码分析师）
- 项目路径：`F:\financialmarket`
- 审计对象：`src/main/java/com/example/financialmarket/engine/**` 的价格生成、公司数据、市场情绪、宏观、撮合模型
- 审计日期：基于当前工作树
- 说明：本审计为**模型/算法层**审计，聚焦「为什么生成的行情/价格/公司数据难以反映现实股市与行为经济学规律」。构建/运行时问题见 `AUDIT.md`。

---

## 0. 结论速览（TL;DR）

当前经济引擎名义上实现了「GBM 价格路径 + OU 宏观过程 + 四类行为智能体 + 订单簿撮合 + DCF/PE/PB 估值」的完整模型，但存在一个**结构性根因**和一批**行为金融/市场微观结构缺口**，导致产出失真：

1. **【结构性根因】两套价格锚完全脱钩**：真正的「成交价」由订单簿撮合决定，而智能体全部锚定在一个**永不变动的静态 fairValue** 上；GBM/宏观模型只驱动一个「基准价 gbmPrice」，它**不参与成交定价**，只用于涨跌停校验和 `mu` 微调。结果：成交价被静态估值「钉死」+ 噪声，既不跟随宏观也不跟随基本面演化。
2. **【次生根因】公司基本面被冻结**：`AICompanyData.updateFundamentals`（EPS/收入/ROE/fairValue 演化）**从不被调用**；EPS/净资产/内在价值在构造时由随机数一次性定死，之后永不变动。估值偏差 `(price-fairValue)/fairValue` 因此近似恒定。
3. **【死代码】大量「看起来先进」的子系统从未接入运行循环**：宏观周期、宏观总量经济、基本面引擎、情绪引擎的多数驱动、市场状态宏观变量（风险偏好/信用脉冲/通胀压力）全部未被 tick 调用。见 §3。
4. **【单位/量纲错配】fairValue 与 IPO 价系统性偏离数倍**：由于 `fairValue` 基于 `eps/(r-g)`、`eps=netProfit/totalShares`、`netProfit≈asset*0.08`，而 `ipoPrice=asset/totalShares`，fairValue 常为 IPO 价的 2~4 倍，造成上市初期价格被价值智能体强行拉抬到静态 fairValue 后长期横盘。
5. **【行为金融缺失】无羊群、无过度自信/损失厌恶/处置效应、无情绪-价格正反馈（泡沫/崩盘）、无信息级联**；散户在价格接近价值时用 `random.nextBoolean()` 抛硬币，情绪引擎计算出的买入比/全局情绪**从不回馈给交易决策**。
6. **【市场微观结构缺失】委托永不撤单/过期**，订单簿堆积陈旧限价单造成虚假深度；价格冲击/市场深度曲线缺位；成交滑点为纯随机；波动率恒定（无波动率聚集/杠杆效应/厚尾）。

> 一句话结论：引擎的「皮」（GBM + 宏观 + 智能体 + 估值）很全，但「里」——真正驱动价格的形成机制、基本面的时间演化、以及行为与情绪到价格的传导——基本是空转的。要贴近现实，需在**成交定价锚、基本面演化、行为反馈回路、订单簿生命周期**四条线上重构。

---

## 1. 实际在运行（会被执行）的模型是什么

从 `MarketEngine.tick()`（`MarketEngine.java:149-293`）逐行确认，每个游戏 tick（20 tick = 1 根 tick K线）真正执行的路径只有：

```
MarketEngine.tick
 ├─ agentManager.updatePhase(tickCounter)                    # 启动期 VALUE→VALUE_MAKER→FULL
 ├─ 交易日切换 performTPlus1Settlement()（当前为清空 pending，无实际结算逻辑）  # L149-172
 ├─ priceEngine.step()                                        # L183
 │    └─ macroEnv.step(tickCounter)   # OU: 利率r/政策不确定p/恐慌v   # MacroEnvironment.java:85
 │    └─ 每个资产 GBM: newPrice = price*exp(mu*dt + sigma*sqrt(dt)*N)  # PriceDiscoveryEngine.java:163-196
 ├─ 每个资产:
 │    ├─ agentManager.stepAgents(gbmPrice, lastPrice, book, liqMult)  # 生成限价单
 │    └─ book.placeOrder / book.matchWithSlippage                   # 撮合 → 更新 lastPrice
 ├─ 熔断检测（lastPrice vs prevClose）                        # L252-265
 └─ 推送盘口 + excellent24Index.updateValue(state) + circuitBreaker.tick()
```

- **宏观**：只有 `MacroEnvironment`（利率 r、政策不确定性 p、恐慌指数 v 的 OU/跳跃扩散过程）在跑，产出动态漂移 `mu` 与波动率 `sigma`。
- **价格**：每个资产的 GBM 基准价 `gbmPrice` 是纯宏观 + 高斯噪声的几何随机游走，**与交易完全无关**（仅 `updateLastPrice` 用成交偏差微调 `mu`，`FEEDBACK_ALPHA=0.02`，`PriceDiscoveryEngine.java:365-375`）。
- **成交价**：`lastPrice = state.asset.lastPrice`，完全由订单簿撮合决定；撮合价格 `tradePrice = mid + (rand-0.5)*slippage*mid`（`OrderBook.java:35-38`）。
- **智能体订单**：四种类型（VALUE/RETAIL/MOMENTUM/MARKET_MAKER）均以 `fairValue` 为锚挂限价单（`MultiAgentManager.java:235-375`）。**而 fairValue 是静态的**（见 §2）。

因此：**宏观 GBM 与实际成交价两条线相互独立**，成交价钉在静态 fairValue 附近。

---

## 2. 结构性根因（最高优先级）

### 2.1 两套价格锚脱钩 —— 成交价不跟随宏观/GBM

**证据**：
- `MultiAgentManager.valueDecision/retailDecision/momentumDecision/marketMakerDecision` 全部用 `fairValue * (1+offset)` 定价（`MultiAgentManager.java:243/284/320/356`）。
- `fairValue` 在 `stepAgents` 中用戈登模型重算：`fairValue = eps*(1+g)/(discountRate-g)`（`MultiAgentManager.java:179`），而 `eps`、`g`（revenueGrowth）**来自冻结的公司数据**。
- `gbmPrice` 在 `stepAgents` 中仅作参数传入，实际只被 `spread` 归一化用到（`MultiAgentManager.java:173`），不参与任何成交定价。
- `updateLastPrice` 的 mu 反馈强度极弱（`FEEDBACK_ALPHA=0.02`，且被钳到 ±0.10），不足以让 GBM 跟随成交价。

**后果**：宏观加息/恐慌/政策变化几乎不体现在真实成交价上；价格长期围绕静态 fairValue 波动，呈现「上市拉到 fairValue 后横盘 + 均匀噪声」的形态，而非现实中由基本面演化与市场情绪共同驱动、有趋势和聚集性的行情。

**对应现实理论**：现实股价是**预期现金流折现 + 供需/流动性 + 情绪**共同作用的结果，且围绕「不断移动的基本面锚」波动。这里基本面锚不移动，宏观锚又与成交脱节。

### 2.2 公司基本面被冻结（公司数据无时间演化）

**证据**：
- `AICompanyManager.updateAll()`（`AICompanyManager.java:47`）声明了，但**全局无任何调用**（grep 确认仅声明自身）。
- `AICompanyData.updateFundamentals()`（`AICompanyData.java:77`）因此**永不执行**。
- 故 `eps`、`revenue`、`revenueGrowth`、`netAssetValue`、`roe`、`dividendYield`、`fairValue` 全部等于构造时的随机初值。

**后果**：公司数据在玩家眼里是「静态快照」，无季度增长、无盈利波动、无行业景气度传导；`fundamentalScore` 恒定，估值偏差恒定，交易信号完全无变化。**公司数据无法反映真实公司基本面随宏观/行业/通胀演化这一基本事实**。

### 2.3 单位/量纲错配 —— fairValue 与 IPO 价系统性偏离

**证据**：
- `ipoPrice = initialAsset / totalShares`（`AICompanyData.java:58`）。
- `eps = netProfit / totalShares`，`netProfit = initialAsset * 0.08`（构造时，`AICompanyData.java:53,63`）。
- `fairValue` 由 `eps/(r-g)`（戈登）、`eps*peRatio`、`bookValue*pbRatio` 加权（`AICompanyData.java:113-138`）。

若 `initialAsset=30000`（万）、`totalShares≈12000`（万股）：
- `ipoPrice = 30000/12000 = 2.5`
- `eps = (30000*0.08)/12000 = 0.2`
- `fairValue(戈登) ≈ 0.2*(1.05)/(0.08-0.05) ≈ 7.0`；`fairValue(PE) = 0.2*15 ≈ 3.0`；加权后约 4~7。

即 fairValue 常为 IPO 价的 **2~4 倍**。价值智能体 `if(deviation<0) buy`（`MultiAgentManager.java:238-239`）会在上市初期疯狂买入把价格抬到 fairValue，随后因 fairValue 不动而**长期横盘**。这是「上市冲高 + 横盘」失真的直接来源。

### 2.4 状态保存时 fairValue 未持久化，重载后重置

`MarketEngine.save/load` 对 AI 公司只存 `asset/netAsset/us/cn`（`MarketEngine.java:384-435`），加载时用 `new AICompanyData(id,...,0.02,0.05,...)` **重新随机生成**基本面（`AICompanyData` 构造用 `Math.random()`，见 `AICompanyData.java:55-70`）。重载存档后公司基本面被整体洗牌，且 fairValue 重新计算。**公司财务在存档-读档间不连续**。

---

## 3. 死代码 / 未接线子系统（设计了但没接入运行）

以下系统在代码中存在，但 grep 确认**在 tick 运行路径中从未被调用**：

| 子系统 | 位置 | 证据 | 影响 |
|---|---|---|---|
| 基本面引擎 | `FundamentalEngine.step` | `MarketEngine` 中仅 save/load 引用（`MarketEngine.java:375,420`），`step` 无调用 | NAV 不随行业商品价调整 |
| AI 公司基本面演化 | `AICompanyManager.updateAll` | 仅声明（`AICompanyManager.java:47`） | 基本面冻结（§2.2） |
| 宏观总量经济 | `MacroEconomy` | 全工程无实例化；`MarketEngine` 只用 `MacroEnvironment` | GDP/通胀/失业/政府债务不影响市场 |
| 经济周期 | `EconomicCycleEngine.tick` | `MarketEngine.java:54` 仅实例化字段；`cycleEngine` 在 `PriceDiscoveryEngine.java:44/70` 仅赋值，`step` 无调用 | 繁荣/衰退周期不传导到资产 |
| 宏观状态（风险偏好/信用/通胀） | `MacroStateEngine.step` | `MarketEngine.java:372,419` 仅 save/load | `MarketState.riskAppetite/creditImpulse/inflationPressure` 恒为 0 |
| 市场情绪更新 | `MarketSentiment.updateGlobalSentiment` | 仅声明（`MarketSentiment.java:44`） | `globalSentiment` 恒为默认 0.7 |
| 情绪→交易 | `MarketSentiment.getBuyRatio` | 仅在 `SecuritiesManager`（自营）用到（`SecuritiesManager.java:33`），**MultiAgent 不消费** | 情绪不驱动价格 |
| 经济周期传导 | `PriceDiscoveryEngine.cycleEngine` | 字段存在但 `step` 不调用 | 周期无作用 |

> 即：名义上 20+ 子系统，实际在价格形成链上只有 `MacroEnvironment` + `MultiAgentManager` + `OrderBook` 真正参与。

---

## 4. 行为金融缺口（Behavioral Finance Gaps）

对照经典行为金融现象，逐项核验：

| 行为金融现象 | 现状 | 证据/说明 |
|---|---|---|
| **羊群效应（herding）** | ❌ 无 | 智能体彼此独立，不观察他人仓位/动作；无信息级联。`MultiAgentManager.stepAgents` 逐 agent 独立决策。 |
| **过度自信（overconfidence）** | ❌ 无 | 无交易量/频率随收益上升的非线性放大。 |
| **损失厌恶 / 处置效应（disposition effect）** | ❌ 无 | 无「赢家拿不住、亏家扛着」逻辑；买卖决策只看估值偏离/随机。 |
| **锚定（anchoring）** | ⚠️ 弱 | 散户锚定 fairValue，但无 IPO 价/近期高点锚定。 |
| **动量效应与动量崩盘** | ⚠️ 微弱 | MOMENTUM 只看 `priceChange`（`MultiAgentManager.java:306-339`），但无趋势强度/成交量确认，也无动量崩溃（多空不对称）。 |
| **情绪-价格正反馈（泡沫/崩盘）** | ❌ 无 | 全局情绪计算出来却不回馈交易；价格不因上涨而更乐观（无 self-reinforcing loop）。 |
| **代表性偏差 / 追涨杀跌** | ❌ 无 | 无「近期上涨→预期继续上涨」的放大。 |
| **信息不对称 / 知情交易** | ❌ 无 | 无内幕/知情智能体，价格不含私有信息噪声（`OrderBook` 滑点为纯随机）。 |
| **风险偏好的状态依赖（恐慌传导）** | ⚠️ 部分 | 宏观 v 影响 `riskAppetite`，进而影响下单概率/仓位（`MultiAgentManager.java:198,273,309`），但这是唯一的传导，且不产生羊群式骤停。 |
| **处置/决策时间离散与异质性** | ⚠️ 部分 | 四类 agent 有不同 tradeFrequency/风险偏好，但无「定期调仓/组合再平衡」的持久行为。 |

**散户决策尤其失真**：当估值偏离在 ±15% 内时，`retailDecision` 直接 `random.nextBoolean()` 抛硬币决定买卖（`MultiAgentManager.java:270`），散户行为退化为均匀噪声，毫无行为特征。

---

## 5. 金融市场微观结构 / 统计特征缺口

| 维度 | 现状 | 证据 | 现实特征 |
|---|---|---|---|
| **波动率聚集（volatility clustering）** | ❌ | `sigma` 只由宏观 v 决定（`MacroEnvironment.getDynamicVolatility`），状态无关 | GARCH：高波动后倾向高波动 |
| **厚尾 / 跳跃** | ⚠️ 部分 | GBM 高斯噪声；宏观 v 有跳跃（`MacroEnvironment.java:99`），但资产价格路径无跳跃 | 现实收益有肥尾、跳跃 |
| **杠杆效应（vol ↑ when price ↓）** | ❌ | sigma 与价格无关 | 下行放大波动 |
| **均值回归 / 慢速趋势的混合** | ⚠️ | 智能体对静态 fairValue 均值回归，无长期趋势 | 现实为「回归 + 趋势 + 噪声」混合 |
| **委托生命周期（撤单/过期）** | ❌ | `OrderBook.placeOrder` 从不删除未成交单（`OrderBook.java:10`）；无 expiry | 现实大量撤单，深度随时间刷新 |
| **价格冲击 / 市场深度** | ❌ | 只有均匀随机滑点 `(rand-0.5)*0.002*mid`（`OrderBook.java:36`）；无影响曲线、无按深度的部分成交 | 大单推动价格、深度有限 |
| **买卖失衡（order flow imbalance）→ 价格发现** | ❌ | 撮合价仅由成交单中价决定，无失衡信号 | 失衡预测短期价格 |
| **做市商库存/报价义务** | ❌ | MARKET_MAKER 只在 5% 概率下单（`MultiAgentManager.java:216`），无报价/库存管理 | MM 提供双边流动性并管理库存 |
| **成交量-波动关系** | ❌ | 量由订单尺寸累加，与波动无统计关联 | 放量伴随波动放大 |

**订单簿失真放大**：`placeOrder` 从不撤单，AI 每 20 tick 挂单，若不成交则永久留在簿内（`MarketEngine.java:210-217` 对超涨跌停的单直接 `continue` 丢弃，未超限的单则累积）。随时间累积，簿内堆满陈旧价格水平的限价单，形成**虚假的深度与远距报价**，撮合价长期在被陈旧单支撑的窄幅内游走。

---

## 6. 公司/行业/指数数据现实性缺口

1. **行业相关性弱**：`FundamentalEngine.step` 本应让公司 NAV 随行业商品价调整（`FundamentalEngine.java:12-31`），但从不调用。行业之间除商品价外无联动；无行业轮动/景气传导。
2. **公司参数纯随机**：`AICompanyManager`（`AICompanyManager.java:17-27`）用固定种子生成 50 家公司，`asset=30000+rand*120000`、`trend/vol` 随机。无「行业属性→财务结构」的一致性（如重资产高杠杆、轻资产高 ROE）。
3. **公司无生命周期事件**：无盈利意外（earnings surprise）、无业绩预告、无分红除息事件驱动、无增发/回购（`StockBuyback.java` 为骨架）。
4. **指数失真**：`Excellent24Index` 由成分股加权，但成分股基本面冻结、价格被静态估值钉住，指数因此近乎平坦，无法体现现实指数「长期上涨 + 牛熊周期 + 波动聚集」。
5. **时间尺度**：宏观步 `DT_MACRO=20/24000` 年 ≈ 每年 1200 步（`MacroEnvironment.java:46`），一天 24000 tick 即「1 天 = 1 年」尺度，宏观过程按此推进；但经济周期长度按 `24000*30` tick（=1 年）×3/9/20 设定（`EconomicCycleEngine.java:9-12`），而该引擎又不运行，时间尺度一致性无从保证。

---

## 7. 对后续改进的输入（供 architect 整合，非本任务结论）

在 §8 的「差距清单」基础上，为落地改进提供如下可操作线索（本轮不给出最终方案，仅作为审计产出）：

1. **统一价格锚**：让成交价显式跟随基本面锚 + 宏观锚 + 情绪，三者形成可调的权重；GBM 降级为噪声源而非独立价格。
2. **接通基本面演化**：在 `MarketEngine.tick` 中调用 `aiCompanyManager.updateAll` 与 `fundEngine.step`，并让 fairValue 按季度重算、可持久化。
3. **修复量纲**：统一 fairValue 与 IPO 价口径，使 IPO 价≈内在价值，避免系统性高估。
4. **注入行为反馈**：让全局情绪 → agent 下单概率/方向、形成情绪-价格正反馈（泡沫/崩盘）；给散户加入损失厌恶/处置效应/羊群项。
5. **补订单簿生命周期**：订单过期/撤单、深度受限、按深度的价格冲击、知情下单。
6. **接线死代码**：按需接入经济周期、宏观总量、宏观状态，或明确删除以免误导后续维护。

---

## 8. 差距清单（可直接作为需求评审的验收依据）

| ID | 类别 | 差距描述 | 严重度 |
|---|---|---|---|
| G-01 | 结构 | 成交价不跟随 GBM/宏观，钉死在静态 fairValue | blocker |
| G-02 | 结构 | 公司基本面被冻结（updateAll 从不调用），无时间演化 | blocker |
| G-03 | 结构 | fairValue 与 IPO 价量纲/尺度系统性偏离 2~4 倍 | high |
| G-04 | 结构 | 存档-读档后公司基本面随机重生成，不连续 | high |
| G-05 | 行为 | 无羊群/信息级联 | high |
| G-06 | 行为 | 无损失厌恶/处置效应/过度自信 | high |
| G-07 | 行为 | 情绪计算不反馈到交易，无泡沫/崩盘正反馈 | high |
| G-08 | 行为 | 散户在 ±15% 内用随机硬币决策，退化为噪声 | medium |
| G-09 | 微观 | 委托不撤单/过期，订单簿堆积虚假深度 | high |
| G-10 | 微观 | 无价格冲击/市场深度曲线，滑点纯随机 | medium |
| G-11 | 微观 | 无波动率聚集/厚尾/杠杆效应，sigma 状态无关 | medium |
| G-12 | 死码 | 宏观总量/经济周期/基本面/宏观状态/情绪更新未接线 | medium |
| G-13 | 数据 | 公司参数纯随机、行业无景气传导、无公司事件 | medium |
| G-14 | 数据 | 指数因成分冻结而近平坦 | medium |

---

## 9. 审计依据（关键 file:line）

- `MarketEngine.java:149-293` —— tick 主循环（接线真相）
- `PriceDiscoveryEngine.java:163-196` —— GBM 基准价（不参与成交定价）
- `PriceDiscoveryEngine.java:365-375` —— updateLastPrice 弱反馈
- `MultiAgentManager.java:179-193` —— fairValue 重算 + 物理正则化（基于冻结 eps）
- `MultiAgentManager.java:235-375` —— 四类决策均锚定 fairValue
- `AICompanyData.java:40-74,77-110,113-138` —— 冻结的基本面与 fairValue
- `AICompanyManager.java:47` —— updateAll 仅声明
- `OrderBook.java:10,35-38` —— 不撤单 + 随机滑点
- `MacroEnvironment.java:85-111,121-131` —— OU 宏观（唯一在跑的宏观）
- `MarketSentiment.java:21-42,44-65` —— getBuyRatio / updateGlobalSentiment（未被 agent 消费）
- `MacroStateEngine.java:15-24` —— step 无调用
- `EconomicCycleEngine.java:14-28` —— tick 无调用
- `MacroEconomy.java:31-63` —— 全工程无实例化
- `MarketEngine.java:384-435` —— 存档只存 asset，加载重随机
