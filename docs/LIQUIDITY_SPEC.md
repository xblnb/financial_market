# LIQUIDITY_SPEC — 流动性枯竭 / 市场不连续修复需求规格（需求契约，供 engine-dev/agent-dev 实施、test-engineer 验证、reviewer 评审）

> 文档定位：本规格是「流动性枯竭(Liquidity Dry-up)与市场不连续(Market Discontinuity)」修复的**需求/架构契约**。
> 审计对象：`src/main/java/com/example/financialmarket/engine/**` 撮合链路（OrderBook / OrderPipeline /
> MultiAgentManager / OrderFlowModel / MarketEngine L2-L3 / meso 层）。实施方：engine-dev（撮合引擎侧）、
> agent-dev（做市商/订单流侧）；验证方：test-engineer（固定种子长程探针）；评审方：reviewer。
> 目标：**盘口真实连续有成交、成交与盘口同源关联、杜绝流动性枯竭**，同时保持 SeedableRandom 确定性可复现、
> H2/NBT 持久化兼容、不改已交付法币 M2 / 黄金 / GBM-GARCH 核心语义、A 股下单/涨跌停/交易规则不回归。

---

## 0. 目标与全局验收（对应团队 Goal）

| ID | 目标/验收 | 判据（定量可测） | 责任 |
|---|---|---|---|
| LQ-0 | 编译/构建通过 | `./gradlew compileJava` 退出码 0；不改动核心公式文件即无 A 股/法币/黄金回归 | 每期实施 |
| LQ-1 | **纯做市商 MM-Agent（存货 Inventory 模型）** | 每资产存在专职 MM-Agent：恒双边最优报价（P_bid=P_mid−S/2−γ·I(t)、P_ask=P_mid+S/2−γ·I(t)）；MM 侧盘口厚度 ≈ 总股本 5%（比率 = MM 挂单量/floatSharesOf，±20% 容差，见 §5.1）；MM 报价每 tick 刷新且旧报价撤单重挂 | agent-dev t2 + test t4 |
| LQ-2 | **激进市价单 / HFT 到达（泊松 + MAX_WAIT 超时强吃）** | 开市时段任一无成交连续窗口 ≤ MAX_WAIT_TICKS（3s=60tick，允许 ±1tick）；强制市价单触发时击穿吃单边并把 lastPrice 移到盘口另一侧；泊松到达率 λ 可配置、方向确定 | engine-dev + agent-dev t2/t3 + test t4 |
| LQ-3 | **瓦尔拉斯集合竞价兜底** | 构造「限价无法交叉」场景（单边空/全价高于 ask）→ 引擎临时转集合竞价，求 P\* 使 Q_buy(P\*)=Q_sell(P\*) 最大成交量出清；P\* 落在涨跌停带内；成交为单一价格、量能最大、确定性 tie-break；竞价后恢复连续竞价 | engine-dev t3 + test t4 |
| LQ-4 | **动态价差自适应（行为金融）** | Spread(t)=α·e^(β·|ln(Vb/Va)|)（**符号勘误见 §8.2**）：订单簿失衡大→价差扩、势均力敌→价差收敛到 α 底并引成交；价差演化确定性、与订单簿快照同源 | agent-dev t2 + test t4 |
| LQ-5 | **熔断撤单重挂（工程兜底）** | 连续无成交 ≥ CANCEL_REHANG_TICKS（5s=100tick）→ L3 清空该资产**合成**挂单（AI/MM/ZI/Hawkes/meso，§4.6），以 P_gbm 为基准重挂两侧确保成交；**真实玩家挂单不动**（A 股不回归） | engine-dev t3 + test t4 |
| LQ-6 | **盘口连续有成交（无流动性枯竭）** | 固定种子长程（≥1 交易日连续开市）逐资产：无成交连续窗口上界达标（LQ-2）；两侧盘口非空（最优档有量）时间占比 ≥ 90%；无「全限价单互相等待」死锁（无成交窗口由 LQ-2/5 兜底打断） | test t4 |
| LQ-7 | **成交与盘口同源关联** | 每笔成交价取自被动侧盘口档位价（walkBook 档位价 + 有界冲击），成交价与成交时刻的盘口/撮合序列同源（同一 OrderBook 会话对象，非独立伪价）；单日 max-min 跳变与涨跌停带一致 | test t4 |
| LQ-8 | 无流动性枯竭（定义达成） | 开市期零成交时段有界、盘口双侧有档（LQ-6/LQ-7 汇总即 LQ-8）；无成交时段必然被 P1 强制单或 P2 MM 或 P3 竞价打断 | test t4 |
| LQ-9 | **确定性/可复现不回归** | 同 engineSeed 固定 tick 两次运行：成交价×量×撮合序（含新增 MM/强制/竞价成交）逐位一致；每新机制用独立 SeedableRandom 子流（§7.3）；关闭 LIQ 总开关 → 与交付基线逐位一致（因子隔离） | test t4 |
| LQ-10 | **不回归边界** | 关闭 LIQ（liq.enabled=false）A 股/法币/黄金/GBM 语义逐位同基线；开启时 A 股规则（交易日历、±10% 涨跌停、T+1、100 股一手、价格/时间优先）全部保持；法币 M2/黄金核心公式零改动；玩家挂单/账户不被合成流污染 | 每期 + test t4 |

**验收流程（test t4 落地）**：固定 `engineSeed` 长程跑两次比对（LQ-9）；以 `liq.enabled` 为开关轴开/关对比
（LQ-10）；构造无交叉/单边/全价高位场景触发 LQ-1/2/3/5 各机制并断言触发正确（LQ-1/2/3/5）；统计无成交
窗口、双侧档率、成交价档位同源性（LQ-6/7/8）；`./gradlew build` 出包。

---

## 1. 总设计原则（约束所有实现）

1. **纯增量、分层开关、因子隔离**：新增机制集中在撮合/订单流消费端；总开关 `liq.enabled`（默认 true），
   子开关 `liq.mm`、`liq.hft`、`liq.auction`、`liq.spreadAdaptive`、`liq.circuitRehang`（默认随总开关）。
   `liq.enabled=false` 时**任何新代码不消费任何随机源、不新增撮合/挂单/撤单**，行为与交付基线逐位一致
   （沿用 P2/marS 的 F2/MP-5「因子隔离」先例）。
2. **不改核心公式语义**：GBM-GARCH（`PriceDiscoveryEngine.step`）、法币 M2（`MacroEconomy/InflationSystem`）、
   黄金定价（`GoldPricingSystem`）、A 股规则（`TradingCalendar/aShareClamp/T+1/一手/优先序`）**零改动或仅注入只读输入**。
3. **确定性**：全部新随机走 `engineSeed` 派生的 `SeedableRandom` 子流（§7.3），禁裸随机；tick 权威统一用
   `tickCounter`（A 股时间权威，禁用可被 `/time` 改动的 `gameTime` 作为超时/节奏判定——沿 TradingCalendar A3-2 约定）。
4. **持久化兼容**：新增状态进 `MarketEngine.save()/loadInternal()`，`tag.put("liq", …)`；读档 `contains` 兜底，
   旧档缺字段重建、读档不失败（对齐 A3-8/MP-5/R0-5 模式）；H2 走 `engine_kv.engine_main` 整块 blob（无需改表）。
5. **A 股不回归**：真实玩家挂单永不被自动撤/清空；成交现金/T+1 账本归属逻辑（`attributeTrade`）不改；
   涨跌停带钳制、100 股一手、休市拒单在全部新路径同样生效。
6. **成交-盘口同源**：所有成交必须源于订单簿真实档位（walkBook 的被动侧档位价），禁止「引擎另起炉灶
   独立定价后塞进盘口」的伪成交路径；强制/做市/竞价成交同样经 OrderBook 档位（竞价 P\* 是集合快照的档位价）。

---

## 2. 现状审计与根因定位（evidence-based）

### 2.1 撮合主路径现状（代码事实）

```
MarketEngine.tick (MarketEngine.java:1201)
 ├─ calendar.advance / 交易日边界 / prevClose 落定          (1221-1287)
 ├─ priceEngine.step(): GBM-GARCH + 宏观 + lastPrice 耦合   (1355; PriceDiscoveryEngine.java:346-416)
 ├─ tradingOpen 门控（休市跳过 L2/L2.5/L3）                 (1448)
 ├─ [L2] 每资产 agentManager.stepAgents → 限价单            (1464-1506)
 │      agent 决策全为 LIMIT（value/retail/momentum/MM 报价），MM 对称报价 ≈mid±spread  (MultiAgentManager.java:368-763)
 ├─ [L2.5] OrderFlowModel ZI + Hawkes 注入                  (1508-1555)
 │      ZI 限价 ±(1..3)×0.01 贴 mid；ZI「市价」→ pipeline price=0.0 (OrderPipeline.java:107-115)
 ├─ [L3] book.matchWithSlippage(SLIPPAGE_FACTOR)            (1557-1603; OrderBook.java:333-387)
 │      while(bestBid >= bestAsk) → aggressor 判定(placedTick→量→失衡→买) → walkBook 吃被动侧档位
 └─ attributeTrade / tradeHistory / volumeTracker / lastPrice 更新  (1582-1602)
```

### 2.2 根因链（每条带证据）

**R-1（结构性）撮合只在「限价单自行交叉」时发生，引擎内没有真正意义的「到达即市价执行」路径。**
- `OrderBook.matchWithSlippage` 的撮合条件是**resting 簿**两侧最优档交叉 `bestBid >= bestAsk`
  （`OrderBook.java:345-348`），随后从**两侧 resting 队列**各取队首做 aggressor 判定
  （`OrderBook.java:358-367`）再 walk 被动侧。它不会把「本 tick 新到、尚未入簿的订单」当作必然主动方立即
  吃单——新单先 `placeOrder` 进簿（`OrderBook.java:140-149`）再在同一调用里按 resting 交叉撮合。
- **市价单(MARKET)在 pipeline 中被赋予 `price=0.0`**（`OrderPipeline.java:107-115`，`urgency>=1.0 → isMarket`），
  再以 `limitPrice=0.0` 作为 TreeMap key 挂入簿。买侧 bids 是**降序** TreeMap（`OrderBook.java:12`），
  `0.0` 落在**最末档**——只要簿内存在任何正价买单，`bids.firstKey()` 就不是这单；当它成为唯一买档时
  `bestBid(0) < bestAsk` 直接 break。**结论：市价买单（含 ZI/HFT「市价」分支）在当前实现中不作为吃单方
  执行，几乎永不被成交**（仅理论上的极端情形——卖方巨额单吃穿整条买档到达 0.0 档——才可能以钳制价被动
  成交），到龄即被 `purgeExpired`（100 tick）清除。卖侧 asks 是**升序** TreeMap，`0.0` 恰好成为**最优卖档**，
  而 walkBook 的限价约束 `aggLimit>0 && pLevel>aggLimit`（`OrderBook.java:270-271`）对 resting 0.0 档不设防：
  任何买侧 aggressor 吃到该档时 `fillPrice = aShareClamp(0+impact,…)`（`OrderBook.java:282`）≈ 跌停带端点 →
  **以盘口不存在的价格成交 → 成交与盘口脱节/市场不连续 + 方向性失真**。→ 这是「无激进订单到达」的直接代码根因。
- 玩家「市价」下单在网络层被翻译为 **lastPrice 限价单**（`PlaceOrderPacket.java:49-50`；HTTP /api/order 同），
  玩家侧同样不存在真市价执行。→ 撮合双方几乎全部是限价单 → 「全限价单互相等待」。

**R-2（行为）盘口两侧报价对称、被动、锚定近端，极少制造交叉。**
- MM（`MultiAgentManager.marketMakerQuotes`，719-763）按 `mid=lastPrice` 对称挂 `mid×(1∓offset)` 多档，
  offset 恒正、买低于卖 → **MM 自身从不制造交叉**；其唯一作用是铺深度。
- 散户/价值/动量报价全部锚 `anchor≈fair`（468-721）且偏移量级 ≤2-5%，当价格在 fair 附近 ±15% 区间时
  决策退化（散户 `rng.nextDouble()<buyRatio`，`MultiAgentManager.java:270/617`），方向噪声化。
- ZI 限价贴 `mid∓0.01..0.03`（OrderFlowModel.java:156-160）→ 同样只收窄价差、不主动吃单。
- 结果：**价差在「最优买 < 最优卖」时（正常盘）无任何成交**，成交只在 fair 偏离大到让某个限价单越过
  对手价时偶发 → 流动性枯竭时段 = 「价格相对共识稳定」的时段，与观察一致。

**R-3（错配）基准价/盘口价/成交价三锚脱节（承接 MODEL_AUDIT §2.1/G-01 的结构问题在撮合层的具体表现）。**
- GBM 每 tick 推进 `gbm.price` 并把 `state.lastPrice` 向 gbm 拉近（`FUNDAMENTAL_COUPLING=0.8`，
  `PriceDiscoveryEngine.java:403-414`）——即**无成交也移动 lastPrice**；
- 成交价由撮合档位 + 冲击 + 连续钳制决定（`OrderBook.walkBook` 253-312），再经 `updateDailySnapshot`
  回写 `state.lastPrice`（`MarketEngine.java:1164-1185`）；
- MM/ZI 报价锚 `lastPrice`（可能是 GBM 耦合值），玩家限价锚 fair → 当无成交且 GBM 继续漂移时，
  盘口被拖到「成交价从未出现过的价位」，下一次成交跳跃 → **成交与盘口脱节 / 市场不连续**。

**R-4（缺失）无「无成交超时」兜底：死锁可无限持续。**
- 熔断检测只看「日内涨跌幅超阈值」（`MarketEngine.java:1631-1644`），与无成交时长无关；全代码库无
  `MAX_WAIT / lastFillTick / noTrade` 计时（grep 空）。`purgeExpired` 只按龄清理，不产生成交。
- 冷启动 `seedColdStartBook`（MarketEngine.java:536-562）只在 init() 铺双侧 5 档，之后若 AI/MM 决策
  在 fair 死区全部停手且无 ZI 市价卖 → 簿上残余到龄清理 → **盘口单边/薄 + 无成交并存**。

### 2.3 对照：现有做市商 / Hawkes / 中观流为何不足以解决

| 现有机制 | 证据 | 为何不足以解决 R-1..R-4 |
|---|---|---|
| MARKET_MAKER 类型 agent（20-40 个/资产中 ~10%，MultiAgentManager.java:312-332） | 0.95 概率激活、对称多档报价 719-763 | 仅铺被动深度；对称报价永不交叉；无存货概念（`AgentState` 无 I(t)、报价不含 γ·I）；价格偏离 fair ±50% 时直接停供（`deviation<=-0.5||>=0.5 return`，727）；**不承担「保证成交/市场连续」义务** → R-1/R-2/R-4 均不解决 |
| Hawkes 自激励到达（OrderFlowModel.java:162-190） | 强度 λ0=0.015+K·Σe^(−βΔt)，限价跟随性订单（1542-1553） | 只是「把订单到达时间聚集化」，产出仍是贴 mid 的**限价**单，且强度量级 0.015~0.045 × DT(20/24000) → 每 tick 触发概率极小（<1e-3），**既不激进也不密集** → R-1/R-2 不解决 |
| 中观 meso 层（MesoLayerManager / SectorFundAgent / InstitutionalAgent） | 每 20 tick 低频；价格 = px×(1∓0.001..0.004)（SectorFundAgent.java:159-160；InstitutionalAgent.java:216-217） | 目标是对组合权重做**再平衡的被动偏移单**，量级 5000-50000 股但频率极低（每宏观步 1 笔/资产方向）；不是做市、不守双边、不兜无成交 → R-1/R-4 不解决 |
| OrderBook 既有滑点/深度冲击（P2-3） | walkBook + MarketImpact + MarS Δ=ασ√(Q/V) | 只改善**成交发生后的价格路径**（冲击/连续性），不创造成交机会 → R-1（无交叉即无成交）不解决 |
| ZI 15%「市价」流（OrderFlowModel:101-112, ziLimitVsMarketProb=0.85） | pipeline 市价 price=0.0 → 见 R-1 | 买侧市价 inert、卖侧 0 价失真 → 反而贡献 R-1 的不对称 |

> **一句话根因**：撮合器缺少「到达即主动吃单」的可市价执行通道（R-1）、缺少永远不撤的双边义务报价
> （R-2/现有 MM 不是义务 MM）、缺少无成交超时/竞价兜底（R-4），且三锚脱节放大跳空（R-3）。

---

## 3. 总体架构与改动布局

```
                 ┌──────────────────────────────────────────────┐
                 │  MarketEngine.tick  (新增 L2.5b / L3 兜底)    │
  L1 宏观/GBM ──▶│  [L2] agent 限价单                            │
                 │  [L2.5] ZI/Hawkes/MM-Agent 订单流（含市价通道）│
                 │  [L2.5b] HFT 泊松到达 + MAX_WAIT 检查 ★P1     │
                 │  [L3] matchWithSlippage（新增 executeMarketable│
                 │        + auction 兜底 + circuit-rehang 兜底） │
                 └───────────────┬──────────────────────────────┘
                                 ▼
              OrderBook：walkBook / executeMarketable / runCallAuction
              OrderPipeline：marketable 出口、Impact 修正、band 校验
              MultiAgentManager + MM-Agent(新)：存货 I(t) + 动态价差 ★P2
              OrderFlowModel：λ 泊松 HFT 到达（source=hft）     ★P1/P2
```

新增/改动文件总表（相对仓库根）：

| 文件 | 角色 | 改动内容 |
|---|---|---|
| `engine/OrderBook.java` | engine-dev | `executeMarketableOrder`、`runCallAuction`、`cancelAllSynthetic`（§4.6）、best 侧深度/失衡只读（已有 getSnapshot/getMidPrice/netImbalance）；`matchWithSlippage` 尾部挂兜底钩子；market 单不再以 0.0 入簿 |
| `engine/MarketEngine.java` | engine-dev | 每资产 `lastFillTick` 计时、`MAX_WAIT/CANCEL_REHANG` 触发、竞价/重挂调度（§4.6 `rehangAssetBook`）、LIQ 开关与 save/load("liq")、每 tick 注入 MM 上下文 |
| `engine/microstructure/OrderPipeline.java` | engine-dev | `Context` 增加 LIQ 位、`source` 扩展 `"mm"/"hft"/"forced"/"auction"`；marketable 出口（非 resting 0.0） |
| `engine/microstructure/OrderFlowModel.java` | agent-dev | 新增 HFT 泊松到达（λ 参数、方向确定、source="hft"）；MM-Agent 上下文消费口 |
| `engine/agent/MultiAgentManager.java` | agent-dev | MM 决策改为**义务式双边 + 存货 I(t) 报价**（新方法 `obligatedMarketMakerQuotes`），保留旧路径供开关隔离 |
| `engine/agent/MarketMakerAgent.java`（新） | agent-dev | 纯做市商状态机：I(t)/成本/目标厚度/报价层数/γ；每 tick 出 OrderFlow（synthetic） |
| `engine/liquidity/LiquidityState.java`（新） | engine-dev | 每资产 watchdog：lastFillTick/lastPrice 锚/上次竞价/上次重挂；save/load（随 "liq" blob） |
| `engine/calibration/CalibrationParams.java` | engine-dev | 新增先验：`hftLambda`、`mmSpreadAlpha/Beta`、`mmGamma`、`mmDepthFraction(0.05)`、`maxWaitTicks(60)`、`cancelRehangTicks(100)`（带默认与钳制） |
| `LIQUIDITY_SPEC.md` | 本文件 | 需求契约（评审锚） |

> 说明：`engine/liquidity/` 新包随 save() 序列化；所有新类尽量「纯 Java + NBT」，沿用 VolumeTracker/Meso 模式。

---

## 4. P1 — L3 MAX_WAIT=3s 强制市价单 + 可市价执行通道（最高优先级）

### 4.1 需求

1. **修复市价单通道（R-1 结构性缺陷，前置）**：`Order` 的 MARKET_BUY/MARKET_SELL 到达时**立即作为主动方
   执行**（walk 对手侧 resting 档），不再以 price=0.0 挂入簿末档/簿首档。
   - 实现：`OrderBook.executeMarketableOrder(Order aggressor)`：以 aggressor 方向取对手侧 best→walk 至
     吃满或被动侧尽；残余量转**已成交价位限价单**挂回原侧（A 股可排队语义）或直接撤残余（按
     `liq.convertResidualToLimit` 开关，默认 true=挂回已成交价）。
   - 兼容：玩家网络层市价翻译（PlaceOrderPacket:49-50）**不改**；合成流（agent/zi/hft/forced/meso）经
     pipeline 出的 MARKET 单走新通道。`matchWithSlippage` 中 resting 0.0 价档由 purge 顺带清（现有行为）；
     新通道开启时 pipeline **不再生成 0.0 resting**（`isMarket → executeMarketable`）。
2. **无成交超时强吃（MAX_WAIT=3s）**：每资产记录 `lastFillTick`（撮合/成交更新，交易日内有效）；
   L2.5b 检查：开市 && 簿非空 && `tickCounter − lastFillTick ≥ MAX_WAIT_TICKS(60)` && 有被动侧可吃
   （best ask 有卖档或 best bid 有买档，且方向单不会被涨跌停带整单拒绝）→ 生成 **强制市价单**（synthetic，
   source="forced"），方向按确定性规则（§4.3），经 `executeMarketableOrder` 吃单边**击穿未成交限价单**。
3. **泊松激进到达（HFT 基座）**：`OrderFlowModel` 新增独立 λ 流（`hftLambda`，先验 0.02/tick/资产），
   每 tick `P=1−e^{−λ}` 判定一次 HFT 到达，到达则生成一笔**市价吃单**（source="hft"，量级 ~depthAtBest×k）。
   该流是让「平时也有激进单到达」的底座，MAX_WAIT 是兜底；两者独立子流、独立开关（hft 关、maxwait 仍开）。

### 4.2 改动文件 / 方法 / 数据流

| 层 | 文件:方法 | 改动 |
|---|---|---|
| OrderBook | `executeMarketableOrder(Order)` 新；`placeOrder` 分支（MARKET→execute 不入簿） | 复用 `walkBook(aggressor,…)`；残余转限价单 `book.placeOrder(limitAtFill)`；返回 trades（同签名 List\<Trade\>） |
| OrderBook | `matchWithSlippage` | 调用侧不变；仅当簿内出现 resting 0.0 老档时按旧语义清理（兼容旧档读入的 0.0 单） |
| MarketEngine | `tick` L2.5b 段（在 1508 块之后、1557 撮合块之前） | 每资产：① HFT 到达（orderFlowModel.hftShouldArrive(assetId, rng)）；② MAX_WAIT 判定→构造强制 Order→`executeMarketableOrder`；③ 把成交并入与 L3 相同记账（attributeTrade/volumeTracker/lastFillTick 更新） |
| MarketEngine | 字段 | `Map<String,Long> lastFillTick`；`liqEnabled` + 子开关；常量改读 `CalibrationParams` 先验 |
| OrderPipeline | `Context` + `toOrder` | 语义保持；`isMarket` 分支改为「不在簿内等撮合、直接返回 marketable intent」，由调用方（MarketEngine）转 `executeMarketableOrder`（避免 pipeline 依赖 OrderBook） |
| OrderFlowModel | `hftShouldArrive/hftDirection/hftLot` | 独立 `hftRng=derive("OrderFlow.HFT")`；方向确定：见 §4.3 |
| CalibrationParams | 新增先验字段 | `hftLambda=0.02`、`maxWaitTicks=60`、`forcedLotScale=2.0`（×depthAtBest 上限）、`convertResidualToLimit=true` |

### 4.3 方向与量（确定性）

- 强制单方向规则（确定性优先级）：① 簿单边（仅 asks → 买 / 仅 bids → 卖，向空侧补位）；② 双侧有量但
  价差宽 → `sign(gbm.price−lastPrice)` 向 gbm 基准收敛；③ 前两者无效（失衡为 0 且价已贴基准）→ 按
  `mix64(assetId ^ tickCounter ^ "forced")` 取号（固定种子函数，无状态随机）。
  > 设计注：**强制单是为了「制造连续成交 + 恢复双侧」而非单边砸盘**：当盘口只有卖压（bids 空）时强制买
  > 吃 ask 侧（买入把价推回 bid 可挂区）→ 之后 MM 能双侧重挂；双侧有量但价差宽时向基准收敛，方向
  > 确定性且不引入长期单边漂移。量 = min(吃穿侧全部被动深度, forcedLotScale×depthAtBest) 且 ≥ 一手。
- HFT 方向：与 ZI 不同，HFT 作为「激进的短期动量/逆势混合」：`direction = netImbalance>0 → 买（跟随）`；
  用独立 `hftRng` 决定是否逆势（10%），其余跟随订单簿失衡（与 chartist 信号一致，P2 §2.3 语义复用）。
- 全部走 `aShareClamp`/`limitBand`（execute 内部复用 walkBook 的 fillPrice 钳制）；数量 A 股一手化
  （买 100 整数倍；卖可零股但强制单也一手化以统一）。

### 4.4 确定性 / 持久化

- 新随机仅经 `engineRng.derive("liq/hft/"+assetId)`、`derive("liq/forced/"+assetId)` 两个子流实例；
  开关关闭不消费任何随机 → 基线逐位一致。
- 持久化：`lastFillTick` 不落盘（读档首 tick 按 `tickCounter` 起算，首个 MAX_WAIT 触发即为冷启动
  播种兜底）；HFT rng 状态随 OrderFlowModel.save() 既有字段旁加 `hftRngState/hftSpare`（contains 兜底）。

### 4.5 P1 自身验收

| ID | 判据 |
|---|---|
| LQ-2a | 构造「最优买<最优卖、无任何新限价越过」的簿，推进 61 tick → 出现 source="forced" 成交且 lastPrice 落入吃单侧档位带 |
| LQ-2b | MARKET_BUY 经 execute 立即吃 ask 档（同 tick 成交），簿中无 0.0 买档残留；关闭 liq → 0.0 老语义保留（读旧档兼容） |
| LQ-2c | HFT 子流关闭时随机零消费（grep 无新增裸随机；同种子两次哈希一致） |
| LQ-9/10 | 同种子一致；关闭 liq 后与交付基线逐位一致 |

### 4.6 LQ-5 熔断撤单重挂（工程兜底，liq.circuitRehang；P1 的 L3 侧兜底，与 P2 MM 每 tick 撤旧重挂区分）

**触发**：开市 && `tickCounter − lastFillTick ≥ CANCEL_REHANG_TICKS(100)`（5s=100tick，先验 > MAX_WAIT_TICKS=60，
故正常由 LQ-2 强制单先打断；本机制兜「强制单因涨跌停带/无被动侧仍无法成交」的极端情况：连续竞价与强制吃单
双失效）。

**动作（engine-dev，L3 段尾）**：
1. **清空该资产全部 synthetic resting 挂单**（MM/agent/ZI/Hawkes/meso：`order.isSynthetic()==true`），
   **真实玩家挂单不动**（A 股不回归：玩家委托 = 契约，引擎不得代撤，LQ-10）；
2. **以 P_gbm 为基准重挂双侧**：`P_bid = P_gbm − δ`、`P_ask = P_gbm + δ`（δ 取动态价差 S(t)/2 或默认
   0.01×P_gbm，确定性），两侧各挂 `rehangLot`（= 一手×档数，先验 100×3=300 股，合成流）；
3. **重挂后立即 execute 一笔强制单**（方向同 §4.3 确定性规则），把重挂单成交掉，恢复 lastFillTick →
   连续竞价重启；
4. 若 P_gbm 在涨跌停带外（极端恐慌）→ 带内端点重挂（aShareClamp），仍不成交则等下一交易日（T+1 边界
   reset 语义），不制造越带成交。

**确定性 / 持久化**：无新随机（P_gbm 为既有 GBM 基准，δ 为确定性函数）；`lastRehangTick` 进
`LiquidityState`（审计/重复触发抑制：相邻两次重挂间隔 ≥ CANCEL_REHANG_TICKS）。

**文件**：`MarketEngine` L3 兜底段（新增 `rehangAssetBook(assetId)` 私有方法）；`OrderBook` 增
`List<Order> cancelAllSynthetic()`（按 isSynthetic 清两侧，返回被撤单数，确定性无随机）；CalibrationParams
增 `cancelRehangTicks=100`。**验收（LQ-5）**：构造「强制单因单边空/带外无法成交 → 连 100 tick 无成交」场景
→ 合成挂单被清空、玩家挂单完好、以 P_gbm 重挂并出现成交；同种子两次一致；`liq.circuitRehang=false` 不触发。

---

## 5. P2 — 纯做市商 MM-Agent（存货 Inventory 模型，厚度≈总股本 5%）+ 动态价差

### 5.1 MM-Agent 存货模型（LQ-1）

- **新类 `MarketMakerAgent`（每资产 1 个专职，synthetic）**：与既有 `MARKET_MAKER` agent 并存但语义升级为
  **义务式做市**（既有 agent 保留在 `liq.mm=false` 路径作因子隔离）。
- 状态：`inventory I(t)`（signed 股：+多 −空）、`avgCost`、`lastQuoteBid/Ask/…`、`rng`（`derive("liq/mm/"+assetId)`）。
- **报价公式（用户存货模型）**：
  ```
  P_bid(t) = P_mid − S(t)/2 − γ·I(t)
  P_ask(t) = P_mid + S(t)/2 − γ·I(t)
  ```
  - `P_mid` = 簿 mid（`book.getMidPrice()`；无双边回落 lastPrice，同 L2.5 `midPriceOf`）；
  - `S(t)` = 动态价差（§5.4 LQ-4 公式），下限 tick（0.01）、上限受 CalibrationParams `mmSpreadMax` 钳制；
  - `γ`（`mmGamma`，先验 0.001/tick/股单位刻度）把存货风险压回 0：I>0（多）→ 双侧报价整体下移促卖、
    I<0（空）→ 上移促买；γ·I 每 tick 单调更新（确定性）。
- **厚度 ≈ 总股本 5%**：目标单侧厚度 `targetDepth(assetId) = max(一手, round(mmDepthFraction(0.05) × floatSharesOf(assetId)))`
  与 MM 报价/Order 数量**同一股单位**；`floatSharesOf` 为**唯一换算缝**：AI 公司 =
  `getTotalShares()×10000`（万股→股，AICompanyData.java:26,68 注释口径；若后续确认 totalShares 即股则
  去 ×10000——换算缝集中一处、测试固定）、玩家公司 = `CompanyData.getFloatShares()`；引擎提供只读
  `MarketEngine.floatSharesOf(String assetId)` 供全链引用。**验收只比「MM 挂单量 / floatSharesOf」比率**
  （单位在比率中抵消），故换算选择不影响 LQ-1，只影响绝对量级与 `mmDepthCapShares`（先验默认
  `max(targetDepth, 5_000_000)` 股/侧，防绝对量爆炸）。
- **铺垫子节奏**：每 tick 挂单预算 `perTickQuoteBudget = targetDepth / MM_FILL_TICKS(60)`（60 tick ≈ 3s 铺满），
  按 3-5 档分布、档距随档位递增、量随档距衰减，**至 targetDepth 后维持**（顶格预算=补足差额）；top-of-book
  最优档从首 tick 即挂出（不等到铺满），保证盘口任何时刻有真实双边；累计达 targetDepth 前的部分成交量由
  OrderBook 档位自然消耗，MM 补挂（撤旧重挂见下）。
- **义务性**：`liq.mm=true` 且开市、涨跌停未锁死、熔断未触发时 **每 tick 必双边报价**（不受既有
  `deviation≥±0.5 停供`、`riskAppetite 门控`影响——那是「利润型 MM」逻辑；义务 MM 只受带内可挂约束）；
  锁死（best bid=涨停且无卖 / best ask=跌停且无买）时允许单边为空（排队语义，A3-3）。
- **撤单重挂**：MM 每 tick 报价前先撤**本资产本 MM 旧报价**（OrderBook 需按「MM 会话 tag」定位：给 MM 报价
  `setSynthetic(true)` + `playerId=null` + 新字段 `Order.mmTag`（或复用 fixed UUID，读档重建语义同
  OrderBook.java:495-518 的 restoreId 模式）→ `cancelByTag`）；撤后按新 P_mid/S/I 重挂 → 报价永驻最优双侧。
- 存货更新：MM 报价被成交时经 Trade 归属（buyer/seller 为 null 合成）→ MM-Agent 记账入口
  `MarketEngine.attributeTrade` 内加钩子：若该笔含 MM 侧 → `agentManager/MM-agent.onFill(assetId, ±qty, price)`
  更新 I(t)/avgCost；MM 无真实现金（合成设施），由 `mmInventoryCap`（先验：±5×targetDepth）钳 I(t) 防失控。

### 5.2 改动文件 / 数据流

| 文件 | 改动 |
|---|---|
| `engine/agent/MarketMakerAgent.java`（新） | 状态 + `quoteFlows(assetId, mid, spread, book, tick)` → List\<OrderFlow\>；`onFill(...)`；`save/load` |
| `engine/agent/MultiAgentManager.java` | 持有每资产 `MarketMakerAgent`；`stepAgents` 末尾或独立方法生成 MM 流并入 `agentOrders`；保留旧 `marketMakerQuotes` 供 `liq.mm=false` |
| `MarketEngine` | 每资产在 L2 agent 块前调用 MM 报价（或随 agentOrders 同批）；`attributeTrade` 加 MM onFill 钩子（不改变 A 股玩家记账逻辑） |
| `OrderBook` | `cancelByTag(String mmTag)`；`Order.mmTag`（new 字段，save/load contains 兜底） |
| `CalibrationParams` | `mmSpreadAlpha/Beta/Gamma/DepthFraction(0.05)/InventoryCap/QuoteTiers(3..5)` |

### 5.3 确定性 / 持久化

- MM 独立子流 `derive("liq/mm/"+assetId)`；报价参数全部确定性（无新裸随机，档内 jitter 也走该子流）。
- 持久化："liq" blob → 每资产 `mmInventory/mmAvgCost/mmRngState`（synthetic，不参与真实账户）；
  读档旧档缺字段 → `initialize` 空仓重建（contains 兜底）。

### 5.4 LQ-4 动态价差自适应（P2 附属，行为金融）

- 公式：`S(t) = α · e^( β·|ln(Vb/Va)| )`，`Vb/Va` = 最优档买/卖量（`OrderBook.getNetImbalance` 的量基，
  缺侧时 Vb/Va 用 1 侧）；失衡大 → |ln| 大 → 价差扩（风控）；势均力敌 |ln|→0 → 价差收敛到 α 底（引成交）。
  - **§8.2 勘误**：用户原式写为 `α·e^(−β·|ln(Vb/Va)|)`（负号），与文字「失衡大价差扩」矛盾；以文字意图为准，
    本规格采用正指数 `+β`，并在 §8.2 记录勘误（reviewer 锚点）。
- 消费端：MM-Agent 的 S(t)、OrderBook walkBook 冲击钳制上限的 `ctx.spread`（buildPipelineContext 已注入
  spread，1834）、影响修正 cap（OrderPipeline MAX_IMPACT_SPREAD_MULT）三处改读同一 `S(t)` 源；
  由 MarketEngine 每 tick 计算后注入（确定性、无随机）。
- α/β 先验（CalibrationParams）：α=0.004（相对），β=0.6；S 夹到 [0.01, 0.05×mid]。
- 验收：人为放大 Vb/Va → 下 tick 价差按公式单调扩/收敛；同种子两次一致；`liq.spreadAdaptive=false` →
  回落既有 `ctx.spread=快照价差`（基线）。

---

## 6. P3 — 瓦尔拉斯集合竞价兜底（限价无法交叉时临时转竞价，模拟 A 股开盘/收盘竞价）

### 6.1 触发与语义

- **触发条件（引擎确定性判定，engine-dev）**：开市 && 簿双侧非空 && `tickCounter−lastFillTick ≥
  CALL_AUCTION_WAIT_TICKS(120)`（2×MAX_WAIT，先验）&& 双侧最优价带内无交叉可能（bestBid < bestAsk 且
  `spread > 2×tick` 或单边量为 0）→ 进入**单资产临时集合竞价**（不入全局日历，不改 A 股时段模型）。
- **出清算法（`OrderBook.runCallAuction`）**：
  1. 冻结快照：把 bids/asks 各自按价聚合（每价档 = 该档总剩余量，时间优先在档内保持）；
  2. 构建 Q_buy(P)=Σ{bid价≥P 量}、Q_sell(P)=Σ{ask价≤P 量}；
  3. 求 **P\***：使 `volume(P)=min(Q_buy(P), Q_sell(P))` 最大；多解 tie-break（确定性，固定次序）：
     ① 与 `prevClose`（无则 gbmPrice）最接近；② 仍并列取低价；③ 再并列取 buy side 压（A 股开盘竞价惯例：
     相同最大成交量取使未成交量最小的区间，此处收敛为 ①②后仍并列 → 取该区间中价）；
  4. P\* 须落在 `[limitDown, limitUp]`（涨跌停带），越带 → 不竞价、回落 P1 强制单（带内吃单）路径；
  5. **出清**：所有 bid≥P\* 与 ask≤P\* 以 P\* 成交（各自剩余量 min），档内时间优先；成交价=P\*（单一价格，
     **天然同源**——P\* 由盘口档位聚合而来）；未成交单按 A 股竞价惯例：可留在簿中（原限价）继续连续竞价。
  6. 输出 trades → 走 MarketEngine 统一记账（attributeTrade 等），更新 lastFillTick。
- 说明：这是**临时兜底撮合模式**（模拟 A 股开盘/收盘竞价的价格发现思想），不是常驻日历阶段；不改
  TradingCalendar（A3-2 时段模型不动）。

### 6.2 文件 / 验收

| 文件 | 改动 |
|---|---|
| `OrderBook.runCallAuction()` 新 | 聚合/求 P\*/出清/tie-break；只读 `getLimitUp/Down`（已有 setLimitBand 字段） |
| `MarketEngine` L3 兜底 | `matchWithSlippage` 空成交且触发条件成立 → `runCallAuction`；输出并记账 |
| CalibrationParams | `callAuctionWaitTicks=120` |
| 验收 LQ-3 | 构造买档最高 4.99 / 卖档最低 5.01、双方量已知 → 竞价 P\*=5.00 出清最大量、单一价、无 0.0 假单；带外不触发；竞价后连续竞价恢复；确定性两次一致 |

---

## 7. 确定性 / 持久化 / 兼容性（继承 P0/P1/P2/A 股各节，LIQ 增量）

### 7.1 开关矩阵与基线

- `liq.enabled=false`（或子开关全关）→ 新增代码路径零执行：无 HFT/MM/竞价/重挂/撤单/新记账；
  OrderFlowModel 消费随机次序与交付基线一致（MP-1-7/F2 同款因子隔离）。
- 子开关默认：mm=true、hft=true、auction=true、spreadAdaptive=true、circuitRehang=true（随总开关）；
  `OrderFlowModel` 既有 ZI/Hawkes 开关语义不变。

### 7.2 H2 / NBT 持久化（对齐 H2_MIGRATION.md §6.3 + A3-8）

- 新状态全部入 `MarketEngine.save()` 的 `tag.put("liq", liqState.save())`（新增 `engine/liquidity/LiquidityState`）：
  `lastFillTick`(可不落盘，读档起算，见 §4.4)、每资产 `mmInventory/mmAvgCost/mmRngState/mmTagSeq`、
  `hftRngState/hftSpare`、`lastAuctionTick/lastRehangTick`（审计用）；
  `loadInternal` 用 `if (tag.contains("liq")) …` + 内部 `contains` 兜底重建，**旧档读档不失败**。
- `engine_kv.engine_main` 整块 blob 无需改表；关系化镜像表不动（MM 为合成流不入 ai_company/company/asset 镜像）。
- `Order` 新增可空 `mmTag`（String）字段：save/load contains 兜底（不写即空 → 旧档兼容）。
  **注意**：`OrderBook.saveSide/loadSide`（OrderBook.java:465-518）须同步序列化/恢复 `mmTag`，否则读档后
  MM 撤旧重挂（§5.1）找不到本 MM 的旧报价；恢复的 MM 报价继续 `setSynthetic(true)`（既有 restoreId 模式）。
  兜底：load 后首个 LIQ tick 若 MM 报价 tag 全部为空（极旧档），按「整侧合成报价按龄清理后重挂」处理。

### 7.3 SeedableRandom 子流分配表（新随机全部走这里）

| 机制 | 子流 seed（均自 engineSeed 派生，实例化独立 SeedableRandom） | 持久化 |
|---|---|---|
| HFT 泊松到达/方向/量 | `derive("liq/hft/"+assetId)` | hftRngState/hftSpare |
| MAX_WAIT 强制单方向/量 | `derive("liq/forced/"+assetId)`（纯函数方向优先，仅 tie-break 用） | 不落盘 |
| MM-Agent 档内微扰/层数 | `derive("liq/mm/"+assetId)` | mmRngState |
| 竞价 tie-break | 无随机（纯确定性规则） | — |

### 7.4 不回归核对清单（LQ-10 分解）

- A 股：`aShareEnabled` 开关语义、TradingCalendar、`aShareLimitUp/Down/WithinBand/Clamp`、T+1 settlement、
  100 股一手校验（`submitOrder` 1967-2050）不改；新增成交仍走 `attributeTrade` → 玩家侧现金/持仓/T+1 账本
  与现在完全一致；玩家 resting 单永不被 MM 撤单/竞价清空（竞价只按 P\* 出清、未成交保留，重挂只清 synthetic）。
- 法币 M2 / 黄金：本规格零接触（仅 engine tick 顺序外围新增段，不触碰 fiat/gold 方法）。
- GBM-GARCH：`PriceDiscoveryEngine.step` 不改；MM 报价/强制单只读 `gbm.price/lastPrice`。
- P0/P1/P2/marS 既有验收（A3-x、R0-x、MP-x）不得因本规格回归（test t4 跑既有回归探针）。

---

## 8. 边界、勘误与非目标（防范围蔓延）

### 8.1 非目标（明确不做）
1. 不改 TradingCalendar（不引入常驻开盘/收盘竞价时段；A 股日历已交付语义冻结）。
2. 不做真实玩家市价单 UI/网络语义变更（保持 price<=0→lastPrice 限价翻译，R-1 修复只作用于合成流与
   强制/HFT 单——玩家若需市价，可后续单独立项）。
3. 不改 MultiAgentManager 四类既有决策公式核心（价值/散户/动量沿用），只新增 MM 义务路径 + 开关隔离。
4. 不为 MM 引入真实现金账户/玩家可做市（纯 synthetic 流动性设施）。
5. 不动 Excellent24Index / 商品 / 期货 / 杠杆 / 银行（仅间接经 lastPrice 变化影响，属既有耦合）。

### 8.2 勘误登记（reviewer 评审锚点）
- **§5.4/LQ-4 公式符号**：用户原式 `Spread(t)=α·e^(−β·|ln(Vb/Va)|)` 与文字意图（失衡大→扩、均衡→收窄）
  相反。规格采用正指数 `S(t)=α·e^(+β·|ln(Vb/Va)|)`（失衡单调扩、均衡收敛 α）。若评审/用户确认保留负号
  （即失衡时价差**收敛**诱使 HFT 进场套利、均衡时价差回归基准 α 较大），则仅改符号并同步 §5.4 文字——二者
  实现面同构（一个参数位），验收 LQ-4 须按最终符号写期望方向。
- 股/万股换算：AI `totalShares` 注释为万股（AICompanyData.java:26,68），而订单量级为股；`floatSharesOf`
  统一换算并显式单测（test t4），LQ-1 只以「MM 挂单量/floatSharesOf」比率验收（单位在比率中抵消），
  防止 5% 厚度绝对量级错 4 个量级。

### 8.3 实施顺序与依赖（供 scheduler）
```
t2(agent-dev): MM-Agent + 存货公式 + 动态价差   ← LQ-1/LQ-4
t2(engine-dev): 可市价执行通道 + HFT 泊松 + MAX_WAIT  ← LQ-2（依赖 LQ-0 编译；P1 先于 P2 验收但不阻塞并行实现）
t3(engine-dev): 集合竞价 + 熔断撤单重挂(§4.6) + LiquidityState 持久化 ← LQ-3/LQ-5
t4(test-engineer): 固定种子长程验证 + 构造场景 + 回归 → .verify/LIQUIDITY_VERIFY.md
t5(reviewer): 评审 LQ-0..10 + 勘误决议
```

---

## 9. 输入 / 来源索引

| 来源 | 位置 | 本规格用途 |
|---|---|---|
| 撮合主循环 | `MarketEngine.java:1201-1674` | L2/L2.5/L3 接入点与记账 |
| 订单簿撮合 | `OrderBook.java:140-149,253-312,333-423` | R-1 证据；walkBook/execute/auction 改造面 |
| 订单类型/生命周期 | `Order.java:6-11,86-105`；`OrderPipeline.java:101-145` | MARKET 单 0.0 价缺陷；marketable 出口 |
| agent 决策 | `MultiAgentManager.java:368-763` | 对称报价、无存货、deviation 停供（R-2） |
| ZI/Hawkes | `OrderFlowModel.java:101-190` | 现有流密度不足（2.3 对照） |
| 中观 | `MesoLayerManager.java:123-192`、`SectorFundAgent.java:158-162`、`InstitutionalAgent.java:215-217` | 2.3 对照 |
| 三锚脱节 | `PriceDiscoveryEngine.java:346-416,596-606`；`MarketEngine.java:1164-1185` | R-3 |
| A 股规则 | `TradingCalendar.java`、`A_SHARE_TRADING_SPEC.md`(A3-1..8) | LQ-10 不回归基线 |
| 先验注入 | `engine/calibration/CalibrationParams.java` | §3 新先验字段 |
| 持久化先例 | `MarketEngine.save()/loadInternal()`、`OrderFlowModel.save/load`、`VolumeTracker.save/load` | §7.2 模式 |
| 用户经济学输入 | 团队 Goal 文本（存货模型/泊松/瓦尔拉斯/动态价差/熔断重挂） | §4/§5/§6 公式来源 |
