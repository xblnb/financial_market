# MACRO_MODEL — 宏观层数学模型与微观接口

> - 产出：macro-expert（宏观周期建模），任务 t2
> - 范围：设计**符合现实宏观经济周期**的宏观数学模型，作为微观层（订单簿 + 行为因子）的「环境输入」。
> - 交付物：本文件 `MACRO_MODEL.md`。
> - 对照代码：`MacroEnvironment` / `MacroStateEngine` / `MacroEconomy` / `EconomicCycleEngine`（F:\financialmarket）与 `MODEL_AUDIT.md`（死代码未接线）。
> - 约束（必须满足）：离线、ServerLevel 主线程 tick 驱动（单线程、无 GPU）、需与微观层解耦、作为微观层环境输入、可复现（可播种）。

---

## 0. 结论速览（TL;DR）

宏观层不该引入重型 DSGE 求解器，而应使用**三层轻量宏观模型**，全部 O(1)/tick、纯数值、可播种：

1. **周期状态机（regime）**：用「隐状态马尔可夫切换（Markov regime-switching）+ 正弦趋势骨架」产出一个**离散周期相位** `Φ ∈ {繁荣, 过热, 衰退, 萧条, 复苏}`，替换现有 `EconomicCycleEngine` 的裸正弦（那只是「看起来像周期」的装饰，且从不被调用）。
2. **货币政策（泰勒规则 + 惯性）**：中央行 `policyRate` 由「泰勒规则 + 平滑项」驱动，通胀与产出缺口反馈到利率，产生**加息→流动性收缩→估值下压→衰退→降息**的负反馈闭环。
3. **宏观环境张量**：把 `利率 r、周期相位 Φ、风险偏好 riskAppetite、流动性乘子 M、恐慌指数 v、通胀 π、信用脉冲` 打包成一个**只读的 `MacroSnapshot`**，作为微观层唯一入口。

**与现有引擎的关系**：`MacroEnvironment`（OU 利率/恐慌/波动）**保留并升级为驱动源**；`EconomicCycleEngine` **改造**（接线 + 状态机化）；`MacroStateEngine` **接线**（产 riskAppetite/creditImpulse/inflationPressure）；`MacroEconomy` **要么接线要么删除**（建议删，因它与其他宏观源重复且从未实例化）。

**时间尺度**：模组中 `1 天 = 24000 tick = 1 年`（`MacroEnvironment.java:46`），因此一个完整现实周期（约 8~10 年基钦+朱格拉）在游戏里是 8~10 个游戏年，节奏需要**压缩到数小时游戏时间**内可见（见 §6）。

---

## 1. 宏观建模理论选型评估

需评估现实宏观经济学主流框架哪种适合 MC 模组离线 tick 约束。

| 框架 | 核心思想 | 数学形式 | 计算量 | 是否契合 | 结论 |
|---|---|---|---|---|---|
| **DSGE（动态随机一般均衡）** | 家庭/厂商/央行理性最优化 + 一阶条件 | 对数线性化方程组，需解线性系统（Klein/Blanchard-Kahn 求解） | 高：每步解线性方程组、需稳态与矩条件 | ❌ | **不采用**。求解器复杂、需要校准稳态、主线程 tick 内跑线性求解器风险高 |
| **RBC（实际经济周期）** | 真实技术冲击驱动波动 | 对数线性 + 卡尔曼平滑 | 中高 | ⚠️ | 可用于「技术/供给冲击」作为周期随机源，但整体过重 |
| **IS-LM / AD-AS（新古典综合）** | 利率-产出-物价的均衡联动 | 2~3 个静态/准动态方程 | 极低 | ✅ | **部分采用**：作为「产出缺口 vs 通胀」的传导骨架 |
| **泰勒规则（Taylor rule）** | 央行按通胀与产出缺口定利率 | `i = r* + π + a·(π−π*) + b·(y−y*)` | 极低 | ✅✅ | **核心采用**：货币政策的现实数学形式，直观、O(1)、天然产生加息/降息周期 |
| **明斯基金融不稳定假说（Minsky）** | 稳定期杠杆累积 → 脆弱性 → 突然崩塌 | 定性 + 相位推进；可用「金融脆弱性指标累积/释放」数值化 | 低 | ✅ | **采用为崩溃触发器**：把「脆弱性累积」做成一个慢变量，积累到阈值触发萧条，复现泡沫-崩盘宏观面 |
| **马尔可夫切换（Hamilton regime-switching）** | 经济在离散状态间跳转，各状态参数不同 | 转移矩阵 + 隐状态；可用连续相位量化 | 低 | ✅✅ | **核心采用**：周期相位 = 隐状态，天然贴合「状态机」，且可播种、可持久化 |

### 结论

**采用「IS-LM/AD-AS 传导 + 泰勒规则货币政策 + 马尔可夫周期状态机 + 明斯基脆弱性」的轻量组合**。数学上每个组件都是 1~2 个常系数差分/ODE，O(1)/tick，主线程完全无压力。DSGE/RBC 只作为概念借鉴（冲击项、均值回归），不实现求解器。

---

## 2. 宏观模型总架构

三层，方向为**宏观 → 微观**单向（解耦，微观不反向改宏观总量；微观只通过「成交/情绪」间接反映到个别变量）：

```
┌───────────────────────────────────────────────────────────────┐
│                      MACRO LAYER (O(1)/tick)                    │
│                                                               │
│  [A] 周期状态机 (CycleRegime)                                   │
│      相位 Φ、隐状态切换、明斯基脆弱性 θ                          │
│                                                               │
│  [B] 货币政策 (MonetaryPolicy)                                 │
│      泰勒规则 → 政策利率 i_ff                                  │
│                                                               │
│  [C] 宏观环境张量 (MacroEnvironment 升级)                       │
│      利率 r、恐慌 v、波动 σ、流动性 M、风险偏好                  │
└───────────────────────────────────────────────────────────────┘
                    │  MacroSnapshot(只读环境输入)
                    ▼
┌───────────────────────────────────────────────────────────────┐
│                      MICRO LAYER                                │
│   MultiAgentManager(行为因子/估值锚)  ← 消费 riskAppetite/利率   │
│   PriceDiscoveryEngine(GBM/GARCH)   ← 消费 σ/μ                 │
│   OrderBook/订单流                   ← 由 agent 决策间接受宏观  │
└───────────────────────────────────────────────────────────────┘
```

宏观层**不直接改价格**，只通过 `MacroSnapshot` 改变微观 agent 的**风险偏好、贴现率、波动基线、涨跌停/熔断阈值、流动性乘子**，再由订单簿撮合成交价——这正是「宏观通过环境输入影响微观行为，微观行为产生价格」的现实耦合方式。

---

## 3. 各组件数学模型

### 3.1 周期状态机 `CycleRegime`（替换/接线 `EconomicCycleEngine`）

**目标**：让经济在 `繁荣→过热→衰退→萧条→复苏→繁荣` 间演进出**符合现实的周期**，而非裸正弦装饰。

**两个机制叠加**：

**(a) 连续相位骨架（供给侧/趋势）**：保留现有正弦思路但去掉 `Math.random()` 抖动，改用可播种、平滑的周期相位：

```
p(t) = A_kit·sin(ω_kit·t + φ0) + A_jug·sin(ω_jug·t) + A_kuz·sin(ω_kuz·t)
```
其中 `ω = 2π/长度`，`A` 为各周期权重，`φ0` 由世界种子派生。这给周期一个**确定性骨架**，避免纯随机导致周期不可复现/不连贯。

**(b) 隐状态马尔可夫切换（内生相位，行为侧）**：定义离散状态 `S ∈ {BOOM, OVERHEAT, RECESSION, DEPRESSION, RECOVERY}`，转移由「脆弱性 θ 与产出缺口 y_gap」驱动：

- **脆弱性 θ**（明斯基）：慢变量，繁荣期累积、萧条期释放：
  ```
  θ_{t+1} = θ_t + κ·(1(繁荣)·(θ_max−θ_t) − 1(萧条)·θ_t)·dt
  ```
  繁荣越久 θ 越高（杠杆/信贷累积），达到 `θ_max` 触发**向衰退/萧条跳转**（泡沫破裂）。
- **产出缺口 y_gap**（IS-LM）：`y_gap = 产出 − 潜在产出`，与利率/情绪相关：
  ```
  y_gap_{t+1} = ρ_y·y_gap_t + β_i·(i_neutral − r_t) + β_s·(riskAppetite_t − 0.5) + ε_y
  ```
- **转移规则**（近似 Hamilton 转移，但用确定性+可播种阈值）：
  ```
  BOOM → OVERHEAT   当 θ 高且 y_gap>0.5（过热累积）
  OVERHEAT→RECESSION 当 θ≥θ_max（脆弱性爆发）→ 崩溃
  RECESSION→DEPRESSION 当恐慌 v>60 且 θ 仍在高位
  DEPRESSION→RECOVERY 当 v 回落到 30 以下、θ 释放至低
  RECOVERY→BOOM     当 y_gap 转正且 θ 低
  ```

**导出周期相位** `Φ ∈ [0,1)`：把状态映射到一个连续值（如 BOOM=0.8, OVERHEAT=0.9, RECESSION=0.3, DEPRESSION=0.1, RECOVERY=0.5），供下游做「周期因子」插值。

**为何符合现实**：这是「周期 + 金融不稳定内生崩溃」的组合，能复现现实中的**长期繁荣→过热→信贷脆弱→突发萧条→漫长复苏**（Minsky + 古典周期），比裸正弦更有因果性。

### 3.2 货币政策 `MonetaryPolicy`（泰勒规则 + 平滑）

**目标**：让利率随通胀/产出缺口调整，产生加息→估值下压→衰退→降息的负反馈。

```
i_ff(t) = ρ_i·i_ff(t−1) + (1−ρ_i)·[ r* + π_t + 1.5·(π_t − π*) + 0.5·y_gap_t ] + ν_t
```
- `ρ_i`：利率平滑系数（现实中央行渐进调息，`ρ_i≈0.8`）→ 加息/降息**有惯性、成周期**，而非一步到位。
- `π*`：目标通胀（模组取 2%）。
- `y_gap`：产出缺口（取自周期状态机）。
- `ν_t`：可播种货币冲击（可选，制造意外加息/宽松）。

**传导**：`r = i_ff` 进入 `MacroEnvironment` 的利率 OU 均值 `r_bar`（或直接作为利率），下游：
- `discountRate = 0.04 + r + p·0.2`（`MacroEnvironment.java:144`）→ **加息降估值**（fairValue 贴现下压）→ 价值 agent 抛售 → 价格下行。
- `getDynamicDrift` 中 `alpha_1·r` 为负（`MacroEnvironment.java:35`）→ 加息压低 GBM 漂移。
- `getLiquidityMultiplier`（`MacroEnvironment.java:136`）→ 加息收缩流动性。

### 3.3 宏观环境张量 `MacroSnapshot`（升级 `MacroEnvironment`）

把现有 OU 过程升级为「由货币政策/周期驱动均值」的完整环境：

```
MacroSnapshot( t ) = {
  r            : 政策利率（泰勒规则结果，替代 OU 独立游走）
  r_bar        : 中性利率（受周期相位影响：繁荣略升、萧条下调）
  panic v      : 恐慌指数（CIR 跳跃扩散，保留；衰退/萧条时均值上移）
  volatility σ : 长期波动基线（受 v 与周期相位影响）
  liquidity M  : 流动性乘子（r 偏离中性 → 收缩）
  riskAppetite : 风险偏好（v 与周期相位共同决定）
  phase Φ      : 周期相位（离散状态 → 连续值）
  inflation π  : 通胀（来自 InflationSystem 或简化泰勒方程）
  creditImpulse: 信用脉冲（来自 MacroStateEngine，见 §3.4）
  fragility θ  : 金融脆弱性（明斯基）
}
```

**关键改动**：把 `MacroEnvironment` 的 `r_bar`、`v_bar`（OU 长期均值）**从常量改为受周期相位/脆弱性驱动**。这样 OU 不再只是「绕着固定均值抖动」，而是「随周期相位移动的均值 + 噪声」，宏观才真正有周期感。

### 3.4 宏观状态 `MacroStateEngine`（接线）

现有 `MacroStateEngine.step`（`MacroStateEngine.java:15-24`）从未被调用，产出 `riskAppetite/creditImpulse/inflationPressure` 恒为 0。接线后由 `MacroSnapshot` 或周期状态机驱动：

- `riskAppetite`：直接取 `MacroEnvironment.getRiskAppetite()`（由 v 与相位决定），不再用 `Math.random()` 抖动。
- `creditImpulse`：与脆弱性 θ 挂钩——繁荣期信用扩张为正、萧条期为负（明斯基的信贷周期）。
- `inflationPressure`：来自简化通胀方程 `π = π* + a·y_gap + 货币增速项`（IS-LM），与泰勒规则的 π 一致。

---

## 4. 宏观 → 微观接口（核心交付）

### 4.1 接口形式

定义**只读 `MacroSnapshot`**，微观层任何模块通过一个方法获取，**禁止微观写回宏观**（解耦）。

```java
public final class MacroSnapshot {
    public final double policyRate;      // 政策利率 (泰勒规则)
    public final double discountRate;    // 贴现率 (用于 fairValue)
    public final double riskAppetite;    // [0,1] 风险偏好
    public final double liquidity;       // [0,1] 流动性乘子
    public final double volatility;      // 长期波动基线 σ
    public final double panic;           // 恐慌指数 v (VIX)
    public final double inflation;       // 通胀 π
    public final double phase;           // 周期相位 Φ∈[0,1) (连续)
    public final double fragility;       // 明斯基脆弱性 θ
    public final int    regime;          // 离散状态枚举
}
```

微观层入口（放入 `MacroEnvironment` 或新建 `MacroService`）：

```java
MacroSnapshot snapshot(long tick);   // 主线程每 tick 取一次
```

### 4.2 微观层如何消费（映射表）

| 微观模块 | 消费的宏观字段 | 注入点（现有代码） | 作用 |
|---|---|---|---|
| `MultiAgentManager`（估值锚） | `discountRate`, `policyRate` | `stepAgents()` 已用 `macroEnv.getDiscountRate()`（`MultiAgentManager.java:158,184-185`） | 加息→贴现率升→fairValue 降→价值 agent 抛售 |
| `MultiAgentManager`（风险偏好） | `riskAppetite` | `macroEnv.getRiskAppetite()`（`MultiAgentManager.java:157`） | 高风险偏好→ agent 更激进入市 |
| `PriceDiscoveryEngine`（GBM） | `volatility`, `policyRate` | `getDynamicVolatility()`/`getDynamicDrift()`（`PriceDiscoveryEngine.java:172-173`） | 宏观波动/利率进 GBM 基准 |
| `PriceDiscoveryEngine`（GARCH 基线） | `volatility` | GARCH 长期基线（P0-1，`ECON_IMPROVEMENT_PLAN`） | 周期相位改变波动聚集基线 |
| 涨跌停/熔断 | `panic` | `getLimitUp/DownThreshold()`（`MacroEnvironment.java:160-170`） | 恐慌→缩涨跌停 |
| 行为因子（羊群强度 a） | `phase`, `fragility` | `MultiAgentManager` 决策层（P1-1 羊群参数） | 过热/萧条期羊群加剧（现实：泡沫与崩盘都是羊群放大） |
| 订单流/做市商 | `liquidity` | `MarketEngine.tick` 已传 `liquidityMultiplier`（`MarketEngine.java:185,204`） | 流动性收缩→做市商缩小报价、点差扩大 |
| 公司基本面（P0-2） | `inflation`, `riskAppetite` | `AICompanyManager.updateAll` 传参 | 通胀升→成本升→利润承压→fairValue 降 |

> **核心思想**：宏观**不改价格**，只改微观 agent 的**效用/风险偏好/估值锚/波动基线**，价格由订单簿内生涌现。这符合现实（央行不直接报股票价，但通过利率/流动性改变市场参与者的估值与风险偏好）。

### 4.3 周期因子（行业/资产差异化）

除总量环境外，宏观还应给**不同行业/资产**提供差异化因子（现实：利率敏感型 vs 防御型）：

```
industryFactor(industry, phase) = 基础敏感度 · f(phase)
```
- 周期股（可选行业如：原材料、制造）：繁荣期强、萧条期弱。
- 防御/必需：各周期较稳。
- 高杠杆/成长：加息期（OVERHEAT→RECESSION）估值下压最大。
用 `phase` 连续值插值，可在 `MarketEngine` 或 `AICompanyManager` 中消费。

---

## 5. 与现有引擎的对照与接线路径

### 5.1 现状诊断（对照 `MODEL_AUDIT`）

| 现有类 | 现状 | 问题 |
|---|---|---|
| `MacroEnvironment` | **唯一在跑**的宏观源（`PriceDiscoveryEngine.step()` 调用 `macroEnv.step`） | OU 均值是常量，周期感弱；`r_bar/v_bar` 不随周期变 |
| `MacroStateEngine` | 构造了 `macroEngine`，仅 `save/load` | `step` 从不调用（`MarketEngine.java:372,419`），riskAppetite 恒 0 |
| `EconomicCycleEngine` | 构造字段 `cycleEngine`，仅传给 `PriceDiscoveryEngine` 赋值（`PriceDiscoveryEngine.java:44,70`） | `tick` 从不调用（`MarketEngine.java:54,84`），裸正弦未接线 |
| `MacroEconomy` | 全工程无实例化 | 死代码；与其他宏观源重复 |
| `InflationSystem` | 实例化但主要影响商品价格 | 通胀与周期/货币未闭环 |

### 5.2 接线与改进路径（分 P0/P1 对齐 `ECON_IMPROVEMENT_PLAN`）

**P0（接线最小必要集，与 `ECON_IMPROVEMENT_PLAN` P0-6 一致）**：
1. **`MacroEnvironment` 升级**：`r_bar`/`v_bar` 改为受周期相位驱动；暴露 `MacroSnapshot`。
2. **`CycleRegime`（新）替换 `EconomicCycleEngine`**：实现 §3.1 状态机，暴露 `phase`/`regime`/`fragility`。在 `MarketEngine.tick` 接线调用（替代 `cycleEngine.tick` 死代码）。
3. **`MacroStateEngine.step` 接线**：在 `MarketEngine.tick` 调用，产出 `riskAppetite/creditImpulse/inflationPressure`（`MacroStateEngine.java:15-24`），不再恒 0。
4. **`MacroEconomy` 删除**：因重复且从未实例化，避免误导（`ECON_IMPROVEMENT_PLAN` §1.4 的建议是「接入或明确删除」，此处建议删除，因其 GDP/通胀/失业与 `MacroEnvironment`/`InflationSystem` 重复且量纲独立）。

**P1（宏观→行为因子闭环）**：
5. 让 `phase`/`fragility` 驱动行为因子参数（羊群强度 a、损失厌恶 λ）——过热/萧条期羊群加剧（§4.2）。
6. 泰勒规则利率接入 `discountRate`，使加息真实传导到估值与成交价（`ECON_IMPROVEMENT_PLAN` P0-4 混合锚）。

### 5.3 接线时序（在 `MarketEngine.tick` 内）

```
MarketEngine.tick(level):
  1.  macroEnv.step(tick)          // 现有，改为驱动 MacroEnvironment
  2.  cycleRegime.tick(tick)       // 新增接线：周期相位/脆弱性
  3.  monetaryPolicy.tick(tick)    // 新增接线：泰勒规则
  4.  macroEngine.step(state, data) // 接线 MacroStateEngine
  5.  macroSnapshot = macroEnv.snapshot(tick)  // 打包环境
  6.  priceEngine.step()           // 消费 σ/μ
  7.  对每个资产：agentManager.stepAgents(..., macroSnapshot)  // 消费风险偏好/贴现率/相位
  8.  撮合 → 成交价
```

---

## 6. 时间尺度与可复现性

### 6.1 时间尺度

- 模组 `1 天 = 24000 tick = 1 游戏年`（`MacroEnvironment.java:46`，`DT_MACRO = 20/24000`）。
- 现实基钦周期 ~3 年、朱格拉 ~9 年，直接映射为 3/9 游戏年太长。**建议压缩**：把周期长度缩放系数设为可配置（如 `cycleLengthScale`，默认让一个完整「繁荣→萧条→复苏」在约 1~2 个游戏日内走完，即 `24000~48000 tick`），保证玩家在合理游玩时间内能观察到周期。这是模拟类模组的合理取舍（游戏时间 ≠ 现实时间）。
- **与微观 tick 同步**：宏观每 `20 tick` 更新（`MacroEnvironment.java:86`），微观每 `20 tick` AI 挂单（`MarketEngine.java:70`）——两者步长一致，宏观 snapshot 每 20 tick 刷新即可，无需更高频。

### 6.2 可复现性（可播种）

- 所有宏观随机源（周期相位初相、OU 冲击、货币政策冲击、状态转移判定）**统一使用世界种子派生的 `SeedableRandom`**，替换 `MacroEnvironment.java:17` 的裸 `new Random()`、`EconomicCycleEngine.java:25` 的 `Math.random()`。
- `MacroSnapshot` 各字段随状态持久化（`MarketEngine.save/load`），保证读档后宏观连续。
- 参考 `ECON_IMPROVEMENT_PLAN` §6（可播种 PRNG 方案）。

---

## 7. 计算量与可行性

| 组件 | 每 tick 运算 | 存储 | 可行性 |
|---|---|---|---|
| 周期状态机 | ~10 次算术/阈值比较 | 5 个 double + 1 状态 | ✅ 极低 |
| 泰勒规则 | ~5 次算术 | 2~3 double | ✅ 极低 |
| MacroEnvironment 升级 | 维持现有 OU（3 个变量） | 不变 | ✅ 极低 |
| MacroStateEngine 接线 | ~5 次算术 | 3 double | ✅ 极低 |
| MacroSnapshot 打包 | 构造 1 个对象 | 若干 double | ✅ 极低 |

**结论**：宏观层全部 O(1)/tick，主线程 tick 完全无压力（相比微观订单簿/GAN 是天壤之别）。宏观层计算成本可忽略，瓶颈在微观层（见 micro-expert 的 t1）。

---

## 8. 持久化兼容（对齐 `ECON_IMPROVEMENT_PLAN` §5.1）

- 新增宏观状态字段（相位、脆弱性、政策利率惯性、OU 均值）写入 `engine_kv` 的 `engine_main` blob（整块 NBT 天然支持加字段）。
- `MacroStateEngine.save/load`（`MacroStateEngine.java:28-29`）目前返回空 NBT——接线后需真正序列化 riskAppetite/creditImpulse/inflationPressure。
- 旧存档缺字段时用默认值重建（复用 `MarketAttachment` 空资产兜底）。
- H2 表结构：宏观状态在 `engine_kv` blob 内，无需新增关系表列（如需 UI 展示再增量加列）。

---

## 9. 验收指标（对照 `ECON_IMPROVEMENT_PLAN` §7）

| # | 指标 | 宏观层贡献 | 验收 |
|---|---|---|---|
| 1 | 波动聚集 | 周期相位改变 σ 基线（GARCH 叠加） | 繁荣/萧条期 σ 明显不同 |
| 2 | 估值-利率负相关 | 泰勒规则 → discountRate → fairValue | 加息时成交价可观察下行 |
| 3 | 周期相位可观察 | `phase` 驱动行业因子/情绪 | 玩家在数小时游戏时间内看到繁荣→萧条切换 |
| 4 | 泡沫-崩盘 | 明斯基脆弱性 θ 触发萧条 | 长期繁荣后出现突发崩盘 |
| 5 | 可复现 | 播种宏观源 | 同种子两次运行周期一致 |
| 6 | 死代码消除 | MacroEconomy 删除/接线 | 无恒 0 或永不调用的宏观子系统 |

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 周期过长玩家无感 | 玩家看不到宏观 | `cycleLengthScale` 可配置压缩 |
| 泰勒规则参数不当 → 利率振荡 | 估值剧烈波动 | 平滑系数 ρ_i≈0.8；硬上限 r∈[0.001,0.10]（`MacroEnvironment.java:93`） |
| 接线引入 NPE/回归 | 启动/读档失败 | 复用空资产兜底；每接一个子系统单测 |
| 周期相位驱动羊群强度过强 | 价格发散 | 参照 P1 参数硬上限（`k_fb`/`a`），宏观只微调参数基线 |

---

## 11. 结论

宏观层采用**「周期状态机（马尔可夫切换+明斯基脆弱性）+ 泰勒规则货币政策 + 升级版 MacroEnvironment 环境张量」**三层模型，全部 O(1)/tick、可播种、可持久化，完全契合离线主线程 tick 约束。它通过**只读 `MacroSnapshot`** 单向注入微观层（风险偏好/贴现率/波动基线/流动性/相位），不直接改价，由订单簿内生定价——这是现实「央行调利率→市场参与者改变行为→价格」的忠实模拟，也为微观行为因子（羊群/损失厌恶）提供「周期化」的宏观环境输入。
