# 玩家产线 → 公司基本面估值

## 目标

科技包玩家的机器、多方块、产线和库存不再只是方块，而是进入公司基本面：

```text
绑定区域
  → 扫描容器 + 机器/多方块
  → 重置成本 / 折旧 / 利用率 / 集中度
  → 存货价值 + 产线价值
  → 净资产 NAV / 近期利润 / IPO 定价 / 财报 / 股票基本面
```

## 绑定

```text
/fmarket company bind-area <x1> <z1> <x2> <z2>
/fmarket company scan
/fmarket company info
```

- `bind-area` 同时扫描：
  - `BaseContainerBlockEntity` → 库存容器（受等级 `maxContainers` 限制）；
  - 机器/多方块 → `boundProductionBlocks`（上限 `max(64, maxContainers × 16)`）。
- `scan` 只读扫描，不产生随机数。
- 产线方块位置写入 `CompanyData` NBT，随 H2 `engine_kv.engine_main` 持久化。

## 机器识别

不硬编码单个 mod，按 namespace / block path / BlockEntity 类名识别：

- 关键词：`machine / controller / multiblock / reactor / boiler / turbine / crusher /
  smelter / assembler / fabricator / refinery / centrifuge / electrolyzer / enricher /
  chamber / hatch / port / farm / harvester ...`
- 普通 `chest / barrel / crate / cabinet / drawer / tank` 只算库存，不算产线。
- Mod 权重：
  - GregTech/GTCEu 最高；
  - Mekanism 次之；
  - Create / IE / Thermal / PneumaticCraft / Industrial Foregoing；
  - AE2 / Botania / Ars Nouveau / Occultism / Blood Magic。
- 多方块控制器有 3.2× 重置成本溢价；LV/MV/HV/EV/IV/LuV 等 tier 每级 +35%。

## 估值公式

```text
replacementCost = base(mod) × multiblockPremium × tierMultiplier × energyPremium
depreciated     = replacementCost × (0.82 − 0.10 × concentration)

outputItems     = Σ 当日容器/产线库存增加
inputItems      = Σ 当日容器/产线库存减少
throughputUtil  = outputItems / max(64, machineCount × 64)
energyUtil      = energyConsumed / max(1, machineCount × 2000)
utilization     = max(throughputUtil, energyUtil × 0.6)，钳制 [0, 1]

assetRisk       = 0.12 + 0.32×concentration + 0.18/machineCount
                  + 0.15×(1−utilization)
assetQuality    = 0.65 + 0.25×utilization − 0.20×assetRisk
productiveValue = depreciated × (0.15 + 0.85×utilization) × assetQuality
appraisedValue  = inventoryValue + productiveValue
```

- `inventoryValue`：绑定容器/产线库存 × 商品基准价/确定性默认价。
- `netAssetValue` 使用 `appraisedValue`。
- **闲置机器只保留 15% 残值/清算价值**，不会“摆着不动还按满产估值”。
- `operatingProfit = 日产出价值 − 日投入价值`。
- `recentProfit`：
  ```text
  10% × 存货正向增值 + 50% × operatingProfit
  ```
  若当日没有运行流，回落到 `0.02%/日 × productiveValue × utilization`。
- 机器数/多方块数/利用率/风险/质量写入 `CompanyData`。

## 实际运行数据

`TechStorageSampler` 每个游戏日对绑定容器 + 产线方块做差分：

- 库存增加 → 视为当日产出（`productionOutputValue`）；
- 库存减少 → 视为当日投入/消耗（`productionInputValue`）；
- 能源存储下降 → `energyConsumed`；
- 法力/魔力存储下降 → 世界宏观 `MACHINE_RUN` 消耗；
- 首日只建立基线，不把已有库存误记为产出。

写入 `CompanyData`：

```text
productionOutputValue
productionInputValue
productionThroughput
productionUtilization
inventoryTurnover
operatingProfit
energyConsumed
```

同时把 `MACHINE_RUN` 事件提交给 `WorldMacroEngine`，统一归到 `company:<stockId>`，与合成/放置/破坏事件的公司 ID 一致。

## 基本面映射

- **IPO 定价**：`ipoPrice = max(1, NAV/share × (1 + 等级溢价))`，NAV 已包含产线。
- **EPS / ROE**：季度财报使用 NAV 与 recentProfit。
- **市净率/市值**：股票页使用 `nav` 与总股本。
- **信用/抵押**：贷款抵押仍由现有银行系统读取公司净资产。

## H2

`company` 表新增关系列：

```text
inventory_value, productive_asset_value, appraised_asset_value,
replacement_cost, asset_depreciation, asset_utilization,
asset_risk, asset_quality, machine_count, multiblock_count,
production_output_value, production_input_value, production_throughput,
production_utilization, inventory_turnover, operating_profit, energy_consumed
```

完整 `CompanyData` 仍由 `engine_kv.engine_main` blob 读档；关系列用于查询/分析。

## 验证

- `ProductionAssetProbe`：**13/13**
  - Mekanism 机器识别；
  - GT 多方块成本高于普通机器；
  - chest 不算产线；
  - 估值放大、利用率/风险/质量有界；
  - 机器/多方块计数正确；
  - 闲置机器估值低于运行机器 35%。
- `./gradlew compileJava`：**BUILD SUCCESSFUL**
- 最终 jar：`build/libs/financialmarket-1.0.0-full.jar`（22,807,230 bytes）。

## 边界

1. 产出/消耗来自绑定容器/产线库存的每日差分；玩家手动存料也会被近似当作“产出”。
2. 机器识别是名字启发式，不保证覆盖所有科技包 mod；未知机器可按需补关键词。
3. 多方块部件会按多个方块累计；没有“控制器去重/结构识别”。
4. 产线价值是重置成本法，不是 DCF/可比公司法。
5. 利用率是“产出/估算产能”与“能耗/估算能耗”的近似，不是逐机器 tick 采样。
6. 没有真实服务器“大型产线绑定 + IPO”smoke；目前为纯模型探针 + 编译验证。
