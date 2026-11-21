# TABL-ABM 框架规格（v0.2，已按你的选择修订）

> **TABL** = **T**emporal **A**ttention **B**i**l**inear Layer（多变量时序预测双线层）
> **ABM** = Agent-Based Model
> **一句话**：TABL 把多变量历史压成未来 H 步的宏观锚，Kronos 提供 K 线先验，CoinTossX 作为 Agent 的撮合场所组装市场，三者共用 MC 时间轴。

已确认：**先影子、达标后替换**现有撮合；锚点**日线 + 盘中重估**；Agent **适配现有 MultiAgentManager**。

---

## 1. 分层

```
L4  TABL 层     TemporalAttention + Bilinear 双线 → AnchorPath（H 步、多资产）
     ↑ Kronos 先验（日线 H 步 K 线预测）
L3  锚点服务    KronosAnchorService（日更，异步）+ AnchorCorrection（盘中重估，闭式）
L2  行为层      MultiAgentManager 适配器 → TabOrderIntent
L1  场所层      TabMarket：每资产一个 CoinTossXEngine（影子簿）
L0  时间层      TabClock = TradingCalendar 的只读投影（唯一时间权威）
```

## 2. 时间契约（不变，唯一权威）

`TradingCalendar` 的 tick 计数器是唯一时间源：一个交易日 `TRADING_DAY_TICKS = 6000` tick，
5 开市 + 2 休市。`TabClock` 只读投影出 `globalTick / day / tickInDay / dayFraction / phase / stepIndex`
（`stepIndex = tickInDay / STEP_TICKS`，默认 `STEP_TICKS = 20` → 300 步/日）。

硬规则：

1. 订单 `submittedTime = globalTick`；wall clock **不得**进入 CoinTossX 优先级排序。
2. 日线锚只在 `isTradingDayStart()` 的同一 tick 生效；盘中只更新**修正项**，不替换日线路径。
3. 一步内 `agents.emit() → match() → mark()` 原子完成。
4. 同世界种子 + 同 tick 序列 ⇒ 锚、订单、成交、标记价逐位可复现；**任何组件禁 `Math.random()`**。

## 3. TABL 层（Temporal Attention Bilinear Layer）

### 3.1 输入/输出

- 输入：`X ∈ R^{L×V}`，`L = 60` 个交易日窗口，`V` = 资产数 + 宏观变量（指数、利率、波动率、情绪…），
  按 `day` 逐行对齐；缺失值按前值填充并在 mask 中标记。
- 输出：`Ŷ ∈ R^{H×V}`，`H = 20` 步（日），对每个资产给出**锚路径**；`band` 由残差方差给出。

### 3.2 双线结构（"双线"即两条并行支路 + 融合）

**线 A｜Temporal Attention（时间注意力）**

- 对每个变量独立做因果自注意力：`α_t = softmax(q_tᵀK / √d)`，`c_t = Σ α_t V_t`；
- 投影 `q/k/v` 为固定正交基（seed 派生，**不训练**），因此注意力权重是数据的确定函数；
- 2 个头（短期/长期尺度，温度不同），拼接得到 `c ∈ R^{V×d}`。

**线 B｜Bilinear（双线性跨变量交互）**

- 低秩双线性：`u = tanh(X W_u)`，`v = tanh(X W_v)`，`z = uᵀ W_b v`（`W_b` 低秩 `r=4`）；
- 捕捉"资产 × 宏观"的交互（例如利率↑ × 银行股），输出 `z ∈ R^{V}`。

**融合与读出（唯一可训练部分）**

- `ŷ = W_o [c ; z] + b`，`W_o` 用**岭回归闭式解**在滚动窗口上拟合（`λ` 可配），
  目标为未来 H 步收益；**无梯度、无外部框架、毫秒级**。
- 先验混合：`anchor = w_k · kronosPath + (1 − w_k) · tablPath`，`w_k` 同样由岭回归拟合（按资产）。
- Kronos 冷启动（历史不足 `L` 日）时 `w_k = 1`，退化为纯 Kronos 锚。

### 3.3 盘中重估（你的选择）

- **日线层（Kronos）每日一次**：`isTradingDayStart()` 投递后台任务，结果在下一日边界前完成；
  未完成沿用上一日锚并记 `staleAnchor`。
- **盘中层（TABL 修正）每 `R` 步一次**（默认 `R = 20`，即每 400 tick）：
  用截至当前 tick 的已实现价格重算 `AnchorCorrection`（岭回归，O(V²)，微秒级），
  `anchorAt(t) = dailyAnchor(t) + correction(t)`；**只用已发生数据**，无前视。
- 修正幅度钳制：`|correction| ≤ clamp × band`（默认 1.0），防止盘中噪声把锚拉飞。

### 3.4 无前视断言（规格级）

- 生成 `day D` 日线锚时，`day D` 的 bar **不得**进入输入（窗口截止 `D−1`）。
- 盘中修正使用 `tick ≤ now` 的数据；重估时刻必须落在 `stepIndex % R == 0`。
- 验证探针会在 `D` 日锚生成前后注入"D 日数据"，断言锚逐位不变（lookahead detector）。

## 4. 行为层（适配现有 MultiAgentManager）

- `TabAgentAdapter` 包装现有 `MultiAgentManager` 的 `AgentType`（RETAIL / MOMENTUM / MARKET_MAKER / VALUE）：
  - 每个 agent 的**公允价/漂移/报价中枢**由 `anchorAt(t)` 替换（原来是宏观环境/随机游走）；
  - 现有 `MicroRole`、`MesoLayerManager`、`OrderPipeline` 保留，只把"锚"注入参数。
- 输出统一为 `TabOrderIntent{side, type, price, qty, tif, agentId}` → `TabOrderGateway`。
- 锚点约束：`kAnchor` 把 `(mark − anchor)` 映射为净不平衡；`|dev| > band` 时 VALUE/MM 加码回归。

## 5. 场所层（CoinTossX，影子优先）

- 每资产一个 `CoinTossXEngine`；`TabOrderGateway` 把 intent 转 `CtOrder`（`submittedTime = globalTick`）。
- 每步结束：`mark = 最近成交价（无成交取 mid）`，写 `TabBookView`（best bid/offer、深度、成交）。
- **阶段 1（影子）**：`tabl.enabled=false` 时零行为；开启后影子簿与现有 `PriceDiscoveryEngine` 并行，
  记录 `shadowSpreadDiff / markDiff / fillRatio` 用于达标判定。
- **阶段 2（达标后替换）**：达标判据（建议）：连续 ≥ 60 交易日，
  `markDiff` 的 95 分位 ≤ 1%，`fillRatio ∈ [0.8, 1.2]`，无自成交、无负库存；
  满足后由配置切到主路径（保留一键回退）。

## 6. 持久化与开关

- NBT：`tabl.day`、`tabl.anchors[H][V]`、`tabl.band`、`tabl.lastMark`、`tabl.wk`、`tabl.staleAnchor`、`tabl.correction`。
- 开关：`tabl.enabled`（默认 **false**）、`tabl.stepTicks=20`、`tabl.reestimateSteps=20`、
  `tabl.horizon=20`、`tabl.lookback=60`、`tabl.ridge=1e-3`、`tabl.clamp=1.0`、`tabl.replacePriceDiscovery=false`。

## 7. 分阶段交付

| 阶段 | 内容 | 验收 |
|---|---|---|
| **T1** | `TabClock`；`AnchorPath`；`MarketDayStamps`；`TemporalAttention`+`Bilinear`+`Ridge`（`TemporalBilinearModel`）；`KronosAnchorService`（异步 + 缓存 + 确定性） | **已完成 11/11**（`.verify/TABL_T1_VERIFY.md`）；`KronosAnchorProvider` 待 T2 |
| **T2** | `TabMarket` / `TabOrderGateway` / `TabAgentAdapter`（接 MultiAgentManager） | **部分完成**：`KronosAnchorProvider` + `TabMarket` + `TabOrderIntent` 8/8（`.verify/TABL_T2_VERIFY.md`）；`TabAgentAdapter` 待 T3 |
| **T3** | 影子接线 + 达标指标 + NBT + 开关 | **部分完成**：`TablAnchorManager`（日切/盘中重估/钳制/回退）6/6（`.verify/TABL_T3_VERIFY.md`）；`MarketEngine` 接线与 NBT 待 T4 |
| **T4** | 替换路径 + 验证文档 | `.verify/TABL_*` 证据；`MODELS.md` 更新 |

## 8. 已确认决策

1. TABL = Temporal Attention Bilinear Layer（多变量时序预测双线层）。
2. 先影子市场，达标后替换 `PriceDiscoveryEngine`（一键回退）。
3. 日线锚 + 盘中重估（Kronos 日更、TABL 修正项每 20 步重估，无前视）。
4. Agent 适配现有 `MultiAgentManager`（不新写一套 agent 行为）。
