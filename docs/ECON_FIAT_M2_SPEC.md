# ECON_FIAT_M2_SPEC — 法币体制 + M2 货币理论架构与需求契约

> - 产出：monetary-expert（货币/宏观经济学专家）为任务 t1（requirements）产出。
> - 输入：`MACRO_MODEL.md`、`ECON_IMPROVEMENT_PLAN.md`、`ECON_P0_SPEC.md`（P0 已落地）、`ECON_P1_SPEC.md`（P1 已落地）+ 当前代码审计（本规格已对照实际源码给出文件/行号/方法签名）。
> - 用途：engine-dev 按本规格实现 t2（彻底法币化）/t3（税收/纳税锚点）/t4（M2 货币理论 + 银行信贷创造）；test-engineer 按本规格实现 t5（整体验证与构建）；reviewer 按本规格验收 t6。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick 步进内 O(玩家+银行+资产) 常数级计算；**保持 P0 同种子可复现**与 **H2 持久化**。
> - 范围：仅本团队 Goal 的法币 + M2 改造。不改动 P0/P1 已交付的价格/行为模型语义（只在其上叠加货币总量与政策传导）。

---

## 0. 目标与全局验收（对应团队 Goal）

完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| FM-0 | 编译通过 | `./gradlew compileJava` 退出码 0 | t2/t3/t4 |
| FM-1 | 可运行 | server 启动无 NPE、无死循环，`MarketEngine.tick` 持续推进 | t5 冒烟 |
| FM-2 | 彻底法币化 | 取消 `CurrencySystem` 的下界合金锭固定汇率实物锚定；货币供给由央行货币政策决定；`NETHERITE_INGOT` 不再以固定 32:1 兑付现金 | t2 + t5 |
| FM-3 | 银行信贷创造 | 商业银行按准备金率派生存款；货币乘数 = 1/准备金率；M2 随放贷内生扩张 | t4 + t5 |
| FM-4 | 税收/纳税锚点 | 引入税收使法币有信用价值；政府可实际征税、国库与玩家现金真实流转 | t3 + t5 |
| FM-5 | M2 货币理论 | 按 M1（现金+活期）/M2（+定期/储蓄/货币市场）分层度量货币供给；通胀模型基于 M2 增长；利率/准备金/OMO 传导到经济 | t4 + t5 |
| FM-6 | 同种子可复现 | 同一 `engineSeed` 跑固定 tick，货币总量序列与政策路径一致 | t2~t4 + t5 |
| FM-7 | H2 持久化 | 货币状态随 `engine_kv` blob（`engine_main`）与 `MarketEngine.save/load` 序列化，读档恢复货币/银行/税收状态，缺字段用默认值重建 | t2~t4 + t5 |

**验收流程（t5 落地）**：固定 `engineSeed` 跑固定 tick，输出 M0/M1/M2、货币乘数、通胀、政策利率、税收累计、国库。法币化前后对比：改造后玩家初始现金来自央行基础货币注入（而非实物兑付），且放贷后 M2/M1 比值向 `1/准备金率` 收敛。

---

## 1. 现状诊断（代码审计结论）

| 类 | 文件 | 现状 | 问题（法币/M2 视角） |
|---|---|---|---|
| `CurrencySystem` | `engine/banking/CurrencySystem.java` | `BASE_ITEM=NETHERITE_INGOT`、`EXCHANGE_RATE=32`；`deposit/withdraw` 把合金锭按固定汇率兑现金/现金兑锭 | **实物锚定**：现金供给受玩家可挖的合金锭约束，非央行内生决定 |
| `PlayerEconomy` | `attachment/PlayerEconomy.java` | `cash` 初始 1000，`CODEC` 持久化 | 现金即"法币"，但无发行方、无总量记账 |
| `CentralBank` | `engine/banking/CentralBank.java` | `baseMoney=10_000_000`、`discountRate`、`reserveRequirement=0.10`、`buyBonds/sellBonds/reverseRepo/emergencyLending/adjustReserves` | `bankReserves` 从无存款侧填充；`baseMoney` 从不随玩家现金联动；无 M0/M1/M2 记账 |
| `Bank` | `engine/banking/Bank.java` | `deposit/withdraw/issueLoan/repayLoan(空)/dailyUpdate(空)/manageAssets` | `issueLoan` 已 `depositAccounts.merge(borrowerId, amount)` 派生存款，但未接入玩家现金、无准备金乘数记账、`repayLoan`/`dailyUpdate` 空实现 |
| `BankManager` | `engine/banking/BankManager.java` | 三家 AI 银行；`dailyUpdate` 仅内部空转；**`MarketEngine.tick` 从不调用** | 银行信贷系统整体未接线 |
| `Government` | `engine/banking/Government.java` | `treasury`、`totalDebt`、`taxRate=0.1`、`collectTax(income)=income*taxRate`、`issueBond/spend` | `collectTax` **从未被调用**；国库与实际玩家现金无流转 |
| `InflationSystem` | `engine/banking/InflationSystem.java` | 计算 `m2 = baseMoney + ΣtotalDeposits`，`inflationRate=0.6*moneyGrowth+0.4*priceIndex` | 有雏形但 `update()` **从未被调用**；M2 度量不完整（无 M1/定期分层） |
| `CreditSystem` | `engine/banking/CreditSystem.java` | `totalPrivateDebt`、`nplRatio`、`creditGrowth` | `update()` **从未被调用** |
| `MarketEngine` | `engine/MarketEngine.java` | `tick()` 中已接线宏观/周期/基本面/情绪；`save/load`、`saveToH2/loadFromH2` 持久化 | 银行/政府/央行/通胀/信贷**均未入 `save()/load()`**，且 `tick()` 未调 `bankManager.dailyUpdate`、`inflationSystem.update`、`creditSystem.update` |

**根因**：货币系统是"平行死代码"——中央行基础货币、银行派生存款、玩家现金三套总量互相独立、互不记账，且不参与 tick、不持久化。法币化的核心是把这三套统一为**央行发行 + 银行派生 + 玩家持有的分层货币体系**，并让货币政策（利率/准备金/OMO）真实传导到现有宏观层（`MacroEnvironment`）与经济。

---

## 2. 货币体系架构设计

### 2.1 分层货币定义（M0 / M1 / M2）

沿用现实 M2 货币层次，映射到游戏内：

| 层次 | 现实定义 | 游戏内实现 | 记账归属 |
|---|---|---|---|
| **M0（基础货币）** | 流通现金 + 银行准备金 | 央行 `baseMoney`（含银行准备金与初始注入） | `CentralBank` |
| **M1** | 流通中现金 + 活期存款（可随时支取） | 玩家 `PlayerEconomy.cash`（现金）+ 银行活期存款（`Bank.totalDeposits` 中"活期/随时支取"部分） | 央行 + `Bank` |
| **M2** | M1 + 定期存款 + 储蓄存款 + 货币市场基金 | M1 + 银行定期/储蓄存款（`SavingsProduct` 非活期部分）+ 货币市场类产品 | 央行 + `Bank` + 产品 |

> 数学：`M2 = M1 + 定期/储蓄 + 货币市场`；`货币乘数 m = 1 / reserveRequirement`。央行注入 1 单位 M0，经商业银行准备金约束下的逐级放贷，最多派生出 `m` 倍 M2（理论上限；受贷款需求、准备金短缺、坏账约束）。

### 2.2 架构图

```
┌────────────────────────── 货币体系（CURRENCY LAYER，O(玩家+银行)/tick）──────────────────────────┐
│                                                                                                │
│  [央行 CentralBank]  ← 货币政策：policyRate(泰勒规则/宏观看板)/reserveRequirement/OMO(逆回购·国债)   │
│    ├─ M0 基础货币 baseMoney                                                                │
│    └─ 初始法币注入（新玩家入金）→ PlayerEconomy.cash                                       │
│         │ 基础货币注入                                                                       │
│         ▼                                                                                     │
│  [商业银行 Bank/BankManager] 按准备金率 reserveRequirement 派生存款                            │
│    ├─ 吸收现金存款 → 保留部分作准备金，其余放贷                                          │
│    └─ issueLoan 放贷 → 借户活期存款增加 → M1/M2 内生扩张（货币乘数）                          │
│         │                                                                                     │
│         ▼                                                                                     │
│  [玩家现金 PlayerEconomy.cash] = M1 流通部分；[银行活期/定期存款] = M1/M2 存款部分                 │
│                                                                                                │
│  [Government] 税收 collectTax → 从玩家现金流入国库 → 法币信用锚点 + 货币回笼                    │
│                                                                                                │
│  [InflationSystem] ← 消费 M2 增长 → 通胀；← 政策利率/准备金 → 传导 MacroEnvironment             │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                 │ 只读 MacroSnapshot（policyRate/reserveRate/M2/M1/M0/inflation）
                 ▼
┌────────────────────────── 现有宏观层 ──────────────────────────────────────────────────────────┐
│  MacroEnvironment（升级：policyRate 驱动 r_bar / 传导 discountRate / 流动性乘子）→ 微观层      │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

**单向依赖**：货币层 → 宏观层 → 微观层（沿用 `MACRO_MODEL.md` §4 的只读 `MacroSnapshot` 思路，货币层不直接改价）。

---

## 3. 需求契约：t2 — 彻底法币化（取消实物锚定）

### 3.1 改动文件
- `src/main/java/com/example/financialmarket/engine/banking/CurrencySystem.java`
- `src/main/java/com/example/financialmarket/engine/banking/CentralBank.java`
- `src/main/java/com/example/financialmarket/attachment/PlayerEconomy.java`
- `src/main/java/com/example/financialmarket/engine/MarketEngine.java`
- `src/main/java/com/example/financialmarket/engine/banking/BankManager.java`
- 涉及入金的新玩家初始化处（`FinancialMarket.java` 或 `MarketAttachment.getOrCreateEngine`）

### 3.2 需求

1. **取消固定汇率实物锚定**：
   - `CurrencySystem` 的 `BASE_ITEM=NETHERITE_INGOT` 与 `EXCHANGE_RATE=32` **不再作为兑付现金的锚**。
   - `NETHERITE_INGOT` 保留为可交易商品（经 `CommodityMarket`，价格由市场决定），但**不再以固定 32:1 兑换 `PlayerEconomy.cash`**。
   - 具体做法（择一，推荐 A）：
     - **A（推荐）**：把 `CurrencySystem.deposit/withdraw` 改为**禁用/移除**（或改为"金币/锭的买卖走 `CommodityMarket`"），并新增法币入金入口：玩家现金由**央行基础货币注入**产生，不再来自实物兑付。
     - **B**：保留 `deposit/withdraw` 方法但语义改为"央行按**市场价格（浮动）**回收/出售合金锭"——即兑换比例随 `CommodityMarket` 价格浮动而非固定 32:1。此方案仍保留实物→现金通道，不彻底，故**不推荐**作为法币化主实现。
   - 由 t2 实施者选择 A 或"保留方法但改浮动 + 主通道改为央行注入"，并在代码注释与验收说明中写清选择。

2. **法币由央行货币政策决定（供给端）**：
   - `CentralBank` 新增 `injectBaseMoney(double)`（或复用 `baseMoney +=`）作为**唯一**扩大 M0 的方式（对应现实"基础货币由央行决定"）。
   - 新玩家初始现金：不再默认 `cash=1000` 凭空存在，而是由**央行基础货币注入**（如玩家入账时 `centralBank.injectBaseMoney(initialAirdrop)` 并 `eco.setCash(eco.getCash() + initialAirdrop)`）。`PlayerEconomy` 默认 1000 可作为兜底常量，但**发行侧须记账到 M0**，保证"现金有发行方"。
   - `MarketEngine` 在 `tick()` 中定期（如每日）把 `CentralBank.baseMoney` 与全体玩家现金/银行准备金对齐到统一记账（见 §4 记账），使 M0 成为真实统计量而非孤立数字。

3. **保持 P0 可复现**：所有新增货币随机源（初始注入量、税收浮动等）用现有 `SeedableRandom`（`engine/random/SeedableRandom.java`，`MarketEngine.engineRng`）派生子流，禁止裸 `new Random()`/`ThreadLocalRandom`（对齐 `ECON_IMPROVEMENT_PLAN` §6）。

### 3.3 验收（t2 自身）
- `CurrencySystem` 不再以固定 `EXCHANGE_RATE` 把合金锭兑成现金；代码中无"锭→现金按固定 32"路径。
- 新玩家现金可溯源到央行基础货币注入（M0 增加）。
- `./gradlew compileJava` 通过；同种子跑固定 tick，M0/玩家现金序列一致（FM-6）。

---

## 4. 需求契约：t4 — M2 货币理论 + 银行信贷创造

> t4 依赖 t2（法币化）。与 t3（税收）无强依赖，可与 t3 并行或先后实施（下游 t5 需三者都完成）。

### 4.1 改动文件
- `src/main/java/com/example/financialmarket/engine/banking/CentralBank.java`
- `src/main/java/com/example/financialmarket/engine/banking/Bank.java`
- `src/main/java/com/example/financialmarket/engine/banking/BankManager.java`
- `src/main/java/com/example/financialmarket/engine/banking/InflationSystem.java`
- `src/main/java/com/example/financialmarket/engine/banking/CreditSystem.java`
- `src/main/java/com/example/financialmarket/engine/macro/MacroEnvironment.java`
- `src/main/java/com/example/financialmarket/engine/MarketEngine.java`
- 新增：`engine/banking/MoneyAggregate.java`（货币总量记账，见 §4.3）

### 4.2 需求

1. **银行信贷创造（货币乘数）**：
   - 接线 `BankManager.dailyUpdate(long currentDay, Government government)`：在 `MarketEngine.tick` 中按交易日（`newDay > currentDay` 处，`MarketEngine.java:179-194`）调用，使银行每日更新。
   - 修正 `Bank.issueLoan`（`Bank.java:82-92`）：放贷时**在准备金约束下派生存款**，并显式计入货币乘数——`贷款金额 / (准备金率)` 的派生上限受 `reserves + vaultCash` 约束（已有 `if (reserves+vaultCash < required) return null`），但需补充：放贷后 `totalDeposits += amount` 且 **M1/M2 记账随之增加**（见 §4.3）。
   - 实现 `Bank.repayLoan`（当前空，`Bank.java:94`）与 `Bank.dailyUpdate`（当前空，`Bank.java:95`）：还款/坏账时从存款侧扣减 M2，`dailyUpdate` 计提坏账、按 `interbankMarket` 与准备金短缺找央行（`manageAssets` 已有 emergencyLending 雏形）。
   - **货币乘数验证点**：给定准备金率 `r`，连续放贷后 `M2 ≈ M0 × (1/r)`（在贷款需求与准备金充足前提下收敛）。

2. **M1/M2 分层记账（核心，新增 `MoneyAggregate`）**：
   - 新增 `engine/banking/MoneyAggregate.java`，提供：
     - `getM0()` = `centralBank.baseMoney`
     - `getM1()` = Σ玩家 `PlayerEconomy.cash`（流通现金）+ Σ银行**活期**存款
     - `getM2()` = `M1` + Σ银行**定期/储蓄**存款 + Σ货币市场类产品余额
   - 活期 vs 定期分类：以 `Bank.depositAccounts`（活期/随时支取）计入 M1；`SavingsProduct` 中非活期（`isDemand==false`，如"一年定期"）计入 M2 的定期层。实施时若产品余额未在 `Bank` 内聚，则 `MoneyAggregate` 从 `BankManager`/产品数据汇总。
   - `MoneyAggregate.update()` 每交易日调用一次，计算各层总量并缓存，供 `InflationSystem`/UI/验证读取。
   - **禁止过度遍历**：每交易日 O(玩家+银行) 汇总一次即可（玩家数×银行数常数级），不逐 tick 全量扫。

3. **通胀模型基于 M2 增长**：
   - 接线 `InflationSystem.update(BankManager, CommodityMarket)`（当前未调用）：在 `MarketEngine.tick` 每交易日调用。
   - 改进：`moneyGrowth = (M2_t − M2_{t-1}) / M2_{t-1}`（用 `MoneyAggregate.getM2()`），`inflationRate = 0.6*moneyGrowth + 0.4*价格指数变化`（沿用现有式 `InflationSystem.java:30`，但 M2 改由 `MoneyAggregate` 提供完整分层值）。保持 `inflationRate ∈ [-0.10, 0.50]`。
   - 现有 `priceIndex` 逻辑（`InflationSystem.java:19-28`）保留。

4. **货币政策传导（利率/准备金/OMO → 经济）**：
   - `MacroEnvironment`（`engine/macro/MacroEnvironment.java`）升级：新增 `setPolicyRate(double)` / `setReserveRequirement(double)` 入口，使**央行政策利率驱动宏观利率均值 `r_bar`**（替换 `MacroEnvironment.java:22` 的固定 `r_bar=0.035`），`reserveRequirement` 影响流动性乘子（`getLiquidityMultiplier`，`MacroEnvironment.java:143`）。
   - `MarketEngine.tick` 在 `MacroEnvironment.step`（`MarketEngine.java:205`）之前，把 `centralBank.getDiscountRate()` 写入 `macroEnvironment.setPolicyRate(...)`，使**政策利率 → 宏观 r → discountRate/fairValue/GBM 漂移 → 成交价**形成传导链（对接 `MACRO_MODEL.md` §3.2 泰勒规则）。
   - `CentralBank` 增加 `runMonetaryPolicy(long day)`：按简化泰勒规则更新 `discountRate`（`i = r* + π + 1.5(π−π*) + 0.5·y_gap`，π 取 `InflationSystem`，`y_gap` 取 `MacroStateEngine`/周期相位），并周期性 `buyBonds/sellBonds/reverseRepo/settleRepos`（OMO）调整 M0。政策更新频率建议每交易日或每若干交易日一次，O(1)。

5. **持久化（FM-7）**：
   - `MarketEngine.save()` 新增：`CentralBank`（baseMoney/discountRate/reserveRequirement/bankReserves）、`BankManager`（各银行 reserves/vaultCash/totalDeposits/totalLoans/equity/loans/depositAccounts）、`Government`（treasury/totalDebt/taxRate）、`InflationSystem`（moneySupply/priceIndex/inflationRate）、`MoneyAggregate`（各层缓存 + 前一交易日 M2）。
   - `loadInternal` 相应恢复；**旧存档缺字段用默认值重建**（复用 `MarketEngine.java:498` 的 `contains` 兜底模式）。
   - `saveToH2/loadFromH2` 依赖 `save()/load()` 的 NBT blob（`engine_kv.engine_main`），货币字段随 blob 自动持久化，无需新增关系表列（如 UI 需展示再增量加列，对齐 `H2_MIGRATION.md`）。

### 4.3 `MoneyAggregate` 接口草案

```java
public final class MoneyAggregate {
    private double m0, m1, m2;
    private double prevM2;
    public void update(CentralBank cb, Collection<PlayerEconomy> players,
                       Collection<Bank> banks /* + 定期/货币市场产品 */) { ... }
    public double getM0() { return m0; }
    public double getM1() { return m1; }
    public double getM2() { return m2; }
    public double getPrevM2() { return prevM2; }
    public double moneyMultiplier() { return m2 / (m0 <= 0 ? 1 : m0); } // 实际货币乘数
    public double moneyGrowth() { return prevM2 <= 0 ? 0 : (m2 - prevM2) / prevM2; }
}
```

### 4.4 验收（t4 自身）
- `MoneyAggregate` 输出 M0 ≤ M1 ≤ M2（始终成立，且 M1-M0=活期存款、M2-M1=定期层）。
- 固定准备金率下，注入 1 单位 M0 并持续放贷，`M2/M0` 趋向 `1/reserveRequirement`（允许受贷款需求/坏账/准备金短缺扰动，验收给容差如 ±30%）。
- 政策利率上调 → `MacroEnvironment.getDiscountRate()` 上升（宏观传导生效）。
- `./gradlew compileJava` 通过；FM-6/FM-7 成立。

---

## 5. 需求契约：t3 — 税收/纳税锚点（法币信用）

### 5.1 改动文件
- `src/main/java/com/example/financialmarket/engine/banking/Government.java`
- `src/main/java/com/example/financialmarket/engine/MarketEngine.java`
- `attachment/PlayerEconomy.java`（如需记录纳税历史/税前现金）
- `network/packets/RequestGovernmentDataPacket.java` / `GovernmentDataResponsePacket.java`（可选：向 UI 暴露税收/国库）

### 5.2 需求

1. **实际征税（纳税锚点）**：
   - `Government.collectTax(double income)`（`Government.java:51`）由空实现/纯公式改为**实际流转**：在 `MarketEngine.tick` 每交易日（或每 N 日）对全体在线玩家按现金/收入征税，`treasury += taxAmount`、玩家 `eco.setCash(eco.getCash() - taxAmount)`。
   - 税基定义（择一，推荐 A）：
     - **A（推荐）**：按玩家**现金余额**收财富税，`taxAmount = eco.getCash() * taxRate`，`taxRate` 取 `government.getTaxRate()`（默认 0.1）。简单、可复现、直接回笼货币。
     - **B**：按玩家**交易/收入流**征税（如商品卖出收入 `CommodityMarket.sell` 时按 `taxRate` 扣税）。更精细但需在多个买卖点插桩，工作量更大。
   - 实施者可选 A 或 B，并在验收说明写明；推荐先用 A，后续可扩 B。
   - **法币信用意义**：税收使法币成为"可用来缴税的信用凭证"（现代法币的财政背书），同时税收回笼货币、压通胀（与 M2 增长形成对冲）。

2. **国库-现金流转闭环**：
   - 税收进 `treasury`；`Government.spend`（`Government.java:43`）把国库资金注入经济（如向玩家发补贴/回购商品），形成"征税→国库→支出→流通"循环。现有 `spend` 仅扣国库，需在实施时注明支出注入到哪（可先只记账国库减少，不强制回灌，但 t5 验证需能观察国库随税收上升）。

3. **持久化**：`Government.treasury/totalDebt/taxRate` 入 `save()/load()`（FM-7）。

### 5.3 验收（t3 自身）
- 运行若干交易日后，`treasury` 随税收增加，玩家现金随之减少（纳税锚点生效）。
- 税收与 M2 记账一致（税后 M1 流通部分减少）。
- `./gradlew compileJava` 通过；FM-6/FM-7 成立。

---

## 6. 实施顺序与依赖

```
t2（法币化） ──→ t4（M2 + 银行信贷创造，依赖 t2）
t2（法币化） ──→ t3（税收，可并行） ──┐
                                     ├──→ t5（整体验证与构建，依赖 t2/t3/t4）
t4 ────────────────────────────────┘ └──→ t6（评审，依赖 t5）
```

- t3 与 t4 无强依赖，可并行；但都依赖 t2 的法币化基础（现金由央行注入 + M0 记账）。

---

## 7. 持久化与兼容（对齐 H2_MIGRATION.md）

| 项目 | 方案 | 兼容 |
|---|---|---|
| NBT `MarketEngine.save/load` | 新增货币/银行/政府/通胀/记账字段 | 整块 NBT 天然支持加字段；`loadInternal` 用 `contains` 兜底旧存档 |
| H2 `engine_kv.engine_main` | 依赖 `save()/load()` blob，货币字段随 blob 自动持久化 | 无需新增关系表列；如 UI 需展示再加 |
| UI/Web | `GovernmentDataResponsePacket` 字段名/类型不变，仅数值变化；可选新增 M1/M2/税收为增量字段 | 向后兼容默认值 |

---

## 8. 可复现（对齐 ECON_IMPROVEMENT_PLAN §6）

- 所有货币随机源（初始注入、税收浮动、政策冲击）用 `SeedableRandom` 从 `engineSeed` 派生子流。
- `MoneyAggregate` 用确定性累加（无随机），天然可复现。
- 政策 `runMonetaryPolicy` 用确定公式 + 可播种冲击，保证同种子同政策路径。

---

## 9. 验证与验收汇总（t5 使用）

| # | 验收 | 命令/方式 |
|---|---|---|
| 1 | 编译通过 | `./gradlew compileJava` 退出码 0 |
| 2 | 可运行无 NPE/死循环 | server 冒烟，`MarketEngine.tick` 持续推进 |
| 3 | 彻底法币化 | 无固定 32:1 锭兑现金路径；现金可溯源央行注入 |
| 4 | 银行信贷创造 + 货币乘数 | M2/M0 在放贷后趋向 1/准备金率（容差） |
| 5 | M1/M2 分层 | M0≤M1≤M2，差值语义正确 |
| 6 | 税收锚点 | 国库随税收上升、玩家现金随之下降 |
| 7 | 通胀基于 M2 | 通胀随 M2 增长方向变化 |
| 8 | 政策传导 | 加息→宏观 discountRate 上升 |
| 9 | 同种子可复现 | 固定种子两跑货币序列一致 |
| 10 | H2 持久化 | save→load 恢复货币/银行/税收状态 |

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 货币乘数不收敛（贷款需求不足） | FM-3 验收失败 | 验收给 ±30% 容差；t4 设 AI 银行自动放贷基线保证乘数体现 |
| 接线引入 NPE/回归（bankManager 空依赖） | 启动/读档失败 | 复用 `MarketAttachment` 空资产兜底；每接一个子系统单测 |
| 税收导致玩家现金归零 | 玩家体验差 | `taxRate` 上限（如 0.3）；税后保底现金 |
| M2 分层遍历成本 | 主线程卡顿 | 每交易日一次 O(玩家+银行) 汇总，不逐 tick 全量扫 |
| H2 旧存档缺货币字段 | 读档失败 | `loadInternal` `contains` 兜底 + 默认值重建 |

---

## 11. 输入/来源索引

- 宏观模型与传导：`MACRO_MODEL.md`
- 改进方案与 PRNG：`ECON_IMPROVEMENT_PLAN.md`
- P0 落地（已交付）：`ECON_P0_SPEC.md`
- 代码证据：`CurrencySystem.java`、`CentralBank.java`、`Bank.java`、`BankManager.java`、`Government.java`、`InflationSystem.java`、`CreditSystem.java`、`PlayerEconomy.java`、`MarketEngine.java`、`MacroEnvironment.java`
