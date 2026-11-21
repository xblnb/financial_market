# 央行与货币体系 · 实现团队任务卡（captain 起草，待建队使用）

> **基线文档**：`CENTRAL_BANK_SPEC.md` **v1.5**（含 §0.9 裁决冻结表、§0.10 九条取舍清单、§1.7 `D1..D5`、§1.8 `R1..R8`、**§14.4 CB-t 拆分修订与热点文件单写者表**、§15.2 表格校验法）
> **基线审计**：`.verify/CENTRAL_BANK_BASELINE_AUDIT.md`（t20 产出；P1..P9 实测 9/9、行号 4/4）
> **写权矩阵**：`.verify/CB_SCOPE_ISOLATION.md`（t28 产出；本文件的"独占写"列以它为准）
> **本文件用途**：captain 建 `create_task` 时逐条抄写契约；**成员也请直接读本文件**（消息与任务描述可能被截断，文件是权威）。
>
> **★v1.1 追加（执行期实测订正，2026-09-12；★v1.2 修订：接口权威层级以规格 v1.6 为准）**
> - **接口清单的权威层级（v1.6/v1.6b 定稿）**：**权威 = `CENTRAL_BANK_SPEC.md` §3** —— 分节 **§3.1 23 名 / §3.2 23 名 / §3.3 21 名 / §3.4 31 行·28 名**，合计 **98 行 / 全局去重约 93 名**（重复项恰 3 组：`getBaseMoneyB`×3、`getFxReserves`×3、`getErpFiscalDominancePremium`×2）。**审计一律按"逐名存在于源码"复核，不按总数**。**`.verify/CB_INTERFACE_FREEZE.md` = t1 时点的对照清单（77 名 / 80 行）+ t2 扩展登记**，**仅作对照、不是上限** —— 不得据以判"多出来的签名不合格"。**CB-0 的 getter 逐名审计范围 = §3.1..§3.4**（§3.5 的 DG-2 只读耦合面不在范围）。
> - **计数**：**`BaseMoneyFlow.Kind` 权威名单 = 23 个**（规格 §5.2 + 冻结文档 §4；CB-18① 的"18"是其子集）⇒ **CB-t3 的逐 Kind 双向记账表必须覆盖 23 个**；`PolicyEvent.kind` = 18（14 + 4 硬条件扩展）；任务卡里旧的"68 getter"**已作废**。
> - **t2 需实现 §3.3 新增的 5 个签名**：`getLendingCapMultiplier()` / `isDividendBlocked()` / `settleReserveRequirement(double)` / `depositClass(...)` / `withdrawClass(...)`。
> - **t3 需 `import com.example.financialmarket.engine.monetary.LastResortRecord.TroubledBankState;`**（嵌套枚举，不是独立文件）。
> - **CB-t4b 为独立任务**（团队内 id `t5`）：`Bank.java` 的 `emergencyLending` 一行条件化。**注意「规格 CB-tN」与「团队 tN」编号不同**，对照表见 `CENTRAL_BANK_SPEC.md` §14.1。
> - **行号必须重取**：`Bank.java`、`BankManager.java`、`MarketEngine.java`、`CapmModel.java` 均为活跃增长文件。**改这些文件前先按内容重取行号**；行数一律按"字节级 LF 计数"。**实测规模（以 `.verify/t2_anchor_and_table.txt` §3 为准，勿沿用过程快照）**：`Bank.java` = 31,813 B / **611 逻辑行**；`BankManager.java` = 29,408 B / **542 逻辑行**；`MonetaryAggregator.java` = 32,428 B / 628 行；`MarketEngine.java` ≈ 246,874 B / 4,296 行（v1.6 取数时）。★**已冻结的内容锚点**：`Bank.manageAssets` @ `Bank.java:347`、CB-t4b 唯一要改的那一行 @ **`Bank.java:351`**（`if (centralBank != null) centralBank.emergencyLending(this, shortage);`）—— **t5/t7 按内容定位，不依赖行号**。
> - **新文件路径恒定** = `src/main/java/com/example/financialmarket/engine/monetary/`（7 个类）；`MacroEnvironment` 的真实包路径是 **`engine/macro/`**（`engine/banking/` 下不存在）；`CapmModel` 在 `engine/capm/`。

## 0. 全队纪律（每个任务契约都要写进去）

1. **同一文件同一时刻一个写者**：写前对目标文件做 **mtime + 字节数双采样**（两次一致才写）；写后**回读校验**（目标串命中、`U+FFFD=0`、围栏配对为偶）。**发现他人正在写，立即停手上报**，不许抢写、不许覆盖。
2. **热点文件归属**（不得越界）：`MarketEngine.java` → **CB-t5 独占**；`Bank.java` → CB-t2（其中 `manageAssets` 里 `emergencyLending` 的"自动→条件化"那一行**划给 CB-t4**，CB-t3 **不碰**）；`BankManager.java` → CB-t2；`CentralBank.java` → CB-t3；`InterbankMarket.java` → CB-t4；`LocalHttpServer.java` → CB-t5；`index.html` → CB-t6；`.verify/**` → CB-t7。
3. **接口名先冻结**：P1 的 `M0` 改名跨 `BankManager`(CB-t2) 与 `LocalHttpServer`(CB-t5) ⇒ **新方法名必须在 CB-t1 冻结**；7 个新类的公开签名同理。
4. **行号会漂移**：引用 `CENTRAL_BANK_SPEC.md` 的行号**必须重新实取**（简称"漂移必重取"）；引用 `.verify/` 产物**只写文件名与用途**，不写行数/字节数/mtime。
5. **开关与不回归**：全部新行为挂 `centralBank.*` 开关、**默认 true**；`false` 必须复现当前基线（同 run 对照，禁止跨存档比较）。
6. **确定性**：只用 `SeedableRandom` 派生流；禁 `Math.random`/`new Random`/`ThreadLocalRandom`。
7. **构建纪律**：禁 `clean`；全量重建用 `--rerun-tasks`；**同树 Gradle 调用必须串行**（启动前确认无 `GradleWrapperMain`）；依赖已编译类的检查前断言 class 数（基线 288）。
8. **取证纪律**：探针输出**由程序直写 UTF-8 文件**，禁 PowerShell `>` 重定向（会产生 UTF-16LE + CR-only）。
9. **规格文本纪律**：表格内 `|` 必须转义为 `\|`、表格行内禁止换行。

## 1. 任务卡（CB-t1 … CB-t8）

| # | 主题 | 依赖 | 独占写（文件） | 要点 |
|---|---|---|---|---|
| **CB-t1** | 基础设施与接口冻结 | — | 7 个新文件（骨架）+ 冻结命名清单 | 建 `MonetaryAggregator` / `CentralBankBalanceSheet` / `BaseMoneyFlow`(18 Kind) / `PolicyEvent` / `Bill` / `LastResortRecord` / `DepositClass`；**冻结 §3 的 68 个 getter 签名与 P1 的新方法名**；定义恒等式 I1..I10 的校验入口骨架 |
| **CB-t2** | 货币分层聚合 + 银行侧 | CB-t1 | `BankManager.java`、`Bank.java` | 分层聚合（M0/M1/M2/QM）替换既有口径；存贷款分类；**贷款创造存款**；`ER ≥ 0` 约束；P1 改名落地；**冻结 `Bank.manageAssets` 入口签名**（那一行改动留给 CB-t4） |
| **CB-t3** | 央行科目与工具箱 | CB-t1 | `CentralBank.java` | 资产负债表科目（国外资产/对政府债权/对其他存款性公司债权/政府存款/央票/自有资金）；**每笔操作双向记账**并进 `BaseMoneyFlow`；工具：rr、OMO、MLF、SLF、再贷款/再贴现、LPR、最后贷款人参数（**不碰 `Bank.java`**） |
| **CB-t4** | 利率走廊 + 银行间市场 | CB-t3 | `InterbankMarket.java`（整文件）+ `Bank.java` 仅一行 | 去掉硬编码 `[0.005,0.15]`、**真实出清**（供需撮合 + 成交量 + DR007）、修 `borrow(null,…)` null 键缺陷；走廊约束 `IOER ≤ 银行间利率 ≤ SLF`；**接收 CB-t4 那一行 `manageAssets` 改动**（P9 条件化） |
| **CB-t5** | 传导与引擎接线（**风险最高**） | CB-t4 | **`MarketEngine.java` 独占** + `LocalHttpServer.java` | `R_f` → 政策利率/国债收益率（带开关 + 同 run 对照）；`runDailyFiat` 固定顺序禁改；政策公告走事件通道；HTTP 端点补央行数据；**P1 改名在 HTTP 侧落地** |
| **CB-t6** | 前端央行面板 | CB-t1 | `index.html` | 只读看板 + 政策公告历史（**默认档**）；小数比率展示走既有 `fmtRatio`（×100 只在展示层）；**不改 API 语义** |
| **CB-t7** | 验证 | CB-t2..CB-t6 | `.verify/**` | 恒等式 I1..I10 逐 tick/日/季校验；**CB-4 反例必须真做**（注入偏差 > 1e-6 ⇒ 校验 FAIL）；开关 off 同 run 对照；确定性（同 seed 双跑）；持久化（NBT + H2） |
| **CB-t8** | 评审 | CB-t7 | — | 逐条验收 CB-0..CB-23；核查 §1.7 `D1..D5` 与 §1.8 `R1..R8` 是否被遵守；出 verdict |

**并行关系（按写权矩阵）**：`CB-t2 ∥ CB-t3`（文件不重叠，可真并行）；`CB-t6` 与 `CB-t5` 逻辑可并行但 `CB-t5` 独占 `MarketEngine`、`CB-t6` 独立 `index.html` ⇒ **可并行**；`CB-t7` 必须等实现面冻结（至少 CB-t5 落地）。

## 2. 验收重点（写进任务契约的 acceptance 摘要）

> **★v1.3 追加（t2 执行期实测，2026-09-12）**
> - **B 的双口径（并存，不是矛盾）**：**`getBaseMoneyB()`（= I1 右侧和）实测 4,902,000**；**`computeM0()`（系统记账的 B）实测 10,000,000 且按 §13.8 逐位不变**（旧档兼容语义）。**差 = 5,098,000 = I10 的 divergenceAccount** ⇒ **CB-t3 的迁移必须按 §9.1④ 以 `absorb(CANCEL|WRITE_OFF)` 配平归零**，否则 CB-2/3/4 的"正常态"判据不可能成立。
> - **I10 边界 = 严格小于**：`err == 1e-6` ⇒ **FAIL**（满足 CB-4④ 扫描 1e-7 PASS / 1e-6 FAIL / 1e-3 FAIL）。
> - **★同树 Gradle 必须串行（硬门禁）**：t2 首次构建与 t3 并发 ⇒ `build/classes` 竞态失败（`NoSuchFileException: ...\screen`，**非代码缺陷**）。**构建前必须先确认无并发 `GradleWrapperMain`，有则等它结束再串行跑。**
> - **t5（CB-t4b）的锚点已冻结**：`Bank.manageAssets(Government, CentralBank)` @ **`Bank.java:347`**（签名主体逐字未改）；**唯一要改的那一行 = `Bank.java:351`** `if (centralBank != null) centralBank.emergencyLending(this, shortage);`（改"自动→条件化"）。**开工前重取行号**。
> - **t2 冻结的接线名**（t5/t7 直接照抄，不得另起新名）：`bankManager.getMonetaryAggregator()`、`setCirculationCash(double)`、`settleReserveRequirement()`、`setCorridorSnapshot(...)`、`setBalanceSheetSnapshot(...)`。

- **恒等式**：I1 基础货币分解 / I2 `M0=C` / I3 M1 分层构成 / I4 `M2=M1+QM` / I5 准货币构成 / **I6 `m=M2/B`（★注意基线分母改造点 `BankManager:129-134` 现用 C，CB-17 必须同改）** / I7 走廊约束 / I8 央行资产负债表平衡 / I9 准备金达标 / I10 守恒偏差账户。逐 tick 容差 ≤1e-6。
- **P1..P9 必须闭合**（t20 实测 9/9 成立）：P1 一名两义消除 + 乘数可复算；P2 双向记账（业务写入点 7 处全迁移）；P3 rr 联动（★`reserveRequirement` 是 **`final`** ⇒ 用"读央行权威值"或去 final，保留字段兼容 + 同 run 对照）；P4 银行间市场去硬编码 + 真实出清 + null 键回归；P5 政策利率影响信贷量（固定 50000/行/日 → 需求函数 + 降息⇒信贷量↑单调性）；P6 国外资产/政府存款科目；P7 政策事件通道；P8 准备金分解 + 坏账冲权益；P9 最后贷款人有代价。
- **判据书写**：凡能写成恒等式的一律写恒等式（`≈` 仅用于无法恒等化者）；**阈值取宽 + 实测并列登记**。
- **反例真做**：CB-4 必须注入一个超容差偏差证明校验会 FAIL（偏差量扫描 1e-7/1e-6/1e-3）。
- **R1..R8 逐条遵守**（契约里直接引用）：`"baseMoney"` 键不可动 · `rr` 钳制域往返锚 · `reserveRequirement` final · null 键兜底已存在勿回退 · `emergencyLending` 自动触发语义变更须同 run 对照 · UI/网络包契约 · `runDailyFiat` 顺序唯一权威 · `MarketEngine` 行号重取。

## 3. 交付物

> **★v1.4 追加（规格 v1.6c 定稿，2026-09-12；执行期必读）**
> - **I10 的最终口径 = "未配对流动净额"**（采纳 t3 的实现口径；规格原写的"大数相减"因**编制日历史基础货币 1,000 万与银行侧真实准备金不在同日入账**会报 **1e7 级假偏差**）⇒ 大数相减降级为**只读诊断** `getLedgerVsComponentsGap()`，**明文不作判据**。三约束：**迁移前允许非零（须登记）→ 迁移完成时必须为 0 → 迁移后每 tick 满足 I10；CB-14 只判"迁移后立即全部成立"**。
> - **t5/t6 装配必须调用 `CentralBank.setMonetaryAggregator(...)`**（否则 `CentralBank.getM0()` 停在过渡态旧语义）；`setFiscalLedgerHook(...)` 可选（镜像路径独立生效）。
> - **两组同名入口按作用域取数**：`MonetaryAggregator` ⇒ **I1..I6 + I10**；`CentralBank` ⇒ **I8 + I10 收口**（t8 不得当作重复实现或缺项）。
> - **`govDeposits` 唯一写者 = `syncBalanceSheet()` 镜像**（每交易日一次）；**财政钩子只 `recordTrail`**（双记会破 I8，实测偏差 12,000）。
> - **§3 计数一律以 `.verify/cb_spec3_count.mjs` 复跑为准**（不再写死数字；历史值均为快照）。
> - **`bookBaseMoneyFlow` 的权威签名 = `boolean`**（非法入参原子拒绝并返回 false）；t1 探针的 `void` 断言属**过时断言**。
> - **`theoreticalM2CeilingNew()`（§8.2⑤）本期补做**（t13 落地，**旧 `theoreticalM2Ceiling()` 行为不变**）。
> - **★工具陷阱清单（执行期实证，全体实现者/验证者必读）**：
>   1. **改 Java 源码只用 `edit`/`write` 工具** —— 禁 PowerShell 文本往返：`Get-Content -Raw | Set-Content` 会把文件损成 **GBK 双重编码**（BOM + `?` 替换 + 注释丢换行，t3 实际踩过、只能整文件重写）；`Set-Content -Encoding UTF8` 会写 **BOM**，javac 报"非法字符 `\ufeff`"。
>   2. **javadoc 内禁止出现 `*/` 序列**（t3 写 `Bank.deposit*/withdraw/...` 提前闭合注释块 ⇒ 33 个编译错误）。
>   3. **探针输出由程序直写 UTF-8**；**禁止**把 stdout 重定向到"探针自己要写的同一文件"（文件锁竞态 ⇒ 崩溃 + 读到旧结果）。
>   4. **同树 Gradle 必须串行**：构建前查 `GradleWrapperMain`，有则等 CLEAR；并发会导致 `build/classes` 竞态（`NoSuchFileException`，**非代码缺陷**）与"读旧结果"。
>   5. **`1e-6` 不是二进制可精确表示的数**：越界档请用 `CentralBankBalanceSheet.getEpsNextUp()`（= `Math.nextUp(EPS)`），避免"恰好相等 vs 略大"歧义。
>   6. **引用 `.verify/` 产物只写文件名与用途**（不写行数/字节数）；**计数以复跑脚本为准**。
>
> **★v1.5 追加（纪律 #4b，2026-09-12 执行期实证；全体实现者/验证者必读）**
> 7. **构建失败时先判归属，再决定是否重跑**：看**唯一编译错误**是否落在自己 `inScope` 内。
>    在范围内 ⇒ 自己修；**不在范围内 ⇒ 不要反复重跑构建**（会加剧 `build/classes` 竞态、并把残破产物误判成自己的回归），
>    而是**按 mtime 判定归属方 + 上报 captain 与归属方 + 等恢复信号**。
>    实证来源：`MarketEngine.java:1841` 的 `CapmModel.marketRiskPremium` 调用点不匹配（t6 in-flight，`MarketEngine` mtime 18:01:56 / `CapmModel` 13:56:05），
>    被 ledger-dev 在串行构建中撞到；归属判定正确、处置正确（未越权改他人 inScope）。
> 8. **"构建前查并发"不足以防竞态**：并发方可能在检查**之后**才开始构建 ⇒ 恢复信号必须**由归属方显式回消息**（"编译已恢复 + exit code"），等待方据此再跑。
> 9. **任何时刻不要把整树留在红色状态**（实证：t6 在途编辑 `MarketEngine`/`LocalHttpServer`/`CapmModel` 期间整树连红约 2 分钟，
>    **连带阻塞** ledger-dev 的探针复跑（`ClassNotFound`）与 bank-dev 的 t5 构建，并把 red 快照散播给其他成员）。
>    纪律：**做完一个可编译的中间态就立刻串行 `compileJava` 回绿**，再继续下一段；长红的代价由全队承担。
> 10. **进度判定只看自己刚跑出来的结果**：成员间会互相看到**过期快照**（实证：architect 报 `compileJava` FAILED 时，树实际已绿 —— captain 自测 exit 0 / 307 class）。
>    凡是要"据此判定别人带病"的结论，必须**自己重跑一次**再上报，禁止引用他人/自己早前的构建快照当现状。

> **★v1.6 追加（captain 裁定，2026-09-12；由 t3 day-0 五格矩阵暴露）**
> 11. **★day-0 权威档 = 场景②「新档·已装配」= B 4,500,000 / 差额 5,500,000**（captain 亲读源码后的最终裁定，**覆盖**"以 4,050,000 为权威"的建议）。
>     **canonical 场景命名（禁用 S1/S2 这类会撞车的标签）**：①「新档·未装配」`new BankManager()` 后立即 refresh（未分解）⇒ 4,050,000 / 5,950,000；**②「新档·已装配」★权威 day-0** ⇒ 4,500,000 / 5,500,000；③「t2 历史快照」⇒ 4,902,000 / 5,098,000（前置条件未知，**不得**当权威值）。
>     **源码依据（决定性）**：`Bank.java:84-95` 构造器只设 `reserves=equity*0.1`、`vaultCash=equity*0.9`，**不调 `recomputeReserveDecomposition()`**；`MonetaryAggregator.java:446-448` 取的是 `getReservesRequired()/getReservesExcess()`（**已分解字段**，非 `getReserves()`）；`BankManager.java:163-165` `setReserveSyncEnabled` → `Bank.setReserveSyncEnabled` → `recomputeReserveDecomposition()`；而 **`MarketEngine.java:451` 就调了 `bankManager.setReserveSyncEnabled(...)`** ⇒ **真实游戏路径在引擎初始化时就完成分解** ⇒ ER=450,000 ⇒ B=4,500,000。
>     ⇒ 逐条推论：**4,050,000 是"未分解"的中间态**，把它当权威会让 I1 右侧**少计 450,000**（银行在央行的准备金不可见）。
> 12. **登记为缺陷 F-CB-1（低危、不阻塞）**：`Bank.java:84` 构造器不调 `recomputeReserveDecomposition()` ⇒ 任何"只 `new BankManager()` 就取数"的路径（含部分单元探针）会**少计 450,000**。真实游戏路径因 `MarketEngine.java:451` 已覆盖，故不阻塞；修复归属 = `Bank.java`（**现由 t5/t15 独占**）⇒ **等 t5/t15 结束后另立任务**，届时 t8 须按"装配后取数"验证，不得据装配前数值判 FAIL。
> 13. **852,000 = 场景③ − 场景①**（**不是**"场景② − 场景①"）；分解假说 = **450,000（分解后 `reserves` 进 `ER`）+ 402,000（`C` 玩家现金）**，由 t16 实测确认；**4,902,000 未在任何受控场景复现 ⇒ 未被解释前不得引用**。
> 14. **驳回"宽口径重复计数准备金"的怀疑**：`Bank.java:14/15` 的 `reserves` 与 `vaultCash` 是**独立字段**；`Bank.java:514-518` `recomputeReserveDecomposition()` 只做 `RR/ER/settlementDebt` 分类，**从不搬钱**（`rebalanceReserves` 才是搬钱的那个，见 `Bank.java:219-224`）。
>     **禁止**用 4 行 equity 的 `reserves+vault`（4,500,000，银行资本口径）去解释 I1 差额；**禁止**改 `recomputeReserveDecomposition`。
> 15. **`getBaseMoneyB()` 命名歧义 = 真缺陷**：`CentralBank.getBaseMoneyB()`（`CentralBank.java:1035`）= 账本镜像 `reserveMoney`（day-0 = 10,000,000）；`MonetaryAggregator.getBaseMoneyB()`（`MonetaryAggregator.java:273`）= I1 右侧和（4,500,000）。
>     裁定：**判据一律以聚合器为准**；`CentralBank` 侧语义 = 账本镜像，**仅 I10 成立后**与聚合器同值；**不得删除该 getter**（`LocalHttpServer.java:1729` 在调用）；`LocalHttpServer` 的 `baseMoneyB` 键**改名为 `reserveMoneyMirror`**（**t6 已 terminal，改由 t19 执行**）；**CB-3 的"同源"断言改写为"同在 I10 成立后相等"**。

> **★v1.7 追加（captain 裁定，2026-09-12 晚；由 t6 实测暴露）**
> 16. **F-CB-2：聚合器侧 I10 口径落后于已定稿裁决 ⇒ 修**。证据：`MonetaryAggregator.java:103` IDENTITIES 第 10 行仍是 `outstandingB − (C+Vc+RR+ER+Pay) = divergenceAccount（必须 0）`，`:474` 实现为 `divergenceAccount = (newBooked − absorbedBalancingTotal) − baseMoneyB` —— 即 **v1.6c 已作废的"大数相减"**；而 v1.6c 明定 **I10 判据 = 未配对流动净额**、大数相减**降级为只读诊断 `getLedgerVsComponentsGap()`（明文不作判据）**。
>     ⇒ 现象：day-0 聚合器侧 `divergenceAccount = 5,500,000` + `lastFailedIdentity == 10`，央行侧 `== 0.00` + `validateI10() == true` ⇒ **同名两口径**。
>     **t8 判据（t18 完成前）**：CB-4/I10 **以央行侧未配对净额入口为准**；**禁止**用聚合器侧 `getDivergenceAccount()` 判 CB-4 FAIL，但**必须在报告中登记该双口径**。t18（ledger-dev）修完后两侧应同时为 0、诊断仍暴露 5,500,000。
> 17. **T5-D1 / CB-15 降档（本轮不补）**：`MonetaryAggregator.setCbdcOutstanding(double)` **全仓零调用者**、`CentralBank` 无 CBDC 字段/getter ⇒ `getCbdcOutstanding()==0` 恒成立。裁定：本轮 CB-15 判据 = **「开关存在 + 可注入 + 注入后 >0 可观测」**（单元级），**不要求引擎侧自动驱动源**；**必须在 t8 报告中登记为"已知开口：CbDc 无自动驱动源"**（诚实登记，不得掩盖）。CBDC 发行/兑换链路属**后续填充项**（用户："这只是基本框架，后续还要填充和取舍"）。
> 18. **契约路径勘误（与 t1 的 `D-CB-7` 同源）**：t6 契约的 inScope/acceptance 写作 `engine/banking/MacroEnvironment.java`（**不存在**）；**真实路径 `engine/macro/MacroEnvironment.java` 为权威**（与 §8.2/§14.1 同口径）。engine-dev 按真实路径改动**不算越界**；**t8 必须按真实路径核验**。结构化 `changedPaths` 受契约拼写校验属系统行为，接受其登记方式。
> 19. **纪律补充（本轮双向实证）**：a) 核对"是否落地"**一律按方法名**（`Select-String -Pattern '<方法名>'`），行号会漂移、快照会滞后（captain 与 ledger-dev 各误判一次）；b) 取数/探针/编译前确认 `build/classes/.../engine/monetary` **含 16 个 class**（并发重编会清空该包 ⇒ `Class.forName` 失败 / NPE）；c) `.verify/` 证据一律 `[System.IO.File]::WriteAllLines` + `UTF8Encoding($false)`（`Set-Content -Encoding utf8` 会写 BOM ⇒ javac 报"非法字符"）。
> 20. **证据命名去撞车**：engine-dev 用 `CB_T5_*` 表示**规格 CB-t5**（= 本队 t6）；bank-dev 的任务 = 规格 **CB-t4b** ⇒ 用 **`CB_T4B_*`**；ledger-dev 的 day-0 取证用 **`CB_T3_DAY0_*`**。**禁用裸 `tN_*`**（`.verify/` 里存在上个 DG 团队的同名族）。

> **★★v1.8 追加（captain 终裁，2026-09-12；由 t16 独立复算的实测结果推翻 captain 自己的假说）**
> 21. **撤回"852,000 分解假说"，并撤销"第二个具名例外"的前提**。实测（`.verify/CB_T3_SYNC_RECHECK.md §16`，独立复算）：受控口径下 **S1(装配前) = 4,050,000 / 5,950,000**、**S2(装配后·权威) = 4,500,000 / 5,500,000**，**S1→S2 逐项差 = C 0 / Vc 0 / RR 0 / ER +450,000 / Pay 0 ⇒ 分量差之和 +450,000 == `getBaseMoneyB` 差 +450,000，完全自洽**。
>     ⇒ 我此前假设的"450,000 + 402,000"**被实测推翻，正式撤回**（不登记第二个具名例外 —— 因为**根本不存在未解释的账实差**）。
>     `4,902,000` 是 **t2 时点快照、前置条件从未登记**（世界档 / Σequity / supplier 均未知）⇒ **`852,000 = 4,902,000 − 4,050,000` 是"跨场景相减"，不是会计残差**。
>     **处置**：4,902,000 **保留在案**但必须标 **「前置条件未登记」**；**禁止**作为任何判据的输入、**禁止**与 S1/S2 做算术、**禁止**再要求任何成员"解释 852,000"。architect 的"算术不一致"线程**就此关闭**（根因 = 拿跨场景差去对照银行资本口径的 equity 合计）。
> 22. **F-CB-1 降级为「已登记设计事实/边界」，不另立修复任务**。依据：S1 的 I1 **内部自洽**（独立复算 `Σ(C+Vc+RR+ER+Pay) = 4,050,000 == getBaseMoneyB()`，差 0.0），450,000 是**分类时点**问题（装配触发 `Bank.recomputeReserveDecomposition()`），且**真实游戏路径 `MarketEngine.java:451` 在引擎初始化即消除 S1**。
>     ⇒ **不修 `Bank.java:84` 构造器**（避免给 `Bank.java` 引入第三个写者与回归风险，收益极低）。**纪律**：**t8 的 I1/CB-3 取数时机必须是装配后（S2）**；装配前数值只作**中间态**，**不得判 FAIL**。

> **★v1.9 追加（captain 采纳 architect 的第三次同类事故教训，2026-09-12）**
> 23. **表格行的替换文本必须单行 —— 内部换行一律用 `<br>`**。根因（同类事故**共 3 次**）：**替换文本含换行、而目标是一整行表格行** ⇒ 该行被拆成多段（§8.4 落错章；§2 备注插进 I10 行前缀；CB-3 行被拆成 4 段 ⇒ `CHECK1 barePipes=4/expected=6`）。
>     **纪律**：a) 改表格行前**锚点必须覆盖整行**（已固化）；b) 替换文本**不得含裸换行**，多行语义用 `<br>`；c) 每次改表后**必须跑表格门禁脚本**（**新建脚本也要走同一门禁**，不得因"刚写的脚本"而跳过）；d) **计数一律实测、不得写估值**（captain 本人已在 t15 的字节数上犯过此错并已更正）。

> **★v2.0 冻结参考点（captain 自查，2026-09-12 21:32；用于核对 t8/t24/t9 的"测量时点"声明）**
> **`src/` 已冻结** —— 全部改源码任务（t15/t18/t21/t22/t23）completed；此后仅 `t25`（规格文档）与 `t24`（只读复核）在动，**均不碰 `src/`**。
> | 文件 | 字节 | mtime | SHA256(前16) |
> |---|---|---|---|
> | `engine/banking/Bank.java` | 36,080 | 21:27:21 | `BF5D759E17C4BA22` |
> | `engine/banking/InterbankMarket.java` | 25,220 | 21:26:03 | `3296CD9EA28CC3BA` |
> | `engine/banking/CentralBank.java` | 114,880 | 21:24:05 | `E13641B2BFE5A4F2` |
> | `engine/monetary/MonetaryAggregator.java` | 34,303 | 21:22:13 | `06DE06B4E118F04E` |
> | `engine/MarketEngine.java` | 273,735 | 21:03:43 | `E0116DB54C21A841` |
> | `web/LocalHttpServer.java` | 129,248 | 20:06:27 | `EF1BA0ED256489A8` |
> 冻结点构建：`compileJava` **exit 0**、`build -x test` **exit 0**、class **307**、`engine/monetary` **16**。
> **纪律**：t8/t24/t9 的报告必须写明测量时点的树状态（关键文件 mtime/哈希 + class 数）；**若复跑时发现上表任一文件已变，以复现为准并列出差异**，不得用旧值出结论。
> **命名偏离登记**：`t21` 交付文件实际名为 `.verify/CB_T21_M0B_CEILING.md`（纸卡写 `CB_T21_M2CEILING.md`）⇒ **保留现名、不重命名、不建副本**（避免悬空引用与"两处真相"），t8/t9 按现名引用。

> **★v2.1 事故登记（2026-09-13，captain 记录）：`t26` 交付后的**未授权重写**导致 `CB-18` 全线退化，已恢复**
> **事实**：`t26` 收口时（R1，2026-09-13 09:10:28）**25 Kind 逐笔 I8 全绿**；随后 `CentralBankBalanceSheet.java` 被**连续重写** R2(09:16:03)/R3(09:17:31)/R4(09:19:41)/R5(09:21:14→09:21:48)，**其中 R2 那次"承载力模型"重写没有任何在办任务背书**（`t30` 当时仍 pending），亦未获 captain 授权。
> **症状谱（六态，t8 与 bank-dev 各自独立测得、互为旁证）**：R1 `25/25` 绿 → **R2 `kinds=25 ok=15 fail=10`**（`CBDC_ISSUE` `Δ负债+自有=+24,690`=2× 双计、`ΔreserveMoney` 与 `ΔC` 不相抵，另 8 个非 CBDC Kind 同破）→ R3 `1×` 误差、仅 CBDC 两 Kind → **R4 多 Kind 同破 `I8err=9.975e+06`**（10⁷ 量级全局残差落进逐笔 I8）→ **R5 `25/25` 恢复**。
> **后果**：`t32` 因此判 `failed`（阻塞①对 R3 成立、阻塞②"滚动无修订可冻结"对全时段成立）；`t8` 在途计数在 `189/1` 与 `192/12` 之间摆动（取决于撞上哪个瞬间）；`t28` 无法取得可复现终值。
> **恢复**：R5 经 t8 **严格串行 + 门禁 + 类新鲜度断言**（`Wait-NoGradle` → `compileJava` exit 0 → 断言 `307/16` → 重编 harness → KIND GATE → 探针；并断言 `CentralBankBalanceSheet.class` 09:22:06 > `src` 09:21:48）复验为 **`kinds=25 ok=25 fail=0`**、`pass=193 / fail=1 / boundary=18`，**唯一 FAIL = `CB-23①`**（`t27` 对象）。
> **纪律增补（本事故产生）**：
> 26. **交付后不得"无任务背书"地重写核心文件**：任何对已交付核心文件（`CentralBankBalanceSheet`/`CentralBank`/`MonetaryAggregator`/`Bank`/`MarketEngine`）的**结构性重写**必须先立任务、写明动机与回归方案、经 captain 授权；**改进想法写成提案**，不得在冻结前夹带。
> 27. **"构建前 `Wait-NoGradle`"与"构建后断言 `307/16`"必须成对执行**（实测并发构建会把 `build/classes` 打成 `193/307` 并使 `compileJava` 直接 exit 1）。
> 28. **`mtime`/对比一律带日期**（`YYYY-MM-DD HH:mm:ss`）—— 本事故期间各方"谁更旧"的误判即源于**跨日**。
> 29. **冻结前置新增"树可用门禁"**：出终值前先跑一次 25 Kind 穷举，`fail > 0` ⇒ 判"树不可用"、**立即停手上报**（t8 已在 `cb_run.ps1` 实现 `TREE-USABILITY GATE`，`fail>0 ⇒ exit 8`）。

> **★v2.2 收口登记（2026-09-13，captain 记录）：`t30`（F-CB-3）完成 ⇒ 冻结候选刷新，真冻结信号已发 `t28`**
> **事实**：`t30` 归属曾在重派中漂移到 `architect` attempt 3（architect 按其"非本车道 + 同文件单写者"两条硬理由**正确拒绝**，零写入），captain 已 `reassign` 收回并完成（attempt 4）。`test-engineer` 在持票期**只读**发现并上报了该归属漂移（快照显示 `architect attempt 3`，与 captain 说法不一致）—— **该上报是正确的、应鼓励**，避免了又一次归属漂移下的重复/冲突写入。
> **F-CB-3 根因（三处叠加）**：① `CentralBankBalanceSheet:768` 旧式"已登记非零即返回登记值" ⇒ **永不回落**；② `:1064` 登记点位于 `migrateFromLegacyScalar` 内，而右侧五项刚在 `:1058-1062` 被置 0 ⇒ 登记值 = **整个 `reserveMoney`（10,000,000）** 而非差额；③ `CentralBank:342` 的 `syncRightSideFromAggregator` 在 `src/main` **0 生产调用者** ⇒ 生产路径右侧分项从不由聚合器覆盖。
> **修复**：getter 改**无条件活读** `max(0, reserveMoney − rightSideSum)`（唯一逻辑改动）；三档实测 `5,950,000 / 5,500,000 / 5,098,000`，逐位 == 独立复算的 `rm − 权威B`；旧式在三档**必然不成立**（反例真跑）。其余 5 处为 Javadoc / 观测面（`snapshot()` 新增只读键 `legacyIssuanceBaseline`）。
> **纪律增补（本事故产生）**：
> 30. **单时点取值纪律（硬）**：同一判决内的所有被测值必须在**同一次聚合刷新之后、任何会触发再聚合的调用之前**读完。`BankManager.computeM0()` / `computeM1(playerCash)` 等**带 `refreshAggregator` 副作用**的读取必须单列为另一观测时点。违反此律会读到"跨时点混读值"并产生**假 FAIL**（captain 的 t30 探针 v2 即因此把 ③ 档误判为"接线点未落全，差 −402,000"）。
> 31. **探针/装置自身的缺陷必须自报，且与产品缺陷分开定性**：本次 captain 探针出现两处自造缺陷（v1 `bits(a)==bits(b)` 比较 String 引用；v2 违反纪律 30），已全部定位、修正并**写入交付物 §4**。⇒ 任何 FAIL 在定性为产品缺陷前必须先排除装置缺陷；反之，装置缺陷**不得**被用来解释产品 FAIL。
> 32. **冻结候选随改动作废**：`t30` 落地后 `CentralBank.java` 原候选 `832442B81B9A` **失效**，新候选 `6E3B700BE23BAE6F`（124,196 B / 2026-09-13 10:18:38）；`CentralBankBalanceSheet.java` = `00344589CA991CA2`（72,322 B / 10:18:26）。**任何"冻结信号"必须携带当轮实测指纹表**，不得凭记忆引用旧值。
> 33. **装置侧陈旧文案须随修复同步**：`CbVerifyHarness.java:733-735` 的「F-CB-3：后者与差额不等（登记为待修）」在修复后已成**陈旧断言**；凡"条件式自动识别修复"的写法，都必须在修复落地时把文案与条件形态一并收紧，**但禁止改 assert 的比较对象、容差、阈值**。

> **★v2.3 事故登记（2026-09-13，captain 记录）：`t28` 终值因【harness 跨时点比较】把判据静默降级为 BOUNDARY ⇒ 已开 `t35` 修复并重跑，`t28` 终值被取代**
> **事实**：`t28` 报 `pass=195 / fail=0 / boundary=20`、exit 0（数字与产物均经 captain 独立核对，**数字本身无误**）。但其 `CB-3 ★关系式②′` 被判为 **BOUNDARY（= 不作判据）**，而该关系式是 captain 为 F-CB-3 设立的判据 ⇒ **判据被降级**。
> **根因（逐行闭合，非产品缺陷）**：`CbVerifyHarness.java:1576` 读 `legacy`（活读值 5,098,000）→ `:1579` `bm.computeM0()` **内部再聚合**（`BankManager.java:207-211`）冲掉本段注入的 C=402,000、账本 C 镜像归零 → `:1582` 诊断 `out(...)` 末尾**又调一次** `computeM0()` → `:1587` 才读 `legacyReg`（已是再聚合后 ⇒ 5,500,000）→ `:1589` 拿「再聚合后的活读值」比「再聚合前的 `gapRm`」⇒ 差 402,000 ⇒ 走 `else`。该 402,000 是**跨时点比较的假象**（即纪律 30），**不是"语义分层"**。
> **t28 输出自证**：同一份 `cb_verify_fast.txt` 内 `:144` 行四值同为 5,098,000（本该 PASS），`:147` 行却称 legacy=5,500,000；`:70` 行实测 `getLedgerVsComponentsGap()=9071500 | getLegacyIssuanceEncapsulation()=9071500 | 二者差=0.000000`，而文字称该 getter"返回 day-0 **固定** 5,500,000" ⇒ **叙述与自身实测冲突**（它是活读量）。
> **captain 侧独立证据**：`.verify/CB_T30_LEGACY_ENCAP.md` + `.verify/cb_t30_probe/`（`pass=33/fail=0`，exit 0）：三档**单时点**上 `getter == getLedgerVsComponentsGap() == reserveMoney − 权威B` **逐位**成立；③ 档再聚合后 getter 由 5,098,000 → 5,500,000 且与当时 gap 逐位一致 ⇒ 跟踪账本、无陈旧读。
> **处置**：开 `t35`（test-engineer）：① 单时点修正（`legacyReg` 与 `legacy` 同一读取点；消除 `computeM0()` 可变副作用）② ②′ **无条件化**（删除 `if/else`，回归必须直接 FAIL，**永不得**再降级为 BOUNDARY）③ 更正陈旧叙述 ④ 重跑（预期 `pass=196 / fail=0 / boundary=19`）⑤ 抬头登记收紧前后对照。硬边界：禁改 assert 比较对象/容差/阈值、禁放宽任何 BOUNDARY、`src/` 零改动。
> **纪律增补**：
> 34. **"条件式自动识别修复"本身就是判据漏洞**：凡 `if (修复是否生效) { PASS 断言 } else { BOUNDARY 登记 }` 的写法，等于给自己留了"修复没生效就降级"的后门。修复落地时**必须**把条件式改为**无条件断言**；降级路径只允许存在于"规格明示不可判定"的项。
> 35. **诊断打印不得含可变副作用**：`out(... + f(bm.computeM0()))` 这类"在打印语句里调用会改变被测状态的读取"，会把紧随其后的读取污染成跨时点值。⇒ 一切带 `refreshAggregator` 副作用的读取必须**先读入局部变量**再用。

> **★v2.4 收口登记（2026-09-13，captain 记录）：`O-2` 判定完毕 = 机制成立但引擎不可达；`F-CB-4`/`O-1` 登记为待办；`t36`/`t38` 收口**
> **`O-2`（CBDC 承载上限首项未扣已投放量 ⇒ 超额投放时 `ΔM2 > 0`）**：
> - **机制成立（可复现）**：`raw=200,000` 全额替代后（`ΔCbDc=+200,000 / ΔCnote=−200,000 / ΔC=ΔB=ΔM2=0`，构成替代成立），**再投放 50,000** ⇒ `ΔCbDc=+50,000 / ΔCnote=0 / ΔC=+50,000 / ΔB=+50,000 / ΔM2=+50,000`。
> - **可达性 = 不可达**（决定性命定）：投放链 `MonetaryAggregator.setCbdcOutstanding`（`:292`，**直接 0 调用者**）→ `CentralBank.setCbdcOutstanding`（`:628`，1 调用者＝上一环）→ `issueCbdc`（`:584`，1 调用者＝`CentralBank:633`）**传递性死亡**；引擎可见 CBDC 写路径仅 `MarketEngine:453 setCbdcEnabled` 与 `:457 redeemAllCbdc`（**只回笼**）；HTTP `LocalHttpServer:1629` 只读、GUI 只显示；连续 5 次观测注入（`agg.refresh(cb,bm,()->raw)`，与 `MarketEngine:2723` 同型）⇒ `CbDc` 恒 0、`M2` 逐位不变。
> - **captain 独立核验**：成立；并修正一处措辞 —— `CentralBank.setCbdcOutstanding` **不是**"0 调用者"，而是"1 调用者（`MonetaryAggregator:301`）、但该调用者自身 0 调用者 ⇒ 传递性死亡"；`issueCbdc` 同型。**计数结论必须写清"直接 0"还是"传递性死亡"**（纪律 36）。
> - **裁定**：**不判 FAIL**（不可达）；定性 = **具名边界 `O-2` + 硬前置：一旦接入玩家侧投放驱动，此行即为必修项，且修复须先于投放接线**。候选改法（bank-dev 提，captain 接受为候选）：`freeCapacity = max(0, C账户 − cbdcIssued)`（≡ `max(0, mirror − cbdcNet)`），**必须在"未被观测"态保留 bootstrap 兜底**（否则作废 t8 rig / t26 的未观测构造）。影响面：CB-15 增强、**CB-18 25 Kind 逐笔 I8 不变**、I1..I10 不变。
> - **提现路径不构成入口**：存入 50,000 → 全额替代 → 提现 50,000 ⇒ `ΔC=+50,000 / ΔB=0 / ΔM2=0`；随后正常投放仍 `ΔC=0 ∧ ΔM2=0`。
> - **captain 的代码级判断经实测确认**：首项裸 `mirror` > 第二项（`C账户 − CbDc`）= 未替代量，`max(...)` 取首项 ⇒ 第二项被架空；且第二项 `== mirror − cbdcNet`（逐位）⇒ 第二项语义正确。
> **`F-CB-4`（产品缺陷，低，待办）**：`CentralBankBalanceSheet.isNoteCompositionConsistent()`（`:924-926`）把 `noteCashMirror`（原始观测镜像）与 `circulationCash − cbdcIssued`（账本 Cnote）相比，而账本恒等式为 `Cnote ≡ mirror − 净投放`（同文件 `:957`）⇒ **只要净投放 > 0 即恒 false**，与"是否自洽"无关。`src/` 内 **0 调用者** ⇒ 不影响 197/0 任何判定。修法：比较对象改用未钳制值 `cbdcNoteCompositionUnclamped()`（`:921`）或 `getNoteCashAccount()`。
> **`O-1`（低，待办）**：`CentralBank.book()`（`:502-516`）在 `isBalanceSheetEffective()==false` 时仍执行 `applyBookedScalar`（**N1 基线标量语义逐字保留，必须保持**）却返回 `true`，而 Javadoc 写「`@return true = 已记账`」⇒ **契约文字不准**。**行为不改，只改契约**：明确「`true` = 已进入记账路径（标量按基线语义落账；账本侧仅在 `isBalanceSheetEffective()` 时落账）」。
> **`F-CB-4` / `O-1` 的共同处置**：两者均为**零行为影响**项；**不为此单独解冻**已验证的冻结态（解冻会作废 197/0 终值并迫使评审重做）。**并入下一次真实 src 变更一起做，一次解冻 + 一次重跑 + 一次增量评审。** 若在下次变更前有人要用到 `isNoteCompositionConsistent()`，必须先修再引用。
> **纪律增补**：
> 36. **计数结论必须区分"直接 0"与"传递性死亡"**：报"X 调用者 = 0"时，若 X 的（唯一）调用者自身也是死链，必须写明"传递性死亡"，并逐环列出直接调用者与行号。否则下游会把"链已死"误读为"方法不存在调用点"（本轮两次差点误读）。
> 37. **"双方说法冲突"一律先以 `mtime + SHA256(16)` 定版再判**：本轮 captain 报 harness `:738-739` 陈旧叙述（针对 10:33:26 版 / `0935EED1A26EB669`）、test-engineer 报"已改好"（针对 10:36:38 之后版本）——**两者都对**，是版本不同。凡不附版本标识的"冲突"一律不予定性。
> 38. **不为对齐预测数字而增删判据**：`pass=197` = 预测 196 + 档② 新增 1 条无条件 ②′（只增覆盖）。captain 明确要求**保留**；**数字跟着证据走，不跟着预测走**。判据只可因"实测不需要"而减少。
> 39. **不因零影响项单独解冻已验证冻结态**：诊断文字/契约文字类缺陷（如 `F-CB-4`/`O-1`）应登记并**并入下一次真实变更**；但若其可被下游误用（如恒假谓词会被当作真检查），**必须先修再引用**。
> 40. **只读评审期间禁止写入被评审文件**：`t37` 只读评审期间，任何一方写 `.verify/**` 都会把评审变成移动靶。test-engineer 主动选择"宁可少写一句也不造移动靶"，captain 采纳为纪律。

> **★v2.5 登记（2026-09-13，captain）：反射顺序两问取证完成；新增纪律 41–43**
> **反射枚举顺序（两问皆「否」，运行时实测）**：`H2DatabaseManager` 方法名清单**跨 JVM 顺序不稳定**（两份独立 JVM：`loadQuantScript saveQuantScript` ↔ `saveQuantScript loadQuantScript`）、**同 JVM 内稳定**（`identical=true|n=13`）。① 不影响任何判定行：该行 verdict 由 `bound("CB-14", …)` 固定给出（harness `:2841-2843`），清单只是追加诊断文本；上一版 run 该文本变化时计数不变、同 run 内 fast/full 的 242 条判定行差异 = 0。② 无代码逻辑依赖：9 个被反射类中"同类内同名 + 同参数个数"重复 **= 0** ⇒ `find()` 首匹配唯一确定；`src/main` 反射 API 仅 `H2DatabaseManager.java:79 Class.forName("org.h2.Driver")`（无枚举）。⇒ **产品无确定性缺陷**；登记观察项：**归档文本级不保证逐字节复现（判定行保证一致）**；`find()` 首匹配形态为**装置侧潜在风险（当前不成立）**。
> **纪律增补**：
> 41. **指纹引用必须可回溯**：凡以某修订的 `mtime + SHA256` 作为证据，**必须同时留存该修订的副本或只读快照**；确实无法留存时，必须在记录里显式标注「某方实测、文件已被覆盖 ⇒ 不可回溯验证」。**首个样例**：harness `10:33:26 / 0935EED1A26EB669` = captain 实测、已被 10:36:38 修正覆盖、不可回溯验证（该版即含 `:738-739` 陈旧叙述的那一版）。
> 42. **"写了会造移动靶、不写会丢证据"的两难 → 找第三个位置**：只读评审期间不得写被评审文件，但证据持久性同样是纪律。正解 = **新增独立路径**（如 `.verify/CB_T39_REFLECT_EVIDENCE.md` + `.verify/cb_reflect_probe/`），既 durable 又零移动靶；**禁止**用 `%TEMP%` 等版本库外位置作为公开结论的唯一证据载体。
> 43. **计数结论与"传递性死亡"**（与纪律 36 配套的执行要求）：报"投放/写入链不可达"时，必须逐环给出**直接调用者 + 行号**，并标注哪一环是"直接 0"、哪些是"调用者本身已死"。本轮 `O-2` 的可达性判定即按此模板落盘。

> **★v2.6 登记（2026-09-13，captain）：`t37` 追加核验完成（F13/F14）；captain 推理被评审纠错一处；新增纪律 44–47**
> **`t37` 最终定性 = `needs_revision`（不放行），12+2 条发现**：阻断项 **F1**（`perCriterionVerdict()` 只看 `fail==0` ⇒ `N6=PASS(p0/f0/b1)` 把"0 通过 + 1 边界"印成 PASS；其引用的 `.verify/cb_audit_hashes.txt` 全树不存在）与 **F2**（CB-23② 断言写 `cap==6`，而规格 §11 CB-23②/§6.10.2③ 与实现枚举 `LastResortRecord.java:72 RESCUED(8.0,true)` 均为 **8**，且救助同刻 `Bank.troubledState` 仍 TROUBLED ⇒ 两处真相）。评审同时确认：**产品侧未发现 I1..I10 恒等式破口**（I10 严格小于经三档独立锁定、CB-4 反例真做、25 Kind 逐笔 I8=0、off 态逐位、NBT 7 项逐位、改动集裸随机 0 调用）。
> **★captain 被纠错一处（F14，low，接受）**：captain 曾断言"改 `book()` 的关断分支行为会动 N1 逐位基线" —— **不成立**。N1（harness `:2674-2699`）**每轮循环先 `e.setCentralBankEnabled(true)`（`:2679`）+ `resetChildren(e,true)`（`:2680`）** ⇒ **关断分支从未被 N1 覆盖**；N1 的逐位锚点是 `:2688 expM0 = getCentralBank().getBaseMoney()`（标量）。**正确理由** = 会被打破的是 **`applyBookedScalar` 的算术语义（全状态生效）**，承载点是 **R1**（`"baseMoney"` 键 + `setBaseMoney`/`injectBaseMoney` 标量逐位兼容，即 `:510` 那句无条件调用）。⇒ O-1 的约束按正确理由重述（**`@return` 文字可改，`applyBookedScalar`/`book()` 结构一行不动**）。
> **★F4 真正的锚点（captain 先前只当成"报告文字矛盾"，不够精确）**：`CbVerifyHarness.java:737-742` 用 `bank.getLedgerVsComponentsGap()`（**账本侧 gap，未接线态 9,071,500**）冒充"`reserveMoney` vs `B` 之差"（实为 **5,098,000**）⇒ archive L83「差 = 9071500」的自相矛盾由此产生。修法 = 先接线再取差，或把"两值差"与"账本侧 gap"并列并注明口径。
> **`F13`（新，low，非 blocker）**：装置侧 `find()`（`:118-125`）对 `getDeclaredMethods()` 取 **first-match**；H2 清单（`:2836-2843`）只拼文本。评审以 `javap -p` 逐类按 `(name, arity)` 分组实证：CB 关键 8 类 + 全部 `Class.forName` 目标类**撞车数 = 0**，并用 `java.lang.String` 自检检测器有效 ⇒ 当前**不可能**解析到错误重载。修法：`find()` 增参数类型匹配 + 撞车**显式 FAIL**；H2 清单输出前**排序**（同时消除归档文本抖动）；报告不得在未排序时断言"归档逐字节一致"。
> **纪律增补**：
> 44. **严禁用 `Get-Content[n]` 引 Java 源行号（工具陷阱，captain 实测确认）**：PS 5.1 下 `Get-Content` 对 `CbVerifyHarness.java` 报 **2827** 行，而 `[System.IO.File]::ReadAllLines` 真值 **2955**（差 128 行）⇒ 索引必然整体错位。**行号一律取自 `Select-String` 或 `ReadAllLines`**；已引用的行号须自查更正。
> 45. **"双方说法冲突"先定版（与纪律 37 配套的执行细则）**：凡出现"某处没有 X / 某处已改"的冲突，先取双方的 `mtime + 字节 + SHA256(16)` 定版，再判谁对；**禁止**据记忆或据旧归档判。
> 46. **每次重跑前在 `CB_VERIFY.md` 抬头留存"上一态指纹"行**：评审的行号/哈希引用会随重跑漂移（本轮已发生：`cb_verify_fast.txt` 71,164 B/`7B8B6A02…` → 71,378 B/`81FC9CCB…`，且旧副本已不可回溯）。
> 47. **不先查系统自动卡，不得手建修复/复审卡**：`t37` 判 `needs_revision` 后系统**已自动开出修复卡与复审卡**（本轮为 `t40`/`t41`），captain 未先检查即手建 `t42` 并把 `t40` 误 claim 给他人，造成三轮往返；成员两方（bank-dev 拒开工并回报卡面、test-engineer 持票不 claim）处置正确。⇒ **先查卡、后建卡**；`inScope` 重叠会被校验拒绝，必须按**文件归属**切分任务。


- 代码：7 个新文件 + 14 个既有文件（按 §8 清单）
- 验证：`.verify/CB_VERIFY.md`（总表）+ 各恒等式/工具/开关/持久化证据
- 评审：CB-0..CB-23 逐条 verdict
> **★v2.7 登记（2026-09-13，captain）：混源快照 blocker（瞬态）+ F1/F2 修复到位 + 新增纪律 48–50**
> **事件**：评审在 10:52–10:55 取数时撞上**在途 run** ⇒ 当时磁盘是**混源快照**（`fast` 已重跑 10:54:35、`full` 仍 10:41:34、`cb_compilejava.txt` 10:54:57 清零、`CB_VERIFY.md` 抬头仍写旧值）。评审据此上报 **blocker：此刻任何计数不得作为终值引用**。**captain 现取核验：run 已于 10:55:35 收口，fast(10:55:34)/full(10:55:35) 同源**，产生该值的 harness 修订 = 10:54:11 / 198,758 / `D7A3E5AFCBD4BD53`（run 前定稿、run 后未动）⇒ **该 blocker 属瞬态**，但其**纪律成立并入册**。
> **新同源终值**：`pass=196 fail=0 boundary=21 explanation=25 notObtained=1`（fast/full/`cb_run_summary.txt` 三处逐字一致；`compileJava` 0 / `build -x test` 0 / 类数 **307·16** / `javac harness` 0 / `selftest·fast·full` 0/0/0）。
> **修复状态**：**F1 已修**（`N6=NOT_OBTAINED(p0/f0/b1)`、新增 `notObtained` 桶、`cb_audit_hashes.txt` 10:54:48 已建）；**F2 已修**（CB-23② 断言恢复规格值 8、实测 `cap=8.000000`；产品侧 t43 已同步状态推送）；**F3 部分修**（`CB-10 p3→p2`、`CB-11 p7→p6/f0/b2` 并写明"未取得：granted=0 ⇒ 旧断言退化为恒真"、`CB-19 p6→p7`）；**F12 已修**（`CB-21 p2/f0/b0 → p2/f0/b1`）。**未修**：F4/F5/F7/F8–F11/F13/F14（F7 现行锚点 = `CB_VERIFY.md:305`/`:407`）。计数差值（`pass 197→196`、`boundary 19→21`、`explanation 26→25`、`notObtained +1`）已逐项登记于 `CB_VERIFY.md:37-43`。
> **`F15`（low）登记**：harness `10:33:26 / 0935EED1A26EB669` **无副本、无 `.bak`、无 git 历史**（评审实测 `git log` 为空）⇒ 按纪律 41 标注"某方实测、已被覆盖、不可回溯验证"；**无判据依赖它，不判 blocker**。评审另以 `cb_verify_fast_t27c.txt`(195/0/20/23)、`auth2`/`obs`(194/0/20/23)、`auth`(193/**1**/18/23) 与 `auth2:146` 的**旧 ②′ BOUNDARY 全文**，把"20→19 是消除错误的降级出口"**用归档直接证明** —— 该证据强于 captain 原先的说明，予以采信。
> **纪律增补**：
> 48. **测量窗口未闭合前，不得引用任何计数**：引用前必须核 **① `cb_compilejava.txt` 非空且 mtime ≤ 归档 mtime ② fast/full mtime 相邻且同源 ③ harness mtime < 归档 mtime ④ `cb_run_summary.txt` 四链 exit code 齐全**；四项任一不满足 ⇒ 判"混源快照"，**只登记、不定值**。（本团队已两次栽在"快照时点"：跨时点取值、混源归档。）
> 49. **`prev_state_tNN/` 留存制度化（纪律 46 的执行细则）**：每次重跑前把上一态归档复制进 `prev_state_tNN/`，**必须包含 `CB_VERIFY.md`、fast/full/summary、以及 `harness/CbVerifyHarness.java` 与 `cb_run.ps1`**（本轮 `prev_state_t40/` 仅 5 件、未含 harness ⇒ 补足）。
> 50. **交付物必须落在卡声明的 `inScope` 路径内**：目录声明（如 `.verify/cb_t43_probe/`）**不覆盖**根目录同名档；把输出档平铺在 `.verify/` 根会被门禁判"未声明"并拒收（bank-dev 本轮即因此被拒，captain 迁移三档进声明目录 + 留 `RELOCATION_MANIFEST.txt` 带哈希清单方收口）。**产物路径写错 = 一次无效往返。**

- 代码：7 个新文件 + 14 个既有文件（按 §8 清单）
- 验证：`.verify/CB_VERIFY.md`（总表）+ 各恒等式/工具/开关/持久化证据
- 评审：CB-0..CB-23 逐条 verdict
- 文档：`CENTRAL_BANK_SPEC.md` 的勘误由 architect 按需落（**同一时刻一个写者**）

