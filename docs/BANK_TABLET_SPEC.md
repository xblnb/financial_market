# BANK_TABLET_SPEC — 银行平板（BankTabletScreen）完善需求规格（需求契约，供 engine-dev 实施、test-engineer 验证、reviewer 评审）

> - 角色：architect（方案架构 / 银行平板需求规格）为任务 t1 产出本契约。
> - 输入：团队 Goal + 现有源码审计（`Bank`/`BankManager`/`CentralBank`/`BankCreatePacket`/`RequestBankDataPacket`/`BankDataResponsePacket`/`BankDepositPacket`/`BankLoanPacket`/`BankTabletScreen`/`BankTabletItem`/`ModMessages`）。
> - 前置基座（已交付、语义不可改）：`ECON_FIAT_M2_SPEC.md`（法币 M0/M1/M2 / 央行 / 银行派生存款）、`BANKING_TRADE_SPEC.md`（4 家商业银行 + 储蓄卡 + 贸易方块）、`H2_MIGRATION.md`（H2 主真相 + NBT 附件镜像双写）、`ECON_REMAINDER_SPEC.md`/`ECON_IMPROVEMENT_PLAN.md`（黄金储备账户 / 央行行长约束 / 公开市场操作）。
> - 用途：engine-dev 按本规格把当前「50 行只读」的 `BankTabletScreen` 做成**完整银行管理界面**；test-engineer 按本规格做整体验证（编译/可复现/持久化/交互冒烟）；reviewer 按本规格验收。
> - 约束：NeoForge 1.21.1 主线程 tick 驱动；**时间权威用引擎 tick 计数器（`MarketEngine.tickCounter`）**，非墙钟。客户端 `BankTabletScreen` 是**纯展示 + 请求**层：所有状态变更都经 packet 发到服务端，由服务端在 `MarketAttachment.getEngine(sp.serverLevel())` 上调用既有确定性引擎方法，客户端不做权威决策。
> - **可复现性硬约束（继承 P0/A0-3）**：所有银行/央行状态迁移调用既有确定性方法；新增随机（若有）必须走 `SeedableRandom`（SplitMix64）子流并登记，**禁用 `ThreadLocalRandom` / 裸 `new Random()` / `Math.random()`**。银行 ID 生成沿用 `BankCreatePacket` 既有确定性规则（`"BANK_" + UUID.substring(0,8)`），无随机。
> - **兼容边界硬约束（不动已定稿语义）**：
>   1. **不修改**法币 M2 / 央行 / 银行派生存款 / 黄金储备账户核心公式（`computeM1/M2/M0`、`rebalanceReserves`、`issueLoan` 信用派生、`goldMarketBuy/Sell`、`setBaseMoney`）。
>   2. **不修改**已实现的 `BankCreatePacket` 发卡银行逻辑（银行归玩家所有、扣现金、`registerBank`）。
>   3. **新增能力只做「接入点 + 展示/命令层」**：玩家开设央行、设政策、存款/贷款/发钞全部**委托**既有 `CentralBank`/`Bank` 方法，不引入新货币公式、不新开 M 口径。
>   4. **不改** `Bank`/`CentralBank` 既有公开签名；新增 getter/公开入口（如 `getDepositBalance`）按需增量，不影响既有调用。

---

## 0. 目标与全局验收（对应团队 Goal）

> 把银行平板从「只读看板」变成**玩家管理自己发行的货币体系的完整界面**：①玩家开设商业银行（发 `BankCreatePacket`，银行归玩家所有）；②玩家可开设央行并设定政策；③管理玩家发行的货币（存款/贷款/利率/准备金/发钞）。复用现有银行/央行引擎确定性方法，保持确定性可复现、H2 持久化兼容、不改已交付经济引擎/法币 M2/黄金核心语义。完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| BT-0 | 编译通过 | `./gradlew compileJava` 退出码 0；`./gradlew build` 出包 | 每项实施 + t5 |
| BT-1 | 可复现性不回归（继承 P0） | 同 `engineSeed` 跑固定 tick，银行/央行状态迁移序列两次逐元素一致；新增随机全走 `SeedableRandom`；`grep` 无新增 `Math.random`/`new Random`/`ThreadLocalRandom` | 实施 + t5 |
| BT-2 | 玩家开设商业银行 | 银行平板内可**开设商业银行**：填银行名 + 初始权益 → 发 `BankCreatePacket` → 服务端扣玩家现金、建 `Bank`（`ownerUuid=玩家`）、`registerBank`；平板展示「我的银行」列表（含 owner 标识） | 实施 + t5 |
| BT-3 | 玩家开设央行 | 平板内可**开设央行**：首次开设即把当前世界 `BankManager.centralBank` 标记为「玩家央行」并写入 `ownerUuid`/`createdBy`/`createdTick`；已存在玩家央行时拒绝重复开设（提示） | 实施 + t5 |
| BT-4 | 央行设定政策 | 玩家（若其为央行拥有者）可在平板设定：贴现率 `setDiscountRate`（含 Guardrail 钳制）、准备金率 `setReserveRequirement`、基础货币基数 `setBaseMoney`/`injectBaseMoney`（发钞）；非拥有者只读 | 实施 + t5 |
| BT-5 | 管理玩家发行的货币（存款/贷款） | 平板内玩家可：向银行**存取款**（`Bank.deposit`/`withdraw` + 现金 ↔ 活期存款，M1 总量不变）、**申请贷款**（`Bank.issueLoan` 信用派生，M2 内生扩张）、**还款**（`Bank.repayLoan`）、查看自己的存贷款列表 | 实施 + t5 |
| BT-6 | 银行利率/准备金管理 | 玩家可管理**自己所有**商业银行的利率（`primeRate`/`depositRate`）与参数，走既有 setter/方法；只改自己名下银行，默认 AI 银行只读 | 实施 + t5 |
| BT-7 | 界面交互 | `BankTabletScreen` 含清晰分页/分栏：银行列表（默认 AI 行 + 玩家行）、我的存贷款、央行政策（创建/设定）、操作后的**中文结果提示**；所有操作经 packet 请求服务端 | 实施 + t5 |
| BT-8 | H2 / NBT 持久化不回归 | 玩家开设的银行/央行归属（`ownerUuid`/`createdBy`/`createdTick`）随 `MarketEngine.save()/loadInternal()` blob（`engine_kv.engine_main` + NBT 镜像）序列化/恢复；H2 镜像表新增列用 `ADD COLUMN IF NOT EXISTS` 前向兼容；旧存档缺字段用 `contains` 兜底，读档不失败 | 实施 + t5 |
| BT-9 | 隔离（不回归） | `banking.enabled=false` 时银行平板回落到「只读看板」（不得注册新包/新交互），与既有基线一致；玩家已建立的银行/央行状态不丢失 | 实施 + t5 |
| BT-10 | 边界与安全 | 服务端**权威校验**：开设银行扣现金不足则拒绝；开设央行仅允许一次；设定政策仅允许央行拥有者；存取/贷款金额非法（≤0 或余额不足）全部拒绝且**不改变任何状态**（原子性、确定性）；客户端消息不能伪造成功 | 实施 + t5 |

**验收流程（t5 落地）**：固定 `engineSeed` 跑固定 tick；走「平板开设商业银行（扣现金 → 建行 → 归玩家）→ 玩家向自己的银行存取款/贷款/还款 → 开设央行（仅一次）→ 设贴现率/准备金率/基础货币发钞 → 查看我的银行/存贷款/央行政策」全流程冒烟；对每次状态迁移断言确定性字段；两次同种子运行校验状态序列哈希一致（BT-1）；验证 H2 读档后银行/央行归属完整恢复（BT-8）；开关隔离与基线对比（BT-9）；越权/非法输入被权威拒绝且状态不变（BT-10）。

---

## 1. 总设计原则（约束所有实现）

1. **纯增量、可开关**：新增逻辑集中在 `network/packets/`（新增交互包）+ `screen/BankTabletScreen.java`（UI）+ `use` 校验 + `MarketEngine`/`BankManager`/`CentralBank` 的**只读接入点**。`banking.enabled=false` 时关闭新增交互，回落到只读看板基线（BT-9）。
2. **客户端只是请求者，服务端是权威**：平板 UI 不直接改引擎状态；每次操作发包，服务端 `enqueueWork` 内调 `MarketAttachment.getEngine(sp.serverLevel())`，在**服务端**执行确定性引擎方法并回传结果（成功/失败 + 中文提示），客户端只据回传刷新（BT-7/BT-10）。
3. **复用既有确定性方法**：存款/取款/放贷/还款走 `Bank.deposit/withdraw/issueLoan/repayLoan`；央行政策走 `CentralBank.setDiscountRate/setReserveRequirement/setBaseMoney/injectBaseMoney`；开设银行沿用 `BankCreatePacket.handle` 既有流程（扣现金、`new Bank`、`registerBank`）。**不新写货币公式**。
4. **归属权威在服务端**：`Bank.ownerUuid != null` 表示玩家银行；`CentralBank` 增加「玩家归属」标记字段（`playerOwned`/`ownerUuid`/`createdBy`/`createdTick`），缺省 `null`（AI 央行）。所有「我的/非我的」判定在服务端完成。
5. **原子性 + 确定性**：非法金额、余额不足、重复开设、越权设定 → 服务端拒绝且**不改变任何状态**（原子、无随机）；每次状态迁移由 `engineSeed` 确定性驱动。
6. **展示即真相的对账视图**：平板展示的每项数据（银行现金/准备金/存款/贷款/权益/利率、央行 baseMoney/贴现率/准备金率）直接读引擎 getter，不做本地推导，避免与权威状态漂移。

---

## 2. 现状诊断（代码审计结论）

| 类 | 文件 | 现状 | 问题（银行平板视角） |
|---|---|---|---|
| `BankTabletScreen` | `screen/BankTabletScreen.java` | 仅 50 行：构造发送 `RequestBankDataPacket`；`render` 画银行名/利率/现金/我的贷款；`updateBankData` 更新缓存 | **只读、无任何交互**；无按钮/输入框/分页；无开设银行、无央行、无存取款/贷款/发钞入口 |
| `BankTabletItem` | `item/BankTabletItem.java` | `use` 客户端 `setScreen(new BankTabletScreen())` | 打开形式 OK；仅客户端，无需改 |
| `BankDataResponsePacket` | `network/packets/BankDataResponsePacket.java` | `BankInfo(bankId,name,primeRate,depositRate)`；`LoanInfo(bankId,principal,remaining)`；`cash` | **字段过少**：无 owner/equity/reserves/deposits/vaultCash/centralBank 状态/我的银行归属，平板无法展示「我的银行」「央行政策」 |
| `RequestBankDataPacket` | `network/packets/RequestBankDataPacket.java` | 服务端枚举 `getAllBanks()` 建 `BankInfo`；按 `sp.getUUID()` 归并贷款 | 数据齐全但**只组装精简字段**；无央行状态、无「我的银行」区分 |
| `BankCreatePacket` | `network/packets/BankCreatePacket.java` | 已实现：扣现金 `initialEquity` → `new Bank(bankId, name, sp.getUUID(), ...)` → `registerBank`；`bankId = "BANK_"+UUID.substring(0,8)` | **已可发卡银行、归玩家所有**（Goal ①已具备后端）；平板无 UI 触发入口 |
| `BankDepositPacket` | `network/packets/BankDepositPacket.java` | **空 stub**（`public class BankDepositPacket {}`） | 无存款/取款 packet，需实现 |
| `BankLoanPacket` | `network/packets/BankLoanPacket.java` | **空 stub**（`public class BankLoanPacket {}`） | 无贷款/还款 packet，需实现 |
| `BankManager` | `engine/banking/BankManager.java` | `getBank(id)/getAllBanks/getCentralBank/registerBank`；`saveToNBT/loadFromNBT` 持久化银行货币状态 | 已有 `registerBank` 接受 `Bank`；`getCentralBank` 返回单例；玩家央行归属字段未持久化 |
| `Bank` | `engine/banking/Bank.java` | `deposit/withdraw/issueLoan/repayLoan/dailyUpdate/manageAssets`；`getVaultCash/getReserves/getTotalDeposits/getTotalLoans/getEquity/getPrimeRate/getDepositRate/getOwnerUuid` | 具备全部管理 getter；`getDepositBalance(UUID)`（按客户查余额）**尚缺**（供平板显示我的存款） |
| `CentralBank` | `engine/banking/CentralBank.java` | `setDiscountRate`(Guardrail)、`setReserveRequirement`、`setBaseMoney`、`injectBaseMoney`、`buyBonds/sellBonds`、`goldMarketBuy/Sell`；getter 齐全 | **无「玩家归属」字段**；单例、无 owner；需增量增加 `playerOwned/ownerUuid/createdBy/createdTick` 标记（不改公式，仅归属/展示） |
| `ModMessages` | `network/ModMessages.java` | 注册 `BankCreatePacket`(c2s)；`BankDataResponsePacket`(s2c) 回调**只路由到 `FinancialTabletScreen`**（`:109-113` 的 `instanceof FinancialTabletScreen`） | **s2c 路由错漏**：`BankDataResponsePacket` 更新的是金融平板而非 `BankTabletScreen`，需补 `instanceof BankTabletScreen` 分支；新增交换包需在此注册 |

**根因**：①平板只有只读展示，无「开设银行/央行/存取/贷款/发钞/设政策」交互；②`BankDataResponsePacket` 数据字段不足以支撑「我的银行」「央行政策」展示；③`BankDepositPacket`/`BankLoanPacket` 为空 stub，无交互包；④`CentralBank` 无玩家归属；⑤s2c 路由错落到金融平板。本规格在**不动既有引擎公式**前提下补齐这些接入点与 UI。

---

## 3. AS-1 数据层扩展：BankDataResponsePacket + RequestBankDataPacket（配合 UI）

> 目标：让平板拿到**完整、对账正确**的数据（含归属与央行状态），客户/服务端不做本地推导。

### 3.1 改动文件
- `network/packets/BankDataResponsePacket.java`：扩充 `BankInfo`（增加 `owner`/`isMine`/`equity`/`vaultCash`/`reserves`/`totalDeposits`/`totalLoans`），新增 `CentralBankInfo`（`discountRate`/`reserveRequirement`/`baseMoney`/`playerOwned`/`ownerUuid`），`banks` 保持 `Map<String,BankInfo>`；`StreamCodec` 同步扩字段（前向兼容：客户端旧声明的旧字段仍可解，新字段用 `buf.readUtf`/`readDouble`/`readBoolean` 追加）。
- `network/packets/RequestBankDataPacket.java`：服务端组装时读 `Bank.getOwnerUuid()` 判断 `isMine`（`ownerUuid != null && ownerUuid.equals(sp.getUUID())`）；读 `BankManager.getCentralBank()` 组装 `CentralBankInfo`；玩家已持有的存款额（按 `Bank.getDepositBalance(sp.getUUID())` 若新增）并入。

### 3.2 字段语义（服务端一次性组装，客户端只显示）
- `BankInfo`：`bankId`、`name`、`primeRate`、`depositRate`（既有）；新增 `ownerUuid`（`toString`）、`isMine`（归属玩家）、`equity`、`vaultCash`、`reserves`、`totalDeposits`、`totalLoans`、`depositBalance`（当前玩家在该行活期存款）。
- `CentralBankInfo`：`discountRate`、`reserveRequirement`、`baseMoney`、`theoreticalM2Ceiling`、`playerOwned`（是否玩家央行）、`ownerUuid`（若玩家央行）。
- 注意：`LoanInfo` 已有，保留；如需展示「我的存款/贷款」可按需加 `bankName`。

### 3.3 验收
- 平板收到响应后能显示：每行银行的权益/准备金/存款/贷款/利率（读引擎 getter 值）；「我的银行」（`isMine=true`）突出显示；我的活期存款总额（各 `depositBalance` 求和）。
- 央行面板显示贴现率/准备金率/基础货币/理论 M2 上限，及是否玩家央行。
- `BankDataResponsePacket` 新增字段不与旧字段冲突；服务端组装确定性（无随机）。

---

## 4. AS-2 玩家开设商业银行（Goal ①）

> 目标：平板内可直接开设商业银行，银行归玩家所有，并展示于「我的银行」。

### 4.1 接入（复用既有 `BankCreatePacket`）
- 平板「开设银行」按钮 → 弹出文本输入（银行名）+ 数值输入（初始权益）→ 调 `new BankCreatePacket(name, equity).send()`。
- 服务端 `BankCreatePacket.handle` **不改**（已实现扣现金 → `new Bank` → `registerBank`），仅需确认其确定性、原子性（现金不足拒绝）。为满足 BT-10 的原子性：`handle` 中 `eco.getCash() >= packet.initialEquity()` 已在更新前判定，不足则整体不动（已满足）。

### 4.2 展示与管理
- 服务端下发 `BankInfo.isMine`；平板「我的银行」页列出玩家名下银行，选中后可进入管理（见 AS-6 利率/参数）。

### 4.3 验收
- 平板开设银行：填名 + 权益 → 服务端扣现金 → 新建 `Bank`（owner=玩家）→ `registerBank`；平板刷新后出现且 `isMine=true`（BT-2）。
- 现金不足时服务端拒绝，平板提示「现金不足」，银行未创建、现金未变（BT-10）。
- 同种子重复操作，银行 ID、归属、初始状态逐值一致（BT-1）。

---

## 5. AS-3 玩家开设央行（Goal ②前半）

> 目标：玩家可开设央行；该世界仅允许一次玩家央行；沿用单例 `CentralBank` 并加归属标记。

### 5.1 CentralBank 归属字段（增量，不改公式）
- 在 `CentralBank` 增加：`UUID playerOwner = null;`、`String createdBy = null;`、`long createdTick = 0;`（或等价），配套 getter `isPlayerOwned()`/`getPlayerOwner()`/`getCreatedBy()`/`getCreatedTick()` 与包内 setter `setPlayerOwner(...)`。
- **不改** `baseMoney/discountRate/reserveRequirement/gold*` 任何公式；归属字段仅用于展示与权限。

### 5.2 开设央行交互
- `BankManager` 或 `MarketEngine` 提供 `boolean tryCreatePlayerCentralBank(UUID owner, String createdBy, long tick)`：若 `centralBank.isPlayerOwned()` 已为 true → 返回 false（拒绝重复）；否则置归属并返回 true。**确定性、原子、无随机**。
- 平板「开设央行」按钮 → 发新 packet（如 `CentralBankCreatePacket`）→ 服务端调用上述；成功回传平板上方显示「央行已由您开设」，失败提示「央行已存在」。

### 5.3 验收
- 首次开设成功，`centralBank.isPlayerOwned()==true`、`createdBy==玩家`、`createdTick==当前`（BT-3）。
- 二次开设被拒，状态不变（BT-10）。
- 归属字段随 `save()/loadInternal()` 持久化（BT-8）。

---

## 6. AS-4 央行设定政策（Goal ②后半 + 发钞）

> 目标：央行拥有者可在平板设定贴现率/准备金率/基础货币（发钞），委托既有 `CentralBank` 方法。

### 6.1 交互清单（服务端权威校验「仅央行拥有者」）
| 操作 | 服务端方法 | 说明 |
|---|---|---|
| 设贴现率 | `centralBank.setDiscountRate(r)` | 走 Guardrail 钳制 `[0.001,0.20]`；返回 `GuardrailResult`（含 effectiveRate）供提示 |
| 设准备金率 | `centralBank.setReserveRequirement(r)` | 内部钳制 `[0.01,0.25]` |
| 发钞（基础货币注入） | `centralBank.injectBaseMoney(amt)` | 仅 `amt>0` 注入，baseMoney（M0）增加；**不改 M2 公式** |
| 直接设基础货币基数额 | `centralBank.setBaseMoney(amt)` | 仅 `amt>=0` |

- 新 packet（如 `CentralBankPolicyPacket(id, action, value)`）→ 服务端校验：当前玩家是否央行拥有者；非拥有者拒绝（提示）；对应方法返回结果回传。

### 6.2 发钞边界
- 发钞**只委托 `injectBaseMoney`**：`baseMoney` 增、`theoreticalM2Ceiling`(`baseMoney/reserveRequirement`) 相应提高，属既有权衡语义，不改公式（BT-1/BT-10）。不发实物、不开新 M 口径。

### 6.3 验收
- 央行拥有者能设置贴现率/准备金率/基础货币，`CentralBank` 各 getter 反映新值（BT-4）。
- 非拥有者调用被拒，状态不变，平板提示（BT-10）。
- 贴现率越界被 Guardrail 钳制（返回 effectiveRate）而非崩溃（BT-10）。
- 同种子重复操作，policy 状态序列一致（BT-1）。

---

## 7. AS-5 管理玩家发行的货币：存款/贷款/还款（Goal ③）

> 目标：玩家经平板管理自己的货币存贷，委托既有 `Bank` 方法，保持 M1/M2 分层记账不回归。

### 7.1 操作与委托
| 操作 | 服务端方法 | 说明 |
|---|---|---|
| 存款（现金→活期） | `bank.deposit(owner, amt)` + `eco.setCash(cash−amt)` | M1 现金→活期转移，总量不变；`amt≤0` 或现金不足拒绝 |
| 取款（活期→现金） | `bank.withdraw(owner, amt)` + `eco.setCash(cash+amt)` | 余额/库存现金不足返回 false 拒绝 |
| 贷款 | `bank.issueLoan(owner, "PLAYER", amt, rate, termDays, collateral)` | 信用派生（借户活期增加，M1/M2 扩张）；受 `equity*10` 上限与准备金纪律约束 |
| 还款 | `bank.repayLoan(loanId, amt)` | 收回本金，存款收缩；返回实还额 |

- `Bank.deposit`/`withdraw`/`issueLoan`/`repayLoan` 已实现，直接复用。需新增 `Bank.getDepositBalance(UUID)` 返回 `depositAccounts` 缺省 0（供平板显示我的存款）。
- 新增交互包：`BankDepositPacket`（原空 stub）/ `BankLoanPacket`（原空 stub）实现为可用 packet，或新增 `BankWithdrawPacket`/`BankRepayLoanPacket`（按实施需要拆分）。服务端调用后回传结果 + 重新下发 `RequestBankDataResponse` 以刷新平板。

### 7.2 验收
- 存款后 `computeM1`（现金+活期）总量不变；活期存款层 `depositAccounts[owner]` 增加（BT-5 + FM-5 不回归）。
- 贷款后借户活期存款增加、`totalLoans` 增加、`computeM2` 上升（信贷派生）；还款后反向（BT-5）。
- 非法/不足全部原子拒绝、状态不变（BT-10）。
- 同种子两次，存贷款状态序列一致（BT-1）。

---

## 8. AS-6 角色与权限隔离（我的银行 / 我的央行）

> 目标：平板只允许玩家管理**自己**的银行与央行；默认 AI 银行（owner=null）与 AI 央行只读展示。

- 判定规则（服务端）：
  - `Bank.isMine` = `getOwnerUuid()!=null && getOwnerUuid().equals(sp.getUUID())`。
  - 央行是否 `isMyCentralBank` = `centralBank.isPlayerOwned() && centralBank.getPlayerOwner().equals(sp.getUUID())`。
- 管理权限：开央行、设政策、管理名下银行利率/参数仅限对应拥有者；否则服务端拒绝且提示。
- 默认 4 家 AI 银行（owner=null）在平板为只读列表（任何玩家可查、不可控）。

**验收**：非拥有者接触其他玩家银行/央行操作全部被拒且状态不变；AI 银行只读（BT-10）。

---

## 9. AS-7 银行平板 UI（Goal 交互）

> 目标：把 `BankTabletScreen` 做成可交互分页界面。

### 9.1 结构（建议）
- **页签/分栏**：`银行列表`（默认 AI 行 + 我的银行，选中切换）、`我的存贷款`（存款/贷款/还款入forms）、`我的银行`（开设银行 + 管理名下银行利率）、`央行`（开设央行 + 政策设定）、`操作结果`（最近操作中文提示）。
- **控件**：`AbstractWidget` 按钮 + 文本/数值输入框（如 `EditBox`），点击/回车触发发包。所有控件只发包，不改本地状态。
- **结果提示**：服务端回传成功/失败中文提示，平板在底部显示。

### 9.2 包路由（修正 s2c 错漏）
- `ModMessages` 中 `BankDataResponsePacket` 的 s2c 回调：当前只 `instanceof FinancialTabletScreen`；**补** `instanceof BankTabletScreen screen` 分支（调用其 `updateBankData`），否则平板收不到刷新。
- 新增交互包（c2s）在 `ModMessages.register` 注册 `playToServer`。

### 9.3 验收
- 平板有上述交互控件，点击/输入能触发发包；服务端处理后平板刷新结果提示与数据（BT-7）。
- 无交互/只读展示不因 UI 改动回归（BT-9 baseline）。

---

## 10. 持久化（BT-8）

- 玩家银行：`BankCreatePacket` 建行已由 `BankManager.saveToNBT/loadFromNBT` 持久化银行货币状态；但**玩家归属** `ownerUuid` 需随 blob 序列化（在 `saveToNBT` 补 `bt.putUUID("ownerUuid", bank.getOwnerUuid())`，缺省 null 时跳过；`loadFromNBT` 对已注册 bank 可选读回 `ownerUuid`——注意 `Bank.ownerUuid` 为 final 无 setter，**改为在 save/load blob 中记录「玩家银行ID集合」并由 `BankManager.registerBank` 重放归属**，或在 Bank 增补归属 setter，二者择一，以不破坏 `final` 与既有公式为准——实施时按不改公式原则选实现）。
- 玩家央行归属字段：随 `MarketEngine.save()/loadInternal()` blob + NBT 镜像持久化；`ADD COLUMN IF NOT EXISTS` 前向兼容。
- `CentralBank` 本身持久化沿用现有机制；仅归属字段（`playerOwner`/`createdBy`/`createdTick`）并入 blob。
- 旧存档缺字段用 `contains` 兜底，不失败。

**验收**：读档后玩家银行归玩家（`isMine` 正确）、玩家央行归属完整（BT-8）；新建银行读档后仍存在且 count 不膨胀。

---

## 11. 验证（t5）

- 编译：`./gradlew compileJava` 退出码 0；`./gradlew build` 出包（BT-0）。
- 可复现：固定 `engineSeed` 跑固定 tick；两次运行「开银行/存取/贷款/还款/开央行/设政策/发钞」状态序列哈希一致；全仓 grep 无新增 `Math.random`/`new Random`/`ThreadLocalRandom`（BT-1）。
- 隔离：`banking.enabled=false` 时平板回落只读，无新包注册；基线一致（BT-9）。
- 持久化：H2 读档后玩家银行/央行归属完整恢复（BT-8）。
- 边界：越权/非法/余额不足原子拒绝、状态不变（BT-10）。
- 与既有基座协同：M1/M2/M0、黄金储备账户、Guardrail 不回归（抽查 `BankManager.computeM1/M2`、`CentralBank.goldMarketBuy/Sell`、`setDiscountRate` 返回值）。

---

## 12. 非目标 / 明确不做（防止范围蔓延）

- **不新增**货币公式或 M× 口径：发钞仅委托 `injectBaseMoney`；不放宽/改写 `computeM0/M1/M2`。
- **不改**黄金储备账户语义（`goldMarketBuy/Sell`、`goldCash/baseMoney` 隔离）；平板若展示黄金仅只读。
- **不改** `BankCreatePacket`、`Bank.deposit/withdraw/issueLoan/repayLoan/dailyUpdate/manageAssets`、`CentralBank.setDiscountRate/setReserveRequirement` 既有公式。
- **不做**玩家之间银行/央行的跨玩家转账/交易撮合（超出 Goal）；仅玩家管理自有的行与币。
- **不重写**已定稿 `FinancialTabletScreen`/A 股/期货/商品/公司；仅在 `BankDataResponsePacket` 回调补 `BankTabletScreen` 路由，不动金融平板逻辑。
- **不做**非确定性随机（除既有 `SeedableRandom` 子流）。
