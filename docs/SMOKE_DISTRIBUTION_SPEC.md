# SMOKE_DISTRIBUTION_SPEC.md — 端到端冒烟 + 玩法连接审计 + 分发准备 需求规格

> 作者角色：architect（方案架构 / 冒烟与分发规格）
> 状态：requirements（t1）——产出规格，供 test-engineer（冒烟）、engine-dev（连接审计/修复）、asset-dev（分发打包）、reviewer（评审）落地。
> 目标：Financial Market 模组「可玩可分发」端到端实机验证与分发准备。① runClient/runServer 实机冒烟——开新档验证全玩法闭环（交易/银行/公司/期货/方块/平板 UI/交易所屏幕/钟声）无崩溃、无存档损坏、长跑稳定（多游戏日）；② 玩法连接性审计（赚钱链/货币链/方块链/公司链/期货基差/信息链），找机制漏洞并修复；③ 分发准备（MCEF/H2 jarJar、mods.toml、资源完整、音频补齐、前向兼容）；④ 修复冒烟发现的崩溃/漏洞/兼容问题。
> 硬约束（继承全部已交付规格）：确定性可复现（`SeedableRandom` 子流，禁裸 `Random`/`Math.random()`/`ThreadLocalRandom`）；H2/NBT 持久化兼容（新增字段 `contains` 兜底，读档不失败）；**不改已交付核心经济语义**（法币 M2、GBM/GARCH、A 股规则、黄金、订单簿/结算公式）；时间权威一律用 `MarketEngine.tickCounter`，非 `gameTime`。

---

## 0. 基线事实（证据快照，2026-08-29 工作树）

| 项 | 证据 | 影响 |
|---|---|---|
| 构建基线 | `compileJava`/`build` exit 0（.verify/coldstart_build.txt）；产物 `build/libs/financialmarket-1.0.0.jar`（1,140,674 B）+ `financialmarket-1.0.0-all.jar`（3,635,880 B，jarJar 含 H2） | SD-0 前置门可复用 |
| runClient 基线 | 08-29 22:33–22:42 成功：新世界「新的世界 (9)」，Dev 登录，H2 打开/关闭，全 chunk 落盘，正常退出；仅 MCEF「JCEF is still running, killing to avoid lingering processes」警告（已知无碍，但分发前需确认不残留进程） | 冒烟基线成立 |
| runServer 基线 | 08-28 23:23 成功：`Done (5.059s)!`，H2 打开于 `runs/server/world/financial_market/db/financialmarket`；离线模式；**无玩家加入 → 引擎未初始化**（引擎在 `onPlayerLoggedIn` 懒创建） | 冒烟须明确：无头服只验「起停/H2/HTTP/空转无异常」，全玩法闭环须有玩家（runClient 集成服） |
| 历史崩溃① | crash-2026-08-11：`Invalid null NBT value with key fund`（MarketEngine.save 空值写 NBT）——源码已加空值保护 | 回归项 |
| 历史崩溃② | crash-2026-08-12：`Cannot read field "drift" because "profile" is null`（PriceDiscoveryEngine.step）——已重构 GbmState 驱动 | 回归项 |
| 历史崩溃③ | crash-2026-08-29 ×3（20:48/20:49/20:57）：**Rendering Block Entity** `Cannot get property facing ... does not exist in Block{financialmarket:exchange_screen}`（`ExchangeScreenGrid.compute:50`）；当前源码 `ExchangeScreenBlock extends HorizontalDirectionalBlock`（FACING 已注册，blockstates/exchange_screen.json 亦含 facing 四向）→ **疑似已修，但 22:33 成功会话未放置过 exchange_screen，必须实机回归** | 最高优先冒烟项 |
| MS-1 阻断项 | COLD_START_VERIFY 判定 FAIL：`/api/kline` 因 `getTechnicalKlines` 产出 `Double.NaN`、Gson 2.10.1 默认拒绝特殊浮点而抛异常；**当前源码已修**：`PriceDiscoveryEngine.jsonSafe()`（NaN/Inf→null，:491-496）应用于 MA（:541）与指标（:547） | 实机复验项（SD-3） |
| 确定性 | `engine/random/SeedableRandom`（SplitMix64 + mix64/derive）；冷启动路径无裸随机（COLD_START_VERIFY §3）；`AICompanyManager` 的 `new Random(seed)` 为已交付共享流，**禁止重排其消费顺序** | 审计红线 |
| 持久化 | H2 表：schema_meta（schema_version=1）/ kline / asset / trade_point / ai_company / company / index_state / index_constituent / engine_kv / quant_script；前向兼容用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`；引擎 blob 走 engine_kv，NBT Attachment 镜像；`loadInternal` 大量 `contains` 兜底 | SD-10/11 判据来源 |
| MCEF | `libs/mcef-neoforge-2.1.6-1.21.1.jar`（215 KB，真 NeoForge mod：内含 neoforge.mods.toml + mcef.mixins.json + org/cef Java 绑定）；**未 jarJar、未在 mods.toml 声明依赖**；原生库由 MCEF 自身运行期下载到 `mods/mcef-libraries/windows_amd64`（含 sha256 校验文件）；`FinancialTabletItem.openScreen` 对 MCEF 缺失 `catch(Throwable)` 降级不崩 | 分发核心项 SD-14/16 |
| 音频 | `exchange_open_bell.ogg`（170,532 B）/ `exchange_close_bell.ogg`（44,267 B）均为**合法 Ogg**（OggS magic 0x4F-67-67-53）；sounds.json 引用 `block/exchange_open_bell`、`block/exchange_close_bell`；两语言文件均有 subtitle 键；遗留 `PLACE_AUDIO_HERE.txt` 提示文件 | SD-13/15 |
| Web 前端 | `html/echarts.min.js` 本地打包（1,024,740 B，真 echarts 包）；index.html 无 CDN 引用；桥接优先 + HTTP 轮询兜底（`enableHttpMode`）；HTTP 服务端口 8080（`LocalHttpServer`，ServerStarted 启动） | 离线可用判据 |
| 命令面 | `/fmarket company create/level/levelup/leveldown/ipo/subscribe/transfer/report/dividend/delist/bind-area/scan/info/shareholders`；`/fmarket p2 on|off`；`/fmarket role assign|revoke` | 冒烟驱动入口 |
| 方块/物品 | 4 方块（market_block / delivery_block / exchange_screen / trading_terminal，BlockInit 注册 + BE + IItemHandler 能力）+ 3 平板（financial_tablet→MCEF、bank_tablet→BankTabletScreen、government_tablet→GovernmentTabletScreen） | 冒烟对象 |
| GameTest | 全工程无 `@GameTest` 类 | 冒烟不依赖 gametest；用命令 + HTTP + 日志断言 |
| 商品种子 | `data/financialmarket/commodity_prices.json`（静态资源，只读） | 期货/商品链基线 |

---

## 1. 端到端冒烟计划

### 1.0 前置门（SD-0）

1. `.\gradlew.bat compileJava --console=plain` → exit 0。
2. `.\gradlew.bat build --console=plain` → exit 0，`:jar` `:jarJar` 出包。
3. 产物断言：
   - `financialmarket-1.0.0-all.jar` 内含 `org/h2/**`（jarJar 生效）、`META-INF/neoforge.mods.toml`、`assets/financialmarket/**`、`data/financialmarket/**`；
   - `financialmarket-1.0.0.jar` 不含 H2（纯模组 jar 语义正确）。
4. 确定性静态门：对**本次改动文件** grep `Math.random` / `new Random(` / `ThreadLocalRandom`（仅允许注释中出现；`AICompanyManager` 既有共享流除外）。
5. 测试前快照：`runs/client/saves`、`runs/server/world` 移动到 `runs/_archive/<时间戳>/`（保留证据，不删）。

### 1.1 runServer 无头冒烟（SD-1）

目的：验证专用服部署形态 —— 起停、H2、HTTP、空转稳定性、存档落盘。全玩法闭环见 1.2（需玩家）。

步骤（背景 job 启动，日志落 `.verify/smoke_server_*.txt`）：
1. `.\gradlew.bat runServer --console=plain`（`runs/server`，自动建新 world）。
2. 等待日志出现 `Done (5.059s)!` 且无 `Exception`/`ERROR`/`FATAL`（白名单：MC 常规 WARN 允许）。
3. 断言 H2：日志 `H2 数据库已打开: .../runs/server/world/financial_market/db/financialmarket`；目录存在且含 `.mv.db`。
4. 断言 HTTP：`Invoke-WebRequest http://localhost:8080/api/stocks`（及 `/api/index`、`/api/kline?asset=<首个资产>&type=DAILY`）返回 200 合法 JSON（注：引擎若因无玩家未初始化，/api/* 可能返回空列表——**记录该行为**，不作为失败，但要求 handler 不 500、不抛异常）。
5. 空转 5 分钟：无异常堆栈；线程数/内存无失控增长。
6. 停服（stdin `stop` 或 Ctrl+C 优雅）→ 断言 `H2 数据库已关闭`、全部 chunk 落盘、world 目录结构完整。
7. 复启同 world → 断言可正常加载、H2 复用（不重复建表、schema_version 仍为 1）。

> 备注：`--nogui` 专用服从 stdin 读命令，pwsh 可用管道/`Start-Process -RedirectStandardInput` 下发 `stop`；若需真实玩家闭环，用 1.2 的 runClient 以 `localhost` 直连 25565 一并覆盖（网络包/持仓同步路径在专用服同样生效）。

### 1.2 runClient 实机冒烟（全玩法闭环，SD-2/3/4/5/6/7/8）

目的：以集成服（开新档）承载真实玩家 Dev，逐链走通全部玩法。

**A. 冷启动**（SD-2）
1. 清理存档后 `.\gradlew.bat runClient`，新建世界（记录世界种子，供 1.4 复现）。
2. 等待 Dev 登录；断言日志：`📤 发送市场列表`、`📤 发送持仓`、`📤 发送 K 线数据`；H2 打开；`/api/stocks` 资产数 > 0。
3. 观察 3 分钟：无异常；帧率正常；MCEF 缓存目录生成（`mods/mcef-cache`）。

**B. 交易链**（SD-3，回归 MS-1/盘口/08-29 渲染崩溃）
1. `/give @s financialmarket:financial_tablet`，右键打开 MCEF 页面 → 断言：echarts 日K 非空白（≥1 根可见）、盘口买/卖五档均有量（`seedColdStartBook` 生效）、委比正常。
2. `Invoke-WebRequest http://localhost:8080/api/kline?asset=<id>&type=DAILY` → 200 且 JSON 无 NaN（Gson 不再抛）；`/api/depth` bids/asks 双侧非空。
3. 挂限价买单/卖单（页面或网络包路径）→ 断言订单进入 orderbook、撮合产生成交、`T+1` 冻结生效、持仓/现金经 PortfolioSyncPacket 更新；撤单生效。
4. 回归崩溃③：放置 `exchange_screen` 方块并保持在视野内 → **不得出现 Rendering Block Entity 崩溃**（历史 3 连崩场景）。
5. 回归崩溃①/②：正常存档→读档→再成交（NBT/H2 双路径写入，fund/drift 不再空值）。

**C. 银行链**（SD-4）
1. `/give @s financialmarket:bank_tablet` → 开卡（Luhn 校验、UUID 绑定）、存款、取款、查余额。
2. 断言 M1/M2 分层度量随存款/贷款变化（`CentralBank`/`BankManager` 日志或命令可见）。
3. 存档→读档后余额/卡片绑定保持。

**D. 公司链**（SD-5）
1. `/fmarket company create <name>` → `levelup` → `bind-area` → `scan`（AssetScanner 估净资产）→ `ipo`（定价/发行）→ `subscribe`（玩家认购，断言现金借记、持股入账）→ 新资产进入 `/api/stocks`。
2. 上市后股票按 A 股规则可交易（一手 100、T+1、±10%、盘口）。
3. `report`（财报）、`dividend`（分红：留存收益 → 股东现金）、`delist`（退市回购清偿）全流程可执行且现金/股权账面一致。

**E. 商品 + 期货链**（SD-6/7）
1. 商品现货：`CommodityMarket` 买/卖物品（铁锭/金锭等），断言价格来自 `commodity_prices.json` 驱动、买卖记账正确。
2. 期货：`FuturesMarket` 开仓（保证金）、逐日盯市结算、价格限制；断言**基差（期货−现货）随交割月收敛**；交割日经 `delivery_block` 实物交割（`DeliveryBlockEntity.tryPhysicalDelivery`）。
3. 市场方块：`market_block` 经漏斗/管道输入售货物品 → 断言售货结算收益计入**绑定储蓄卡**（非背包现金，BT-10 语义）；`tradeBlock.enabled` 开关（`/fmarket p2` 或引擎开关）生效。

**F. 交易所屏幕 + 钟声**（SD-8/13）
1. 放置 4 类方块各 1（market_block / delivery_block / exchange_screen / trading_terminal）→ 断言方块模型/纹理/blockstates 无缺失警告，BE 正常 tick。
2. `exchange_screen` 右键循环三种模式（核心指数/大盘行情/市场总览）→ 断言画面数据为实时数据（非占位）。
3. 开市/休市（TradingCalendar 会话边界）→ 断言 `exchange_open_bell`/`exchange_close_bell` 触发：日志 SoundEvent、游戏内可闻、subtitle 显示。
4. `trading_terminal` 右键 → MCEF 交易页（与 financial_tablet 同源 URL），断言可交易。

**G. 政府平板 + 角色**（信息链一角）
1. `/give @s financialmarket:government_tablet` → 政府数据页（税收/支出/国债）。
2. `/fmarket role assign <player> CENTRAL_BANKER` / `FINANCE_MINISTER` → 服务端唯一权威校验（RETAIL 无货币政策执行权）。

### 1.3 长跑稳定（多游戏日，SD-9）

1. 沿用 1.2 世界：`/tick rate 200`（1.21.1 支持，需作弊）或自然推进，连续运行 **≥ 5 个游戏日**（等价引擎 tick 数：1 日 = 24000 tick；A 股交易日历按 TradingCalendar 会话推进）。
2. 每游戏日采样：`MarketEngine.tickCounter` 单调递增、`/api/stocks` 资产数稳定、成交笔数/K线 bar 数正常增长、日志无异常堆栈、无死循环（tick 耗时平稳）。
3. 期间穿插：成交、存贷、公司分红、期货盯市各一次 → 状态与采样一致。
4. 结束断言：CPU/内存无持续增长；正常退出（无 `Stopping!` 前崩溃）。
5. 长跑期间若有崩溃 → 该崩溃即为**阻断项**，按 §4 回归矩阵登记并修复后复验。

### 1.4 存档完整性与前向兼容（SD-10/11）

1. **完整性**：1.2 世界正常退出后，比较退出前/后状态快照——资产价格、订单簿 depth、持仓/现金、银行卡余额、公司/股东、期货持仓、AI 公司、指数在 `engine_kv` blob + NBT Attachment 双路径恢复一致；`level.dat`、`financial_market/db/*.mv.db` 存在且非 0 字节；二次读档可连续操作（再下一单、再存一笔）。
2. **前向兼容**：构造「旧档」夹具——取上一基线存档（如 runs/_archive 或历史世界），或人为剥离新增字段的 NBT/H2 记录 → 读档不得失败、缺字段走默认（`contains` 兜底）；`ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 幂等（重跑不炸）。
3. H2 完整性：`schema_meta` 的 schema_version=1 不变；引擎 blob 序列化/反序列化往返一致（可用 .verify 既有 `H2StoreReadHarness`/`N3PersistenceHarness` 基建扩展）。

### 1.5 确定性复现（SD-12）

1. 实机法：同一种子开两个新档（同档名不同目录），各跑 N tick（同一 tickCounter 采样点），对比首个/随机 3 个资产的 DAILY close 序列——逐位一致。
2. harness 法（既有基建）：复用 .verify 的引擎 harness（classpath 与 build 一致），固定 engineSeed 跑固定 tick 两次，断言价格/结算/成交序列逐元素一致。
3. 静态门：本次改动文件无裸随机新增；`AICompanyManager` 共享流消费顺序未被重排。

---

## 2. 玩法连接性审计清单（六链，engine-dev 逐链追源码）

> 审计方法：对每条链，沿「入口 → 消费 → 产出 → 下一个入口」逐方法追接线，标记**打通/断点/死路/账实不一致**；产出 `AUDIT_CONNECTIVITY.md` 与修复清单。判据：无断点、无重复记账、无凭空生钱/湮没钱、读档恢复后链路可继续。

### 2.1 赚钱链（玩家如何赚钱、钱从哪来）
- 闭环期望：市场方块售货 → 卡入账 → 存款生息 → 股票低买高卖 → 公司分红 → 期货套利 → 政府/央行政策影响价格 → 玩家盈亏。
- 接线点：`MarketBlockEntity.tick`（售货结算）→ `BankCardService`/`Bank.depositAccounts` → `SettlementSystem`（股票 T+1 结算）→ `CompanyCommand.dividend`（分红）→ `FuturesMarket`（结算/保证金）→ `InflationSystem`/`MacroEnvironment`（价格锚）。
- 已知风险：
  - 售货收益是否**只**进绑定卡、不重复进背包现金？
  - 分红是否从留存收益真实扣减、按股东持股比例分配、重复登录不重发？
  - 期货盈利/亏损是否实时计入可用保证金与卡余额？
  - 是否存在「凭空生钱」路径（如未扣成本即入账、结算重复执行、卖空无需资金）？

### 2.2 货币链（M0/M1/M2 与玩家可见货币）
- 闭环期望：央行基础货币 → 准备金率 → 信贷派生存款（M2 扩张）→ 玩家存取款/贷款可见 → 政府税收回笼 → 通胀反馈价格。
- 接线点：`CentralBank`（准备金/贴现/逆回购）→ `BankManager`/`Bank`（派生存款）→ `MoneyAggregate`（M0/M1/M2 分层）→ `CurrencySystem`（物品现金存取）→ `Government`/`TaxSystem`（税收/国债）→ `InflationSystem`。
- 已知风险：
  - 贷款是否真实派生存款（货币乘数 = 1/准备金率）？FM-2 后 `NETHERITE_INGOT` 是否仍残留 32:1 固定汇率（应已取消，`CurrencySystem` 不得再固定锚金）？
  - 玩家存款/取款是否同步 M1/M2（增发/回笼），还是仅余额 UI 变化？
  - 政府税收从谁收、收进哪、是否有发行国债回笼流动性的真实记账？

### 2.3 方块链（四个方块与引擎/货币/玩家实体的接线）
- 闭环期望：`market_block`（售货→卡）↔ `delivery_block`（期货交割/售货）↔ `exchange_screen`（行情展示 3 模式 + 钟声）↔ `trading_terminal`（MCEF 交易）。
- 接线点：`BlockInit` 注册与 IItemHandler 能力（两 BE 共用 `getInventory`，`DeliveryBlockEntity extends MarketBlockEntity` 已核，无 cast 风险）→ `MarketBlockEntity.tick`（`tradeBlock.enabled` 门控）→ `DeliveryBlockEntity.tryPhysicalDelivery` → `ExchangeScreenBlockEntity.tick`（铃声去重/模式）→ `ExchangeScreenGrid.compute`（**facing 崩溃回归点**）。
- 已知风险：
  - `exchange_screen` 三模式数据是否实时来自引擎（`ExchangeScreenDataService`/`ExchangeScreenSnapshot`），非死数据？
  - 钟声触发时机是否与 TradingCalendar 开/休市严格对齐？同屏多块是否去重（grid prime 判定）？
  - `delivery_block` 实物交割是否真扣期货持仓/真给物品，还是仅记账？

### 2.4 公司链（创建→上市→交易→分红→退市）
- 闭环期望：`create → levelup → bind-area → scan（净资产）→ ipo（定价/发行/认购）→ 股票入 A 股引擎可交易 → report（财报）→ dividend（分红）→ delist（回购清偿）`。
- 接线点：`CompanyManager`/`CompanyData`/`CompanyIpoState` → `AssetScanner`/`ListingValidator`/`DelistingMonitor` → `MarketEngine.registerPlayerCompany`/`submitOrder` → `CompanyCommand.*`（含 `boundCard` 前置校验）。
- 已知风险：
  - IPO 认购是否真实借记玩家现金、贷记股份？上市后 `prevClose=ipoPrice` 是否生效（涨跌停锚）？
  - 公司股票交易是否完全复用 A 股规则（一手/T+1/±10%）且不额外重复收税/费？
  - 分红与退市清算是否账实一致（股东现金 vs 公司留存收益）？读档后股东结构是否恢复？

### 2.5 期货基差链（现货 vs 期货收敛）
- 闭环期望：`CommodityMarket` 现货价 ↔ `FuturesMarket` 合约价 → 基差 = 期货−现货，随交割月收敛；保证金/逐日盯市/涨跌停（CZCE 规则）→ 交割日实物交割（delivery_block）或现金平仓。
- 接线点：`CommodityRegistry.autoRegister`（应**真正被调用**——CF-2 曾标记死代码）→ `FuturesMarket.open/close`（保证金走卡）→ 逐日结算 → `DeliveryBlockEntity.tryPhysicalDelivery`。
- 已知风险：
  - 期货价是否与现货价有锚定（无锚 → 基差不收敛、纯赌博）？
  - 结算/强平/追保是否确定性（tickCounter 权威，非 gameTime）？
  - 实物交割是否真能发生（物品来源/去向），还是死路径？

### 2.6 信息链（数据 → 展示）
- 闭环期望：引擎状态（价格/K线/盘口/宏观/公司/银行）→ `LocalHttpServer` / 网络包 → 平板 UI（echarts MCEF、BankTablet、GovernmentTablet）与交易所屏幕 → 玩家可据此决策。
- 接线点：`MarketDataCollector`/`TechnicalIndicatorCalculator`/`PriceDiscoveryEngine.getTechnicalKlines` → `LocalHttpServer`（`/api/stocks|kline|depth|index|...`）→ `index.html`（**桥接优先、HTTP 兜底双路径一致**）→ `FinancialMCEFScreen`/`ExchangeScreenDataService`/`ClientMarketDataCache`。
- 已知风险：
  - 桥接包（`StockDataResponsePacket`/`OrderBookSnapshotPacket`）与 HTTP API 字段是否一致（MS-1 曾要求补 `time` 键）？
  - `ClientMarketDataCache` 是否存在「只写不读」的废弃缓存（曾审计确认）？
  - 页面数据刷新是否随 tick 推进（非静态快照）；异常是否被 `.catch(()=>{})` 静默吞掉（应打日志）？

---

## 3. 分发准备清单（asset-dev）

### 3.1 依赖打包（MCEF / H2 jarJar）
- [ ] **H2**：确认 `financialmarket-1.0.0-all.jar` 内含 `org/h2/**` 与 jarJar 元数据（`META-INF/jarjar`）；发布物用 **all.jar**（单 jar 自包含 H2）。dev 运行已由 `runs.configureEach { runtime 'com.h2database:h2:2.2.224' }` 覆盖。
- [ ] **MCEF**（当前最大分发缺口）：`libs/mcef-neoforge-2.1.6-1.21.1.jar` 只进编译 classpath，**不在产物内、不在 mods.toml 依赖里**。处置二选一（建议 A）：
  - A：`neoforge.mods.toml` 增加 `[[dependencies.financialmarket]] modId="mcef" type="required" versionRange="[2.1.6,)"`，分发包 mods/ 目录附带 `mcef-neoforge-2.1.6-1.21.1.jar`；MCEF 原生库首次运行自动下载（`mods/mcef-libraries/windows_amd64`，sha256 校验）——离线用户需预置。
  - B：jarJar 打进 all.jar（215 KB 可行）——但 MCEF 自带 mixins + 独立 mods.toml，jarJar 嵌套需实机验证 mixin 与 mod 发现正常，风险高；**不推荐，除非实测通过**。
- [ ] 验证：把 all.jar + mcef jar 放入干净 mods 目录启动 runClient——MCEF 页可开、无 ClassNotFound；**仅 all.jar 无 mcef 时**：游戏能启动、`financial_tablet` 右键不崩（catch(Throwable) 降级，控制台打印错误）——记录该降级行为作为文档说明。
- [ ] 依赖树复核：`./gradlew dependencies` 输出与发布物一致（Gson 2.10.1 需打进 all.jar——**确认 Gson 是否被 jarJar 或随 neoforge 提供**；若未打包且未声明，分发后 `/api/*` 会 ClassNotFound）。

### 3.2 mods.toml / 元数据
- [ ] `META-INF/neoforge.mods.toml`：modId/version=1.0.0 正确；neoforge 依赖 `[21.1.0,)` 合理；**补 MCEF 依赖（3.1-A）**；license=MIT；authors 占位 `YourName` 替换为实际作者；logoFile=financialmarket.png 存在。
- [ ] 发布名/版本一致性：`gradle.properties`（mod_id/neo_version/minecraft_version）与 mods.toml、build.gradle version=1.0.0 三处一致。

### 3.3 资源清单
- [ ] **语言**：`lang/en_us.json` 与 `zh_cn.json` 键集一致（diff 无缺失键）；覆盖：物品/方块/字幕（`subtitles.financialmarket.exchange_open_bell/close_bell`）/提示语。缺失键会导致界面英文回退——逐键对拍。
- [ ] **纹理/模型/blockstates**：7 个注册项（4 方块 + 3 平板）的 item model JSON 引用的 texture 路径全部存在（`textures/item/*.png`）；blockstates 四向 facing 与模型 y 旋转齐全；`textures/item/*.png`（289/287/288 B）为占位小图——**确认非 1×1 空白像素**，否则补齐真实纹理（可接受最小可用艺术，但不得白图）。
- [ ] **音频**：两个 .ogg 为合法 Ogg/Vorbis（已验 magic）；`PLACE_AUDIO_HERE.txt` 在分发包中**移除**（或移入 docs/）；实测开/休市可闻（SD-13）。
- [ ] **Web**：`html/index.html` + `html/echarts.min.js`（1.02 MB 本地包）均在产物内；页面离线可渲染（断网模拟：localhost 加载不依赖外网）。
- [ ] **数据**：`data/financialmarket/commodity_prices.json` 在产物内。
- [ ] 启动日志扫描：runClient 完整启动日志中无 `Missing model`/`Missing texture`/`Failed to load`/`Skipping loading sound` 类警告。

### 3.4 前向兼容（存档）
- [ ] 新字段一律 `contains` 兜底（§1.4 已验）；H2 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 幂等。
- [ ] 升级路径文档：1.0.0 → 后续版本只允许「读旧档成功 + 新字段默认值」，禁止破坏性 schema 变更。
- [ ] schema_version 语义：引擎 blob 结构变更时递增并写迁移逻辑；冒烟不要求实现新迁移，但必须确认当前 1.0.0 读旧档（1.0.0-Beta 前）不失败。

### 3.5 分发物清单（zip 结构）
```
financialmarket-1.0.0-dist.zip
├── mods/
│   ├── financialmarket-1.0.0-all.jar   (含 H2；含 assets/data/元数据)
│   └── mcef-neoforge-2.1.6-1.21.1.jar  (外部必需依赖，方案 A)
├── README.md   (安装：NeoForge 21.1.x + Java 21；首次运行 MCEF 原生库下载说明/离线预置说明；存档兼容说明)
└── LICENSE     (MIT)
```

---

## 4. 验收标准（SD-0 .. SD-N，全部开新档基线）

| 编号 | 验收项 | 判据 | 归属 |
|---|---|---|---|
| SD-0 | 构建/产物门 | compileJava+build exit 0；all.jar 含 H2+assets+mods.toml；确定性静态门无新增裸随机 | test-engineer（前置） |
| SD-1 | runServer 起停 | 新档 `Done`、H2 打开/关闭、HTTP 8080 各端点不 500、空转 5 分钟无异常、复启同档正常 | test-engineer |
| SD-2 | runClient 冷启动 | 新档 Dev 登录、市场列表/持仓/K线首包送达、资产数>0、3 分钟无异常 | test-engineer |
| SD-3 | 交易闭环 | MCEF 页日K非空白（MS-1 复验）、盘口双侧有量、挂单/成交/撤单/T+1/持仓现金同步、`/api/kline` 合法 JSON 无 NaN | test-engineer + engine-dev(数据侧) |
| SD-4 | 银行闭环 | 开卡(Luhn)/存款/取款/余额；M1/M2 随存取变化；读档保持 | test-engineer |
| SD-5 | 公司闭环 | create→levelup→bind-area→scan→ipo→subscribe(现金借记)→上市可交易→report→dividend→delist 全流程账实一致 | test-engineer + engine-dev |
| SD-6 | 商品/方块售货 | 商品买卖记账正确；market_block 售货收益入绑定卡；tradeBlock.enabled 门控生效 | test-engineer |
| SD-7 | 期货闭环 | 开仓(保证金)/逐日盯市/强平确定性；基差收敛；交割日 delivery_block 实物交割真实 | test-engineer + engine-dev |
| SD-8 | 方块/屏幕/终端 | 4 方块放置渲染无崩溃（**回归 08-29 三连崩**）、exchange_screen 三模式数据实时、trading_terminal MCEF 可交易 | test-engineer |
| SD-9 | 长跑稳定 | ≥5 游戏日连续运行无崩溃、tickCounter 单调、采样一致、内存/CPU 无失控、正常退出 | test-engineer |
| SD-10 | 存档完整 | 退出→读档后价格/订单簿/持仓/银行卡/公司/期货/指数 H2+NBT 双路径一致；二次操作正常 | test-engineer |
| SD-11 | 前向兼容 | 旧档（缺新字段）读档不失败、默认值兜底；ALTER 幂等 | test-engineer + engine-dev |
| SD-12 | 确定性 | 同种子两档前 N tick 资产价格序列逐位一致；无新增裸随机 | test-engineer + engine-dev |
| SD-13 | 音频 | 开/休市钟声触发、可闻、subtitle 显示；ogg 合法 | test-engineer + asset-dev |
| SD-14 | 依赖声明 | mods.toml 含 neoforge 依赖 + MCEF 依赖（或等价文档）；Gson/H2 运行期可达 | asset-dev |
| SD-15 | 资源完整 | lang 双语言键集一致；纹理/模型/blockstates 无缺失；启动日志无 Missing 警告；echarts 本地包离线可用；PLACE_AUDIO_HERE.txt 移除 | asset-dev |
| SD-16 | MCEF 分发 | 分发 zip：all.jar + mcef jar；干净 mods 目录实测 MCEF 页可开；缺 mcef 时降级不崩且有文档说明 | asset-dev |
| SD-17 | 历史崩溃回归 | fund 空 NBT、drift null、exchange_screen facing 三例不再复现（含读档/渲染场景） | test-engineer + engine-dev |
| SD-18 | 连接审计闭环 | 六链审计报告 `AUDIT_CONNECTIVITY.md`：每链标注 打通/断点/死路 + 修复清单；阻断性断点修复后复验通过 | engine-dev + reviewer |

---

## 5. 风险与边界

1. **MCEF 是外部必需 mod**：dev 运行靠 classpath 注入，分发必须显式处理（§3.1）。若评审坚持单 jar 分发，需实测 jarJar 嵌套 MCEF（mixins/独立 mods.toml），否则维持「mods 目录双 jar + 依赖声明」。
2. **引擎懒初始化**：无头专用服无玩家时引擎不 init——runServer 冒烟只覆盖起停/HTTP/空转；全玩法闭环以 runClient 集成服为准；如需无头闭环，需后续增加 `server.properties` 脚本玩家或 gametest（本期不做，记录为后续项）。
3. **exchange_screen 崩溃为最高优先回归**：08-29 三次同栈崩溃发生于「放置后渲染」路径；源码已疑似修复，但 22:33 成功会话未放置该方块——**SD-8 必须实机放置并保持视野**。
4. **占位纹理**：`textures/item/*.png` 尺寸过小，疑似占位像素；若评审认定「可玩」要求真实贴图，asset-dev 需补齐（列为 SD-15 检查项，不阻断功能冒烟）。
5. **双语言键集**：仅当 en/zh 键集不一致时才阻断；键值翻译质量不属本期冒烟范围。
6. **MCEF 残留进程**：日志尾部 `JCEF is still running, killing to avoid lingering processes` 为已知行为；冒烟需确认进程树无残留 java/cef 僵尸（分发后玩家体验项）。
7. **不改核心语义**：六链审计与修复**只允许**补接线/修崩溃/修账实不一致，禁止改 GBM/GARCH、A 股规则、M2 公式、黄金、订单簿/结算公式；任何「机制增强」须另开规格评审。

---

## 6. 产出物清单（按角色分派）

- **architect（本文件）**：`SMOKE_DISTRIBUTION_SPEC.md`（需求与验收口径）。
- **test-engineer**：`.verify/SMOKE_VERIFY.md`（冒烟执行报告，含 SD-0..17 逐项证据、日志/HTTP 快照、存档往返断言）；新增 `.verify/smoke_server_*.txt`、`.verify/smoke_client_*.txt` 原始日志。
- **engine-dev**：`.verify/AUDIT_CONNECTIVITY.md`（六链审计 + 断点/死路清单）；修复项（崩溃回归、账实不一致、链路断点）源码改动。
- **asset-dev**：分发物 `build/dist/`（或等价路径）zip + `README.md` + LICENSE；`mods.toml` 依赖声明；资源补齐（占位纹理/PLACE_AUDIO_HERE 清理）。
- **reviewer**：按 §4 逐项评审；任一 SD 失败 → 对应角色修复后复验（评审不得自行判 pass）。

---

> **行号时效性**：本规格引用的行号（如 PriceDiscoveryEngine:491）为审计快照时点，以方法名/语义为准。冒烟以「可复现的实机行为」为最终判据，不以行号为准。
