# MARGIN_LEVERAGE_SPEC — 保证金交易完善 + 杠杆合约（杠杆票据）产品 需求规格（需求契约，供实施/验证/评审）

> - 角色：architect（方案架构 / 保证金杠杆需求规格）为任务 t1 产出。
> - 输入：团队 Goal + 当前源码审计（本规格对照实际源码给出文件/方法/字段/默认参数）。已交付前置基座：`A_SHARE_TRADING_SPEC.md`（A 股交易日历 `TradingCalendar`：5 分钟交易日 / 每 5 交易日休市 2 日 / 交易日内全天连续；±10% 涨跌停；T+1；一手 100；价格/时间优先撮合）、`ECON_P0/P1/P2_SPEC.md`（SeedableRandom、GBM/GARCH、P2 订单簿）、`ECON_FIAT_M2_SPEC.md`（法币/M2/央行/税收/通胀）、`COMPANY_SPEC.md`（公司/AssetScanner 读 `commodityMarket`）、`COMMODITY_FUTURES_SPEC.md`（商品市场 + CZCE 期货 `FuturesMarket`，其逐日结算/保证金/追保/强平/交割为保证金改进与杠杆票据的直接模板）、`FRONTEND_THS_SPEC.md`（前端 + LocalHttpServer API）、`H2_MIGRATION.md`（H2/NBT 双写）、`BANKING_TRADE_SPEC.md`（储蓄卡门控 BT-4/BT-10）。
> - 用途：engine-dev 按本规格实现保证金完善 + 杠杆票据产品；test-engineer 按本规格实现验证 + 构建/可复现；reviewer 按本规格验收。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick 步进内 O(账户)/O(资产)/O(票据) 常数级计算。**时间权威用引擎 tick 计数器（`MarketEngine.tickCounter`）**，非墙钟、非 `gameTime`（`gameTime` 可被 `/time` 改动破坏时段确定性；现有 `MarketEngine.java:1200` 融资融券结算用 `gameTime`，本规格要求改为 `tickCounter`）。
> - **可复现性硬约束（继承 P0/A0-3）**：同一种子同行情必须保持。所有新增随机一律走 `SeedableRandom`（`engine/random/SeedableRandom.java`，SplitMix64），**禁用 `ThreadLocalRandom` / 裸 `new Random()` / `Math.random()`**。保证金多级追保/强平、杠杆票据定价/交割/敲出全部为确定性逻辑。
> - **兼容边界硬约束（不动已定稿语义）**：
>   1. **不修改** `PriceDiscoveryEngine.step()` 的 GBM/GARCH 公式与 A 股交易规则常量（`PriceDiscoveryEngine.java`：`A_SHARE_LIMIT_RATIO`/`TICK_SIZE`/`BOARD_LOT`）。
>   2. **不修改** A 股交易日历 `engine/calendar/TradingCalendar.java`（时间模型）；保证金逐日结算与杠杆票据到期沿用该日历（`isSessionEnd/isTradingDayStart/isMarketOpen`）。
>   3. **不修改** `MarketEngine.submitOrder`/`OrderBook`/`SettlementSystem` 的 A 股股票交易语义；保证金交易与杠杆票据为**并列独立市场/独立引擎**，不进入 A 股股票撮合/涨跌停/T+1 路径（保证金以 A 股现价/维持担保比例做风险控制，不做 A 股下单）。
>   4. **不修改** 法币 M2 / 央行 / 税收 / 银行语义（`ECON_FIAT_M2_SPEC`、`BANKING_TRADE_SPEC`）；杠杆票据与保证金只**读取** `InflationSystem`/`MoneyAggregate`/`CentralBank`/`hasBankCard` 作宏观/门控，不改其公式。
>   5. **不修改** 已交付期货 `FuturesMarket`/商品 `CommodityMarket` 内部公式；保证金与杠杆票据与它们**协同只读消费**（价格锚、逐日结算模板），不写回。

---

## 0. 目标与全局验收（对应团队 Goal）

> ①完善现有保证金交易（`MarginManager` 开多/空、杠杆上限、**分级保证金/强平线**、**持仓限额**、**逐日盯市/多级追加保证金 margin call**、可复现强化）；②新增**杠杆合约产品**（可交易/可交割的**杠杆票据**，自定杠杆倍数/到期日，与股票/期货/商品协同），使市场出现更高的投机性与不确定性。保持确定性可复现、H2 持久化兼容、与已交付经济引擎/A 股/法币 M2/公司/商品/期货/银行贸易协同。完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| ML-0 | 编译通过 | `./gradlew compileJava` 退出码 0；`./gradlew build` 出包 | 每项实施 + t5 |
| ML-1 | 可复现性不回归（继承 P0） | 同 `engineSeed` 跑固定 tick，保证金强平/追保序列 + 杠杆票据定价/交割/敲出序列两次逐元素一致；新增逻辑零新随机（全仓 `grep` 无 `Math.random`/`new Random`/`ThreadLocalRandom` 新增调用；保证金/票据逻辑为纯确定性函数，时间权威一律 `tickCounter`，不再用 `gameTime` 门控新结算） | 实施 + t5 |
| ML-2 | **分级保证金/强平线（t 保证）** | `MarginManager` 引入可配置多级风险线：预警线/追加保证金线/强平线；账户维持担保比例落入对应区带触发「预警 → 追保通知 → 宽限 → 强平」；开仓后比例须 ≥ 开仓最低线（避免开仓即预警）；强平按确定性顺序（未实现亏损最大 → 最老 → 字典序）部分/全部平仓至比例回升到强平线上 | 实施 + t5 |
| ML-3 | **持仓限额（t 保证）** | 保证金账户引入：单账户总负债上限、单资产名义上限、单账户单资产最大融券数量、集中度上限；开仓超限拒绝（`POSITION_LIMIT_EXCEEDED`）；结算/强平超限部分确定性强平；限额可配置 | 实施 + t5 |
| ML-4 | **逐日盯市 + 多级追加保证金 margin call（t 保证）** | 每个交易日边界（`calendar.isSessionEnd/isTradingDayStart`）执行逐日盯市，更新保证金账户风险状态（NORMAL→WARNING→MARGIN_CALL→FORCE_CLOSE）；比例低于追保线发追保通知并启动宽限计数（默认 1 交易日），宽限结束仍不足 → 强平；`marginCallStreak/lastMarginCallDay` 确定性计数并持久化；沿用现有 20-tick 日内盯市（改为 `tickCounter` 门控）作为日内兜底 | 实施 + t5 |
| ML-5 | **杠杆上限与可配置（t 保证）** | `MAX_LEVERAGE` 由常量改为可配置（默认 5）；开多/开空统一校验杠杆；杠杆票据杠杆倍数独立配置（默认 1.5~8） | 实施 + t5 |
| ML-6 | **杠杆票据产品（t 保证）** | 新建杠杆票据市场：发行（自定杠杆倍数/到期日/标的）+ 二级市场交易 + 到期交割（现金/实物）+ 敲出归零；票据价格 = 标的收益率 × 杠杆的确定性衍生；全程可复现、可持久化 | 实施 + t5 |
| ML-7 | **与股票/期货/商品协同（t 保证）** | 杠杆票据标的可为 A 股股票资产、期货合约、商品；价格分别锚 `state.getAsset().getLastPrice()` / `futuresMarket.getContract().lastPrice` / `commodityMarket.getCommodity().getPrice()`；只读消费，不改其公式；票据增加市场投机性与不确定性而不污染标的供需/撮合 | 实施 + t5 |
| ML-8 | **前端 / API 协同（t 保证）** | 保证金风险状态 + 杠杆票据列表/持仓/交易经网络包与 `LocalHttpServer` 暴露：`/api/margin`、`/api/leverage/notes`、`/api/leverage/positions`、POST `/api/margin/deposit`、POST `/api/leverage/issue`、POST `/api/leverage/order`；前端保证金/票据页（协同增强，非硬性验收） | 实施 + t5 |
| ML-9 | **H2 / NBT 持久化不回归** | 保证金账户（多级风险状态/追保计数/限额用量）+ 杠杆票据市场（票据元数据/价格/持仓/交割状态/发行方保证金）随 `MarketEngine.save()/loadInternal()` blob（`engine_kv.engine_main` + NBT 镜像）序列化/恢复；旧存档缺字段用 `contains` 兜底重建，读档不失败 | 实施 + t5 |
| ML-10 | **隔离（不回归）** | `margin.enabled=false` 回落现状（保证金不结算/不接入新风险线，账户空置）；`leverage.enabled=false` 不注册任何票据、不 tick、不持久化；两者全关与既有基线逐位一致 | 实施 + t5 |

**验收流程（t5 落地）**：固定 `engineSeed` 跑固定 tick 两次，保证金强平/追保/票据定价/交割序列哈希一致（ML-1）；构造多级风险线触发（预警→追保→宽限→强平）与开仓最低线校验（ML-2）；构造超负债/超单资产/超融券/集中度超限 → 拒绝或强平（ML-3）；验证逐日盯市 + 多级 margin call 状态机与计数持久化（ML-4）；杠杆上限/倍数配置生效（ML-5）；发行/交易/交割/敲出全流程（ML-6）；股票/期货/商品三类标的定价与只读协同（ML-7）；前端/API 冒烟（ML-8）；save→load 恢复保证金+票据状态（ML-9）；开关隔离与基线对比（ML-10）；`./gradlew build` 出包（ML-0）。

---

## 1. 现状诊断（代码审计结论）

| 类 | 文件 | 现状 | 问题（保证金/杠杆票据视角） |
|---|---|---|---|
| `MarginManager` | `engine/margin/MarginManager.java` | A 股融资融券引擎单例：`openPosition/closePosition/settleAndForceClose/settleAll`、`MAINTENANCE_RATIO=1.3`、`FORCE_CLOSE_RATIO=1.3`、`MAX_LEVERAGE=5`、可借券源池；`save/load` 随 `MarketEngine` "margin" 字段持久化 | **单级风险线**：强平线=维持线=1.3，无预警/追保/宽限分层；**无持仓限额**（负债/单资产/融券/集中度均无上限）；**非逐日**：`settleAll` 每 20 tick 日内强平（`MarketEngine.java:1200` 用 `gameTime` 门控，时间权威不纯）；**无 margin-call 状态机与追保计数**；`MIN_COLLATERAL_RATIO`（`:30`）在 `openPosition` 未实际生效（死参数）；`MarginPosition` 缺 `openedTick`（无法按最老优先强平） |
| `MarginAccount` | `engine/margin/MarginAccount.java` | 保证金 `collateral` + 多头 `longs` + 空头 `shorts`；`equity/liability/maintenanceRatio`；`getPositionViews` 字典序排序 | 无风险区带状态字段、无追保计数、无已用限额统计、无开仓 tick（多头）；部分平仓/强平顺序需确定性补强 |
| `ShortPosition` | `engine/margin/ShortPosition.java` | 空头持仓：`entryPrice/borrowPrice/openedTick/realizedPnl` | 已有 `openedTick`（可作为最老优先排序锚）；无杠杆字段（融券杠杆=1 语义） |
| `FuturesMarket` | `engine/futures/FuturesMarket.java` | CZCE 期货：分档保证金（一般/次月/交割月）、逐日无负债结算、`marginCallStreak` 追保计数 + `TOP_UP_GRACE_TRADING_DAYS` 宽限、`POSITION_LIMIT_LOTS` 持仓限额、强平/交割、`save/load` | **直接模板**：保证金多级追保/强平/持仓限额/逐日结算可对齐其确定性实现；杠杆票据到期/敲出/交割可借鉴其结算与风险控制骨架 |
| `FinancialProduct` | `engine/products/FinancialProduct.java` | 产品类型枚举 `SAVINGS/FUND/BOND/INSURANCE/TRUST/STOCK`，`annualYield/riskLevel/lockDays` | 无「杠杆票据」产品类型；杠杆票据为可交易/可交割衍生品，宜新建独立 `engine/leverage/` 引擎而非并入 `FinancialProduct`（该模型为存款类收益，非杠杆衍生） |
| `MarketEngine` | `engine/MarketEngine.java` | 持有 `marginManager`（`:81,:183`），`getMarginManager()`（`:1634`），`save` "margin"（`:1738`）/`load`（`:1950`）；`isAShareEnabled`（`:224`）、`isFuturesEnabled`（`:250`）；`getState/getCommodityMarket/getFuturesMarket/getCentralBank/getTickCounter` | 无 `leverageMarket`、无 `margin.enabled/leverage.enabled` 开关、`settleAll` 用 `gameTime` 门控；需接入逐日结算钩子（`calendar.isSessionEnd/isTradingDayStart`）与 `tickCounter` 时间权威 |
| `LocalHttpServer` | `web/LocalHttpServer.java` | 已有 `/api/account,/api/positions,/api/session,/api/futures,/api/futures/positions,/api/futures/order` | 无 `/api/margin`、无 `/api/leverage/*` |
| `MarginOrderPacket`/`MarginDataResponsePacket` | `network/packets/` | 融资融券开/平仓、数据响应 | 沿用并扩展（新增风险状态/追保/限额字段，`contains`/默认值向后兼容） |

**根因**：保证金交易已有 t3 单级强平与杠杆上限，但**缺多级风险线、持仓限额、逐日盯市 + 多级追保状态机、时间权威纯化**；杠杆票据产品**完全不存在**。本规格把保证金完善为「多级风险线 + 持仓限额 + 逐日多级追保」的 A 股语义融资融券，并新建独立杠杆票据市场。

---

## 2. AS-M1 分级保证金 / 强平线（t 保证，ML-2）

> 目标：把单级 `FORCE_CLOSE_RATIO=1.3` 升级为**多级风险线**，使保证金账户进入「预警 → 追保 → 宽限 → 强平」的确定性风险区带。

### 2.1 改动文件
- 改 `engine/margin/MarginManager.java`（新增常量/字段、风险线判定、开仓最低线校验、强平顺序补强）
- 改 `engine/margin/MarginAccount.java`（新增风险状态/追保计数/限额统计字段，多头补 `openedTick`）
- 改 `engine/MarketEngine.java`（`margin.enabled` 开关、逐日结算钩子、`settleAll` 改 `tickCounter` 门控、`save/load` 扩展字段）
- 改 `network/packets/MarginDataResponsePacket.java`（新增风险状态/追保/限额字段，向后兼容）

### 2.2 多级风险线常量（`MarginManager`，可配置）
```java
// 分级维持担保比例线（A 股语义，均可配置）
public static final double WARNING_RATIO     = 1.5;   // 预警线：< 该值 → 禁止新开仓 + 警示提示
public static final double MARGIN_CALL_RATIO = 1.4;   // 追加保证金线：< 该值 → 发追保通知，启动宽限计数
public static final double FORCE_CLOSE_RATIO = 1.3;   // 强平线：宽限结束仍 < 该值 → 强制平仓
public static final double OPEN_MIN_RATIO    = 1.5;   // 开仓最低线：开仓后比例须 ≥ 该值（= 预警线，避免开仓即预警/追保）
```
- 约束：`WARNING_RATIO ≥ MARGIN_CALL_RATIO > FORCE_CLOSE_RATIO`（否则配置非法回退默认，`load` 时校验）。
- 现有 `MAINTENANCE_RATIO=1.3` 与 `FORCE_CLOSE_RATIO=1.3` 保留为兼容别名（`MAINTENANCE_RATIO == FORCE_CLOSE_RATIO`），但强平判定改走 `FORCE_CLOSE_RATIO`；`MIN_COLLATERAL_RATIO` 改为实际生效：开仓初始保证金仍 `required = qty×price/leverage`，`MIN_COLLATERAL_RATIO` 作为**绝对下限**：`required = max(qty×price/leverage, MIN_COLLATERAL_RATIO×qty×price)`（默认 0.5 意为保证金不得低于名义的 50%，防过高杠杆叠加）。

### 2.3 风险区带判定（`MarginAccount.getRiskBand(prices)`，确定性）
- `EQUITY/liability`（沿用 `getMaintenanceRatio`）。无负债 → `NORMAL`。
- 新增枚举 `MarginRiskBand { NORMAL, WARNING, MARGIN_CALL, FORCE_CLOSE }`：
  - `ratio ≥ WARNING_RATIO` → `NORMAL`
  - `MARGIN_CALL_RATIO ≤ ratio < WARNING_RATIO` → `WARNING`（禁开新仓）
  - `FORCE_CLOSE_RATIO ≤ ratio < MARGIN_CALL_RATIO` → `MARGIN_CALL`（追保中）
  - `ratio < FORCE_CLOSE_RATIO` → `FORCE_CLOSE`（待强平）
- 账户持久化 `riskBand`（读档重建，避免跨档丢失）。

### 2.4 开仓最低线（`openPosition` 补强）
- 现有 `openPosition` 以「开仓后维持担保比例 ≥ `MAINTENANCE_RATIO`（1.3）」校验（`MarginManager.java:131`）→ **改为 ≥ `OPEN_MIN_RATIO`（1.5）**：开仓后若比例低于预警线则拒绝（`OpenResult.COLLATERAL_INSUFFICIENT` 语义保留，或新增 `OpenResult.RISK_BAND_EXCEEDED` 更明确，任选，须在返回枚举注明）。
- 处于 `WARNING/MARGIN_CALL/FORCE_CLOSE` 区带的账户禁止新开仓（`OpenResult.RISK_BAND_EXCEEDED`）。
- **验收**：开仓后比例 < 1.5 被拒；预警/追保/强平区带禁开新仓（ML-2）。

### 2.5 强平顺序补强（确定性，ML-2/ML-1）
- `settleAndForceClose` 强平顺序改为：**未实现亏损最大 → 未实现盈亏相同时按 `openedTick` 最老 → 再按 `assetId` 字典序**（多头 `MarginPosition` 补 `openedTick` 字段，与 `ShortPosition` 对齐）。
- 平至 `getMaintenanceRatio ≥ FORCE_CLOSE_RATIO` 即停（保留 `guard` 防死循环，上限 64）。
- **验收**：构造多仓位亏损账户，强平序列同种子可复现，且先平亏损最大/最老仓（ML-2/ML-1）。

---

## 3. AS-M2 持仓限额（t 保证，ML-3）

### 3.1 限额常量（`MarginManager`，可配置）
```java
public static final double MAX_LIABILITY_PER_ACCOUNT   = 5_000_000;  // 单账户总负债上限
public static final double MAX_POSITION_VALUE_PER_ASSET = 2_000_000; // 单资产名义上限
public static final int    MAX_SHORT_QTY_PER_ASSET      = 100_000;    // 单账户单资产最大融券数量
public static final double CONCENTRATION_LIMIT          = 0.30;      // 单资产名义 / 账户总资产 集中度上限
```

### 3.2 开仓校验（`openPosition`）
- 开仓前校验（确定性，任一超限返回新枚举 `OpenResult.POSITION_LIMIT_EXCEEDED`）：
  1. 开仓后 `getLiability ≤ MAX_LIABILITY_PER_ACCOUNT`；
  2. 开仓后该资产名义 `≤ MAX_POSITION_VALUE_PER_ASSET`（多空合计名义）；
  3. 空头开仓后该资产融券量 `≤ MAX_SHORT_QTY_PER_ASSET`；
  4. 开仓后该资产名义 / 账户总资产 `≤ CONCENTRATION_LIMIT`（总资产 = 权益 + 负债）。
- `shortablePool` 券源校验保留（`:113`）。

### 3.3 结算/强平限额强制（`settleAndForceClose`/新增 `enforcePositionLimits`）
- 结算后对超限资产按确定性顺序（最老 → 字典序）部分平仓至回到限额内（先 `enforcePositionLimits` 再 `checkForceClose`）。
- **验收**：构造超负债/超单资产/超融券/集中度超限 → 开仓拒绝或结算强制削减；限额序列确定性（ML-3）。

---

## 4. AS-M3 逐日盯市 + 多级追加保证金 margin call（t 保证，ML-4）

> 目标：在既有 20-tick 日内盯市基础上，加入**交易日边界的逐日盯市**与**多级追保状态机**（NORMAL→WARNING→MARGIN_CALL→FORCE_CLOSE），追保宽限、计数确定性、持久化。

### 4.1 时间权威纯化（ML-1/ML-4）
- `MarketEngine.java:1200` 的 `if (gameTime % AI_ORDER_INTERVAL == 0)` 融资融券结算改为 `tickCounter % AI_ORDER_INTERVAL == 0`（时间权威统一 `tickCounter`）。
- 逐日结算钩子挂在 `calendar.isSessionEnd()`（`MarketEngine.java:889` 区域），与期货 `settleDaily` 对齐。

### 4.2 逐日盯市（`MarginManager.settleDaily(playerId, engine, calendar)`）
- 每个交易日边界：按现价对全部持仓 mark-to-market，计算 `riskBand`。
- 状态机更新（确定性）：
  - `NORMAL`：若 `ratio < MARGIN_CALL_RATIO` → 进 `MARGIN_CALL`，`marginCallStreak=1`，记录 `lastMarginCallDay`（当前交易日序号）与 `marginCallDeadline = lastMarginCallDay + MARGIN_CALL_GRACE_TRADING_DAYS`；
  - `MARGIN_CALL`：`ratio ≥ MARGIN_CALL_RATIO` → 回 `NORMAL`、清零计数；否则 `marginCallStreak++`；`marginCallStreak ≥ GRACE_TRADING_DAYS`（默认 1）且仍 `< FORCE_CLOSE_RATIO` → `FORCE_CLOSE` 并强平；
  - `WARNING/FORCE_CLOSE` 按 §2.3 区带确定性迁移。
- 常量：`MARGIN_CALL_GRACE_TRADING_DAYS = 1`（对齐期货 `TOP_UP_GRACE_TRADING_DAYS`）。
- 追保通知：命中 `MARGIN_CALL` 时输出确定性日志 + 网络包推送（`MarginDataResponsePacket` 增 `riskBand/marginCallDeadline/requiredTopUp` 字段，默认值向后兼容）。

### 4.3 账户字段扩展（`MarginAccount`，均持久化）
- `MarginRiskBand riskBand`、`int marginCallStreak`、`long lastMarginCallDay`、`long marginCallDeadline`（缺省 = 0，读档兜底）。
- `MarginPosition` 增 `long openedTick`（`restoreLong` 读档含 `openedTick`，旧档缺省 0）。

### 4.4 日内兜底（保留）
- 保留 20-tick 日内 `settleAll`（改 `tickCounter` 门控）：日内 `ratio < FORCE_CLOSE_RATIO` 立即按 §2.5 强平（防止日内瞬间穿线，与逐日状态机互补）。

### 4.5 出金/入金门控
- `withdraw`：维持比例须 ≥ `WARNING_RATIO`（而非维持线）才允许出金（防出金后落入追保区）；处于 `MARGIN_CALL/FORCE_CLOSE` 禁止出金。
- `deposit`：金额为正即入账（沿用）；追保时入金自动缓解 `riskBand`（结算时重估）。
- **验收**：构造跌破追保线 → 状态机 MARGIN_CALL + 宽限；宽限结束仍不足 → 强平；入金/价格回升 → 回 NORMAL；同种子序列可复现（ML-4/ML-1）。

---

## 5. AS-M4 杠杆上限与可配置（t 保证，ML-5）

- `MAX_LEVERAGE` 由常量改为可配置（`margin.maxLeverage`，默认 5），`openPosition` 校验沿用（`:101`）但读配置。
- 开空同样受 `MAX_LEVERAGE` 约束（初始保证金 `qty×price/leverage`；`ShortPosition` 语义杠杆=1 但开仓保证金按 leverage 冻结，防过高杠杆叠加，见 §2.2）。
- 杠杆票据独立配置（§7 `leverage.minLeverage/maxLeverage`），不共用 `MAX_LEVERAGE`。
- **验收**：`margin.maxLeverage=3` 时 leverage>3 开仓被拒；开空超杠杆被拒（ML-5）。

---

## 6. AS-L1 杠杆票据市场框架 + 发行 + 定价 + 交易 + 交割（t 保证，ML-6/ML-7）

> 目标：新建**独立杠杆票据（杠杆合约）市场**——可交易、可交割、自定杠杆倍数/到期日的衍生品，标的可为 A 股股票、期货合约或商品，使市场出现更高投机性与不确定性。**确定性、可持久化、只读协同**。

### 6.1 改动文件（新增 `engine/leverage/` 包）
- 新增 `engine/leverage/LeveragedNoteMarket.java`（市场单例，由 `MarketEngine` 持有）
- 新增 `engine/leverage/LeveragedNote.java`（票据元数据/价格/状态）
- 新增 `engine/leverage/LeveragedNoteHolding.java`（玩家票据持仓：`noteId/units/avgEntryPrice`）
- 新增 `engine/leverage/LeveragedNoteRisk.java`（发行保证金/敲出判定/交割结算，可并入 Market）
- 改 `engine/MarketEngine.java`（持有 `leverageMarket`、`tick` 接入、`save/load`、`getLeverageMarket()`、`leverage.enabled` 开关）

### 6.2 票据数据结构（`LeveragedNote`）
```java
public class LeveragedNote {
    String noteId;                 // 唯一代码，如 "LNV_" + 序号/UUID（确定性）
    String name;                   // 名称，如 "沪深300×3 三个月票据"
    UnderlyingType underlyingType; // STOCK | COMMODITY | FUTURES
    String underlyingKey;          // 标的 key：A股 assetId / 商品 itemId / 期货 code
    double leverage;               // 自定杠杆倍数（LEVERAGE_MIN..LEVERAGE_MAX）
    long   maturityTicks;          // 到期日（tick）
    long   issuedTick;             // 发行日
    double unitNotional;           // 每份面额（默认 1000）
    double issuePrice;             // 发行价
    double refUnderlyingPrice;     // 发行参考标的价格（首次定价锚）
    double prevSettleUnderlying;   // 上一结算标的价格（逐日再锚）
    double lastPrice;              // 最新价
    double prevSettlePrice;        // 上一结算票据价
    long   outstanding;            // 未平仓份数
    double issuerMargin;           // 发行方冻结保证金
    NoteStatus status;             // LISTED | KNOCKED_OUT | MATURING | EXPIRED
}
enum UnderlyingType { STOCK, COMMODITY, FUTURES }
enum NoteStatus { LISTED, KNOCKED_OUT, MATURING, EXPIRED }
```

### 6.3 发行（自定杠杆倍数/到期日/标的，ML-6）
- `LeveragedNoteMarket.issue(player, underlyingType, underlyingKey, leverage, maturityTicks, units)`：
  - 校验：`leverage ∈ [LEVERAGE_MIN, LEVERAGE_MAX]`（默认 1.5~8）；`maturityTicks > tickCounter + LEVERAGE_MIN_TERM_TICKS` 且 `≤ tickCounter + LEVERAGE_MAX_TERM_TICKS`（默认 6000×5 ~ 6000×90）；标的可交易（股票在列 / 商品注册 / 期货 LISTED）。
  - 门控：`engine.hasBankCard(player)`（banking 关闭恒 true，对齐期货 BT-4/BT-10）。
  - 保证金：发行方冻结 `unitNotional × units / leverage`（从 `PlayerEconomy` 现金扣减，记 `issuerMargin`；发行方本人亦可持有）。
  - 初始定价：`refUnderlyingPrice = 标的价格`；`issuePrice = unitNotional`（每份面额 = 面额×1，起点）；`prevSettleUnderlying = refUnderlyingPrice`。
  - 上限：单次发行 `units ≤ LEVERAGE_MAX_UNITS_PER_ISSUE`（默认 100000）；全市场票据数 `≤ LEVERAGE_MAX_NOTES`（默认 256，确定性淘汰 EXPIRED 再复用/拒绝）。
- **验收**：自定杠杆/到期/标的成功发行；非法杠杆/到期被拒；保证金正确冻结（ML-6）。

### 6.4 定价（逐日再锚 + 杠杆，确定性，ML-6/ML-1）
- 每交易日结算（`calendar.isSessionEnd`）按 **settle-to-settle** 再锚：
  ```java
  double dailyReturn = leverage * (underlyingNow / prevSettleUnderlying - 1.0);
  double next = round2( prevSettlePrice * (1.0 + dailyReturn) );
  lastPrice = next;
  // 敲出：累计或单日反向超 1/leverage → 归零
  if (next <= KNOCK_OUT_FLOOR) { lastPrice = 0; status = KNOCKED_OUT; settleOutstanding(); }
  prevSettlePrice = lastPrice; prevSettleUnderlying = underlyingNow;
  ```
- 标的价源（只读）：
  - `STOCK` → `engine.getState().getAsset(underlyingKey).getLastPrice()`
  - `COMMODITY` → `engine.getCommodityMarket().getCommodity(underlyingKey)?.getPrice()`
  - `FUTURES` → `engine.getFuturesMarket().getContract(underlyingKey)?.lastPrice`
- 中间 tick 同步 `lastPrice`（沿用结算价与最新标的价重估，确定性，无随机）。
- **验收**：杠杆 × 标的日收益正确衍生；标的上涨 → 票据放大上行；反向超 1/杠杆 → 敲出归零；同种子可复现（ML-6/ML-1）。

### 6.5 二级市场交易（可交易，ML-6）
- 每票据一张确定性订单簿（L1 盘口：买/卖一档 + 队列，复用 P2 `OrderBook` 撮合语义，不进入 A 股撮合路径）。
- `buy(player, noteId, units)`：现金扣 `lastPrice×units`（或盘口价），记入持仓；`sell(player, noteId, units)`：从持仓扣减，现金入 `lastPrice×units`（只可卖出已持有）。
- 交易时间门控：`calendar.isMarketOpen()`（休市拒绝，与商品/期货一致）。
- **验收**：票据可买卖、持仓变化、现金出入正确；休市拒绝（ML-6）。

### 6.6 到期交割（可交割，ML-6）
- `tick ≥ maturityTicks` 且当交易日结算后 → 进入 `MATURING`，对全部未平仓持仓执行交割：
  - 现金结算（默认 `LEVERAGE_CASH_SETTLEMENT=true`）：持票人 PnL = `(finalNotePrice − avgEntryPrice) × units`，计入 `PlayerEconomy` 现金；发行方剩余 `issuerMargin` 返还/结算。
  - 实物交割（可选，`LEVERAGE_CASH_SETTLEMENT=false` 且标的是可交付物品/商品）：多头按 `units × multiplier` 交付实物（物品→库存 / 商品→物品），空头交付/现金补偿（确定性规则，对齐期货交割）。
  - 交割后票据 `status=EXPIRED`，从在售移除。
- **敲出交割**：`KNOCKED_OUT` 时按 `finalNotePrice=0` 了结（持票人亏面额，发行方保证金兑付）。
- **验收**：到期现金/实物交割正确；敲出归零兑付；票据退出（ML-6）。

### 6.7 与股票/期货/商品协同（ML-7）
- 票据只读消费三类标的价，**不写回**标的供需/撮合（衍生品不污染现货）。
- 发行方保证金可能来自 `PlayerEconomy`（现金）；如需与期货/证券协同，可配置由 `SecuritiesManager`（证券自营）或期货保证金账户托管（本期以现金托管为默认，托管方为可配置扩展点）。
- **验收**：三类标的票据价格分别跟随标的；杠杆放大产生更高投机波动；标的行情本身不受票据交易影响（ML-7）。

---

## 7. 参数包与调参约束

集中为 `MarginManager`/`LeveragedNoteMarket` 内常量（推荐独立 `engine/margin/MarginParams.java` + `engine/leverage/LeverageParams.java`）。默认值与硬约束：

```
// ===== 保证金交易（part ①） =====
margin.enabled                     = true
margin.maxLeverage                 = 5          // 杠杆上限（原 MAX_LEVERAGE 常量 → 配置）
margin.warningRatio                = 1.5        // 预警线
margin.marginCallRatio             = 1.4        // 追加保证金线
margin.forceCloseRatio             = 1.3        // 强平线
margin.openMinRatio                = 1.5        // 开仓最低线（开仓后比例须 ≥）
margin.minCollateralRatio          = 0.5        // 开仓初始保证金绝对下限（名义的 50%）
margin.marginCallGraceTradingDays  = 1          // 追保宽限交易日数
margin.mtmIntervalTicks            = 20         // 日内盯市间隔（沿用 AI_ORDER_INTERVAL，tickCounter 门控）
margin.maxLiabilityPerAccount      = 5_000_000  // 单账户总负债上限
margin.maxPositionValuePerAsset    = 2_000_000  // 单资产名义上限
margin.maxShortQtyPerAsset         = 100_000    // 单账户单资产最大融券数量
margin.concentrationLimit          = 0.30       // 单资产名义/账户总资产 集中度上限
// ===== 杠杆票据（part ②） =====
leverage.enabled                   = true
leverage.minLeverage               = 1.5        // 自定杠杆下限
leverage.maxLeverage               = 8.0        // 自定杠杆上限
leverage.minTermTicks              = 6000 * 5   // 最短到期（5 交易日）
leverage.maxTermTicks              = 6000 * 90  // 最长到期（90 交易日）
leverage.unitNotional              = 1000       // 每份面额
leverage.maxUnitsPerIssue          = 100000     // 单次发行份数上限
leverage.maxNotes                  = 256        // 全市场票据数上限
leverage.knockOutFloor             = 0.0        // 敲出归零阈值（≤0 即敲出）
leverage.cashSettlement            = true       // true=现金交割；false=实物（可交付物品/商品）
leverage.tradeGateByCalendar       = true       // true=沿用 A 股休市门控；false=票据全天可交易
```

- **隔离**：`margin.enabled=false` → 保证金账户不结算/不接入多级风险线与限额（回落现状基线）；`leverage.enabled=false` → 不注册票据、不 tick、不持久化（ML-10）。
- **不引入**：期权/涡轮/可转债/互换等其它衍生；套保/套利自动策略；真实节假日（沿用 A 股 7 期日历）。

---

## 8. 兼容性（继承 P0/P1/P2/A 股/公司/商品/期货/银行，保证金 + 杠杆票据增量）

### 8.1 网络包 / UI / Web
- **不删除/不重构**既有 packet 结构。`MarginDataResponsePacket` 只**新增字段**（`riskBand/marginCallStreak/marginCallDeadline/requiredTopUp/limitUsage`，默认值向后兼容）；新增杠杆票据 packet（`LeveragedNoteListPacket`、`LeveragedNotePositionSyncPacket`、`LeveragedNoteOrderPacket`、`RequestLeveragedNoteDataPacket`、`LeveragedNoteIssuePacket`，`ModMessages` 注册）。
- `LocalHttpServer` 既有 `/api/*` 不动，只**新增**：`GET /api/margin`（账户权益/负债/维持担保比例/风险区带/追保/限额用量）、`POST /api/margin/deposit`、`GET /api/leverage/notes`、`GET /api/leverage/positions`、`POST /api/leverage/issue`、`POST /api/leverage/order`（绑定玩家经 `/api/session` 机制，最终走确定性引擎方法）。
- 前端 `FinancialTabletScreen` 为增量（保证金页加风险区带/追保/限额展示 + 新增杠杆票据页：列表/持仓/发行/交易表单），展示层零随机、零引擎确定性改动，非硬性验收（对齐 `FRONTEND_THS_SPEC.md` §1.3）。

### 8.2 H2 / NBT 持久化（对齐 H2_MIGRATION.md）
- 权威在引擎 blob（`MarketEngine.save()/loadInternal()`），随 `engine_kv.engine_main` + NBT 附件镜像双写。
- 保证金：`tag.put("margin", marginManager.save())` 扩展字段——每账户 `collateral/riskBand/marginCallStreak/lastMarginCallDay/marginCallDeadline` + 每多头 `asset/qty/entry/lev/openedTick` + 每空头（沿用）+ 券源池（沿用）；`loadInternal` 用 `contains` 兜底（旧档缺新字段 → 默认 `NORMAL`/0，读档不失败，ML-9）。
- 杠杆票据：`tag.put("leverage", leverageMarket.save())`——每票据 `noteId/underlyingType/underlyingKey/leverage/maturityTicks/issuedTick/unitNotional/issuePrice/refUnderlyingPrice/prevSettleUnderlying/lastPrice/prevSettlePrice/outstanding/issuerMargin/status` + 每玩家持仓 `noteId/units/avgEntryPrice` + 各票据订单簿；`contains` 兜底缺失重建空表，读档不失败（ML-9）。

### 8.3 可复现（保证金 × 杠杆票据 × SeedableRandom）
- 保证金强平/追保、票据定价/交割/敲出全部确定性 `f(tick)`，无随机；时间权威一律 `tickCounter`（修正 `MarketEngine.java:1200` 的 `gameTime` 门控）。
- **验证**：t5 固定 `engineSeed` 两次运行，保证金强平/追保序列 + 票据定价/交割/敲出序列哈希一致（ML-1）。

### 8.4 与已交付引擎协同（不破坏）
- 不动 A 股 `submitOrder`/`OrderBook`/`SettlementSystem`/`TradingCalendar`/`PriceDiscoveryEngine` GBM（文首硬约束）。
- 保证金与票据只**读取** `InflationSystem`/`MoneyAggregate`/`CentralBank`/`getState`/`CommodityMarket`/`FuturesMarket`（宏观/价源），不改其公式。
- `hasBankCard` 门控（`BANKING_TRADE_SPEC` BT-4/BT-10）：banking 关闭恒 true（不回归银行基线）。
- 保证金与期货保证金/强平**分离**（保证金走 `MarginManager` 股票路径，期货走 `FuturesMarket`，票据走 `LeveragedNoteMarket`），互不混入。

---

## 9. 对 t5（整体验证 + 构建验证）的需求

1. **构建验证**：`./gradlew compileJava` 通过（ML-0）；`./gradlew build` 出包。
2. **编译/随机审计**：`grep -rn "Math.random\|new Random\|ThreadLocalRandom" src/main/java` 不得出现保证金/杠杆票据增量引入的调用；时间权威检查：新结算一律 `tickCounter` 门控（ML-1）。
3. **分级风险线（ML-2）**：构造比例落入 NORMAL/WARNING/MARGIN_CALL/FORCE_CLOSE 四区带；开仓后比例 < `OPEN_MIN_RATIO` 被拒；预警/追保/强平区带禁开新仓；强平顺序（亏损最大→最老→字典序）确定性。
4. **持仓限额（ML-3）**：超负债/超单资产/超融券/集中度超限 → 开仓拒绝（`POSITION_LIMIT_EXCEEDED`）或结算强制削减；限额序列确定性。
5. **逐日盯市 + 多级追保（ML-4）**：跌破追保线 → MARGIN_CALL + 宽限计数；宽限结束仍不足 → 强平；入金/价格回升 → 回 NORMAL；`marginCallStreak/lastMarginCallDay` 持久化。
6. **杠杆上限/配置（ML-5）**：`margin.maxLeverage=3` 生效；开空超杠杆被拒。
7. **杠杆票据全流程（ML-6）**：发行（自定杠杆/到期/标的）→ 逐日定价（杠杆×标的收益）→ 二级交易 → 到期现金/实物交割；敲出归零兑付；票据退出；全流程确定性。
8. **协同（ML-7）**：STOCK/COMMODITY/FUTURES 三类标的票据价格分别跟随标的；杠杆放大波动；标的行情不受票据交易影响（只读协同）。
9. **可复现脚本（ML-1）**：固定 `engineSeed` 一键重跑 N tick（跨交易日边界/到期/敲出），保证金强平/追保 + 票据序列两次哈希一致。
10. **持久化（ML-9）**：跑一段流程后 save→load，保证金风险状态/追保计数 + 票据持仓/价格/交割状态完整恢复；旧档缺字段读档不失败。
11. **隔离（ML-10）**：`margin.enabled=false`/`leverage.enabled=false` 回落基线，与现有行为对比证明贡献。

---

## 10. 实施顺序与依赖（供 scheduler）

```
t1 (本规格) ──→ t2 (保证金完善: 分级风险线 + 持仓限额 + 逐日多级追保 + 时间权威纯化 + 持久化扩展)
        └──→ t3 (杠杆票据: LeveragedNoteMarket 发行/定价/交易/交割/敲出 + MarketEngine 接入 + 持久化)
                └──→ t4 (前端/API: 网络包 + LocalHttpServer /api/margin,/api/leverage + 前端保证金/票据页) ──→ t5 (验证) ──→ t6 (评审)
```
- **依赖**：t2 依赖 t1（保证金多级风险线基座）；t3 依赖 t1（票据市场框架），并复用 `FuturesMarket` 逐日结算/强平/交割骨架作模板；t4 依赖 t1 与 t2/t3（API/前端）；t5 依赖 t2/t3/t4；t6 依赖 t5。
- **优先级**：t2（保证金完善，ML-2~ML-5 + 持久化）先行；t3（杠杆票据，ML-6/ML-7）核心；t4（前端/API，ML-8）收口。
- **注**：保证金 + 杠杆票据新增随机为零（全部确定性）；若实现需要任何随机，一律走既有 `SeedableRandom` 派生子流并登记，否则违反 ML-1。

---

## 11. 与非目标边界（防范围蔓延）

- **不动** `PriceDiscoveryEngine.step()` GBM/GARCH、A 股交易规则常量、`TradingCalendar` 内部公式、`submitOrder`/`OrderBook`/`SettlementSystem` 股票语义、法币 M2/央行/税收/银行公式、已交付期货/商品公式。
- **不做**：期权/涡轮/可转债/互换等其它衍生；套保/跨期套利自动策略；真实节假日（沿用 A 股 7 期日历）；集合竞价外复杂撮合；杠杆票据做市商自动报价（由订单簿撮合成交）。
- **保证金与期货保证金/杠杆票据分离**：`MarginManager` 股票融资融券、`FuturesMarket` 期货保证金、`LeveragedNoteMarket` 票据三套独立，互不混入（文首硬约束 5）。
- **杠杆票据实物交割**：默认现金结算（`LEVERAGE_CASH_SETTLEMENT=true`）；实物交割为可配置项（仅对可交付物品/商品标的），流体交付走 `FluidStorageLedger` 语义（可选）。
- **全部分支 O(账户)/O(资产)/O(票据) 常数级**；逐日结算/强平/交割/票据结算每交易日一次遍历（非 per-tick 线性扫）。

---

## 12. 输入/来源索引

- 团队 Goal：保证金交易完善（多级保证金/强平线、持仓限额、逐日盯市/多级追保、可复现）+ 新增杠杆合约产品（可交易/可交割杠杆票据，自定杠杆倍数/到期日，与股票/期货/商品协同），协同已交付引擎/前端，确定性可复现、H2 兼容。
- 已交付基座：`A_SHARE_TRADING_SPEC.md`（TradingCalendar 时间模型、A 股规则）、`ECON_P0/P1/P2_SPEC.md`（SeedableRandom/GBM/P2）、`ECON_FIAT_M2_SPEC.md`（法币/M2/通胀）、`COMPANY_SPEC.md`、`COMMODITY_FUTURES_SPEC.md`（`FuturesMarket` 逐日结算/保证金/追保/强平/交割模板）、`FRONTEND_THS_SPEC.md`（前端 + LocalHttpServer）、`H2_MIGRATION.md`（H2/NBT）、`BANKING_TRADE_SPEC.md`（储蓄卡门控）。
- 代码证据：`engine/margin/MarginManager.java`（`:30-38` 常量、`:99-138` 开仓、`:159-182` 强平、`:250-324` 持久化）、`engine/margin/MarginAccount.java`、`engine/margin/ShortPosition.java`、`engine/MarketEngine.java`（`:81,:183,:102,:224,:250,:889-895,:1195-1206,:1583-1590,:1634,:1738,:1950`）、`engine/futures/FuturesMarket.java`（逐日结算/追保/持仓限额/交割骨架）、`engine/products/FinancialProduct.java`（非杠杆衍生，票据独立引擎）、`network/packets/MarginOrderPacket.java`/`MarginDataResponsePacket.java`、`web/LocalHttpServer.java`（`:122-135` 路由）。
- 现实 A 股融资融券规则依据（本规格引用的公开规则）：[上海证券交易所融资融券交易实施细则](https://www.sse.com.cn/assortment/stock/list/margin/)、[中国证监会证券公司融资融券业务管理办法](http://www.csrc.gov.cn/)。核心规则点：维持担保比例预警线/追保线/平仓线分级、逐日盯市、持仓集中度与限额、追保宽限、强制平仓、融资买入一手整数倍、融券可借券源。
