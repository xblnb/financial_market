# BACKLOG_SPEC.md — Financial Market 三项 backlog 批量落地需求规格

- 作者：architect（方案架构/需求规格）
- 依据：`AUDIT.md`、`.verify/AUDIT_CONNECTIVITY.md`（六链审计 + P1–P4 记录）、`H2_MIGRATION.md`、既有 SPEC 系列（A_SHARE/COMPANY/BANK_TABLET/EXCHANGE_SCREEN/ECON_FIAT_M2/CAPM/NETHERITE_GOLD 等）与当前工作树源码逐行核对（2026 基线快照）。
- 范围：**仅规格**（BACKLOG_SPEC.md + .verify/）。**不落 src/**。实现/验证/评审由后续任务承接。
- 硬约束（全程继承）：确定性（SeedableRandom 子流、禁裸随机）、H2/NBT `contains` 兜底、不改已交付核心语义（法币 M2、黄金、GBM-GARCH、CAPM、A 股规则、玩家单现金/持仓结算）、开新存档测试。

---

## 1. 目标与非目标

### 1.1 目标
把三个 backlog 方向从「现状断点」推进到「可交付验收」：
1. **量化/自动化交易**：历史数据开放（HTTP `/api/history` + CSV 导出，供玩家本地 Python 回测）；服务端确定性量化 DSL 扩展（在既有 `EMA_CROSS/GRID/MARTINGALE` 之上新增 `MACD/RSI/BOLL/组合/风控` 指令，按 bar 触发省性能，SeedableRandom 确定性、禁裸随机）；策略管理 UI/入口完善（提交/启停/回测/收益展示）。
2. **玩家公司深化**：玩家上市公司进入指数、被 AI 交易（含 AI 持股账本）、并购/收购、公司事件驱动；政府/证监会角色与玩家公司联动。
3. **网络包→平板/交易所屏幕同步**（P1–P4 未接线项）：网络包数据回传 MCEF 金融平板 / 原生 FinancialTabletScreen / 交易所屏幕，消除「方块/平板/屏幕各看各的数据」。

### 1.2 非目标（明确不做）
- 不改已交付语义（§8 非回归冻结清单）。
- 不引入新运行时大依赖（Rhino/GraalJS/Pandas 等）；JS/DSL 语法分析继续走服务端内建 DSL。
- 不做「期货 PnL 对手方镜像账本、国债/OMO、M2 流通现金全量账本、gameTime→tickCounter 全量迁移」等审计已登记的**独立规格缺口**（§8.3），除非该方向设计必须触碰并经 captain 裁决。
- 不重写 MCEF 前端整体；只做数据接线与量化/公司页的缺口补齐。

### 1.3 三项优先级（本规格推荐，captain 可调整）
| 优先级 | 方向 | 理由 |
|---|---|---|
| **P0** | ③ 网络同步（先 BL-12 交易所屏幕 / BL-13 政府平板，后 BL-14/BL-15） | 断点是「已交付信息链」的可见性缺口，改动小、验证直接 |
| **P1** | ① 量化（BL-1/2/3 历史 API + H2 落库先行，BL-4/5/6 DSL 与管理随后） | BL-1..3 纯增量（H2 已建表未写），风险低、为 BL-4/6 提供数据底座 |
| **P2** | ② 玩家公司深化（BL-7→BL-8→BL-10→BL-9→BL-11） | 需规格裁决项最多（指数口径、AI 持股账本、并购语义），放中后期 |

> 三个方向可并行开工；每方向内按 BL 序号串行 + 同方向 BL 依赖（见 §4 依赖列）。

---

## 2. 现状审计（已核对源码，行号以当前工作树为准）

### 2.1 量化/自动化交易现状
| 项 | 现状 | 位置/证据 |
|---|---|---|
| 量化脚本引擎 | 内建 DSL：`EMA_CROSS fast slow`、`GRID step lots`、`MARTINGALE baseStep doubleOnLoss`；**单指令**（parse 命中第一条即 return，多行脚本不组合）；解析失败禁用不抛 | `engine/quant/QuantScriptEngine.java:89-126` |
| 执行节拍 | `gameTime % 20`（==0）且 tradingOpen 内，遍历**全部在线玩家 × 全部资产 × 全部已编译脚本** evaluate → `submitOrder` | `MarketEngine.java:1943-1946, 2677-2708` |
| 脚本归属语义 | **缺口**：`quant_script` 表脚本是全局的，`runQuantStrategies` 对每个在线玩家都执行每个脚本（谁存了脚本 = 全服玩家用其钱交易？无 owner 绑定、无 per-account 风控） | `H2DatabaseManager.saveQuantScript`（无 owner 列）；`QuantScriptEngine.getScriptIds()` 全量 |
| 策略状态持久化 | **缺口**：EMA 历史队列 / 网格锚点 / 马丁格尔连败计数存于 delegate 实例内（内存），引擎重建即丢失 → 读档后信号相位不连续 | `QuantAPI.java:106-236`（delegate 内 Map），save() 仅存注册参数 |
| 手动运行 | `/api/quant/run` 与 /status（pnl/equityCurve 为 0 占位）；`/api/quant/script` GET/POST 已通 | `LocalHttpServer.java:1784-1979` |
| H2 kline/trade_point | **表已建、无生产者写入**（唯一写 SQL 在**死代码** `storage/KlineFileStorage.java:67,212`）；H2 每次保存只写 engine_kv blob + ai_company/asset/company/index 关系镜像 | `H2DatabaseManager.java:127-263`；`MarketEngine.saveToH2/saveRelationsToH2:3524-3698` |
| 内存 K 线 | `PriceDiscoveryEngine` klineCache（TICK/Q/HALF/DAY/MONTHLY 聚合）+ tradePoints 内存 List；`/api/kline` 只回内存 ≤60 DAILY（limit=60）与 ≤50 笔 tradePoints；tradePoints 追加**无上限裁剪**（长跑膨胀风险） | `PriceDiscoveryEngine.java:466-533,663-712`；`LocalHttpServer.java:325-363` |
| 历史 API/CSV | **不存在** `/api/history`、无 CSV、无回测端点；`index.html` 量化页调 `/api/quant/*` 但 status 恒零、无回测 UI 落地 | `LocalHttpServer.java:89-190`（路由清单）；`index.html:2765-2835` |
| HTTP 线程读 H2 | 约束：HTTP 工作线程**不得复用**单连接（仅服务器线程）；历史 API 需独立短连接或服务器线程代理 | `H2DatabaseManager.java:38-40` |
| 浏览器桥 | MCEF 桥接 javaBridge → LocalHttpServer（HTTP 轮询为主），P5 参数错位已修 | `FinancialMCEFScreen.java:28-56` |

### 2.2 玩家公司深化现状
| 项 | 现状 | 位置/证据 |
|---|---|---|
| 指数构成 | `Excellent24Index.rebalance(state, aiManager)` **仅 AI 公司**（按 NAV×totalShares Top24）；rebalance 仅在 init 调用一次；玩家公司永不入选 | `engine/Excellent24Index.java:14-34`；`MarketEngine.java:821` |
| 指数成分持久化 | index_state/index_constituent H2 镜像仅 AI；读档按 blob 恢复成分后 updateValue | `MarketEngine.java:2919-2922,3192-3203` |
| 交易所屏幕名称映射 | board/总览 name 映射**只查 AI 公司**，玩家公司显示裸 stockId | `screen/data/ExchangeScreenDataService.java:139-149` |
| AI 交易玩家公司 | agent/MM/ZI/Hawkes/meso 循环**遍历 state 全部资产**（含 LISTED 玩家公司），但 `updateCompanyData` 仅对 AI 公司注入基本面 → 对玩家公司用默认参数交易，无行业/基本面针对性 | `MarketEngine.java:1789-1831,1807-1809` |
| AI 持股账本 | **缺口**：`attributeTrade` 只对非 null playerId 记 `CompanyData.shareholders`；AI（playerId=null）买卖不记账 → Σshareholders 与 totalShares 漂移（审计 M1 已登记为「确定性建模决策」保留）；AI 无法真正成为股东/触发收购 | `MarketEngine.java:2498-2502`；`.verify/AUDIT_CONNECTIVITY.md §7.2 公司 M1` |
| 并购/收购 | 仅退市兜底 `delistBuyout(max(ipo,last))` 回购清偿；**无主动收购/要约/合并** | `MarketEngine.java:1335+`；COMPANY_SPEC §7.1 |
| 公司事件 | **无**事件调度器；价格只走 GBM/情绪/基本面季度路径 | `PriceDiscoveryEngine` step；`MarketEngine.tick` 无事件块 |
| 政府/证监会 | RoleSystem 只有 `CENTRAL_BANKER/FINANCE_MINISTER`（+RETAIL）；**无证监会/监管角色**；公司 IPO 无监管审批环节（创始人对本人公司直接 startIpo） | `engine/role/RoleSystem.java:30-33`；`MarketEngine.startIpo:905` |
| 财务/分红/退市 | 财报/年度分红/季度退市检查已接线（gameTime 门控，审计 L3：/time 回拨可重发 → 新改动一律 tickCounter 门控） | `MarketEngine.java:1727-1755` |

### 2.3 网络包→平板/屏幕同步现状（对应审计 P1–P4 + 长尾）
| 审计项 | 现状 | 位置/证据 |
|---|---|---|
| P1 网络包→平板死路 | `ClientMarketDataCache` **只写不读**（orders/positions/trades/kline/depth 14 个字段 0 读者）；`FinancialTabletScreen`（原生旧屏 1450 行）**无打开入口**（无 setScreen 引用），仅 ModMessages 推送仍指向它 | `.verify/AUDIT_CONNECTIVITY.md §7.1-P1`；`ModMessages.java:71-204`；`screen/FinancialTabletScreen.java:29` |
| P2 交易所屏幕客户端同步断 | `ExchangeScreenBlockEntity.refreshSnapshotAndSync` 用 `sendBlockUpdated`（1.21.1 只发 ClientboundBlockUpdatePacket、不带 BE 数据），**无 ClientboundBlockEntityDataPacket** → 客户端 BER 停在初始/NO DATA；`handleUpdateTag` 已实现但无触发源；客户端仅落块/读档时经 getUpdateTag 拿一帧 | `block/ExchangeScreenBlockEntity.java:96-105,126-141`；`.verify/AUDIT_CONNECTIVITY.md §7.1-P2` |
| P3 银行平板请求 | **已修**：`RequestBankDataPacket` 已注册、响应已路由到 BankTabletScreen（含央行政策数据 + banking.enabled） | `ModMessages.java:66,121-133`；`BankTabletScreen` update |
| P4 政府平板全死 | `RequestGovernmentDataPacket.handle` **整段注释**且**未注册**；`GovernmentDataResponsePacket` 无构造点；响应 handler 只路由 FinancialTabletScreen（旧屏）→ 政府平板显示全 0；且响应缺 `reserveReq` 字段而屏上有该显示项 | `network/packets/RequestGovernmentDataPacket.java:27-43`；`ModMessages.java:134-138`；`screen/GovernmentTabletScreen.java:11,81` |
| 银行平板刷新 | 仅在打开时请求一次 + 关键交互后回包，无周期轮询 → 与 MCEF/交易所屏幕读数不同步（各看各的数据） | `BankTabletScreen`、BankDataResponsePacket 路径 |
| 数据一致性架构 | 无统一「引擎 tick 快照号/水位线」概念；各表面：MCEF=HTTP 轮询（~1s）、交易所屏=服务端 10 tick 快照（同步断）、旧原生屏=包推送（无入口）、银行/政府平板=请求-响应 | 综合 |
| 数据源同源性 | 服务端引擎为唯一权威；HTTP/网络包读同一 MarketEngine，但字段结构各自序列化（StockDataResponsePacket 只带 OHLCV；/api/kline 带技术指标）→ 需统一定义一致性判据而非逐字段耦合 | `StockDataResponsePacket.java:52-93` |

---

## 3. 全局设计原则（三项通用）

### 3.1 确定性（R0-3 体系延续）
1. 所有新增随机消费必须来自**引擎种子派生的独立 SeedableRandom 子流**：`new SeedableRandom(SeedableRandom.mix64(engineSeed ^ DomainSalt))`，`DomainSalt` 为每功能域固定常量（如 `0x4D414344L`/`0x4556454E54L`）；**不消费既有共享流**（`AICompanyManager` 的 `Random(seed)` 与既有子流消费次序不变 → 同种子基线逐位一致）。
2. 新增代码禁止 `Math.random/new Random/ThreadLocalRandom/UUID.randomUUID`（引擎路径）；改动文件 grep CLEAN（非注释命中 0）。
3. 新增时间门控一律用 **`tickCounter`**（时间权威，与 AS-M3 一致），不使用可被 `/time` 改写的 `gameTime`。
4. 量化/事件/指数规则全部为确定性纯函数或确定性阈值；同种子 + 同 tick 序列 → 逐位相同（SD-12）。

### 3.2 持久化边界
1. **真源 = NBT blob（engine_kv 压缩 NBT）**；H2 关系表为镜像/检索面；新字段 blob 内 `CompoundTag.contains` 兜底读档不失败；新增表列 `ADD COLUMN IF NOT EXISTS` 幂等（沿用 H2DatabaseManager.createTables 模式）。
2. H2 写入**只在服务器线程**（tick 内/停服时）；HTTP 线程读历史走**独立短连接**（同 JVM 内 H2 允许多连接）或服务器线程代理，禁止复用单连接（`H2DatabaseManager:38-40`）。
3. 新增量化策略状态/公司事件/机构持股等持久化字段：旧档缺失 → 默认重建/空集合，不抛异常。
4. 表/文件命名与现有 schema_meta data_version 对齐；结构变更须在 schema_meta 记录。

### 3.3 不回归隔离（特征开关 + 因子隔离，沿用 LQ-9/LQ-10/MP 风格）
- 每个方向设**默认行为不变**开关：方向① `quantV2`（历史 API 落库为纯增量，可直接默认开）；方向② `companyInteraction`；方向③ 同步修复（无开关，是修复断点）。开关关闭时新代码路径零执行 → 既有交付基线逐位一致。
- 每一 BL 的验收必须包含：开关关闭路径的基线探针结果 == 改造前记录（或改造前无该功能 = 探针确认无副作用）。

### 3.4 数据流总约定
```
MarketEngine（唯一权威，tickCounter 推进）
 ├─ 内存：state/orderBook/kline/tradePoint/…（现有）
 ├─ H2（服务器线程落库）：engine_kv blob（真源）＋ 关系镜像
 │    └─ 新增：kline/trade_point 增量写（BL-3）＋ quant 状态/公司事件表（BL-5/10 视设计）
 ├─ HTTP（工作线程只读）：/api/* 实时读引擎；/api/history[/.csv] 读 H2 历史（BL-1/2）
 ├─ 网络包（C2S 权威动作 / S2C 推送，tickCounter 时间戳）→ ClientMarketDataCache（唯一客户端缓存，带水位线）
 └─ 三表面：MCEF(HTTP) / 原生平板(包推送) / 交易所屏(BE 同步) —— BL-12..15 统一读数
```

---

## 4. 验收清单总表（BL-0..BL-15）

> 每个 BL 含：方向、目标文件范围（设计目标）、验收判据（定量）、确定性/持久化要点、依赖、建议实现者。
> 验证方法论沿用仓库惯例：`compileJava` exit 0；改动文件确定性 grep CLEAN；`.verify/harness` 式 Java 探针（编译进 runtime classpath 运行）；长跑 `tick rate 200`；冷启动新档 + 读档复验。

| BL | 方向 | 标题 | 依赖 | 建议实现者 | 摘要 |
|---|---|---|---|---|---|
| BL-0 | 公共 | 基线护栏与回归基线 | — | architect→test-engineer | 冻结清单、开关、基线探针、验收骨架 |
| BL-1 | ① | H2 历史 JSON API `/api/history` | BL-3 的表数据（先做 3 或同批） | engine-dev | 返回 H2 kline+trade_point 历史 |
| BL-2 | ① | H2 历史 CSV 导出 | BL-1/3 | engine-dev | `/api/history.csv`、`/api/trades.csv` 本地回测可用 |
| BL-3 | ① | H2 kline/trade_point 服务器线程落库 | — | engine-dev | 收尾 DAILY bar/tick 成交 → H2，含裁剪与幂等 |
| BL-4 | ① | 量化 DSL v2（MACD/RSI/BOLL/组合/风控） | BL-3（历史供给指标窗口） | engine-dev | 按 bar 触发、多指令脚本、风控指令 |
| BL-5 | ① | 脚本归属 + 状态持久化 + 风控执行 | BL-4 | engine-dev | owner 绑定、启停、per-account 状态、服务端风控 |
| BL-6 | ① | 量化 UI/入口（提交/启停/回测/收益） | BL-1..5 | engine-dev(+前端文件) | /api/quant/* 真实值 + 回测端点 + 页面接线 |
| BL-7 | ② | 玩家公司进入指数 | — | agent-dev | 指数含 LISTED 玩家公司，基线回归 |
| BL-8 | ② | AI 交易玩家公司 + AI 持股账本 | BL-7 | agent-dev | 机构股东桶、Σ==totalShares、指数口径 |
| BL-9 | ② | 并购/收购 | BL-8 | agent-dev | 要约收购 + 确定性并购引擎 |
| BL-10 | ② | 公司事件驱动 | BL-8 | agent-dev | 事件调度/价格渠道/持久化/通知 |
| BL-11 | ② | 政府/证监会角色联动 | BL-7 | agent-dev | RoleSystem 扩展 + 监管动作 + 平板接线 |
| BL-12 | ③ | 交易所屏幕实时同步 | — | frontend-dev | ClientboundBlockEntityDataPacket 修复 P2 |
| BL-13 | ③ | 政府平板全链修复 | — | frontend-dev | P4 恢复（注册/handle/响应路由/周期刷新） |
| BL-14 | ③ | 统一客户端缓存 + 旧原生屏决策 + 银行平板实时 | BL-12/13 | frontend-dev | P1 收敛（缓存有读者 / 屏入口决策 / 周期刷新） |
| BL-15 | ③ | 端到端一致性验收 + 回归 | BL-12/13/14 | frontend-dev + test-engineer | 同 tick 多表面读数一致判据 |

> BL-0 由本规格立基线（§8 冻结清单）；test-engineer 在本任务下游为其写 `.verify/` 探针骨架。

---

## 5. 方向①：量化/自动化交易（BL-1..BL-6）

### 5.1 BL-3 H2 kline / trade_point 服务器线程落库（先行，纯增量）
**目标文件**（设计目标，实现时可等价调整）：
- `engine/PriceDiscoveryEngine.java`：暴露「已收盘 TICK bar / 已收盘 DAILY bar / 新 tradePoint」确定性消费点；新增只读历史访问器。
- `storage/db/H2DatabaseManager.java`：kline/trade_point DAO（INSERT/MERGE + 幂等 + 裁剪），沿用 `KlineFileStorage` 现成 SQL（去死代码化）。
- `engine/MarketEngine.java`：tick 内调用落库点（服务器线程）。

**数据流**：engine tick（bar 收盘事件/成交）→ 服务器线程 H2 `MERGE INTO kline/trade_point`（bar_index/seq 幂等）→ `/api/history` 读 H2。
**确定性**：写入由 tickCounter 与 bar_index 确定；同种子 → 同行数同行值。
**持久化边界**：`asset_id,kline_type,bar_index` 主键幂等；`trade_point(asset_id,seq)`；裁剪策略**确定性**（如 DAILY 保 360 根/资产、TICK 保近 N 交易日，按月/bar 数阈值在服务器线程执行）；blob 仍为真源，H2 kline 为检索镜像；schema 幂等迁移。
**验收（BL-3）**：
1. 冷启动新档（`runClient` 建世界），tick rate 200 跑 ≥5 模型日（或 `tick rate 200` + 加速）后：`SELECT COUNT(*) FROM kline` 每 LISTED 资产 DAILY ≥ 60（含冷启动历史），TICK > 0；`trade_point` seq 连续无空洞。
2. 两次同种子冷启动各跑 K tick → 两库 `kline`/`trade_point` 全行（asset,type,bar_index 对齐窗口内）**逐行相同**。
3. 停服/读档后 H2 行保留；重复保存不产生重复行（PK 幂等）；`schema_meta` 记录版本。
4. `compileJava` exit 0；改动文件确定性 grep CLEAN。

### 5.2 BL-1 HTTP `/api/history` JSON
**接口契约**（新增，`LocalHttpServer`）：
```
GET /api/history?asset=<stockId>&type=<0|1|2|4|5 同 /api/kline>&from=<barIndex|tick>&to=<…>&limit=<n>
  → 200 {"assetId","type","rows":[{"barIndex","open","high","low","close","volume"},…]}
GET /api/history?asset=…&points=true&limit=<n>
  → {"assetId","tradePoints":[{"seq","price","quantity","isBuy"},…]}
非法 asset/type → 200 空 rows（不清真 500，沿用 P7 修复约定）；数值经 jsonSafe/fmt 清洗（无 NaN）。
```
**数据流**：HTTP 工作线程 → 独立只读 H2 连接（短连接，同 JVM）→ H2 kline/trade_point；内存未收盘 bar 不回填历史（口径 = 「已收盘确定性历史」）。
**验收（BL-1）**：
1. 对 ≥10 个资产（含 AI 与 1 个玩家公司若 LISTED）请求 `type=4&limit=120`：`rows` 与 H2 `kline` 查询一致（条数=min(limit,行数)）；OHLCV 全为有限数。
2. 非法 id/type 返回 200 空结构不 500；无 NaN 泄漏（沿用 MS-1 复验方式）。
3. 同种子两新档同窗口 → JSON 数值逐位一致。
4. 无 H2/引擎异常写日志；不影响游戏 tick。

### 5.3 BL-2 CSV 导出
```
GET /api/history.csv?asset=…&type=4&from=&to=
  → text/csv; charset=utf-8：header bar_index,open,high,low,close,volume
GET /api/trades.csv?asset=…&from=&to=
  → header seq,price,quantity,is_buy
```
**验收（BL-2）**：
1. CSV 行数 == 同参 JSON rows 数；纯 Python `csv` 模块可解析（无外部依赖）；数值 round2 后输出。
2. 两个同种子新档同窗口导出**字节相同**（同一 header/行序/round2）。
3. Content-Type/编码正确；`curl http://localhost:8080/api/history.csv?...` 可落盘并被 `python` 读取为数值表（附 .verify 探针脚本）。
4. 空结果返回 header 行（可回测空窗口不 500）。

### 5.4 BL-4 量化 DSL v2（按 bar 触发、多指令、风控）
**现状→目标**：现 parse 单指令、每 20 tick 全量评估。目标：
1. **多指令脚本**：行式 DSL，每行一条指令，按顺序求值；信号指令产「意向」，风控指令过滤/裁剪后经 `submitOrder`（真实玩家单，A 股一手/T+1/涨跌停复用）下单。
2. **指令集扩展**（均确定性、无随机）：
   - `MACD fast slow signal` → DIF/DEA 金叉买、死叉卖（沿用 EMA 类价格历史，需 ≥signal 预热窗）；
   - `RSI period overbought oversold` → 上穿 overbought 卖、下穿 oversold 买；
   - `BOLL period k mode` → mode 0 回归（触下轨买/上轨卖）/ mode 1 突破（带上轨买/下轨卖），按 spec 明确；
   - `COMBO <i1> AND <i2> …`（仅同向叠加、全部满足才下单，防过度设计）；
   - 风控行：`RISK MAX_ORDERS_PER_BAR n`、`RISK MAX_POSITION_VALUE pct|amount`、`RISK STOP_LOSS pct`、`RISK TAKE_PROFIT pct`、`RISK MAX_DAILY_LOSS pct`（当日累计浮亏超限 → 停该脚本至日界，tickCounter 门控）。
3. **按 bar 触发省性能**：新 DSL 脚本只在**确定性 bar 收盘点**评估（tickCounter 对齐现有 TICK bar 聚合边界：每 `TICKS_PER_BAR=20` 个成交/引擎 tick 触发一次该资产脚本），不再每 20 tick ×全资产全脚本空转；指标状态每 bar 更新一次。**遗留 EMA/GRID/MARTINGALE 注册策略在 `quantV2=false` 时保持现 `gameTime%20` 语义**（不回归）；`quantV2=true` 时新 DSL 走 bar 触发。
4. **预热**：MACD/RSI/BOLL 需要窗口，不足 → 该资产不产单（确定性跳过）。
5. **状态**：每 (player,scriptId,assetId) 指标状态显式存对象（可序列化），随 BL-5 持久化。
**验收（BL-4，定量）**：
1. 编译通过；每个新指令有确定性单测（同输入两次 → 同输出；预热窗口不足 → 空单）。
2. 评估次数上界（服务端计数）：一个交易日内 DSL 评估次数 ≤ `bar收盘点数×启用脚本数×持仓资产数×1`（探针在 MarketEngine 计数器验证），与现每 20 tick 全量相比可证下降（给出同资产数下旧/新比值 ≥ 3×）。
3. 风控：构造超量/超损场景（`MAX_ORDERS_PER_BAR 1` + 同 bar 双信号）→ 实际 submitOrder 次数 == 上限；`STOP_LOSS`/`MAX_DAILY_LOSS` 触达后该脚本日内在引擎侧拒单（而非仅 UI）。
4. 两新档同种子跑 K tick → 两档订单序列（submitOrder 时间戳 tickCounter+asset+side+qty+price）逐位一致。
5. `quantV2=false` 基线探针 == 改造前记录（开关零执行）。
6. NaN/除零防护：任何新指令异常 → 该脚本该次评估返回空并告警，不阻断 tick。

### 5.5 BL-5 脚本归属、状态持久化、服务端风控
1. **owner 绑定**：`quant_script` 增 `owner_uuid VARCHAR(36)`（幂等 ADD COLUMN）；`/api/quant/script` POST 绑定当前会话玩家；旧无主脚本视为公共只读模板（可 fork 为自己的）。
2. **执行对象**：`runQuantStrategies` 只执行「该玩家自己 enabled 的脚本」（player↔script 映射持久化于 MarketEngine blob `"quantScripts"`，与现有 QuantScriptEngine.enabled 合并升级）。
3. **状态持久化**：每 (player,scriptId,assetId) 指标状态/风控计数器随 `MarketEngine.save()/load()` 的 `"quantState"` NBT 保存（contains 兜底；旧档无键 → 空，读档不失败，预热自然重建）。
4. **启停**：现有 Enable/DisableQuantStrategyPacket + /api/quant/strategy/enable|disable 扩展为「按 (player,scriptId)」语义；停用即时生效。
5. **风控执行在服务端 submitOrder 前统一网关**：新增 `QuantRiskGate`（引擎内），所有量化订单经其校验（上限/止损/日亏/现金）；手动玩家单不经此门（不回归）。
**验收（BL-5）**：
1. 玩家 A 保存脚本 → 仅 A 的执行列表含它；B 的启停不影响 A（两 UUID 场景探针）。
2. 读档后：启用标记 + 指标状态 + 风控计数器恢复（场景：马丁格尔连败 5 → 读档 → 续跑，订单序列与不读档同种子运行逐位一致——若状态设计为序列化，则该判据成立；否则记录为已知差异并在 spec 注明口径，二者取其一并在 VERIFY 声明）。
3. 风控网关：构造 2× 上限请求 → 引擎仅放行上限内；无量化玩家单被该网关拒绝（手动路径 0 命中）。
4. contains 兜底：旧档读入不抛；`/api/quant/strategies` 与 `/api/quant/status` 返回真实 enabled 列表与状态。

### 5.6 BL-6 量化 UI / 入口
1. `/api/quant/status` 改真实值：`lastRunTick/generatedOrders(当日累计)/pnl/pnlPct/equityCurve`（引擎记账：策略订单成交盈亏 + 现金/持仓，持久化含兜底）。
2. 新增 `/api/quant/backtest {scriptId?,code?,asset?,type?,from?,to?,initialCash?}`：服务端只读回放（对 H2/内存历史逐 bar 确定性求值，**不落真实订单**、不改账本），返回 `{equityCurve[], trades[], stats{ret,sharpe?,maxDd,winRate}}`（Sharpe/winRate 纯函数）。
3. `index.html` 量化页：策略列表（owner/脚本/启停/收益）真实渲染、脚本编辑器保存即 owner 绑定、回测结果图表（复用现有 echarts 组件）、错误信息非空吞（P6 相关 .catch 至少量化页打日志）。
4. 入口：MCEF 量化 tab + 服务端命令 `/fmarket quant list/enable/disable/backtest`（管理可查全服）。
**验收（BL-6，定量）**：
1. 场景跑 2 模型日后 `/api/quant/status` pnl/pnlPct/equityCurve 至少一项非零且与引擎账本一致（探针比对）；generatedOrders == 当日实际 submitOrder 数。
2. 回测端点：对 DAILY 历史窗口返回统计纯函数值；两次同参请求 JSON 逐位一致；回测不产生任何订单/账本变化（提交前后 PlayerEconomy/簿快照一致探针）。
3. 页面：启停按钮后 `/api/quant/strategies` enabled 翻转 ≤1 轮询周期；脚本保存 → 服务端 compile 成功/失败反馈非空。
4. UI 无 404 端点（fetch 清单 vs LocalHttpServer 路由比对脚本）。

---

## 6. 方向②：玩家公司深化（BL-7..BL-11）

### 6.1 前置口径裁决（D1/D2 见 §10；推荐值此处先行锁定，实现按推荐值，captain 否决再改）
- 指数候选池 = AI 公司 + `LISTED` 玩家公司（CompanyStatus.LISTED 且 state 有该 asset）。市值口径：`NAV×totalShares` 与 AI 公司同式（确定性），不引 float 复杂度（D2 否决浮筹口径时保持）。
- AI 持股记账采用**机构股东桶**模型：每个机构（InstitutionalAgent/SectorFundAgent/MM 池等按域建桶）以确定性派生 UUID 记入 `CompanyData.shareholders`，Σshareholders==totalShares 恒等式恢复（见 BL-8）；D2 提供否决备选。

### 6.2 BL-7 玩家公司进入指数
- `Excellent24Index.rebalance(MarketState, AICompanyManager)` 扩参为可包含玩家公司（新增 `rebalanceAll(state, aiManager, companyManager)`；旧签名保留为 AI-only 等价子集或按 flag 分流）：Top-24 = 去重候选池按市值降序，平手按 stockId 字典序。
- `MarketEngine` 在 init 与读档恢复后、及每日开市时按 tickCounter 周期重平衡（现状只在 init 一次 → 改为每交易日开市重平衡；**需保证无玩家公司时结果与现状一致**）。
- `ExchangeScreenDataService.buildIdToNameMap` 追加玩家公司 name 映射（board 不再裸 id）。
- H2 镜像 index_constituent 写入选中的玩家公司（幂等去重，沿用 saveRelationsToH2 去重防御）。
**验收（BL-7，定量）**：
1. 冷启动新档无玩家公司：成分列表与改造前基线探针**完全一致**（回归位）；指数值同式。
2. 场景：玩家公司 IPO 上市且 NAV×totalShares ≥ 现 Top-24 末位 → 下个重平衡日进入成分；从 /api/index 与 H2 index_constituent 可查；比例 = 其价格纳入均值计算（值与手动按式复算一致 ±0.01）。
3. 退市 → 下个重平衡日移出；读档后成分保留（blob 恢复路径）。
4. 指数 updateValue 无 NaN、无限值。

### 6.3 BL-8 AI 交易玩家公司 + AI 持股账本
**缺口根因**：AI 单 playerId=null → attributeTrade 跳过账本 → 漂移；AI 无法做股东。
**设计**：
1. 引擎内新增 `InstitutionalHoldings`（或并入 AICompanyData/institutional 模块）：机构桶（确定性派生 UUID，如 `UUID.nameUUIDFromBytes(("fm-institution:"+tag+seedSalt).getBytes)` 不引裸随机）→ (assetId → qty, avgCost)。
2. `attributeTrade`：当 AI/null 侧成交且对手为玩家/机构时，按被动/主动归属记账：
   - AI 买入玩家卖出 → 玩家现金入 + holdings 减（既有）；机构桶 +qty（从玩家卖出额买入）；
   - AI 卖出玩家买入 → 玩家扣现金 + 加股；机构桶 -qty（若桶不足 → 服务端防御拒该合成单，或按已有「AI 不持有时不作卖出对手」的 MM 报价规则约束：**AI 卖出报价不得超过其桶存量**（在 agent 层修正，不下单再查））。
3. `CompanyData.shareholders` 收机构桶 UUID；`getShareholdersOf` 排序天然含桶；分红按比例发到桶账户（机构现金池，防凭空/湮灭）。
4. IPO：机构参与认购窗口（确定性比例，如流通 40% 中 ≤20% 由机构桶认购），上市即有机构持仓。
5. AI 对玩家公司的决策用基本面：`updateCompanyData` 扩展对玩家公司（用 CompanyData 财报/NAV 派生模拟指标），机构/中观按行业/估值对玩家公司下单；**A 股规则不变**（一手/±10%/T+1 由 submitOrder/簿侧保证）。
**验收（BL-8，定量）**：
1. 场景（机构买入 N 手玩家公司股）：成交后 Σ shareholders（玩家+创始人+机构桶）== totalShares **精确相等**（探针断言 ±0）；机构桶 avgCost×qty 与付出现金一致（守恒）。
2. 反向场景（机构卖出）后恒等式仍成立；桶不足时服务端不产生负持股（簿侧无越卖报价日志探针 0 命中）。
3. 分红：机构桶按比例收现金入机构现金池（Σ 分红 == 留存扣减额，与既有 payDividend 守恒探针并跑）。
4. IPO 场景：机构参与后上市即簿上有机构 resting 报价（AI 流动性存在），玩家可成交且 A 股校验命中 0 异常。
5. 同种子两档 → 机构桶交易序列逐位一致；`companyInteraction=false` 时 attributeTrade/agent 路径 == 现状（AI 桶 0 增长）。

### 6.4 BL-9 并购/收购
**范围**（推荐最小集，D3）：**AI 机构要约收购玩家公司** + **玩家公司主动出售/被收购指令**；玩家↔玩家合并不做（列为超范围）：
1. 触发：机构（或 AI 财团）对目标玩家公司打分（估值差 fairValue/price、桶占比、低流动天数）超过确定性阈值 → 发起要约（溢价 = max(现价×1.2, ipoPrice) 区间由子流抽样，确定性）。
2. 要约流程：公告 N 交易日 → 到期结算：接受要约的持股（玩家按持仓比例或全量）→ 机构现金付出 → 公司退市（复用 delistBuyout 的清账/退市路径语义，但资金来源为机构现金池而非交易所兜底）→ removeAsset + T+1 clear（沿用 F4）。
3. 玩家命令/包：`CompanyActionPacket` 扩展 `accept_offer` / 新包 `CompanyOfferPacket`（要约列表/接受）；HTTP `/api/company/offers` 可查可操作。
4. 确定性：要约发起/接受/结算全部 tickCounter 门控 + 子流随机；不引入并发竞态（服务器线程）。
**验收（BL-9，定量）**：
1. 场景 A（AI 要约 → 玩家接受）：交易前后机构现金池 + 玩家现金 + 目标公司总市值守恒（Σ 现金变化 == -Σ 股份现金等价，探针）；公司状态 DELISTED、资产移出、T+1 该股残留清零（沿用 F4 断言）。
2. 要约参数确定：同种子两档 → 同一公司在同一 tickCounter 收到相同要约价/窗口。
3. 玩家拒绝/不响应 → 到期要约失效、公司不变化、无泄漏。
4. 与既有 delistBuyout（退市兜底）互不干扰：普通退市不触发要约、要约未完成不被 DelistingMonitor 重复处理（状态互斥探针）。

### 6.5 BL-10 公司事件驱动
**设计**：确定性事件引擎（tickCounter 门控 + 独立子流）：
1. 事件类型（v1 最小集）：财报超预期/低于预期、大额订单、管理层变动、行业冲击（映射到行业商品价/宏观因子）、合规处罚（配合 BL-11）。
2. 事件 → 价格渠道：事件改**情绪/需求侧输入**（riskAppetite 微调/订单流偏差），经现有 agent 决策与簿侧传导，**不直接写 GBM 状态/不 bypass 涨跌停**；幅度钳制（如单日 ≤ 既有 ±10% 带内偏差上限，参数确定性）。
3. 持久化：pending/已触发事件日志 NBT contains 兜底；通知走聊天 + 公司页/平板事件栏（BL-15 网络一致）。
4. 与财务季度/分红节奏错峰（tickCounter 门控不与 REPORT/DIVIDEND 同日同 tick 触发，防语义缠绕）。
**验收（BL-10，定量）**：
1. 事件调度确定性：同种子两档 → 事件类型/时间/目标逐位一致（子流探针）。
2. 幅度钳制：任何事件后单 tick 价格变动仍满足 A 股涨跌停带约束（簿侧 clamp 探针 0 越界）；无 NaN。
3. 事件后订单簿/成交正常（无崩溃）；事件日志读档保留（contains 兜底）。
4. `companyInteraction=false` → 事件引擎零执行，基线探针 == 现状。

### 6.6 BL-11 政府/证监会角色联动
1. `RoleSystem.Role` 增 `REGULATOR("regulator")`（+ `RegulatorGuardrail` 沿 `CentralBankerGuardrail/FinanceMinisterGuardrail` 风格）；角色互斥与 count 约束纳入现有 managementRoleCount。
2. 监管动作（服务端权威、tickCounter 时间戳）：
   - **IPO 审批**：`startIpo` 前置「监管审批」态（提交 → 监管批/驳 → 批后开窗）；无监管角色时回落现行为或由「系统默认审批通过」（默认与现状一致，避免新档玩法被卡——决策 D4）；
   - **停牌/复牌**（监管暂停该玩家公司交易 n 交易日，簿侧拒新单不撤既有）；
   - **调查/处罚**：异常交易检测（如单账户对敲/撤单率超阈、操纵价）→ 罚款（从玩家现金/冻结扣，入政府罚款收入，M2 记账语义沿用 tax 路径）或暂停其量化脚本（联动 BL-5 网关）。
3. 接入：`GovernmentTabletScreen`/政府平板新「监管」面板（BL-13 数据链修复后同构）；`GovernmentActionPacket` 或新 `RegulatorActionPacket`。
**验收（BL-11，定量）**：
1. 角色门控：非 REGULATOR 玩家调监管 API → 拒绝且不改状态；REGULATOR → 生效（探针两角色对照）。
2. IPO 审批流程：提交 →（默认自动批或监管批）→ 窗口开；数据与 H2 company.ipo_state 一致。
3. 停牌期间该股 submitOrder 被拒（服务端计数），复牌后恢复；不触碰其它资产/玩家单（回归）。
4. 罚款记账：罚款入政府，玩家现金扣减对称；与 M2/tax 既有语义不冲突（探针：Δ政府收入 == -Δ玩家现金 - 无凭空）。
5. 确定性 grep CLEAN；两档同种子监管序列逐位一致。

---

## 7. 方向③：网络包→平板/交易所屏幕同步（BL-12..BL-15）

### 7.1 数据一致性判据（本方向总纲）
定义「一致」= 同一 `tickCounter` 采样下，各表面读数与服务端引擎权威值满足：
- 数值字段（指数/现价/涨跌幅/宏观量/持仓/现金/订单数）误差为 **0**（同 tick 快照）或 ≤ **1 个轮询周期内**的合法滞后（异步推送语义，用水位线 tick 戳声明滞后）；
- 表面必须显示**数据时间戳/刷新时间**（tick 或刷新时刻），禁止无限期静默旧数据（NO DATA/恒 0 视为失败）。

### 7.2 BL-12 交易所屏幕实时同步（修 P2）
- `ExchangeScreenBlockEntity.refreshSnapshotAndSync`：`sendBlockUpdated` 之外，向 tracking 玩家发 `ClientboundBlockEntityDataPacket.create(this, be -> be.getUpdateTag(...))`（NeoForge `PacketDistributor.sendToPlayersTrackingChunk`），每 `SNAPSHOT_INTERVAL_TICKS` 一帧（现 10 tick 可保）；`handleUpdateTag` 已具备接收能力 → 无需改 BER 渲染主逻辑。
- 初次落块/读档后首帧：tick 门控发送即可覆盖；BER「NO DATA」仅允许出现在首帧到达前（<1s）。
- 快照内容沿用 `ExchangeScreenDataService.capture`（权威派生，无随机），BoardRow name 含玩家公司（BL-7 联动）。
**验收（BL-12，定量）**：
1. 单机放 1 块交易所屏幕并保持渲染：≥10s 观察期内 BER 快照 tick 严格递增（探针读 BER 快照或屏幕文字截图比对）；无「NO DATA」持续态。
2. 读数一致：同刻 BER 指数与 `/api/index` 值相差 ≤ 0.01（在各自采样 tick 差 ≤ SNAPSHOT_INTERVAL 内对齐比较）；涨跌符号一致。
3. 断线重连/读档后 5s 内恢复实时（无 NO DATA）。
4. 拼接屏整组同 tick 快照一致（`assertGateTickConsistency` 回归通过）。

### 7.3 BL-13 政府平板全链（修 P4）
1. `RequestGovernmentDataPacket.handle` 恢复实现（服务端：gov/centralBank/inflationSystem 读数）并注册 `playToServer`。
2. `GovernmentDataResponsePacket` 增 `reserveReq` 字段（6 个 double；StreamCodec 扩展——旧客户端不兼容属可接受，单 mod 同版本）；服务端唯一构造点 = RequestGovernmentDataPacket.handle。
3. `ModMessages` 响应路由加 `GovernmentTabletScreen.updateGovernmentData(...)`（与 BankDataResponsePacket 双路由同构）；顺带 FinancialTabletScreen 兼容分支保留。
4. `GovernmentTabletScreen` init 时 `RequestGovernmentDataPacket().send()` + 每 ≤5s 周期重发（tick 或屏内计时）；按钮动作后立即刷新一次。
**验收（BL-13，定量）**：
1. 打开政府平板 → 5s 内国库/国债/通胀/基础货币/贴现率/准备金率全部非 0 默认并 = 服务端权威（与 `/api/macro` 同 tick 对齐 ≤0.01/0.1%）。
2. 用 `/fmarket` 或监管动作改变贴现率 → 平板 ≤6s 显示新值。
3. 包注册清单比对（ModMessages 与 packets 目录）零遗漏；handle 无注释死代码。

### 7.4 BL-14 统一客户端缓存 + 旧原生金融平板决策 + 银行平板实时
1. `ClientMarketDataCache`：加 `watermarkTick`（每条 update 记录来源 tick）；**接读者**：旧原生 FinancialTabletScreen 渲染路径全部改读缓存（若 D1=保留），或废弃该屏并移除全部旧屏路由（若 D1=下线，需把 ModMessages 中对 `FinancialTabletScreen` 的 instanceof 分支清成单一缓存/桥接消费者，杜绝死分支）。
2. 原生金融平板入口决策（D1，推荐：**保留并加 Shift 右键金融平板切换 MCEF/原生**，MCEF 缺失时自动回落原生）：打开即批量 Request*（stock list/kline/portfolio/orders/positions/trades/bank data…沿用已注册 C2S），周期刷新 ≤5s，读数与 HTTP /api 对齐（同 tick 判据）。
3. 银行平板：周期重发 `RequestBankDataPacket`（≤5s），响应路由已就位 → 实时性与 MCEF 银行数据同源一致。
4. 消除「各看各的数据」：三表面所有行情的**来源实现**统一为 (a) MCEF：HTTP /api；(b) 原生/银行/政府平板：S2C 包 → 同一 `ClientMarketDataCache`；(c) 交易所屏：BE 快照；三者共用引擎权威数据 + 各自水位线声明（§7.1 判据），UI 展示来源 tick。
**验收（BL-14，定量）**：
1. 缓存读者探针：全仓搜索 `ClientMarketDataCache.getInstance().get*` 调用 ≥1 个真实渲染消费者（D1 任一分支完成后）；orders/positions/trades 缓存更新后旧原生屏（若保留）字段与 HTTP /api/orders 等一致。
2. 银行平板：存款后（不关屏）≤6s 显示新余额；读数 = `/api/banking` 对应字段。
3. 交易动作（HTTP 下单）后 ≤6s：原生平板持仓/现金（若保留）、MCEF 持仓页、/api/positions 三方一致（同 tick 对齐判据）。
4. 无死代码：ModMessages 不再出现「只喂无读者缓存」的孤立分支（静态比对 + reviewer）。

### 7.5 BL-15 端到端一致性 + 回归
- 场景脚本：新档 → 建公司→IPO→玩家+AI 成交→分红→政府调贴现率→监管停牌/复牌→退市/要约；同 tick 采样 MCEF(/api)、银行/政府/金融平板（网络包）、交易所屏幕读数一致性断言；A 股规则与玩家手动单回归探针全绿。
- 产出 `.verify/BACKLOG_VERIFY.md` 汇总（test-engineer）。

---

## 8. 非回归冻结清单与回归矩阵

### 8.1 冻结（语义禁止改动，BL 不得触碰；只能在其外新增 gated 路径）
| 领域 | 冻结内容 | 权威文件/SPEC |
|---|---|---|
| 法币/M2 | M0 注入、信贷派生（准备金约束）、M2→通胀→商品价→priceIndex 闭环、存取款/还款守恒、税收/罚款入政府路径 | `MarketEngine.runDailyFiat`、`Bank.issueLoan/repayLoan`、ECON_FIAT_M2_SPEC |
| 黄金 | netherite 黄金定价 multiplier/金价/净购金清零日循环 | `GoldPricingSystem.tick`、NETHERITE_GOLD_SPEC |
| GBM-GARCH/CAPM | GBM Euler 步 dt 语义（DT=1/24000）、漂移/波动通道、涨跌停锚 prevClose、CAPM ke/fairValue 与 OLS β、指数 updateValue=mean×100 公式 | `PriceDiscoveryEngine`、`CapmModel`、`OlsRegression`、CAPM_SPEC |
| A 股规则 | 一手 100、T+1 账本、±10% 带、熔断/休市日历、成交连续性/滑点/冲击公式 | `OrderBook`、`SettlementSystem`、`TradingCalendar`、A_SHARE_TRADING_SPEC |
| 玩家单 | `submitOrder` 现金校验、`attributeTrade` 现金/持仓结算、`pendingTradeSettlement` 离线回放、撤单语义 | `MarketEngine:2470-2665` |
| 随机流次序 | `AICompanyManager` 共享 `Random(seed)`、既有子流消费次序（新随机必须独立新子流） | `.verify/AUDIT_CONNECTIVITY.md` §9.1/§10 |
| 持久化协议 | blob 真源 + contains 兜底 + H2 镜像幂等 + schema_meta | H2_MIGRATION.md §6/§8.6 |

### 8.2 回归矩阵（每个 BL 提交时 test-engineer 跑）
| 探针 | 断言 |
|---|---|
| `compileJava` | BUILD SUCCESSFUL exit 0 |
| 改动文件确定性 grep（非注释 `Math.random\|new Random\|ThreadLocalRandom\|UUID.randomUUID`） | 0 命中 |
| 两新档同种子 K tick | 资产价/簿/指数/宏观逐位一致（沿用 smoke SD-12 方式） |
| A 股冒烟（挂单/成交/T+1/涨停） | 与既有 AS 验收一致（复用 .verify 中 AShare harness 断言） |
| 读档冒烟 | 退出→读档持仓/现金/公司/指数一致（SD-10/SD-11，contains 兜底） |
| 长跑（tick rate 200 ≥5 模型日） | 无 NaN/发散/崩溃；quant/事件开关关闭路径 0 执行命中 |

### 8.3 明确不纳入本 backlog 的审计登记项（§8 冻结外另册，防范围蔓延）
期货 PnL 对手方镜像（F1）、国债/OMO 记账（货币#6/#8/#9）、M2 全量流通现金账本（#10）、年度预算回滚（#11）、分红/财报 gameTime→tickCounter 全量迁移（L3）、transferShares T+1 校验（M2）、交割 map 清理（F8/F10-F12）、追保宽限（F4）——这些由既有 AUDIT_CONNECTIVITY §7.2 另开任务，**除非本方向设计与之耦合并经 captain 明确放行**。

---

## 9. 交付物与验证工程约定
1. 每方向实现任务完成后，产出对应 `<方向>_SPEC.md`/VERIFY（沿用仓库既有命名），并登记 `.verify/` 探针产物（输出 txt/class/构建日志）。
2. 新功能一律**开新存档测试**（新世界种子运行），禁止只在读档旧档验证；读档测试单独做前向兼容冒烟。
3. HTTP/包测试走 `localhost:8080`（回环绑定已修，P8）；测试脚本与既有 `smoke_full_loop.ps1`、`smoke_rcon.py` 同风格。
4. 探针类放 `.verify/harness/`，编译类路径按 `.verify/harness/runtime_cp.txt` 生成流程（compileJava 后取 runtimeClasspath）。

## 10. 需 captain 裁决的规格点（其余已在本规格锁定）
| 编号 | 议题 | 推荐 | 备选 | 影响 |
|---|---|---|---|---|
| D1 | 旧原生 FinancialTabletScreen 保留 or 下线 | 保留 + Shift 右键切换/无 MCEF 回落（BL-14 分支1） | 下线并清路由 | BL-14 工作量与死代码收敛度 |
| D2 | AI 持股模型 | 机构股东桶（Σ==totalShares 恒等） | 维持漂移模型（AI 永不持股，指数只算 AI+玩家散户） | BL-8/9 语义与恒等式 |
| D3 | 并购范围 | 机构要约收购玩家公司（玩家间合并不做） | 增加玩家↔玩家要约 | BL-9 工作量翻倍 |
| D4 | 无监管角色时 IPO 审批行为 | 默认自动批（与现状等价），有 REGULATOR 后走审批 | 无角色则 IPO 永久不可 | BL-11 对旧玩法的兼容 |

---

## 附录 A：验收判据汇总速查（BL-0..15 → § 位置）
BL-0 §8；BL-1 §5.2；BL-2 §5.3；BL-3 §5.1；BL-4 §5.4；BL-5 §5.5；BL-6 §5.6；BL-7 §6.2；BL-8 §6.3；BL-9 §6.4；BL-10 §6.5；BL-11 §6.6；BL-12 §7.2；BL-13 §7.3；BL-14 §7.4；BL-15 §7.5。
