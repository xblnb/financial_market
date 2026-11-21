# FRONTEND_COMPLETE_SPEC — Financial Market 前端全量标签页完善需求规格（需求契约，供 t2/t3/t4 前端实施、t3 后端 API 补齐、t5 验证、t6 评审）

> - 角色：architect（方案架构 / 前端页面需求规格，专业软件对照）为任务 t1 产出。
> - 输入：团队 Goal + 当前源码审计（本规格对照实际源码给出文件/方法/字段/默认参数）。
> - 已交付前置基座：`FRONTEND_THS_SPEC.md`（同花顺 PC 式五区 + 红涨绿跌，**本规格复用其主题变量/数据层/trading 链路约定，不推翻**）、`A_SHARE_TRADING_SPEC.md`（A 股交易规则）、`COMMODITY_FUTURES_SPEC.md`（商品/期货）、`COMPANY_SPEC.md`（公司系统）、`BANKING_TRADE_SPEC.md`（银行/借贷/法币 M2）、`P2_SPEC.md`/`MACRO_MODEL.md`/`ECON_*`（宏观/微观）、`H2_MIGRATION.md`（H2/持久化）。
> - 用途：frontend-dev 按本规格实现 t2（补齐 5 个缺页容器的布局，`portfolio/company/lending/quant/marketmap` + 统一外壳）与 t4（7 列表页交互/轮询/数据接入）；backend-dev 按本规格 §5 的「API 契约」补齐 t3（公司列表、银行/借贷、量化、云图聚合端点）；test-engineer 按本规格实现 t5（整体验证 + 构建）；reviewer 按本规格验收 t6。
> - 约束：前端纯 HTML/CSS/JS（无打包器，NeoForge 资源 + `LocalHttpServer` 静态服务），深色专业软件风；**红涨绿跌（CN，`#E03131`/`#2FA96B`，沿用 `index.html:24-25`）**；沿用双轨数据源（`javaBridge` 主 + `LocalHttpServer` HTTP 兜底，`index.html:1673-1720` 既有 `enableHttpMode`）；交易语义一律走后端权威，前端只表达与提交，不重复实现撮合/校验。
> - **可复现/隔离硬约束**：前端展示层零随机、不改变引擎确定性；前端不得触碰 H2/NBT 持久化格式；H2 兼容由后端负责。所有新增 HTTP 端点**只读或走既有确定性服务入口**，不改经济引擎语义（`PriceDiscoveryEngine.step`/`A_SHARE_*`/`SettlementSystem`/`submitOrder` 一律不动）。

---

## 0. 目标与全局验收（对应团队 Goal）

把现有前端从「**仅 stocks/commodities/futures 三页有容器**」补齐为**全部 8 个标签页（股票、商品、期货、持仓、公司、借贷、量化、云图）都有独立页面容器、接后端 API、交互统一、布局贴近对应专业软件**（同花顺/交易终端/银行客户端/公司研究/量化平台/大盘云图）。完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| G0 | 编译/资源不破坏构建 | `./gradlew build` 通过；`html/` 下前端资源打包进 jar，HTTP `/` 静态可加载 | 全部 + t5 |
| G1 | 全标签页有独立容器 | 8 个 Tab 点击后都显示对应 `#page-*` 容器，**无一空白/报错/裸 main-container**；非股票页隐藏 `#index-bar` 与 `#main-container`（沿用 `switchPage` 语义并**修复它对 5 个缺页的遗漏**） | t2/t4 + t5 |
| G2 | 统一外壳与风格 | 各页复用同一深色主题变量、`.page-bar`/`.page-scroll`/表格/账户条/Tab/空态/红涨绿跌组件；配色/字体/间距与股票页一致 | t2/t4 + t5 |
| G3 | 行情页（股票/商品/期货）不回归 | stocks 五区（F1~F14，`FRONTEND_THS_SPEC.md`）、commodities、futures 现有功能与交互不破坏；仅做外壳/风格统一与联动增强 | t4 + t5 |
| G4 | 持仓页（交易终端式） | 资产总览条（现金/总市值/总资产）+ 持仓明细表（含 A 股/期货/保证金/杠杆多账户）+ 委托 + 成交 + 账户权益，红涨绿跌，可展开/排序；数据接 `/api/portfolio`、`/api/positions`、`/api/orders`、`/api/myTrades`、`/api/account`、`/api/margin`、`/api/futures/positions`、`/api/leverage/positions` | t2/t4 + t5 |
| G5 | 公司页（公司研究式） | 上市公司列表（可排序/搜索/按行业筛选）+ 公司详情（财务指标/估值/行业）+（可选）我的公司（IPO/股权/财报）管理；接 `GET /api/companies`（**t3 新增**）+ 既有 `/api/company?id`、`/api/fundamentals?id`、`/api/stocks` | t2/t4 + t3 + t5 |
| G6 | 借贷页（银行客户端式） | 账户总览（活期/定期/货币市场存款、负债、信用、NPL）+ 银行列表（各家存款利率/贷款利率/准备金/权益）+ 贷款申请表单 + 我的贷款列表（可提前还款）+ 央行/政府债券/同业拆借行情；接 `/api/banking`、`/api/loans`、`/api/loan/apply`、`/api/loan/repay`、`/api/banks`、`/api/bonds`（**t3 新增**） | t2/t4 + t3 + t5 |
| G7 | 量化页（量化平台式） | 策略列表（启停/回测/运行状态）+ 策略编辑（脚本/参数）+ 最近生成订单/收益曲线；接 `/api/quant/strategies`、`/api/quant/strategy`、`/api/quant/run`、`/api/quant/status`（**t3 新增**） | t2/t4 + t3 + t5 |
| G8 | 云图页（大盘云图式） | 行业/个股热力图（Treemap，按涨跌幅红/绿着色）+ 大盘广度（涨跌家数/涨停跌停/成交分布/量能柱）+ 宏观仪表（指数/利率/M2/情绪）；**前端由 `/api/stocks` + `/api/index` 计算**，可选 `/api/marketmap` 聚合端点（**t3 可选新增**） | t2/t4 (+t3) + t5 |
| G9 | 交互统一 | 所有页数据缺省显示 `--`/「暂无数据」不白屏不 JS 报错；红涨绿跌全局一致；表格表头可排序；金额/涨跌格式化一致（沿用 `_fmt2`/`_colCls`/`_spark`，`index.html:1731-1740`） | t2/t4 + t5 |
| G10 | 桥接 + HTTP 双轨 | 游戏内 WebView 走 `javaBridge`（推送 + `request*`），独立浏览器走 HTTP 轮询；两轨复用同一套渲染函数；桥接缺失静默占位（沿用 `index.html:695-705` 防御） | t4 + t5 |
| G11 | 确定性/引擎语义不回归 + H2 兼容 | 前端零随机、只读展示 + 提交权威请求；不改经济引擎语义；新增 HTTP 只读/交易写入都走既有确定性服务入口；H2/NBT 格式由后端保持前向兼容（前端零触碰） | 全部 + t5 |

**验收流程（t5 落地）**：`./gradlew build` 出包（G0）；8 个 Tab 逐个点击，断言对应容器显示且无空白/报错（G1）；固定 `engineSeed` 跑固定 tick 两次，各页展示的行情/持仓/公司/银行/量化序列与后端引擎输出一致（确定性不回归，G11）；逐项手工/脚本验证 G4~G8（各页布局、排序、联动、下单/贷款/启停策略/热力图着色）+ 桥接 / HTTP 双轨（G10）。

---

## 1. 总设计原则（约束所有实现）

1. **纯 HTML/CSS/JS 单页**：沿用 `src/main/resources/assets/financialmarket/html/index.html`（已 1896 行）为唯一入口。允许在该文件内拆分 `style.css`/`app.js` 或继续内联，**但不引入 Node/打包器**，仅原生 JS + ECharts（`echarts.min.js`）。`LocalHttpServer` 需能静态服务拆出的 `.css`/`.js`（`LocalHttpServer.java:40-51` 已有 `/`、`.css`、`.js` 路由，可复用）。
2. **统一外壳（G2）+ 每页独立容器（G1）**：在 `#main-container`（股票五区）之外，按现有 `#page-commodities`/`#page-futures` 模式补齐 `#page-portfolio`/`#page-company`/`#page-lending`/`#page-quant`/`#page-marketmap` 五个 `.page` 容器。`switchPage(page)`（`index.html:1849-1858`）改为**通用映射表**驱动，任一 Tab 都正确显隐对应容器，其余隐藏（修复当前对 5 缺页的遗漏）。
3. **双轨数据源抽象（G10）**：沿用 `enableHttpMode`（`index.html:1675-1720`）与 `javaBridge` 推送/请求（`index.html:1887-1892` 自动检测）。各页数据统一走 `fetch('/api/*')` 或 `window.javaBridge.updateXxx/requestXxx`；**渲染函数唯一**，两轨复用。
4. **展示与语义分离**：前端只做格式化/排序/着色/空态等**纯展示**；一切交易/贷款/启停策略的合法性由后端判定返回中文结果，前端原样显示（`updateTradingResult` 式反馈，`index.html:1046-1052`）。
5. **确定性不回归（G11）**：前端零随机（禁用 `Math.random`）；行情/持仓/公司/银行/量化按后端返回渲染。
6. **统一交互组件**：提供可复用的「表头可排序表」「账户条」「Tab 切换」「空态占位」「红涨绿跌着色」「金额格式化」，保证全页一致（G9）。
7. **布局即语义**：各页骨架对齐对应专业软件（见 §4 每页 ASCII 骨架）；数据缺失一律 `--`/「暂无数据」。

---

## 2. 现状审计（对照实际源码）

### 2.1 前端现状（`html/index.html`，1896 行）
- **Tab 定义**：`#tab-bar`（`index.html:416-425`）8 个 Tab：`stocks/commodities/futures/portfolio/company/lending/quant/marketmap`。
- **已有页面容器**：`#main-container`（股票五区，`index.html:451-575`）、`#page-commodities`（`index.html:636-651`）、`#page-futures`（`index.html:654-676`）。**缺** `#page-portfolio`/`#page-company`/`#page-lending`/`#page-quant`/`#page-marketmap` 五个容器。
- **`switchPage` 缺陷**（`index.html:1849-1858`）：仅处理 `stocks/commodities/futures`；对 `portfolio/company/lending/quant/marketmap` 点击后：`index-bar` `display:none`、`main-container` `display:none`、commodities/futures `display:none` → **主体空白**（正是 Goal 所述「点击空白/报错」）。
- **行情/交易链路**：股票五区 + 红涨绿跌 + 排序 + 五档填价 + 键盘选股 + 指标周期均已完成（`FRONTEND_THS_SPEC.md` F1~F14）；商品/期货列表 + 下单已接 `/api/commodities`、`/api/commodity/order`、`/api/futures`、`/api/futures/positions`、`/api/futures/order`（`index.html:1726-1865`）。
- **数据源**：HTTP 模式已有轮询（`enableHttpMode`，`index.html:1675-1720`）；`javaBridge` 占位防御（`index.html:695-705`）。
- **交易面板**：买卖下单/委托/成交/持仓 Tab（`index.html:578-633`），对应 `updatePositions`/`updateOrders`/`updateTrades`/`updateTradingResult`（`index.html:984-1052`）。

### 2.2 后端现状（`LocalHttpServer.java`，1219+ 行，28 个 `/api/*` 上下文，见 `createContext` 清单 `:91-148`）
- **股票/行情**：`/api/stocks`（list，含 `stockId,name,price,change,industry,roe,eps,pe,nav,revenueGrowth,dividendYield,sparkline`）、`/api/index`（`{value,change}`）、`/api/kline`、`/api/depth`、`/api/tick`、`/api/company?id`、`/api/fundamentals?id`、`/api/trades`、`/api/daily`。
- **交易/账户（A 股）**：`/api/account`、`/api/positions`、`/api/portfolio`、`/api/orders`、`/api/myTrades`、`/api/session`、`/api/order`、`/api/cancelOrder`。
- **商品/期货**：`/api/commodities`、`/api/futures`、`/api/futures/positions`、`/api/commodity/order`、`/api/futures/order`。
- **杠杆/保证金**：`/api/margin`、`/api/margin/deposit`、`/api/leverage/notes`、`/api/leverage/positions`、`/api/leverage/issue`、`/api/leverage/order`。
- **缺（t3 需补齐，供新页消费）**：**公司列表**（`/api/companies`）、**银行/借贷**（`/api/banking`、`/api/loans`、`/api/loan/apply`、`/api/loan/repay`、`/api/banks`、`/api/bonds`）、**量化**（`/api/quant/strategies`、`/api/quant/strategy`、`/api/quant/run`、`/api/quant/status`）、**云图可选项**（`/api/marketmap`）。

### 2.3 可复用的后端模型（字段契约来源，t3 据此实现）
- **公司**：`engine/ai/AICompanyManager.getAll()` → `List<AICompanyData>`（industry/roe/eps/netAssetValue/revenueGrowth/dividendYield/bookValuePerShare/fairValue/totalShares/ipoPrice/profitabilityScore/financialHealthScore，见 `LocalHttpServer.java:360-416` 既有 `getCompanyJson`/`getFundamentalsJson`）；玩家公司 `company/CompanyManager`（`CompanyData`：owner/name/stockId/level/status/ipoState/netAssetValue/floatShares/retainedEarnings/shareholders/reports，见 `COMPANY_SPEC.md`）。
- **银行/借贷**：`engine/banking/{Bank,Loan,BankCardService,CreditSystem,BankManager,CentralBank,InterbankMarket,GovernmentBond}`（`Bank.getBankId/getName/getVaultCash/getReserves/getTotalDeposits/getTotalLoans/getEquity/getReserveRequirement/getPrimeRate/getDepositRate/getLoans`，`Loan.getLoanId/getBorrowerId/getPrincipal/getRemaining/getAnnualRate/getTermDays/getStatus/getCollateralValue`，`CreditSystem.getTotalPrivateDebt/getNplRatio/getCreditGrowth`，`BankManager.getAllBanks/getCentralBank/getInterbankMarket`，见 `BANKING_TRADE_SPEC.md`）。
- **量化**：`engine/quant/{QuantAPI,QuantScriptEngine,Strategy}`（`Strategy.getPlayerId/getStrategyName/getFastPeriod/getSlowPeriod/isEnabled/getKind/getScriptId`，`QuantAPI.enableStrategy/disableStrategy/getStrategy/executeStrategies`，`QuantScriptEngine.compile/evaluate/getScriptIds`，见 `engine/quant/*`）。

---

## 3. 统一外壳与页面框架（t2/t4 实现）

### 3.1 `switchPage` 通用化（G1 关键修复）
```js
// 现有：switchPage 仅处理 stocks/commodities/futures（index.html:1849-1858）
// 改为：通用映射表驱动
function switchPage(page) {
  document.querySelectorAll('#tab-bar .tab').forEach(t => t.classList.toggle('active', t.dataset.page === page));
  document.getElementById('index-bar').style.display  = (page === 'stocks') ? '' : 'none';
  document.getElementById('main-container').style.display = (page === 'stocks') ? '' : 'none';
  // 所有 .page 容器统一显隐（无则隐藏）
  ['commodities','futures','portfolio','company','lending','quant','marketmap'].forEach(p => {
    const el = document.getElementById('page-' + p);
    if (el) el.style.display = (page === p) ? 'flex' : 'none';
  });
  // 页面加载回调（各页懒加载数据）
  if (page === 'commodities') loadCommodities();
  if (page === 'futures')     loadFutures();
  if (page === 'portfolio')   loadPortfolio();
  if (page === 'company')     loadCompanies();
  if (page === 'lending')     loadLending();
  if (page === 'quant')       loadQuant();
  if (page === 'marketmap')   loadMarketmap();
}
```
- 顶部 `#index-bar`（大盘指数条）仅在股票页显示（沿用现有语义）。
- 底部 `#footer` 与顶部现金 `#header-cash` 在所有页保留（全局状态）。
- **页面容器全部为 `.page`**（`display:none` 默认，激活为 `flex`），共享 `.page-bar`/`.page-scroll`/`.page-title`/`.page-tip` 样式（`index.html:388-410` 已有）。

### 3.2 共享样式/组件（G2/G9）
- 复用 `:root` 主题变量（`--bg-app`、`--bg-panel`、`--color-up-cn:#E03131`、`--color-down-cn:#2FA96B`、`--color-accent`、`--color-border`，`index.html:10-32`）。
- 新增可复用 class（放同一 `<style>` 内）：`.fm-table`（表头可排序，`th[data-sort]` 点击升降，箭头指示）、`.fm-account-bar`（账户条：名称+金额，红涨绿跌）、`.fm-tabs`（页内 Tab）、`.fm-empty`（空态）、`.fm-badge`（状态徽章）。**不覆盖**股票页既有 class（`stock-row`/`data-table`/`page-btn` 等），新增类只针对新页，避免破坏 G3。
- 格式化工具沿用/扩展：`_fmt2(v)`（保留 2 位）、`_colCls(v)`（红/绿）、`_spark(points)`（迷你趋势，`index.html:1731-1740`）；新增大数格式化 `fmtBig(v)`（`≥1e8 → X.XX亿`，`≥1e4 → X.XX万`，与 `index.html:799` 一致）。

### 3.3 统一交互约定（G9）
- 每个列表页提供：表头排序（升/降切换，红涨绿跌列定制刷新）、搜索过滤（`input` `oninput` 本地过滤当前快照，不重新拉数据）、行点击联动详情、空态「暂无数据」。
- 页内 Tab 切换：纯前端显隐，不重新拉数据（数据在 `load*` 时一次性拉取，`page-scroll` 内局部刷新）。
- 数据刷新：HTTP 模式依 `document.hidden` 节流轮询（沿用 `enableHttpMode` 的 `tick`，`index.html:1707-1715`）；新页各自 `load*` 函数在 `setInterval`（约 2s，与 commodities/futures 一致，`index.html:1862-1865`）仅当该页可见时轮询。

---

## 4. 各页需求（布局 = 专业软件式 + 数据 + 交互）

> 每条给出：页面容器 id、`.page-bar` 标题/说明、骨架、数据端点、交互要点。**下列所有新端点契约见 §5**；已知端点直接消费。

### 4.1 持仓页（`#page-portfolio`，交易终端/资产全景式，G4）
骨架：
```
┌──────────────────────────────────────────────────────────────────────────┐
│ 资产总览条(账户)：现金 | 总市值 | 总资产 | 可用保证金 | 冻结 | 权益         │
├──────────────────────────────┬───────────────────────────────────────────┤
│ 左：分类持仓表(可展开/排序)     │ 右：账户明细(委托/成交/资金流)              │
│  [全部|A股|期货|保证金|杠杆]Tab  │   [委托|成交] Tab                         │
│  代码 名称 数量 可用 现价 市值   │   委托：orderId 代码 方向 价 量 剩余 状态[撤单]│
│  成本 盈亏(元) 盈亏%(红/绿)      │   成交：时间 代码 方向 价格 数量             │
├──────────────────────────────┴───────────────────────────────────────────┤
│ 底部：账户权益明细(账户 盈亏 规模) + 资金汇总                               │
└──────────────────────────────────────────────────────────────────────────┘
```
- **数据**（已知端点）：`GET /api/portfolio`（`{playerName,cash,totalMarketValue,totalAssets,positions:[...]}`，`LocalHttpServer.java:556`）、`GET /api/positions`、`GET /api/orders`、`GET /api/myTrades`、`GET /api/account`、`GET /api/margin`、`GET /api/futures/positions`、`GET /api/leverage/positions`。
- **账户条**：现金（`cash`）、总市值（`totalMarketValue`）、总资产（`totalAssets`）来自 `/api/portfolio`；保证金（freeCash/usedMargin/frozenMargin/equity）来自 `/api/futures/positions` 或 `/api/margin`；红涨绿跌着色（浮盈红/浮亏绿）。
- **分类持仓表**：默认`全部`合并 `positions`(A 股) + `futures/positions`(期货) + `margin/positions`(保证金) + `leverage/positions`(杠杆)；Tab 切换只显示对应来源；`代码/名称/数量/可用/现价/成本/市值/盈亏/盈亏%`，盈亏%列红涨绿跌，表头可排序（现价/市场/盈亏）。
- **可选细化**：`data-source` 字段标记来源（如 `a_share`/`futures`/`margin`/`leverage`），供展开/筛选；无数据行显示 `--`。
- **委托/成交 Tab**：复用股票页的 `updateOrders`/`updateTrades` 渲染表格（`index.html:1004-1044`），撤单按钮走 `POST /api/cancelOrder`（已存在）。
- **交互**：行点击可定位到对应资产并联动到股票页（`selectStock`）——建议在股票页 `#center-panel` 打开；本页点持仓行切换高亮 + 展示该持仓所属账户的盈亏明细。

### 4.2 公司页（`#page-company`，上市公司研究式，G5）
骨架：
```
┌──────────────────────────────────────────────────────────────────────────┐
│ 上市公司 · 研究（搜索代码/名称 | 行业下拉 | 排序）                          │
├────────────────────────────────┬─────────────────────────────────────────┤
│ 左：上市公司列表(可排序/搜索)      │ 右：公司详情(基本面+估值)                  │
│  代码 名称 行业 现价 涨跌% 市盈率  │  info-bar：名称 现价 涨跌% 行业           │
│  （行点击选中 + 高亮 + 闪烁）      │  关键指标：PE/PB/EPS/ROE/营收增长/股息率   │
│                                 │  资产表：净资产 市值 内在价值 总股本         │
│                                 │  评分：盈利能力 / 财务健康 / 成长阶段        │
│                                 │  [我的公司] 子面板(可选)：IPO/股权/财报      │
└────────────────────────────────┴─────────────────────────────────────────┘
```
- **数据**：`GET /api/companies`（**t3 新增**，返回全部公司摘要 `[{stockId,name,industry,price,change,pe,pb,eps,roe,nav,revenueGrowth,dividendYield,marketCap,growthStage}]`）；`GET /api/company?id`（详情，既有）、`GET /api/fundamentals?id`（既有）、`GET /api/stocks`（行情/涨跌）。
- **右栏详情**：复用 `updateCompany`（`index.html:955-976`）的表单布局（`f-pe/f-pb/f-eps/f-roe/f-rev-gr/f-div/f-fair/f-shares/f-profit/f-health`），但作为独立详情面板而非股票页右栏。
- **我的公司子面板（可选，G5 协同增强）**：对玩家有持仓/持有的公司（`owner==playerId` 或 `shareholders` 含当前玩家），展示 `status/level/ipoState/ipoPrice/floatShares/netAssetValue/retainedEarnings` + 股权结构 + 最近财报；操作（`发起上市/认购/发放分红/查看财报`）走 `<action>` 或调用后端命令（`/api/company/action`，**t3 可选新增**，见 §5.2）。标注为协同增强，服务端已有 `COMPANY_SPEC.md` 命令/packet，前端接入不改变引擎语义。
- **交互**：列表搜索（代码/名称）、行业下拉过滤（取 `industry` 去重）、表头排序（现价/涨跌/市盈率/市值）、行点击选中联动右栏详情。

### 4.3 借贷页（`#page-lending`，银行客户端式，G6）
骨架：
```
┌──────────────────────────────────────────────────────────────────────────┐
│ 银行与借贷 · 个人/公司金融（红涨绿跌 + 账户条：现金/存款/负债/信用额度）      │
├──────────────────────────────┬───────────────────────────────────────────┤
│ 左：银行列表(各家)              │ 右：我的账户                             │
│  名称 存款利率 贷款利率 准备金率  │  活期存款 | 定期存款 | 货币市场 | 负债      │
│  总存款 总贷款 权益 储备           │  信用分 | NPL | 信贷增速 | 总私人债务     │
├──────────────────────────────┼───────────────────────────────────────────┤
│ 贷款申请表单：金额/期限/抵押/利率  │ 我的贷款列表(可提前还款)                   │
│  [申请贷款]                      │  贷款号 金额 剩余 利率 期限 状态[还款]      │
├──────────────────────────────┴───────────────────────────────────────────┤
│ 央行/政府/同业：贴现率 准备金率 M0/M1/M2 政府债券(面值/票息/到期) 同业利率    │
└──────────────────────────────────────────────────────────────────────────┘
```
- **数据**（**t3 新增**，见 §5.3）：`GET /api/banks`（银行列表 `[{bankId,name,depositRate,loanRate,primeRate,reserveRequirement,totalDeposits,totalLoans,equity,vaultCash,reserves}]`）、`GET /api/banking`（我的账户 `{cash,demandDeposits,timeDeposits,moneyMarketDeposits,totalDebt,creditScore,nplRatio,creditGrowth,collateralValue}`）、`GET /api/loans`（我的贷款 `[{loanId,bankId,principal,remaining,annualRate,termDays,status,collateralValue,startDay}]`）、`GET /api/bonds`（政府债券 `[{bondId,faceValue,couponRate,maturityDays,currentValue,status}]`）、`GET /api/macro`（`{discountRate,reserveRequirement,m0,m1,m2,interbankRate,totalPrivateDebt,nplRatio,creditGrowth}`，可用 `CentralBank`/`BankManager.computeM0/M1/M2`/`getInterbankRate`/`CreditSystem` 派生）。
- **写入**（**t3 新增**）：`POST /api/loan/apply` body `{bankId, amount, termDays, collateralValue}` → `{success,message,loanId?}`；`POST /api/loan/repay` body `{loanId, amount}` → `{success,message}`。后端走 `Bank.issueLoan`/`Bank.repayLoan` 确定性路径，校验（可贷额度/抵押/限价利率）权威在后端，失败返回中文 message。
- **布局参照**：银行客户端左「业务列表」右「账户/交易明细」两栏 + 中部贷款申请；底栏展示央行/同业宏观（金融数据终端风）。
- **交互**：银行行选中联动右侧（该行存款/贷款/权益）；贷款申请提交 → 刷新`/api/loans`+`/api/banking`；还款按钮 → `POST /api/loan/repay`；红涨绿跌用于利率变动/资产变动（如信用分/NPL 越高越警示用黄/红）。

### 4.4 量化页（`#page-quant`，量化策略平台式，G7）
骨架：
```
┌──────────────────────────────────────────────────────────────────────────┐
│ 量化策略 · 工作台（账户：现金/权益 | 启用状态）                              │
├────────────────────────────┬─────────────────────────────────────────────┤
│ 左：策略列表                 │ 右：策略详情/编辑                              │
│  名称 类型 快/慢周期 状态[启停] │  参数：快周期 慢周期 策略类型(均线/脚本) [保存] │
│  （行选中 + 启用开关）         │  脚本编辑：code 输入框 [编译/保存/重新加载]      │
├────────────────────────────┴─────────────────────────────────────────────┤
│ 底部：运行状态 / 最近生成订单 / 收益曲线                                       │
│  run 状态(启用中/已停用) | 生成订单(时间/资产/方向/价/量) | 累计收益率曲线       │
└──────────────────────────────────────────────────────────────────────────┘
```
- **数据**（**t3 新增**，见 §5.4）：`GET /api/quant/strategies` → `[{strategyId,playerId,name,kind,fast,slow,enabled,scriptId}]`；`GET /api/quant/status?strategyId` → `{enabled,lastRunTick,generatedOrders:[{assetId,side,price,quantity,time}],pnl,pnlPct,equityCurve:[number]}`；`GET /api/quant/script?scriptId` → `{scriptId,fileName,code}`；`POST /api/quant/strategy` body `{name,kind,fast,slow}`；`POST /api/quant/strategy/enable`/`disable` body `{strategyId}`；`POST /api/quant/script` body `{scriptId,code}`（保存/编译）；`POST /api/quant/run` body `{strategyId}`（手动运行一次，`QuantAPI.executeStrategies`）。
- **布局参照**：量化平台左「策略/信号」右「参数/代码」+ 底部「回测/运行结果」。
- **交互**：行选中联动详情；启用开关切 `enable`/`disable`；脚本编辑「编译/保存」→ `POST /api/quant/script`，返回编译结果；「运行」→ `POST /api/quant/run` 并刷新 status（生成订单 + 收益曲线）；空态「暂无策略，请新建」。

### 4.5 云图页（`#page-marketmap`，大盘云图式，G8）
骨架：
```
┌──────────────────────────────────────────────────────────────────────────┐
│ 大盘云图 · 热力（行业/个股 Treemap，按涨跌幅红/绿着色）                      │
├───────────────────────────┬──────────────────────────────────────────────┤
│ 中部：ECharts Treemap      │ 右：大盘广度                                   │
│  (行业矩形=行业avg涨跌)      │  涨跌家数(涨/跌/平) | 涨停/跌停 | 成交额分布    │
│  (点击行业钻取个股矩形)       │  量能柱(成交量分时段) | 行业热点(前5强/弱)    │
├───────────────────────────┴──────────────────────────────────────────────┤
│ 底：宏观仪表(指数 优经24 涨跌 | 贴现率 | M2 | 市场情绪 | 交易状态)             │
└──────────────────────────────────────────────────────────────────────────┘
```
- **数据**：**前端主算**——`GET /api/stocks`（每只 `change` 着色，按 `industry` 分组算行业均值/家数）+ `GET /api/index`（指数值/涨跌，`change` 现不为 0 时可用，`updateIndex` 已兼容 `{value,changePoint,change}`）。广度（涨跌家数/涨停跌停/成交分布）由前端对 `/api/stocks` 统计。宏观仪表：指数来自 `/api/index`；`M2/贴现率/交易状态` 若有 `CentralBank` 数据可选由 `GET /api/macro`（§5.3 复用）提供；否则 `--` 占位。
- **可选后端聚合**：`GET /api/marketmap`（**t3 可选新增**，见 §5.5）返回 `{industry:[{name,avgChange,count,upCount,downCount,totalVol}], breadth:{up,down,flat,limitUp,limitDown,totalVol,totalAmount}, indices:[...]}`，前端优先消费，缺失则本地计算 `/api/stocks`。
- **图表**：ECharts `treemap`（`series:[{type:'treemap', data:[{name,value,itemStyle...}]}]`），`name=行业`,`value=该行业股票市值或数量`，`itemStyle.color` 按 `avgChange` 红/绿（`#E03131` 涨 / `#2FA96B` 跌，`#3A4A5A` 平）。点击行业矩形 drill-down 到个股，个股矩形按 `change` 着色、`value=市值`、tooltip 显示代码/名称/涨跌/成交量。右侧广度用数字 + 柱状图（成交量分时段或行业排序柱）；热力图与广度联动（点击行业高亮）。
- **交互**：hover 高亮 + tooltip；点击行业钻取/返回；右上「按行业/按个股」切换；`setInterval` 可见时刷新（约 2s）；无数据显示空态。

---

## 5. 后端 API 契约（t3 后端实施）

> 供 backend-dev 补齐 `LocalHttpServer` 端点。**沿用既有只读/交易端点**（§2.2），本表只列**新增**。所有端点在 `LocalHttpServer` 已注入的 `MarketEngine eng / CompanyManager companyManager / BankManager* / QuantAPI*`（或经 `eng` 暴露的 getter）上取数据；只读端点不得改变引擎状态；写入端点走既有确定性服务入口。H2/持久化由后端维护，**本契约不定义持久化格式**。

### 5.1 公司列表与详情（t3）
- `GET /api/companies` → `[{stockId,name,industry,price,change,pe,pb,eps,roe,nav,revenueGrowth,dividendYield,marketCap,growthStage}]`。全部公司 = `eng.getAiCompanyManager().getAll()`（既有 `getCompanyJson` 字段，`LocalHttpServer.java:360-416`）合并逐只 `lastPrice` 与 `change`（复用 `getStockListJson` 逻辑，`:220-260`）。`pe=eps>0?price/eps:0`、`pb=bvps>0?price/bvps:0`、`marketCap=price*totalShares`。若后端有玩家公司（`companyManager.getAllCompanies()`），可附加 `isPlayerCompany/status/level/ipoState` 字段（默认可选）。
- `GET /api/company/action`（可选，玩家公司操作）body 或 query `{action, param...}` → `{success,message}`；走 `COMPANY_SPEC.md` §8.2 命令语义（`ipo/subscribe/levelup/...`），复用 `CompanyActionPacket` 的确定性逻辑。**注明为协同增强**，后端命令/packet 已是权威，HTTP 仅作桥接。

### 5.2 银行/借贷（t3）
- `GET /api/banks` → `[{bankId,name,depositRate,loanRate,primeRate,reserveRequirement,totalDeposits,totalLoans,equity,vaultCash,reserves,demandDeposits,timeDeposits,moneyMarketDeposits}]`（`BankManager.getAllBanks()` + `Bank` getters）。
- `GET /api/banking` → `{playerName,cash,demandDeposits,timeDeposits,moneyMarketDeposits,totalDebt,creditScore,nplRatio,creditGrowth,collateralValue,interbankRate}`（绑定玩家；存款/贷款取自 `BankCardService`/`Loan`/`PlayerEconomy`，信用指标取 `CreditSystem`，同业利率取 `InterbankMarket.getInterbankRate()`）。
- `GET /api/loans` → `[{loanId,bankId,principal,remaining,annualRate,termDays,status,collateralValue,startDay}]`（绑定玩家名下 `Loan`，`Bank.loans` ∩ 玩家 borrower；`status`=`LoanStatus`）。
- `GET /api/bonds` → `[{bondId,faceValue,couponRate,maturityDays,currentValue,status}]`（`GovernmentBond`，`currentValue=calculateCurrentValue(day,marketRate)`）。
- `GET /api/macro` → `{discountRate,reserveRequirement,m0,m1,m2,interbankRate,totalPrivateDebt,nplRatio,creditGrowth}`（`CentralBank.getDiscountRate/getReserveRequirement/getBaseMoney`、`BankManager.computeM0/M1/M2(playerCash)`、`InterbankMarket.getInterbankRate`、`CreditSystem.*`）。用于云图宏观仪表（§5.5 可选）与借贷页（§4.3）。
- `POST /api/loan/apply` body `{bankId, amount, termDays, collateralValue}` → `{success,message,loanId?}`；走 `Bank.issueLoan(borrowerId, borrowerType, amount, rate, termDays, collateralValue)`，校验（余额/抵押/风险）由后端判定。
- `POST /api/loan/repay` body `{loanId, amount}` → `{success,message}`；走 `Bank.repayLoan(loanId, amount)`。

### 5.3 量化（t3）
- `GET /api/quant/strategies` → `[{strategyId,playerId,name,kind,fast,slow,enabled,scriptId}]`（`QuantAPI` 存 `Strategy` + `QuantScriptEngine.getScriptIds`）。
- `GET /api/quant/script?scriptId` → `{scriptId,fileName,code}`（`QuantScriptEngine`/`Strategy.getScriptId`）。
- `GET /api/quant/status?strategyId` → `{enabled,lastRunTick,generatedOrders:[{assetId,side,price,quantity,time}],pnl,pnlPct,equityCurve:[number]}`（`QuantAPI.getStrategy().isEnabled()` + 最近 `executeStrategies` 产出的 `List<Order>` + 收益派生）。
- `POST /api/quant/strategy` body `{name,kind,fast,slow}` → `{success,message,strategyId?}`；`POST /api/quant/strategy/enable|disable` body `{strategyId}` → `{success,message}`（绑定玩家，走 `QuantAPI.enableStrategy/disableStrategy(playerId,...)`）。
- `POST /api/quant/script` body `{scriptId,code}` → `{success,message}`（`QuantScriptEngine.compile(scriptId,code)`）。
- `POST /api/quant/run` body `{strategyId}` → `{success,message,orders:[...],pnl}`（`QuantAPI.executeStrategies(state,playerId,assetId)` 或 `QuantScriptEngine.evaluate`）。
- **确定性**：量化策略执行沿用引擎 `SeedableRandom` 子流（`QuantAPI`/`engine` 已有），不引入新随机；HTTP 触发一次运行也走相同路径，可复现。

### 5.4 云图聚合（t3【可选】）
- `GET /api/marketmap` → `{industries:[{name,avgChange,count,upCount,downCount,flatCount,totalVol,totalAmount}], breadth:{up,down,flat,limitUp,limitDown,totalVol,totalAmount}, indices:[{name,value,change,changePoint}]}`。由 `/api/stocks` 数据按 `industry` 分组聚合（`avgChange`=组内涨幅均值、`upCount/downCount/flatCount`=组内涨/跌/平家数、`limitUp/limitDown`=±10% 家数），`indices` 取 `/api/index`（优经24）。**纯只读聚合**。若后端不实现，前端本地计算 `/api/stocks`（§4.5 已有方案），故此端点为可选（后端可为空返回或省略）。

---

## 6. 数据契约（前端消费的字段统一）

> 数值统一 `number`；缺失字段 `--` 占位；`status/kind/side/state` 用后端枚举字符串，前端只展示不推断。**与既有 `FRONTEND_THS_SPEC.md` §6 及 `index.html` 消费字段一致**。

| 域 | 字段 | 类型 | 来源 |
|---|---|---|---|
| 资产总览 | `playerName, cash, totalMarketValue, totalAssets` | string,number,number,number | `/api/portfolio`（既有） |
| A 股持仓 | `assetId,name,quantity,available,frozen,avgCost,lastPrice,marketValue,profit,profitPct` | — | `/api/positions`（既有） |
| 期货持仓 | `code,side,lots,longLots,shortLots,openPrice,lastPrice,floatPnl,usedMargin,marginRatio` | — | `/api/futures/positions`（既有） |
| 保证金 | `collateral,equity,liability,maintenanceRatio,riskBand,limitUsage,positions:[...]` | — | `/api/margin`（既有） |
| 杠杆持仓 | `noteId,units,avgEntryPrice,lastPrice,marketValue,pnl` | — | `/api/leverage/positions`（既有） |
| 委托 | `orderId,assetId,name,side,type,price,quantity,remaining,status,time` | — | `/api/orders`（既有）/`/api/myTrades` |
| 公司摘要 | `stockId,name,industry,price,change,pe,pb,eps,roe,nav,revenueGrowth,dividendYield,marketCap,growthStage` | — | `/api/companies`（新增） |
| 公司详情 | `stockId,name,industry,netAssetValue,revenue,revenueGrowth,eps,roe,dividendYield,debtRatio,bookValuePerShare,fairValue,totalShares,ipoPrice,profitabilityScore,financialHealthScore,growthStage` | — | `/api/company?id`（既有） |
| 银行 | `bankId,name,depositRate,loanRate,primeRate,reserveRequirement,totalDeposits,totalLoans,equity,vaultCash,reserves` | — | `/api/banks`（新增） |
| 我的账户 | `cash,demandDeposits,timeDeposits,moneyMarketDeposits,totalDebt,creditScore,nplRatio,creditGrowth,collateralValue` | — | `/api/banking`（新增） |
| 贷款 | `loanId,bankId,principal,remaining,annualRate,termDays,status,collateralValue,startDay` | — | `/api/loans`（新增） |
| 债券 | `bondId,faceValue,couponRate,maturityDays,currentValue,status` | — | `/api/bonds`（新增） |
| 宏观 | `discountRate,reserveRequirement,m0,m1,m2,interbankRate,totalPrivateDebt,nplRatio,creditGrowth` | — | `/api/macro`（新增） |
| 策略 | `strategyId,playerId,name,kind,fast,slow,enabled,scriptId` | — | `/api/quant/strategies`（新增） |
| 策略脚本 | `scriptId,fileName,code` | — | `/api/quant/script`（新增） |
| 运行状态 | `enabled,lastRunTick,generatedOrders:[{assetId,side,price,quantity,time}],pnl,pnlPct,equityCurve` | — | `/api/quant/status`（新增） |
| 云图 | `industries:[{name,avgChange,count,upCount,downCount,flatCount,totalVol,totalAmount}], breadth:{up,down,flat,limitUp,limitDown,totalVol,totalAmount}` | — | `/api/marketmap`（可选新增）/ 前端由 `/api/stocks` 计算 |
| 结果反馈 | `success,message`（写入端点统一） | boolean,string | `/api/*/apply|repay|strategy|run` |

---

## 7. 实施顺序与依赖（供 scheduler）

```
t1 (本规格) ──→ t2 (前端页面容器 + 布局：5 个缺页 .page 容器 + 统一外壳 + switchPage 修复)
        ├────→ t3 (后端 API 补齐：/api/companies、/api/banking、/api/loans、/api/loan/*、/api/banks、/api/bonds、/api/macro、/api/quant/*、/api/company/action、/api/marketmap)
        │           └── (t2/t3 彼此独立，可并行；t4 依赖二者)
        └────→ t4 (前端交互/轮询/数据接入：各页 load*、排序/搜索/联动/交易/贷款/启停策略/云图 Treemap、桥接+HTTP 双轨)
                    └──→ t5 (整体验证 + 构建) ──→ t6 (评审)
```
- **依赖**：t2 依赖 t1（布局契约）；t3 依赖 t1（API 契约）；t4 依赖 t1+t2+t3；t5 依赖 t2+t3+t4；t6 依赖 t5。
- **t3 与 t2 可并行**（API 契约先行，前端先建立容器 + 占位 fallback，后端补齐后自动接通）。前端新页在 t3 端点未就绪时显示「暂未连接 API」(HTTP 失败 `--`/空态，不报错)。
- **优先级**：t2 外壳（G1/G2）先行，让 8 页都有独立容器且空白不再出现；t3 端点补齐数据来源；t4 收口交互；t5 验证；t6 评审。

---

## 8. 与非目标边界（防范围蔓延）

- **不做**：美/中风格切换（固定 CN 红涨绿跌）；移动端适配（目标桌面 ≥1280×720）；图表库替换（沿用 ECharts）；后端撮合/校验/宏观/银行/公司/量化引擎的任何语义改动（前端只读 + 提交权威请求）。
- **不改**：`PriceDiscoveryEngine.step`/`A_SHARE_*` 规则/`SettlementSystem` T+1/`MarketEngine.submitOrder`/`AICompanyData`；H2/NBT 持久化格式（**前端零触碰**，后端保持前向兼容）；既有 `/api/*` 只读端点结构（仅允许增量字段）；`FRONTEND_THS_SPEC.md` 已交付的股票五区交互。
- **保持**：股票/商品/期货三页现有功能与交互不回归（G3）；新增页面只做增量，与既有 `index.html` 类名隔离。
- **确定性**：前端零新随机；新增 HTTP 只读不改变引擎状态；量化/贷款/交易写入走既有确定性入口（`SeedableRandom` 子流），可复现。
- **「我的公司」管理（IPO/股权/财报）为协同增强**：后端 `COMPANY_SPEC.md` 命令/packet 已是权威，前端接入不得改变公司引擎语义。

---

## 9. 对 t5（整体验证 + 构建）的需求

1. **构建验证（G0）**：`./gradlew build` 通过，前端资源打包进 jar。
2. **页面容器（G1/G2）**：8 个 Tab 逐个点击，断言显示对应 `#page-*` 容器、无空白/报错；股票页 `#main-container` 与非股票页互斥。
3. **布局/风格（G4~G8）**：各页静态核对外观（账户条/表格/Tab/红涨绿跌/空态），与股票页风格一致。
4. **数据/交互（G4~G9）**：持仓账户条与持仓表（`/api/portfolio` 等）、公司列表+详情（`/api/companies`+`/api/company`）、借贷银行+申请/还款（`/api/banks`+`/api/banking`+`/api/loan/*`）、量化策略启停/脚本/运行（`/api/quant/*`）、云图 Treemap 与广度（`/api/marketmap` 或前端 `/api/stocks`）；排序/搜索/联动/订阅刷新正常。
5. **写入链路（G4/G6/G7）**：持仓页撤单（`/api/cancelOrder`）、借贷申请/还款（`/api/loan/*`）、量化启停/保存脚本/运行（`/api/quant/*`）后端校验消息正确返回（中文）。
6. **双轨（G10）**：游戏内 WebView 经 `javaBridge` 完成同类操作（或占位不报错 + 后端注入方法存在）；独立浏览器 HTTP 兜底可用。
7. **确定性不回归（G11）**：固定 `engineSeed` 两次运行，各页展示的行情/持仓/公司/银行/量化序列与后端一致（无新增随机）。
8. **兼容（G3）**：股票/商品/期货三页功能回归通过（`FRONTEND_THS_SPEC.md` §9）。

---

## 10. 输入/来源索引

- 团队 Goal：前端全量标签页完善（期货/商品/公司/银行/持仓/量化/大盘云图）。
- 代码证据：`src/main/resources/assets/financialmarket/html/index.html`（Tab `:416-425`、`switchPage :1849-1858`、`enableHttpMode :1675-1720`、`updateOrders/Positions/Trades :1004-1052`、商品/期货页 `:1726-1865`）、`src/main/java/com/example/financialmarket/web/LocalHttpServer.java`（`createContext :91-148`、`getStockListJson :220-260`、`getCompanyJson :360-416`、`getAccountJson :499`、`getPositionsJson :521`、`getPortfolioJson :556`、`getOrdersJson :571`、`getMyTradesJson :598`、`getMarginJson :1005`、`getFuturesPositionsJson :876`、`getLeveragePositionsJson :1107`）、`engine/ai/AICompanyManager.java`（`getAll :...`）、`engine/banking/*`（Bank/Loan/BankManager/CentralBank/CreditSystem/InterbankMarket/GovernmentBond）、`engine/quant/{QuantAPI,QuantScriptEngine,Strategy}`、`network/packets/*Quant*`（Enable/Disable/Save/Request、QuantScriptDataPacket）。
- 既有规格：`FRONTEND_THS_SPEC.md`（股票五区/交互/双轨/trading 链路）、`COMMODITY_FUTURES_SPEC.md`（商品/期货）、`COMPANY_SPEC.md`（公司系统/命令/IPO/股权）、`BANKING_TRADE_SPEC.md`（银行/借贷/M2）、`A_SHARE_TRADING_SPEC.md`（A 股规则）、`P2_SPEC.md`/`MACRO_MODEL.md`/`ECON_*`（微观/宏观）、`H2_MIGRATION.md`（H2/持久化）。
- 现实参照：同花顺 PC（五区）、交易终端（持仓/资产全景）、银行客户端（账户/贷款）、上市公司研究（公司列表+财务）、量化策略平台（策略/脚本/回测）、大盘云图（Treemap 热力 + 广度）。
