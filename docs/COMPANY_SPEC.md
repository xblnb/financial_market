# COMPANY_SPEC — 玩家公司系统（创建/升级/经营、IPO、股权、财报分红、退市摘牌）落地实施规格（需求契约，供 t2/t3/t4 实施、t5 验证、t6 评审）

> - 角色：architect（方案架构 / 公司系统需求规格）为任务 t1 产出。
> - 输入：团队 Goal（公司系统需求规格）+ 当前源码审计（本规格对照实际源码给出文件/行号/方法签名/默认参数包）。
> - 已交付前置（本规格的落地基座）：`P2_SPEC.md`（订单簿/ABM 微观结构）、`A_SHARE_TRADING_SPEC.md`（AS-1~AS-8 A 股交易规则）、`ECON_FIAT_M2_SPEC.md`（法币/M2/央行/税收）、`H2_MIGRATION.md`（H2/NBT 双写持久化）、前端 `FinancialTabletScreen` 公司页。公司系统**继承并依赖**这些已定稿语义，只做增量接入。
> - 用途：engine-dev 按本规格实现 t2（生命周期 + AssetScanner + 命令补全/合并）、t3（IPO 上市 + 股权管理）、t4（财报/分红 + 退市摘牌 DelistingMonitor）；test-engineer 按本规格实现 t5（整体验证 + 构建/可复现）；reviewer 按本规格验收 t6。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、确定性可复现、H2/NBT 持久化兼容。
> - **可复现性硬约束（继承 P0/A0-3/P2）**：同一种子同行为必须保持。所有新增随机一律使用 `SeedableRandom`（`engine/random/SeedableRandom.java`，SplitMix64），**禁用 `ThreadLocalRandom` / 裸 `new Random()` / `Math.random()`**。
> - **兼容边界硬约束（不动已定稿语义）**：
>   1. **不修改** `AICompanyData`（`engine/ai/AICompanyData.java`）的 `updateFairValue()`/`restoreFundamentals()` 公式与 IPO 量纲（P0 已定稿）。玩家公司 IPO 定价**复用同一套量纲语义**，但不改写 `AICompanyData`。
>   2. **不修改** `PriceDiscoveryEngine.step()` GBM/GARCH 公式、`A_SHARE_*` 交易规则常量（`PriceDiscoveryEngine.java:33-50`：`A_SHARE_LIMIT_RATIO=0.10`、`A_SHARE_TICK_SIZE=0.01`、`A_SHARE_BOARD_LOT=100`）。
>   3. **不修改** `SettlementSystem`（`engine/SettlementSystem.java`）T+1 账本语义（frozen/available）；仅在**已存在的成交记账入口**（`MarketEngine.attributeTrade`）叠加公司股权同步钩子。
>   4. **不修改** `MarketEngine.submitOrder` 的 A 股校验流程（一手/T+1/涨跌停/休市）；公司股票上市后**复用该流程**成为普通可交易资产。

---

## 0. 目标与全局验收（对应团队 Goal）

> 让 Financial Market 模组的玩家公司成为**完整可玩的经济主体**：玩家可创建/升级/经营公司（净资产由容器资产扫描），可走完整 IPO 上市流程（定价/发行/股东认购），上市后股票进入现有 A 股交易引擎成为可交易资产，具备股东与股权（转让/持股/股权结构）、财报与分红、退市/摘牌机制；AssetScanner/DelistingMonitor/ListingValidator 从占位符落地接线；两个同名 `CompanyCommand` 合并为单一命令集。与已交付的经济引擎 / A 股交易规则 / 法币 M2 / 前端交易界面协同，保持确定性可复现、H2/NBT 持久化兼容。完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| A-0 | 编译通过 | `./gradlew compileJava` 退出码 0；`./gradlew build` 出包 | 每项实施 + t5 |
| A-1 | 可复现性不回归（继承 P0/A0-3/P2） | 同 `engineSeed` 跑固定 tick，两次成交价序列逐元素一致；新增随机全部走 `SeedableRandom`（全仓 `grep` 无 P2 之后新增的 `Math.random`/`new Random`/`ThreadLocalRandom` 调用） | t2~t4 + t5 |
| A-2 | 生命周期落地（创建/升级/经营） | `AssetScanner` 从 `boundContainers` 估值写入 `CompanyData.netAssetValue`；等级升级有确定性门槛；创建/升级命令与前端「创建/升级」按钮可用 | **t2** + t5 |
| A-3 | 完整 IPO 上市流程 | `ListingValidator` 校验 → 确定性定价 → 发行窗口 → 股东认购 → 资产注册进交易引擎 → 状态 `LISTED` 且可交易；新股首日 `prevClose` 锚 `ipoPrice`（A 股涨跌停首日规则正确） | **t3** + t5 |
| A-4 | 股东与股权 | `CompanyData.shareholders` 为股权权威账本，IPO 认购/二级市场成交/股权转让/退市回购均**保持账本与 PlayerEconomy/Settlement 持仓一致**；股权结构可查询 | **t3** + t5 |
| A-5 | 财报与分红 | 按周期生成财报（净资产/营收/净利/每股收益变化）；分红从留存收益向股东按持股比例派发现金（计入 PlayerEconomy 现金，确定性） | **t4** + t5 |
| A-6 | 退市/摘牌机制 | `DelistingMonitor` 按确定性阈值检测触发退市 → 回购/现金清偿 → 状态 `DELISTED` → 资产退出交易引擎；不污染残留账本 | **t4** + t5 |
| A-7 | H2/NBT 持久化不回归 | 新增 `CompanyData` 字段随引擎 blob（`engine_kv.engine_main` + NBT 附件镜像）序列化/恢复；H2 `company` 镜像表新增列用 `ADD COLUMN IF NOT EXISTS` 前向兼容；旧存档缺字段用 `contains` 兜底重建，读档不失败 | t2/t3/t4 + t5 |
| A-8 | 命令补全 + 同名合并 | 删除 `company/CompanyCommand.java`（重复类），保留/扩展 `command/CompanyCommand.java`；命令集覆盖 create/level/ipo/info/shareholder/transfer/dividend/delist/bind-area/scan/report | **t2** + t5 |

**验收流程（t5 落地）**：固定 `engineSeed` 跑固定 tick；走一遍「创建 → 扫描 → 升级 → IPO 定价/发行/认购 → 二级市场成交 → 股权转让 → 财报 → 分红 → 退市」全生命周期冒烟；对每次状态迁移断言确定性字段；两次同种子运行校验成交价序列哈希一致（A-1）；验证 H2 读档后公司数据完整恢复（A-7）。

---

## 1. 总设计原则（约束所有实现）

1. **纯增量、可开关**：新增逻辑集中在 `company/` 包（`CompanyData`/`CompanyManager`/`AssetScanner`/`ListingValidator`/`DelistingMonitor`）+ `command/CompanyCommand` + `network/packets/CompanyActionPacket` + `MarketEngine` 的少数接入点（`registerAICompany` 的玩家公司变体、`attributeTrade` 的股权同步钩子、季度财报/分红接入点）。每个子项有独立 `enabled` flag；全关时行为与现有基线一致（因子隔离）。
2. **股权账本单一权威（A-4）**：`CompanyData.shareholders`（`Map<UUID,Integer>`，已存在）是公司股权唯一权威来源，用于股权结构查询、分红、转让、退市回购。二级市场成交通过**既有的 `attributeTrade` 入口**同步股东账本（买者 +qty / 卖者 −qty，仅真实玩家 UUID；合成流/ZI 的 `null`/代理 UUID 不写入股东账本）。所有改变持股的操作必须在同一 server 主线程 tick 内原子完成（无跨 tick 中间态）。
3. **确定性优先**：IPO 定价、升级门槛、财报、分红比例、退市阈值、回购价**全部为确定性函数**（输入 = 当前状态字段 + 引擎常量），无随机或仅用 `SeedableRandom` 子流。默认不消耗随机，保证 A-1。
4. **复用 A 股交易引擎**：玩家公司上市后走 `registerAICompany` 同源路径注册资产（`MarketEngine.java:299-316`），成为普通可交易资产；二级市场一切 A 股规则（一手 100、T+1、±10% 涨跌停、休市）自动生效，**不重复造轮子**。
5. **不动已定稿语义**：见文首硬约束清单。凡涉及 `AICompanyData.fairValue`、`PriceDiscoveryEngine.step`、`SettlementSystem` T+1、`MarketEngine.submitOrder` A 股校验的改动一律拒绝。

---

## 2. 数据模型：CompanyData / CompanyManager 扩展（t2 基座）

> 现状：`company/CompanyData.java`（122 行）已有字段 owner/name/stockId/level/status/netAssetValue/recentProfit/totalShares/ipoPrice/boundContainers/shareholders/foundedTime/listedTime，含 `write()/read()` NBT 序列化；`company/CompanyManager.java`（63 行）已有 createCompany/getByOwner/getByStockId/getAllCompanies/registerStockId/removeCompany/save/load。引擎 blob 已在 `MarketEngine.save()/loadInternal()`（`:1000-1001/:1169`）与 H2 `company` 镜像表（`:1545-1567`）持久化。

### 2.1 改动文件
- `company/CompanyData.java`（新增字段 + 序列化）
- `company/CompanyManager.java`（新增查询/派生方法）
- `engine/MarketEngine.java`（季度财报/分红接入点、公司相关 getter，见 §6）

### 2.2 `CompanyData` 新增字段（全部含 getter/setter，NBT 序列化含 `contains` 兜底）

```java
private double retainedEarnings;    // 可分配留存收益（分红资金池），AssetScanner 利润累积 + 分红扣减
private long   lastReportTime;      // 上次财报周期时间（tick 计数），0 = 尚未生成
private long   lastDividendTime;    // 上次分红时间（tick 计数），0 = 未分过红
private int    floatShares;         // 流通股数（IPO 发行量）；上市前 = totalShares（全为创始人持有）
private CompanyIpoState ipoState;   // 见 §4.1（NONE/PRICED/SUBSCRIBING/LISTED/FAILED）
private double subscriptionRaised;  // 本次认购已募集现金（确定性记账，供对账）
```

- 新枚举 `CompanyIpoState`（`company/CompanyIpoState.java`）：`NONE, PRICED, SUBSCRIBING, LISTED, FAILED`。语义：`NONE` 未启动 IPO；`PRICED` 已定价待发行；`SUBSCRIBING` 发行认购窗口开放；`LISTED` 已上市；`FAILED` 认购不足/发行中止（可回到 `NONE` 重新发起）。
- **序列化**：在 `write()` 增加 `retainedEarnings/lastReportTime/lastDividendTime/floatShares/ipoState/subscriptionRaised`；`read()` 中所有新增字段用 `tag.contains(key)` 兜底（缺失时用默认：`retainedEarnings=0`、`floatShares=totalShares`、`ipoState=NONE`、时间=0、`subscriptionRaised=0`），保证旧存档读档不失败（A-7）。`ipoState` 用 `name()`/`valueOf`，兜底 `NONE`。
- **H2 镜像表**（`H2DatabaseManager`）：`company` 表按 `ai_company` 的先例（`:230-246`）用 `ALTER TABLE company ADD COLUMN IF NOT EXISTS retained_earnings DOUBLE / float_shares BIGINT / ipo_state VARCHAR(32) / last_report_time BIGINT / last_dividend_time BIGINT / subscription_raised DOUBLE`。`MarketEngine.saveRelationsToH2`（`:1545-1567`）相应补写新列；`loadFromH2` 仍以 blob（`engine_kv`）为权威恢复全部字段（镜像表仅供查询/迁移，不作为加载源）。新增 H2 表 `company_shareholder(company_stock_id, holder_uuid, shares)` 作为股东镜像（可选，用于 SQL 查询；blob 仍是权威）。

### 2.3 `CompanyManager` 新增派生方法

```java
public boolean hasCompany(UUID owner);                          // getByOwner != null
public CompanyData getByName(String name);                      // 全表线性匹配（O(n)，公司量小可接受；确定性）
public List<CompanyData> getByStatus(CompanyStatus status);     // 供 DelistingMonitor 遍历 LISTED 公司
public boolean isStockIdTaken(String stockId);                  // companiesByStockId 判重
public String nextStockId();                                    // 确定性生成唯一股票代码（见 §4.2）
```

- **`nextStockId()` 生成规则（确定性、无随机）**：`"PC" + 按创建顺序自增序号`（如 `PC0001`、`PC0002`…）。自增序号来自 `companiesByOwner.size()+1` 再探测去重（若碰撞则递增），**不依赖 `System.currentTimeMillis`/随机**。现有 ad-hoc 的 `"PLC_" + 玩家名` 生成（`CompanyActionPacket.java:57`）废弃，改走 `nextStockId()`。

### 2.4 验收（t2 自身）
- `CompanyData` 新增字段可正常 NBT 读写；缺失 key 的旧存档读档不抛异常（A-7）。
- `CompanyManager.nextStockId()` 同输入同输出、且在全表内唯一；两次创建公司生成不同代码。
- `./gradlew compileJava` 通过（A-0）。

---

## 3. 生命周期：AssetScanner 落地 + 升级 + 经营（t2 核心，G-01/经营）

> 现状：`company/AssetScanner.java`（9 行）为占位符 `scanAll(ServerLevel, CompanyManager)` 空实现；`boundContainers` 已存在于 `CompanyData`；前端「划定区域」发送 `CompanyAreaPacket`（`CompanyAreaPacket.handle` 目前空实现）；前端「升级/降级」走 `CompanyActionPacket("levelup"/"leveldown")`（`CompanyActionPacket.java:44-53`，仅创造模式可用）。

### 3.1 改动文件
- `company/AssetScanner.java`（落地估值逻辑）
- `company/CompanyData.java`（净资产/利润写入入口）
- `network/packets/CompanyAreaPacket.java`（绑定区域落地）
- `engine/MarketEngine.java`（tick 接入 AssetScanner 周期 + 公司经营 tick）

### 3.2 `AssetScanner` 落地

**目标**：把玩家公司绑定的容器资产（`boundContainers`）扫描估值，写入 `CompanyData.netAssetValue` 与 `recentProfit`。

```java
public class AssetScanner {
    // 每 N tick 扫描一次（默认 SCAN_INTERVAL_TICKS = 24000L * 1，即每游戏日一次）
    public static void scanAll(ServerLevel level, CompanyManager manager);
    // 单公司扫描：遍历 boundContainers，统计容器内容并估值
    public static void scanCompany(ServerLevel level, CompanyData company);
}
```

- **估值来源（确定性、无随机）**：对 `boundContainers` 内每个 `BlockEntity`（若为 `BaseContainerBlockEntity`，即箱子/桶/漏斗等 `Inventory` 容器），累加其物品；物品估值复用 `CommodityMarket`/`CommodityRegistry`（`engine/CommodityMarket.java`、`engine/CommodityRegistry.java`）的基准价。无匹配则按物品种类给确定性默认价（常量表，无随机）。
- **净资产写入**：`company.setNetAssetValue(sum)`。净资产 = 容器内物品估值总和（公司拥有的实物资产），模型与「公司是工厂，产出以物资形态存在」一致。
- **近期利润**：`recentProfit = max(0, 本次净资产 − 上次净资产的增量) * profitCaptureRatio`（默认 `0.1`，即实物增值的 10% 视为可分配利润），并累积进 `retainedEarnings`（`retainedEarnings += recentProfit`）。净资产下降时 `recentProfit=0`、`retainedEarnings` 不扣（避免存货回撤吃掉已实现利润，确定性规则）。
- **空容器/未绑定区域**：`boundContainers` 为空或全部无效坐标时，净资产按 0 处理（不抛异常）。
- **接入点**：`MarketEngine.tick` 每 `SCAN_INTERVAL_TICKS`（用 `tickCounter % SCAN_INTERVAL_TICKS == 0`，`tickCounter` 为时间权威，`:966`）调用一次 `AssetScanner.scanAll(level, companyManager)`。放在与基本面更新（`:416-420`）相邻的位置，`try/catch` 防御不阻断 tick。

### 3.3 绑定区域落地（CompanyAreaPacket）

- `CompanyAreaPacket.handle`（`CompanyAreaPacket.java:25-27`，当前空）实现：取服务器玩家公司，把 `corner1/corner2` 长方体**所有 BlockPos** 去重后并入 `boundContainers`（容量受 `CompanyLevel.getMaxContainers()` 限制：`CompanyLevel.java:10-13`，STARTUP=9/SMALL=27/MEDIUM=64/LARGE=125/CORPORATION=200）。超限时报错并拒绝。创建/读档后 `boundContainers` 默认空。
- 补 `CompanyData.addBoundContainer(pos)` / `clearBoundContainers()`（序列化同步）。前端「划定区域」即发送此包（`FinancialTabletScreen.java:853`）。

### 3.4 升级门槛（确定性）

- 当前 `levelup/leveldown` 仅创造模式可用（`CompanyActionPacket.java:45-52`）。**新增确定性升级门槛**：`levelup` 需满足
  - `netAssetValue >= CompanyLevel[目标].minMarketCap`（`CompanyLevel.minMarketCap`：SMALL=5000/MEDIUM=20000/LARGE=100000/CORPORATION=500000，`CompanyLevel.java:13`）；
  - 公司状态为 `PRIVATE` 或 `LISTED`（`DELISTED` 不可升级）。
  - 满足门槛则 `level = fromLevel(level+1)`，否则发送失败提示。
- `leveldown` 保留为管理操作（创造模式即可），**生产/生存模式禁止降级**（防止刷等级）。
- 前端「升级」按钮保持发送 `CompanyActionPacket("levelup","")`，服务端按新门槛判定并回传结果。

### 3.5 验收（t2 自身）
- 在箱子放入可识别物品并绑定区域后，一个扫描周期内 `netAssetValue` 增长、`recentProfit`/`retainedEarnings` 随之更新（A-2）。
- `CompanyLevel` 门槛未达标时 `levelup` 被拒绝；达标后升级成功。
- 绑定区域超过 `maxContainers` 被拒绝。
- 同种子两次运行，`netAssetValue/recentProfit` 逐值一致（A-1）。

---

## 4. 完整 IPO 上市流程（定价/发行/股东认购）（t3 核心，A-3）

> 现状：`CompanyActionPacket` 的 `"list"` action（`CompanyActionPacket.java:54-64`）做 ad-hoc 上市：`setStatus(LISTED)` + 硬编码生成 `stockId` + `new AICompanyData(...0.02,0.05...)` + `registerAICompany`。本规格将其替换为**完整、确定性、分阶段**的 IPO 流程。`ListingValidator.canList`（`company/ListingValidator.java`）当前仅 `netAsset>1000 && level>=2`，需强化为完整上市条件（§4.3）。

### 4.1 IPO 状态机与流程

```
PRIVATE(ipoState=NONE)
   │  player: /fmarket company ipo 或前端「发起上市」
   ▼
PRICED   ← ListingValidator 校验通过 + 确定性定价（ipoPrice 落定）
   │  engine: 发行窗口开启（发行 floatShares 股）
   ▼
SUBSCRIBING（窗口 N 个交易日，玩家可认购）
   │  engine: 窗口结束（认购足额 → 上市；不足 → FAILED）
   ├─────────────┐
   ▼             ▼
LISTED        FAILED（认购不足→ 回退 PRIVATE/ipoState=NONE，已募集现金退还）
```

- **并发/重入**：`ipoState` 在 server 主线程 tick 内由 `MarketEngine` 或 packet handler 单线程推进；同一公司同一时刻只有一个发行窗口。已 `LISTED` 或 `SUBSCRIBING` 中禁止再次发起 IPO。
- **`IPOState` 推进入口**：新增 `MarketEngine.advanceIpo()`（每 tick 或每交易日调用，见 §4.5），确定性遍历 `companyManager.getByStatus(PRIVATE)` 中 `ipoState==SUBSCRIBING` 的公司，结算窗口。

### 4.2 定价（确定性，§1 原则 3）

```java
// 新增 CompanyData.computeIpoPrice()：确定性，无随机
double navPerShare = totalShares > 0 ? netAssetValue / totalShares : 0.0;
double levelPremium  = 0.0 + 0.1 * (level.getLevel() - 1);   // 等级溢价（SMALL 起 +0.1 … CORPORATION +0.4）
double minIpoPrice   = 1.0;                                   // 最低发行价（常量，A 股 tick 0.01 的整数百倍）
double ipoPrice      = round2(max(minIpoPrice, navPerShare * (1 + levelPremium)));
```

- **量纲对齐**：`ipoPrice` 与 `AICompanyData.ipoPrice`（`AICompanyData.java:86`：`ipoPrice = fairValue`）同量纲（法币元/股）。玩家公司无 `AICompanyData` 复杂估值，用净资产 + 等级溢价简化为确定性锚。`round2` 复用 `MarketEngine.round2`（`:256`，四舍五入到 0.01）语义，**保证 IPO 价是 0.01 的整数倍**（满足 A 股 tick 步长）。
- **股票代码**：`stockId = CompanyManager.nextStockId()`（§2.3）。`registerStockId`（`CompanyManager.java:30`）登记。
- **发行数量**：`floatShares = (int) round(totalShares * FLOAT_RATIO)`，`FLOAT_RATIO` 默认 `0.4`（40% 流通，创始人保留 60%）。`floatShares` 须为 `A_SHARE_BOARD_LOT(100)` 的整数倍（向下取整到百股）；若 `floatShares < 100` 则按 `100` 起发（若 `totalShares` 足够）。上市后创始人 `shareholders[owner] = totalShares − floatShares`。

### 4.3 `ListingValidator` 强化（A-3 前置门槛）

```java
public class ListingValidator {
    public static boolean canList(CompanyData company);
    public static String rejectionReason(CompanyData company);   // 返回中文原因，供命令/前端展示
}
```

- **条件**（全部为确定性阈值常量）：
  1. `status == PRIVATE` 且 `ipoState == NONE`（或 `FAILED` 后可重试）；
  2. `level.getLevel() >= 2`（SMALL 起，继承现有 `CompanyActionPacket.java:55` 语义）；
  3. `netAssetValue >= LISTING_MIN_NAV`（默认 `5000`，即 SMALL 门槛）；
  4. `retainedEarnings >= 0`（财务健康）；
  5. `totalShares >= A_SHARE_BOARD_LOT`；
  6. `boundContainers` 至少绑定一个有效区域/容器（经营真实性，可选软门槛，默认开启）。
- 不满足任一 → `canList=false`，`rejectionReason` 返回首个未满足项（确定性）。

### 4.4 发行 / 股东认购（确定性记账，A-3/A-4）

- **发起**：玩家（创始人，`PRIVATE` 状态）发起 IPO → 校验 `ListingValidator` → 定价落定（`ipoPrice`）→ 发行 `floatShares` 股 → `ipoState=SUBSCRIBING`，窗口开放 `IPO_WINDOW_TRADING_DAYS`（默认 `3` 个交易日）。生成 `stockId` 并 `registerStockId`（此时**不注册交易资产**，资产在 `LISTED` 时才注册）。
- **认购**（`CompanyActionPacket` 新增 action `"subscribe"`，param = 认购股数）：
  - 认购者为任意在线玩家（含创始人），校验：`ipoState==SUBSCRIBING`；认购数量为 `A_SHARE_BOARD_LOT` 整数倍且 `>0`；总认购不超 `floatShares`；玩家现金 `>= 认购数 × ipoPrice`。
  - 通过后：`PlayerEconomy.setCash(cash − 认购额)`（**现金即时扣减**，确定性记账）；`company.subscriptionRaised += 认购额`；登记认购意向 `Map<UUID,Integer> subscriptions`（**新增临时字段**，仅 `SUBSCRIBING` 期有效，不持久化跨窗口或随 blob 序列化——窗口结束即结算清空；为可复现可序列化，见 §7.2）。
  - 认购现金**暂存**：上市成功后转化为股东持股并可能经 `subscriptionRaised` 计入公司留存；认购不足 FAILED 时原路退还现金。
- **窗口结算**（`MarketEngine.advanceIpo`，每交易日边界调用）：
  - **足额**（`Σsubscriptions >= floatShares`）：按认购顺序优先满足，超额部分按现金退还；`shareholders` 初始化：创始人持 `totalShares − floatShares`，认购者按其所得累计；将已认购股东持股同步进 `PlayerEconomy.addHolding` + `SettlementSystem.seedAvailable`（视为已解冻可卖，符合 A 股既有持仓迁移语义 `MarketAttachment.java:105-122`）；注册交易资产（`registerPlayerCompany`，§4.5）；`status=LISTED`、`ipoState=LISTED`、`listedTime=tickCounter`；`subscriptionRaised` 转公司留存收益。
  - **不足**（`Σsubscriptions < floatShares`）：`status` 回 `PRIVATE`、`ipoState=FAILED`、`floatShares` 复位、所有认购现金**原路退还**、清空 `subscriptions`、注销 `stockId`（`removeCompany` 语义复用或 `companiesByStockId.remove`）。玩家下个交易日可重新发起（`ipoState` 从 `FAILED` 归 `NONE` 需命令触发）。

### 4.5 资产注册（复用 registerAICompany 同源路径，A-3）

```java
// 新增 MarketEngine.registerPlayerCompany(CompanyData company)：
// 以 ipoPrice 建资产，industry 固定 "PLAYER"，trend/volatility 用 engineRng 子流（可播种），
// 复用 registerAICompany(MarketEngine.java:299-316) 的资产/订单簿/代理初始化逻辑。
```

- **首日涨跌停锚**：新上市资产无 `prevClose`；`registerPlayerCompany` 中显式 `state.getAsset(id).setPrevClose(ipoPrice)`，使 `withinAShareLimit`/`applyPriceLimitBand` 用 `ipoPrice` 作为首日涨跌停锚（`MarketEngine.java:750-772`）。这是本规格对 A 股规则的**唯一必要新增**，不修改规则本身。
- **行业**：玩家公司资产行业标记 `"PLAYER"`（不并入 AI 行业；可被 `Excellent24Index` 忽略或按策略后续扩展——**默认不纳入指数**，非目标边界）。

### 4.6 前端/网络协同（A-3 可玩性）

- `CompanyActionPacket` 扩展 action：`"ipo"`（发起定价发行）、`"subscribe"`（认购，param=股数）。
- `CompanyDataResponsePacket` 已序列化整个 `CompanyData`（`CompanyDataResponsePacket.java:19` 用 `write()`），新增字段自动随 `write()` 传输；前端 `FinancialTabletScreen.updateCompanyData` 已能收到。前端公司页需补充「发起上市 / 认购 / 显示 ipoState/ipoPrice/floatShares」UI（前端为**协同增强**，服务端逻辑必须先行就绪且不依赖前端改动）。

### 4.7 验收（t3 自身）
- `ListingValidator` 条件全部满足才可发起 IPO；任一不满足给出 `rejectionReason`（A-3）。
- 足额认购后：资产可下单交易、首日涨跌停锚 `ipoPrice`、`status==LISTED`、`shareholders` 正确、认购者 `PlayerEconomy` 有持仓且 `Settlement.available` 可卖（A-3/A-4）。
- 不足额：现金原路退还、`stockId` 注销、状态回 `PRIVATE`、可重新发起（A-3）。
- 同种子两次运行，定价/认购结算/股东分配逐值一致（A-1）。

---

## 5. 股东与股权（转让/持股）+ 二级市场协同（t3，A-4）

> 现状：`CompanyData.shareholders`（`Map<UUID,Integer>`）已存在并在 `write()/read()` 序列化（`CompanyData.java:86-93,113-117`）；二级市场成交记账入口为 `MarketEngine.attributeTrade`（`:779-785`）。本规格定义股权账本与交易引擎的一致性规则。

### 5.1 股权账本权威性 + 二级市场同步钩子（A-4）

- **权威**：`shareholders` 是股权结构唯一权威（用于股权结构查询、分红、转让、退市回购）。`PlayerEconomy` 持仓是玩家可交易浮筹的账户视图，两者必须一致。
- **同步钩子**：在 `MarketEngine.attributeTrade(Trade)`（`:779-785`，A 股 T+1 记账处）叠加股权同步：若 `trade.getAssetId()` 是某上市玩家公司的 `stockId`（`companyManager.getByStockId(assetId) != null`），且 `buyer/seller` 为真实玩家 UUID（非 null）：
  - 买者股东 `shareholders[buyer] += quantity`；
  - 卖者股东 `shareholders[seller] = max(0, shareholders[seller] − quantity)`，为 0 时移除。
  - **合成流/ZI**（`attributeTrade` 已跳过 null，`MarketEngine.java:776-784`）天然不进入股东账本——AI/做市商等非玩家浮筹仅作流动性，不占股东席位（**确定性建模决策**，见非目标边界）。
- **一致性校验**：`Σ shareholders.values() + floatShares*(AI浮筹不计)` 在纯玩家交易下恒等于 `totalShares`（创始人 60% + 流通 40% 全在玩家手中时为 `totalShares`）。分红/转让/回购均从该账本读写。

### 5.2 股权转让（大宗转让，A-4）

- 新增命令/packet action：`"transfer"`，param 形如 `<接收方UUID> <股数>`。
- 规则：转让方持有 `>= 股数` 且为 `A_SHARE_BOARD_LOT` 整数倍（若公司 `LISTED`，转让走二级市场更自然，但大宗转让作为**场外直接划转**保留）；`PRIVATE` 公司（未上市）创始人可向任意玩家场外转让（这是未上市公司唯一的股权变动途径）。
- 执行（确定性）：`shareholders[from] −= qty`、`shareholders[to] += qty`；同步 `PlayerEconomy`：`from.removeHolding(stockId,qty)`（若 `LISTED`）、`to.addHolding(stockId,qty,当前市价或账面价)`；`LISTED` 时同步 `Settlement`（`from.onSellFill`/`to.seedAvailable`，视为已解冻）。现金不参与（场外转让默认按约定，无需定价，或可加可选 `price` 参数）。
- **创始人变更**：转让不自动改 `owner`（创始人身份用于管理权限）；如需变更由命令 `transfer-ownership`（可选，非本期必须）。

### 5.3 股权结构查询（A-4 可查询）

- 命令/packet：`"shareholders"` 返回按持股降序的股东列表（UUID→股数，实时 `shareholders` 快照）。用于公司页「股权」展示与验证 A-4。
- 新增 `CompanyManager.getShareholdersOf(company)` 返回排序列表（确定性排序：股数降序，同股数按 UUID 字典序）。

### 5.4 验收（t3 自身）
- 二级市场买卖玩家公司股票后，`shareholders` 与 `PlayerEconomy`/`Settlement` 一致（A-4）。
- 大宗转让正确更新 `shareholders` 与玩家持仓；`PRIVATE` 公司场外转让可成功。
- 股权结构查询返回确定性排序、持股和恒等于 `totalShares`（纯玩家持股时，A-4）。
- 同种子两次运行，股权变动逐值一致（A-1）。

---

## 6. 财报与分红（t4，A-5）

> 现状：公司无财报/分红机制；`recentProfit` 由本规格 §3 的 `AssetScanner` 提供。分红须与法币 M2 协同（`ECON_FIAT_M2_SPEC`：玩家现金由央行基础货币/流转决定）。

### 6.1 财报（financial report，A-5）

- **周期**：每 `REPORT_QUARTER_TICKS`（默认 `24000L*30*3`，与基本面更新周期 `FUNDAMENTAL_UPDATE_QUARTER_TICKS` 对齐，`MarketEngine.java:93`）对每个 `PRIVATE/LISTED` 玩家公司生成一期财报快照。
- **财报字段**（确定性派生）：`periodEnd`(tick)、`netAssetValue`、`recentProfit`、`retainedEarnings`、`totalShares`、`eps = recentProfit/totalShares`、`roe = recentProfit/netAssetValue`（`netAssetValue>0` 时）、`ipoPrice`、`marketCap`（`LISTED` 时 = `lastPrice*totalShares`）。
- **存储**：新增 `CompanyData.addReport(CompanyFinancialReport r)` + `List<CompanyFinancialReport> reports`（新增字段，序列化；`contains` 兜底为空表）。`CompanyFinancialReport` 为新 record/类（`company/CompanyFinancialReport.java`），含上述字段。上限保留最近 `MAX_REPORTS`（默认 `40` 期）防膨胀。
- **接入点**：`MarketEngine.tick` 每 `REPORT_QUARTER_TICKS`（与 `FUNDAMENTAL_UPDATE_QUARTER_TICKS` 同判，`:416-420`）生成财报；`lastReportTime = tickCounter`。
- **查询**：命令/packet `"report"` 返回最近财报摘要。

### 6.2 分红（dividend，A-5）

- **触发**：每 `DIVIDEND_PERIOD_TICKS`（默认 `24000L*30*3*4`，即 4 个季度 ≈ 一年）检查；条件：`retainedEarnings > 0`、`status != DELISTED`。
- **分配**：分红总额 `payout = retainedEarnings * DIVIDEND_PAYOUT_RATIO`（默认 `0.5`，即把留存收益的一半分红，另一半留作再投资）。按 `shareholders` 持股比例分配：股东 `i` 得 `payout * shareholders[i]/totalShares`（`totalShares>0`；舍入误差归创始人账户或弃零，确定性规则——**规定按每股计算向下取整到 0.01，尾差累进创始人**）。
- **现金记账（M2 协同）**：每位股东 `PlayerEconomy.setCash(cash + 每股分红×持股)`；该现金视为公司真实利润派发（公司由扫描利润产生留存收益，分配即股东取得经营收益，**与法币模型一致：经济主体经营创造货币**）。`retainedEarnings -= payout`；`lastDividendTime = tickCounter`；记入财报/`dividendPaidTotal`（新增累计字段，序列化）。
- **确定性**：分红比例/舍入规则全常量、无随机；同状态同输出（A-1/A-5）。
- **与股东账本一致性**：分红只派发给 `shareholders` 中当前持股者（与 §5 股权账本一致）。

### 6.3 验收（t4 自身）
- 满足条件时按周期生成财报；字段与当前状态派生一致（A-5）。
- 分红按持股比例正确派发到各股东 `PlayerEconomy` 现金，`retainedEarnings` 正确扣减（A-5）。
- 同种子两次运行，财报/分红逐值一致（A-1）。

---

## 7. 退市/摘牌机制 + DelistingMonitor 落地（t4，A-6）

> 现状：`company/DelistingMonitor.java`（10 行）为占位符 `check(ServerLevel, MarketEngine)` 空实现，且**从未被任何代码调用**。本规格将其落地接线。

### 7.1 `DelistingMonitor` 落地

```java
public class DelistingMonitor {
    // 每 DELIST_CHECK_TICKS（默认 24000L*30*1，每季度）扫描一次全部 LISTED 玩家公司
    public static void check(ServerLevel level, MarketEngine engine);
    // 单公司判定 + 执行退市
    public static boolean evaluate(CompanyData company, MarketEngine engine);
}
```

- **判定阈值（确定性常量）**，命中任一即触发：
  1. **连续亏损**：`recentProfit < 0` 且 `PROFIT_LOSS_QUARTERS`（默认 `2`）期连续为负（用 `reports` 最近 N 期判定）；
  2. **净资产过低**：`netAssetValue < DELIST_MIN_NAV`（默认 `1000`）；
  3. **长期破发**：`LISTED` 且 `lastPrice < ipoPrice * DELIST_PRICE_FLOOR`（默认 `0.2`，即低于发行价 20%）持续 `PRICE_FLOOR_DAYS`（默认 `10` 个交易日，用 `dailyTrades`/价格序列判定，确定性）；
  4. **股东结构异常**（可选）：股东数 < 2 且为非创始人控股（本期默认关闭，防误伤）。
- **执行退市（确定性）**：
  - `status = DELISTED`、`ipoState = NONE`；
  - **回购/现金清偿**：对 `shareholders` 每股东，以 `buyoutPrice = max(ipoPrice, lastPrice)`（取较高者，保护股东）按持股支付现金（`PlayerEconomy.setCash(+buyoutPrice×持股)`），并 `removeHolding(stockId)`；`shareholders.clear()`；`totalShares` 全部回归公司；
  - **资产退出交易引擎**：从 `state`/`orderBooks`/`tradeHistory`/`dailyTrades`/`circuitBreaker*`/`pendingSettlements` 移除该 `stockId`（新增 `MarketEngine.removeAsset(stockId)`，复用 `registerAICompany` 的逆操作）；`aiCompanyManager` 不含玩家公司无需处理；
  - `companiesByStockId.remove(stockId)`（`stockId` 置 null 或保留历史记录——**保留 `stockId` 与 `reports` 作为历史档案**，但不再可交易）；`companiesByOwner` 保留 `DELISTED` 公司（供历史查询，不可再上市/升级）；
  - 已冻结（`Settlement.frozen`）的该股按 `buyoutPrice` 一并清偿并清除（确定性，防残留冻结股）。
- **接入点**：`MarketEngine.tick` 每 `DELIST_CHECK_TICKS` 调用 `DelistingMonitor.check(level, this)`（与财报/分红同区域，`try/catch` 防御）。

### 7.2 持久化（A-7）

- 新增字段 `reports`/`subscriptions`/`dividendPaidTotal` 随 `CompanyData.write()/read()` 序列化；`subscriptions`（认购意向）为窗口期临时态，**同样随 blob 持久化**（保证读档后 `SUBSCRIBING` 窗口可复现结算），`contains` 兜底为空。
- H2 `company` 镜像表新增列 `ADD COLUMN IF NOT EXISTS`；新增 `company_shareholder` 镜像表（§2.2，可选）。blob 仍是权威加载源。
- 退市/上市状态迁移全部在 blob 内，读档后可复现（A-7）。

### 7.3 验收（t4 自身）
- 构造连续亏损/净资产过低/长期破发任一条件，`DelistingMonitor` 在周期内触发退市（A-6）。
- 退市后：股东获回购现金、持股清零、资产从交易引擎移除、`status==DELISTED`、不可再交易（A-6）。
- 残留冻结股被清偿清除；读档后退市状态可复现（A-6/A-7）。
- 同种子两次运行，退市判定与清偿逐值一致（A-1）。

---

## 8. 公司命令补全 + 合并同名 CompanyCommand（t2，A-8）

> 现状：存在**两个同名类** `CompanyCommand`：`command/CompanyCommand.java`（58 行，被 `FinancialMarket.java:128` 注册）与 `company/CompanyCommand.java`（58 行，**死代码，未被注册**）。两者内容几乎相同（仅 `command` 版本多 `CompanyData company =` 赋值）。

### 8.1 合并策略（A-8）

- **删除** `company/CompanyCommand.java`（重复类）；**保留** `command/CompanyCommand.java` 并扩展为完整命令集。删除后全仓无同名类冲突。
- 更新 `FinancialMarket.java:128` 仍注册 `command.CompanyCommand`（路径不变）。

### 8.2 命令集（`/fmarket company ...`，A-8）

```text
/fmarket company create <name>          # 创建公司（创造模式；生产可考虑成本，本期创造模式）
/fmarket company level [set <1-5>]      # 查询等级 / 设定等级（管理）
/fmarket company levelup|leveldown      # 升级/降级（升级走 §3.4 确定性门槛）
/fmarket company ipo                    # 发起 IPO（定价 + 开发行窗口，§4）
/fmarket company subscribe <shares>     # 认购新股（§4.4）
/fmarket company info                   # 公司信息（name/status/level/nav/profit/shares/ipoPrice/ipoState）
/fmarket company shareholders           # 股权结构（§5.3）
/fmarket company transfer <uuid> <shares> # 场外股权转让（§5.2）
/fmarket company report                 # 最近财报（§6.1）
/fmarket company dividend               # 立即触发一次分红（管理操作，验证用）
/fmarket company delist                 # 立即触发退市检查（管理操作，验证用）
/fmarket company bind-area <x1 z1 x2 z2> # 命令行绑定区域（与 CompanyAreaPacket 等效，§3.3）
/fmarket company scan                   # 手动触发一次 AssetScanner（§3.2）
```

- **权限**：`create/ipo/subscribe/info/shareholders/transfer/bind-area` 普通玩家可执行（`hasPermission(0)` 或直接玩家）；`level/leveldown/level set/dividend/delist/scan` 管理操作 `hasPermission(2)`（创造模式）。
- **校验**：所有命令走确定性校验，失败发中文 `sendFailure`；成功 `sendSuccess`。
- **helper 前置**：`CompanyCommand` 通过 `MarketAttachment.getEngine(player.serverLevel())` 取引擎（现有模式），`getByOwner(player.getUUID())` 取公司；新增取法全部复用 `CompanyManager`/`MarketEngine` 已定义方法，**不在命令类里直接改 `CompanyData` 私有字段**。

### 8.3 验收（t2 自身）
- `company/CompanyCommand.java` 已删除，全仓无两个同名类；`./gradlew compileJava` 通过（A-0/A-8）。
- 命令集逐个可用，普通/管理权限正确区分（A-8）。
- 所有命令确定性（无随机源）。

---

## 9. 网络包 / 前端协同（A-3/A-4/A-5 可玩性）

- **`CompanyActionPacket`**（`network/packets/CompanyActionPacket.java`）扩展 action：`ipo / subscribe / transfer / shareholders / report / dividend / delist / scan`；`handle` 按 action 分派到 `MarketEngine`/`CompanyManager`/`AssetScanner`/`DelistingMonitor` 的确定性方法，主线程 `enqueueWork` 内执行（现有模式）。**保持 `levelup/leveldown/list/create` 兼容**（`list` 语义改为走 IPO 状态机：`list` 等价 `ipo`，`ipoState` 从 `NONE`→`PRICED`→`SUBSCRIBING`）。
- **`CompanyDataResponsePacket`** 已用 `CompanyData.write()` 全量序列化（`CompanyDataResponsePacket.java:19`），新增字段自动带出；`RequestCompanyDataPacket.handle`（`:26`）回传当前公司。前端 `updateCompanyData` 即可显示 `ipoState/ipoPrice/floatShares/retainedEarnings`。
- **前端**（`FinancialTabletScreen` 公司页，`:825-857`）**协同增强**（非硬性验收）：把「上市」按钮改为按 `ipoState` 显示「发起上市/认购 N 股/已上市」，新增认购数量输入与股东列表/财报摘要展示。服务端逻辑先行就绪，前端可后续迭代。
- **`ModMessages`**（`network/ModMessages.java:35-37,79-82`）已注册 `CompanyActionPacket/RequestCompanyDataPacket/CompanyAreaPacket/CompanyDataResponsePacket`，无需新增包类（action 在现有包内扩展）。

---

## 10. 参数包与调参约束

集中为 `CompanyData`/`CompanyManager`/`AssetScanner`/`ListingValidator`/`DelistingMonitor`/`MarketEngine` 内常量（推荐独立 `company/CompanyParams.java` 常量类，纯 Java，无随机）。默认值与硬约束：

```
// ===== 生命周期（t2） =====
assetScanner.scanIntervalTicks      = 24000 * 1     // 每游戏日扫描一次
assetScanner.profitCaptureRatio     = 0.10          // 净资产增值的 10% 计入可分配利润
// ===== IPO（t3） =====
ipo.floatRatio                      = 0.40          // 40% 流通，创始人保留 60%
ipo.windowTradingDays               = 3             // 认购窗口交易日数
ipo.minPrice                        = 1.0           // 最低发行价（0.01 整数倍）
listing.minNav                      = 5000          // ListingValidator 净资产门槛
listing.requireBoundArea            = true          // 须绑定有效区域（软门槛）
// ===== 股权（t3） =====
equity.boardLot                     = 100           // 复用 A_SHARE_BOARD_LOT，转让/认购按手
// ===== 财报/分红（t4） =====
report.quarterTicks                 = 24000 * 30 * 3  // 与 FUNDAMENTAL_UPDATE_QUARTER_TICKS 对齐
report.maxReports                   = 40
dividend.periodTicks                = 24000 * 30 * 3 * 4  // 一年一次
dividend.payoutRatio                = 0.50          // 留存收益的 50% 分红
// ===== 退市（t4） =====
delist.checkIntervalTicks           = 24000 * 30 * 1  // 每季度检查
delist.profitLossQuarters           = 2             // 连续亏损季度数
delist.minNav                       = 1000          // 净资产下限
delist.priceFloor                   = 0.20          // 低于发行价 20%
delist.priceFloorDays               = 10            // 持续交易日数
delist.buyoutMaxOf                   = "ipoOrLast"   // 回购价取 max(ipoPrice, lastPrice)
```

- **收敛性**：IPO 定价/分红/回购均不产生发散随机；分红与退市回购会向玩家现金注入货币（公司真实利润），量受 `profitCaptureRatio`/`payoutRatio`/公司净资产约束，天然有界。若现金膨胀，优先降 `profitCaptureRatio`、再降 `dividend.payoutRatio`。
- **调参策略（t5 一键重跑对比）**：以各 `enabled` 为开关轴；走完整生命周期，验证 IPO 定价量纲（0.01 整数倍）、分红/回购现金注入、退市阈值命中。

---

## 11. 兼容性（继承 P0/P1/P2/A 股，公司系统增量）

### 11.1 网络包 / UI / Web
- **不删除/不重构**现有 packet 结构。`CompanyActionPacket` 只**新增 action**（向后兼容：未知 action 忽略）。`CompanyDataResponsePacket` 结构不变（`write()` 自含新增字段）。
- 前端公司页为增量 UI（§9），服务端不依赖。

### 11.2 H2 / NBT 持久化（对齐 H2_MIGRATION.md）
- 公司数据权威在引擎 blob（`MarketEngine.save()/loadInternal()` 的 `companies` 子键，`:1000-1001/:1169`），随 `engine_kv.engine_main` + NBT 附件镜像双写。
- 新增 `CompanyData` 字段（含 `reports/subscriptions/dividendPaidTotal/retainedEarnings/ipoState/floatShares/...`）进 `write()/read()`，**全部 `contains` 兜底**（缺失用默认，读档不失败，A-7）。
- H2 `company` 镜像表新增列用 `ADD COLUMN IF NOT EXISTS`（复用 `ai_company` 先例 `H2DatabaseManager.java:230-246`）；新增 `company_shareholder` 镜像表（可选）。镜像表仅供查询，blob 是加载权威。
- **退市/上市/分红状态**全部在 blob 内，读档可复现（A-7）。

### 11.3 可复现（公司系统 × SeedableRandom）
- 新增随机仅用于 `MarketEngine.registerPlayerCompany` 的 `volatility/drift`（复用 `engineRng`，`:302-303` 同源可播种子流），其余 IPO 定价/分红/回购/退市全部确定性函数、无随机。
- **验证**：t5 固定 `engineSeed` 两次运行，成交价序列 + 公司状态序列哈希一致（A-1）。

### 11.4 与已交付引擎协同（不破坏）
- 不动 `AICompanyData`/`PriceDiscoveryEngine.step`/`SettlementSystem` T+1/`submitOrder` A 股校验（文首硬约束）。
- 玩家公司资产走 `registerAICompany` 同源路径，industry=`PLAYER`，不纳入 `Excellent24Index`（默认，非目标）。
- 分红/退市回购通过 `PlayerEconomy.setCash` 派发现金，走法币 M2 流转（`ECON_FIAT_M2_SPEC`）。

---

## 12. 对 t5（整体验证 + 构建验证）的需求

1. **构建验证**：`./gradlew compileJava` 通过（A-0）；`./gradlew build` 出包。
2. **编译/随机审计**：`grep -rn "Math.random\|new Random\|ThreadLocalRandom" src/main/java` 不得出现 P2 之后新增的调用（允许既有历史；公司系统增量必须用 `SeedableRandom` 或纯确定性，A-1）。
3. **全生命周期冒烟**：固定 `engineSeed` 走「创建 → 扫描 → 升级 → IPO 定价/发行/认购 → 二级市场成交 → 股权转让 → 财报 → 分红 → 退市」全流程，断言每步状态/账本/现金（A-2~A-6）。
4. **股权一致性校验**：全流程后 `Σ shareholders == totalShares`（纯玩家持股段）、`shareholders` 与 `PlayerEconomy`/`Settlement` 一致（A-4）。
5. **可复现脚本**：固定 `engineSeed` 一键重跑 N tick，成交价序列 + 公司状态序列两次哈希一致（A-1）。
6. **持久化校验**：跑一段流程后保存→读档，断言公司字段/退市状态/股东账本完整恢复（A-7）。
7. **命令集验收**：逐个执行 §8.2 命令，断言成功/失败反馈与权限正确（A-8）。

---

## 13. 实施顺序与依赖（供 scheduler）

```
t1 (本规格) ──→ t2 (生命周期 + AssetScanner + 命令补全/合并同名: CompanyData/Manager/AssetScanner/CompanyAreaPacket/CompanyCommand)
        └──→ t3 (IPO 上市 + 股权管理: ListingValidator/IPO 状态机/registerPlayerCompany/attributeTrade 股权钩子/转让/股东查询)
                └──→ t4 (财报/分红 + 退市摘牌: reports/dividend/DelistingMonitor/removeAsset) ──→ t5 (验证) ──→ t6 (评审)
```

- **依赖**：t2 依赖 t1（数据模型/命令基座）；t3 依赖 t2（`CompanyData` 扩展 + `nextStockId` + 命令入口）；t4 依赖 t2/t3（财报消费净资产/利润、分红消费 `shareholders`、退市消费 `LISTED` 资产）。t5 依赖 t2/t3/t4；t6 依赖 t5。
- **优先级**：t2（生命周期 + 命令基座，A-8/A-2）先行；t3（IPO + 股权，A-3/A-4）核心；t4（财报分红退市，A-5/A-6）收口。

---

## 14. 与非目标边界（防范围蔓延）

- **不动** `AICompanyData`（`AICompanyData.java`）估值/IPO 量纲（P0 已定稿）。
- **不动** `PriceDiscoveryEngine.step` GBM/GARCH 与 A 股交易规则常量（P0/A 股已定稿）。
- **不重构** `SettlementSystem` T+1 或 `MarketEngine.submitOrder` A 股校验；仅在既有 `attributeTrade` 叠加股权同步钩子。
- **不把玩家公司纳入 `Excellent24Index`/AI 行业体系**（本期默认排除；指数纳入为后续增强）。
- **前端公司页 UI 为协同增强**，服务端逻辑先行就绪，前端改动不作为 A-3~A-6 硬性验收项。
- **创始人变更/多创始人/公司间并购/上市再融资/增发**为后续增强，本期不做。
- 全部为 O(公司数)/O(股东数)/O(容器数) 常数级；`AssetScanner` 每周期遍历容器，容器数受 `maxContainers` 上限约束（≤200/公司），可控。

---

## 15. 输入/来源索引

- 团队 Goal：公司系统需求规格（本任务 t1）。
- 已交付基座：`P2_SPEC.md`（订单簿/ABM）、`A_SHARE_TRADING_SPEC.md`（AS-1~AS-8）、`ECON_FIAT_M2_SPEC.md`（法币/M2）、`H2_MIGRATION.md`（H2/NBT）、`ECON_P0_SPEC.md`/`ECON_P1_SPEC.md`（SeedableRandom/行为基座）。
- 代码证据：`company/CompanyData.java`、`company/CompanyManager.java`、`company/AssetScanner.java`、`company/ListingValidator.java`、`company/DelistingMonitor.java`、`company/CompanyLevel.java`、`company/CompanyStatus.java`、`company/CompanyCommand.java`、`command/CompanyCommand.java`、`engine/MarketEngine.java`（`:299-316` registerAICompany、`:416-420` 基本面周期、`:779-785` attributeTrade、`:804-861` submitOrder、`:945` getCompanyManager、`:1000-1001/:1169` companies 持久化、`:1545-1567` H2 company 镜像）、`engine/SettlementSystem.java`、`attachment/PlayerEconomy.java`、`network/packets/CompanyActionPacket.java`、`CompanyAreaPacket.java`、`CompanyDataResponsePacket.java`、`RequestCompanyDataPacket.java`、`storage/db/H2DatabaseManager.java`（`:187-200` company 表、`:230-246` ADD COLUMN 先例）、`screen/FinancialTabletScreen.java`（`:825-857` 公司页）、`FinancialMarket.java`（`:128` 命令注册）。
