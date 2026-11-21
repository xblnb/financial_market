# FIX_UI_SPEC — 金融平板(Financial Market)UI + 数据管线根因确认与验收契约

> - 角色：architect（方案架构 / 根因诊断与需求规格），为任务 t5 产出；供 t1（engine-dev 引擎数据管线修复）、t2（frontend-dev 金融平板 UI 修复与临摹）对照实施，供 t4（reviewer 评审验收）。
> - 输入：团队 Goal + 用户提供同花顺参考图（交易界面五区、红涨绿跌）+ 本规格对**实际源码**的逐条根因审计（含文件:行号）。
> - 范围边界：**只补数据管线接入点**；**不改已交付经济引擎/法币M2/黄金/A股规则核心语义**；引擎「H2/NBT 持久化」**零改动**；**禁裸随机**（沿用 SeedableRandom 子流）。前端 `src/main/resources/assets/financialmarket/html/index.html` 与引擎 web 层（`web/LocalHttpServer.java`、`engine/MarketEngine.java`、`engine/AssetSnapshot.java`、`engine/MarketState.java`、`engine/PriceDiscoveryEngine.java`）为修复面。
> - 权威语义参照：`A_SHARE_TRADING_SPEC.md`（A 股：±10% 涨跌停 / T+1 / 一手 100 股 / 价格+时间优先）、`FRONTEND_THS_SPEC.md`（同花顺 PC 五区布局 + 红涨绿跌 + 双轨数据源）。

---

## 0. 范围界定（谁改什么，谁不许动）

| 层 | 文件（确切路径） | 允许改动 | 禁止改动 |
|---|---|---|---|
| 引擎快照 | `engine/AssetSnapshot.java` | 仅数据维护：增量写入 `dailyChange/high/low/open/bid/ask/prevClose` 语义，**不新增字段以外的公式** | 价格生成/撮合逻辑 |
| 引擎状态 | `engine/MarketEngine.java` | per-tick / 成交命中处**补快照更新接入点**（dailyChange/high/low/open 计算与写入）+ 复用已有 `getIndexValueEx()` | A 股规则、经济/M2/黄金公式、注册/IPO/T+1 语义 |
| 引擎状态 | `engine/MarketState.java` | 视需要把 `getIndexValue()` 桩改为转发/或仅加 `setIndexValue` 真值写入点 | 持久化格式 |
| 引擎 kline | `engine/PriceDiscoveryEngine.java` | 确认/修复 `getTechnicalKlines` 空态：保证已注册资产对 DAILY/MONTHLY/分时有非空序列 | 价格生成、GBM、撮合语义 |
| Web 层 | `web/LocalHttpServer.java` | `getIndexJson` 改读真源 `engine.getIndexValueEx()`；`getStockListJson` 的 `change` 改读前端可消费的**涨跌幅%**；`getDailyDataJson` 字段来源对齐 | API 结构、POST 交易校验权威 |
| 前端 | `html/index.html` | 修复 `updateStockData/updateStats/updateInfoBar/updateMarketList` 消费的字段；图表 `drawMainChart` 渲染；盘口/五档/板块/底部指数条数据接入 | 后端规则 |

**硬约束（贯穿所有改动）**：
1. **确定性可复现**：任何新随机都必须走 `SeedableRandom` 子流（禁裸 `Math.random`/`new Random()`）；同种子两次运行 → 快照/K线/断言序列一致。
2. **不改核心语义**：A 股 ±10% 涨跌停、T+1、一手 100 股、价格+时间优先、经济引擎/法币 M2/黄金公式**一行不改**；修复仅是**读取/回写快照与真源接入**。
3. **H2/NBT 兼容**：`MarketState.save/load` 与 H2 持久化边界不新增破坏性字段；新增快照字段为内存态，持久化写读双兼容（读旧档缺字段默认回退，不崩、不重置账本）。
4. **红涨绿跌（CN）固定**：涨=红 `#F04747`，跌=绿 `#43B581`；应用于现价/涨跌幅/K线实体/成交量柱/五档/指数/持仓浮盈。

---

## 1. 根因逐条确认（含确切代码位置与修复方案）

### BUG-1 所有股票涨跌幅恒 0.00%（用户截图1/2 实证）
- **根因**：`AssetSnapshot.setDailyChange(double)`（`engine/AssetSnapshot.java:50`）**全项目零调用**（grep 全 src：唯一出现在定义处，无任何调用者）。`AssetSnapshot` 构造器把 `dailyChange` 初始化为 0（`:13`），且从不更新。
- **数据出口**：`web/LocalHttpServer.getStockListJson()`（`:250-284`）把 `s.put("change", fmt(snap.getDailyChange()))`（`:261`）序列化给前端 → 恒 0。
- **前端表现**：`html/index.html` 行情列表用 `change` 渲染（`renderMarketList` 消费），因恒 0 显示 `0.00%`。注意：个股详情 `updateInfoBar/updateStats`（`index.html:1692-1741`）是**用 kline 相邻 close 另算涨跌幅**，故详情页涨跌幅可能非 0，而**自选/个股列表恒 0**——这正是截图列表区全 0 的原因。
- **修复方案（t1 引擎数据管线）**：在**每次成交/价格更新命中**处（`MarketEngine` 交易循环 / `PriceDiscoveryEngine.step()` 更新 `lastPrice` 处）写入快照：`dailyChange = (lastPrice - prevClose)/prevClose * 100`（prevClose>0，否则 0）；同时在成交命中处更新 `best bid/ask`。**数据管线接入点：只回写快照，不改撮合/定价公式。**
- **补充**：若前端列表 `change` 字段为「涨跌幅%」，则 `getStockListJson` 直接输出 `snap.getDailyChange()`（数值已成 %）；若前端期望「涨跌额」，需在契约明确（见 §2.1 字段口径——本规格采用**涨跌幅 %**，与 `FRONTEND_THS_SPEC` 图表口径一致）。

### BUG-2 最高/最低/开盘/收盘不随当日交易更新
- **根因**：`AssetSnapshot.setHigh(45)/setLow(46)/setOpen(44)` **几乎不被调用**；全 src 对 `setHigh/setLow/setOpen` 的调用者**为零**（仅 `setPrevClose` 在 `MarketEngine.java:474`,`:910` 被调用）。故 `high/low/open` 恒为构造时初始价（`:22-24`=lastPrice）。
- **数据出口**：`LocalHttpServer.getDailyDataJson()`（`:471-480`）读 `snap.getHigh()/getLow()`（`:477-478`）→ 恒初始价；`open/close` 走 `eng.getTodayOpen()/getTodayClose()`（`:475-476`）独立 map。两套来源不一致，且 high/low 无当日交易演进。
- **前端表现**：`updateStats`（`index.html:1712-1741`）用 **kline 最后一根K线的 high/low/open** 渲染（`:1716-1721`），该值是**历史聚合bar**而非「当日」high/low，导致「当日最高/最低」与真实当日交易不符（列表页/详情页口径分裂）。
- **修复方案（t1）**：交易循环内，当新 `lastPrice` 成交时：`high = max(high, lastPrice)`、`low = min(low, lastPrice)`；**当日首笔成交**时 `open = lastPrice`（若 open 仍=初始价）；每日开始 `calendar.isTradingDayStart()` 重置 `todayOpen/todayClose/hasTodayTrade` 时同步**按昨收刷新当日基准**，并让 `getDailyDataJson` 的 high/low/open 与快照一致（或统一到快照单一来源）。
- **对齐要求**：`open` 取「当日首笔成交价」，`high/low` 取「当日 max/min(lastPrice)」，`close` 取「lastPrice/当日末笔」，`prevClose` 沿用日界 `setPrevClose`（`:910`）。前端详情页/统计区必须消费**同一来源**（见 §2.2）。

### BUG-3 指数 value 恒 0（getIndexJson 读 null 桩）
- **根因**：`MarketState.getIndexValue()`（`engine/MarketState.java:62-64`）返回 `null`（空桩）；`web/LocalHttpServer.getIndexJson()`（`:287-305`）读 `Object val = eng.getState().getIndexValue()`（`:290`），`val` 恒 null → `value = 0.0`（`:291`）。`changePoint/change` 恒 0（`lastIndexValue` 逻辑也不触发）。
- **已有真源**：`MarketEngine.getIndexValueEx()`（`engine/MarketEngine.java:1725`）返回 `excellent24Index.getIndexValue()`（交易所屏幕已用此绕过桩，`:1795-1796`, `:2368`）。**这就是「指数真源」。**
- **修复方案（t1）**：`LocalHttpServer.getIndexJson()` 把 `getState().getIndexValue()` 替换为 `eng.getIndexValueEx()`；保留 `changePoint = value - lastIndexValue`、`change = (value-lastIndexValue)/lastIndexValue*100` 的增量逻辑（`lastIndexValue` 缓存已存在，`：295-299`）。指数条由 +→ 显示真实值/涨跌点/涨跌幅（红涨绿跌）。

### BUG-4 图表（eCharts K线/分时/成交量）画不出来/空白
- **数据层确认**：`engine/PriceDiscoveryEngine` **在注册资产时** `generateHistoricalKlines()`（`:208-248`）会基于 `SeedableRandom` 生成 TICK 序列并聚合到各周期（`:201`，`historyBars` ticks）。`klineCache`（`:102`）含每资产的 `TICK/QUARTER_DAY/HALF_DAY/DAILY/MONTHLY`（`:198`）。`getTechnicalKlines(assetId,type,limit)`（`:381-419`）在空态会从 TICK 重新聚合（`:383-393`），通常**能返回非空**。
  - **结论**：K线**数据在注册后一般非空**；因此「图表空白」根因**更可能在前端渲染与数据消费层**，而非后端 kline 数据缺失。**但有两类真实空态**需 t1 排查/修复：
    1. **period 映射缺口**：`getKlineJson`（`LocalHttpServer.java:307-345`）对 type 映射：0=TICK/1=QUARTER_DAY/2=HALF_DAY/4=DAILY/5=MONTHLY（`:316-323`）。若前端请求了后端**未填充**的周期（如大量 QUARTER_DAY/HALF_DAY 因 tick 数不足聚合为空，`aggregateToPeriod` 分组 `total/groupSize`——`:335` 当 `total<groupSize` 时 count=0 输出空），则该周期返回空数组 → 画不出来。
    2. **TICK 未推进**：若股票当日**无成交/未触发**，`klineCache` 的 TICK 末段无当期 bar，`getKlines(…, TICK)` 采用历史 bar → 分时图看起来「停在旧价」或无新 bar。
- **前端渲染层确认**：`html/index.html`：
  - `updateStockData(assetId, klines, tradePoints)`（`:1224`）**当 `!klines || klines.length===0` 直接 return**（`:1225`）→ 空则不绘制、不更新，图表保持初始空白。
  - `drawMainChart(cacheKlines, cacheTradePoints)`（`:1232`, 定义 `:1560`）绘制主图；`initCharts()`（`:1400-1404`）用 `echarts.init(#main-chart/'dark')`。
  - 数据来源：HTTP 模式 `fetch('/api/kline?id&type')`（`:2033`）→ `updateStockData(selectedStockId, d.klines, d.tradePoints)`（`:2034`）。
- **修复方案**：
  - **t1（数据保证）**：确保 `getTechnicalKlines` 对**任意请求周期**返回非空（tick 不足时回退到可用的较短周期序列，或保证 DAILY 至少含历史 bar）；保证当日发生过交易的资产 kline 含当期 bar。
  - **t2（前端渲染/数据消费）**：修复 `updateStockData` 的**空态防御**——空 klines 时仍应清空并给出「暂无数据」占位而非静默空白；校验 `drawMainChart` 是否在容器尺寸为 0 或数据异常时未能渲染；确认 `updateStockData` 传入的 `klines` 字段名（open/high/low/close/volume/amount/ma*/avgPrice，`PriceDiscoveryEngine:410-419` + `ClientMarketDataCache:227-246`——web 走 `getKlineJson` 的 `enhanced` 需确认含 `ma*/avgPrice`，`getKlineJson:325` 用 `getTechnicalKlines` 已含）；确认 echarts.min.js 资源已随 jar 打入（`index.html:7`）且路径可加载。

### BUG-5 多个 tab 区域无数据（UI 不完善）
- **根因**：`html/index.html` 底部分段 `买卖/委托/成交/持仓`（`:1370-1387` `updateTrades`、`:1327-1345` `updatePositions`、`:1347-1368` `updateOrders`）与左侧 `自选/个股列表/涨幅榜`（`:1764-1774`）虽定义了更新函数，但：
  - HTTP 模式 `pollTrade()`（`:2044-2049`）依赖 `GET /api/orders|positions|myTrades|account`，若这些**接口返回空/未实现**则 tab 空。
  - 板块栏/底部沪深指数条若数据源缺失则无数据显示。
- **修复方案**：
  - **t1**：确保 `web/LocalHttpServer` 的 `GET /api/orders|positions|myTrades|account|daily|company` 返回真实（绑定玩家）数据（沿用 `FRONTEND_THS_SPEC` §5.3/USER 上下文）。
  - **t2**：前端所有 tab 建立空态占位（`暂无...`），无数据不白屏不报错；板块栏/底部指数条接入真实数据源（见 §3）。

### 1.1 实测日志证据确认（captain 补充 · `F:\financialmarket\控制台log.txt`）
> 以下结论由运行客户端控制台日志实锤（此前多为推测），直接指向修复目标，优先处理。

**证据 A —— K线空（BUG-4 实锤）**：日志逐秒出现：
```
[getKlineJson] assetId=AI_21, type=DAILY, count=0
[getKlineJson] assetId=AI_22, type=DAILY, count=0
[getKlineJson] assetId=AI_28, type=DAILY, count=0   # …对全部 AI_00~AI_49 均 count=0
```
同时日志有大量 `AI_x N 笔成交`（MultiAgentManager 每 tick 下单）与 `成交: AI_xx price x qty`（真实撮合成交）。即**订单簿/撮合系统在交易，但 `/api/kline` 对 AI 股恒返回 0 根 DAILY K线** → 前端 eCharts 无数据可画。
- **机制候选（engine-dev t1 须逐一定位）**：
  1. `klineCache` 虽在 `PriceDiscoveryEngine.addAsset`（`:185-201`）里因 `generateHistoricalKlines` 生成历史 bar，但**成交/每 tick 未持续向 `klineCache[TICK]` 追加当期 bar**；且 `generateHistoricalKlines` 生成的 DAILY 分组依赖 `TICKS_PER_BAR`/`aggregateToPeriod`（`:335` `total/groupSize`），若**实际达到的 DAILY 分组数恒为 0**（`total < 240`），则 DAILY 空。
  2. `getKlines`/`getTechnicalKlines`（`:351-419`）在 DAILY 空时改从 TICK 重聚合（`:357-364`），若 **TICK 也为空**则全程返回 `emptyList` → `count=0`。
  3. 前端请求周期 `type` 映射（`LocalHttpServer.getKlineJson:316-323`）与后端 `KlineType` 兜底，若落在未填充周期也会空。
- **修复方向（t1 优先，让图画得出来）**：**只要有成交就推进 K 线**——每 tick 从 `OrderBook`/撮合成交结果向 `klineCache[TICK]` 追加 bar（量可为 0，价取当前 lastPrice），并保证 `generateHistoricalKlines` 至少产出 ≥1 根 DAILY；`getTechnicalKlines` 任一周期空态都**回退到可用周期**而非空数组。

**证据 B —— 盘口空（BUG-6 实锤）**：日志：
```
[getDepthJson] assetId=AI_21, bids=0, asks=0
[getDepthJson] assetId=AI_28, bids=0, asks=1   # …多数 bids=0，asks 偶 1/2/4
```
即 **`OrderBook.getSnapshot()` 对 AI 股几乎总是空**（bids=0，asks 稀疏）。原因：**AI 单多为市价撮合，成交后即消去、不留在盘口**，导致买卖五档无价量 → 前端盘口恒空。
- **修复方向**：让 AI（MultiAgentManager）也**挂限价单填盘口**（把部分买入/卖出以限价留在订单簿，形成连续买一~买五/卖一~卖五），或**前端对空盘口显示 `--`**（不白屏）。优先走「AI 挂限价单」以贴近同花顺盘口；若代价过大，至少满足「盘口空 → 前端 `--` 占位」。

**证据 C —— 涨跌幅恒 0（BUG-1 实锤）**：日志显示每只 AI 股初始价 20~39（如 `AI_0=37`、`AI_4=39`、`AI_10=20`），且此后仅有 `lastPrice`（价差演进），无任何 `dailyChange` 写入。`AssetSnapshot.setDailyChange`（`AssetSnapshot.java:50`）零调用 → `change` 恒 0。修复同 BUG-1（回调快照 dailyChange）。

**证据 D —— 指数恒 0（BUG-3 实锤）**：日志 `getIndexJson` 走 `getState().getIndexValue()`（null 桩）→ `value=0`；真源 `getIndexValueEx()` 已存在。修复同 BUG-3（改读真源）。

### 1.2 命名与价位校准（设计取舍 · 建议本阶段不强制改名）
- **事实**：`AI_00~AI_49` 是**虚构 AI 公司**，初始价 20~39，非真实 A 股。若参考图要求真实股名（泸州老窖 000568 / 贵州茅台 600519 / 沪深300ETF 510300 …），需考虑把 AI 股映射为真实/类真实名称与价位。
- **建议（分级，保持确定性）**：
  1. **本阶段（先修数据+UI）不强制改名**：仅修复 `kline/depth/dailyChange/index` 与图表/盘口/tab 渲染，用户截图现象即可消除。
  2. **可选外观映射层（不改引擎 ID）**：在**前端只读显示层**维护一张**确定性静态映射表** `AI_xx → {显示名, 行业, }`（如 `AI_0↔泸州老窖`、`AI_21↔贵州茅台`、`AI_xx↔沪深300ETF`…），仅改变列表/详情/盘口的**展示文本**；后端资产 ID 仍为 `AI_xx`（**H2/NBT 持久化零破坏**）。
  3. **若要改后端价位区间**：属引擎初始化改动且影响确定性序列与旧存档，**须先与用户确认再单独立项**；否则维持现价，只做显示层映射。
- **确定性约束**：映射表为**硬编码静态**（无随机），被映射资产 ID、价格序列、K线、涨跌幅、盘口均由引擎确定性产出；改名/改价不引入任何随机源，同种子可复现。

---

## 2. 修复后验收定义（每项可断言）

### 2.1 涨跌幅正确（F-AC1）
> **每只股票 `dailyChange` = (lastPrice − prevClose)/prevClose × 100%**，且随 `lastPrice` 每 tick/每成交更新。
- 断言：对任意有 `prevClose>0` 的资产，`state.getAsset(id).getDailyChange()` 恒等于 `(lastPrice-prevClose)/prevClose*100`（±0.01% 容差）；成交后该值变化且非 0。
- 前端：`/api/stocks` 每项 `change`（涨跌幅%）在价格变动后随时间变化；列表页 `0.00%` 消失。
- 边界：`prevClose==0` 时 `dailyChange=0`；新上市首日以 `ipoPrice` 为 prevClose（`MarketEngine:474`）。

### 2.2 高低/开收随交易更新（F-AC2）
> **`high/low/open/close(当日)` 随当日交易演进**，且为**当日**口径（非历史bar）。
- 断言：`high = max(当日所有 lastPrice)`、`low = min(当日所有 lastPrice)`、`open = 当日首笔成交价`、`prevClose = 昨日收盘`；`getDailyDataJson` 四值与快照单一来源一致。
- 前端：详情/统计区（`updateStats`）显示的当日高/低/开/昨收与 `getDailyDataJson` 一致；历史bar仅用于K线。

### 2.3 指数真源（F-AC3）
> **指数 `value` 来自 `engine.getIndexValueEx()`（`excellent24Index.getIndexValue()`）**，非 null 桩。
- 断言：`/api/index` 的 `value > 0` 且随 tick 变化；`changePoint/change` 为相对上一采样的真实增量。
- 前端：顶部指数条显示 `优经24` 真实值 + 涨跌点 + 涨跌幅，红涨绿跌。

### 2.4 kline 非空（F-AC4）
> **已注册资产对请求周期返回非空 K 线序列**。
- 断言：对任意资产、任意 `type∈[0,1,2,4,5]`，`getTechnicalKlines` 返回 ≥1 根 bar（空态回退到可用周期仍非空）；`/api/kline` 的 `klines` 长度 > 0，且含 `open/high/low/close/volume`（及主图需要的 `ma*/avgPrice`）。

### 2.5 图表正确绘制（F-AC5）
> **eCharts 主图(candlestick+MA+买卖点)与副图(成交量柱+指标)能正确渲染**，周期切换/缩放/十字线正常。
- 断言：`#main-chart`/`#sub-chart` 渲染非空白 canvas；切换周期（分时/日K/周K/月K）重新拉取并重绘；数据不空时不白屏。空数据时显示占位而非错误。
- 前端修复收敛为：`drawMainChart` 成功绘制 + `updateStockData` 对非空输入不早退。

### 2.6 各 tab 有数据（F-AC6）
> **自选/个股列表/涨幅榜、买卖/委托/成交/持仓、板块栏、底部沪深指数**均有数据（或明确空态占位）。
- 断言：`/api/orders|positions|myTrades|account` 对绑定玩家返回真实列表；前端各 tab 渲染数据并支持排序/联动；指数条与大盘/板块有值。

### 2.7 确定性 + 持久化不回归（F-AC7）
> 同种子两次运行 → 快照、K线、涨跌幅、指数数值序列**一致**；H2/NBT 读旧档不崩、账本不重置。
- 断言：固定 `engineSeed` 跑 N tick 两次，`state` 全部 `AssetSnapshot` 字段与 `getTechnicalKlines` 序列逐根相等；旧存档加载后 `prevClose/high/low/open` 按缺省回退，不抛异常、不归零账本。

---

## 3. UI 临摹范围（同花顺风格，红涨绿跌）

> 参照用户提供参考图 + `FRONTEND_THS_SPEC`「五区布局」；前端对**从后端取到的真实字段**做表达，不自行预测/再随机。

| 区域 | 组件 | 数据源（web/桥接） | 字段 | 红涨绿跌 |
|---|---|---|---|---|
| 顶部 | 大盘指数条 `优经24` | `GET /api/index` / `updateIndex` | `value, changePoint, change` | ✔ |
| 左侧 | 自选股 / 个股列表 / 涨幅榜 三视图 + 可排序表头（代码/名称/现价/涨跌幅/成交量） | `GET /api/stocks` / `updateMarketList` | `stockId, name, price, change, volume, sparkline` | 现价/涨跌幅 ✔ |
| 中部 | info-bar(名称/现价/涨跌幅/MA5/10/20/60)；周期按钮（分时/日K/周K/月K）；主图 candlestick + MA + 买卖点；副图成交量柱 + 指标（MACD/KDJ/RSI/BOLL）；dataZoom/十字线 | `GET /api/kline?id&type` / `updateStockData` | `klines[{time,open,high,low,close,volume,amount,ma5/10/20/60,avgPrice,...}], tradePoints[{time,price,isBuy}]` | 实体/量柱 ✔ |
| 右侧 | 五档盘口：卖5..卖1 / 买1..买5（价+量），点击填价(F9)，委比 | `GET /api/depth?id` / `updateDepth` | `bids/asks:[[price,qty],...]`，委比 | 买/卖档色 ✔ |
| 底部 | 交易面板 Tab：买卖下单（手/限价/买入/卖出/市价）、委托（撤单）、成交、持仓（浮盈） | `GET/POST /api/order|cancelOrder|orders|positions|myTrades|account` | 见 `FRONTEND_THS_SPEC` §5.3 | 浮盈红涨绿跌 ✔ |
| 底部 | 沪深指数条/板块栏 | index 真源 + 板块聚合 | `value, change` | ✔ |

- **配色**：涨红 `#F04747`、跌绿 `#43B581`（沿用 `:root` 变量 `--color-up-cn/--color-down-cn`），固定 CN，不做美/中切换。
- **空态**：无数据时 `--` 或「暂无数据」，不白屏、不 JS 报错。
- **不新增随机**：前端零随机；仅表达后端确定性数据。

---

## 4. 确定性 / 持久化边界（固化）

1. **随机边界**：新增任何数值生成只能取自 `SeedableRandom` 子流（`engine/random/SeedableRandom.java`），同种子可复现；**禁** `Math.random`/`new Random()`。若 t1 需补齐当日 bar 或历史随机补样，必须走既有 `engineRng`/`gbm.rng` 子流，并保证与既有序列不冲突（同种子前后两次一致）。
2. **核心公式不可改动**：A 股 ±10% 涨跌停、T+1、一手 100 股、价格+时间优先、经济引擎/法币 M2/黄金公式**一字不改**；只读快照与真源接入，不新增交易/定价语义。
3. **快照/持久化**：`MarketState.save/load` 与 H2/NBT 边界不破坏；新增快照字段（high/low/open/dailyChange/bid/ask）为**内存态滚动值**，读旧档缺字段按缺省回退（`prevClose` 回退 lastPrice、high/low/open 回退 lastPrice、dailyChange=0），**不重置账本、不抛异常**。
4. **只读接入点**：所有修复都是「回写 `AssetSnapshot` 字段 / 反转 `getIndexValueEx` 真源 / 保证 kline 非空」，不改变 `submitOrder`/`OrderBook`/`PriceDiscoveryEngine.price` 输入输出语义。
5. **前端零随机零预测**：前端只按后端返回渲染，不二次撮合/预测，不引入随机源。

---

## 5. 实施与验收对照（供 scheduler / t1 / t2 / t4）

| 验收 | 责任 | 关联根因 | 验证方式 |
|---|---|---|---|
| F-AC1 涨跌幅 | t1 | BUG-1 | 断言 dailyChange 断言 + `/api/stocks.change` 变化 |
| F-AC2 高低/开收 | t1 | BUG-2 | `getDailyDataJson` 四值 = 当日口径 |
| F-AC3 指数真源 | t1 | BUG-3 | `/api/index.value>0` 且变 |
| F-AC4 kline 非空 | t1 | BUG-4 | `getTechnicalKlines` 各周期非空 |
| F-AC5 图表绘制 | t2（数据由 t1 保证） | BUG-4 | `#main-chart` 渲染非空白；`updateStockData` 不早退 |
| F-AC6 各 tab 有数据 | t2（数据由 t1 保证） | BUG-5 | 各 tab 渲染/空态占位 |
| F-AC7 确定性+持久化 | t1+t2 | 全局 | 同种子复跑一致；旧档不崩 |

---

## 6. 非目标（防范围蔓延）

- **不做**：美股/美中风格切换；移动端；图表库替换（沿用 eCharts）；后端撮合/A股规则/经济公式/持久化格式的任何改动；H2 迁移。
- **不动**：`engine/` 核心经济引擎、`Attachment/PlayerEconomy`、法币 M2/黄金子系统、`H2_MIGRATION` 既有语义；既有 `/api/*` 只读结构（仅允许增量字段/修字段来源）。
- **确定性**：前端零新随机；HTTP/Bridge 只读表达；交易写入只走后端权威路径。
