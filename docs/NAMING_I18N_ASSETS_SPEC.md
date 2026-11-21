# NAMING_I18N_ASSETS_SPEC — A股命名系统 + 语言适配 + 纹理/建模 需求规格

> - 角色：architect（方案架构/需求规格）为本任务产出。
> - 输入：团队 Goal（Financial Market 模组下一步：①A股命名系统 ②语言适配 ③物品纹理+方块建模）+ 当前源码审计。
> - 用途：engine-dev（命名系统/引擎实现）、asset-dev（资源/语言/纹理/建模）、test-engineer（验证/测试/构建）、reviewer（评审验收）按本规格落地。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、确定性可复现、H2/NBT 持久化兼容、不改已交付核心经济引擎语义。
> - **可复现性硬约束（继承 P0/A0-3/P2/COMPANY_SPEC A-1）**：所有新增随机一律用 `SeedableRandom`（`engine/random/SeedableRandom.java`，SplitMix64）**派生子流**，**禁用裸 `new Random()` / `ThreadLocalRandom` / `Math.random()`**；同一 `engineSeed` 同行为必须保持。
> - **兼容边界硬约束（不改已交付语义）**：见 §0.3。

---

## 0. 目标与全局验收

> 让 Financial Market 模组的公司/资产命名严格符合 A 股规则、物品/方块显示名中英文齐全、物品纹理与方块模型完整可见可放置。与已交付的经济引擎（P0 行情 / A 股交易 / 法币 M2 / 公司系统）协同，保持确定性可复现、H2/NBT 持久化兼容。

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| N-0 | 编译通过 | `./gradlew compileJava` 退出码 0；`./gradlew build` 出包 | 每项实施 + 验证 |
| N-1 | A股命名系统 | AI 公司名与股票代码严格符合 A 股代码段规则；玩家 IPO 上市公司与 AI 共用同一套规则与代码池且不与 AI 冲突；全仓 `grep "Math.random\|new Random\|ThreadLocalRandom"` 命名增量无新增裸随机 | **engine-dev** + 验证 |
| N-2 | 确定性可复现 | 命名/代码分配全部走 `SeedableRandom` 派生子流；固定 `engineSeed` 两次运行，AI 公司名/代码序列与玩家 IPO 代码分配序列逐项一致 | **engine-dev** + 验证 |
| N-3 | 持久化兼容 | 玩家公司名/代码随存档（引擎 blob + H2 `company` 镜像表）恢复；代码池已占用集合可从既有数据重建，读档不失败 | **engine-dev** + 验证 |
| N-4 | 语言适配 | 全部物品(7)/方块(4) 有正确翻译键；`en_us.json` 补齐 + 新增 `zh_cn.json` 全覆盖；无缺失/错误键 | **asset-dev** + 验证 |
| N-5 | 纹理/建模 | 每个物品有可用纹理与 item model；每个方块有 blockstate + block model + block texture；游戏中可见、可放置、外观正确 | **asset-dev** + 验证 |
| N-6 | 不改核心经济语义 | `AICompanyData` 估值/IPO 量纲、`PriceDiscoveryEngine.step` GBM/GARCH、`SettlementSystem` T+1、`MarketEngine.submitOrder` A 股校验均不被改动 | 全员 |

**验收流程（验证落地）**：固定 `engineSeed` 两次运行 → 断言 AI 公司名/代码序列哈希一致（N-2）；跑玩家 IPO 全流程 → 断言玩家代码来自共享池且不与 AI 冲突（N-1/N-2）；保存→读档断言公司名/代码完整恢复（N-3）；遍历 `lang` 键与注册表核对全覆盖（N-4）；核对资产文件清单与资源路径存在（N-5）；`grep` 裸随机审计 + 静态核对不改动清单（N-6）。

---

## 0.1 当前源码审计（本规格的落地基座）

### 资产注册与现有 lang
- `init/ItemInit.java`（`src/main/java/com/example/financialmarket/init/ItemInit.java`）注册 7 个物品：
  `financial_tablet` / `bank_tablet` / `government_tablet`（顶层 item），`market_block` / `delivery_block` / `exchange_screen` / `trading_terminal`（BlockItem，`BlockInit` 的方块）。
- `init/BlockInit.java` 注册 4 个方块：`market_block`（MarketBlock，普通 Block + BE）、`delivery_block`（DeliveryBlock，extends MarketBlock）、`exchange_screen`（ExchangeScreenBlock，HorizontalDirectionalBlock + BER）、`trading_terminal`（TradingTerminalBlock，HorizontalDirectionalBlock + BE）。
- `lang/en_us.json`（`src/main/resources/assets/financialmarket/lang/en_us.json`）现有 7 行，**仅**含：
  - `item.financialmarket.financial_tablet` = Financial Tablet
  - `block.financialmarket.exchange_screen` = Exchange Screen
  - `block.financialmarket.trading_terminal` = Trading Terminal
  - 2 条 subtitles。
  **缺失**：`bank_tablet`、`government_tablet`、`market_block`、`delivery_block`（item 与 block 键）。**无 `zh_cn.json`**。

### 现有资产文件
- `models/item/`：`bank_tablet.json`、`exchange_screen.json`（parent=block model）、`financial_tablet.json`、`government_tablet.json`、`trading_terminal.json`（parent=block model）。**缺失** `market_block.json`、`delivery_block.json`。
- `models/block/`：`exchange_screen.json`、`trading_terminal.json`（均 parent `block/cube`，六面同纹理）。**缺失** `market_block.json`、`delivery_block.json`。
- `blockstates/`：`exchange_screen.json`（4×facing variants）、`trading_terminal.json`（4×facing）。**缺失** `market_block.json`、`delivery_block.json`。
- `textures/block/`：`exchange_screen.png`、`trading_terminal.png`。**缺失** `market_block.png`、`delivery_block.png`。
- `textures/item/`：`bank_tablet.png`、`financial_tablet.png`、`government_tablet.png`（exchange_screen/trading_terminal 经 item model 复用 block 纹理）。

### 命名现状（待改造）
- `engine/ai/AICompanyManager.java` 构造 50 家 AI 公司：用 **裸 `new Random(seed)`** 生成
  - `id = "AI_" + i`（交易资产 id，`MarketEngine.registerAICompany` 用 `getStockId()`，即 `AI_0..AI_49`）；
  - `name = prefixes[rand] + suffixes[rand]`（前缀 10 项 + 后缀 10 项，无「地域/名号+行业+股份/科技」的完整式）；
  - `cnCode = String.format("60%04d", rand.nextInt(10000))` → **不是规范的 A 股代码段**（未区分 600/601/603/605/深主板/创业板/科创板/北交所）；
  - `usCode = 4 位大写字母`。
- `company/CompanyManager.nextStockId()`（`CompanyManager.java:82-90`）当前生成 `"PC" + %04d`（`PC0001`…），**非 A 股代码段**，玩家 IPO 需改为与 AI 共享的 A 股代码池。
- `AICompanyData`（`engine/ai/AICompanyData.java`）：`stockId`（trading id）/`name`/`usCode`/`cnCode` 均 final，`getStockId()/getName()/getCnCode()/getUsCode()` 供读取；`MarketEngine.save()`（`:1810-1836`）持久化 `id/name/industry/asset/us/cn/...`，`loadInternal`（`:2035-2039`）用 `new AICompanyData(id, name, industry, asset, 0.02, 0.05, us, cn)` 恢复。

---

## 0.2 本规格改动的文件清单（概览）

| 文件 | 改动性质 | 责任 |
|---|---|---|
| `engine/naming/AStockCodePool.java`（新） | A 股代码池：段规则 + 确定性分配 + 冲突避免 | engine-dev |
| `engine/naming/CompanyNameGenerator.java`（新） | 中文公司名随机组合（SeedableRandom 子流） | engine-dev |
| `engine/ai/AICompanyManager.java` | 公司名/代码改用命名系统；`stockId` 保持 `AI_i` 不变（交易兼容） | engine-dev |
| `company/CompanyManager.java` | `nextStockId()` 改走共享代码池，不再返回 `PCxxxx` | engine-dev |
| `engine/MarketEngine.java` | 接入命名系统（保存/加载/池重建），代码池已占用集合持久化 | engine-dev |
| `lang/en_us.json` | 补齐 4+ 缺失键 | asset-dev |
| `lang/zh_cn.json`（新） | 全量中文键 | asset-dev |
| `models/item/market_block.json`、`delivery_block.json`（新） | BlockItem item model | asset-dev |
| `models/block/market_block.json`、`delivery_block.json`（新） | 方块模型 | asset-dev |
| `blockstates/market_block.json`、`delivery_block.json`（新） | 方块状态（无 facing 单 variant） | asset-dev |
| `textures/block/market_block.png`、`delivery_block.png`（新） | 方块纹理 | asset-dev |
| `textures/item/…`（可选完善） | 现有 3 平板纹理按需优化 | asset-dev |

> 已有且正确的资产（`financial_tablet`/`bank_tablet`/`government_tablet` item model+texture、`exchange_screen`/`trading_terminal` block 全套）**不删除、不回退**，只按 §3 补充缺失项。

---

## 0.3 兼容边界（不改已交付语义，N-6）

1. **不改** `AICompanyData` 的 `updateFairValue()`/`restoreFundamentals()` 公式、IPO 量纲（P0 已定稿）。AI 公司**交易资产 id 保持 `AI_i`**（`stockId`）不变——只改 `name` 与 `cnCode`（显示/展示用），不改动影响行情定价的字段。
2. **不改** `PriceDiscoveryEngine.step()` GBM/GARCH、A 股交易规则常量（`PriceDiscoveryEngine.java:33-50`）。
3. **不改** `SettlementSystem` T+1 语义、`MarketEngine.submitOrder` A 股校验。
4. **不改** `AICompanyManager` 中用于资产/趋势/波动率/财务的裸 `Random(seed)` 取数（该 Random 是**已交付**的，只**新增命名用**的 `SeedableRandom` 子流，绝不替换影响资产参数的取数）。※ 命名模块只新增独立的 `SeedableRandom` 子流，不复用/不挪用资产取数。
5. AI `stockId`（`AI_i`）与 A 股 `cnCode` 是两个字段：**代码池作用于 A 股代码（AI 用 cnCode、玩家用 stockId），两者共同构成全局唯一命名空间**，保证 AI 与玩家代码互不冲突（见 §1.3）。

---

# ① A股命名系统规格

## 1.1 股票代码段规则（A 股代码段）

代码为 **6 位数字字符串**，前 3 位为交易所/板块前缀，后 3 位为序号（000–999）。合法段：

| 交易所/板块 | 前缀 | 例 |
|---|---|---|
| 沪市主板（上交所） | `600` `601` `603` `605` | 600001、603519 |
| 深市主板（深交所） | `000` `001` `002` `003` | 000001、002415 |
| 创业板 | `300` `301` | 300750、301029 |
| 科创板 | `688` `689` | 688981、689009 |
| 北交所 | `8xxxxx`（`8` 开头的 6 位，如 `830`–`899` 段） | 832885、835640 |

- **代码格式**：`prefix + String.format("%03d", seq)`，seq ∈ [0,999]，满段则跳下一段。
- **段枚举**：实现层用枚举/常量表 `AStockCodePool.Segment { SH600("600"), SH601("601"), SH603("603"), SH605("605"), SZ000("000"), SZ001("001"), SZ002("002"), SZ003("003"), CY300("300"), CY301("301"), KC688("688"), KC689("689"), BJ8("8") }`，`BJ8` 表示 8 开头 6 位。
- **合法校验**：`AStockCodePool.isValid(code)` 判定 6 位数字、前缀在段表内；非法前缀拒绝分配。

## 1.2 中文公司名规则（「地域/名号 + 行业 + 股份/科技」式随机组合）

公司名 = `地域/名号词根 + 行业词根 + 组织后缀`，三段从确定性词库随机组合：

- **地域/名号词根**（nameRoots）：华夏、中原、天枢、星河、东岳、南岭、昆仑、东海、北辰、九鼎、云泽、鸿运、长风、曜石、磐石、沧海、青岚、紫垣、苍梧、玄黄 …（≥20 项）
- **行业词根**（industryRoots）：电子、医药、能源、机械、钢铁、建材、金融、物流、农业、科技、信息、通信、化工、汽车、食品、纺织、矿业、环保、军工、航天 …（≥20 项）
- **组织后缀**（orgSuffix）：股份、科技、控股、集团、实业、电子、能源、生物、新材、装备 …（≥10 项）
- **组合**：`root + industry + suffix`，如「星河电子科技」「东岳金融控股」「九鼎新能源股份」。
- **实现**：`CompanyNameGenerator.generate(SeedableRandom rng)` 用传入 rng 连续 `nextInt(词库size)` 三段独立选取；保证同一 rng 状态 → 同名。
- **确定性**：调用方用 `SeedableRandom` 子流（§1.4），词库为编译期常量数组（纯 Java，无随机）。

> **说明（玩家公司名）**：玩家公司名由玩家 `/fmarket company create <name>` 提供，**不随机生成**，本规则只用于 **AI 公司名** 的随机组合。玩家公司**股票代码**走共享代码池（§1.3）。若未来需要「AI 用中文名、玩家可重命名」，玩家名走既有 `setName`，代码仍走共享池。

## 1.3 共享代码池（AI + 玩家共用，互不冲突）

### 1.3.1 设计
新增 `engine/naming/AStockCodePool.java`，是 **AI A 股代码（`AICompanyData.cnCode`）与玩家公司股票代码（`CompanyData.stockId`）共用的唯一命名空间**。

- 持有：`Set<String> used`（已占用代码）、`SeedableRandom rng`（分配用子流）。
- `allocateCode()`：确定性从段表按优先级取段，段内取「当前未占用」的最小序号；若被占则顺序探测；保证返回值 `isValid` 且 `!used.contains`。
- `markUsed(code)` / `contains(code)`：登记/判重。
- `isTaken(code)`：跨 AI 与玩家统一判重（AI `cnCode` 与玩家 `stockId` 共同喂入 `used`）。

### 1.3.2 AI 与玩家不冲突的保证
- **初始化/读档**时，把 (a) 全部 AI 公司的 `cnCode`、(b) 全部玩家公司 `CompanyData.stockId` 一并 `markUsed`，重建 `used` 集合（见 §1.5 持久化重建）。
- AI 分配：`AICompanyManager` 构造时对每家公司从共享池取 `cnCode`（用命名子流选段/选号），`markUsed`。
- 玩家分配：`CompanyManager.nextStockId()` 从**同一个**共享池取号，先探测 `used`，避免与任何 AI `cnCode` 及既有玩家 `stockId` 重复。
- 因此 AI 先占的代码玩家不会再取；玩家之间也不会重复。

### 1.3.3 `CompanyManager.nextStockId()` 改造
- 签名/语义保持 `String nextStockId()`（供 IPO 调用），但**返回值改为共享池分配的 6 位 A 股代码**，不再返回 `PCxxxx`。
- 实现：`return pool.allocateCode()`（在 engine 已注入共享池时）；`CompanyManager` 需能访问共享池——由 `MarketEngine` 构造时注入（复用 `setEngine` 模式，见 `CompanyManager.setEngine`）。
- **向后兼容**：旧存档玩家 `stockId` 为 `PCxxxx` 时，`isStockIdTaken`/`getByStockId`/`removeCompany`/`unregisterStockId` 逻辑不变（仅字符串判等），读档不失败；新分配的走新格式。旧 `PCxxxx` 公司不参与共享池冲突（视为历史遗留，不回收、不重复发放其代码）。

## 1.4 确定性（SeedableRandom 子流，禁裸随机）

- **命名子流**：`engineSeed` 派生命名专用种子 `long namingSeed = SeedableRandom.mix64(engineSeed ^ "a_share_naming".hashCode())`，`AICompanyManager`/代码池用 `new SeedableRandom(derive("..."))` 派生子流，保证同 `engineSeed` → 同 AI 名/代码序列。
- **代码池分配**：`AStockCodePool` 内部 `rng` 由 `namingSeed.derive("code_pool")` 生成；分配顺序确定性 → 玩家 IPO 顺序固定时代码分配可复现。
- **禁裸随机审计**：新增命名/代码代码**不得**出现 `new Random(` / `ThreadLocalRandom` / `Math.random`（仅允许复用已交付的 `AICompanyManager` 资产取数 Random，见 §0.3-4）。验证用 `grep -rn "Math.random\|new Random\|ThreadLocalRandom" src/main/java` 人工区分已交付 vs 新增。

## 1.5 H2 / NBT 持久化兼容（N-3）

- **玩家公司名/代码**：`CompanyData.name`/`stockId` 已随 `CompanyData.write()/read()`（`CompanyData.java:170-276`）序列化进引擎 blob（`MarketEngine.save()` `companies` 子键，`:1807-1808`），并经 `MarketEngine.saveRelationsToH2`（`:1545-1567`）写 H2 `company` 镜像表。**无新增字段**，读档天然兼容。※ 若需更明确，可在 H2 `company` 表用 `ADD COLUMN IF NOT EXISTS`（复用 `H2DatabaseManager.java:230-246` 先例）加 `cn_code`/`code_segment` 查询镜像列（可选，blob 仍是权威）。
- **代码池已占用集合重建**（关键，保证不冲突跨读档）：读档时**不存 `used` 独立快照**，而是由已加载的 (AI `cnCode`) ∪ (玩家 `stockId`) 重建。因为 AI 与玩家公司数据都从 blob 恢复，`used` 是这些数据的确定性函数，**无需新增持久化字段即可跨读档保证不冲突**。这样：
  - 读档后 `used` = 当前存档内全部已用代码；
  - 后续玩家 IPO `allocateCode()` 自动避开 AI 与历史玩家代码；
  - 旧档（`PCxxxx` 玩家 + 旧 AI `60xxxx` cnCode）读入后，`used` 仅含这些历史代码，新代码从空闲段分配，**读档不失败、不冲突**。
- **AI `cnCode` 变更与读档**：AI `name`/`cnCode` 在 `save()` 时写入 blob（`:1813-1818`），读档用 blob 恢复（`:2035-2039`），故新格式代码在**新生成**的存档生效；旧存档恢复旧代码字段，不重随机（P0-2 G-04 语义：读档恢复而非重随机）。※ AI 名/代码仅影响展示，不改交易 id/资产参数，故改动不会破坏读档行情连续性。

## 1.6 命名系统验收（engine-dev 自身）

- `AStockCodePool`：`isValid` 正确区分合法/非法段；`allocateCode` 返回合法且唯一代码；满段跳段正确。
- 同 `engineSeed` 两次构造 `AICompanyManager`，AI `name`/`cnCode` 序列逐项一致（N-2）；且 50 家 cnCode 全部 `isValid`、互不重复（N-1）。
- 玩家 IPO 分配的代码不与任一 AI `cnCode` 重复、彼此不重复；同一 `engineSeed` 同 IPO 顺序两次分配序列一致（N-2）。
- 保存→读档：AI `cnCode`/`name`、玩家 `stockId`/`name` 完整恢复；读档后新玩家 IPO 代码不与历史 AI/玩家冲突（N-3）。
- `grep` 无新增裸随机（N-6）。

---

# ② 语言适配规格

## 2.1 覆盖范围（全部 7 物品 + 4 方块 + 2 subtitles）

**物品（7）**：financial_tablet、bank_tablet、government_tablet、market_block、delivery_block、exchange_screen、trading_terminal。
**方块（4）**：market_block、delivery_block、exchange_screen、trading_terminal。

> **BlockItem 键说明**：`market_block`/`delivery_block`/`exchange_screen`/`trading_terminal` 是 `BlockItem`，其显示名默认取**方块**翻译键 `block.financialmarket.<id>`（`BlockItem.getDescriptionId` 返回 block 的 id）。为稳妥，`item.financialmarket.<id>` 键也一并提供（部分场景/工具提示可能读取；缺失无害但补齐更全）。`financial_tablet`/`bank_tablet`/`government_tablet` 为顶层 item，只用 `item.financialmarket.<id>`。

## 2.2 语言键对照表

### `en_us.json`（补齐后全量）

| 键 | 值（英文） | 状态 |
|---|---|---|
| `item.financialmarket.financial_tablet` | Financial Tablet | 已有 |
| `item.financialmarket.bank_tablet` | Bank Tablet | **新增** |
| `item.financialmarket.government_tablet` | Government Tablet | **新增** |
| `item.financialmarket.market_block` | Market Block | **新增** |
| `item.financialmarket.delivery_block` | Delivery Block | **新增** |
| `item.financialmarket.exchange_screen` | Exchange Screen | **新增** |
| `item.financialmarket.trading_terminal` | Trading Terminal | **新增** |
| `block.financialmarket.market_block` | Market Block | **新增** |
| `block.financialmarket.delivery_block` | Delivery Block | **新增** |
| `block.financialmarket.exchange_screen` | Exchange Screen | 已有 |
| `block.financialmarket.trading_terminal` | Trading Terminal | 已有 |
| `subtitles.financialmarket.exchange_open_bell` | Exchange open bell rings | 已有 |
| `subtitles.financialmarket.exchange_close_bell` | Exchange close bell rings | 已有 |

### `zh_cn.json`（新增，全量）

| 键 | 值（简体中文） |
|---|---|
| `item.financialmarket.financial_tablet` | 金融平板 |
| `item.financialmarket.bank_tablet` | 银行平板 |
| `item.financialmarket.government_tablet` | 政府平板 |
| `item.financialmarket.market_block` | 市场方块 |
| `item.financialmarket.delivery_block` | 交割方块 |
| `item.financialmarket.exchange_screen` | 交易所屏幕 |
| `item.financialmarket.trading_terminal` | 交易终端 |
| `block.financialmarket.market_block` | 市场方块 |
| `block.financialmarket.delivery_block` | 交割方块 |
| `block.financialmarket.exchange_screen` | 交易所屏幕 |
| `block.financialmarket.trading_terminal` | 交易终端 |
| `subtitles.financialmarket.exchange_open_bell` | 交易所开盘钟声 |
| `subtitles.financialmarket.exchange_close_bell` | 交易所收盘钟声 |

## 2.3 语言适配验收（asset-dev 自身）

- `en_us.json` 含上述**全部** 14 键（无遗漏、无拼写错误、JSON 合法）。
- `zh_cn.json` 存在且为合法 JSON，键集合与 `en_us.json` 一致（13 展示键 + 2 subtitles 或按表全量 13 键）。
- 用测试/脚本核对：遍历 `ItemInit`/`BlockInit` 注册的每个 id，存在对应 `item./block.` 键（N-4）。
- 游戏内（或数据核对）物品/方块显示名正确（英文/中文随游戏语言切换）。

---

# ③ 物品纹理 + 方块建模规格

## 3.1 资产文件总清单

> 资源命名空间 `financialmarket`，根目录 `src/main/resources/assets/financialmarket/`。

### 物品（7）
| 物品 id | item model | item texture | 状态 |
|---|---|---|---|
| financial_tablet | `models/item/financial_tablet.json` | `textures/item/financial_tablet.png` | ✅ 已有 |
| bank_tablet | `models/item/bank_tablet.json` | `textures/item/bank_tablet.png` | ✅ 已有 |
| government_tablet | `models/item/government_tablet.json` | `textures/item/government_tablet.png` | ✅ 已有 |
| market_block | `models/item/market_block.json`（parent=block/market_block） | 复用 block 纹理 | **新建** |
| delivery_block | `models/item/delivery_block.json`（parent=block/delivery_block） | 复用 block 纹理 | **新建** |
| exchange_screen | `models/item/exchange_screen.json`（parent=block/exchange_screen） | 复用 block 纹理 | ✅ 已有 |
| trading_terminal | `models/item/trading_terminal.json`（parent=block/trading_terminal） | 复用 block 纹理 | ✅ 已有 |

### 方块（4）
| 方块 id | blockstate | block model | block texture | 说明 |
|---|---|---|---|---|
| market_block | `blockstates/market_block.json` | `models/block/market_block.json` | `textures/block/market_block.png` | **新建**，无 facing 单 variant |
| delivery_block | `blockstates/delivery_block.json` | `models/block/delivery_block.json` | `textures/block/delivery_block.png` | **新建**，无 facing 单 variant |
| exchange_screen | `blockstates/exchange_screen.json` | `models/block/exchange_screen.json` | `textures/block/exchange_screen.png` | ✅ 已有（4×facing），可优化外观 |
| trading_terminal | `blockstates/trading_terminal.json` | `models/block/trading_terminal.json` | `textures/block/trading_terminal.png` | ✅ 已有（4×facing），可优化外观 |

## 3.2 建模要求

### 3.2.1 新建方块：market_block / delivery_block（asset-dev 核心）
- **blockstate**：两方块为普通 `Block`（非 `HorizontalDirectionalBlock`），**无 `facing` 状态属性**，blockstate 为单 variant：
  ```json
  { "variants": { "": { "model": "financialmarket:block/market_block" } } }
  ```
  （delivery_block 同理，model 路径替换。）
- **block model**：可用 `parent: "block/cube"`，六面 + particle 指向对应 block 纹理（对齐现有 exchange_screen 写法）；亦可做有识别度的形状（如市场柜台、交割台），实现层可选——**最低要求**是完整 cube 六面纹理，保证可见、可放置、不透明。
- **item model**：`parent: "financialmarket:block/market_block"`（对齐 `exchange_screen.json` 的 item model 写法），使手持/物品栏显示为方块模型。
- **纹理**：`textures/block/market_block.png`、`delivery_block.png`（16×16 或更高，占位需可见、有区分度，如市场方块偏柜台/金色系，交割方块偏仓柜/货箱系）。PNG 必须存在且非全透明（否则方块隐形）。

### 3.2.2 已有方块：exchange_screen / trading_terminal
- **不删除**现有模型/blockstate/纹理；若实现「优化外观」以提升辨识度（如屏幕面板、终端基座），保持 `facing` 4 variant 结构不变、纹理路径不变。
- **交易所屏幕**：BER（`client/blockentity/ExchangeScreenBlockEntityRenderer.java`）已在方块正面绘制内容，block model 提供载体面；确保模型/纹理不遮挡 BER 文字（当前 `translate 0.501` 贴墙，模型若加凸起需避免 z 冲突，默认保持 cube 即可）。

## 3.3 纹理/建模验收（asset-dev 自身）

- 下列文件全部存在且为合法 JSON / 非空 PNG：`models/item/{market_block,delivery_block}.json`、`models/block/{market_block,delivery_block}.json`、`blockstates/{market_block,delivery_block}.json`、`textures/block/{market_block,delivery_block}.png`。
- `blockstates` 的 model 引用、model 的 texture 引用路径正确（无 404 缺模型 → 游戏内方块不会显示为紫黑/隐形）。
- `item model` 引用存在的 block model（N-5）。
- 现有 7 物品 + 4 方块在创造模式物品栏可见、可放置、外观正确（N-5）。

---

# ④ 对验证（test-engineer）的需求

1. **构建验证**：`./gradlew compileJava` 通过（N-0）；`./gradlew build` 出包。
2. **命名确定性 + 规则验证**（可用 `.verify/harness/CompanyRealHarness.java` 同型 harness，见 `.verify/T5_COMPANY_VERIFY.md` 先例）：固定 `engineSeed` 两次构造，断言
   - 两次 AI `name`/`cnCode` 序列哈希一致（N-2）；
   - 全部 AI `cnCode` 通过 `AStockCodePool.isValid` 且互不重复（N-1）；
   - 玩家 `nextStockId()` 代码合法、不与 AI cnCode/历史玩家冲突（N-1/N-2）；
   - 两次 `nextStockId()` 序列一致（N-2）。
3. **持久化验证**：保存→读档断言玩家 `name`/`stockId`、AI `cnCode`/`name` 完整恢复；读档后新玩家 IPO 代码不与历史冲突（N-3）。
4. **语言覆盖验证**：遍历 `ItemInit`/`BlockInit` 注册 id，断言 `en_us.json`/`zh_cn.json` 有对应键（N-4）；JSON 解析合法。
5. **资产完整性验证**：按 §3.1 清单断言各 JSON/PNG 文件存在、JSON 合法、引用路径正确（N-5）。
6. **裸随机审计 + 不改语义静态核对**：`grep` 区分已交付 Random 与新增；核对 §0.3 硬约束文件未被改动（N-6）。

---

# ⑤ 实施顺序与依赖（供 scheduler）

```
t-req (本规格, architect)
   ├──→ engine-dev：A股命名系统（AStockCodePool + CompanyNameGenerator + AICompanyManager + CompanyManager.nextStockId + MarketEngine 接入/持久化）
   │         └──→ asset-dev：语言适配（en_us 补齐 + zh_cn.json）
   │                  └──→ asset-dev：纹理/建模（market_block/delivery_block 全套 + 可选优化 exchange/trading）
   │                          └──→ test-engineer：验证（编译 + 命名确定性/规则 + 持久化 + 语言/资产覆盖 + 静态核对）
   │                                  └──→ reviewer：评审验收
```

- **依赖**：asset-dev 语言/建模不依赖 engine-dev 代码（纯资源），可并行；验证依赖 engine-dev + asset-dev；评审依赖验证。
- **优先级**：engine-dev 命名系统（N-1/N-2/N-3）为功能核心；asset-dev 语言与建模（N-4/N-5）为体验补全，可并行推进。

---

# ⑥ 与非目标边界（防范围蔓延）

- **不改** AI 交易资产 id（`AI_i`）、`AICompanyData` 估值/IPO 量纲、行情/结算/A 股校验（N-6）。
- **不重构**既有 packet/前端 UI；命名系统不要求前端改动（前端沿用现有公司页展示 `stockId`/`name`）。
- **不删除**已正确的资产文件（tablet 纹理/模型、exchange/trading block 全套）。
- 玩家公司名**不随机生成**（由玩家提供）；本规格的随机命名只用于 AI 公司名与股票代码。
- 股票代码 **6 位固定长度**，不做数字前缀补位歧义；北交所段用 `8` 开头 6 位，不引入非标准长度。
- 不新增 GUI/交互；不涉及指数纳入、行业体系重构、多币种。
