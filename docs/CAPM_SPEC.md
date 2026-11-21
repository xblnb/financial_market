# CAPM_SPEC — 强制市场出清强化（价格连续/EMH） + CAPM 资产定价需求规格（需求契约）

> 文档定位：本规格是「市场微观结构与资产定价强化」的**需求/架构契约**（CM-0..CM-6），供 engine-dev
> （出清/价格连续性/EMH 强化）、agent-dev（CAPM/基本面估值实现）、test-engineer（验证，产出
> `.verify/CAPM_VERIFY.md` 与探针）、reviewer（评审）共同执行。审计对象为撮合/价格/估值/宏观链路
> （`engine/OrderBook.java`、`engine/MarketEngine.java`、`engine/PriceDiscoveryEngine.java`、
> `engine/ai/AICompanyData.java`、`engine/agent/MultiAgentManager.java`、`engine/macro/*`、
> `engine/Excellent24Index.java`、`engine/validation/StylizedFactsValidator.java` 等）。
> 目标：①在已交付流动性修复（MM/激进市价/集合竞价/撤单重挂，LIQUIDITY_SPEC LQ-0..LQ-10）**之上**强化
> 价格连续性约束（相邻成交价差有界/无跳空/无异常点位），让价格变动接近随机游走（EMH 弱式：历史价格无
> 预测力）且基本面信息即时反映；②引入 CAPM 资产定价——E[R_i]=R_f+β_i(E[R_m]−R_f)，β 由资产收益对
> 市场组合（优经 24 指数）收益的确定性回归估计；R_f 用央行/宏观利率；市场风险溢价来自宏观层；基本面
> AI 的 fairValue 折现改用股权成本 ke=R_f+β·ERP 而非固定折现率。保持 SeedableRandom 确定性可复现、
> H2/NBT 持久化兼容、不改已交付法币 M2/黄金/GBM-GARCH 核心公式（仅加 CAPM 定价接入点与出清连续性
> 强化）、A 股规则/涨跌停不回归、玩家单不受影响。开新存档测试。

---

## 0. 总验收（对应团队 Coverage CM-1..CM-6）

| ID | 验收项 | 判据（定量可测） | 责任 |
|---|---|---|---|
| CM-0 | 编译/构建通过 | `gradlew.bat compileJava` exit 0；`gradlew.bat build -x test` exit 0 出包 | 每期实施 |
| CM-1 | 价格连续：相邻成交价差有界 / 无跳空 / 无异常点位 | 固定种子长程逐资产：相邻成交（含集合竞价、撤单重挂、开盘首笔、强制单）|ln(P_n/P_{n-1})| ≤ CM_CONT_MAX_LOG(0.05)+取整容差（0.01 档位取整；容差 = 0.002+1e-9）→ 0 违反；全窗口成交价 NaN/Inf/≤0 次数 = 0；A 股开启时成交价出当日涨跌停带次数 = 0；竞价 P* 出清后锚更新 | engine-dev + test |
| CM-2 | 强制出清强化（均衡出清/触发正确/连续竞价无残留交叉） | ① 撮合交叉（bestBid≥bestAsk）存在且带内有可出清量时，同 tick 或次 tick 内完成出清（残留交叉 tick 占比 ≤ 0.1%）；② MAX_WAIT@60 / auction@120 / rehang@100 触发正确（构造场景断言，同 LIQ t4 风格）；③ 竞价 P* 为单一价、落在涨跌停带与连续性包络内、成交量最大、确定性 tie-break | engine-dev + test |
| CM-3 | EMH：价格接近随机游走（弱式：历史价格无预测力）+ 基本面信息即时反映 | 固定种子长程：①（**t6 勘误，reviewer CM-3a 决议**）日内（每 tick 采价 / 相邻成交 / 20-tick bar）收益一阶自相关 = **微观结构观察**（做市价差反弹 + 连续性锚日内均值回复），登记上界 \|ρ̂₁\| ≤ 0.5（防过度回复；观察值记录不断言 PASS/FAIL）；**日收益为正式弱式闸门**：n ≥ 100 个开市交易日、\|ρ̂₁\| < 1.96/√n（不显著拒绝 ρ=0）且 Ljung–Box(5) p>0.01，另报点估计 ρ̂₁（复用 StylizedFactsValidator 自相关/卡方右尾工具语义）；② \|r\| 绝对收益自相关仍显著为正（波动聚集不回归，既有 SF PASS 保持）；③ 季度基本面更新后：若 \|Δfair/fair\| ≥ 5%，开市窗口内首个含价值型 agent 的撮合 tick 当 tick 即按新 fair 锚定价（updateCompanyData 已每 tick 推入，同 tick 反映）且价格发现半衰期 ≤ 600 tick（既有「偏离 fairValue 半衰期」口径） | engine-dev + test |
| CM-4 | CAPM 定价：β 确定性回归 + ke=R_f+β·ERP | ① 回归数学正确性：构造 N=40 确定性对齐收益序列 y=0.8·x（方差非零）→ β̂=0.8±1e-9；指数收益零方差 / 样本 < MIN_BETA_OBS → 回落行业先验（确定性）；② ke 计算：给定 R_f/ERP/β 手算比对 ≤1e-9；③ 敏感度：R_f +2pct → 全资产 ke 升、fair 降；同宏观下 ke(β_high) > ke(β_low)；β 排序确定 | agent-dev + test |
| CM-5 | 基本面可用：fairValue/基本面 AI 用 CAPM 股权成本 | ① AICompanyData DCF 折现率 = ke（capm 开启）；capm 关闭 = 既有固定折现率（0.08+debtRatio×0.1）逐位一致；② 高 β 资产要求更高预期收益（ke 排序）且估值对宏观利率/风险溢价更敏感（弹性 e_high>e_low）；③ 研报/中观 fair 消费端（ResearchReport/MesoLayerManager/LocalHttpServer fairValue 输出）自动同源（只读 fair，无需改） | agent-dev + test |
| CM-6 | 确定性/持久化/不回归 | ① capm/clearing 关闭开关 → 与交付 LIQ 基线语义逐位一致（因子隔离，先例 LQ-9/LQ-10）；② 新增状态入 "capm"/"cm" blob，load 用 contains 兜底，旧档读档不失败；新存档 save→load→继续 与不存档运行序列一致（同 seed）；③ 不回归清单（见 §7.4）：法币 M2/黄金/GBM-GARCH 核心公式文件零改动、A 股规则/涨跌停/玩家单零影响、SeedableRandom 无新增裸随机 | 每期 + test |

**验收流程（test-engineer 落地）**：固定 engineSeed 长程（≥2 个 A 股交易日 × ≥4 资产）两次运行比对
（CM-6 确定性）；构造竞价/重挂/开盘/无成交窗口场景断言 CM-1/CM-2 触发与边界；固定种子统计相邻成交
gap、自相关/Ljung–Box、价格发现半衰期（CM-1/CM-3）；CAPM 单测直接驱动 CapmModel + AICompanyData
（CM-4/CM-5）；以 `cm.enabled` / `cm.capm` / `cm.clearing` 开关为轴开/关对比（CM-6 因子隔离）；
`gradlew.bat build` 出包。产出 `.verify/CAPM_VERIFY.md` + 探针归档（蓝图见 §9）。

---

## 1. 总设计原则（约束所有实现）

1. **纯增量、分层开关、因子隔离**（沿用 LIQ `liq.enabled` / LQ-9/LQ-10 先例）：
   - 总开关 `cm.enabled`（默认 true）；子开关 `cm.clearing`（出清/连续性/EMH 强化，默认随总开关）、
     `cm.capm`（CAPM 定价，默认随总开关）。对应引擎方法 `setCmEnabled/isCmEnabled`、
     `setCmClearingEnabled/setCmCapmEnabled` 与既有 liq 开关同风格。
   - `cm.clearing=false`：竞价连续性包络/锚更新/异常点位护栏等**新**行为零执行 → 与交付 LIQ 语义一致；
     `cm.capm=false`：β/ke/CAPM 折现零执行、AICompanyData fairValue 保持既有固定折现率公式逐位一致；
     `cm.enabled=false` 时二者同关。**任何新代码不消费随机源即不新增裸随机**（CAPM 全确定性，见 §6）。
2. **不改核心公式语义**：GBM-GARCH（`PriceDiscoveryEngine.step`）、法币 M2（`InflationSystem`/
   `CentralBank`/`Government`）、黄金定价（`GoldPricingSystem`）、A 股规则（`TradingCalendar`/
   `aShareClamp/limitUp/Down/WithinBand`、T+1、一手、价格/时间优先）**零改动或仅注入只读输入**。
3. **确定性**：本规格全部新增机制为**确定性纯函数/无状态规则**（竞价包络、锚更新、护栏、β 回归、ke
   折现、环缓冲追加）；β/ke 由既有确定性输入（成交/收盘序列、宏观利率/周期/危机、行业标签）推导，
   **不新增 SeedableRandom 子流、不消费任何随机** → 因子隔离最强形态（无随机消费即无序列扰动）。
4. **成交-盘口同源 + 连续性最后兜底次序**：所有成交仍源于订单簿真实档位（walkBook/竞价候选档）；
   价格次序恒为「档位价+有界冲击 → A 股涨跌停带钳制 → 连续性钳制 → **A 股涨跌停带最后钳制**」
   （LIQ F5 决议保持：带外成交 0 容忍）。新连续性行为不得破坏该次序。
5. **持久化兼容**：新增状态进 `MarketEngine.save()`（"capm"/"cm" 子 tag），`loadInternal` 用
   `tag.contains(...)` + 内部 `contains` 兜底重建；H2 走 `engine_kv.engine_main` 整块 blob（无表变更）；
   订单簿连续性锚为运行期瞬态（不落盘，读档由首笔成交自举 + 交易日开盘 resetContinuityAnchor，与 LIQ 一致）。
6. **A 股不回归 / 玩家单不受影响**：真实玩家挂单永不被新机制自动撤/改价/清空；玩家委托与 T+1/现金账本
   归属逻辑（`attributeTrade`）不改；新机制只作用于合成流与簿档位成交价约束。CAPM 只改 AI 公司的
   fairValue/ke 输入链，不触碰玩家账户、不打玩家持仓。

---

## 2. 现状审计（evidence-based）

### 2.1 撮合主循环现状（已交付 LIQ 之后）

```
MarketEngine.tick (MarketEngine.java:1336)
 ├─ calendar.advance / 交易日边界：prevClose 落定、T+1、清合成单、resetContinuityAnchor(prevClose) (1358-1470)
 ├─ L1: macroEnvironment.setPolicyRate(CB.discountRate); priceEngine.step() (GBM-GARCH, 1536-1538)
 ├─ 每 20 tick: 宏观传导 applyMacroTransmission / 危机 / sentiment (1541-1573)
 ├─ 每 FUNDAMENTAL_UPDATE_QUARTER_TICKS(24000×30×3): aiCompanyManager.updateAll → 各公司 updateFundamentals→updateFairValue (1576-1582)
 ├─ [L2] agentManager.stepAgents (锚 fair·(1+β_gbm·(gbm/fair−1)+β_macro·(riskApp−0.5))，orderBook 每 tick 推 company) (1675-1699)
 ├─ [L2.5] ZI/Hawkes 注入 (1702-1748)
 ├─ [L3] runLiqL25B（drain 市价 → MAX_WAIT 强制 → HFT → rehang → auction，LQ-1..5）(1774-1779)
 │        book.matchWithSlippage → walkBook（档位价+impact → band → continuity → band）(1781)
 │        recordLiqTrades / attributeTrade / tradeHistory / lastFillTick 刷新 (1782-1804)
 ├─ 熔断检测 / 盘口推送 / excellent24Index.updateValue(state) (1833-1875)
```

已交付的价格连续性基座（**审计确认的现状**，作为 CM-1 的基准面，不是本轮新设计）：
- `OrderBook.lastFillPrice` 连续性锚 + `clampForContinuity(price, anchor)`：相邻成交对数变动
  |ln(price/anchor)| > `CONTINUITY_MAX_LOG_MOVE = 0.05` 时夹断到 `anchor·exp(±0.05)`（OrderBook.java:35,155-161）。
- 锚更新点：`walkBook`（撮合/可市价执行/强制/HFT/重挂全部经它，307-313）、P0 回退撮合（435-438）。
- 交易日开盘 `resetContinuityAnchor(prevClose)`（LIQ F5：跨日锚残留修复，MarketEngine.java:1393-1401/1443-1451）。
- 涨跌停带最后生效：`fillPrice = aShareClamp(...)` 在连续性之后再次钳制（312/437），A 股 ±10% 带内成交。
- `PriceDiscoveryEngine.step` 内 GBM 参考价自身也有 `clampForContinuity`（5%/步）+ `FUNDAMENTAL_COUPLING=0.8`
  把 market lastPrice 向 GBM 拉近（R-3 语义：无成交也平滑移动）。

### 2.2 连续性缺口（本轮 CM-1/CM-2 要封堵的跳空类，每条带证据）

**GAP-1：集合竞价 P\* 不做连续性包络，且竞价出清后不更新连续性锚。**
- `runCallAuction`（OrderBook.java:648-745）：候选价只要求落在涨跌停带内（660-665），tie-break 只取
  与 referencePrice（prevClose）最近/低价（678-692），**从不与 lastFillPrice 锚比较**；成交价 = P\* 直接
  记账（724），**全方法无 `lastFillPrice = pStar` 赋值** → 竞价出清价可与竞价前最后一笔成交价跳空
  >5%（竞价触发前提是连续 ≥120 tick 无成交 + 簿聚合交叉——此时簿可能已整体漂离锚，P\* 相对锚的跳空
  是真实可达状态）。竞价后下一笔连续竞价成交又以**过期的旧锚**做连续性夹断 → 双跳空窗口。
- 修复面：`runCallAuction` 增加「连续性包络」过滤候选 + 出清后锚更新（§4.2 A-1）。

**GAP-2：重挂基准价 P_gbm 与锚解耦可能产生「带内但跳空」的首笔成交。**
- `liqCircuitRehang` 以 P_gbm ± 0.01×P_gbm 重挂并立即强制成交（MarketEngine.java:2164-2192）；该成交经
  walkBook 会被连续性夹断（好），但重挂单**挂单价**本身在带内任意处——若强制单方向规则与簿失衡使
  成交从旧锚链上夹断，连续竞价重启后的 MM 报价以新成交价（被夹断价）为 mid 重挂，属可接受；需在
  CM-2 场景断言「重挂成交后相邻成交有界」（不新增机制，仅验证面）。

**GAP-3：异常点位防御不集中。**
- 成交价经 aShareClamp + round 收敛（5% 夹断、band 最后钳制）后 NaN/Inf 不应出现；但撮合链缺少一道
  **显式护栏**（断言 finite && >0 && 带内），防御性审计计数为 0 的要求无落地位 → 在 OrderBook 增加
  集中 sanitize + 审计计数（只计数不改变成交，永不触发时输出 0，触发即实现缺陷暴露）。

**GAP-4（现状确认，非缺口）：锚的时效性。**
- 锚在交易日开盘重置；日内任意时长无成交后，锚仍为「最后一笔成交价」（5% 常数上界）。因 MAX_WAIT=60
  强制单/auction=120/rehang=100 已把无成交窗口封顶在 ~100 tick，锚最坏陈旧 100 tick（≈5s）；5% 常数
  上界与 ±10% 涨跌停带相容。本轮**不引入时间扩散放宽**（保持常数 5%，防越带/防过度自由），仅要求把
  竞价也纳入同一锚体系（GAP-1）——此点作为勘误/评审决议项（§8.2）。

### 2.3 估值/基本面现状（CAPM 接入点审计）

| 位置 | 现状（代码事实） | 与 CAPM 关系 |
|---|---|---|
| `AICompanyData.updateFairValue`（ai/AICompanyData.java:155-183） | DCF 折现率 **固定** `discountRate = 0.08 + debtRatio×0.1`（158），growthRate=2%..8%（157），terminal=2%，fair = w_dcf·dcfValue + w_pe·peValue + w_pb·pbValue（每股化，177-182）；`updateFundamentals` 每季度演进基本面后重算（90-123），`restoreFundamentals` 读档重算（127-152） | **主接入点**：折现率改读 ke=R_f+β·ERP（capm 开）；关闭时公式逐位一致 |
| `MacroEnvironment`（macro/MacroEnvironment.java） | 宏观利率 r（OU，锚 policyRate）、policyRate=央行贴现率、cyclePhase/crisisTension、`getDiscountRate()`=0.04+r+p·0.2+cycleRiskPremium+crisis·0.25（233-235）；`alpha_3=0.02` 注释为「ERP(股权风险溢价)」用于**漂移**（47）；`EconomicCycle.riskPremium(phase)`：EXPANSION 0 / PEAK 0.005 / CONTRACTION 0.03 / TROUGH 0.06（crisis/EconomicCycle.java:90-97） | R_f/ERP 的确定性来源：`getInterestRate()`(=r) 或 policyRate；周期 premium 与危机张力经公有 getter 读取（不触碰既有 getDiscountRate 公式） |
| `CentralBank.discountRate`（banking/CentralBank.java:38,78-91） | 央行贴现率（玩家/央行行长可调，guardrail 钳制 0.1%~10%），`MarketEngine` 每 tick `macroEnvironment.setPolicyRate(cb.getDiscountRate())` | R_f 的政策源头（宏观利率 r 向它均值回归） |
| `Excellent24Index`（engine/Excellent24Index.java） | 市值 Top-24 AI 公司成分；`indexValue` = 成分 lastPrice 等权均值 ×100，每 tick `updateValue(state)` 刷新；**无历史收盘序列、无每日收盘存档**（仅标量） | **市场组合代理**：需要「每日指数收盘」时间序列作为 β 回归的 x 轴 |
| `AICompanyManager.updateAll`（ai/AICompanyManager.java:71-75） | 逐公司 `updateFundamentals(state, riskAppetite, inflation)`（季度节拍），rng 消费顺序为已交付序列 | CAPM 折现必须在 updateAll 之后以 ke 重算 fair（纯函数、零 rng）→ 不扰动随机序列 |
| `MultiAgentManager.stepAgents`（agent/MultiAgentManager.java:431-541） | fair = company.getFairValue()（DCF+PE+PB 每股体系），混合锚 `fair·(1+β_gbm(gbm/fair−1)+β_macro(riskApp−0.5))`，MS-5a 加息单调约束，value/retail/momentum/MM 全部围绕该锚 | fair 值变化 → 同 tick 传导到 L2 锚（orderBook 每 tick 推 company，1675-1677）→ CAPM fair 自动驱动全部 agent；无需改 agent 决策公式 |
| `MesoLayerManager`/`ResearchReport`/`LocalHttpServer` | 中观与研报读 `company.getFairValue()`（MesoLayerManager.java:266、ResearchReport.java:70、LocalHttpServer.java:431/465） | 只读 fair，CAPM 后自动同源（零改动） |
| `PriceDiscoveryEngine` DAILY klines | 每资产日 K（聚合 DAILY，history 60 根；实时 TICK→DAILY 聚合），save/load 整档持久化（763-888）；`getRecentCloses/dailyClose` | 资产历史收盘参考（可选冷启动窗口，见 §4.3 设计注） |

### 2.4 EMH 现状与度量先例

- `StylizedFactsValidator`（validation/StylizedFactsValidator.java）已实现确定性收益自相关 `autocorr`、
  `acfLag`、`ljungBox`、卡方右尾 p 值、`olsSlope`（私有，225-290、1210），其 SF 输出已断言
  `autocorr(r) lag1 绝对值 < 0.05 → PASS 收益近似零自相关`（380）。→ EMH 弱式**验证工具面已存在**
  （探针可复用/等价重实现），本轮只补「面向真实引擎成交序列的 CM-3 探针」。
- 「价格发现效率（偏离 fairValue 半衰期）」已有度量口径（StylizedFactsValidator:2257-2260）→ CM-3 信息
  即时性可复用同一口径（均值半衰期 >0 表示向价值回归；本轮给上界）。

---

## 3. 总体架构与改动布局

```
                 ┌──────────────────────────────────────────────────────┐
                 │  MarketEngine.tick                                     │
  L1 宏观/GBM ──▶│  · 交易日边界：prevClose 落定 + capm 收盘采样(CAPM)      │
                 │  · 季度基本面：updateAll → CAPM ke 重算 fair (CAPM)     │
                 │  [L2] agent（锚=CAPM fair，同 tick 反映）               │
                 │  [L2.5] ZI/Hawkes                                       │
                 │  [L3] runLiqL25B：auction 连续性包络 + 锚更新 ★CLR      │
                 │       matchWithSlippage：sanitize 护栏 ★CLR             │
                 └───────────────┬────────────────────────────────────────┘
                                 ▼
              OrderBook：runCallAuction 包络 / touchContinuityAnchor / sanitizeFill
              CapmModel(新, engine/capm)：每日收盘环、OLS β、R_f/ERP、ke 折现接入
              AICompanyData：updateFairValue(equityCost) 重载（DCF 折现=ke）
```

### 3.1 改动文件总表（相对仓库根；★=新文件）

| 文件 | 责任 | 改动内容 |
|---|---|---|
| `engine/OrderBook.java` | engine-dev | `runCallAuction` 增加连续性包络过滤 + tie-break 锚优先 + 出清后 `touchContinuityAnchor(pStar)`；新增 `touchContinuityAnchor(double)`（更新 lastFillPrice 与锚 tick）；新增 `sanitizeFill` 集中护栏（finite/>0/带内断言 + 审计计数 `invalidFillGuardHits`，只读 getter 供探针） |
| `engine/MarketEngine.java` | engine-dev + agent-dev | ① clearing：auction 调度段按 `cm.clearing` 传连续性包络参考（lastFillPrice 锚）、auction 出清后锚同步、sanitize 护栏计数暴露；② CAPM：持有 `capmModel`、交易日边界收盘采样、季度 ke 重算、save/load "capm" blob、开关组 `cm.enabled/cm.clearing/cm.capm`、读档后 fair 恢复（order 内） |
| `engine/PriceDiscoveryEngine.java` | agent-dev | **不改核心公式**；仅新增只读历史收盘导出（若 CAPM 冷启动窗口选 kline 方案，见 §4.3 注）或零改动 |
| `engine/capm/CapmModel.java`（★新） | agent-dev | CAPM 服务：指数/资产每日收盘对齐环（≤90）、OLS β（含方差零/样本不足回落、先验收缩）、R_f/ERP 派生、ke=clamp(R_f+β·ERP)、save/load（contains 兜底） |
| `engine/capm/OlsRegression.java`（★新，或内嵌静态） | agent-dev | 确定性最小二乘：给定 N 对 (x,y) 返回 (β, α, R², n)；纯函数、无随机（供单测与引擎共用，探针可交叉复算） |
| `engine/ai/AICompanyData.java` | agent-dev | `updateFairValue()` 保持既有公式为默认；新增 `updateFairValue(double equityCost)`：equityCost>0 时 DCF 折现率=equityCost（含 ke−growth ≥ 安全分母护栏），否则既有 0.08+debtRatio×0.1 |
| `engine/ai/AICompanyManager.java` | agent-dev | 新增 `updateAll(state, riskAppetite, inflation, DoubleUnaryOperator equityCostOf)` 重载（缺省回落既有 updateAll，零 rng 扰动）；公司列表遍历顺序不变 |
| `engine/agent/MultiAgentManager.java` | agent-dev | 零改动或仅只读 getter（若暴露 ke/β 给 UI/验证）；**不改**锚/决策公式 |
| `engine/macro/MacroEnvironment.java` | agent-dev | 零改动（只读 getInterestRate/getCyclePhase/getCrisisTension/policyRate 已有） |
| `engine/securities/ResearchReport.java`、`engine/meso/*`、`web/LocalHttpServer.java` | — | 零改动（fair 只读消费自动同源） |
| `CAPM_SPEC.md`（本文件） | architect | 需求契约（评审锚） |

---

## 4. P-A — 出清/连续性强化（engine-dev；对应 CM-1/CM-2，开关 `cm.clearing`）

> 设计哲学：在 LIQ 已交付的 LQ-1..LQ-5 之上**只封跳空类、不重排已交付随机/撮合次序**；全部新机制
> 确定性、零随机、默认开（`cm.clearing=true`），关闭即交付 LIQ 语义（因子隔离）。

### 4.1 需要强化但现状已满足的（只写验收，不改代码）

- 连续竞价相邻成交（walkBook/可市价执行/强制/HFT/重挂）已受 `clampForContinuity` 5% 夹断 → CM-1 断言面。
- 交易日开盘锚重置、跨日不越带（LIQ F5）→ CM-1/CM-6 回归面。
- 带外成交 0 容忍（band 最后钳制）→ CM-1c 断言面。

### 4.2 增量设计（GAP-1..GAP-3 修复）

**A-1（GAP-1）集合竞价纳入同一锚体系 —— `OrderBook.runCallAuction`（`cm.clearing` 门内）：**

1. 取连续性锚 `anchor = lastFillPrice`（>0 时有效；≤0 → 沿用既有语义：仅 band 约束 + 参考 prevClose）。
2. 计算**连续性包络** `env = [max(limitDown, anchor·e^{−M}), min(limitUp, anchor·e^{+M})]`（M =
   `CONTINUITY_MAX_LOG_MOVE`=0.05；anchor≤0 时 env=[limitDown, limitUp]）。
3. 候选价过滤：既有 band 内候选（660-665）**再要求**落在 env 内；取 env 内 volume(P) 最大的 P\*；
   tie-break 次序：① volume 最大；② 与 continuity 锚（有锚）或 referencePrice（无锚，兼容既有）最近；
   ③ 低价。全部确定性、无随机。
4. env 内无候选（锚与簿脱节到包络外，带内仍交叉）→ **本轮不竞价**，返回空（回落 P1 强制单路径——
   其成交同样被 5% 夹断，保证首笔连续）；`lastAuctionTick` 照旧记录尝试防空转（既有 120 tick 抑制语义
   保持），且由 P1/重挂先打断（P1 阈值 60 < auction 120，工程上强制单先触发）。
5. 出清成功后：`touchContinuityAnchor(pStar)`（更新 lastFillPrice=round2(pStar)）→ 竞价后连续竞价首笔
   以新锚夹断，无第二跳空。

**A-2（GAP-3）异常点位集中护栏 —— `OrderBook` 新增：**

- `sanitizeFill(double p)`：`!Double.isFinite(p) || p <= 0` → 返回 -1（不可用），调用点（walkBook/P0
  回退/竞价/残余转限价/重挂价）对 -1 落审计计数 `invalidFillGuardHits++` 并跳过该笔成交/档位
  （防御性：正常永不触发）；`A 股开启且 (p < limitDown || p > limitUp)` → 同样计数并回退 band 端点
  （既有一致语义，双保险）。计数只读 getter 暴露（探针断言 = 0）。确定性、零随机。
- 说明：不改成交语义的既有路径（band→continuity→band 次序不变）；护栏只把「理论不可达状态」暴露为
  硬计数，而非新增伪价。

**A-3（GAP-2 验证面）** 重挂/竞价/开盘首笔纳入 CM-1 相邻成交断言（不改代码，验证覆盖）。

### 4.3 确定性 / 持久化（P-A）

- 零新随机：A-1/A-2 全部为确定性规则（锚/包络/round2 纯函数）。
- 锚（lastFillPrice）为运行期瞬态，不落盘（与 LIQ 一致）；`invalidFillGuardHits` 为瞬态审计计数
  （不落盘；探针在运行期读取）。
- `cm.clearing=false`：A-1 的 env 过滤与锚更新、A-2 护栏计数路径零执行（返回既有 runCallAuction 语义）
  → 与交付 LIQ 逐位一致（CM-6 因子隔离）。

### 4.4 P-A 验收要点（详细探针在 §9）

| ID | 判据 |
|---|---|
| CM-1a | 长程全成交序列（含竞价/重挂/开盘首笔）相邻 \|ln(P_n/P_{n-1})\| ≤ 0.05 + 0.002 容差 → 0 违反 |
| CM-1b | 构造竞价场景（GAP-1 复现：簿聚合交叉、锚与 P* 候选脱节 >5%）→ `cm.clearing` 开：竞价 P\* 落 env∩band 内、竞价后锚=round2(P\*)、相邻成交有界；关：回落既有行为 |
| CM-1c | 异常点位护栏：长程 invalidFillGuardHits=0；构造 NaN/Inf 输入 → 计数 >0 且无成交产生 |
| CM-2a | 交叉出清残留 tick 占比 ≤ 0.1%（固定种子 2 交易日×4 资产） |
| CM-2b | MAX_WAIT@60/auction@120/rehang@100 触发正确（构造场景，同 LIQ t4 风格） |
| CM-2c | 竞价单一价、量最大、tie-break 确定、两次同簿哈希逐位一致 |

---

## 5. P-B — CAPM 资产定价（agent-dev；对应 CM-4/CM-5，开关 `cm.capm`）

### 5.1 模型定义（确定性公式）

**β 回归（资产收益 ~ 指数收益）：**

- 市场组合代理 = 优经 24 指数（Excellent24Index，现有市值 Top-24 等权指数）。
- 收益用**对数收益**：`r_i(t) = ln(C_i(t)/C_i(t−1))`，C = 当日收盘（资产 = 该资产交易日收盘
  lastPrice/prevClose 落定值；指数 = 同交易日指数收盘 `excellent24Index.getIndexValue()`）。
- 对齐窗口：交易日收盘时向**每资产环缓冲**追加 (x_t=指数收益, y_t=资产收益)，长度 ≤
  `CAPM_MAX_OBS`（默认 90，常量在 CapmModel）；窗口内对齐对一一对应（缺测跳日不追加，确定性）。
- OLS：`β̂ = Σ(x−x̄)(y−ȳ) / Σ(x−x̄)²`（`OlsRegression`，纯函数；方差为 0 或 N < `CAPM_MIN_OBS`（默认 20）
  → 不回归）。
- **先验收缩（确定性、金融一致）**：`β = w·β̂ + (1−w)·β_prior`，`w = min(1, n/MIN_BETA_OBS)`；
  `β_prior` = 行业先验表（`industryBetaOf(industry)`，常量表：
  FINANCE 0.85 / TECH·MAGIC 1.30 / MINING·ENERGY 1.15 / AGRICULTURE·CONSTRUCTION·LOGISTICS 0.90 /
  PLAYER 1.00 / 未知 1.00）。方差为 0 或样本不足 → β = β_prior。
- β 钳制：`clamp(β, 0.05, 4.0)`（确定性；防发散/退化）。

**R_f 与 ERP（来自宏观层，确定性）：**

- `R_f(t) = MacroEnvironment.getInterestRate()`（宏观利率 r，向央行政策利率均值回归；即「央行/宏观
  利率」权威）。实现也可选 `getPolicyRate()`——见 §8.2 决议项（默认取 r，与 GBM 漂移/既有估值折现
  同源，变更只需一个 getter 参数位）。
- `ERP(t) = clamp(ERP_BASE + EconomicCycle.riskPremium(phase) + crisisTension·ERP_CRISIS_WEIGHT,
  ERP_MIN, ERP_MAX)`，其中 `ERP_BASE=0.045`（常态股权风险溢价，常量）、`ERP_CRISIS_WEIGHT=0.25`、
  `ERP_MIN=0.02`、`ERP_MAX=0.12`；phase/crisisTension 读 `MacroEnvironment.getCyclePhase()/
  getCrisisTension()`（周期/危机 → 风险溢价抬升 → 估值下压，明斯基语义与既有 getDiscountRate 方向
  一致，但公式独立、不触碰已交付折现率公式）。
- `ke_i(t) = clamp(R_f(t) + β_i(t)·ERP(t), KE_MIN, KE_MAX)`（KE_MIN=0.02、KE_MAX=0.50；另需满足 DCF
  安全分母：实际折现率 = max(ke, growthRate + 0.02)，见 5.2）。

### 5.2 fairValue 接入（CM-5）

- `AICompanyData.updateFairValue(double equityCost)`：DCF 折现率
  `discountRate = max(equityCost, growthRate + DCF_MIN_SPREAD(0.02))`（equityCost>0 时）；
  无参 `updateFairValue()` 保留 = 既有固定折现率（0.08+debtRatio×0.1）→ `cm.capm=false` 逐位一致。
- **调用点（确定性、零 rng）**：季度基本面节拍内
  `aiCompanyManager.updateAll(state, riskAppetite, inflation, id -> capmModel.costOfEquity(id))`
  （updateFundamentals 的 rng 消费次序不变；随后以 ke 重算 fair 为纯函数）；读档 `restoreFundamentals`
  后 MarketEngine 以当期 ke 重算 fair（load 顺序：先 load "capm" 再重算，缺失字段 contains 兜底）。
- **传导（零改动自动生效）**：L2 agent 每 tick 推 company（1675-1677）→ fair 变化同 tick 进入锚；
  中观/研报/web 只读 fair 同源。高 β 资产 ke 高 → DCF 现值低；R_f/ERP 升 → 全部 AI 估值下压、高 β 更敏感。
- 玩家公司：无 AICompanyData/无 fair（MultiAgent 不处理）；CAPM 不引入玩家 fair，仅 β 估算覆盖（若
  资产有收盘历史；供未来/展示，无账户影响）。

### 5.3 收盘采样与状态（确定性 / 持久化）

- 交易日边界（aShare 开启走 calendar.isSessionEnd；关闭走 P0 基线日界，MarketEngine.java:1361/1424）：
  prevClose 落定后，对 state 内每资产追加收盘对 `(indexClose, assetClose)`（指数收盘 = 当日
  `excellent24Index.getIndexValue()`，资产收盘 = 当日 lastPrice/prevClose）；环超上限丢最旧（确定性 FIFO）。
- β 缓存：收盘追加后惰性重算（或标记脏、下次 costOfEquity 读取时重算——读取顺序确定即结果确定）。
- **持久化**：`CapmModel.save()` → `MarketEngine.save()` `tag.put("capm", …)`：
  每资产收益环（ListTag double）、指数收益环、β 缓存与窗口计数；`loadInternal` `contains("capm")` +
  内部 contains 兜底；旧档缺字段 → 空环 + β_prior 自举（读档不失败）。H2 整块 blob，无表变更。
- **冷启动窗口（设计注/决议项 §8.2）**：新存档/读档缺环时，β 以行业先验起步；随交易日推进窗口增长
  至 MIN_BETA_OBS 后开始收缩到 OLS。**不**用既有 DAILY kline 反向回填指数历史（指数无历史、资产日 K
  网格与交易日历不对齐）——避免对齐近似带来的确定性歧义；代价是 β 需 ~20 交易日收敛（探针长程覆盖）。
  （评审若希望首日即有意义 β，可决议：以资产 DAILY klines 与等权重建指数同网格回填——实现面同构，
  仅在 CapmModel 冷启动注入处换数据源。）

### 5.4 CAPM 验收要点（详细探针 §9）

| ID | 判据 |
|---|---|
| CM-4a | OLS 数学：注入 N=40 确定性 x（方差非零）与 y=0.8·x → β̂=0.8±1e-9；β 钳制/收缩/先验回落边界单测 |
| CM-4b | ke 计算：给定 R_f/ERP/β 手算 ≤1e-9；ke 下限/上限/安全分母护栏单测 |
| CM-4c | 敏感度：`R_f +2pct`（真实 setPolicyRate 路径）→ 全部 AI ke 升 fair 降；β_high 资产 fair 弹性 > β_low；`ERP` 加项同向（CapmModel 直驱） |
| CM-4d | 集成：长程真实引擎运行 N 交易日后，模型 β̂ 与探针独立 OLS（同收盘对）|Δ| < 1e-6 |
| CM-5a | fair 折现率：capm 开 = ke（同输入下按新公式手算 ≤1e-6 相对差）；capm 关 = 0.08+debtRatio×0.1 逐位一致 |
| CM-5b | 基本面 AI/研报/中观消费端读数（fair 值）随 CAPM 改变且无需改码；研报评级/目标价方向确定 |

---

## 6. 确定性（SeedableRandom 纪律）

- **本规格不新增任何 SeedableRandom 子流、不新增裸随机**（β/ke/竞价包络/锚/护栏全部确定性纯函数）。
- 不变式：`cm.capm=false` 时季度 updateAll 之后**不执行** ke 重算循环 → AICompanyData rng 消费次序与
  交付基线逐位一致；`cm.clearing=false` 时竞价路径不消费任何随机（本来也无）→ LQ-9/LQ-10 语义保持。
- 既有随机流（GBM-GARCH / agent 决策 / ZI-Hawkes / HFT 泊松 / MM）零触碰；引擎 seed 派生子流表
  （LIQUIDITY_SPEC §7.3）不新增行。
- 复现口径：同 engineSeed + 同输入（玩家操作/央行利率路径视为输入）→ 同成交序列；CAPM 只依赖确定性
  收盘历史（成交的确定性输出）与宏观确定性量 → 全链确定性。

## 7. 持久化 / 不回归核对清单

### 7.1 持久化（H2/NBT，对齐 H2_MIGRATION.md / LIQUIDITY_SPEC §7.2）

- 新状态仅 `CapmModel`（"capm" blob）：资产/指数收益环、β 缓存与窗口、R_f/ERP 快照（可选）；load 用
  `tag.contains("capm")` + 每字段 contains 兜底 → 旧档缺字段以 β_prior + 空环重建，读档不失败。
- 订单簿连续性锚 / invalidFillGuardHits / lastFillTick：运行期瞬态，不落盘（先例 LIQ §4.4/§7.2）。
- `engine_kv.engine_main` 整块 blob，无表变更；关系化镜像表不动。

### 7.2 开关矩阵（因子隔离）

| 开关 | false 语义 |
|---|---|
| `cm.enabled=false` | 全部新代码零执行；与交付 LIQ 基线逐位一致（CM-6） |
| `cm.clearing=false` | auction 包络/锚更新/护栏计数零执行 → 既有 runCallAuction 语义 |
| `cm.capm=false` | β/ke/CAPM 重算零执行；AICompanyData fair=既有固定折现率公式 → 既有季度 fair 逐位一致 |

### 7.3 不回归清单（test 逐项跑既有回归探针）

- **A 股规则**：TradingCalendar 时段/日历、±10% 涨跌停（aShareLimitUp/Down/WithinBand/Clamp）、T+1
  settlement、100 股一手、价格/时间优先、休市拒单全部不变；新成交仍走 attributeTrade；`cm` 关闭与开启
  时玩家挂单均不被自动撤/清/改价（重挂只清 synthetic，先例 LQ-10）。
- **法币 M2 / 黄金 / GBM-GARCH**：`InflationSystem`/`CentralBank`/`Government`（M2）、`GoldPricingSystem`
  （黄金）、`PriceDiscoveryEngine.step`（GBM-GARCH）核心公式零改动（mtime + 标识 grep 审计，见 LIQUIDITY
  VERIFY §3 同款方法）；CAPM 只读 gbmPrice/lastPrice/宏观量。
- **玩家单/账户**：玩家 resting 单、T+1/现金/持仓账本、分红/回购/IPO 语义不受影响。
- **既有验收不回归**：A3-x/R0-x/MP-x/LQ-0..10/SF 既有 PASS 探针在 `cm` 关闭轴与开启轴分别复跑。

### 7.4 文件零改动面（mtime 审计目标）

TradingCalendar / GoldPricingSystem / MacroEnvironment / InflationSystem / CentralBank / SeedableRandom /
OrderFlowModel（若未新增流）/ Excellent24Index（若指数收盘采样在 MarketEngine 侧完成）——如实现选择改
Excellent24Index（如加 indexDailyClose 环），须在 §9 探针登记「额外触碰面」并保持确定性（推荐
MarketEngine 侧采样，指数类零改动）。

---

## 8. 边界、决议项与非目标

### 8.1 非目标（明确不做）

1. 不改 TradingCalendar / A 股时段模型 / 涨跌停幅度。
2. 不做真实玩家市价 UI/网络语义变更（保持 price≤0→现价限价翻译；合成流可市价语义已交付）。
3. 不改 MacroEnvironment.getDiscountRate / getDynamicDrift / getLiquidityMultiplier / GBM-GARCH 公式
   （CAPM 独立派生 R_f/ERP/ke，不修改已交付折现/漂移公式）。
4. 不改 MultiAgentManager 决策公式与锚公式（CAPM 只改 fair 输入）。
5. 不给 MM/合成流引入现金账户；不新增常驻竞价时段（临时兜底语义保持）。
6. 不做 CAPM 之外的资产定价模型（Fama-French/APT 等）；β 只对优经 24 指数回归（单一市场组合代理）。
7. 不改动商品/期货/杠杆/银行结算链路（仅经价格/利率间接耦合）。

### 8.2 决议项/勘误登记（reviewer 锚点）

1. **R_f 取 r 还是 policyRate**：默认 `getInterestRate()`（宏观利率 r，向央行政策利率收敛）。
   若评审要求「央行政策即时传导」，改取 `getPolicyRate()`——单 getter 参数位，验收 CM-4c 的方向性
   判据两种取值下同向（升息 → ke 升 → fair 降），只影响传导快慢。
2. **ERP 构成**：常态 ERP_BASE=0.045 + 周期/危机加项（独立于既有 getDiscountRate 的 0.04+r+…
   公式）。若评审希望 ERP 与既有 0.04 基态/alpha_3=0.02 语义对齐，仅改 CapmModel 常量，不涉及已交付
   公式文件。
3. **β 冷启动**：默认行业先验起步、~20 交易日收敛 OLS（不反向回填 kline 对齐近似）。评审可选「kline
   回填冷启动」方案（实现面同构，见 §5.3 注）——但默认方案确定性更干净，探针长程已覆盖收敛。
4. **债项/杠杆微调**：DCF 折现直接取 ke（高杠杆公司的杠杆已体现在市场收益回归的 β 中），不再叠加
   debtRatio 加项；若评审认为需显式杠杆调整，可加 `ke + debtRatio·LEVERAGE_K`（常量，一个参数位），
   验收 CM-5 同步按最终公式重算期望。
5. **CM-3 自相关阈值**：采用既有 SF PASS 口径（|ρ̂₁|<0.05、LB(5) p>0.01）。初版口径经 t4 实测与 reviewer 决议勘误为：**日内（成交/tick/20-tick bar）= 微观结构观察**（做市价差反弹 +
    连续性锚日内均值回复；登记上界 |ρ̂₁| ≤ 0.5 防过度回复，观察值不断言 PASS/FAIL）；**日收益 = 正式弱式闸门**
    （n ≥ 100 交易日、|ρ̂₁| < 1.96/√n 且 LB(5) p>0.01，另报点估计），详见 §0 CM-3 判据①。t6 以 ≥100 交易日
    长样本重验（结论见 .verify/CAPM_VERIFY.md §5）。
   

### 8.3 实施顺序与依赖（供 scheduler）

```
t2(engine-dev): OrderBook A-1 竞价包络+锚更新、A-2 护栏、MarketEngine clearing 调度/开关  ← CM-1/CM-2
t2(agent-dev):  CapmModel+OlsRegression+AICompanyData ke 接入+MarketEngine 采样/季度重算/持久化 ← CM-4/CM-5
t3(test-engineer): 固定种子长程 + 构造场景 + OLS 单测 + 开关轴回归 → .verify/CAPM_VERIFY.md（蓝图 §9）
t4(reviewer): 评审 CM-0..6 + §8.2 决议
```

---

## 9. 验证探针蓝图（test-engineer 落地参考，交付 .verify/CAPM_VERIFY.md）

沿用 LIQUIDITY_VERIFY 先例（.verify/harness/*.java：javac 编译、真实引擎私有方法反射直调、level=null
null-safe 记账、运行 stdout 静音、归档 .txt）：

- `CapmVerifyHarness.java`：固定 seed 长程（≥2 交易日 × ≥4 资产）：
  - 相邻成交 gap 扫描（CM-1a，含竞价/重挂/开盘首笔）；
  - invalidFillGuardHits / NaN/Inf/≤0/带外成交计数（CM-1c/CM-2a）；
  - 竞价触发与 P\* 落 env∩band、锚更新、确定性双哈希（CM-1b/CM-2b/CM-2c）；
  - 收益自相关 / Ljung–Box（复用或等价实现 autocorr/acfLag/ljungBox 工具）（CM-3a）；
  - |r| 自相关保持显著（CM-3b）；季度 fair 变化同 tick/半衰期 ≤600 tick（CM-3c）；
  - β̂ 与独立 OLS 交叉复算 |Δ|<1e-6（CM-4d）；ke/fair 敏感性（CM-4c/CM-5）。
- `CapmUnitProbe.java`（纯 Java，不依赖引擎世界）：OlsRegression 数学、先验收缩/方差零/样本不足回落、
  ke 手算、AICompanyData updateFairValue(equityCost) 折现替换、capm=false 固定折现逐位一致（CM-4a/4b/5a）。
- 开关轴对比：`cm.enabled=false` 与交付 LIQ 基线语义比对（构造既定场景序列哈希/字段级比对，CM-6）。
- 归档：`capm_verify_fast.txt / capm_verify_full.txt / capm_build_output.txt / capm_compile_output.txt /
  capm_audit_bare_random.txt`（grep 裸随机 0 命中）+ mtime 审计（§7.4 零触碰文件）。
- 复跑口令与 CI 锚定在 .verify/CAPM_VERIFY.md 登记。

---

## 10. 输入 / 来源索引

| 来源 | 位置 | 本规格用途 |
|---|---|---|
| 流动性修复交付 | `LIQUIDITY_SPEC.md`（LQ-0..10）、`.verify/LIQUIDITY_VERIFY.md` | CM-1/2 基座与探针/开关先例 |
| 撮合主循环 | `MarketEngine.java` tick 1336-1876 / runLiqL25B 2074-2143 / liqCircuitRehang 2155-2193 | P-A 接入点与记账 |
| 订单簿连续性 | `OrderBook.java` clampForContinuity 155-161、walkBook 276-342、match 363-456、runCallAuction 648-745、save/load 777-844 | GAP-1/3 修复面 |
| A 股规则 | `TradingCalendar.java`、`A_SHARE_TRADING_SPEC.md`（A3-x/AS-x） | 不回归基线 |
| 估值/基本面 | `ai/AICompanyData.java` updateFairValue 155-183、`ai/AICompanyManager.java` 71-75 | CAPM 接入点 |
| 宏观利率/周期/危机 | `macro/MacroEnvironment.java` r/policyRate/phase/crisis、`crisis/EconomicCycle.java` riskPremium 90-97、`banking/CentralBank.java` | R_f/ERP 确定性来源 |
| 指数/市场组合 | `engine/Excellent24Index.java` | β 回归 x 轴 |
| 收益统计先例 | `validation/StylizedFactsValidator.java` autocorr/acfLag/ljungBox/olsSlope 225-290 | CM-3 度量口径 |
| 用户输入 | 团队 Goal 文本（强制出清/价格连续/EMH/CAPM/ke 折现/确定性/不回归） | §0..§5 公式与验收来源 |
