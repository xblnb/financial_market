# AK + Cobb–Douglas 生产函数层

## 定位

产线资产不再只做静态估值，而是进入两条互补的生产函数：

- **AK 内生增长**：`Y = A·K`，负责资本积累、增长率、资本回报、Tobin's q；
- **Cobb–Douglas**：`Y = A·K^α·L^(1−α)`，负责 TFP、MPK/MPL、最优资本与资本缺口。

AK 是 CD 在 `α = 1` 时的特例。两者共用同一套观测产出/资本/劳动数据，不互相替代。

## AK

```text
Y = A·K
I = operatingProfit × 0.5 + retainedEarnings × 0.10
I = I / (1 + adjustmentCost × I/K)
K' = (1−δ)·K + I
g = K'/K − 1
r = A − δ
q = 1 + g / max(0.02, r + δ)
fairValue = K' × q
```

- `K` 初始来自 `replacementCost × 0.82 + inventory × 0.5`。
- `A` 优先用实际日产出反推：`A = output / K`；无产出时用世界宏观 TFP。
- `δ = 0.04 + 0.03 × assetRisk`，钳制 `[0.02, 0.12]`。
- 投资受 `60% × K` 上限和安装成本约束。
- 无利润、无留存收益的闲置公司，资本会按折旧收缩，增长为负。

## Cobb–Douglas

```text
Y = A·K^α·L^(1−α)
TFP = Y / (K^α·L^(1−α))
MPK = α·Y/K
MPL = (1−α)·Y/L
r = MPK − δ
optimalK = α·Y / (targetReturn + δ)
capitalGap = (optimalK − K)/K
```

- `α = 0.35`（资本份额），劳动份额 `1−α = 0.65`。
- `L` 是劳动力代理：在线玩家数 × 公司资本占比，最低 `0.1`。
- `targetReturn = 5%`。
- `capitalGap > 0` 表示资本不足、应继续投资；`< 0` 表示资本过剩/闲置。

## 数据流

```text
TechStorageSampler 日差分
  → 实际产出/投入/能耗/库存
  → CompanyData.production*
  → AssetScanner 资产估值
  → AkGrowthEngine
       ├─ AK: capital/output/investment/growth/return/fairValue
       └─ CD: output/TFP/MPK/MPL/optimalCapital/capitalGap
  → 公司基本面 / 股票 / 跨资产宏观 / API
```

宏观侧把 AK 聚合增长和回报送入 `CrossAssetMacro`：

```text
demand += 0.18 × AK growth
stockGlobalTilt += 0.008 × tanh(AK growth × 40)
optionVolMultiplier −= 0.15 × AK growth
```

## 公司字段

写入 `CompanyData`：

```text
akCapital, akOutput, akInvestment, akGrowth, akReturn, akTfp, akFairValue
cdOutput, cdLabor, cdTfp, cdMpk, cdMpl, cdOptimalCapital, cdCapitalGap
```

随 `engine_kv.engine_main` blob 持久化。

## API

```text
GET /api/ak
```

返回聚合：

```json
{
  "capital": 4800000000,
  "output": 286000000,
  "investment": 124000000,
  "growth": 0.021,
  "returnRate": 0.058,
  "tfp": 0.061,
  "savingsRate": 0.43,
  "cobbDouglas": {
    "output": 286000000,
    "tfp": 1.82,
    "mpk": 0.021,
    "mpl": 0.058,
    "capitalShare": 0.35,
    "laborShare": 0.65,
    "optimalCapital": 5100000000,
    "capitalGap": 0.062
  },
  "companies": [...]
}
```

前端「宏观」页展示 AK 资本/产出/增长/回报，以及 CD 产出/TFP/MPL/资本缺口。

## H2

`company` 表新增：

```text
ak_capital, ak_output, ak_investment, ak_growth,
ak_return, ak_tfp, ak_fair_value,
cd_output, cd_labor, cd_tfp, cd_mpk, cd_mpl,
cd_optimal_capital, cd_capital_gap
```

## 验证

- `AkCdProbe`：**13/13**
  - AK 产出/资本/增长/回报；
  - CD 产出、TFP 反推、MPK/MPL、最优资本、资本缺口、要素份额；
  - AK 聚合。
- `./gradlew compileJava`：**BUILD SUCCESSFUL**
- 最终 jar：`build/libs/financialmarket-1.0.0-full.jar`（22,855,244 bytes）。

## 边界

1. 劳动力是“在线玩家数 × 资本占比”的代理，不是真实员工/工时系统。
2. `α = 0.35` 固定，没有按行业/科技等级估计。
3. 产出仍是容器/产线库存日差分，玩家手动存料会被近似记为产出。
4. AK 的投资只来自经营利润与留存收益，没有外部股权融资/债务融资进入资本积累。
5. CD 的最优资本用固定目标回报 5%，没有内生于利率曲线。
6. 没有真实服务器“产线运行多日 → AK/CD 参数稳定” smoke；目前为纯模型探针 + 编译验证。
