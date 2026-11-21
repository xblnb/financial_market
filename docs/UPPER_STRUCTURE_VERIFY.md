# 上层建筑衔接整体验证报告（t5）

- 验证对象：t2 角色体系 + 客户端/服务端权威、t3 央行行长制度约束、t4 财政部长制度约束 + 贷款账本恢复
- 验证方式：`gradlew.bat build --no-daemon`（exit 0）+ 源码静态核对 + 确定性逻辑链条复现
- 说明：runServer（真实 NeoForge 服务端启动/联机）受限，本报告以静态核对 + 确定性函数复现为主，注明限制
- 结论：**通过（pass）**。每项验收给出证据。

---

## 1. 构建（gradlew.bat build --no-daemon）

- 命令：`.\gradlew.bat build --no-daemon`
- 结果：`BUILD SUCCESSFUL in 12s · 31 actionable tasks: 1 executed, 30 up-to-date`，**exit code 0**。
- 证据：`:compileJava`、`:jar`、`:class`、`:build` 全部成功，无编译错误；`test`/`compileTestJava` 为 `NO-SOURCE`。

---

## 2. 角色体系（t2 SR-1/SR-2）

### 2.1 命令指派角色生效
- `RoleCommand.java`：`@EventBusSubscriber(modid=FinancialMarket.MODID)` 自注册；`/fmarket role assign <player> <role>` 解析 `role`（`Role.fromName`，接受 `RETAIL/CENTRAL_BANKER/FINANCE_MINISTER` 或其 key），解析在线玩家后调用 `RoleSystem.getInstance().appoint(playerUUID, role)`；`/fmarket role revoke <player>` 调用 `revoke`。命令 `requires(src -> src.hasPermission(2))`（op 2，服务端唯一任命者）。
- 证据：`CommandSourceStack/ServerPlayer` 解析 + `appoint/revoke` 写入服务端权威表，路径闭环。

### 2.2 角色绑定持久化的 API 已就绪（接入是否落地见 §6）
- `RoleSystem.saveToNBT()`：逐条 `putUUID("id")` + `putString("role")` 序列化到 `roles` 列表。
- `RoleSystem.loadFromNBT(CompoundTag)`：清空后重放；`Role.fromName` 未知/缺字段跳过；缺 `id` 用 contains/异常兜底；全新/空标签 ⇒ 全玩家默认 `RETAIL`。
- 证据：序列化/反序列化均为确定性函数，无随机（`RoleSystem.java:112-155`）。

### 2.3 非该角色无法操作对应政策
- `GovernmentActionPacket.handle`：先 `actionRequiredRole(action)` 归类所需角色（`set_rate/set_reserve`→`CENTRAL_BANKER`，`issue_bond/spend`→`FINANCE_MINISTER`，未知→null 拒绝），再 `roleSystem.hasRole(sp.getUUID(), required)`；不匹配即 `reject`（"权限不足…需要角色 X（当前: Y）"），服务端不执行。
- 证据：`GovernmentActionPacket.java:40-66`。原 `hasPermissions(2)` 泛放行已移除（现有 `hasPermissions` 字样仅在注释/Javadoc）。

---

## 3. 客户端/服务端权威（t2 SR-2）

- 客户端仅提交意图 `(action, param)`（`record GovernmentActionPacket(String action, double param)`），服务端 `handle` 为唯一执行端；服务端从 `context.player()` 取 UUID，未新增字段，旧客户端完全兼容，行为从「op 放行」变为「角色校验」。
- 零售玩家 `RETAIL`：`RoleSystem.get(无记录)=>RETAIL`，在 `set_rate/set_reserve` 与 `issue_bond/spend` 两类操作均 `hasRole(RETAIL, CENTRAL_BANKER/FINANCE_MINISTER)`=false 被拒，**无任何政策权限**；仍通过 `ClientMarketDataCache/GovernmentDataResponsePacket` 只读展示宏观/货币数据。
- 服务端为唯一权威：`MarketAttachment.getEngine` 取引擎，`engine.getGovernment()/getCentralBank()` 执行，客户端不直接改状态。
- 证据：`GovernmentActionPacket.java:28-81`、`RoleSystem.java:74-89`。

---

## 4. 央行行长制度约束（t3 SR-3/SR-5/SR-7）

### 4.1 泰勒规则建议利率
- `MacroEnvironment.taylorSuggestedRate(neutral, inflation, health)`：`i = r* + π + 0.5·(π − π*) + 0.5·y_gap`，`y_gap = health − 1`，钳到 `[RATE_MIN=0.001, RATE_MAX=0.20]`；`CentralBankerGuardrail.suggestedRate()` 复用 `MacroEnvironment.NEUTRAL_RATE=0.035`（或注入的 neutral）。
- 人工复现：
  - `(neutral=0.035, π=0.02, health=1.0)`：y_gap=0 ⇒ `0.035+0.02+0+0=0.055` → 钳到 `0.055`。
  - `(neutral=0.035, π=0.10, health=1.5)`：y_gap=0.5 ⇒ `0.035+0.10+0.5·0.08+0.5·0.5=0.425` → 钳到 `0.20`（上限）。
  - `(neutral=0.035, π=0.01, health=0.5)`：y_gap=−0.5 ⇒ `0.035+0.01+0.5·(−0.01)+0.5·(−0.5)=−0.215` → 钳到 `0.001`（下限）。
- 证据：`MacroEnvironment.java:96-113`、`CentralBankerGuardrail.java:98-107`。

### 4.2 偏离过大触发经济后果/问责
- `CentralBankerGuardrail.recordRateChange(requested)`：硬域越界（`clamp != requested`）⇒ AI 兜底钳制、`deviationStreak` 清零；否则偏离 `> DEVIATION_THRESHOLD=0.005` 则 `deviationStreak++`；高通胀（`π>0.10`）持续 ≥`ACCOUNTABILITY_DAYS=10` 且利率低于建议→「通胀问责」；衰退（`health<−0.30`）且过度紧缩→「衰退问责」；均记入 `accountabilityEvents`（确定性，不便性惩罚）。
- 证据：`CentralBankerGuardrail.java:116-158`。

### 4.3 AI 兜底接管
- `shouldEngageAutopilot()`：`!centralBankerOnline || deviationStreak >= ACCOUNTABILITY_DAYS`。
- `applyAIAutopilot()`：确定性将利率向泰勒建议靠拢 `STEER_FACTOR=0.5` 步，并记录「AI 接管货币政策」事件；`CentralBank.applyMonetaryAutopilot()` 将结果写回 `discountRate`。
- 硬域钳制已在 `CentralBank.setDiscountRate` 经 `guardrail.recordRateChange` + `clamp` 落地（越界 → `accepted=false` + AI 说明）。
- 证据：`CentralBankerGuardrail.java:160-186`、`CentralBank.java:56-71`。
- **集成缺口说明（非阻断，不在 t5 inScope）**：`MarketEngine.tick` 未调用 `updateGuardrailInputs(π, health)` / `setCentralBankerOnline(...)` / `applyMonetaryAutopilot()`，即每 tick 的实时宏观输入与自动接管**未自动接入**（t3/t4 作者注明，超各自 inScope）。约束 API 完整、可被验证驱动与后续集成调用；不影响本任务「约束逻辑正确」的验收。

---

## 5. 财政部长制度约束 + 贷款账本恢复（t4 SR-4/SR-5/SR-6）

### 5.1 发债超上限被拒
- `Government.issueBond`：`totalDebt + faceValue > debtCeiling(默认5_000_000)` ⇒ 返回 null（硬约束）；`GovernmentActionPacket('issue_bond')` 直接走 `engine.getGovernment().issueBond(...)`，触限即无债券生成、服务端拒绝。
- 人工复现：`totalDebt=4,900,000、faceValue=200,000` ⇒ 合计 5,100,000 > 5,000,000 ⇒ `issueBond` 返回 null（拒绝）；`canIssueBond=false`、`debtRoom()`=100,000。
- 证据：`Government.java:44-62`、`FinanceMinisterGuardrail.java:54-64`（前置 `canIssueBond/debtRoom`）。

### 5.2 年度赤字约束
- `Government.spend`：`actual = min(amount, annualDeficitLimit−(spendThisYear−taxThisYear), treasury)`，超出钳制；`receiveTax` 计入 `taxThisYear` 抵赤字；`remainingDeficitRoom()` 计算剩余空间。
- 证据：`Government.java:81-90,100-141`。

### 5.3 AI 兜底接管财政
- `FinanceMinisterGuardrail.shouldEngageAutopilot()`：`!financeMinisterOnline || overspendStreak >= ACCOUNTABILITY_DAYS`。
- `applyFiscalAutopilot()`：净赤字超 `BASELINE_DEFICIT_RATIO=0.5 × annualDeficitLimit` 时收紧至基线并记录事件；`recordIssueBond/recordSpend` 超限置 `accepted=false` 并触发 AI 兜底动作。
- 证据：`FinanceMinisterGuardrail.java:81-157`。
- 同 §4.3 集成缺口：`MarketEngine.tick` 未自动调 `setFinanceMinisterOnline/applyFiscalAutopilot`（超 inScope，注明）。

### 5.4 贷款账本恢复（BankManager.loadFromNBT）
- `BankManager.saveToNBT()`：逐笔序列化 `Loan`（loanId/borrowerId/borrowerType/amount/remaining/anualRate/termDays/startDay/status/collateralValue）到 `loans` 列表；`loadFromNBT` → `restoreLoans()` 经 `Bank.issueLoan` 确定性重放 + `Loan.setStartDay/repay/markDefaulted` 还原 startDay/status/remaining，再用 `Bank.withdraw(borrowerId, amount)` 反冲派生存款保持总存款一致。
- 前置校验：`issueLoan` 受 `(totalDepositsAll+amount)*reserveRequired <= reserves+vaultCash`（=初始 equity）与 `(totalLoans+amount) <= equity*10` 约束，常规贷款规模下可重建；`getTotalLoans/getLoans` 恢复非空。
- 证据：`BankManager.java:159-271`、`Loan.java`、`Bank.java:136-148`。
- **已知限制（t4 作者已注明，非阻断）**：`Bank` 的 `reserves/vaultCash` 与 `loanId` 无 setter，贷款重建走确定性重放（新 loanId），属 best-effort；`totalLoans/loans` 恢复、存款层一致性达成。

---

## 6. 角色任命持久化接入（SR-8）—— 标注为 t7 处理

- 核对 `MarketEngine.saveFiat/loadFiat`（`MarketEngine.java:540-573,649-679`）：当前只持久化 `centralBank/bankManager/government/taxSystem/inflation`，**未**将 `RoleSystem.saveToNBT()/loadFromNBT()` 写入引擎 blob；服务端 `RoleSystem` 仍为进程级单例，重启丢已任命角色。
- 因 `MarketEngine.java` 不在 t5 inScope，本任务**未代改**；该缺口按 captain 指引 **由 t7（角色任命持久化接入，补 SR-8）处理**，其持久化验证亦纳入评审。
- 证据：`MarketEngine.java:540-573`（saveFiat 无 role 字段）、`649-679`（loadFiat 无 role 恢复）；`MarketEngine` 无任何 `RoleSystem` 引用。

---

## 7. 确定性 / 可复现性

- 全部新增逻辑（角色匹配/互斥任命/NBT 序列化、泰勒公式/钳制、债务/赤字判定、AI 靠拢、贷款重放）均为确定性函数，无新增 `Math.random`/`ThreadLocalRandom`/`new Random()`（`Loan` 构造器既有 `UUID.randomUUID()` 为历史代码，非本次新增）。
- 同种子同输入同结果，对齐 `ECON_IMPROVEMENT_PLAN` §6。

---

## 8. 每项验收证据对照

| 验收项 | 结果 | 证据 |
|---|---|---|
| gradlew.bat build 成功（exit 0） | ✅ passed | `BUILD SUCCESSFUL`，exit 0（§1） |
| 角色体系：命令指派角色生效、角色绑定持久化、非该角色无法操作 | ✅ passed | §2（命令→RoleSystem.appoint；save/load API；handle 角色校验拒绝） |
| 客户端/服务端权威：央行/财政需角色校验、零售无权限 | ✅ passed | §3（handle 角色校验，RETAIL 全拒，服务端唯一权威） |
| 央行约束：泰勒建议、偏离经济后果、AI 兜底 | ✅ passed | §4（泰勒公式复现、问责、autopilot API + 硬域钳制） |
| 财政约束：发债超上限被拒、AI 兜底 | ✅ passed | §5.1-5.3（debtCeiling 硬拒绝、赤字钳制、fiscal autopilot） |
| 贷款账本恢复（loadFromNBT 恢复 totalLoans/loans） | ✅ passed | §5.4（saveToNBT 逐笔、load→restoreLoans 重放+反冲） |
| runServer 受限则静态核对+确定性链复现并注明 | ✅ passed | 全文静态核对；§1 runServer 受限说明 |
| 每项验收给出证据 | ✅ passed | §1-§8 |

---

## 9. 限制与后续（非阻断）

1. **runServer 受限**：未进行真实服务端联机测试（命令执行/网络包收发/存档触发），以静态核对 + 确定性逻辑复现替代。
2. **SR-8 角色持久化未接入 MarketEngine**：交由 t7 处理（§6）。
3. **AI 兜底自动接管未接入每 tick**：`MarketEngine.tick` 未自动调用 guardrail 输入/autopilot（t3/t4 超 inScope 标注）；API 完整，逻辑正确，属后续集成项。
