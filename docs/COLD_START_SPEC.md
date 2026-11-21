# COLD_START_SPEC.md — Financial Market 模组市场模型冷启动四方案组合 + 实测 Bug 修复

> 作者角色：architect（方案架构 / 根因诊断与规格）
> 状态：requirements（t1）——产出规格，供 engine-dev / agent-dev / frontend-dev 落地实现。
> 目标：以**开新存档**为测试基线，冷启动即具备「厚度深度 + 基本面参照 + 均线初值」；修复前端图表空白与盘口单边；保持确定性可复现（SeedableRandom 子流，禁裸随机）、H2/NBT 持久化兼容、不改已交付法币 M2 / 黄金核心语义。

---

## 0. 术语与总览

市场模型分层（每条 `tick` 内，`MarketEngine.tick` 时序）：

| 层 | 组件 | 作用 | 文件 |
|---|---|---|---|
| L1 | 价格发现 | GBM+GARCH 宏观演化，产出 gbmPrice | `engine/PriceDiscoveryEngine.java` |
| L2 | AI 智能体 | VALUE/MOMENTUM/RETAIL/MARKET_MAKER 生成限价单 | `engine/agent/MultiAgentManager.java` |
| L2.5 | ZI+Hawkes 订单流 | 对称随机限价/市价单 + 自激励聚集 | `engine/microstructure/OrderFlowModel.java` |
| L3 | 撮合 | 价格优先+时间优先，walk the book 深度冲击 | `engine/OrderBook.java` |
| 派发 | 前端 | WebView echarts `index.html` 与 原生 `FinancialTabletScreen` | `resources/.../html/index.html`、`FinancialTabletScreen.java` |
| 持久化 | H2/NBT | 整块 NBT(Base64) + 关系镜像表 | `storage/db/H2DatabaseManager.java`、`MarketAttachment.java` |

**冷启动入口**（`attachment/MarketAttachment.java:40 getOrCreateEngine`）：
- ① 优先 `MarketEngine.loadFromH2()`；资产为空则 `init()`。
- ② 回退 `MarketEngine.load(NBT)`；资产为空则 `init()`。
- ③ 均无 → `new MarketEngine(deriveEngineSeed(level)).init()`，随后 `saveEngine` 立即落盘。

`MarketEngine.init()`（`engine/MarketEngine.java:383`）：
1. `macroEnvironment.loadSeedData(0.035, 0.02, 18.0)` 冷启动宏观种子；
2. `aiCompanyManager.registerAllToMarket(this)` → 每个公司 `registerAICompany` → `priceEngine.addAsset(id, ipoPrice, vol, drift, industry, 60)` **并生成 60 根 DAILY 历史K线**；
3. 每资产 `orderBooks.putIfAbsent(id, newOrderBook(id))` → **订单簿初始为空**（无任何 resting 深度）；
4. `agentManager.initializeAgents(id, 20+engineRng.nextInt(20))`。

> **核心缺口**：冷启动时订单簿为空、智能体 `holdings=0`、宏观因子仅 `loadSeedData` 三参。这正是「盘口单边」「开局无深度」「基本面 AI 无参照」的根因，也是本规格四方案的落点。

---

## 1. 根因诊断（回归实证）

### 1.1 前端图表空白 — 日志：数据 60 根但图画不出

**数据在服务端确认存在**：`LocalHttpServer.getKlineJson`（`web/LocalHttpServer.java:312`）调用 `pe.getTechnicalKlines(assetId, DAILY, 60)`，`MarketEngine.init` 的 `addAsset(..., 60)` 经 `generateHistoricalKlines`（`PriceDiscoveryEngine.java:219`）以 `totalTicks = 60*240*20` 生成 60 根 DAILY（`DAILY_TICKS_PER_BAR=240`），`getTechnicalKlines` 返回 `enhanced.size()==60` 有日志佐证。

**前端上下文（frontend 审计确认）**：交易前端是 **MCEF 浏览器页**（`FinancialMCEFScreen` → `http://localhost:8080`=`LocalHttpServer` → `index.html`+`echarts.min.js`），**所有行情/盘口/K线均走 BRIDGE_JS → HTTP `/api/kline`、`/api/depth`、`/api/stock` 等**。网络包（`StockDataResponsePacket`/`OrderBookSnapshotPacket`）**只喂旧的 `FinancialTabletScreen`（原生 Canvas）**，不喂 echarts 页；`ClientMarketDataCache` 的 kline/depth 缓存对 MCEF 页“只写不读”（`getEnhancedKlines` 无消费者）。→ echarts 页的证据链与网络包路径**解耦**，两处 bug 分别定位。

图表空白（MCEF echarts 页），根因定为**渲染容器/时序/异常（(c) 已发送但渲染失败）**，而非数据缺失：

**(A) WebView echarts 路径（MCEF 页，主用）——主因：容器 0 高度 + 时序竞态**
- **作者自证**（`index.html` initCharts 注释，frontend 审计：`:1495-1497` 原文）：「关键修复：echarts.init 时必须容器已获得非零尺寸，否则 canvas 高度=0，出现『数据 count=60 但图空白』的假象。init 前轮询等待 flex 布局稳定；若数据先于图表初始化到达（时序竞态），init 成功后补绘一次缓存 K 线。」
- **容器尺寸**：`#main-chart`/`#sub-chart` 为 `height:100%` 且嵌在 flex 链（`:232-234` `#charts-container{flex:1}`/`#main-chart{flex:2.7;height:100%}`；`:109` `#main-container{flex:1;height:0}`；body 高 `100vh`）。**百分比高度在 flex 嵌套中不可靠，可能在 init 时解析为 0 → canvas 高=0 → 有数据也空白**。
- **时序竞态**：K线经 fetch 异步到达，若 `drawMainChart` 先于容器尺寸稳定执行则为空。
- **现有修复（进行中）**：`:1535-1536` 轮询 `clientHeight/clientWidth>0` 才 `echarts.init`；`:1546-1547` resize(40/250ms)；`:1542-1545` ResizeObserver；`:1550` init 后补绘；`:1552-1562` 每 50ms 重试（上限 80）；`:1752` setOption 后 rAF resize。**但仍存缺口：`:1555-1558` 超限后“强制初始化”未校验容器尺寸，若容器确为 0 高仍会空白——修复可能不完整。**（frontend-dev 需补齐）。
- **time 为字符串下标**：`getTechnicalKlines:461` `time=String.valueOf(i)`；`LocalHttpServer.getKlineJson:340`（tradePoints）同样下标；`ClientMarketDataCache.computeEnhancedKlines:226` 同。ECharts category 轴用下标字符串作类目，HTTP 路径下互不相同、**不致空白**，但确系“timestamps vs 下标”语义问题（建议归一化为递增序号或真实时间键，见 MS-1）。
- **异常吞没**：MCEF 页 BRIDGE_JS `getJSON:30` `.catch(()=>cb(null))` 与 `drawMainChart:1687` 仅 `!mainChart` 守卫——若 `drawMainChart` 抛错会被吞掉且页面继续“空白”。

**(B) 网络包路径（旧原生 `FinancialTabletScreen`，非本 bug 触发源）**
- 独立缺陷（`(b) time 丢失`）：`RequestKlinePacket.handle:57-66` 组 klinesMap 只放 `open/high/low/close/volume`，无 `time`；`StockDataResponsePacket.write:52-65` writeMap 也只写 open..volume，读回无 time。若前端从网络包取数，所有 bar `time=''`（`:1689` `k.time||''`→`''`），category 轴全部塌缩到同一空串 → 图表空白。**但 MCEF 页走 HTTP，当前不触发此路径**；仅旧原生屏受影响。为兼容两路，建议网络包也补 `time` 键（MS-1 一并修）。
- `FinancialTabletScreen:1336/1346` 的 `updateStockData/updateOrderBook` 属旧原生 Canvas 屏（非 echarts），不参与本 bug；`ExchangeScreenDataService/ExchangeScreenSnapshot` 属“交易所方块”旧屏，亦不走 echarts。

**两条前端路径的增强K线来源不同（审计注）**：
- **桥接/首包路径**：`ClientMarketDataCache.computeEnhancedKlines`（`web/ClientMarketDataCache.java:191`）用自有 `calcMA`（`i<p-1` 置 0，非 NaN）且含 `time=String.valueOf(i)` 与全部指标键 → 此路数据自洽。
- **HTTP `/api/kline` 路径**：`LocalHttpServer.getKlineJson` → `PriceDiscoveryEngine.getTechnicalKlines`，其 `calcMA`（`PriceDiscoveryEngine.java:473`）对 `i<period-1` 返回 **NaN** 且 `time=String.valueOf(i)`；若 `TechnicalIndicatorCalculator` 任一指标产出 NaN/Infinity，或 Gson 序列化产生非法 JSON，则浏览器端 `r.json()` parse 失败 → `pollDetail` 的 `.catch(()=>{})`（`index.html:2131`）静默吞掉 → `drawMainChart` 不被调用 → **空白**。故 MS-1 的修复须覆盖：服务端 NaN 清洗 + 客户端 `NaN→null` + `.catch` 打日志。

**结论（R-B1）**：数据健在，但渲染路径在「容器高度 0 / time 缺失 / NaN 污染 / 时序 catch 吞异常」四因上可导致空白。**修复点集中在 index.html 的 initCharts/resize、updateStockData/drawMainChart 的防御与 time 归一化，以及服务端 getTechnicalKlines 的 NaN 清洗**。详见 §2 与 §3（MS-1）。

### 1.2 盘口单边（bids 有量、asks 恒 0）

**数据侧实证**：`LocalHttpServer.getDepthJson`（`web/LocalHttpServer.java:353`）确实同时序列化 `snap.bids` 与 `snap.asks`（`else` 均 `List.of(fmt(price), qty)`）；`MarketEngine` `:1388-1394` 构建 `OrderBookSnapshotPacket` 亦双边都带。→ **非序列化丢失**（排除“只传 bids/清零”），是订单簿**真实 asks 为空/薄**与前端**渲染/顺序缺陷**共同所致。

**根因 R-B2（数据侧，主因）**：A 股冷启动进入 `MultiAgentManager.updatePhase` → `StartupPhase.VALUE_ONLY`（`tickCounter<500`，`MultiAgentManager.java:130-176`），`isAgentTypeActive`（`:180`）在 VALUE_ONLY 只放行 `AgentType.VALUE`，**冻结 MARKET_MAKER / MOMENTUM / RETAIL**。而 VALUE 单的**卖出分支要求 `agent.holdings >= size`**（`valueDecision` `:417`），冷启动 `holdings=0` → **VALUE 智能体在开盘无任何可卖持仓，只能挂 BUY**。叠加：
- L2.5 ZI/Hawkes 虽对称（ZI `buyProb=0.5`），但对称挂单的卖侧余量被持续买压瞬间吃光，抵达并立即成交 → `getSnapshot` 取到的是**撮合后残余（ask 侧近空盘/0 量档）**；
- 因此**开盘 `VALUE_ONLY` 阶段 0–500 tick 无卖侧静态深度，`asks` 恒 0**，盘口单边。

**根因 R-B2b（前端渲染/顺序缺陷，叠加放大）**：`index.html:1338` `for(i=4;i>=0;i--)` 且标签 `卖${5-i}` → `asks[0]`（服务器升序=最优/最低卖价）被显示为“卖5”、`asks[4]`→“卖1”，**卖侧五档顺序颠倒**（买侧 `:1349` `bids[0]→买1` 正常，买/卖不对称）。另 `:1340`/`:1351` 对 `Number(slot[1])>0` 的守卫把后端 0 量档改显示“--”（仅掩盖症状，未修数据侧）。

**辅助成因（R-B3）**：冷启动订单簿为空（`newOrderBook` 无 resting 单），且 agent 卖侧因 `holdings=0` 无 sell 存量 —— 属于「无 sell 存量 + 无初始深度」的冷启动缺陷。

**结论**：盘口单边 = **冷启动卖侧流动性/初始深度缺失（方案一历史播种根治）** + **前端卖侧五档顺序反转与 0 量档显示（frontend-dev 修 R-B2b）**。精确复现可直接看 `/api/depth` 返回的 `asks` 是否为空数组。

---

## 2. 冷启动四方案落地设计

> 统一确定性原则：所有随机一律走 `SeedableRandom`（含 `mix64`/`derive` 子流），禁 `Math.random()`/裸 `Random`；同种子 → 同序列。所有新增状态随 `save()/load()` 落 H2/NBT，旧档缺字段用 `contains` 兜底，读档不失败。

### 方案一：历史播种（历史切片起步）

**目标**：冷启动即让 L3 订单簿有厚度深度、基本面 AI 有参照、均线有初值，前 1000 笔像真实市场延续。

| 桩 | 改动文件/方法 | 数据流 | 确定性 | 持久化 |
|---|---|---|---|---|
| 初始价 | `MarketEngine.registerAICompany`/`registerPlayerCompany` → `priceEngine.addAsset(..., 60)` 现以 `ipoPrice` 起步；**保留**，并确保 `AssetSnapshot.prevClose=ipoPrice`（`registerPlayerCompany:486` 已有；`registerAICompany` 缺 → **补** `setPrevClose(ipoPrice)`）。 | state.lastPrice; prevClose → 涨跌停带锚 | 无随机 | save/load 已有 |
| 买卖五档 | 新增 `MarketEngine.seedColdStartBook(assetId)`：用 `engineRng.derive("coldstart_book/"+id)` 生成以 `lastPrice` 为中心的**对称多档**（价格降序/升序各 5 档，档距≈`tick 0.01×[1..5]`，量≈[500,4000]），`OrderBook.placeOrder` 双侧 synthetic 单；在 `init()` 每资产调用。 | orderBook.bids/asks 立即有 5 档 depth | engineRng 子流 | OrderBook.save/load 已核（P2-4 `:380`） |
| 宏观因子 E(0) | `MarketEngine.init` 的 `macroEnvironment.loadSeedData(0.035,0.02,18.0)` 仅三参；**补** `riskAppetite=0.5`（中性）、`interestRate/discountRate`、`liquidityMultiplier=1.0`、`dynamicDrift/Volatility` 初值，写入 `MacroEnvironment`。 | macroEnv → L1/agent 消费 | 无随机 | 持久化在 global state 走 `macroEnv.save/load`（已含） |
| 智能体持仓 | 冷启动给 VALUE（与个别 MOMENTUM）初始化 `holdings`（如 `[size_low,size_high]`）使**开盘即可卖**：`MultiAgentManager.initializeAgents` 后按 `engineRng` 派生给 `AgentState.holdings`。 | agent 卖侧不再饿死；修复 1.2 | engineRng 子流 | AgentState 随引擎 NBT 存（若已存则补字段） |
| 均线初值 | 由方案一“历史K线已生成 60 根 DAILY”天然支撑 MA5/10/20/60；**保证** 服务端 `getTechnicalKlines` 返回的 MA 不因 NaN 断图（见 MS-1 NaN 清洗）。 | 前端 MA 有初值 | 无随机 | 走 klinesCache |

**开新存档生效**：仅在 `init()`（H2/NBT 无数据）路径调用 `seedColdStartBook` 与持仓播种；读档路径（`loadInternal`）**不重播**，直接复用已恢复的订单簿/状态（含旧档兜底）。

### 方案二：渐进式智能体释放

**现状**：`MultiAgentManager` **已实现** `StartupPhase{VALUE_ONLY(0-500), VALUE_MAKER(500-2000), FULL(>2000)}` 与 `isAgentTypeActive`（`:130-190`），并在 `stepAgents` 决策循环 `:316` 每 agent 判定。已满足「前 500 tick 只 L1+基本面、500-2000 引入做市商、2000 后释放游资散户」骨架。

**落地增强（MS-4）**：
1. 确认 `isAgentTypeActive` 确实被消费（`:316` 已验证）——**无需新接线**，属“已钉稳”。
2. **VALUE_ONLY 阶段仍保留基金面 AI（VALUE=fundamentalist）活跃**，满足“价值锚定先行”；
3. `VALUE_MAKER` 阶段解锁 `MARKET_MAKER`（做市商双边报价 `marketMakerQuotes` `:533`），补流动性；
4. `FULL` 阶段解锁 `MOMENTUM+RETAIL`（游资/散户），投机博弈；
5. **配套**：`VALUE_ONLY` 阶段因 value 单卖出受 holdings 限制，由方案一的持仓播种兜底，避免“只买不卖”重现单边。
6. **可选门控**：`updatePhase` 的阈值需与 `MarketEngine.tick` 的 `agentManager.updatePhase(tickCounter)`（`:954`）使用**同一时间权威**（tickCounter），已一致。

### 方案三：损失函数硬约束

**目标**：在 AI 决策“损失”加物理惩罚：加息时基本面 AI 不得上调估价 + 价格连续性 `|log(S_t)-log(S_{t-1})|<M` 防开局跳空涨跌停。

**a) 加息 → 基本面 AI 不得上调 fairValue（MS-5a）**
- 现状是启发式：`MultiAgentManager.stepAgents` `:273-281`，`if (interestRate > 0.04 && fairValue > gbmPrice*1.2) fairValue = gbmPrice*1.05`，且 `AICompanyData.updateFairValue()` 用 DCF/PE/PB 无利率约束。
- **落地**：新增 `MultiAgentManager.constrainFairValueForMacro(prevFairValue, newFairValue, interestRate)`：当 `interestRate` 相对上一期**上升**（`Δr>0`）时，`newFairValue = min(newFairValue, prevFairValue)`（强制不升），并添加惩罚项 `L += λ·max(0, newFairValue-prevFairValue)`（λ 对外可见常量，默认温和）；`stepAgents` 计算 `fairValue` 后、参与锚之前调用。同时 `AICompanyData.updateFairValue` 注入可选的利率上界 guard（幂等、同种子影响一致）。
- 确定性：纯函数、无随机。

**b) 价格连续性约束（MS-5b）**
- 缺口确认：`PriceDiscoveryEngine.step`（`:307-313`）`newPrice = gbm.price*exp(...)` 仅有 `max(newPrice, 0.01)` 下限保护，**无逐 tick 连续钳制**；A 股 ±10% 涨跌停（`withinAShareLimit` `:1485`、`OrderBook` 钳制 `:215`）锚的是 **prevClose（昨收）**，因此冷启动/隔夜可跳空到 ±10%（涨跌停）。
- **落地**：新增 `PriceDiscoveryEngine.clampForContinuity(assetId, target, last): double = if(last>0 && |log(target/last)|>M) last*exp(sign*M) else target`，M 默认 0.05（≈5%/bar）；
  - 在 `step()` 生成 `newPrice` 后（L311-313 之间）对 `gbm.price` 施加：
    `newPrice = gbm.price * exp(clamp(log(newPrice/gbm.price), -M, +M))`；
  - `OrderBook.walkBook`/`matchWithP0RandomSlippage` 的 `fillPrice` 增加「相对本会话最近成交价」的连续性夹断（与涨跌停带取更严者）。
  - 消费端（`MarketEngine.tick` L1086 后）同样应用，确保任何进入订单簿/成交的价都不跨 bar 跳变。**消除开局跳空涨停/跌停**。
- 确定性：无随机；M 为常量可配置。

**c) fairValue 两级约束（MS-5a/5c）**
- 注意存在**两级 fairValue**：① 智能体决策用本地 Gordon（`stepAgents` `:267-270`，`discountRate` 来自 `macroEnv.getDiscountRate()`，**对政策利率敏感**）——这是“基本面 AI 下单所用估值”；② 公司级 DCF+PE+PB（`AICompanyData.updateFairValue` `:155-183`，折现率仅 `0.08+debtRatio*0.1`，**对政策利率无感**，用于 ipoPrice=初始成交价）。
- **落地**：MS-5a 主约束落在 ①（`stepAgents` L269-270 后加单调约束）；如需让 ② 也随政策利率修正，在 `AICompanyData.updateFundamentals`/`updateFairValue` 注入 policyRate 输入（幂等，且只影响后续估值，不动已交付 DCF 公式因子）。
- **确定性注意**：`:318-324` 的 `PriceDiscoveryEngine.isValuationConsistent` 是恒 `return true` 的 stub，可保留或改为供 API 查询，不作消费者依赖。

### 方案四：卡尔曼初始化

**现状——存在但公式有缺陷（agent 审计确认）**：`PriceDiscoveryEngine.getNoiseMultiplier()`（`:178`）与 `step()` `:277` 的 `softStartFactor` 已有雏形（`KALMAN_WARMUP_STEPS=100:127`、`NOISE_RAMP_TAU=50:128`、`1-exp(-ratio²/TAU²)`），但：
- **公式量纲错**：`ratio = startupTick/KALMAN_WARMUP_STEPS`，故指数实为 `exp(-(startupTick/100)²/50²) = exp(-t²/25_000_000)`，等效 **τ≈5000**（远非规格的 τ≈50）——前 100 tick 乘数≈0（近确定性），远达不到“τ≈50 后恢复随机游走”；
- **硬跳变**：`:179` `if (startupTick >= KALMAN_WARMUP_STEPS) return 1.0` 在 tick=100 瞬间跳回 1，造成 K 线在 t=100 突变，不是平滑“先确定后随机”。

**落地修正（MS-6，核心）**：
1. 改成规格要求：`getNoiseMultiplier = 1 - exp(-(startupTick/τ)²)`，τ=50（把 `ratio` 直接除以 τ 而不是除以 `KALMAN_WARMUP_STEPS`），去掉 `:179` 的硬 `return 1.0`（或让 warmup 上限与 τ 一致而非 100），使乘数平滑趋近 1；
2. `softStartFactor = 0.1 + 0.9*multiplier` 前 N 步仍保留 10% σ 地板（非完全静止，保确定性趋势演化）；如需更“确定性”可下调下限；
3. 将 τ / warmup 提升为构造可配置，默认守 τ=50；
4. 与方案三 b 的连续性夹断协同：软启动压噪声 + 连续性夹断叠加，开局K线平滑、无跳空；
5. 保证用 `gbm.rng`（已按资产派生）驱动，确定性可复现。

> **MS-6 的验收须验证**：① 前 ~50 tick 乘数贴近 0（近确定性）；② tick≈50 处平滑过渡（无跳变）；③ 之后随时间恢复随机游走。当前实现**不满足** ①②，属必须修复项。

---

## 3. Bug 修复规格

### MS-1 前端图表空白（frontend-dev）
- 容器：`index.html#initCharts`/`drawMainChart` 调用前强制 `getBoundingClientRect().height>0` 再 `resize()`；`#main-chart`/`#sub-chart` 用 `resize({height: el.clientHeight})` 兜底（fix R-B1-A）。
- time 归一化：`updateStockData`/`drawMainChart` 对 `k.time` 缺失时回退整数索引，保证 candle 轴维数一致（fix R-B1-A）。
- NaN 清洗：服务端 `getTechnicalKlines` 对 `ma5/10/20/60` 与指标在被请求根数不足时返回 `null`（而非 NaN/0）或前端将 NaN 替换为 `null`，避免 ECharts line 断图（fix R-B1-A）。
- 异常可观测：HTTP `pollDetail` 的 `.catch` 至少 `console.error`，避免“静默空白”。
- 首包打点：`FinancialMarket.onPlayerLoggedIn` 发送 K 线时补 `time` 键（与 `getTechnicalKlines` 对齐），消除桥接/HTTP 两路字段不一致。

### MS-2 盘口单边渲染（frontend-dev 仅验证；主修在数据侧 engine）
- 前端 `updateDepth` 已正确渲染 asks/bids；**根因在数据侧**（见 §1.2）。frontend-dev 只需在数据侧修复后可自由回归（“卖1..卖5 显示 `--` 变有量”即验收）。渲染侧防御：`asks[i][1]` 为 0 时显示 `0` 而非 `--`（避免误以为丢数据）。

### MS-3 根因在数据侧的引擎修复（engine-dev / agent-dev）
- 方案一 `seedColdStartBook` + 持仓播种 → 双侧深度 + 可卖持仓（directly 修 1.2）。
- 方案二 确认 `StartupPhase` 消费 + 做市商双侧报价 `VALUE_MAKER` 引入。

---

## 4. 确定性 / 持久化兼容 / 守边界

- **确定性**：所有新增随机用 `engineRng.derive(...)`（`SeedableRandom.mix64` 子流）；方案二/四复用既有子流，不新增裸随机。禁 `Math.random()`。
- **可复现性风险（agent 审计确认）**：`MultiAgentManager.stepAgents` 的决策随机用**单一共享** `this.random`（`:143`），随 `state.getAssetIds()`（HashMap keySet）迭代顺序消费；玩家公司上市注册（`registerPlayerCompany` `:483`）改变该集合插入顺序，会扰动后续所有智能体决策序列。**冷启动方案需把决策随机改为按资产派生**（`random.derive(assetId)` 新建子流），保证同种子 + 同公司注册时序下逐位一致（MS-7）。
- **既有裸随机（破坏源，非活动路径）**：`MarketDepth.java:24-25`（`Math.random`，死代码无调用）、`FinancialTabletScreen.java:1010-1011`（客户端 UI 演示数据）、`MarketState.java:33-36`（无种子 `new Random()`，死代码无调用）。均不在引擎模拟路径，但建议一并清理或播种（低优先级）。
- **AICompanyManager 的 `java.util.Random(seed)`**（`:20`）为“已交付”共享流（行业/代码/趋势等），注释明示**不得改其消费顺序/替换**（否则扰动既有取数序列）；冷启动只可新增独立子流，不得重排其消费。
- **持久化**：
  - 方案一订单簿 depth 走 `OrderBook.save/load`（P2-4 已核）；`AssetSnapshot.prevClose` 走既有 save/load；`AgentState.holdings` 若引擎 NBT 已存则补字段、未存则加 `contains` 兜底。
  - 方案三新增的 M/λ 常量不落盘（编译期常量）；fairValue guard为运行期无状态。
  - 方案四 τ/WARMUP 若改可配置，仅在配置分支落盘，旧档缺字段用默认值。
  - **新增字段一律 `contains` 兜底，读档不失败**（A2-7）。
- **守边界**：不改法币 M2 / 黄金定价公式；方案三/四只是**在既有价格/估值输出上加夹断/约束**，不改变已交付核心语义。`MarketEngine.init` vs `loadInternal` 严格分离：播种只在 `init()` 冷启动执行，读档不重播。

---

## 5. 验收标准（MS-0 .. MS-N，开新存档）

| 编号 | 验收项 | 判据 | 归属 |
|---|---|---|---|
| MS-0 | 冷启动无异常 | 开新世界，日志无 `MarketEngine` 初始化异常，资产数>0 | engine-dev |
| MS-1 | 图表非空白 | 选中任一资产，日K周期下 echarts 渲染出 K 线（≥1 根可见），无 `--`/空白；echarts.init 前容器 `clientHeight/clientWidth>0`（修复 R-B1 容器0高度/时序竞态）；网络包路径 klinesMap 补 `time` 键（修 R-B1-b） | frontend-dev |
| MS-2 | 盘口双侧 | 任意资产五档盘口 `bids` 与 `asks` 均≥1 档有量（非恒 0）；前端卖侧五档顺序纠正（`asks[0]→卖1`，修 R-B2b）；委比正常计算 | engine-dev / frontend-dev |
| MS-3 | 开盘有深度 | `init()` 后订单簿 snapshot.bids/asks 各≥3 档、总深度>0（可加调试日志实证） | engine-dev |
| MS-4 | 渐进释放 | tick<500 只有 VALUE 挂单；500-2000 有 MARKET_MAKER；>2000 有 MOMENTUM+RETAIL（日志可见订单方向/数量），价格先价值锚定后投机 | agent-dev |
| MS-5a | 加息不升估值 | 模拟 `interestRate` 上调前后，`fairValue` 单调不升（单元判定），日志可观察 | agent-dev |
| MS-5b | 无跳空 | max over bars of `|log(S_t/S_{t-1})|` ≤ M（默认 0.05），开局无越涨跌停跳空 | engine-dev |
| MS-6 | 平滑开局 | 前 ~100 tick K 线振幅收敛、噪声乘数 1-exp(-t²/τ²) 生效，τ≈50 后恢复随机游走 | engine-dev |
| MS-7 | 可复现 | 同世界种子两次开新档，前 2000 tick 每资产价格序列逐位一致 | engine-dev / test-engineer |
| MS-8 | 持久化兼容 | 存档→读档后订单簿 depth、prevClose、agent holdings 恢复；读旧档（缺新字段）不失败 | engine-dev / test-engineer |
| MS-9 | 守边界 | 法币 M2、黄金公式服务端日志/输出未变化；关掉冷启动开关回落到旧行为 | reviewer |

---

> **行号时效性说明**：本规格在审计期间引用的大量行号为**审计快照时点**的版本。该仓库正被并行实现者（engine-dev / agent-dev / frontend-dev）**实时编辑**（index.html、PriceDiscoveryEngine.java、OrderBook.java、LocalHttpServer.java 等均已有新增“关键修复”注释与行号漂移）。实现者应以方法名/注释/语义为准，行号仅供定位参考；已落地的修复以源码现状为准，本规格作为**需求与验收口径**。

---

## 6. 落地文件清单（按功能分派）

**engine-dev（方案一/四 + MS-3/5b/6/7/8 数据侧）**
- `engine/MarketEngine.java`：`init()` 加 `seedColdStartBook`、`riskAppetite` 等宏观初值、`registerAICompany` 补 `setPrevClose`；新增 `seedColdStartBook`（engineRng 子流生成 5 档双侧）。
- `engine/PriceDiscoveryEngine.java`：`step()` `newPrice` 后加 `clampForContinuity`（L311-313）；**修正** `getNoiseMultiplier` 公式与 `:179` 硬跳变（方案四）；`getTechnicalKlines` MA 不 NaN。
- `engine/OrderBook.java`：`matchWithSlippage`/`walkBook`/`matchWithP0RandomSlippage` 成交价加相对最近价连续性夹断。
- `engine/MacroEnvironment.java`：冷启动宏观因子初值写回（`loadSeedData` 参数化）。
- `engine/ai/AICompanyData.java`：`updateFairValue`/`updateFundamentals` 可选政策利率上界 guard（MS-5c）。

**agent-dev（方案二/三 + MS-4/5a + 可复现性）**
- `engine/agent/MultiAgentManager.java`：确认 `isAgentTypeActive` 消费（`:316` 已为真）；新增 `constrainFairValueForMacro`（`stepAgents` L269-270 后插入）；初始化 `AgentState.holdings`（持仓播种）；**决策随机改为按资产派生**（`random.derive(assetId)`）；`updatePhase` 阈值/门逻辑按需微调。

**frontend-dev（MS-1/MS-2 渲染）**
- `resources/assets/financialmarket/html/index.html`：`initCharts`/`drawMainChart` 容器尺寸兜底与 time 归一化、NaN→null、`.catch` 打日志、asks 0 显示。
- `FinancialMarket.java`（onPlayerLoggedIn）：K 线首包补 `time` 键。

---

## 7. 风险与后续

- **风险 1**：VALUE_ONLY 阶段 value 单卖出仍受 `holdings` 限制 → 持仓播种必须覆盖，否则单边重现（MS-3/4 联动）。
- **风险 2**：`loadInternal` 与 `init` 路径的种子序列必须一致，否则读档后行情与同期新档偏离（MS-7）。`initializeAgents` 在 `load` 中被再次调用（`MarketEngine.loadInternal:2116`）——**需为幂等**，且不要在 load 路径重播 `seedColdStartBook`。
- **风险 3**：容器高度 0 的时序在真实客户端与 headless 测试不同 → MS-1 验收须在真实游戏视图复核。
