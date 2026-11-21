# FRONTEND_THS_SPEC — 同花顺 PC 式前端交易界面需求规格（需求契约，供 t2/t3 前端实施、t4/t5 后端实施、t6 验证、t7 评审）

> - 角色：architect（方案架构 / 前端需求规格，同花顺 PC 对照）为任务 t1 产出。
> - 输入：团队 Goal + 当前源码审计（本规格对照实际源码给出文件/方法/字段/默认参数）。已交付前置基座：`A_SHARE_TRADING_SPEC.md`（A 股交易规则：时间模型/±10%涨跌停/T+1/一手100股/价格+时间优先撮合，后端 engine 层）、`ECON_P0/P1/P2_SPEC.md`、`H2_MIGRATION.md`。
> - 用途：frontend-dev 按本规格实现 t2（同花顺 PC 式 HTML/CSS 布局重构）与 t3（JS 交互逻辑：红涨绿跌/键盘选股/五档填价/指标周期切换/交易面板）；backend-dev 按本规格的「桥接与 API 契约」扩展 t4（LocalHttpServer 交易接口 + javaBridge）与 t5（下单/撤单/持仓/成交网络链路）；test-engineer 按本规格实现 t6（前端整体验证 + 构建）；reviewer 按本规格验收 t7。
> - 约束：前端为 `src/main/resources/assets/financialmarket/html/` 下的**纯 HTML/CSS/JS**（无打包器，NeoForge 资源 + `LocalHttpServer` 静态服务），同花顺 PC 深色专业看盘风格；**红涨绿跌（CN 风格）**；数据源双轨：`javaBridge`（游戏内 WebView 桥接，主路径）+ `LocalHttpServer` HTTP API（独立浏览器/验证，兜底路径）。交易语义一律走后端 A 股规则（`A_SHARE_TRADING_SPEC.md`），前端**只负责表达与提交，不重复实现撮合/校验**。
> - **可复现/隔离硬约束**：前端展示层不做任何随机、不改变引擎确定性；交易请求必须语义化透传后端 A 股规则（休市拒绝、±10% 涨跌停、T+1 冻结可用、一手 100 股整数倍、价格/时间优先），前端校验仅为**即时反馈（UX）**，最终权威在后端。H2 兼容由后端负责，前端不得触碰持久化格式。

---

## 0. 目标与全局验收（对应团队 Goal）

> 把现有 Financial Market 前端从「javaBridge 单轨、交易存 stub」升级为**同花顺 PC 专业看盘/交易软件**式界面：顶部大盘指数条、左侧可排序行情列表（自选/涨幅榜）、中部 K 线主图 + 成交量/指标副图 + 分时/日周月切换、右侧五档盘口（买五卖五）联动、底部交易面板（买入/卖出、委托、成交、持仓、撤单）；实现红涨绿跌、键盘输入代码快捷选股、五档点击填价、指标/周期切换、javaBridge 桥接与 LocalHttpServer API 扩展、下单/持仓/撤单链路打通。完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| F0 | 编译/资源不破坏构建 | `./gradlew build` 通过；前端资源打包进 jar（`html/` 下文件随资源打进），HTTP 静态服务可加载 | 每项实施 + t6 |
| F1 | **同花顺 PC 式布局** | 页面呈现五区：顶部大盘指数条、左侧行情列表、中部 K 线区、右侧五档盘口、底部交易面板；深色专业看盘配色，默认自适应桌面分辨率（≥1280×720） | t2 + t6 |
| F2 | **红涨绿跌** | 涨/跌用色与同花顺 CN 一致：上涨=红（`#F04747`）、下跌=绿（`#43B581`）；应用于现价、涨跌幅、K 线实体、成交量柱、五档、指数、持仓浮盈。**不提供美/中切换**（本版固定 CN，同花顺 PC 简体默认） | t3 + t6 |
| F3 | **顶部大盘指数条** | 顶部一条实时展示「优经24」（`Excellent24Index`）指数值、涨跌点数、涨跌幅；指数刷新与行情同步 | t3 + t6 |
| F4 | **左侧可排序行情列表（自选/涨幅榜）** | 左列支持两种视图：自选（当前 `/api/stocks` 全量即自选）与涨幅榜；表头（代码/名称/现价/涨跌幅/成交量）**点击排序**（升/降切换）；行点击选中并联动中部 K 线与右侧盘口；价格变动闪烁 | t2 + t3 + t6 |
| F5 | **中部 K 线主图 + 成交量/指标副图 + 周期切换** | 主图 ECharts candlestick（OHLC + MA5/10/20/60 + 买卖点）；副图成交量柱（红涨绿跌）+ 可选 MACD/KDJ/RSI/BOLL 指标；周期切换（分时/日K/周K/月K，本模组支持 TICK/日/月，前端按后端 `type` 语义映射） | t3 + t6 |
| F6 | **右侧五档盘口（买五卖五）联动** | 展示买一~买五、卖一~卖五（价格/数量），红涨绿跌；点击任一档位**自动填入交易面板的限价输入框**；盘口与选中股票联动 | t3 + t6 |
| F7 | **底部交易面板（买入/卖出、委托、成交、持仓、撤单）** | 底部 Tab 面板含：买卖下单（数量/限价/买入/卖出/市价）、委托列表（可撤单）、成交明细、持仓列表；下单/持仓/撤单链路打通 | t3 + t4 + t5 + t6 |
| F8 | **键盘输入代码快捷选股** | 全局（非输入框内）键入数字代码→顶部弹出候选/直接匹配→回车确认选中股票；支持拼音/代码前缀匹配（可按代码段匹配当前列表） | t3 + t6 |
| F9 | **五档点击填价** | 点击买/卖档位把该档价格填入交易面板限价框（UX 联动） | t3 + t6 |
| F10 | **指标/周期切换** | 指标按钮（成交量/MACD/KDJ/RSI/BOLL）可切换/叠加；周期按钮切换后端 K 线类型并重绘 | t3 + t6 |
| F11 | **javaBridge 桥接** | 游戏内 WebView：javaBridge 推送行情/盘口/持仓/订单/成交，前端 `request*` 触发 Java 端回复；**关闭按钮 `closeScreen()` 生效**；桥接缺失时静默占位不报错 | t3 + t4 + t6 |
| F12 | **LocalHttpServer API 扩展** | HTTP 端新增交易 API（下单/撤单/持仓/订单/成交/账户），前端经 HTTP 可完成完整交易链路（浏览器可独立使用/验证） | t4 + t5 + t6 |
| F13 | **下单/持仓/撤单链路打通** | 前端「买入/卖出」→ 后端 A 股校验（休市/涨跌停/T+1/一手/资金）→ 撮合/挂单 → 委托列表刷新 → 可撤单 → 成交/持仓更新，全链路可操作可验证 | t3 + t4 + t5 + t6 |
| F14 | **保持经济引擎/A 股规则语义 + 同种子可复现 + H2 兼容** | 前端只读行情 + 提交请求，不引入任何新随机源、不改引擎确定性；交易语义权威在后端（`A_SHARE_TRADING_SPEC.md`）；前端不触碰持久化格式，读档/H2 兼容由后端保证不回归 | 全部 + t6 |

**验收流程（t6 落地）**：`./gradlew build` 出包（F0）；加载 HTTP `/`（`LocalHttpServer` 端口 8080）与游戏内 tablet WebView 两种入口，静态核对五区布局（F1）；固定 `engineSeed` 跑固定 tick 两次，前端展示的行情/成交序列与后端引擎输出一致（确定性不回归，F14）；逐项手工/脚本验证 F2~F13（红涨绿跌、排序、联动、填价、选股、指标周期、桥接、HTTP 交易链路）。

---

## 1. 总设计原则（约束所有实现）

1. **纯 HTML/CSS/JS 单页**：沿用 `src/main/resources/assets/financialmarket/html/index.html` 为入口（可整体重写，或拆分为 `style.css`/`app.js` 由 `LocalHttpServer` 静态服务加载——参考 `LocalHttpServer.java:40-51` 的 `/`、`.css`、`.js` 路由）。**无 Node/打包器**，仅原生 JS + ECharts CDN（当前 `index.html:7` 引入 echarts@5.4.3）。
2. **双轨数据源抽象**：前端定义统一的行情/交易数据层，向上接 UI，向下接两个 provider：
   - `javaBridge`（游戏内，`window.javaBridge`，当前由外部 WebView 注入，`index.html:304-312` 已建占位防御）——**主路径**，适合游戏内玩家上下文（下单自动带当前玩家）。
   - `LocalHttpServer` HTTP API（独立浏览器/验证）——**兜底/验证路径**，适合无 WebView 时展示与自动化验证。
   - 二选一自动：`window.javaBridge` 存在且有真实方法（非占位）→ 用桥接；否则 → 用 HTTP 轮询。
3. **展示与交易语义分离**：前端计算涨跌幅/颜色/排序/填价等**纯展示逻辑**；所有**交易合法性**（休市、涨跌停、T+1、一手整数倍、资金）由后端判定并返回结果，前端把结果消息原样显示。
4. **确定性不回归**：前端零随机（禁用 `Math.random` 等新引入随机）；K 线/行情按后端返回渲染，不做二次预测。同种子后端确定性 → 前端展示确定性。
5. **布局即语义**：同花顺 PC 五区布局（F1）是核心交互骨架，左右/中/下的联动是验收重点；面板可滚动、可读，数据缺失显示 `--` 占位不白屏。

---

## 2. 现状审计（对照实际源码）

### 2.1 前端现状（`html/index.html`，657 行）
- **布局**：已有 topbar（tabs：股票/商品/持仓/公司/借贷/量化/云图）、`#main-container`（左 330px 自选、中 K 线 + 副图、右 280px 五档）、footer 单指数。**缺**：大盘指数条（在 header/footer 单一处，非顶部独立条）、左侧排序（列表仅 `updateMarketList` 渲染，无表头排序、无涨幅榜）、底部交易面板（买卖表单在右栏而非底部 Tab 面板）、委托/成交/持仓/撤单面板。
- **数据源**：**纯 javaBridge 单轨**。`window.onload`（`index.html:637-654`）把 `updateMarketList/updateIndex/updateStockData/updateDepth/updateTick/updatePortfolio/updateCompany` 挂到 `window.javaBridge`；`requestKline/requestDepth/requestTick/requestCompany` 为空实现（`index.html:648-651`）。**全文件无 `fetch`/`XMLHttpRequest`/`/api/` 调用** → 独立浏览器打开不显示任何数据。
- **交易**：`executeTrade`（`index.html:621-632`）仅 `alert` 模拟，**未**接 `PlaceOrderPacket`/HTTP。下单/持仓/撤单链路在 Web 端完全未打通。
- **桥接方法清单（现有）**：`closeScreen, updateMarketList, updateIndex, updateStockData, updateDepth, updateTick, updatePortfolio, updateCompany, requestKline`（`index.html:305` 占位清单）。

### 2.2 后端现状（`LocalHttpServer.java`，416 行）
- **静态服务**：`/`、`/index.html`、`.css`、`.js`（`LocalHttpServer.java:40-51`）。
- **只读 GET API**：`/api/stocks`（`:54`，list，含 `stockId,name,price,change,industry,roe,eps,pe,nav,revenueGrowth,dividendYield,sparkline`）、`/api/index`（`:55`，`{value,change}`）、`/api/kline?id&type`（`:56`，`{klines,tradePoints}`，type 映射见 `:205-213`：0=TICK/1=QUARTER_DAY/2=HALF_DAY/4=DAILY/5=MONTHLY）、`/api/depth?id`（`:62`，`{bids,asks}`，`[[price,qty],...]`）、`/api/tick?id`（`:66`，`{ticks}`）、`/api/company?id`、`/api/fundamentals?id`、`/api/trades?id&limit`（`:78`）、`/api/daily?id`（`:84`）。
- **缺**：无 POST 下单/撤单；无持仓/委托/成交（个人）/账户 API；`/api/index` 的 `change` 恒为 0（`:193`），指数无涨跌。

### 2.3 网络包现状（`network/packets/`）
- 已有：`PlaceOrderPacket`（含 `MARKET_BUY/SELL/LIMIT_BUY/LIMIT_SELL` 语义、`isBuy/quantity/price`、`send()` 提交到服务端，`PlaceOrderPacket.java`）、`PortfolioSyncPacket`（`{holdings,cash}`）、`OrderBookSnapshotPacket`（`{assetId,bids,asks}`，Level=`{price,quantity}`）、`MarketListPacket`、`StockDataResponsePacket`、`RequestMarketListPacket/RequestPortfolioPacket/RequestKlinePacket/RequestStockDataPacket` 等。
- **缺**：撤单 packet、个人订单列表 packet、个人成交列表 packet（`t5` 后端补齐；前端契约见 §5.2/§5.3）。

---

## 3. AS-1 同花顺 PC 式布局与配色（t2 前端实施）

> 目标：重写/扩展 `html/index.html` 为同花顺 PC 专业看盘五区布局 + 红涨绿跌深色主题。

### 3.1 页面骨架（五区）
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ 顶栏（可选 logo/入口 + 现金余额 + 关闭按钮）                                     │
├──────────────────────────────────────────────────────────────────────────────┤
│ 大盘指数条（F3）：[优经24 值 涨跌点 涨跌幅]  ← 顶部独立一条                       │
├──────────────────┬─────────────────────────────────────────┬──────────────────┤
│ 左侧行情列表(F4)   │  中部 K 线区(F5)                        │ 右侧五档盘口(F6)    │
│ [自选|涨幅榜] tab  │  info-bar(名称/现价/涨跌/MA5/10/20/60)     │  卖5..卖1(价/量)    │
│ [代码][名称][现价]  │  [分时][日K][周K][月K] 周期按钮             │  现价/涨跌          │
│ [涨跌幅][成交量]    │  主图 candlestick + MA + 买卖点             │  买1..买5(价/量)    │
│ （表头可点击排序）   │  副图：成交量柱 + [MACD][KDJ][RSI][BOLL]    │  成交明细(可选)     │
│  行闪烁/选中高亮    │  （dataZoom 缩放/十字线）                   │  点档填价(F9)       │
├──────────────────┴─────────────────────────────────────────┴──────────────────┤
│ 底部交易面板(F7)：[买卖下单|委托|成交|持仓]  Tab 面板                              │
│  买卖：数量(手) 限价  [买入][卖出][市价买][市价卖]                                │
│  委托：orderId 代码 方向 价格 数量 剩余 状态 [撤单]                                │
│  成交：时间 代码 方向 价格 数量                                              │
│  持仓：代码 数量 可用 成本 现价 市值 浮盈/浮亏                                        │
└──────────────────────────────────────────────────────────────────────────────┘
```
- **深色主题变量**：沿用并统一为 `:root` CSS 变量（见 `index.html:9-25`）：`--bg-app`、`--bg-panel`、`--color-text-primary/secondary/muted`、`--color-up-cn:#F04747`（红）、`--color-down-cn:#43B581`（绿）、`--color-accent`、`--color-border`。**红涨绿跌固定 CN**（F2）。
- **响应式下限**：≥1280×720 桌面；窗口缩小时面板可裁剪但不可白屏/溢出崩溃（`overflow` 与 `flex` 控制）。

### 3.2 组件清单（t2 交付）
- 大盘指数条组件（`.index-bar`）：值 + 涨跌点 + 涨跌幅，红涨绿跌。
- 左侧列表组件（`.market-list`）：视图切换 tab（自选/涨幅榜）+ 可排序表头 + 行渲染（代码/名称/现价/涨跌幅/成交量/迷你 sparkline）+ 选中态 + 涨跌闪烁（沿用 `index.html:79-82` `flash-up/flash-down`）。
- 中部图表容器（`.chart-area`）：info-bar、周期按钮组、`#main-chart`、`#sub-chart`、指标工具栏（沿用 `index.html:116-129`）。
- 右侧盘口组件（`.depth-panel`）：卖五~卖一、买一~买五，每行价格/数量；点击回调填价（F9）。
- 底部交易面板组件（`.trade-panel`）：4 个 Tab 子视图（买卖/委托/成交/持仓），表单 + 列表 + 撤单按钮。
- 键盘选股浮层（`.quick-search`）：非输入框内键入数字/拼音时弹出的匹配候选列表（F8）。
- **空态**：无数据时对应区域显示 `--` 或「暂无数据」（不 JS 报错、不白屏）。

---

## 4. AS-2 前端交互逻辑（t3 前端实施）

> 目标：在布局之上实现红涨绿跌、键盘选股、五档填价、指标/周期切换、交易面板操作、双轨数据源与联动的纯 JS 逻辑。

### 4.1 数据层与双轨切换（F11/F12）
- 定义 `DataProvider` 抽象，两个实现：
  - `JavaBridgeProvider`：调 `window.javaBridge.requestKline/requestDepth/requestTick/requestCompany`（现有）+ 新增 `requestOrders/requestPositions/requestTrades/placeOrder/cancelOrder`（§5.2 契约）；监听 `javaBridge.updateMarketList/updateIndex/updateStockData/updateDepth/updateTick/updatePortfolio/updateOrders/updatePositions/updateTrades/updateTradingResult`（占位防御沿用 `index.html:304-312` 模式）。
  - `HttpProvider`：`fetch` 轮询 `LocalHttpServer` 各 `/api/*` 端点（§5.1），下单/撤单走 POST（§5.3）。
- **自动选择**：`onload` 时检测——`window.javaBridge` 存在且至少有一个非占位方法（或显式全局配置 `window.__USE_BRIDGE`）→ 桥接；否则 → HTTP 轮询（默认 1s 行情、2s 盘口/持仓/订单、按需请求 K 线/成交）。两种 provider 复用同一套 UI 更新函数，保证布局与交互一致。
- **轮询节流**：HTTP 轮询在页面可见时进行，`document.hidden` 时暂停；避免过度请求。

### 4.2 红涨绿跌（F2）
- 统一工具函数 `colorFor(v)`（v≥0 红、v<0 绿）与 `signed(v)`（+/- 前缀）。应用于：列表现价/涨跌幅、指数条、K 线实体与边框、成交量柱（`close>=open` 红/绿，沿用 `index.html:563` 逻辑）、五档、持仓浮盈、info-bar 涨跌。
- 与后端 `change` 语义一致（`LocalHttpServer` `snap.getDailyChange()`）。

### 4.3 左侧排序（F4）
- 表头列（代码/名称/现价/涨跌幅/成交量）点击 → 升序/降序切换（三角箭头指示）；再点同列反向。
- 视图「自选」= 后端全量（`/api/stocks` 或 `updateMarketList`）；「涨幅榜」= 按 `change` 降序排列（同数据排序视图）。
- 排序/视图切换不重新拉数据（纯前端重排当前列表快照），但选中/联动保持不变。
- 行点击 `selectStock(id)`：联动中部 K 线（请求 K 线）与右侧盘口（请求 depth）与 info-bar（沿用 `index.html:609-619` 交互，补 `requestDepth` 已在现版本实现）。

### 4.4 键盘代码快捷选股（F8）
- 全局 `keydown`（非输入框内，判定 `document.activeElement` 非 `<input>`）：连续数字输入累积成「代码输入缓冲」。
- 缓冲长度 ≥3 时弹出候选浮层：匹配当前列表 `stockId` 前缀（代码段）与名称；**回车**确认第一候选/唯一匹配并 `selectStock`；`Esc` 清空；超时（如 3s 无输入）清空缓冲。
- 候选匹配不区分大小写，支持代码前缀 + 名称包含。
- 若与 `javaBridge` 模式冲突（游戏内 WebView 的键盘捕获），`Esc`/回车处理不得吞噬其他快捷键。

### 4.5 五档点击填价（F9）
- 盘口每档（买/卖）行可点击：点击把该档价格写入底部交易面板「限价」输入框，并给该行临时高亮反馈。
- 买一/卖一在选中股票、拉取 depth 后默认作为限价框的初始建议价（沿用 `index.html:585` 用收盘价填价的做法，改为用最佳盘口价；盘口缺失时回退现价）。

### 4.6 指标/周期切换（F5/F10）
- 周期按钮：`分时(type0) / 日K(type4) / 周K / 月K(type5)`。后端 KlineType 支持 TICK(0)/QUARTER_DAY(1)/HALF_DAY(2)/DAILY(4)/MONTHLY(5)（`LocalHttpServer.java:205-213`）。前端映射：
  - 「分时」→ type 0
  - 「日K」→ type 4
  - 「周K」→ 后端若无周K专用类型，前端聚合日K为周K（纯展示聚合）或映射到最接近类型（实现时选定一种并注明）
  - 「月K」→ type 5
  - 「1/4日」「1/2日」保留可选（type 1/2），默认不展示。
- 切换周期 → `requestKline(id,type)`（桥接）或 `fetch /api/kline?id&type`（HTTP），收到 `updateStockData`/K 线响应后重绘主图+副图。
- 指标工具栏：成交量（恒显副图柱）+ 可叠加 MACD/KDJ/RSI/BOLL（沿用 `index.html:468-480` 的 toggle 逻辑；`activeIndicators` 数组驱动副图/主图叠加序列）。

### 4.7 底部交易面板（F7）
- **买卖下单视图**：
  - 字段：数量（手，默认 100 股 → 显示为 1 手）、限价（默认买一/卖一/现价）、买入/卖出按钮 + 市价买/市价卖。
  - 前端即时校验（UX）：数量>0、价格>0；买入数量为 100 整数倍（提示，但不阻断提交——权威在后端）。手↔股换算（1 手=100 股，对齐 A 股 BOARD_LOT，`A_SHARE_TRADING_SPEC.md` §5）。
  - 提交：桥接 → `javaBridge.placeOrder(assetId,isBuy,quantity(股),price)`（price≤0 表市价，对齐 `PlaceOrderPacket.java:46`）；HTTP → `POST /api/order`（§5.3）。提交后刷新委托/持仓。
- **委托视图**：列表（orderId、代码、方向、价格、数量、剩余、状态）；每行「撤单」按钮 → `javaBridge.cancelOrder(orderId)` 或 `POST /api/cancelOrder`（§5.3），成功后刷新委托。
- **成交视图**：个人成交明细（时间、代码、方向、价格、数量）。
- **持仓视图**：持仓（代码、数量、可用(可卖)、冻结、成本、现价、市值、浮盈/浮亏，红涨绿跌）。
- 所有列表带滚动、空态占位；状态来自后端（`updateOrders/updatePositions/updateTrades` 或 HTTP 轮询）。

### 4.8 交易结果反馈
- 后端下单/撤单返回结果（成功/失败 + 中文消息，如「休市中」「超出涨跌停」「T+1 冻结」「买入须 100 股整数倍」）原样 toast/内联展示；成功则联动刷新委托/持仓/现金。

---

## 5. AS-3 javaBridge 与 LocalHttpServer API 契约（t4/t5 后端实施）

> 目标：让前端能够经桥接与 HTTP 两条路径完成完整交易链路。**契约先行，前后端对齐；后端负责 A 股语义权威与玩家上下文解析。**

### 5.1 沿用（不改）的只读 API
- `GET /api/stocks`、`GET /api/index`、`GET /api/kline?id&type`、`GET /api/depth?id`、`GET /api/tick?id`、`GET /api/company?id`、`GET /api/fundamentals?id`、`GET /api/trades?id&limit`、`GET /api/daily?id`（`LocalHttpServer.java:54-84`）。前端原样消费。
- **改进（可选，向后兼容）**：`GET /api/index` 增加真实涨跌（`change` 不再恒 0），并补充指数涨跌点数（`changePoint`）；字段为增量（默认值），旧结构仍兼容。

### 5.2 javaBridge 新增契约（t4 后端 + t3 前端占位）
> 桥接为「Java 端→JS 推送」+「JS→Java 请求」双向。**t3 前端先建占位方法**（沿用 `index.html:304-312` 防御），t4 后端在 WebView 桥接层实现注入。

- **JS→Java（前端调用，后端须接收）**：
  - `requestOrders()` → Java 回复 `updateOrders`
  - `requestPositions()` → Java 回复 `updatePositions`
  - `requestTrades()` → Java 回复 `updateTrades`
  - `requestPortfolio()`（已有 `requestPortfolio` 隐含，按需显式化）→ 回复 `updatePortfolio`
  - `placeOrder(assetId, isBuy, quantity, price)`：quantity 为**股**；price≤0 表市价。后端走 `PlaceOrderPacket` 同路径（`PlaceOrderPacket.java:34-53` → `engine.submitOrder`），并复用 A 股校验。
  - `cancelOrder(orderId)`：后端按玩家 open order 撤销，成功则拒绝/移除该单。
  - `closeScreen()`（已有）：关闭 WebView。
- **Java→JS（Java 推送，前端消费）**：
  - `updateMarketList(list)` / `updateIndex(value, changePoint, changePct)` / `updateStockData(assetId, klines, tradePoints)` / `updateDepth(assetId, bids, asks)` / `updateTick(assetId, ticks)` / `updateCompany(data)` / `updatePortfolio(holdings, cash)`（均已有，`index.html:305`）——**沿用，不改签名**（保持兼容）。
  - `updateOrders(orders)`：`[{orderId, assetId, name, side:"buy"|"sell", type:"market"|"limit", price, quantity, remaining, status, time}]`
  - `updatePositions(positions)`：`[{assetId, name, quantity, available, frozen, avgCost, lastPrice, marketValue, profit, profitPct}]`
  - `updateTrades(trades)`：`[{time, assetId, name, side:"buy"|"sell", price, quantity}]`
  - `updateTradingResult(success, message, orderId?)`：下单/撤单结果反馈。
- 数组顺序、字段命名与 §5.3 HTTP 保持一致（前后端共用同一 DTO 语义）。

### 5.3 LocalHttpServer 新增交易 API（t4/t5 后端实施）
> **玩家上下文问题**：HTTP 服务无 Minecraft 玩家上下文。t4 需定义「HTTP 交易绑定玩家」机制，默认方案：配置项 `web.tradingPlayer`（UUID 或玩家名，默认=服务器 owner/首个真实玩家），或 `POST /api/session {playerName|uuid}` 绑定当前 HTTP 会话。交易请求在绑定的玩家身份下执行 A 股校验与记账（对齐 `PlaceOrderPacket.handle` 的 `context.player()` 语义）。**选择与实现由 backend 决定，但必须可配置、可复现、H2 兼容。**

- **只读新增**：
  - `GET /api/account` → `{playerName, cash, totalMarketValue, totalAssets}`
  - `GET /api/orders` → `[{orderId, assetId, name, side, type, price, quantity, remaining, status, time}]`（当前绑定玩家 open/全部委托）
  - `GET /api/positions` → `[{assetId, name, quantity, available, frozen, avgCost, lastPrice, marketValue, profit, profitPct}]`
  - `GET /api/myTrades` → `[{time, assetId, name, side, price, quantity}]`（绑定玩家成交）
- **写入新增**（POST，body `application/json`）：
  - `POST /api/order` body `{assetId, side:"buy"|"sell", type:"market"|"limit", quantity(股), price}` → `{success, message, orderId?}`；复用 A 股校验（休市/涨跌停/T+1/一手/资金），失败返回中文 message。
  - `POST /api/cancelOrder` body `{orderId}` → `{success, message}`。
- **错误响应**：HTTP 层对参数错误返回 400 + `{success:false, message}`；后端语义拒绝（休市等）返回 200 + `{success:false, message}`（与桥接 `updateTradingResult` 语义一致）。
- **校验权威**：所有下单/撤单最终调用 `engine.submitOrder` / 撤单逻辑（与 `PlaceOrderPacket` 同路径），保证 HTTP 与游戏内行为一致（A 股规则、确定性、H2）。

### 5.4 网络包补充（t5 后端实施，供桥接/游戏内链路）
- 新增个人订单列表 / 个人成交列表 / 撤单 packet（如 `CancelOrderPacket`、`OrdersSyncPacket`、`TradesSyncPacket`），前端在游戏内经桥接消费。**不删除既有 packet 字段**（兼容）。
- 撤单在 `MarketEngine`/`OrderBook` 提供确定性取消逻辑（同种子可复现，对齐 `A_SHARE_TRADING_SPEC.md` §6 价格/时间优先，撤销即从队列移除）。

---

## 6. 数据契约（前端消费的字段统一）

| 域 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 行情列表 | `stockId, name, price, change, volume, sparkline` | string, string, number, number, number, number[] | `/api/stocks` 已有；`sparkline` 用于迷你图 |
| 指数 | `value, changePoint, change` | number | `value` 现指、`change` 涨跌幅%、`changePoint` 涨跌点（增量） |
| K 线 | `klines:[{time,open,close,high,low,volume,amount,ma5,ma10,ma20,ma60,macdDif,macdDea,macdHist,kdjK,kdjD,kdjJ,rsi,bollUp,bollMid,bollLow,avgPrice}]`, `tradePoints:[{time,price,isBuy}]` | — | `/api/kline` 已有结构，`index.html:497-554` 消费 |
| 盘口 | `bids/asks:[[price,qty],...]`（各≤5 档） | number[][] | `/api/depth` 已有，`index.html:401-425` 消费 |
| 持仓 | `assetId,name,quantity,available,frozen,avgCost,lastPrice,marketValue,profit,profitPct` | — | §5.2/§5.3 |
| 委托 | `orderId,assetId,name,side,type,price,quantity,remaining,status,time` | — | §5.2/§5.3 |
| 成交 | `time,assetId,name,side,price,quantity` | — | §5.2/§5.3 |
| 账户 | `playerName,cash,totalMarketValue,totalAssets` | — | §5.3 |

- 数值统一 number；缺失字段用 `--` 占位；`side` 用 `"buy"/"sell"`；`status` 用 `"open"/"filled"/"partial"/"cancelled"`（后端定枚举，前端只展示）。

---

## 7. 实施顺序与依赖（供 scheduler）

```
t1 (本规格) ──→ t2 (布局重构 HTML/CSS) ──→ t3 (JS 交互 + 双轨 + 交易面板)
        │
        └──→ t4 (LocalHttpServer 交易 API + javaBridge 注入 + 玩家绑定)
                └──→ t5 (下单/撤单/持仓/成交网络包 + 链路打通) ──→ t6 (前端整体验证+构建) ──→ t7 (评审)
```
- **依赖**：t2 依赖 t1；t3 依赖 t2；t4 依赖 t1（API 契约）与后端 A 股基座（`A_SHARE_TRADING_SPEC.md`）；t5 依赖 t4；t6 依赖 t2/t3/t4/t5；t7 依赖 t6。
- **前端先占位**：t3 在 t4/t5 就绪前即建立 javaBridge 占位 + HTTP provider 与交易面板，后端补齐后自动接通（契约 §5 对齐）。

---

## 8. 与非目标边界（防范围蔓延）

- **不做**：美股/美/中风格切换（本版固定 CN 红涨绿跌）；移动端适配（目标桌面）；图表库替换（沿用 ECharts）；后端撮合/校验逻辑的任何改动（前端不重复实现 A 股规则，只提交与表达）；T+0/做空特例 UI（融资融券保留现有 `MarginOrderPacket` 入口即可，不在本规格新增）。
- **不动**：`MarketEngine`/`OrderBook`/`PriceDiscoveryEngine`/经济引擎/A 股规则语义（`A_SHARE_TRADING_SPEC.md`）；H2/NBT 持久化格式（前端零触碰）；既有 `/api/*` 只读 API 结构（仅允许增量字段）。
- **确定性**：前端零新随机；HTTP 轮询只读展示，不改变引擎状态；交易写入只走后端权威路径。

---

## 9. 对 t6（前端整体验证 + 构建）的需求

1. **构建验证（F0）**：`./gradlew build` 通过，前端资源打包进 jar。
2. **布局验证（F1/F2/F3）**：加载 `LocalHttpServer` `/`（8080）静态核对五区布局、红涨绿跌、指数条刷新。
3. **列表/联动（F4/F5）**：左侧排序（升/降）、涨幅榜、行点击联动 K 线与盘口；K 线主图 + 成交量 + 指标 + 周期切换正常。
4. **盘口/填价/选股（F6/F8/F9）**：五档展示、点击填价、键盘代码选股（候选/回车）。
5. **交易面板/链路（F7/F12/F13）**：经 HTTP 完成 买入→委托→撤单→成交→持仓 全链路；后端 A 股校验消息正确返回（休市/涨跌停/T+1/一手）。
6. **桥接（F11）**：游戏内 tablet WebView 经 javaBridge 完成同类操作（或验证占位不报错 + 后端注入方法存在）。
7. **确定性不回归（F14）**：固定 `engineSeed` 两次运行，前端展示的行情/成交序列与后端一致（无新增随机）。

---

## 10. 输入/来源索引

- 团队 Goal（同花顺 PC 五区 + 红涨绿跌 + 键盘选股 + 五档填价 + 指标周期 + javaBridge/LocalHttpServer 扩展 + 下单持仓撤单链路）
- 代码证据：`src/main/resources/assets/financialmarket/html/index.html`、`src/main/java/com/example/financialmarket/web/LocalHttpServer.java`、`network/packets/PlaceOrderPacket.java`、`PortfolioSyncPacket.java`、`OrderBookSnapshotPacket.java`、`MarketListPacket.java`、`StockDataResponsePacket.java`、`screen/FinancialTabletScreen.java`
- 既有规格：`A_SHARE_TRADING_SPEC.md`（A 股交易规则语义，权威）、`ECON_P0/P1/P2_SPEC.md`、`H2_MIGRATION.md`
- 现实参照：同花顺 PC 专业看盘/交易软件五区布局（顶部指数条、左侧列表、中部 K 线、右侧五档盘口、底部交易面板）与红涨绿跌配色。
