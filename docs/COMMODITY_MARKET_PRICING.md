# 商品市场定价：自动初始价值 + 现实价格动态

## 定位

商品市场交易原版/模组物品与流体。定价分两层：

1. **初始价值**：配方链、加工成本、稀有度、堆叠/耐久/食物/mod 命名空间；
2. **价格动态**：成本锚 + 供需 + 均值回归 + 动量 + 波动聚集 + 季节性 + 供给冲击。

价格仍受成本上下限约束，不会归零或无限上涨。

## 自动初始定价

`CommodityRegistry.autoRegister` 遍历全部物品/流体，`basePriceOf`：

```text
recipeCost = min over recipes( Σ 原料递归基价 × 数量 / 产出数 )
craftMarkup = 工具/盔甲 +20%
rarityFloor = 路径稀有度（下界合金 60 / 钻石 40 / 金 20 / 铁 10 / 矿石 8 ...）
intrinsicFloor = 堆叠、耐久、食物营养、物品稀有度、mod 命名空间
basePrice = max(recipeCost × (1+markup), rarityFloor, intrinsicFloor)
```

intrinsicFloor 细节：

- 不可堆叠（工具/机器） ×1.40；
- 耐久 `1 + min(1, maxDamage/1000)`；
- 食物 `max(floor, nutrition × 0.25)`；
- Rarity：UNCOMMON ×1.10、RARE ×1.35、EPIC ×1.75；
- 模组命名空间保底：GregTech 12、Mekanism 6、AE2 5、Create/Thermal/IE 4、其它 mod 1.5。

配方图递归深度 4、带环保护；无配方物品不会被打成 0。

## 公允价值

```text
excess = (demand − supply) / (demand + supply)
fair   = costAnchor × (1 + 0.20 × excess) × macroFactor
floor  = costAnchor × 0.20
cap    = costAnchor × 5.00
```

- 需求大于供给 → 公允价值上升；
- 供给大于需求 → 公允价值下降；
- 宏观通胀/M2 通过 `macroFactor` 整体抬升；
- 成本锚随上游配方价格缓慢收敛，形成成本推动。

## 日频价格动态

```text
seasonal = 0.045·sin(phase−0.6)   农作物
           0.035·cos(phase)       能源
           0.020·sin(phase+0.4)   建材

shock    = deterministicShock(key, day) × (0.004 + 0.55·volatility)
price'   = price + 0.12·(fair−price) + 0.18·momentum
           + price·(0.25·seasonal + shock)

momentum' = 0.78·momentum + 0.22·return
volatility' = 0.92·volatility + 0.08·|return| + 0.004·|shock|
```

- **均值回归**：价格偏离公允价值后缓慢回归；
- **动量**：短期趋势延续；
- **波动聚集**：大波动后波动率上升；
- **季节性**：农作物/能源/建材按 365 天周期；
- **稀有供给冲击**：约每 47 天一次，确定性哈希，非 `Math.random`；
- **粘性报价**：单次交易只向公允价值移动 35%，避免每次点击瞬间跳价；
- 价格始终被 `[costAnchor×0.20, costAnchor×5.00]` 钳制。

## 数据与 UI

- `GET /api/commodities` 返回：
  ```text
  price, basePrice, costAnchor, fairValue,
  momentum, volatility, seasonality, lastReturn,
  supply, demand, change, dayChange, sparkline
  ```
- 前端新增「商品市场」页：
  - 价格 / 日涨跌 / 相对基准；
  - 公允价值 / 成本锚；
  - 动量 / 波动率 / 季节性；
  - 供给 / 需求；
  - 30 日走势。
- 每个商品保留最近 120 个日价格；随引擎 blob 保存/读档。

## 验证

- `CommodityPriceProbe`：**10/10**
  - 需求抬升公允价值、供给压低；
  - 宏观因子抬升；
  - 价格/波动/动量有界；
  - 农作物季节性；
  - 钻石无季节性；
  - 同日同商品的冲击完全确定。
- `./gradlew compileJava`：**BUILD SUCCESSFUL**
- 最终 jar：`build/libs/financialmarket-1.0.0-full.jar`（22,864,924 bytes）。

## 边界

1. 没有真实订单簿/库存仓单，供给需求由交易、世界宏观、配方下游传播近似。
2. 波动率是 EWMA + 确定性冲击，不是校准过的 GARCH/随机波动率。
3. 季节性只按物品路径粗分类，没有作物生长季/能源需求的真实日历。
4. 跨商品相关只通过配方上下游成本传播，没有原油→化工→塑料这类完整产业链弹性。
5. 初始定价是成本/稀有度法，不是可比公司/拍卖行成交价法。
6. 未做真实服务器“数万物品自动定价 + 60 天价格路径” smoke；目前为纯模型探针 + 前端 mock 验证。
