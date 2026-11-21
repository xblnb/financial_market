# 跨资产宏观传导层（World ↔ Stock ↔ Futures ↔ Options ↔ Players）

## 目标

股票、CZCE 期货、SABR 期权、玩家订单流不再各自独立：世界宏观状态和玩家行为生成有界信号，分别作用于三个市场，并互相反馈。

## 新增

- `engine/macro/cross/CrossAssetMacro.java`：纯 Java、无 Minecraft 依赖的传导核心。
- `GET /api/crossmacro`：只读快照。
- 前端「宏观」页新增跨资产卡片、行业 Tilt、玩家净流、期权 vol/skew。

## 传导通道

### 世界 → 资产

- 世界 `productionIndex / cpi / eventRisk` 与 `MacroEnvironment` 的风险偏好/利率：
  - 股票：行业 tilt + 全局 tilt → `PriceDiscoveryEngine` 的 TABL 目标乘数；
  - 期货：`futuresSpotMultiplier` → `czceSpots()` 的现货乘数；
  - 期权：`optionVolMultiplier / optionSkewShift` → `CzceOptionDesk` 的 SABR α 与 ρ。

### 资产 → 资产

- 股票指数收益 → 期货需求、期权风险溢价。
- 商品/期货收益代理（PPI）→ 能源/材料/食品/工具等行业的成本与收入 tilt。
- 利率变化 → 金融行业 tilt；风险事件 → 全行业风险折价。

### 玩家 → 宏观

- 股票订单：`recordStockOrder` 累计净名义金额；
- 期货成交：`CzceFuturesMarket.FlowListener` 回调 `recordFuturesFlow`；
- 杠杆/危机压力：`setLeverageStress`；
- 玩家净流影响股票短周期 tilt、期货 spot 乘数、期权 skew。

### 输出回馈

- `PriceDiscoveryEngine.setCrossAssetTilt(assetId, tilt)`：只叠加在 TABL 目标上，仍受涨跌停和订单簿约束。
- `MarketEngine.czceSpots()`：商品/基准现货 × 期货宏观乘数。
- `CzceOptionDesk.setMacroStress(volMultiplier, skewShift)`：只在生成期权链时对 SABR 参数做临时缩放，不改持久化基准参数。

## 关键公式（有界）

```text
demand = 0.55·growth + 0.30·(riskAppetite−0.5) − 0.22·eventRisk
         + 0.12·tanh(stockReturn·12)

futuresSpotMultiplier = 1 + 0.35·demand + 0.12·tanh(stockReturn·20)
                        + 0.06·playerFuturesFlow − 0.10·eventRisk
                        − 0.05·leverageStress       ∈ [0.85, 1.15]

optionVolMultiplier = 1 + 0.55·eventRisk + 0.35·|stockReturn|·20
                      + 0.20·leverageStress + 0.10·|commodityReturn|·15
                      − 0.10·demand                 ∈ [0.75, 1.80]

optionSkewShift = −0.10·eventRisk + 0.05·playerStockFlow − 0.03·demand
                  + 0.08·tanh(stockReturn·20) − 0.04·leverageStress
                                                    ∈ [−0.20, 0.15]
```

行业 tilt 按 `finance / energy / materials / food / tools / services / other` 计算，单行业限制 ±4%。

## API

```text
GET /api/crossmacro
```

返回：

```json
{
  "stockReturn": 0.012,
  "commodityReturn": 0.006,
  "riskAppetite": 0.58,
  "growth": 0.014,
  "inflation": 0.008,
  "eventRisk": 0.22,
  "playerStockFlow": 0.31,
  "playerFuturesFlow": -0.08,
  "leverageStress": 0.18,
  "futuresSpotMultiplier": 1.018,
  "optionVolMultiplier": 1.12,
  "optionSkewShift": -0.045,
  "stockGlobalTilt": 0.008,
  "sectorTilts": {"finance":0.004,"energy":0.012,...}
}
```

## 接线点

```text
MarketEngine 每 20 tick：
  worldMacro.step
  → crossAssetMacro.step(WorldMacroSnapshot, MacroEnvironment, index, commodityProxy, stress)
  → priceEngine.setCrossAssetTilt(asset, cross.stockTilt(industry))
  → czceOptions.setMacroStress(...)
  → czceFutures.tick(..., czceSpots() * futuresSpotMultiplier)
```

- `submitOrder` 记录股票订单流。
- `CzceFuturesMarket.open/close` 通过 `FlowListener` 记录期货流。
- 期权链读取宏 stress：α×volMultiplier、ρ+skewShift。

## 验证

- `CrossMacroProbe`：**10/10**
  - tilt/spot/vol 有界；
  - 行业 map 完整；
  - 玩家流改变 tilt；
  - 杠杆压力提高 vol；
  - 负股票收益压低 skew。
- `./gradlew compileJava`：**BUILD SUCCESSFUL**
- `./gradlew clean jar`：**BUILD SUCCESSFUL**
- 最终 jar：`22,790,705 bytes`

前端宏观页实际渲染：

![跨资产宏观](.verify/frontend_crossmacro.png)

## 边界

1. 股票订单流按“提交订单”累计，未区分最终成交/撤单；是短周期近似。
2. 商品收益目前用世界 PPI 代理，不是逐品种期货收益；后续可换成 CZCE 加权收益。
3. 传导只改变目标/乘数，不绕过订单簿、涨跌停、保证金。
4. 期权 vol/skew 是临时宏乘数，不覆盖持久化 SABR 基准参数。
5. 玩家流与信号未单独写入 H2；读档后从下一 tick 重新累积。引擎主体状态仍由 H2 blob 持久化。
