# SUPERSTRUCTURE_ROLE_SPEC — 上层建筑角色体系与客户端/服务端权威需求契约

> - **产出**：architect（角色体系/客户端-服务端架构）为任务 t1（requirements）产出。
> - **输入**：`ECON_FIAT_M2_SPEC.md`（法币+M2 已交付：央行利率/准备金/M0、政府税收/支出、M1/M2、政策传导）、`ECON_IMPROVEMENT_PLAN.md`（架构与可复现约定）＋ 当前源码审计（本规格对照实际源码给出文件/行号/方法签名）。
> - **用途**：engine-dev 按本规格实现 t2（角色体系＋客户端/服务端权威）、t3（央行行长制度约束）、t4（财政部长制度约束 ＋ `BankManager.loadFromNBT` 贷款账本恢复修复）；test-engineer 按本规格实现 t5（整体验证与构建）；reviewer 按本规格验收 t6。
> - **约束**：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick 步进内常数级计算；**保持同种子可复现** 与 **H2 持久化**（`engine_kv.engine_main` blob 随 `MarketEngine.save()/load()` 整体序列化，见 `ECON_FIAT_M2_SPEC.md` §7）。
> - **范围**：仅本团队 Goal 的「上层建筑 → 金融市场衔接」角色/权威改造。不动已交付的法币/M2/价格/行为模型语义，只在其上叠加**角色分层、权力约束、客户端/服务端权威校验**。

---

## 0. 目标与全局验收（对应团队 Goal）

> 在既成法币+M2 基础上，让玩家既可做**被动参与的散户**（只读+交易），也可被服务端任命为**央行行长**（货币政策）或**财政部长**（财政），主动发行与管理货币与财政；同时以**制度约束＋AI 兜底**防止角色滥用；客户端**只读展示＋提交意图**，服务端为唯一权威。完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| SR-0 | 编译通过 | `./gradlew compileJava` 退出码 0 | t2/t3/t4 |
| SR-1 | 角色分层 | 服务端可任命/撤销玩家为 `CENTRAL_BANKER`（央行行长）或 `FINANCE_MINISTER`（财政部长）；散户默认 `RETAIL`；`RETAIL` 无任何货币政策/财政执行权 | t2 + t5 |
| SR-2 | 客户端/服务端权威 | 服务端为唯一权威：所有货币政策/财政操作在**服务端**校验角色与约束后执行；客户端仅**只读展示**＋**提交意图**（packet 携带角色校验所需标识），客户端不可直接改任何引擎状态 | t2 + t5 |
| SR-3 | 央行权力约束 | 央行行长改 `discountRate`/`reserveRequirement`/OMB 受**泰勒规则建议**＋**通胀/衰退问责**约束；越界触发 AI 兜底/问责 | t3 + t5 |
| SR-4 | 财政权力约束 | 财政部长发债/支出受**债务上限**与**赤字约束**；越界触发 AI 兜底 | t4 + t5 |
| SR-5 | AI 兜底防滥用 | 约束违反时系统自动介入（拒绝越界动作或自动校正到制度轨道），避免单角色把经济推向失控 | t3/t4 + t5 |
| SR-6 | 贷款账本恢复修复 | `BankManager.loadFromNBT` 恢复**贷款账本**（`Bank.loans`/`totalLoans`）而非仅存款层 | t4 + t5 |
| SR-7 | 同种子可复现 | 角色任命/撤销用确定性命令流；约束/AI 兜底均为确定性函数（无裸随机） | t2~t4 + t5 |
| SR-8 | H2 持久化 | 角色任命与政府/央行状态随 `engine_kv` blob 与 `MarketEngine.save()/load()` 序列化，读档恢复 | t2~t4 + t5 |

**验收流程（t5 落地）**：服务端执行任命命令 → 客户端（政府平板）只读展示当前角色/权限 → 以对应角色提交意图 → 服务端校验角色与约束 → 观察引擎是否执行/拒绝；用确定性验证类（仿 `.verify/VerifyFiatM2.java`）驱动真实 `RoleRegistry`/`GovernmentAuthority`/`CentralBankPolicy`/`FiscalPolicy` 复现各约束点。

---

## 1. 现状诊断（代码审计结论）

| 项 | 文件 | 现状 | 问题（角色/权威视角） |
|---|---|---|---|
| 角色 | —（无） | 无任何角色概念 | 无「散户 vs 央行行长 vs 财政部长」分层；任何持有 `GovernmentTabletItem` 的玩家都可在客户端发操作 |
| 权限校验 | `network/packets/GovernmentActionPacket.java:26` | `if (context.player() instanceof ServerPlayer sp && sp.hasPermissions(2))` | 仅校验 **op 2 级**，不区分货币政策 vs 财政操作；op 玩家可同时执行 `set_rate`/`set_reserve`（央行操作）与 `issue_bond`/`spend`（财政操作），无角色隔离 |
| 权限校验 | `network/packets/FinancialWarPacket.java:29` | 同样 `sp.hasPermissions(2)` | `interest`/`reserve`/`sentiment`/`sanction` 等「央行/政府干预」操作同样只按 op 放行 |
| 权威 | `screen/GovernmentTabletScreen.java:33-66` | 客户端直接 `new FinancialWarPacket(...).send()`/`new GovernmentActionPacket(...).send()` | 客户端只发**意图**（正确），但服务端无角色校验、无约束校验 → 客户端被恶意/越权利用时服务端照样执行 |
| 央行 | `engine/banking/CentralBank.java:37-41` | `setDiscountRate`/`setReserveRequirement` **任意值直接写入**（`setReserveRequirement` 有 `[0.01,0.25]` 钳制，`setDiscountRate` 无钳制） | 央行行长可一次性把利率改到极端值；无泰勒规则引导、无通胀/衰退问责 |
| 财政 | `engine/banking/Government.java:24-54` | `issueBond(faceValue, coupon, maturity)` 直接 `totalDebt += faceValue`；`spend` 仅受国库余额限制 | 无**债务上限**——无限发债可无限推高 `totalDebt`；无赤字（年度新增债务）约束 |
| 持久化 | `engine/banking/BankManager.java:181-196` | `loadFromNBT` 只恢复各银行**存款层**（`deposit`/`depositTime`/`depositMoneyMarket`），**不恢复 `loans`/`totalLoans`（贷款账本）** | 读档后 `totalLoans` 归零、`loans` 空列表 → 账面贷款丢失，M2/货币乘数统计失真（评审建议的非阻断小项） |
| 状态读取 | `network/packets/RequestGovernmentDataPacket.java` | 服务端 `handle` 部分**被整体注释掉**（`/* ... */`）；`GovernmentDataResponsePacket.reserveRequirement()` 返回硬编码 `0` | 客户端无法可靠拉取政府/央行状态（只读展示不完整）；财政部门与央行部门的展示混在一屏 |

**根因**：当前「政府平板」是**单角色全功能控制台**——op 即可执行全部货币+财政操作，且服务端只按 op 2 级放行、无任何制度约束。上层建筑衔接的核心是把它拆成**按角色隔离、服务端校验、受制度约束**的三层：`RETAIL`（散户）/`CENTRAL_BANKER`（央行行长）/`FINANCE_MINISTER`（财政部长），并让**服务端**成为唯一执行者。

---

## 2. 角色体系架构设计

### 2.1 角色定义（`RoleRegistry`）

新增 `engine/authority/RoleRegistry.java`（建议包 `com.example.financialmarket.engine.authority`）：

| 角色 | 枚举值 | 权限范围 | 说明 |
|---|---|---|---|
| 散户 | `RETAIL` | 只读展示＋市场交易（现有 `PlaceOrderPacket` 等） | 默认角色，无任何货币政策/财政执行权 |
| 央行行长 | `CENTRAL_BANKER` | 货币政策：`setDiscountRate`、`setReserveRequirement`、OMO（`buyBonds`/`sellBonds`/`reverseRepo`/`settleRepos`）、`emergencyLending` 等 | 受泰勒规则＋通胀/衰退问责（§3） |
| 财政部长 | `FINANCE_MINISTER` | 财政操作：`issueBond`、`spend`、`setTaxRate`、`setFiscalStance`（发债/支出/税率） | 受债务上限＋赤字约束（§4） |

> **角色互斥**：同一玩家同时只能持有一种除 `RETAIL` 外的管理角色（任命为 `CENTRAL_BANKER` 会自动清除 `FINANCE_MINISTER`，反之亦然）。`RETAIL` 是兜底默认，管理角色可被随时降级/撤销回 `RETAIL`。

```java
public enum FiscalRole { RETAIL, CENTRAL_BANKER, FINANCE_MINISTER }
public final class RoleRegistry {
    private final Map<UUID, FiscalRole> roles = new HashMap<>();   // 服务端权威角色表
    // 读取
    public FiscalRole get(UUID playerId);                       // 默认 RETAIL
    public boolean hasRole(UUID playerId, FiscalRole role);     // 相等或含管理权时 true
    // 任命/撤销（服务端唯一）
    public void appoint(UUID playerId, FiscalRole role);        // 互斥：设 CENTRAL_BANKER 会先降 FINANCE_MINISTER
    public void revoke(UUID playerId);                          // 降回 RETAIL
    // 持久化
    public CompoundTag saveToNBT();
    public void loadFromNBT(CompoundTag tag);                   // 缺字段默认 RETAIL
}
```

### 2.2 角色校验：`GovernmentAuthority`（服务端权威单点）

新增 `engine/authority/GovernmentAuthority.java`，作为**所有管理操作的服务端唯一网关**。它把「角色匹配 + 制度约束 + AI 兜底」集中在一处，避免各 packet 手动重复校验：

```java
public final class GovernmentAuthority {
    public enum ActionType { MONETARY, FISCAL, MARKET_INTERVENTION }

    /** 判断某玩家是否有权执行某一类管理操作 */
    public boolean canExecute(UUID playerId, ActionType type);

    /**
     * 执行一次管理操作。返回执行结果（含是否被约束拒绝 / AI 兜底介入）。
     * 流程：{@code canExecute} → {@code applyConstraint} → 执行或拒绝。
     */
    public AuthorityResult execute(UUID playerId, ActionType type, GovernmentActionRequest req);

    /** 单类操作的制度约束（央行=泰勒规则+问责；财政=债务上限+赤字）。返回 null=允许，否则返回拒绝原因。 */
    public String applyConstraint(ActionType type, GovernmentActionRequest req);

    /** AI 兜底：约束违反时自动校正/拒绝的确定性函数。 */
    public AuthorityResult aiFallback(ActionType type, GovernmentActionRequest req);
}
```

- `AuthorityResult`：`{ boolean accepted; String rejectionReason; String aiAction; double effectiveValue; }`，供服务端日志/客户端提示。
- **AI 兜底**为**确定性规则引擎**（禁止裸 `Random`，对齐 `ECON_IMPROVEMENT_PLAN` §6）：当约束违反（如利率极端值、发债超上限）时，返回 `accepted=false` 并给出 `aiAction`（如「利率已自动钳制到上限 10%」「发债请求被债务上限拒绝，已自动回落到赤字预算余量」）。

### 2.3 客户端/服务端权威分层（`GovernmentActionPacket` 扩展）

- **服务端唯一权威**：所有货币/财政状态变更在服务端执行（现有 `handle` 已如此），客户端**不得**直接改引擎任何字段。
- **客户端**：`GovernmentTabletScreen` 仅为**只读展示 + 提交意图**。提交时把「玩家 UUID + 意图 `action`/`param`」发给服务端；服务端用 `RoleRegistry` ＋ `GovernmentAuthority` 进行角色与约束校验后决定是否执行。
- **意图包扩展**：`GovernmentActionPacket` 当前 `record(String action, double param)`。服务端 `handle` 已能从 `context.player()` 拿到 `ServerPlayer`（含 UUID），**无需新增字段**即可做角色校验。但为区分**央行操作**与**财政操作**，建议：
  - 在 `GovernmentActionPacket.handle` 内按 `action` 字符串归类到 `ActionType`（`set_rate`/`set_reserve`/`buy`/`sell`/`reverse_repo`/`settle_repo` → `MONETARY`；`issue_bond`/`spend`/`set_tax`/`set_fiscal` → `FISCAL`；`sentiment`/`sanction` → `MARKET_INTERVENTION`），再走 `GovernmentAuthority.canExecute`。
  - 把 `FinancialWarPacket` 中属「央行/政府干预」的动作（`interest`/`reserve`/`sentiment`）也并入同一权威校验，避免双路径绕过。
- **读-写分离**：客户端只读数据经 `GovernmentDataResponsePacket`（服务端 → 客户端）。t2 需**补全** `RequestGovernmentDataPacket.handle`（当前被注释）并让响应带上 `reserveRequirement` 真实值（当前硬编码 `0`），并视需要区分央行视图/财政视图字段。

### 2.4 服务端任命入口

- 新增命令 `/fmarket role <player> <RETAIL|CENTRAL_BANKER|FINANCE_MINISTER>`（需 `requires(src -> src.hasPermission(2))`，对齐 `command/CompanyCommand.java:17`），调用 `engine.getRoleRegistry().appoint(...)`，并立刻 `saveEngine`。
- 服务端为唯一任命者；客户端只是**显示**当前角色。

---

## 3. 需求契约：t3 — 央行行长制度约束（泰勒规则＋AI兜底）

### 3.1 改动文件
- `engine/banking/CentralBank.java`
- 新增 `engine/authority/CentralBankPolicy.java`（泰勒规则建议 ＋ 通胀/衰退问责）
- `engine/authority/GovernmentAuthority.java`（接入校验）
- `engine/MarketEngine.java`（如需在 tick 内触发问责/自动校正）
- `network/packets/GovernmentActionPacket.java`（`set_rate`/`set_reserve` 走权威校验）

### 3.2 需求
1. **泰勒规则建议**：`CentralBankPolicy` 提供 `suggestedRate()`，用简化泰勒规则计算**建议政策利率**：
   `i = r* + π + 0.5·(π − π*) + 0.5·(y_gap)`，其中 `r*` 取现有 `MacroEnvironment` 中性利率（`r_neutral=0.035`，`MacroEnvironment.java:45`），`π` 取 `InflationSystem.getInflationRate()`，`π*` 为通胀目标（默认 0.02），`y_gap` 为产出缺口（可用 `EconomicCycleEngine.getEconomicHealth() − 1` 作为代理，见 `engine/macro/EconomicCycleEngine.java:41`；无周期数据时默认 0）。**只作为建议值**暴露给客户端与日志，不强制。
2. **权限钳制**：`CentralBank.setDiscountRate` 增设硬性域 `[0.001, 0.20]`（当前无钳制）；`setReserveRequirement` 保持 `[0.01, 0.25]`。**极值越界 → AI 兜底拒绝/钳制**。
3. **通胀/衰退问责**：`CentralBankPolicy` 依据 `InflationSystem` 与周期/衰退指标判定是否触发问责：
   - **通胀问责**：连续 N 交易日（默认 10）`inflationRate > 0.10`（高通胀），且央行利率**未跟随泰勒规则上调**（`|currentRate − suggestedRate| > threshold`）→ 触发问责（日志警告 + 记录 `accountabilityEvent`）。
   - **衰退问责**：周期进入衰退（`EconomicCycleEngine.getEconomicHealth()` 或 `getPhaseName()` 指示收缩/衰退，见 `engine/macro/EconomicCycleEngine.java:41-42`）而央行**过度紧缩**（利率显著高于建议）→ 触发问责。
   - 问责**不自动惩罚玩家**（避免竞态接管玩家操作），而是**记录纪律事件**并在 `GovernmentDataResponsePacket` 暴露，供 t5 验证「约束存在且可观察」；这是「制度约束」的可测形态。
4. **AI 兜底**：`GovernmentAuthority.aiFallback(MONETARY, req)`：
   - 若 `req.value` 超出利率/准备金硬域 → 返回钳制到边界，`accepted=false`（拒绝越界原值、提示已钳制值）。
   - 若设置值偏离泰勒建议超过阈值（如单次改动越过 ±0.5%）→ 允许但返回 `aiAction` 提醒（非阻断）；同时由问责机制记录。
   - 全部为**确定性**（无随机），同种子同路径。

### 3.3 验收（t3 自身）
- `setDiscountRate` 超出 `[0.001,0.20]` 被钳制/拒绝（AI 兜底生效）。
- 高通胀（如 `InflationSystem.inflationRate` > 0.10）持续 N 日而利率未上调 → 记录通胀问责事件。
- `centralBankPolicy.suggestedRate()` 在 `inflationRate` 上升时随之上升（方向正确）。
- `./gradlew compileJava` 通过；SR-3/SR-5/SR-7/SR-8 成立。

---

## 4. 需求契约：t4 — 财政部长制度约束（债务上限＋赤字）＋ 贷款账本恢复修复

### 4.1 改动文件
- `engine/banking/Government.java`（债务上限/赤字字段＋发债/支出钳制）
- 新增 `engine/authority/FiscalPolicy.java`（债务上限/赤字预算）
- `engine/authority/GovernmentAuthority.java`（接入校验）
- `engine/banking/BankManager.java`（**修复 `loadFromNBT` 恢复贷款账本**）
- `network/packets/GovernmentActionPacket.java`（`issue_bond`/`spend` 走权威校验）

### 4.2 需求
1. **债务上限**：`Government` 增设 `debtCeiling`（默认如 `5_000_000`，或按 `treasury`/税基动态）。`issueBond` 前校验：`当前 totalDebt + faceValue > debtCeiling` 则**拒绝**（返回 null + 记日志）。发债后 `totalDebt` 不变超上限。
2. **赤字约束**：`FiscalPolicy` 维护**年度赤字预算**（默认如 `currentYearDeficitLimit`）。`spend`/发债导致**年度新增赤字**（`spend + netDebtIncrease`）超预算 → 限制/拒绝。t4 可用简化口径：年度赤字 = 年度新发债 − 年度税收；超过预算则需削减支出或停发债。
3. **AI 兜底（财政）**：`GovernmentAuthority.aiFallback(FISCAL, req)`：
   - `issueBond` 超债务上限 → 返回 `accepted=false`，`aiAction` 提示拒绝并给余量。
   - `spend` 超国库或赤字预算 → 拒绝/钳到余量。
4. **贷款账本恢复修复（评审非阻断小项）**：`BankManager.loadFromNBT`（`BankManager.java:181-196`）当前只恢复存款层。改为**同时恢复贷款账本**：
   - `saveToNBT` 已存 `totalLoans` 但未存每笔 `Loan`。需在 `saveToNBT` 中序列化 `Bank.loans`（`Loan.loanId/borrowerId/borrowerType/principal/remaining/annualRate/termDays/startDay/status/collateralValue`），在 `loadFromNBT` 反序列化出 `Loan` 并从 `bank.loans`/`totalLoans` 恢复。
   - 需要 `Bank` 暴露贷款账本写入口：新增 `Bank.setLoans(List<Loan>)` 或 `Bank.addLoan(Loan)`（设 `totalLoans = Σ principal`），且 `Loan` 需提供从 NBT 重建的构造/字段 setter（当前 `loanId/startDay/status` 无 setter，需补）。**保持确定性、无随机**。
   - 恢复后 `totalLoans` 与 `loans.size()` 一致，M2/货币乘数统计不再失真（SR-6）。
5. **持久化**：`debtCeiling`/赤字预算随 `Government` 入 `save()/load()`；`RoleRegistry` 入 blob。

### 4.3 验收（t4 自身）
- `issueBond` 在 `totalDebt + faceValue > debtCeiling` 时被拒绝。
- 年度赤字超预算时 `spend`/新发债被限制。
- `loadFromNBT` 后 `bank.totalLoans > 0` 且与 `saveToNBT` 前一致（贷款账本恢复）。
- `./gradlew compileJava` 通过；SR-4/SR-5/SR-6/SR-7/SR-8 成立。

---

## 5. 实施顺序与依赖

```
t2（角色体系＋客户端/服务端权威，SR-1/SR-2/SR-8）
   ├──→ t3（央行行长制度约束，SR-3，依赖 t2 的 RoleRegistry/GovernmentAuthority）
   └──→ t4（财政部长制度约束＋贷款账本恢复修复，SR-4/SR-6，依赖 t2）
            └──→ t5（整体验证与构建，依赖 t2/t3/t4）──→ t6（评审）
```

- t3 与 t4 均依赖 t2 建立 `RoleRegistry` 与 `GovernmentAuthority` 的**权威校验骨架**；两者彼此独立、可并行。

---

## 6. 兼容性：网络包、客户端 UI、H2

| 项目 | 方案 | 兼容 |
|---|---|---|
| `GovernmentActionPacket` | **不新增字段**（服务端从 `context.player()` 取 UUID）；仅在 `handle` 内归档为 `ActionType` 并走 `GovernmentAuthority` | 旧客户端（发 `action`/`param`）完全兼容，行为从「op 放行」变为「角色校验」 |
| `FinancialWarPacket` | 把 `interest`/`reserve`/`sentiment` 等归类到 `MARKET_INTERVENTION`/`MONETARY`，统一走权威校验 | 字段不变；仅服务端校验加强 |
| `GovernmentDataResponsePacket` | 补全 `reserveRequirement` 真实值；如需区分央行/财政视图可按需增量字段（默认值向后兼容） | 字段名/类型不变或加默认值字段 |
| 客户端 `GovernmentTabletScreen` | `mouseClicked` 目前未绑定按钮动作（`GovernmentTabletScreen.java:90-94`），需补内容触发 `send()`；仅**提交意图**，仍不直接改服务端状态 | 纯客户端展示，服务端不依赖它 |
| H2 `engine_kv.engine_main` | 角色表/央行政策/财政约束随 `save()/load()` blob 整体序列化 | 无需新增关系表列；旧存档缺字段用 `contains` 兜底默认值 |

---

## 7. 可复现（对齐 ECON_IMPROVEMENT_PLAN §6）

- `RoleRegistry`/`GovernmentAuthority`/`CentralBankPolicy`/`FiscalPolicy` 全部为**确定性函数**（角色匹配、钳制、泰勒规则计算、债务校验均无随机）。
- AI 兜底用**确定性规则**（钳制到边界/拒绝），禁止 `new Random()`/`ThreadLocalRandom`。
- 泰勒规则用确定公式＋现有 `InflationSystem`/`MacroEnvironment` 状态，天然同种子同路径。
- 任命/撤销经确定性命令流，同种子下角色状态可复现。

---

## 8. 风险与缓解

| 风险 | 级别 | 影响 | 缓解 |
|---|---|---|---|
| 角色校验绕过（`FinancialWarPacket` 双路径） | 中 | 越权执行货币/市场干预 | t2 统一归口 `GovernmentAuthority`，所有管理类 packet 都过它；t5 校验无绕过路径 |
| 约束过强导致玩家无法操作 | 中 | 角色被「锁死」 | 泰勒规则只作**建议**；问责只**记录**不惩罚；AI 兜底只钳制**极端越界** |
| `loadFromNBT` 贷款恢复改动 `Bank`/`Loan` 私有字段 | 中 | 编译/读档回归 | 新增 setter 保持既有语义；`loadFromNBT` 用 `contains` 兜底；t5 验证读档后 `totalLoans` 一致 |
| H2 旧存档缺角色/约束字段 | 低 | 读档失败 | `loadInternal` `contains` 兜底 + 默认值（`RETAIL`/`debtCeiling` 默认） |
| 客户端展示与服务端权威不一致 | 低 | 玩家误以为能操作 | t2 明确客户端只读+提交意图；服务端拒绝时返回结果展示 |

---

## 9. 输入/来源索引

- 已交付法币+M2：`ECON_FIAT_M2_SPEC.md`、`MACRO_MODEL.md`
- 架构与可复现：`ECON_IMPROVEMENT_PLAN.md`（§6 可播种 PRNG、§9 风险）
- 代码证据：`GovernmentActionPacket.java`、`FinancialWarPacket.java`、`Government.java`、`CentralBank.java`、`BankManager.java`、`Bank.java`、`Loan.java`、`MacroEnvironment.java`、`InflationSystem.java`、`GovernmentTabletScreen.java`、`FinancialMarket.java`、`MarketAttachment.java`
