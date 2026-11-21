# ECON_REMAINDER_SPEC — 剩余经济引擎子系统需求规格 + 可玩性评估（需求契约，供 t2/t3/t4 实施、t5 验证、t6 评审）

> - 角色：architect（方案架构 / 剩余引擎需求规格 + 可玩性评估）为任务 t1 产出。
> - 输入：团队 Goal + 当前源码审计（本规格对照实际源码逐条给出文件/行号/方法签名）+ 已交付前置（`ECON_P0_SPEC.md`/`ECON_P1_SPEC.md`/`ECON_P2_SPEC.md`、`A_SHARE_TRADING_SPEC.md`（AS）、`H2_MIGRATION.md`、`ECON_FIAT_M2_SPEC.md`）。
> - 用途：engine-dev 按本规格实现 t2（量化）/t3（融资融券）/t4（证券研究+回购+危机+周期）；test-engineer 按本规格实现 t5（整体验证+构建+可复现+持久化）；reviewer 按本规格验收 t6。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 tick 内 O(资产/agent/订单) 常数级计算、确定性（同种子可复现）、H2 兼容。
> - 范围：**仅团队 Goal 点名的「剩余未完成」子系统**。已交付的 P0/P1/P2/A股交易规则/FM 法币 M2 语义**不得回归**。

---

## 0. 目标与全局验收（对应团队 Goal）

本次落地 5 个子系统块，完成后须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| R0-1 | 编译通过 | `./gradlew compileJava` 退出码 0 | 每项实施 |
| R0-2 | 可运行 | server/client run 启动无 NPE、无死循环，`MarketEngine.tick` 持续推进 | t5 冒烟 |
| R0-3 | 同种子可复现（硬约束） | 同一 `engineSeed` 跑固定 N tick，两次成交价序列 + 危机/量化/强平事件序列逐元素一致；`grep -rn "Math.random\|new Random\|ThreadLocalRandom" src/main/java` **不得出现本次新增**的调用（允许既有历史遗留，但本次改动禁止引入新随机源） | 全部 |
| R0-4 | 可触发 | 每子系统至少有一个**玩家可从 UI/命令触发的入口**，且实际产生引擎副作用（不再是空壳/死代码） | t2~t4 + t5 |
| R0-5 | 已纳入 tick 与持久化 | 每个子系统状态进入 `MarketEngine.tick` 的执行路径，并进入 `MarketEngine.save()/load()`（NBT 附件 + H2 `engine_main` blob 双通道），读档不丢失、旧档缺字段用 `contains` 兜底不失败 | 全部 + t5 |
| R0-6 | A 股语义不回归 | aShare 开启时，融资/融券/量化下单/自营/回购均遵守涨跌停带、一手 100 股买入整数倍、T+1 可用量校验；`aShare.enabled=false` 时回落 P0/P1/P2 基线（隔离，AS-7） | 全部 + t5 |
| R0-7 | 空壳消除 | 以下空壳类被填实或明确落地：`QuantScriptEngine`、`Strategy`、`MarginManager`、`ShortPosition`、`ResearchReport`、`EconomicCycle`（crisis 包） | t2~t4 |

---

## 1. 量化交易引擎（t2，对应 R-Q）

### 1.1 现状（审计）
- `engine/quant/QuantScriptEngine.java`（4 行）空壳。
- `engine/quant/Strategy.java`（4 行）空壳。
- `engine/quant/QuantAPI.java`：有 `TradingStrategy` 接口 + `DoubleEMACrossStrategy`（EMA 金叉/死叉），有 `addStrategy/executeStrategies`，但：
  - `network/packets/EnableQuantStrategyPacket.java:29-30` 每次 `new QuantAPI()`（**非引擎单例**），策略注册后**从未被执行**（`executeStrategies` 无调用方，订单从未进入订单簿）。
  - `DisableQuantStrategyPacket.java` 的 handler 为空。
- `SaveQuantScriptPacket/RequestQuantScriptPacket/QuantScriptDataPacket`：已能读写 H2 `quant_script` 表（脚本源码存 / 取正常），但 `QuantScriptEngine` 不执行。

### 1.2 需求

**1. QuantAPI 引擎单例化**
- `MarketEngine` 新增 `private final QuantAPI quantAPI;`，构造 `this.quantAPI = new QuantAPI(seed)`（`QuantAPI` 增一个 `QuantAPI(long seed)` 构造，内部建 `SeedableRandom` 子流；无参构造保留 `new QuantAPI(0L)` 兼容）。新增 getter `getQuantAPI()`。
- `QuantAPI` 增方法（签名由 implementer 确认并注释）：
  - `enableStrategy(UUID playerId, String name, int fast, int slow)`：注册 `DoubleEMACrossStrategy` 及（可选）其它预置；返回成功布尔。
  - `disableStrategy(UUID playerId)`：清空该玩家策略。
  - `List<Order> generateOrders(MarketState state, UUID playerId, String assetId)`（复用现有 `executeStrategies` 语义，可改名或保留）。
- **确定性**：EMA 金叉/死叉本身纯确定性（无随机）——保持。若新增网格/马丁格尔等含随机策略，随机一律走 `SeedableRandom` 子流。

**2. `Strategy` 类落地**
- 把空 `Strategy.java` 填为「策略注册/持有」模型：字段 `playerId/strategyName/fastPeriod/slowPeriod/enabled` + 构造/`get`。`QuantAPI` 内部用 `List<Strategy>` 持有玩家策略（替换或兼容现有 `playerStrategies` 映射，由 implementer 定），`DoubleEMACrossStrategy` 作为其执行委托。
- 目标：`EnableQuantStrategyPacket` 能恢复/查询玩家的持久化策略。

**3. `QuantScriptEngine` 落地**
- 从 H2 `quant_script` 表加载脚本源码。由于**服务端禁用外部 JS 引擎**（Nashorn 在 Java 15+ 移除、引入 Rhino/GraalJS 属于重量级外部依赖，与「离线、无新增大依赖」约束冲突），实现为**轻量内建 DSL**：
  - 支持预置指令集：`EMA_CROSS fast slow`、`GRID step lots`、`MARTINGALE baseStep doubleOnLoss`（指令集由 implementer 在 `QuantScriptEngine` 内以常量表 + 简单 `String` 解析实现）。
  - 解析失败 / 空脚本 / 未知指令 → 该脚本 `disabled`，打一条 warning 日志，**不得抛异常打断 tick**。
  - 暴露 `Map<String, CompiledScript> getCompiled()`、`List<Order> evaluate(String scriptId, MarketState state, UUID playerId, String assetId)`。
- 与 `QuantAPI` 的关系：`QuantScriptEngine` 负责「脚本→策略」的翻译与执行；`QuantAPI` 负责玩家策略注册与订单生成。二者经 `MarketEngine.getQuantAPI()/getQuantScriptEngine()` 暴露，玩家可通过命令/UI 同时或任一触发。

**4. 执行接线（进入 `MarketEngine.tick`）**
- 在 `MarketEngine.tick` 的 **`tradingOpen` 门内**（`MarketEngine.java:406` 之后、L2 智能体之前或之后，由 implementer 定并注释），对每个**在线玩家**调用：
  ```
  for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
      for (String assetId : state.getAssetIds()) {
          List<Order> orders = quantAPI.generateOrders(state, sp.getUUID(), assetId);
          // 或 quantScriptEngine.evaluate(...)
          for (Order o : orders) submitOrder(o);   // 走 A 股校验路径
      }
  }
  ```
  - **必须走 `submitOrder(order)`**（`MarketEngine.java:754`）而非直接 `book.placeOrder`，以复用 A 股一手整数倍 / T+1 可用 / 涨跌停带 / 休市拒单校验（R0-6）。
  - 该执行放**每 20 tick**（与宏观节奏对齐，`gameTime % 20 == 0`）或 `AI_ORDER_INTERVAL`，避免每 tick 高成本（由 implementer 定并注释）。
  - 玩家离线策略不执行。

**5. 网络包接线**
- `EnableQuantStrategyPacket.handle`（`:25-33`）改为：
  ```
  var engine = MarketAttachment.getEngine(sp.serverLevel());
  if (engine != null) engine.getQuantAPI().enableStrategy(sp.getUUID(), packet.strategyName(), packet.fastPeriod(), packet.slowPeriod());
  ```
- `DisableQuantStrategyPacket.handle`（`:19-25`）改为调用 `engine.getQuantAPI().disableStrategy(sp.getUUID())`。
- `RequestQuantScriptPacket`/`SaveQuantScriptPacket` 已有 H2 读写，保持；`RequestQuantScriptPacket.handle` 可选：读回后同时触发 `quantScriptEngine` 编译。

### 1.3 验收（t2 自身）
- 玩家启用双均线策略后，在后续 tick 中 `submitOrder` 被实际调用、金叉出现买入限价单、死叉出现卖出限价单（用日志/断点确认）。
- `EnableQuantStrategyPacket` 不再 `new QuantAPI()`（grep 校验）。
- 同种子两次运行：量化下单序列一致（R0-3）。
- A 股开启时：量化买入单非 100 整数倍被拒、超涨跌停带被拒、休市不触发（R0-6）。

---

## 2. 融资融券（t3，对应 R-M）

### 2.1 现状（审计）
- `engine/margin/MarginManager.java`（4 行）空壳。
- `engine/margin/ShortPosition.java`（4 行）空壳。
- `engine/margin/MarginAccount.java`：有 `openLong/openShort/closePosition/getEquity/checkMarginCall`，但：
  - 无保证金**入金/出金**通道 → 新建账户 `collateral=0`，`openLong/openShort` 永远 `required > collateral` 失败 → **融资融券当前完全不可用**。
  - `openLong`/`openShort` 各放一个 `MarginPosition`（单资产单仓），无 ShortPosition 模型。
  - `getEquity` 依赖外部传 `currentPrices`；`RequestMarginDataPacket` 传 `new HashMap<>()`（`RequestMarginDataPacket.java:26`）→ 价格为空 → equity=collateral，失真。
  - `checkMarginCall`（`:57-69`）阈值逻辑有缺陷：开仓即扣 `required`，`collateral+unrealized` 常 < `required*0.5` → 开仓即被催缴/强平。
  - 无 tick 结算、无强平、无持久化。
- `attachment/PlayerEconomy.java:56-62 getMarginAccount(ServerPlayer)`：局部 `marginAccount` 遮蔽字段，恒返回 `new MarginAccount(...)`，且 CODEC（`:64-74`）**不含 margin** → 账户状态既不持久也不共享。这是 bug（审计点）。
- `network/packets/MarginOrderPacket.java:31-41`：直接 `acc.openShort/openLong`，不经过 MarginManager，无风控/校验。
- `network/packets/RequestMarginDataPacket.java:22-31`：`risk` 恒 0，无持仓明细。
- `FinancialTabletScreen.java` 有融资融券 UI：`executeMarginTrade(leverage,isShort)`（`:1366-1371`）发 `MarginOrderPacket`；`init()`（`:182`）发 `RequestMarginDataPacket`；顶部显示 `保证金 balance`（`:691`）。

### 2.2 需求

**1. `MarginManager` 完整实现（引擎单例）**
- `MarketEngine` 新增 `private final MarginManager marginManager;`（构造 `new MarginManager(seed)`）+ getter `getMarginManager()`。
- `MarginManager` 职责：
  - `Map<UUID, MarginAccount> accounts` 全服账户注册表。
  - 参数（常量）：`MIN_COLLATERAL_RATIO`（开仓最低保证金比例，建议 0.5）、`MAINTENANCE_RATIO`（维持担保比例，A 股常用 1.3，即 130%）、`FORCE_CLOSE_RATIO`（强平触发线，建议 `MAINTENANCE_RATIO`）、`MAX_LEVERAGE`（建议 5，A 股常规 1~5 倍）、`INTEREST_RATE_DAILY`（融资利率，可选每日计息）。
  - 方法：
    - `MarginAccount getOrCreateAccount(UUID)`：修复 `PlayerEconomy.getMarginAccount` 的 bug 语义——账户归引擎管理（而非每次 new）。
    - `boolean deposit(UUID, double)` / `boolean withdraw(UUID, double)`：入/出金（出金校验不低于维持线）。
    - `OpenResult openPosition(UUID, String assetId, int quantity, int leverage, boolean isShort, MarketEngine engine)`：全量校验（见下）+ 返回结果（成功/失败原因枚举：`COLLATERAL_INSUFFICIENT / LEVERAGE_EXCEEDED / NOT_LISTED / SHORT_UNAVAILABLE / A_SHARE_REJECTED / OK`）。
    - `double settleAndForceClose(UUID, Map<String,Double> currentPrices)`：每日/tick 结算 + 强平（见 3）。
    - `List<MarginAccount> snapshot()` / `getAccount(UUID)` 供 UI/验证。

**2. `ShortPosition` 落地**
- `ShortPosition` 字段：`assetId / quantity / entryPrice / borrowPrice / openedTick / realizedPnl` + getters。
- 融券卖出语义（A 股简化）：开空时从「可借券源」借入 `quantity` 股卖出；平仓时买回归还。券源简化处理：由 `SecuritiesManager`（券商自营持仓 / 融券池）提供或 `MarginManager` 维护全局 `shortablePool`（每资产可借上限），借出即扣减、还券即回补；`SHORT_UNAVAILABLE` 当池内不足。
- 开空 PnL = `(entryPrice - currentPrice) * quantity`，计入账户权益与担保比例。
- 融券须遵守 A 股 T+1 语义（R0-6）：**禁止用当日买入（frozen）的股票开空**；开空卖出走合成/受控路径，不污染真实 `SettlementSystem` T+1 账本（参照 ZI 合成流：标记 `synthetic` 或直接按借入券源结算，由 implementer 定并注释，但不得使 `PlayerEconomy` 持股变负）。

**3. 接入 `MarketEngine.tick`（结算 + 强平）**
- 在 `MarketEngine.tick` 的 `tradingOpen` 门内、撮合之后（或每 20 tick，由 implementer 定并注释），调用：
  ```
  marginManager.settleAll(state, this);   // 逐账户 mark-to-market
  ```
- `settleAll` 对每个有持仓的账户：
  1. 用 `state.getAsset(assetId).getLastPrice()`（真实现价，非空 map）计算权益 = 保证金 + 全部未实现 PnL（融资多头 + 融券空头）。
  2. 计算维持担保比例 = 权益 / 负债（负债 = 融资负债 + 融券负债，定义由 implementer 给出并注释）。
  3. 若担保比例 < `FORCE_CLOSE_RATIO` → **强平**：按现价平掉部分/全部仓（先平亏损最大的仓），回补保证金，直到担保比例 ≥ 维持线；输出「⚠️ 强平 玩家/资产/数量/价格」日志。
  4. 强平价格为当前市价（确定性）；强平卖出/买入须钳到涨跌停带（复用 `PriceDiscoveryEngine.aShareClamp` / `applyPriceLimitBand` 语义，R0-6）。
- **确定性**：结算/强平为纯确定性逻辑，**禁止随机**（R0-3）。

**4. 网络包接线**
- `MarginOrderPacket.handle`（`:31-41`）改为：
  ```
  var engine = MarketAttachment.getEngine(sp.serverLevel());
  if (engine != null) engine.getMarginManager()
      .openPosition(sp.getUUID(), packet.assetId(), packet.quantity(), packet.leverage(), packet.isShort(), engine);
  ```
  （不再直接 `acc.openShort/openLong`。）
- `RequestMarginDataPacket.handle`（`:22-31`）改为：
  ```
  var acc = engine.getMarginManager().getOrCreateAccount(sp.getUUID());
  double equity = acc.getEquity(engine.getState() 现价map);
  double risk = 1 - equity / 负债(有负债时)；无负债 risk=0;
  sp.connection.send(new MarginDataResponsePacket(acc.getCollateral(), risk, positions));
  ```
- `MarginDataResponsePacket`：**加字段**（向后兼容，见 §7 网络兼容）`List<MarginPositionDTO> positions`（每仓 assetId/quantity/entryPrice/isLong/unrealizedPnl）。旧 handler/前端兼容：字段为 additive（新增构造重载或默认空 list）。
- `PlayerEconomy.getMarginAccount`：**改为委托引擎**或移除对该错误方法的依赖；margin 状态归 `MarginManager.accounts`（引擎级），CODEC 不再承诺持久化 margin（由引擎 blob 持久化，见 §6）。

**5. 入金通道（玩家可触发）**
- 融资融券要有可触发的入金入口。最小集：在 `MarginOrderPacket` 前新增一个「入金/出金」触发（新增 packet `MarginDepositPacket(amount)` 或复用命令 `/fmarket margin deposit <amount>`，由 implementer 择一并在 UI 或命令注册）。金额从玩家 `PlayerEconomy.getCash()` 扣减转入 `MarginManager` 保证金（确定性记账）。

### 2.3 验收（t3 自身）
- 玩家入金后可成功开多/开空；保证金不足 / 超杠杆上限 / 非 100 整数倍（融资买入）/ 融券券源不足 → 返回对应拒绝原因且无副作用。
- 注入股价大幅下跌触发维持担保比例 < 强平线 → 强平执行并回补到维持线以上。
- 同种子两次运行：开仓/强平事件序列一致（R0-3）。
- A 股开启：开空不违反 T+1（当日买入股票不可用于开空）；强平价在涨跌停带内；aShare 关闭时回落基线（R0-6）。
- 读档后 margin 账户状态（保证金/持仓/强平计数）保持（R0-5）。

---

## 3. 证券研究与回购（t4，对应 R-S）

### 3.1 现状（审计）
- `engine/securities/ResearchReport.java`（4 行）空壳。
- `engine/securities/SecuritiesCompany.java`：`propTrade`（`:43-54`）用 `UUID.randomUUID()`（**非种子化**）且 `submitOrder` 被注释（`:49/:52`）；`publishResearch`（`:56-59`）调 `sentiment.adjustAssetSentiment`（可复用）。
- `engine/securities/SecuritiesManager.java`：`dailyPropTrading`（`:26-38`）用 `Collections.shuffle`（**非种子化**），且**从未被调用**（`MarketEngine` 有 `securitiesManager` 字段与 getter，但 `tick` 未接线）。
- `engine/buyback/StockBuyback.java`：`executeBuyback`（static，`:14-23`）存在但**未接线**；改 `AssetSnapshot` 的 volume/lastPrice，无 A 股涨跌停钳制、无一手/券源约束。

### 3.2 需求

**1. `ResearchReport` 落地**
- 填实：`ResearchReport` 建模一份研报：字段 `reportId/assetId/title/rating(评级,如 买入/持有/卖出)/targetPrice/impactScore/issuedTick`。
- 生成逻辑：`static List<ResearchReport> generate(SecuritiesCompany issuer, MarketEngine engine, SeedableRandom rng, int count)`——确定性选资产、基于基本面（`AICompanyData.getFairValue/getEps`）与现价偏离定评级与 `impactScore`（偏离越大、越低估 → 买入评级、正向影响）。
- 发布影响：`engine.getSentiment().adjustAssetSentiment(assetId, impact)`（复用 `SecuritiesCompany.publishResearch` 或直接调 `MarketSentiment.adjustAssetSentiment`，`MarketSentiment.java:...`）。`impact` 钳制在安全区间（如 [-0.1,0.1]）防情绪失控。
- **确定性**：资产选择与 impact 全部走 `SeedableRandom`，禁止 `Collections.shuffle`/`UUID.randomUUID()`。

**2. `SecuritiesManager.dailyPropTrading` 接线 + 去随机化**
- `MarketEngine.tick` 每交易日期首（`calendar.isTradingDayStart()` 后）或每 N tick 调用 `securitiesManager.dailyPropTrading(priceEngine, sentiment)`（在 `tradingOpen` 门内）。
- `SecuritiesCompany.propTrade` 修正：
  - 移除 `UUID.randomUUID()`，改用引擎种子派生的固定合成 `playerId`（如复用 `ZI_PLAYER_ID` 语义，`MarketEngine.java:94` 或券商专属合成 UUID），并 `order.setSynthetic(true)`（不污染 T+1 账本，AS-3）。
  - 下单改为真实提交：走 `engine.submitOrder`（复用 A 股校验）或 `book.placeOrder`（若希望自营不受一手约束，需明确注释；建议**走 submitOrder** 保持 R0-6）。
  - `quantity` 下限保护（`lastPrice>0` 时 `max(1, ...)`），金额受 `capital` 约束。
- `SecuritiesManager.dailyPropTrading` 内 `Collections.shuffle` 改为 `SeedableRandom` 选资产（不 shuffle，用 `rng.nextIntInclusive` 索引或确定性子集）。

**3. `StockBuyback` 接线**
- 保留 `executeBuyback` 语义，但增加：
  - **A 股钳制**：回购后 `setLastPrice` 的推价须钳到涨跌停带（复用 `PriceDiscoveryEngine.aShareClamp`，R0-6）；`aShare.enabled=false` 时沿用现逻辑（`*1.005` 或无钳制，回落基线）。
  - **数量约束**：`quantity` 为 100 整数倍（回购按一手）且 ≤ 可流通量，不得令 volume 为负（现已有 `volume < quantity` 拒绝）。
- 接线触发（择一或都做）：
  - **周期性**：`MarketEngine.tick` 每 N tick 让估值偏低的资产被公司/政府触发回购（确定性选资产，`SeedableRandom`；判定：`state` 现价 < fairValue 一定比例，如 0.85）。
  - **命令**：`/fmarket buyback <assetId> <quantity>`（复用 `CompanyCommand` 或新增），管理员/股东可手动触发。
- **确定性**：触发选择与数量走 `SeedableRandom`（或纯确定性阈值，推荐纯阈值 + 固定周期）。

### 3.3 验收（t4 自身）
- `ResearchReport` 生成后在 `MarketSentiment` 产生可观察的 `adjustAssetSentiment` 效果。
- `dailyPropTrading` 被实际调用，自营订单进入订单簿且 `synthetic=true`、不污染 T+1 账本。
- `StockBuyback` 触发后目标资产 volume 下降、现价不越涨跌停带。
- 同种子两次运行：研报/自营/回购序列一致（R0-3）；grep 无新增 `Collections.shuffle`/`UUID.randomUUID()`（新代码）。

---

## 4. 危机 / 经济周期（t4，对应 R-C）

### 4.1 现状（审计）
- `engine/crisis/CrisisEngine.java`：`tick`（`:21-62`）**从未被调用**；`crisisLevel` 用 `Math.random()`（`:31`）扰动（**破坏可复现性**）；`crisisLevel/currentCrisis/crisisTicks` **不持久化**；`getCreditGrowth`（`:63-66`）恒 0（死代码）。
- `engine/crisis/EconomicCycle.java`（4 行）空壳。
- 注意：`engine/macro/EconomicCycleEngine.java` 是**另一套**、已实现且已在 `MarketEngine.tick`（`:381`）接线的经济周期（`getEconomicHealth/getPhaseName`，seedable）。空壳 `EconomicCycle`（crisis 包）与它职责重叠，需**落地**而非重复造引擎。

### 4.2 需求

**1. `CrisisEngine` 去随机化 + 种子化**
- 构造改 `CrisisEngine(long seed)`（内部建 `SeedableRandom` 子流；无参构造 `CrisisEngine(0L)` 兼容）。
- `MarketEngine` 中 `private final CrisisEngine crisisEngine = new CrisisEngine();`（`:60`）改为 `new CrisisEngine(seed)`（构造器传 `engineSeed`）。
- `tick(...)` 中 `Math.random()`（`:31`）替换为 `rng.nextDouble()`（确定性子流），或**直接移除随机扰动**（推荐：危机判定改为纯确定性阈值函数，`crisisLevel` 全由基本面指标驱动，更强可复现；若保留微扰则必须走 rng）。
- `getCreditGrowth` 删除或填实（从 `creditSystem` 读数），不得恒 0 误导。

**2. `CrisisEngine` 接入 `MarketEngine.tick`**
- `MarketEngine.tick` 每 `gameTime % 20 == 0` 的宏观节奏块（`:377-388`）内，新增：
  ```
  crisisEngine.tick(sentiment, bankManager, government, inflationSystem, creditSystem);
  ```
  （`sentiment`/`bankManager`/`government`/`inflationSystem`/`creditSystem` 均为 `MarketEngine` 已有字段，签名与 `CrisisEngine.tick` 现有签名一致。）
- 危机影响（`adjustGlobalSentiment` 等）保持现状语义；确保 `crisisTicks` 递减与复位逻辑在接入后按周期推进。

**3. `EconomicCycle` 落地（crisis 包）**
- 把它落地为**经济相位模型**，与已接线的 `EconomicCycleEngine` 分工：
  - `EconomicCycle` 提供 `enum Phase { EXPANSION, PEAK, CONTRACTION, TROUGH }` + `static Phase phaseOf(double economicHealth)`（阈值：`>0.4` 繁荣/扩张、`>0.1` 复苏、`>-0.3` 衰退、否则萧条/收缩，与 `EconomicCycleEngine.getPhaseName` 对齐）。
  - `CrisisEngine` 消费该相位：相位为收缩/萧条时提高危机倾向（作为 `crisisLevel` 的一个确定性加项，取 `cycleEngine.getEconomicHealth()` 反向映射）。
  - 可选：`EconomicCycle` 维护相位历史（`List<Phase> history`，环形缓冲）供 UI/验证。
- **确定性**：相位为 `economicHealth` 的纯函数，无随机。

**4. 持久化**
- `CrisisEngine`：`save()/load()` 持久化 `crisisLevel/currentCrisis/crisisTicks`；`load` 用 `contains` 兜底（旧档无字段用默认值重建，读档不失败）。
- `EconomicCycle`：相位历史可选持久化；`Phase` 派生值可只依赖 `economicHealth`（已由 `EconomicCycleEngine` 持久化）无需单独持久化。

### 4.3 验收（t4 自身）
- `CrisisEngine.tick` 被实际调用（日志/断点）；无 `Math.random`（grep 校验新增代码）。
- 同种子两次运行：`crisisLevel` 序列 + 危机类型切换序列一致（R0-3）。
- 注入高 NPL/高通胀/低情绪 → 危机等级上升、对应 `CrisisType` 触发。
- `EconomicCycle`（crisis 包）不再空壳；相位与 `cycleEngine.getEconomicHealth` 一致。
- 读档后 `crisisLevel/currentCrisis/crisisTicks` 保持（R0-5）。

---

## 5. `MarketEngine.tick` 接线总顺序（t2~t4 共用，R0-5）

> 复用现有时间模型：`tickCounter` 为时间权威，A 股 `calendar` 判定开市（`MarketEngine.java:313-361`）。**交易相关的副作用放在 `tradingOpen` 门内**（`:406` 之后）；纯宏观/危机放在门外的每 20 tick 块（`:377-388`）。

建议插入点（implementer 可微调但须注释，且不得改动既有 P0/P1/P2/A股 调用顺序语义）：

```
tick(level):
  tickCounter++; gameTime = ...
  fundNewPlayers; feedSuperstructureTick; agentManager.updatePhase
  // ==== A 股时间模型：交易日切换（既有，AS-1）====
  calendar.advance(tickCounter)
  ...isSessionEnd / isTradingDayStart（既有）...
     └─ isTradingDayStart 块内：runDailyFiat(level) 之后追加
        └─ marginManager.settleDaily(level)   // 每日结算/强平（t3）【若按日结算】
        └─ securitiesManager.dailyPropTrading(...)  // 自营（t4）
        └─ StockBuyback 周期回购触发（t4，可选）
  ...priceEngine.step()...
  // ==== 每 20 tick 宏观/危机节奏（既有 :377-388，追加）====
  if (gameTime % 20 == 0) {
     macroEngine.step(...); cycleEngine.tick(...); fundEngine.step(...); sentiment.updateGlobalSentiment(...)  // 既有
     crisisEngine.tick(sentiment, bankManager, government, inflationSystem, creditSystem)   // t4 追加
  }
  tradingOpen = !aShareEnabled || calendar.isMarketOpen()
  if (tradingOpen) {
     // ...既有 L2 智能体挂单、L2.5 ZI/Hawkes、L3 撮合、熔断、盘口推送...
     // t2 追加：量化策略执行（每 20 tick），走 submitOrder
     // t3 追加：marginManager.settleAll(state, this)（若按 tick 而非按日）
  }
  excellent24Index.updateValue; circuitBreaker.tick
```

- **强平/结算节奏**：若按日（`isTradingDayStart`）则集中每日一次；若按 tick 则放 `tradingOpen` 门内。二选一，由 implementer 定并注释（推荐：按 tick mark-to-market、按日结算利息/结转）。
- 不得让上述新增调用抛出未捕获异常阻断 tick；每个新调用外层可 `try/catch` 打 warning（与既有 `MarketEngine` 防御风格一致）。

---

## 6. 持久化（R0-5 / H2 兼容）

### 6.1 `MarketEngine.save()` 增量
在既有 `save()`（`:850-924`）末尾追加（全部 `tag.put(...)`）：
- `tag.put("quant", quantAPI.save())`：玩家策略注册表（playerId → strategy 参数）。
- `tag.put("quantScripts", quantScriptEngine.save())`：脚本编译缓存/启用标记（源码已在 H2 `quant_script` 表，blob 只存启用状态与编译摘要）。
- `tag.put("margin", marginManager.save())`：账户表（uuid → collateral + 长/空持仓明细 + 强平计数 + 计息累计）。
- `tag.put("crisis", crisisEngine.save())`：`crisisLevel/currentCrisis/crisisTicks`。
- （可选）`tag.put("securities", securitiesManager.save())`：券商资本/自营持仓（若需跨档保留）。

### 6.2 `loadInternal()` 增量
在 `loadInternal`（`:1020-1111`）追加，**全部用 `if (tag.contains(...)) { ... load(...) }` 兜底**（旧档缺字段用默认/空重建，读档不失败，沿用 A3-8/FM-7 惯例）：
- `quant` / `quantScripts` / `margin` / `crisis` / `securities`（各自 `contains` 守卫 + 内部子字段 `contains` 兜底）。

### 6.3 H2 兼容
- `MarketEngine.saveToH2`（`:1249`）/`loadFromH2`（`:1277`）走 `save()/loadInternal()` 整块 blob，**上述新字段自动随 `engine_main` 持久化**，无需改 H2 schema。
- `saveRelationsToH2`（`:1309`）可选新增关系表镜像：`margin_account`（player_uuid, collateral, equity）与 `research_report`（report_id, asset_id, rating, impact）——若新增，`H2_MIGRATION.md` 对应建表语句需同步；**非必须**（整块 blob 已保证不丢失），实施按最小改动优先，默认只走 blob。
- **前向兼容**：所有新增 `save/load` 字段读旧档缺失时默认重建，与既有 `aiCompanies`/`fiat` 的 `contains` 兜底一致。

---

## 7. A 股交易规则语义保持（R0-6）

| 子系统 | A 股约束（必须满足） | 处理 |
|---|---|---|
| 量化 | 买入 100 整数倍、涨跌停带、T+1 可卖、休市拒单 | 走 `submitOrder`（复用 `:754` 校验） |
| 融资融券 | 融资买入 100 整数倍；融券不得用当日买入(frozen)股票开空；强平价钳涨跌停带；T+1 语义 | `MarginManager.openPosition` + 强平 `aShareClamp`；合成/受控券源不污染 T+1 账本 |
| 自营/研报 | 自营订单遵守涨跌停/一手（走 submitOrder），`synthetic=true` 不污染 T+1 | `SecuritiesCompany.propTrade` 修正 |
| 回购 | 推价不越涨跌停带、数量 100 整数倍、volume≥0 | `StockBuyback` 增钳制与手约束 |
| 危机/周期 | 不直接改价格，仅经 sentiment/宏观 → 不引入新交易副作用 | 无特殊 A 股约束，保持 |

- **隔离**：`aShare.enabled=false` 时上述新增子系统回落 P0/P1/P2 基线行为（例如回购不做涨跌停钳制沿用 `*1.005`、自营/量化无 A 股校验）——与既有 A3-7 隔离原则一致。由 t5 验证开关对比。

---

## 8. 网络 / UI / 命令触发点（R0-4 可玩性）

| 子系统 | 现有触发 | 需求改动 |
|---|---|---|
| 量化 | `EnableQuantStrategyPacket` / `DisableQuantStrategyPacket`（发） | 改 handler 走引擎单例；`FinancialTabletScreen` 量化 tab（`:944/:1171`）已发上述包，接线后即有真实效果 |
| 融资融券 | `MarginOrderPacket` / `RequestMarginDataPacket`（发）；`FinancialTabletScreen` margin UI（`:1359/:182`） | 改 handler 走 `MarginManager`；新增入金触发（`MarginDepositPacket` 或 `/fmarket` 命令）；`MarginDataResponsePacket` 加持仓明细（additive） |
| 证券研究/回购 | 无 | 周期自营/研报/回购自动运行 + 可选 `/fmarket buyback` 命令 |
| 危机/周期 | 无 | 自动接入 tick；`EconomicCycle.Phase` 供 UI 展示（可选） |

- **网络字段向后兼容**：`MarginDataResponsePacket` 加 `positions` 为 additive（保留 `balance/risk` 前两字段，旧 handler 仍可解）。其余 packet **不增删字段**（沿用既有「不新增/删除/改字段」原则，`A_SHARE_TRADING_SPEC §8.1`）。
- UI 碎片化（见 §11 可玩性）：margin/量化当前在旧原生 `FinancialTabletScreen`（80KB）而 `financial_tablet` 打开 Web MCEF 屏。本次**不改 UI 载体**（避免大改），只保证 handler/引擎侧接线；UI 载体统一列为可玩性建议（非本次范围）。

---

## 9. 对 t5（整体验证 + 构建 + 可复现 + 持久化）的需求

1. **构建**：`./gradlew compileJava` 通过；`./gradlew build` 出包。
2. **可复现脚本**：固定 `engineSeed` 两次运行 N tick（跨休市/交易日边界），成交价序列哈希 + 危机事件序列 + 强平事件序列 + 量化下单序列哈希一致（R0-3）。
3. **子系统冒烟**：
   - 量化：启用策略后 `submitOrder` 被调用、订单进入订单簿。
   - 融资融券：入金→开多/开空→强平链路（注入跌价触发强平）。
   - 证券研究/回购：研报影响 sentiment、自营 `synthetic` 订单、回购改 volume。
   - 危机：`crisisEngine.tick` 推进、`Math.random` 无新增。
4. **持久化**：save/load 后 quant/margin/crisis 状态保持；构造旧档（无新字段 key）读档不失败（`contains` 兜底）。
5. **A 股隔离**：`aShare.enabled` 开关对比，验证开/关差异符合 §7 表。
6. **随机源审计**：`grep -rn "Math.random\|new Random\|ThreadLocalRandom" src/main/java` 本次改动无新增。

---

## 10. 实施顺序与依赖（供 scheduler）

```
t2 (量化：QuantAPI 单例 + Strategy + QuantScriptEngine + 接线) ──┐
t3 (融资融券：MarginManager + ShortPosition + tick 结算/强平)   ──┼─→ t5 (整体验证) ──→ t6 (评审)
t4 (证券研究/回购 + 危机/周期去随机化落地)                        ──┘
```
- 三者相互独立（不同包/文件），可并行实施，均依赖 t1 本规格。
- t5 依赖 t2/t3/t4；t6 依赖 t5。

---

## 11. 可玩性评估（本任务附带产出）

> 评估基于当前源码审计（未启动端到端重跑，属静态评估）。分「已可玩 / 半成品（空壳或未接线）/ 缺失 / 体验债」四档，并对「剩余子系统落地」带来的可玩性增量排序。

### 11.1 已可玩（能跑且有真实效果）
- **股票行情/看盘**：Web MCEF 屏（ECharts 分时/K线/盘口买五卖五/技术指标）+ 实时 K 线、指数（24 指数）、成交明细。
- **交易**：股票买卖（限价/市价、A 股一手/T+1/涨跌停、订单簿撮合、手续费/滑点）——A 股核心链路已接线。
- **持仓/自选**：`PortfolioSyncPacket` 持仓同步、成本基准。
- **公司**：玩家/AI 公司创建、上市、等级、基本面（EPS/PE/ROE/股息）。
- **借贷/银行**：贷款、存款、银行间市场、央行（贴现率/准备金/国债/逆回购）、政府（发债/税收/财政立场）。
- **大宗商品**：按物品注册、买卖、价格演化。
- **法币/M2**：央行基础货币注入、税收、通胀、信贷派生。
- **角色扮演**：央行行长 / 财政部长角色 + 上层建筑约束 / AI 兜底。

### 11.2 半成品 / 空壳（本次落地目标，落成后可玩性增量最大）
| 子系统 | 现状问题 | 落地后可玩性增量 |
|---|---|---|
| **融资融券** | 保证金恒 0 无法开仓、无风控/强平、不持久化 → **完全不可用** | 杠杆做多/做空、强平博弈、担保比例管理 → 高 |
| **量化** | 策略注册但从不执行 → **无效** | 双均线/网格/脚本自动交易、被动收益 → 高 |
| **证券研究/回购** | 研报空壳、自营/回购未接线 → 无感知 | 研报驱动情绪、自营造流动、回购托价 → 中 |
| **危机/经济周期** | 危机从不触发（tick 未接）、`Math.random` 破坏复现、周期相位重复 | 股灾/通胀螺旋/挤兑风险、周期相位决策 → 中高 |

### 11.3 缺失 / 已知缺陷（可玩性负向项）
1. **物品纹理缺失**：`models/item/*.png` 放错目录，平板物品显示紫黑块（`textures/item/` 不存在）。
2. **UI 载体碎片化**：`financial_tablet` 打开 Web MCEF 屏，但融资融券/量化 UI 在旧原生 `FinancialTabletScreen`（80KB）；两套 UI 并存、入口不一致 → 玩家难发现融资融券/量化入口。
3. **Web 依赖 CDN**：ECharts 来自 jsdelivr，离线环境图表缺失。
4. **本地化缺失**：`lang/en_us.json` 仅 1 条，大量中文硬编码。
5. **日志乱码**：`System.out.println` 中文 emoji 直接输出，运行时编码问题。
6. **入口可发现性**：经济引擎需玩家持有平板物品才能看；命令 `/fmarket` 子命令文档缺失。

### 11.4 可玩性结论与建议
- **结论**：当前处于「引擎骨架丰富但若干关键玩法未接线」状态——A 股交易核心链路可玩，但**融资融券与量化这两大「交易深度」玩法实际不可用**，危机动态不存在，导致长期可玩性（赚钱手段单调、无系统性风险事件）偏低。
- **建议**（按 ROI 排序）：
  1. 完成本规格 t2/t3/t4（融资融券、量化、危机接线）——直接激活 3 类高增量玩法。
  2. 修复物品纹理 + 统一 UI 入口（把 margin/量化接入 Web MCEF 屏或至少在 MCEF 加入口）——提升可发现性。
  3. 本地化 + 统一日志（log4j）——体验卫生。
  4. 后续：ECharts 本地化、命令文档、`/fmarket` help。
- 本规格（t2/t3/t4）落地后，可玩性评级可由「半成品」提升至「A 股交易 + 杠杆/自动交易 + 周期性风险事件」的完整闭环。

---

*（本规格基于 2026-08-14 当前工作树快照；所有行号为审计时参考，实施时以实际为准。）*
