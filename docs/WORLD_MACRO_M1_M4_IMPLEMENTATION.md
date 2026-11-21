# 世界宏观层 M1–M4 实现进度（第一版）

> 目标：玩家/世界行为 → 经济事件 → 公司/板块/商品/情绪 → 股市。
> 状态：**M1–M4 主链路已打通并通过编译/离线校验**；原生模组适配、UI、压力校准仍是后续增量。

## 1. 已完成

| 阶段 | 内容 | 状态 |
|---|---|---|
| **M1 通用** | 资源画像、事件模型/总线、翻译器、EWMA 预期差、公司/板块聚合、商品供需传导、情绪 nudge、审计账本、`/fm macro` | ✅ 核心完成 |
| **M2 配方图** | `ProfileCompiler` 编译物品/配方；`RecipeGraph` 成本/tier/瓶颈/循环/IO 矩阵；公司容器差分的通用产线采样 | ✅ 第一版 |
| **M3 魔法** | 软识别 Botania/Ars/Blood 等命名空间；法术/仪式事件、法力/试剂消耗、风险、魔法→R&D 外溢 | ✅ 第一版（软适配） |
| **M4 整合包** | `config/financialmarket/economy/items.json` / `processes.json` 覆盖；标签别名；未映射账本；公司宏观字段持久化；`/fm macro status/explain/unmapped/profiles` | ✅ 第一版 |

## 2. 代码落点

`src/main/java/com/example/financialmarket/engine/macro/world/`

| 类 | 职责 |
|---|---|
| `WorldMacroModel` | `ResourceKey/Profile`、`ProcessProfile`、`WorldAction`、`EconomicEvent`、`WorldStateSnapshot` |
| `ProfileRegistry` | 规范资源/标签别名/未映射/OTHER fallback |
| `EconomicEventBus` | 有界事件队列（默认 20k） |
| `EconomicTranslator` | 动作→事件：VA、stock/flow、novelty、confidence、法术 R&D/仪式风险 |
| `MacroFlowAggregator` | 公司/板块/世界聚合、EWMA surprise、日影响 cap、资源供需 |
| `WorldMacroEngine` | 门面：submit/step/snapshot/ledger/recipeGraph |
| `WorldMacroSnapshot` | 只读快照（生产/PPI/CPI/风险/板块/公司） |
| `EconomicLedger` | 有界审计账本，支持 `explain` |
| `RecipeGraph` | 配方边、成本、tier、循环、IO 矩阵、成本冲击 |
| `ProfileCompiler` | 注册表/配方→画像/图；全部物品 fallback；标签 |
| `WorldActionCollector` | 原版事件→WorldAction；tech/magic 软适配入口 |
| `TechStorageSampler` | 公司绑定容器日差分→MACHINE_RUN/生产/消费（通用科技/魔法） |
| `EconomyProfileLoader` | 整合包 JSON 覆盖 items/processes |
| `WorldStateCollector` | 玩家/天气/公司/技术均值等慢变量 |
| `MacroTransmission` | 供需→`CommodityMarket`；风险→`MarketSentiment`；公司宏观字段 |
| `WorldMacroCommand` | `/fm macro ...` |

接线：
- `MarketEngine`：每 20 tick `worldMacro.step`；交易日开盘 `techStorageSampler` + `worldStateCollector` + `MacroTransmission`；首次配方图就绪时 `ProfileCompiler`/`EconomyProfileLoader`。
- `FinancialMarket.Events`：`ItemCraftedEvent`、`BlockEvent.BreakEvent`、`EntityPlaceEvent`、`RightClickItem/Block`、`LivingDeathEvent`、`AdvancementEarnEvent`。
- `CompanyData`：新增 `macroProduction/macroCapex/macroRnd/macroRisk/macroTechTier`，并 NBT 持久化。

## 3. 经济语义

- `PRODUCTION/EXTRACTION/SPELL/RITUAL`：净增加值 `Σout − Σin`，进入生产指数/公司现金流。
- `INVESTMENT`：capex 存量，当期不重复计收入。
- `RND`：知识/技术存量，提升 `techTier`；法术首次释放产生小 R&D 外溢。
- `RISK`：风险溢价/情绪 nudge，不进 GDP。
- `CONSUMPTION`：需求/成本，不重复计 GDP。
- `TRADE/LOGISTICS`：只影响流动性/交付，暂不进生产账户。
- 预期差：`E_t = 0.9E_{t-1} + 0.1X_t`，`surprise=(X−E)/scale`，重复动作 novelty/impact 衰减。
- 防刷：日 cap 5.0、`tanh` 饱和、净额、循环图标记、未映射低权重。

## 4. 验证

- `javac` 核心 + 离线探针：**25/25 checks passed**（别名、VA、stock/flow、novelty、surprise 衰减、risk、未映射、explain）。
- `./gradlew compileJava`：**BUILD SUCCESSFUL**（仅 unchecked/deprecation 警告）。
- `/fm macro status/explain/unmapped/profiles` 已注册。

## 5. 已知边界（下一轮）

1. **原生模组适配**：当前 Botania/Ars/Blood 是命名空间/物品软识别；没有调用各自 mana/source API。Mekanism/Create/GT 的机器吞吐用容器差分近似，不是 recipe-completion/energy 精确采样。
2. **宏观→资产因子**：目前传导到商品供需、情绪和公司宏观字段；尚未把 `WorldMacroSnapshot` 作为 `MultiAgentManager`/`PriceDiscoveryEngine` 的显式因子（避免破坏现有逐位基线）。
3. **UI**：只有命令/审计；没有仪表盘。
4. **持久化**：公司宏观字段已入 NBT；事件账本/板块聚合未持久化（读档重建）。
5. **压力测试**：未在 100k 配方/大量公司场景实测编译耗时与 tick 预算。
6. **源码恢复说明**：`MarketEngine.java`/`FinancialMarket.java` 曾被 PowerShell 整文件替换破坏 UTF-8；已用编译产物反编译恢复逻辑并重放 M1 接线。**注释丢失、行为保留**；后续新增修改请用 `edit`/`write`，不要用 PowerShell `Set-Content` 处理中文源码。

## 6. 运行方式

```text
/fm macro status      # 世界宏观状态
/fm macro explain materials
/fm macro unmapped
/fm macro profiles
```

整合包覆盖：

```text
config/financialmarket/economy/items.json
config/financialmarket/economy/processes.json
```

```json
[
  {"id":"mekanism:ingot_osmium","sector":"materials","category":"intermediate",
   "tier":3,"unit_value":25.0,"elasticity":0.8,"tags":["c:ingots"]}
]
```

---

# Round 2 增量（2026-11-08）

## 已补

1. **宏观 → agent 板块因子**
   - `MarketEngine` 用 `assetSector` 把股票行业归一到 `materials/energy/food/tools/arcane/services/finance/other`。
   - 通过 `MultiAgentManager.setSectorFlowProvider(...)` 把板块产出/毛利/风险变成 chartist 的 `sectorFlow`；只影响 agent 信号，不直接写价格。
   - `WorldMacroEngine.snapshot()` 增加缓存（dirty flag），避免每个 agent/每 tick 重建快照。
2. **原生能力采样**
   - `TechStorageSampler` 除容器差分外，读取 NeoForge `Capabilities.EnergyStorage.BLOCK`，按日差分 FE 消耗 → `CONSUMPTION` 事件；适用于 Mekanism/Create/GT 等 FE 机器。
3. **HTTP 世界宏观端点**
   - `GET /api/worldmacro` 返回 `productionIndex/ppi/cpi/capacityUtil/eventRisk/novelty/sectors/companies`，供仪表盘/外部工具读取。
   - 保留既有 `/api/macro`（货币政策）不变，避免同名冲突。
4. **配方图规模与环检测**
   - `RecipeGraph.cyclicResources()` 从递归 DFS 改为 Kahn 拓扑排序，修复 50k 长链 StackOverflow。
   - 50k 工艺链压力结果：`add=77ms costs=66ms tiers=24ms matrix=13ms cycles=27ms`，无环。
5. **公司宏观字段**
   - `CompanyData` 新增并持久化 `macroProduction/macroCapex/macroRnd/macroRisk/macroTechTier`；`MacroTransmission` 每日回写。

## 验证

- 核心探针：**25/25**。
- 50k 配方图压力：**通过**（见上）。
- `./gradlew compileJava`：**BUILD SUCCESSFUL**。

## 仍未完成（下一轮）

1. **模组原生 API**：Botania/Ars 的 mana/source、Mekanism factory recipe completion、Create kinetic/train 仍是软适配/能力近似。
2. **事件账本持久化**：公司字段已 NBT；`EconomicLedger`/板块聚合读档重建，不落盘。
3. **UI 页面**：有 `/api/worldmacro` 和 `/fm macro`，但没有前端图表页。
4. **校准**：`sectorFlow` 的缩放、单玩家 cap、事件影响占比尚未用真实整合包数据回归。

---

# Round 3 增量（2026-11-08）

## 已补

1. **世界宏观落盘**
   - `WorldMacroPersistence` 把 tick/day、公司经济、板块经济、EWMA 预期、审计账本写入 `MarketEngine.save()` 的 `worldMacro` 标签；`load()` 恢复。
   - 旧存档缺 `worldMacro` 时按 `contains` 跳过，向后兼容。
   - 8 项离线 round-trip 探针全过（tick/day/production/events/sector/company/ledger）。
2. **魔法资源原生采样**
   - `TechStorageSampler` 读取容器物品的 `DataComponents.CUSTOM_DATA`，采样 `mana/source/stored_mana/lp`，按日差分 → `CONSUMPTION` 的 `mana:generic` 事件。
   - 覆盖 Botania mana 物品、Ars source、Blood LP 等“物品内储能”实现；无需硬依赖。
3. **宏观前端页**
   - 新增 `assets/financialmarket/html/worldmacro.html`，访问 `/worldmacro.html`，5 秒轮询 `/api/worldmacro`，展示顶部指标/板块/公司。
   - 纯静态、无前端构建；保留原 `/api/macro` 货币政策端点。
4. **验证**
   - 核心探针 25/25；持久化探针 8/8；50k 配方图压力通过。
   - `./gradlew compileJava`：**BUILD SUCCESSFUL**。

## 仍未完成

- Create kinetic/train、Mekanism factory、GregTech multiblock 的 recipe-completion 级适配（目前用容器/FE/mana 差分）。
- `sectorFlow`/事件影响占比尚未用真实整合包回归校准。
- 前端页只是只读仪表盘，未接入交易页导航/权限。

---

# Round 4 增量（2026-11-08）

## 已补

1. **原生模组配方画像**
   - 新增 `NativeModProfiles`：按 recipe type 识别 Mekanism、Create、GregTech、Thermal、AE2、IE、Botania、Ars、Blood Magic、Thaumcraft 的工序语义/tier/sector。
   - `ProfileCompiler` 在通用 heuristic 之前先用该画像；无模组时自动回退，不硬依赖。
   - 探针 **10/10**：Mekanism enrich→CRUSH/tier3、Create pressing→ASSEMBLY、GT centrifuge→CHEM/tier4、Botania runic→RITUAL、Ars apparatus→SPELL、Blood altar→tier4。

2. **可校准参数**
   - 新增 `config/financialmarket/economy/calibration.json` 支持：
     `sectorFlowOutput / sectorFlowMargin / sectorFlowRisk / supplyScale / demandScale / maxSentimentNudge / dayImpactCap / impactCap`。
   - `MacroCalibration` 在 `EconomyProfileLoader` 中加载；`MarketEngine.sectorFlow`、`MacroTransmission`、`MacroFlowAggregator` 使用这些参数。
   - 默认值保持保守，旧整合包不配置时行为不变。

3. **验证**
   - 核心 25/25、原生画像 10/10、持久化 8/8。
   - 50k 配方图压力通过。
   - `./gradlew compileJava`：**BUILD SUCCESSFUL**。

## 仍未完成（可选深化）

- 真正的模组原生回调/recipe-completion（目前 NativeModProfiles 是配方类型级语义，机器吞吐仍由容器/FE/mana 差分）。
- 用真实整合包跑一轮事件影响占比回归，再调 `calibration.json`。



