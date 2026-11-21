# COMMODITY_FUTURES_SPEC — 商品市场完善 + 期货市场（CZCE 规则）需求规格（需求契约，供 t2/t3/t4 实施、t5 验证、t6 评审）

> - 角色：architect（方案架构 / 商品期货需求规格，含 CZCE 交易规则）为任务 t1 产出。
> - 输入：团队 Goal + 当前源码审计（本规格对照实际源码给出文件/方法/字段/默认参数）。已交付前置基座：`A_SHARE_TRADING_SPEC.md`（A 股交易日历 `TradingCalendar`：5 分钟交易日 / 每 5 交易日休市 2 日 / 交易日内全天连续；±10% 涨跌停；T+1；一手 100；价格/时间优先撮合）、`ECON_P0/P1/P2_SPEC.md`（SeedableRandom、GBM/GARCH、P2 订单簿）、`ECON_FIAT_M2_SPEC.md`（法币/M0/M1/M2/央行/税收/通胀）、`COMPANY_SPEC.md`（公司/AssetScanner 复用 `commodityMarket`）、`FRONTEND_THS_SPEC.md`（前端 + LocalHttpServer API）、`H2_MIGRATION.md`（H2/NBT 双写）。
> - 用途：engine-dev 按本规格实现 t2（商品市场动态注册 + 经济学定价 + 去随机）、t3（CZCE 期货市场）、t4（商品/期货前端 + API 完善）；test-engineer 按本规格实现 t5（整体验证 + 构建/可复现）；reviewer 按本规格验收 t6。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick 步进内 O(商品)/O(合约)/O(持仓) 常数级计算。**时间权威用引擎 tick 计数器（`MarketEngine.tickCounter`）**，非墙钟、非 `gameTime`（`gameTime` 可被 `/time` 改动破坏时段确定性）。
> - **可复现性硬约束（继承 P0/A0-3）**：同一种子同行情必须保持。所有新增随机一律走 `SeedableRandom`（`engine/random/SeedableRandom.java`，SplitMix64），**禁用 `ThreadLocalRandom` / 裸 `new Random()` / `Math.random()`**。商品定价 / 期货结算 / 交割 / 强平全部为确定性逻辑。
> - **兼容边界硬约束（不动已定稿语义）**：
>   1. **不修改** `PriceDiscoveryEngine.step()` 的 GBM/GARCH 公式与 A 股交易规则常量（`PriceDiscoveryEngine.java:30-61`：`A_SHARE_LIMIT_RATIO`/`TICK_SIZE`/`BOARD_LOT`）。
>   2. **不修改** A 股交易日历 `engine/calendar/TradingCalendar.java`（时间模型）；期货直接**复用**该日历作为交易会话门控与「合约月」派生基准（本规格只读消费，不改内部公式）。
>   3. **不修改** `MarketEngine.submitOrder`/`OrderBook`/`SettlementSystem` 的 A 股股票交易语义；商品与期货为**并列独立市场**（新引擎），不进入 A 股股票撮合/涨跌停/T+1 路径。
>   4. **不修改** 法币 M2 / 央行 / 税收语义（`ECON_FIAT_M2_SPEC.md`）；商品定价与期货结算**读取** `InflationSystem`/`MoneyAggregate`/`MacroEnvironment` 作为宏观驱动，不改其内部公式。

---

## 0. 目标与全局验收（对应团队 Goal）

> ①商品市场可交易**游戏内所有生存模式可获得**的物品、流体及其它模组物品（**动态注册**：覆盖 `BuiltInRegistries.ITEM` 全部非 AIR/禁用物品 + `BuiltInRegistries.FLUID` 可获流体 + 任意模组物品），定价系统**符合经济学**（价格由**供需 + 生产成本 + 宏观货币/通胀**共同驱动，去随机、可复现）；②新增**期货市场**，遵循**郑州商品交易所（CZCE）交易规定**（合约月份 / 涨跌停板 / 保证金 / 交割 / 强制平仓等），但交易日历**沿用已交付的 A 股规则**（5 分钟交易日、交易日内全天连续、每 5 交易日休市 2 日）。与已交付经济引擎 / A 股 / 法币 M2 / 公司 / 前端协同，保持确定性可复现、H2 持久化兼容。完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| CF-0 | 编译通过 | `./gradlew compileJava` 退出码 0；`./gradlew build` 出包 | 每项实施 + t5 |
| CF-1 | 可复现性不回归（继承 P0） | 同 `engineSeed` 跑固定 tick，商品价格序列 + 期货结算/成交序列两次逐元素一致；新增逻辑零新随机（全仓 `grep` 无 `Math.random`/`new Random`/`ThreadLocalRandom` 新增调用；商品定价/期货结算/交割/强平为纯确定性函数） | t2~t4 + t5 |
| CF-2 | **商品动态注册（t2）** | 商品市场在引擎 init 实际注册 `BuiltInRegistries.ITEM` 全部物品（除 AIR/command_block/barrier/structure/light）+ 可获流体（水/岩浆/模组流体）+ 任意模组物品；注册后 `commodities` 非空、可交易；`CommodityRegistry.autoRegister` 真正被调用（当前死代码，见 §1） | **t2** + t5 |
| CF-3 | **经济学定价（t2）** | 商品基价 = 确定性函数（稀有度档 + 配方生产成本 + 用途/标签），不再是纯字符串启发式常量；市价由**供给 + 需求 + 生产成本锚 + 宏观货币/通胀因子**共同驱动；价格有界且收敛；上下游配方价格传播正确 | **t2** + t5 |
| CF-4 | **物品 + 流体交易（t2）** | 玩家可买/卖物品（走库存，现有路径）与**流体**（新增流体存储账本：买入入账、卖出扣减）；休市时商品交易门控与 A 股日历一致；成交无 `Math.random` | **t2** + t5 |
| CF-5 | **期货市场框架 + 合约（t3）** | 期货引擎注册交付品种合约（`品种+交割月` 代码）；合约月份按**确定性合约日历**从 tick 派生（连续/特定月份集合）；持仓、保证金账户、未平仓量、逐日结算齐备 | **t3** + t5 |
| CF-6 | **CZCE 涨跌停板 + 保证金 + 逐日无负债结算（t3）** | 涨跌停板锚**昨结算价**（非 prevClose），默认 ±4%（CZCE 典型），连续涨跌停**扩大幅度 + 提高保证金**；初始保证金按比例（5%~10%）、临近交割月**分档提高**；每交易日按**当日结算价**（成交加权平均）逐日盯市结算盈亏进保证金 | **t3** + t5 |
| CF-7 | **强制平仓 + 交割（t3）** | 保证金低于维持线且未追保 → 强制平仓（确定性）；超持仓限额 → 强平；临近交割月/最后交易日未平仓 → 进入交割（实物交割交付物品/流体，抽象品种可配置现金结算兜底）；交割月后合约退出 | **t3** + t5 |
| CF-8 | **期现联动 + 经济耦合（t3）** | 期货价由**现货价 + 持有成本/预期 + 宏观**驱动，临近交割收敛于现货（期现收敛）；期货市场消费法币 M2 / 通胀 / 政策利率作为宏观驱动，不改其公式 | **t3** + t5 |
| CF-9 | **前端 / API 协同（t4）** | 前端商品页可看价/供需/趋势并买/卖物品与流体；期货页可看合约/现价/结算价/涨跌/持仓/保证金/强平状态并开/平仓；javaBridge + `LocalHttpServer` 双轨（`/api/commodities`、`/api/futures`、`/api/futures/positions`、POST 商品买卖 / 期货开平仓） | **t4** + t5 |
| CF-10 | **H2 / NBT 持久化不回归** | 商品状态（价格/供需/成本锚/日成交量）+ 期货状态（合约/持仓/保证金账户/未平仓量/结算价/交割期）随 `MarketEngine.save()/loadInternal()` blob（`engine_kv.engine_main` + NBT 镜像）序列化/恢复；旧存档缺字段用 `contains` 兜底重建，读档不失败 | t2/t3 + t5 |
| CF-11 | **隔离（不回归）** | `commodity.enabled=false` 回落现状（商品市场空置、不下单不 tick 接入点）；`futures.enabled=false` 时不注册任何合约；两者关闭时与 P0/P1/P2/A 股/公司基线逐位一致 | t2/t3 + t5 |

**验收流程（t5 落地）**：固定 `engineSeed` 跑固定 tick 两次，商品价 + 期货结算/成交/强平序列哈希一致（CF-1）；静态核对动态注册覆盖物品+流体+模组物品（CF-2）；确定性逻辑链复现供需/成本/宏观驱动的价格演化与配方传播（CF-3）；商品买/卖物品+流体、休市门控（CF-4）；构造期货开/平仓、涨跌停、逐日结算、追保/强平、交割全流程（CF-5~CF-7）；期现收敛与宏观耦合（CF-8）；前端/API 冒烟（CF-9）；save→load 恢复商品+期货状态（CF-10）；开关隔离与基线对比（CF-11）；`./gradlew build` 出包（CF-0）。

---

## 1. 现状诊断（代码审计结论）

| 类 | 文件 | 现状 | 问题（商品/期货视角） |
|---|---|---|---|
| `CommodityMarket` | `engine/CommodityMarket.java` | 持有 `Map<Item,CommodityEntry> commodities`；`buy/sell` 走玩家库存物品、按 `getPrice()×qty` 记账；`tick` 每 `gameTime%6000` decay、`%24000` 重置日量、`%200` 同步、`%1200` **用 `Math.random()` 加噪声**（`:122-127`） | **死代码接线 + 可复现破坏**：`commodityMarket.tick` 从未被调用；用 `gameTime` 而非 `tickCounter` 作时间权威；`Math.random()` 违反可复现性；无流体支持（只认 `Item` 库存）；无 `save/load` 持久化 |
| `CommodityRegistry` | `engine/CommodityRegistry.java` | `autoRegister`/`loadFromJson` **从未被任何代码调用**（仅定义）；`estimateBasePrice` 是字符串启发式（`ingot→10`、`diamond→25`…），`inferIndustries` 也靠路径猜 | **动态注册未落地**：运行时 `commodities` 为空 → `buy/sell` 恒失败、`MarketSentiment` 商品因子恒 0；基价无经济学依据（无成本/稀有度/配方驱动）；只注册物品不注册流体 |
| `CommodityEntry` | `engine/CommodityEntry.java` | `price = basePrice × demand/(supply+0.1)`，夹在 `[0.1,10]×basePrice`；`basePrice` final 不变；`decay` 缓慢向 1 收敛 | **定价粗放**：`basePrice` 静态不随生产成本演化；无宏观货币/通胀驱动；无流体；无上下**成本锚**约束（仅相对自身 10 倍带宽） |
| `MarketEngine` | `engine/MarketEngine.java` | 构造创建 `commodityMarket`（`:60`）并传给 `FinancialWarSystem`/`EconomicCycleEngine`/`FundamentalEngine`/`MarketSentiment`（`:155,:860-866`），提供 `getCommodityMarket()`（`:1468`）；`save()`（`:1509+`）持久化 price/macro/state/companies/fiat/orderFlow/orderBooks/tradingCalendar/t1Ledger/quant | **无商品/期货接入**：init 不调 `CommodityRegistry.autoRegister`；tick 不调 `commodityMarket.tick`；无 `futuresMarket`；`save/load` 无商品/期货字段 |
| `MarginManager` | `engine/margin/MarginManager.java` | 股票融资融券：保证金账户 `collateral/equity/liability`、`MAINTENANCE_RATIO=1.3`、强平、可借券源池 | **可复用模板**：期货保证金/逐日结算/强平可借鉴其账户模型，但**不得混入股票融资融券路径**（期货为独立市场） |
| `TradingCalendar` | `engine/calendar/TradingCalendar.java` | 已交付 A 股时间模型：`TRADING_DAY_TICKS=6000`、5 开 2 休（7 期周期）、`isMarketOpen/isTradingDayStart/isSessionEnd/getTradingDayNumber/getPeriod` | **期货复用基座**：期货交易会话门控、合约月派生、逐日结算与交割时点全部以此为权威 |
| `LocalHttpServer` | `web/LocalHttpServer.java` | 有 `/api/stocks,index,kline,depth,account,positions,portfolio,orders,myTrades,order,cancelOrder` | **缺商品/期货 API**：无 `/api/commodities`、无 `/api/futures` 系列 |
| `CommodityPriceSyncPacket` / `RequestCommodityPricePacket` | `network/packets/` | 已存在：游戏内商品价同步/请求 | 沿用并扩展（加供需/成本锚/趋势字段，向后兼容默认值） |
| `FinancialTabletScreen` | `screen/FinancialTabletScreen.java` | 已有「商品」Tab（`:66-69,:751-794` 等） | 前端商品页增强 + 新增期货页（t4，协同增强） |

**根因**：商品市场是「平行死代码」——注册/结算/tick 三处均未接线，运行时为空、永不演化、不持久化；且现有 `tick` 含 `Math.random()`。期货市场**完全不存在**。本规格把商品市场接线落地 + 经济学改造，并新建独立 CZCE 期货市场。

---

## 2. AS-1 商品市场动态注册（物品 + 流体 + 模组物品）（t2）

> 目标：让 `CommodityMarket` 真正注册并交易**游戏内所有生存模式可获得的物品、流体及其它模组物品**（动态注册 = 运行期枚举注册表，天然覆盖任意模组物品/流体）。

### 2.1 改动文件
- 改 `engine/CommodityRegistry.java`（落地 `autoRegister` 调用点 + 流体注册 + 成本基价）
- 改 `engine/CommodityMarket.java`（新增流体交易账本、`tick` 时间权威化、持久化）
- 改 `engine/CommodityEntry.java`（基价动态化/成本锚/宏观因子、流体 entry）
- 改 `engine/MarketEngine.java`（`init()` 调 `autoRegister`；`tick()` 调 `commodityMarket.tick`；`save/load` 接商品状态）

### 2.2 动态注册（CF-2）
- **物品**：`MarketEngine.init()`（`:278`）在 `registerAllToMarket` 后调用 `CommodityRegistry.autoRegister(this.commodityMarket)`（当前从未调用）。`autoRegister` 遍历 `BuiltInRegistries.ITEM`，跳过 `Items.AIR` 与 `command_block/barrier/structure/light`（沿用现有排除 `CommodityRegistry.java:22-23`），其余全部注册。
- **流体**：`CommodityRegistry` 新增 `registerFluids(CommodityMarket)`，遍历 `BuiltInRegistries.FLUID`，注册**可获流体**（`Fluid.isSource` 的水/岩浆 + 任意模组源流体；跳过 `empty`）；每个流体注册为 `FluidCommodityEntry`（§2.4）。`MarketEngine.init` 一并调用。
- **模组物品/流体**：因遍历 `BuiltInRegistries`，任意已注册模组物品/流体**自动纳入**，无需白名单（CF-2「其它模组物品」由注册表枚举天然满足）。
- **注册幂等**：`register` 对已存在 key 覆盖为最新（不改引用），重复 init/读档不重复膨胀。

### 2.3 成本基价（CF-3，生产成本驱动）
把 `CommodityRegistry.estimateBasePrice` 的纯字符串启发式改为**确定性成本锚**：

```java
// CommodityRegistry：basePriceOf(Item) —— 确定性，无随机
double basePriceOf(Item item) {
  // 1) 配方生产成本（成本驱动）：min over recipes of Σ ingredient.basePrice × qty（走 RecipeAnalyzer 图，含上游）；
  //    无配方或成本为 0 → 用稀有度档保底
  // 2) 稀有度档保底（无配方 / 天然矿产物）：RAWDROP_RARITY_TIER 常量表（按物品属性/标签/资源路径档位），
  //    如 STONE=0.5、LOG=2、INGOT=10、ORE=8、GEM=25、BLOCK=90…（复用现有启发式档位作**稀有度档**，但仅作无配方保底）
  // 3) 效用/工艺加成：可合成工具的成品按 Σ原料 + 工艺溢价（如 pickaxe = Σ原料 + 0.2×Σ原料）
  return round2( max(rarityFloor, recipeCost) ) * rarityFloor>0 ? 1 : 1;
}
```

- **配方成本**：`RecipeAnalyzer`（`engine/RecipeAnalyzer.java`）图已构建（`buildGraph`，`MarketEngine.java:845`）。`basePriceOf` 对每物品取**最小配方成本** `min_over_recipes( Σ ingredient.basePrice × ingredientCount )`；上游成本递归（带环防护，最多 `MAX_RECIPE_DEPTH` 层，默认 4）。无配方或全零成本 → 落稀有度档。
- **确定性**：全部常量 + 已构建配方图，无随机。
- **验收**：同种子下所有物品基价唯一确定；铁锭类 > 铁矿 > 石头；钻石 > 铁锭；工具成品 ≈ Σ原料 + 工艺溢价（CF-3）。

### 2.4 `CommodityEntry` 扩展（供需 + 成本锚 + 宏观，CF-3）

新增/修改字段（均含持久化）：
```java
public class CommodityEntry {
    // 既有：item, price, basePrice, supply, demand, dailyVolume, industries
    // 新增：
    private double costAnchor;        // 生产成本锚 = 由 §2.3 派生，随上游配方价演化（成本推动型通胀）
    private double demand;            // 需求压力量
    private double supply;            // 供给压力量
    private double macroFactor;       // 宏观货币/通胀因子（由外部写入，见 §2.5）
    private double prevSettlePrice;   // 上一商品结算价（供期货锚定，见 §6；默认=basePrice）
}
```

**经济学定价模型**（替换 `updatePrice` 的 `basePrice×ratio` 粗放式）：
```java
// 确定性，无随机
void updatePrice() {
    double s = supply + 1.0, d = demand + 1.0;
    double excess = (d - s) / (s + d);            // 供需缺口 ∈ (-1,1)
    double anchorTerm = costAnchor * (1.0 + PRICE_DRIFT_TO_COST * excess);   // 围绕成本锚浮动
    double macroTerm  = macroFactor;                                       // 宏观货币乘数（1=中性）
    price = round2( clamp(anchorTerm * macroTerm, costAnchor*PRICE_FLOOR_RATIO, costAnchor*PRICE_CAP_RATIO) );
    // 收敛：excess→0 时 price→costAnchor×macroTerm；有界 [PRICE_FLOOR_RATIO, PRICE_CAP_RATIO]×costAnchor
}
```
- 默认 `PRICE_DRIFT_TO_COST=0.20`、`PRICE_FLOOR_RATIO=0.2`、`PRICE_CAP_RATIO=5.0`（可调）。
- **成本锚演化**：每结算周期 `costAnchor` 沿上游配方成本漂移（`costAnchor = clamp( Σ上游价/配方成本, …)`，无上游则保持），使生产/采矿成本上升推动商品价（成本推动）。
- `buyPressure/sellPressure/adjustDemand/adjustSupply` 沿用（更新 `supply/demand` + 日量 + `updatePrice`），**删除任何 `Math.random`**。
- **流体 entry**：新增 `FluidCommodityEntry extends CommodityEntry`（或给 `CommodityEntry` 加 `key` 区分 `Item`/`Fluid`），字段 `Fluid fluid`，价格模型同物品。

### 2.5 宏观驱动（CF-3，与法币 M2 协同）
- `CommodityMarket.tick(level, engine)`（签名新增 `MarketEngine` 引用）每交易日（`calendar.isTradingDayStart()`）调用 `applyMacro(MacroSnapshot)`：
  - `macroFactor` 读 `InflationSystem.getInflationRate()` 与 `MoneyAggregate.getM2Growth()`（`ECON_FIAT_M2_SPEC`）：`macroFactor = 1 + (0.5*inflationRate + 0.5*m2Growth)`（通胀/M2 扩张 → 商品价整体上行，货币量驱动价格水平的现实传导）。**只读，不改宏观公式**。
  - `cycleEngine.getEconomicHealth()` 作为需求侧增益：`demand *= (1 + 0.1*economicHealth)`（经济周期 → 商品需求）。
- **验收**：M2 扩张/通胀上升 → 商品整体价格水平上行；经济上行 → 需求/价上移（CF-3/CF-8）。

---

## 3. AS-2 商品交易（物品 + 流体）+ 去随机 + 持久化（t2）

### 3.1 改动文件
- 改 `engine/CommodityMarket.java`（tick 接线 + 流体交易 + 持久化）
- 改 `engine/MarketEngine.java`（`tick()` 调 `commodityMarket.tick`；`save/load` 接商品状态）
- 改 `attachment/PlayerEconomy.java`（可选：流体存储账本入口，`contains` 兜底不破坏既有序列化）

### 3.2 tick 接线 + 时间权威（CF-4/CF-1）
- `MarketEngine.tick(ServerLevel)`（`:771`）在 A 股日历推进（`calendar.advance(tickCounter)`）之后、宏观步进区域调用 `commodityMarket.tick(level, this, calendar)`。
- `CommodityMarket.tick` **改用 `engine.getTickCounter()` 作时间权威**（不再用 `level.getGameTime()`）：
  - `tickCounter % 6000 == 0` → `decay`（供需缓慢回归）；
  - `calendar.isTradingDayStart()` → 日量重置 + `applyMacro`（§2.5）+ `costAnchor` 演化；
  - `tickCounter % COMMODITY_SYNC_INTERVAL`（默认 200）→ 同步 `CommodityPriceSyncPacket`（沿用）；
  - **删除** `tick` 内 `Math.random()` 噪声段（`CommodityMarket.java:121-127`）——任何情绪波动走 `SeedableRandom` 子流（可选，默认零噪声以保确定性）。

### 3.3 休市门控（CF-4）
- 商品市场与期货市场共享 A 股日历：`calendar.isMarketOpen()==false` 时 `buy/sell` 与期货开/平仓**拒绝**（提示「休市中」）。与 `submitOrder` 的 A 股门控一致（`MarketEngine.java:1324`）。
- **验收**：休市期商品买卖/期货开平仓被拒；开市期全天连续可交易。

### 3.4 流体交易（CF-4）
- `CommodityMarket` 新增：
  ```java
  // 买入：从流体 entry 买 qty 桶 → 记入玩家流体存储（FluidStorageLedger），现金扣减
  boolean buyFluid(ServerPlayer player, Fluid fluid, int qtyBucket);
  // 卖出：从玩家流体存储扣 qty 桶 → 现金入账（可反向转为流体桶物品可选，本期记存储账本）
  boolean sellFluid(ServerPlayer player, Fluid fluid, int qtyBucket);
  // 玩家流体存储账本（playerUUID → fluid → 桶数），随 PlayerEconomy 或独立附件持久化
  ```
- **流体存储账本**：新增 `FluidStorageLedger`（playerUUID→fluid→int 桶），`save/load`（`contains` 兜底为空）；买卖只记账本，**不**与玩家背包直接交互（流体难以背包化；账本即「流体仓库」）。可选增强：卖出支持把流体以桶物品交付（本期记存储，桶化交互为后续增强）。
- `hasEnoughItems/removeItems` 沿用物品路径；流体走账本路径。
- **验收**：买水 10 桶 → 现金扣减、流体账本 +10；卖 6 桶 → 现金入账、账本 −6（CF-4）。

### 3.5 持久化（CF-10）
- `CommodityMarket` 新增 `save()/load()`：对每 `CommodityEntry` 存 `item/fluid key + price/basePrice/costAnchor/supply/demand/dailyVolume/macroFactor/prevSettlePrice`。
- `MarketEngine.save()`（`:1509+`）增 `tag.put("commodity", commodityMarket.save())`；`loadInternal()`（`:1692`）用 `if (tag.contains("commodity")) commodityMarket.load(...)` 恢复，旧存档缺字段用 `autoRegister` 重建（`contains` 兜底，读档不失败，CF-10）。

---

## 4. AS-3 期货市场框架 + 合约（t3）

> 目标：新建**独立期货市场**（区别于 A 股股票市场），遵循 CZCE 交易规定框架，交易日历复用 A 股 `TradingCalendar`。

### 4.1 改动文件（新增 `engine/futures/` 包）
- 新增 `engine/futures/FuturesMarket.java`（交易所单例，由 `MarketEngine` 持有）
- 新增 `engine/futures/FuturesContract.java`（合约元数据：代码/品种/交割月/乘数/涨跌停/保证金/状态）
- 新增 `engine/futures/FuturesPosition.java`（持仓：多/空、手数、开仓均价、浮盈）
- 新增 `engine/futures/FuturesAccount.java`（玩家期货保证金账户：保证金/权益/持仓）
- 新增 `engine/futures/FuturesCalendar.java`（合约列表/交割月/最后交易日的确定性派生）
- 新增 `engine/futures/FuturesSettlement.java`（逐日无负债结算、结算价计算）
- 新增 `engine/futures/FuturesDelivery.java`（实物/现金交割）
- 新增 `engine/futures/FuturesRiskControl.java`（保证金/追保/强平/持仓限额/涨跌停扩大）
- 改 `engine/MarketEngine.java`（构造持有 `futuresMarket`、`tick` 接入结算/交割/强平、`save/load`、`getFuturesMarket()`）

### 4.2 期货标的（Underlying）
- 期货写在**可交付、可标准化**的商品/流体上。默认：`CommodityRegistry` 注册的**物品**中按 `dailyVolume` 排序取 Top-K（默认 `FUTURES_UNDERLYING_TOP_K=24`）作为期货品种，且须为可堆叠物品（`Item.isStackable`，保证可实物交割）；`config.futures.underlyings` 可显式指定覆盖。每个品种给一个**品种代码**（如 `IRON`、`GOLD`、`WHEAT`、`WATER`…），确定性映射自 `item id`（取 `resourcePath` 大写下划线）。
- 期货标的复用商品现货价（`CommodityEntry.price`）作为现货锚（期现联动，§7）。
- 总开关 `futures.enabled`（默认 true）；关闭不注册任何合约（CF-11）。

### 4.3 合约日历（FuturesCalendar，确定性，CF-5）
- **合约代码**：`<品种代码>_<交割月YYMM>`，如 `IRON_2605`。交割月/年由引擎 tick 确定性派生：
  ```java
  // 虚拟「合约月」= 一个固定 tick 跨度；交割月序号单调递增，保证代码唯一且可复现
  public static final long CONTRACT_MONTH_TICKS = 6000L * 30;  // 一「合约月」≈ 30 个交易日（可调）
  long contractYearIndex = engineTick / CONTRACT_MONTH_TICKS;   // 单调年序号
  int  contractMonthCode = (int)(contractYearIndex % 12) + 1;   // 1..12 月号
  ```
- **上市合约**：对每品种上市**近月 + 后续连续月份**，默认 `FUTURES_LISTED_MONTHS=6`（当前交割月 + 后续 5 个合约月）。当前交割月由 `calendar.getPeriod()` 与 `FuturesCalendar` 映射（当前合约月 = 期序号对应月份）。
- **合约月份集合**：CZCE 多为**特定月份**（如白糖/棉花奇数月 1,3,5,7,9,11；PTA/甲醇等连续月）。游戏实现支持两种模式（按品种配置）：`MONTH_MODE=CONTIGUOUS`（连续 1-12 月）或 `MONTH_MODE=ODD`（奇数月）。默认 `CONTIGUOUS`（简单）；`ODD` 作为可选对齐 CZCE 特定月集合。
- **交割月 / 最后交易日**：
  - `deliveryMonthTicks = 该合约交割月的期首 tick`；
  - `lastTradingDayTicks = deliveryMonthTicks + (交割月内最后一个交易日偏移)` —— 由 `TradingCalendar` 纯函数确定（交割月内第 `LAST_TRADING_DAY_OFFSET` 个开市交易日，默认交割月最后一个开市交易日）；
  - 最后交易日结束后进入**交割日**（`FuturesDelivery`），交割完成合约置 `EXPIRED` 退出。
- **确定性**：全部为 `f(tick)` 纯函数，无随机（CF-1/CF-5）。

### 4.4 `FuturesContract` 数据结构
```java
public class FuturesContract {
    String code;              // IRON_2605
    String underlying;        // IRON（对应 CommodityEntry / item）
    long deliveryMonthTicks;  // 交割月期首 tick
    long lastTradingTicks;    // 最后交易日 tick
    int multiplier;           // 每手单位数（默认 10，对齐 CZCE 白糖 10吨/手量纲）
    double prevSettlePrice;   // 昨结算价（锚涨跌停）
    double lastPrice;         // 最新成交价
    double limitUp, limitDown;// 当日涨跌停带（锚昨结算价，§5）
    double marginRatio;       // 当日保证金比例（§5）
    FuturesContractStatus status; // LISTED / DELIVERING / EXPIRED
}
```

---

## 5. AS-4 期货涨跌停板 + 保证金 + 逐日无负债结算（t3，CF-6）

### 5.1 涨跌停板（CZCE，锚昨结算价）
- **锚定**：涨停/跌停锚**昨结算价** `prevSettlePrice`（非 prevClose、非 lastPrice）：
  - `limitUp   = round2(prevSettlePrice × (1 + limitRatio))`
  - `limitDown = round2(prevSettlePrice × (1 − limitRatio))`
- **比例**：默认 `FUTURES_LIMIT_RATIO = 0.04`（±4%，CZCE 多数商品一般月份典型值，如白糖/棉花/菜粕）；品种可配置（如 6%）。
- **连续涨跌停扩大**（CZCE 风险控制）：某合约连续 `N` 个交易日（默认 2）涨停或跌停 → 第 `N+1` 日 `limitRatio` 扩大（默认 ×1.5，且不超上限 `MAX_LIMIT_RATIO=0.10`），同时 `marginRatio` 相应提高（§5.2）。确定性计数器。
- **成交价钳制**：期货撮合成交价钳在 `[limitDown, limitUp]`；越界报价拒绝（端点可挂单排队，语义同 A 股涨停排队）。期货为**T+0 双向**（当日开仓可当日平仓，不做 T+1）。
- 复用 A 股 `round2`/钳制语义（`PriceDiscoveryEngine.aShareClamp` 或独立 `FuturesRiskControl.clamp`），**不修改** A 股常量。

### 5.2 保证金（CZCE，分档 + 逐日）
- **初始保证金**：开仓时冻结 `开仓价 × multiplier × 手数 × marginRatio`，`marginRatio` 默认 `FUTURES_INIT_MARGIN=0.08`（8%，CZCE 一般 5%~10%）。
- **分档提高（临近交割月）**：随交割月临近，`marginRatio` 阶梯提高：
  ```java
  // 距交割月剩余交易月数
  int monthsLeft = deliveryMonthIndex - currentMonthIndex;
  marginRatio = monthsLeft <= 1 ? DELIVERY_MONTH_MARGIN        // 交割月，默认 0.20
              : monthsLeft <= 2 ? NEAR_MONTH_MARGIN            // 次月，默认 0.12
              : baseMarginRatio;                                // 一般月份，默认 0.08
  ```
- **保证金账户**：`FuturesAccount` 持 `collateral`（保证金余额）+ 每合约持仓。开仓冻结、平仓释放、结算盈亏加减。
- **逐日无负债结算（当日无负债结算 / 逐日盯市）**：每交易日边界（`calendar.isTradingDayStart()` 前，即 `isSessionEnd` 落定当日结算价后）执行：
  1. 计算当日**结算价** `settlePrice`：该合约当日成交的**加权平均价**（CZCE 结算价口径；无成交则取 `lastPrice`，再退化 `prevSettlePrice`）。
  2. 对每持仓按 `settlePrice` 重估：`盈亏 = (settlePrice − 开仓/昨结价) × multiplier × 手数`（多头）或反向（空头）；盈亏计入该玩家 `collateral`（逐日盯市），`prevSettlePrice = settlePrice`。
- **验收**：开仓后下一交易日按结算价结算，浮盈/浮亏真实进出保证金；涨跌停带锚昨结算价；交割月前保证金逐级提高（CF-6）。

---

## 6. AS-5 期货强制平仓 + 交割（t3，CF-7）

### 6.1 强制平仓（CZCE）
`FuturesRiskControl.checkForcedClose(engine)` 每交易日结算后调用，命中任一即**确定性强平**：
1. **保证金不足 / 未追保**：结算后 `collateral / (Σ 占用保证金)` 低于**维持线**（`MAINTENANCE_RATIO=1.0`，即保证金余额不足以覆盖占用）且当日未追保 → 按「先亏仓（浮亏最大）→ 再按开仓时间最老」顺序强平，直到权益回升到维持线上。可配置「追保宽限 N 交易日」后强平（默认 1 个交易日）。
2. **持仓限额**：单玩家单合约持仓手数超 `POSITION_LIMIT`（默认 `1000` 手）→ 超限部分强平。
3. **临近交割未平仓**：最后交易日后仍持有且无交割资格（默认所有玩家可交割）→ 进入交割（§6.2）；若品种为现金结算 → 按结算价现金了结。
- **强平执行**：按**当前结算价**平仓（确定性；如无法在结算价撮合则按最新 `lastPrice` 钳涨跌停），释放保证金，`FuturesPosition` 清零。全程无随机。
- **验收**：构造保证金不足 → 结算后触发追保/强平；超持仓限额 → 超限强平；强平序列同种子可复现（CF-7/CF-1）。

### 6.2 交割（CZCE，实物交割，抽象品种可现金兜底）
- **触发**：最后交易日结束后（`tick >= lastTradingTicks`），对该合约所有未平仓持仓执行交割。
- **实物交割（默认）**：`FuturesDelivery` 把多头持仓按 `multiplier × 手数` 交付**实物**（物品 → 玩家库存/流体 → 流体存储账本）；空头从玩家库存/流体账本**扣减**交付（不足则按现货价现金补偿——确定性规则）。交割后多空对冲平仓、保证金结清、合约 `EXPIRED` 退出。
- **现金结算兜底**：`futures.cashSettlement=true` 的品种（或不可实物交付的抽象品种）按**最后结算价**与开仓价差额现金了结（不交付实物）。
- **交割价**：交割日用**该合约结算价**（最后交易日结算价）作为交割结算价（CZCE 交割结算价口径，简化用最后结算价）。
- **验收**：实物交割把多头持仓转成物品/流体、空头交付；现金结算按差额了结；交割后合约退出交易（CF-7）。

---

## 7. AS-6 期现联动 + 经济耦合（t3，CF-8）

- **期货价驱动**：合约 `lastPrice` 由 **现货价 + 持有成本 + 预期 + 宏观** 确定性驱动，随交割临近收敛于现货：
  ```java
  // 每 tick：futuresPrice = spot × (1 + carry) + convergeToSpot 项
  double spot = commodityEntry.price;                       // 现货锚（§2）
  double monthsToDelivery = (deliveryMonthTicks - tick)/CONTRACT_MONTH_TICKS;
  double carry = monthsToDelivery * (policyRate + storageCost); // 持有成本（利率 + 存储费率）
  double target = spot * (1 + carry);
  lastPrice = lastPrice + CARRY_CONVERGE_RATE * (target - lastPrice); // 指数收敛
  lastPrice = clamp(lastPrice, limitDown, limitUp);         // 锚昨结算价的涨跌停带
  ```
  - `CARRY_CONVERGE_RATE`（默认 0.05）保证同种子收敛；`monthsToDelivery→0` 时 `carry→0`、`target→spot`（**期现收敛**）。
  - `policyRate` 读 `centralBank.getDiscountRate()`（法币利率传导）；`storageCost` 常量（默认 0.001/月）。
- **宏观耦合**：`macroFactor`（§2.5）经现货传导到期货；通胀/M2 上升 → 现货价上行 → 期货价上行。期货不引入独立随机，全部 `f(tick)`。
- **与商品市场协同**：期货开/平仓、交割**不**直接改变现货 `demand/supply`（期货是衍生品，现货供需仍由实物买卖驱动）；但可配置「期货套保信号」轻微影响现货需求（默认关闭，防耦合震荡）。
- **验收**：距交割越近，期货价越接近现货；利率上升 → 远月升水（持有成本体现）；通胀/M2 → 现货+期货同向上行（CF-8）。

---

## 8. AS-7 前端 / API 协同（t4，CF-9）

### 8.1 游戏内网络包（`network/packets/`）
- **沿用**：`CommodityPriceSyncPacket` / `RequestCommodityPricePacket`（物品价）。扩展 `CommodityPriceSyncPacket` 字段（供需/成本锚/趋势/流体，`contains`/默认值向后兼容）。
- **新增（服务端→客户端）**：
  - `FuturesListPacket`（合约列表：code/underlying/lastPrice/prevSettlePrice/change/limitUpDown/margin/status）
  - `FuturesPositionSyncPacket`（玩家各合约多空持仓/开仓均价/浮盈/占用保证金/可用保证金）
  - `CommodityTradeResultPacket`（商品买卖结果 + 中文消息，复用现有结果反馈风格）
- **新增（客户端→服务端）**：`FuturesOrderPacket`（开/平仓：code、direction long/short、quantity 手数、open/close）、`RequestFuturesDataPacket`。
- 全部主线程 `enqueueWork` 内执行；服务端走 `FuturesMarket` 确定性方法。

### 8.2 `LocalHttpServer` API（CF-9）
- **只读新增**：
  - `GET /api/commodities` → `[{itemId,name,price,basePrice,costAnchor,supply,demand,change,trend,kind:"item"|"fluid"}]`
  - `GET /api/futures` → `[{code,underlying,name,lastPrice,prevSettlePrice,change,limitUp,limitDown,marginRatio,multiplier,lastTradingTick,status}]`
  - `GET /api/futures/positions` → 绑定玩家 `[{code,side:"long"|"short",lots,openPrice,lastPrice,floatPnl,usedMargin,availableMargin}]`
- **写入新增**（POST `application/json`，绑定玩家经 §5.3 `/api/session` 机制）：
  - `POST /api/commodity/order` body `{itemId,fluid?,side:"buy"|"sell",quantity}` → `{success,message}`
  - `POST /api/futures/order` body `{code,side:"buy"(开多)/"sell"(开空),action:"open"|"close",lots}` → `{success,message}`
- 所有写入最终调用 `CommodityMarket`/`FuturesMarket` 确定性方法，与游戏内行为一致（休市拒绝、涨跌停、保证金、强平权威在后端）。

### 8.3 前端 `FinancialTabletScreen`（t4，协同增强，非硬性验收）
- **商品页增强**：显示现货价/基价/成本锚/供需/趋势；物品与流体买卖输入（数量 + 买/卖），休市提示。
- **新增期货页**：合约列表（代码/现价/涨跌/昨结算/涨跌停/保证金/交割月）、持仓（多空/浮盈/保证金/强平警示）、开平仓表单（合约/方向/手数）。
- 展示层零随机、零引擎确定性改动；语义权威在后端（对齐 `FRONTEND_THS_SPEC.md` §1.3）。

---

## 9. 参数包与调参约束

集中为 `CommodityRegistry`/`CommodityEntry`/`FuturesMarket`/`FuturesRiskControl` 内常量（推荐独立 `engine/futures/FuturesParams.java` + `CommodityParams`）。默认值与硬约束：

```
// ===== 商品市场（t2） =====
commodity.enabled                = true
commodity.syncIntervalTicks      = 200
commodity.registryExclude        = command_block|barrier|structure|light
commodity.priceDriftToCost       = 0.20      // 供需缺口对成本锚的浮动系数
commodity.priceFloorRatio        = 0.2       // 价格下界 = costAnchor×0.2
commodity.priceCapRatio          = 5.0       // 价格上界 = costAnchor×5.0
commodity.macroInflationWeight   = 0.5       // 宏观因子中通胀权重
commodity.macroM2Weight          = 0.5       // 宏观因子中 M2 增长权重
commodity.cycleDemandWeight      = 0.1       // 经济周期对需求增益
commodity.maxRecipeDepth         = 4         // 配方成本递归深度（防环）
// ===== 期货市场（t3） =====
futures.enabled                  = true
futures.underlyingTopK           = 24        // 可交付可堆叠物品 Top-K 作期货品种
futures.contractMonthTicks       = 6000 * 30 // 一「合约月」tick 跨度（可调）
futures.listedMonths             = 6         // 每品种上市近月+后续月份数
futures.monthMode                = CONTIGUOUS // CONTIGUOUS|ODD（CZCE 特定月集合）
futures.multiplier               = 10        // 每手单位数
futures.limitRatio               = 0.04      // ±4%（CZCE 一般商品典型）
futures.limitExpandDays          = 2         // 连续涨跌停 N 日后扩大
futures.limitExpandFactor        = 1.5       // 扩大倍数
futures.maxLimitRatio            = 0.10      // 涨跌停上限
futures.baseMarginRatio          = 0.08      // 一般月份保证金
futures.nearMonthMargin          = 0.12      // 次月保证金
futures.deliveryMonthMargin      = 0.20      // 交割月保证金
futures.maintenanceRatio         = 1.0       // 保证金权益/占用 维持线
futures.topUpGraceTradingDays    = 1         // 追保宽限交易日数
futures.positionLimitLots        = 1000      // 单玩家单合约持仓手数上限
futures.cashSettlement           = false     // true=现金结算兜底；false=实物交割
futures.carryConvergeRate        = 0.05      // 期现收敛速率
futures.storageCostPerMonth      = 0.001     // 持有成本存储费率
```

- **隔离**：`commodity.enabled=false` → 不注册/不 tick/不持久化商品；`futures.enabled=false` → 不注册合约。两者全关与既有基线逐位一致（CF-11）。
- **不引入**：套期保值/期权/跨期套利自动策略、涨跌停集合竞价外的复杂撮合、真实世界节假日（沿用 A 股 7 期日历）。

---

## 10. 兼容性（继承 P0/P1/P2/A 股/公司，商品期货增量）

### 10.1 网络包 / UI / Web
- **不删除/不重构**既有 packet 结构。`CommodityPriceSyncPacket` 只**新增字段**（默认值，向后兼容）；新增期货/商品结果 packet 为新包（`ModMessages` 注册）。
- `LocalHttpServer` 既有 `/api/*` 只读 API 不动，只**新增** `/api/commodities`、`/api/futures`、`/api/futures/positions`、`POST /api/commodity/order`、`POST /api/futures/order`。
- 前端为增量 UI（商品页增强 + 期货页），服务端逻辑先行就绪，前端改动不作为 CF-3~CF-8 硬性验收项。

### 10.2 H2 / NBT 持久化（对齐 H2_MIGRATION.md）
- 权威在引擎 blob（`MarketEngine.save()/loadInternal()`），随 `engine_kv.engine_main` + NBT 附件镜像双写。
- 新增 `tag.put("commodity", commodityMarket.save())` + `tag.put("futures", futuresMarket.save())`；`loadInternal` 用 `contains` 兜底（缺失重建/空，读档不失败，CF-10）。
- 商品状态：每 entry 的 `price/basePrice/costAnchor/supply/demand/dailyVolume/macroFactor/prevSettlePrice`。期货状态：`FuturesContract`（代码/交割月/结算价/涨跌停/保证金/状态）+ 玩家 `FuturesAccount`（collateral/各合约多空持仓/开仓均价/占用/可用）+ 未平仓量 + 涨跌停连续计数器。
- **流体存储账本**：随 `PlayerEconomy` 附件或独立键持久化，`contains` 兜底为空。
- 旧存档缺 `commodity/futures` 键：`commodityMarket` 经 `autoRegister` 重建、`futuresMarket` 空表，读档不失败。

### 10.3 可复现（商品 × 期货 × SeedableRandom）
- 商品定价、期货结算/交割/强平全部确定性 `f(tick)`，无随机；任何情绪/初始波动经 `engineRng` 子流（可播种，同种子可复现）。
- **验证**：t5 固定 `engineSeed` 两次运行，商品价序列 + 期货结算/成交/强平序列哈希一致（CF-1）。

### 10.4 与已交付引擎协同（不破坏）
- 不动 A 股 `submitOrder`/`OrderBook`/`SettlementSystem`/`TradingCalendar`/`PriceDiscoveryEngine` GBM（文首硬约束）。
- 期货/商品只**读取** `InflationSystem`/`MoneyAggregate`/`MacroEnvironment`/`CentralBank`（宏观驱动），不改其公式。
- `AssetScanner`（`COMPANY_SPEC`）已读 `commodityMarket`（`AssetScanner.java:31`）；商品改造后其估值读到的价格改为经济学价，**接口不变**（`getCommodity/getAllCommodities` 保留），公司系统不回归。
- `FinancialWarSystem`/`MarketSentiment` 已消费 `commodityMarket`（`MarketSentiment.java:88`）；改造后商品因子基于真实注册+经济学价，**接口不变**，仅数值更合理。

---

## 11. 对 t5（整体验证 + 构建验证）的需求

1. **构建验证**：`./gradlew compileJava` 通过（CF-0）；`./gradlew build` 出包。
2. **编译/随机审计**：`grep -rn "Math.random\|new Random\|ThreadLocalRandom" src/main/java` 不得出现商品/期货增量引入的调用（允许既有历史；商品/期货增量必须零新随机，CF-1）。
3. **动态注册验证（CF-2）**：引擎 init 后 `commodityMarket.getAllCommodities()` 非空，覆盖物品（含模组物品）+ 流体；无 AIR/禁用项。
4. **经济学定价验证（CF-3）**：确定性逻辑链——同种子下物品基价唯一；铁锭>铁矿>石头、钻石>铁锭；工具≈Σ原料+工艺溢价；供需缺口/成本锚/宏观因子正确驱动价格、价格有界。
5. **物品+流体交易 + 休市门控（CF-4）**：买/卖物品走库存、买/卖流体走存储账本；休市拒绝、开市连续可交易。
6. **期货合约（CF-5）**：合约代码唯一、交割月确定性、上市月份集合正确；开/平仓、持仓、保证金账户、未平仓量齐备。
7. **涨跌停/保证金/逐日结算（CF-6）**：涨跌停锚昨结算价 ±4%；连续涨跌停扩大；保证金分档（一般/次月/交割月）；逐日按结算价盯市盈亏进保证金。
8. **强平/交割（CF-7）**：保证金不足→追保/强平；超持仓限额→强平；最后交易日后→实物/现金交割、合约退出；强平/交割序列确定性。
9. **期现联动/宏观（CF-8）**：近月收敛于现货；利率→远月升水；通胀/M2→现货+期货上行。
10. **可复现脚本（CF-1）**：固定 `engineSeed` 一键重跑 N tick（跨交割月/交易日边界），商品价 + 期货序列两次哈希一致。
11. **持久化（CF-10）**：跑一段流程后 save→load，商品价/期货持仓/保证金/结算价/交割状态完整恢复；旧档缺字段读档不失败。
12. **隔离（CF-11）**：`commodity.enabled=false`/`futures.enabled=false` 回落基线，与现有行为对比证明贡献。

---

## 12. 实施顺序与依赖（供 scheduler）

```
t1 (本规格) ──→ t2 (商品市场完善: CommodityRegistry 落地 + 成本基价 + 经济学定价 + 流体 + tick 接线 + 持久化)
        └──→ t3 (期货市场: FuturesMarket/Calendar/Settlement/Delivery/RiskControl + MarketEngine 接入 + 持久化)
                └──→ t4 (前端/API: 网络包 + LocalHttpServer /api/commodities,/api/futures + 前端商品/期货页) ──→ t5 (验证) ──→ t6 (评审)
```
- **依赖**：t2 依赖 t1（商品基座：注册/定价/交易/持久化）；t3 依赖 t1 与 t2（期货锚现货 `CommodityEntry`、复用日历、`commodity.enabled` 隔离）；t4 依赖 t1 与 t3（期货 API/前端）、商品部分依赖 t2。t5 依赖 t2/t3/t4；t6 依赖 t5。
- **优先级**：t2（商品市场基座，CF-2~CF-4 + 持久化）先行；t3（期货 CZCE 规则，CF-5~CF-8）核心；t4（前端/API，CF-9）收口。
- **注**：商品 + 期货新增随机为零（全部确定性）；若实现需要任何随机，一律走既有 `SeedableRandom` 派生子流并登记，否则违反 CF-1。

---

## 13. 与非目标边界（防范围蔓延）

- **不动** `PriceDiscoveryEngine.step()` GBM/GARCH、A 股交易规则常量、`TradingCalendar`（时间模型）内部公式、`submitOrder`/`OrderBook`/`SettlementSystem` 股票语义、法币 M2/央行/税收公式。
- **不做**：期权、套期保值/跨期套利自动策略、真实节假日（沿用 A 股 7 期日历）、集合竞价外复杂撮合、期货 T+N 交收（期货 T+0 双向）。
- **期货与股票融资融券分离**：`MarginManager` 股票融资融券不动；期货保证金/强平走独立 `FuturesMarket`，不混入股票路径。
- **流体**：默认存**流体存储账本**（不强制桶化/背包化）；「把流体以桶物品交付/取用」为后续增强。
- **商品直接持仓/库存的全局供给统计**（玩家矿量/产量全局登记）本期不做，供给由买卖压力 + 确定性环境基线驱动（保证 O(商品) 常数级）。
- 全部分支为 O(商品)/O(合约)/O(持仓) 常数级；逐日结算/强平/交割每交易日一次遍历（非 per-tick 线性扫）。

---

## 14. 输入/来源索引

- 团队 Goal：商品市场（动态注册 + 经济学定价）+ 期货市场（CZCE 规则），协同已交付引擎/前端，确定性可复现、H2 兼容。
- 已交付基座：`A_SHARE_TRADING_SPEC.md`（TradingCalendar 时间模型）、`ECON_P0/P1/P2_SPEC.md`（SeedableRandom/GBM/P2）、`ECON_FIAT_M2_SPEC.md`（法币/M2/通胀）、`COMPANY_SPEC.md`（AssetScanner 读 commodityMarket）、`FRONTEND_THS_SPEC.md`（前端 + LocalHttpServer）、`H2_MIGRATION.md`（H2/NBT）。
- 代码证据：`engine/CommodityMarket.java`、`engine/CommodityRegistry.java`、`engine/CommodityEntry.java`、`engine/RecipeAnalyzer.java`、`engine/calendar/TradingCalendar.java`、`engine/MarketEngine.java`（`:60,:278,:771,:845,:1509,:1692` 等）、`engine/margin/MarginManager.java`（模板）、`engine/macro/MacroEnvironment.java`、`engine/banking/InflationSystem.java`、`network/packets/CommodityBuyPacket.java`/`CommoditySellPacket.java`/`CommodityPriceSyncPacket.java`/`RequestCommodityPricePacket.java`、`web/LocalHttpServer.java`（`:77-121`）、`screen/FinancialTabletScreen.java`。
- 现实 CZCE 规则依据（本规格引用的公开交易规则）：[郑州商品交易所期货交易风险控制管理办法](https://www.czce.com.cn/cn/rootfiles/2011/10/24/1317295047031948-1317295047058239.pdf)、[郑商所 PTA 期货业务规则解读](https://www.shinnytech.com/articles/business-rules/products/czce.ta)、[郑商所动力煤 ZC 期货业务规则解读](https://www.shinnytech.com/articles/business-rules/products/czce.zc)、[白糖期货合约 SR](https://www.bocifco.com/newsinfo.aspx?cid=4&id=42077)。核心规则点：合约月份特定集合/连续、涨跌停板 ±4%（连续涨跌停扩大）、保证金一般 5%~10%（临近交割月分档提高）、逐日无负债结算（当日成交加权平均价作结算价）、实物交割（交割月，最后交易日/交割日）、强制平仓（保证金不足/超持仓限额/临近交割）。
