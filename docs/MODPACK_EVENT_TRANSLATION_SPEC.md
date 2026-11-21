# 大型整合包：世界行为 → 经济/金融事件 翻译层规格（v0.2）

> 目标：在 **几十个大型模组、上万物品、十万级配方** 的整合包里，把玩家行为与世界状态
> 稳定翻译成有经济学/金融学意义的事件，而不是为每个模组写硬编码。
>
> 一句话：**模组提供“资源—工艺”图的边和原始事件；经济层只认“规范资源 + 工艺 + 事件 + 流量”。**
>
> 关联：`WORLD_MACRO_SPEC.md`（分层架构/宏观传导）、`MACRO_MODEL.md`（政策层）、
> `MICRO_MACRO_COUPLING.md`（微观消费接口）。

---

## 1. 为什么不能按模组/按物品手工映射

| 做法 | 问题 |
|---|---|
| 每个模组写一个 Adapter，逐个物品映射 | 模组一更新/整合包一换就崩；10k 物品不可能手工维护 |
| 按物品 ID 写死价格/行业 | 同一物品有多个来源（矿石/锭/粉/块）、多个模组标签、替代品；无法规范化 |
| 每个动作直接改股价 | 刷石机/刷怪塔可无限印钱；没有预期差、没有净增加值、没有存量/流量区分 |
| 只支持已知科技/魔法模组 | 未知模组、冷门模组、整合包自制内容全部失效 |

**正确抽象**：把世界看成一张 **资源—工艺图（Resource–Process Graph）**：

```text
资源节点：物品 / 流体 / 能量 / 法力 / 服务 / 知识
工艺边：  配方 / 机器 / 仪式 / 法术 / 物流 / 事件
玩家动作：在图上增加/消耗流量、形成资本存量、推进技术、制造风险
```

经济层只处理规范化的节点/边/流量；模组差异被“画像 + 标签 + 配方图 + 适配器”吸收。

---

## 2. 四个核心抽象

### 2.1 规范资源 `ResourceKey`

```java
record ResourceKey(
    String canonicalId,     // 规范 ID：c:ingots/osmium, minecraft:iron_ore, energy:FE, mana:botania
    ResourceKind kind,      // ITEM / FLUID / ENERGY / MANA / SERVICE / KNOWLEDGE
    String sector,          // materials / energy / food / tools / arcane / logistics / finance
    String category,        // raw / intermediate / capital / consumer / luxury / reagent / knowledge
    int tier,               // 科技/魔法层级
    double unitValue,       // 缺价锚
    double elasticity,      // 供需价格弹性
    Set<String> aliases,    // 原版/模组 ID、标签、替代品
    Set<String> substitutes
) {}
```

- **规范 ID 优先用标签**：`c:ingots/osmium` 比 `mekanism:ingot_osmium` 更稳定；多个模组的锇锭归一到同一资源。
- 没有标签时用 **registry id + namespace 画像**；未知资源进 `OTHER`，`unitValue=1`、`impactWeight=0.1`，不静默丢弃。
- `aliases` 用于识别同一物品的不同形态（矿石/raw/锭/粉/块），但**不自动等值**：需要配方图/标签证明。

### 2.2 工艺 `ProcessProfile`

```java
record ProcessProfile(
    String processId,        // create:crushing / mekanism:enriching / botania:mana_pool / ars:ritual
    ProcessKind kind,        // CRAFT / SMELT / CRUSH / CHEM / ASSEMBLY / RITUAL / SPELL / LOGISTICS
    List<ResourceKey> inputs,
    List<ResourceKey> outputs,
    double energyPerRun,     // FE / mana / LP / J
    double timePerRun,       // tick
    int tier,                // 机器/法术 tier
    double byproductChance,
    double valueAddedRatio,  // 预计算：VA / output value
    String sector
) {}
```

- 从 `RecipeManager` 自动编译；模组自定义配方类型也走同一编译器。
- 多输出/副产物/概率产出按期望值计；循环配方标记为套利风险。
- `processId` 用于归因与审计：一条事件能追到“哪个配方/哪个机器/哪个模组”。

### 2.3 经济事件 `EconomicEvent`

```java
record EconomicEvent(
    long tick, long seq,
    EventType type,          // EXTRACTION / PRODUCTION / CONSUMPTION / INVESTMENT / RND /
                             // RISK / LOGISTICS / LABOR / DISCOVERY / SPELL / RITUAL / TRADE
    String actorId, String companyId,
    String sector, String region,
    List<ResourceFlow> inputs, List<ResourceFlow> outputs,
    double valueAdded,       // Σ output.value − Σ input.value
    double novelty,          // 0..1：首次/新配方/新法术高，重复低
    double persistence,      // 0=flow, 1=stock
    String sourceMod, String adapter, String provenance,
    double confidence        // 0..1：原生适配高，通用采样低
) {}

record ResourceFlow(ResourceKey key, double quantity, double unitValue) {}
```

- **一条原始动作最多 1~2 条事件**；不能把“左键方块”直接变成价格。
- `provenance` 记录：原始 registry id、recipe id、process id、adapter 版本；用于 `/fm macro explain`。
- `confidence` 低于阈值的事件只进账本/UI，不进价格。

### 2.4 世界状态 `WorldStateSnapshot`

```java
record WorldStateSnapshot(
    long tick, long day,
    double population, double villagePopulation,
    double employment, double laborSupply,
    double pollution, double weatherSeverity,
    double mobDensity, double infrastructure,
    double energySupply, double manaSupply,
    double resourceReserveIndex,
    double techTierMean, double riskLevel,
    Map<String, Double> sectorCapacity
) {}
```

- 世界状态是**慢变量**，按日/每 1000 tick 采样，不进每 tick 热路径。
- 来源：ServerLevel 统计、村庄/实体计数（有上限）、公司机器/能源/污染账本、维度访问、天气。
- 世界状态本身也可以生成事件：干旱→农业产出↓、污染→监管/健康成本↑、怪物密度→风险↑。

---

## 3. 三阶段翻译管线

```text
L0 CAPTURE
  原始动作 / 世界状态 / Capability / 容器差分 / 配方完成 / 模组事件
        ↓
L1 CANONICALIZE
  registry id / tag / alias → ResourceKey
  recipe / block entity / capability → ProcessProfile
        ↓
L2 TRANSLATE
  WorldAction → EconomicEvent（净 VA、stock/flow、novelty、confidence）
        ↓
L3 AGGREGATE
  company / sector / region / world；EWMA 预期；surprise
        ↓
L4 TRANSMIT
  CommodityMarket / CompanyData / SectorState / AssetFactor / MarketSentiment
```

### 3.1 捕获层（L0）

| 来源 | 捕获 | 备注 |
|---|---|---|
| NeoForge 事件 | `ItemCraftedEvent`、`BlockEvent.BreakEvent`、`EntityPlaceEvent`、`LivingDeathEvent`、`AdvancementEvent`、`PlayerInteractEvent` | 通用、零模组依赖 |
| 配方完成 | `RecipeManager` / 模组 recipe completion | 比容器扫描更准 |
| Capability | Item/Fluid/Energy handler 差分 | 机器/储罐/能量 |
| 容器差分 | 公司绑定容器每日/每秒差分 | 无事件模组的 fallback |
| 模组原生 | Create train/kinetic、Mekanism factory、Botania mana、Ars source、GT voltage | 可选适配器 |
| 世界状态 | 村庄/实体/天气/污染/维度/区块统计 | 低频采样，有上限 |

**禁止**：每 tick 遍历所有方块实体/所有区块；只在公司绑定区域/加载区块做低频采样。

### 3.2 规范化层（L1）

**物品/流体/能量**：

1. 查整合包 override（最高优先级）。
2. 查模组自带 `economy/items.json`。
3. 查标签：`c:ores` / `c:ingots` / `c:dusts` / `c:gems` / `c:raw_materials` / `c:foods` / `c:storage_blocks` / `c:energy`。
4. 查配方图角色：只有输入=raw；只有输出=source；中间=intermediate；被大量引用=bottleneck。
5. 查命名/namespace 画像（`*_ore`、`*_ingot`、`*_dust`、`*_seed`、`*_rune`……）。
6. 默认 `OTHER`（低权重）+ 未映射账本。

**配方/工艺**：

- 遍历 `RecipeManager`，把每个 recipe 编译成 `ProcessProfile`：
  - 输入/输出/副产物/概率；
  - 机器类型/tier/能耗/时间；
  - 所属 sector；
  - VA 比例、上游依赖深度、是否循环。
- **预计算**：`cost(i)`、`tier(i)`、`bottleneck(i)`、`(I−A)^{-1}`；运行时只查表。

### 3.3 翻译层（L2）

对每条 `WorldAction`：

1. 解析 inputs/outputs 为 `ResourceFlow`。
2. 若来自配方：使用 `ProcessProfile` 的 VA/tier/energy；否则按资源画像估算。
3. 分类：
   - 只有输出、无输入 → `EXTRACTION`；
   - 有输入/输出 → `PRODUCTION`，`VA = Σout − Σin`；
   - 只有输入、无输出（食物/燃料/试剂/法术）→ `CONSUMPTION`/`SPELL`；
   - 放置机器/祭坛/轨道 → `INVESTMENT`（stock，不是当期收入）；
   - 成就/研究/附魔 → `RND`（stock，提升 TFP）；
   - 爆炸/死亡/污染/魔法失控 → `RISK`/`LOSS`；
   - 火车/传送/仓库 → `LOGISTICS`；
   - 玩家交易 → `TRADE`（只影响流动性/成交量）。
4. `novelty`：首次该配方/该法术/该成就 = 1；重复按 EWMA 衰减。
5. `confidence`：原生事件 0.9；配方完成 0.8；容器差分 0.5；未知 fallback 0.2。

### 3.4 聚合层（L3）

- 维度：player → company → sector → region → world。
- 窗口：20 tick（微）、24000 tick（日）、季度（宏观）。
- 存量：机器、资本、库存、技术、风险；流量：产出、消耗、capex、R&D。
- 预期：
  ```text
  E_t = λ·E_{t-1} + (1−λ)·X_t
  surprise = clamp((X_t − E_t)/max(σ_t, floor), −4, 4)
  ```
- 只把 `surprise` 送入 L4；重复动作的 `novelty` 与 `surprise` 同时衰减。

---

## 4. 大型整合包的规范化与冲突处理

### 4.1 同一物品多来源

| 情况 | 处理 |
|---|---|
| 多模组锇锭 | 规范到 `c:ingots/osmium`；所有配方产出/消耗都记到同一 `ResourceKey` |
| 矿石/raw/锭/粉/块 | 不同形态保留为不同 `ResourceKey`，用配方图连边；不自动等值 |
| 同名不同物 | registry id 优先；namespace 画像区分 |
| 替代品 | `substitutes` + 交叉弹性；一种涨價，需求转向替代品 |
| 整合包自制物品 | 无画像时 `OTHER` 低权重；管理员 JSON override |

### 4.2 配方冲突/多路径

- 同一物品有多个配方：**所有路径都贡献供给**，但成本锚取**最低可行成本**（现有 `CommodityEntry` 的成本锚逻辑）。
- 同一配方有多个产出：按期望产量分配 VA；副产物也计入供给。
- 循环配方（A→B→A）：检测强连通分量，标记套利；加交易成本/上限，不进入宏观 VA。
- 机器 tier 冲突：取**中位数/最大值**（可配），并记录来源；整合包可 override。

### 4.3 能量/法力/LP 的统一

- 规范为 `energy` 资源，但保留载体：
  - `energy:fe`、`energy:mana`、`energy:lp`、`energy:source`；
  - 整合包定义 `conversion`（例如 1 mana = X FE）时，才允许跨载体替代；否则分开计价。
- 能源价格进入 `energy` 板块成本；法力作为 `arcane` 板块的能源投入。

### 4.4 整合包画像包

```text
data/<packid>/financialmarket/economy/
  items.json          // ResourceProfile override
  processes.json      // ProcessProfile override（tier/VA/sector）
  sectors.json        // 板块定义/权重
  conversions.json    // 能量/法力/LP 换算
  world_rules.json    // 天气/污染/村庄→事件规则
```

- 加载顺序：pack override → mod profile → tags/recipe auto → OTHER。
- 版本/哈希：`packHash`；数据包重载时重建画像图，不重启服务器。
- 覆盖日志：哪些资源被 override、哪些未映射。

---

## 5. 世界状态 → 经济事件

| 世界状态 | 事件 | 经济含义 |
|---|---|---|
| 天气（干旱/暴雨/雷暴） | `EXTRACTION`/`PRODUCTION` 修正 | 农业产出、能源需求、物流延迟 |
| 村庄人口/就业 | `LABOR`/`CONSUMPTION` | 劳动供给、消费需求、工资 |
| 怪物密度/袭击 | `RISK`/`LOSS` | 保险、安防支出、生产中断 |
| 污染/魔法不稳定 | `RISK` | 监管、健康成本、风险溢价 |
| 新维度/新矿脉 | `DISCOVERY` | 资源储量、未来供给、物流成本 |
| 基础设施（铁路/传送/仓库） | `LOGISTICS` | 区域价差、交付能力、库存周转 |
| 能源/法力网络 | `ENERGY` 供给/需求 | 电价、算力/法术成本 |
| 科技 tier 分布 | `RND`/TFP | 长期生产率 |
| 玩家/公司数量 | 市场深度/流动性 | 成交量、冲击成本 |

- 世界状态事件也走 **EWMA + surprise**：天气每年都下雨，不会每天涨价；只有异常干旱/污染飙升才有影响。
- 采样频率：日/1000 tick；不每 tick 扫世界。

---

## 6. 大型模组的具体翻译示例

### 6.1 科技链（Create + Mekanism + AE2 + 铁路）

```text
Create 粉碎：cobblestone → gravel（PRODUCTION，VA 低）
Mekanism 5x：ore → 5 ingot（PRODUCTION，VA 高，energy 需求）
AE2 存储/自动合成：库存周转↑、LOGISTICS/RND（资本存量）
Create 火车：跨区域运输（LOGISTICS，区域价差↓）
```

- 矿石供给↑ → 锭价↓；5x 产线 capex → 未来供给↑；能源需求↑ → 能源板块收入↑；
- 自动合成/存储 → 库存天数↓、流动性↑（不直接改价格，改公司周转/风险）。

### 6.2 魔法链（Botania + Ars Nouveau + Blood Magic）

```text
Botania mana：endoflame 消耗煤 → mana（ENERGY；煤需求↑，mana 供给↑）
Ars 法术：Grow → 作物产量↑（SPELL/农业 TFP；mana/reagent 消耗）
Ars 仪式：大型 capex（INVESTMENT；材料需求↑）
Blood Magic：LP 仪式（LABOR/ENERGY；高风险 → RISK/监管）
```

- 魔法能源替代煤炭 → 传统能源需求↓、能源价格↓；
- 法术服务 → `arcane` 板块收入；试剂 → 商品需求；
- 失控/污染 → 风险溢价、保险需求、监管成本。

### 6.3 跨模组耦合

- 魔法工具 → 采矿效率 → 材料供给（TFP 外溢）；
- 科技污染 → 魔法/农业减产（RISK/负外部性）；
- 魔法传送 → 物流成本↓ → 区域价差收敛；
- 科技/魔法 tier 提升 → TFP，但 capex/维护成本↑。

---

## 7. 防刷与守恒（整合包尤其重要）

| 风险 | 机制 |
|---|---|
| 刷石机/刷铁机 | 净 VA；供给饱和；单玩家/公司/板块日 cap；`tanh` 饱和 |
| 合成-出售套利 | 配方图环检测；交易成本；售价<成本不产生正 VA |
| 多模组同一物品重复计数 | 规范 `ResourceKey` + 净额；同一资源只记一次 |
| 建机器当收入 | 放置=stock/capex；运行=flow；折旧 |
| 反复施法/仪式 | novelty 衰减；试剂/法力成本；服务需求饱和 |
| 单玩家操纵板块 | 单玩家/公司/板块日影响 cap；大额事件拆多日；市场深度 |
| 未知模组误分类 | `OTHER` 低权重；未映射账本；管理员 override |
| 读档/重放重复计数 | 事件按 `(tick,seq)` 幂等；聚合快照去重 |

**守恒检查**：

```text
Σ company VA ≈ Σ commodity value added ≈ Σ sector output − input cost
商品库存/供需账本可对账；无负库存/负价格
```

---

## 8. 性能与规模

| 项 | 目标 |
|---|---|
| 资源画像编译 | 10k 物品/100k 配方，启动/数据包重载 1–3 s，缓存到磁盘 |
| 运行时事件 | 每 20 tick 聚合一次；队列上限（如 20k/日）；溢出按公司/类型聚合 |
| 每 tick 成本 | 平均 <0.05 ms，峰值 <0.5 ms（不含 Kronos/TABL 推理） |
| 内存 | 画像/配方图 50–150 MB（可配）；事件账本滚动 |
| 线程 | ServerLevel 主线程；Kronos/TABL 异步/离线 |
| 确定性 | 事件按 `(tick,seq)` 排序；同种子同动作逐位一致；无 `Math.random()` |

**关键**：配方图/画像在启动时编译，运行时只做 O(事件) 的查表和聚合；绝不在 tick 里遍历 100k 配方。

---

## 9. 代码落点（建议）

```text
engine/macro/world/
  WorldActionCollector        // NeoForge 事件 + Capability/容器采样
  ResourceKey / ResourceKind
  ItemEconomicProfile
  ProcessProfile
  ProfileCompiler             // 标签/配方/namespace/override → 画像
  RecipeGraph / InputOutputMatrix
  EconomicEvent / EventType / ResourceFlow
  EconomicTranslator          // WorldAction → EconomicEvent
  CompanyEconomy              // K: 机器/祭坛/轨道；L/E/M；tech/risk
  SectorEconomy / WorldMacroState
  MacroFlowAggregator         // EWMA/surprise/净额/上限
  WorldStateCollector         // 天气/村庄/污染/维度/基础设施
  MacroTransmission           // CommodityMarket/CompanyData/AssetFactor/Sentiment
  EconomicLedger              // 事件账本/审计/explain
```

接入现有：

| 现有 | 接入 |
|---|---|
| `FinancialMarket.Events` | 订阅 `ItemCraftedEvent`/`BlockEvent`/`LivingDeathEvent`/`AdvancementEvent` → `WorldActionCollector` |
| `MarketEngine.tick` | 每 20 tick `MacroFlowAggregator.step`；`buildMacroSnapshot` 合并 `WorldMacroState` |
| `CommodityMarket`/`CommodityEntry` | `applyProduction/Consumption`；`adjustSupply/Demand` |
| `CompanyData`/`CompanyManager` | `sector/capital/production/capex/rnd/techTier/risk`；财报记录 VA |
| `AssetScanner` | 日度库存/资产扫描；容器差分做生产/消费低频采样，避免双计 |
| `AssetSnapshot`/`PriceDiscoveryEngine` | `sectorExposure`/`eventFairValue`/`eventRisk` |
| `MultiAgentManager` | agent 读 `WorldMacroSnapshot`/`SectorSignal`，不写回 |
| `MarketSentiment` | `applyEconomicEvent`：叙事/情绪，只影响微观 |
| `MacroStateEngine`/`MacroEnvironment` | 保持政策层；只读聚合产出缺口/通胀，不读原始动作 |

---

## 10. 分阶段 MVP（整合包）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M1 通用** | 事件模型/总线；原版 + 标签/配方；3 板块（材料/能源/消费）；商品/公司聚合；审计 | 覆盖 ≥80% 原版物品；供给↑→价格↓；无 NaN；tick 预算 |
| **M2 配方图** | ProfileCompiler/RecipeGraph/IO 矩阵；Create/Mekanism/Thermal 通用适配；capex/tier/TFP | 5x 矿石链正确传导到锭价/公司毛利 |
| **M3 魔法** | Botania/Ars/Blood 的 mana/source/LP；SPELL/RITUAL/RISK；魔法→科技外溢 | 施法→试剂需求/服务收入；仪式→capex；失控→风险溢价 |
| **M4 整合包** | 数据包 override、冲突处理、未映射账本、UI/explain、参数校准 | 换整合包不崩；未映射可补；单玩家不能操纵板块 |

### 硬验收

1. **经济符号**：production↑→price↓；demand↑→price↑；capex↑→未来产出↑；R&D↑→TFP↑；risk↑→discount↑。
2. **守恒**：公司 VA ≈ 收入−投入−成本；商品供需可对账；无负库存/负价格。
3. **防刷**：单玩家/公司日影响 ≤ cap；循环配方被检测；重复动作边际递减。
4. **性能**：画像编译 ≤3s；平均 <0.05ms/tick；事件队列有界。
5. **可复现**：同种子/同动作逐位一致。
6. **可解释**：`/fm macro explain <asset>` 输出事件→板块→因子的完整链。

---

## 11. 需要拍板的问题

1. **整合包/模组清单**：优先支持哪些大型模组？（Create / Mekanism / GregTech / AE2 / Thermal / Botania / Ars / Blood / Thaumcraft / Astral / Occultism / 其他）
2. **主体粒度**：公司为主，个体户自动建个人公司？还是玩家个体也独立记账？
3. **服务器规模**：玩家/公司/机器数量，决定采样与事件上限。
4. **数据包作者**：是否允许整合包作者写 `economy/*.json` 覆盖？建议允许。
5. **政策耦合**：玩家经济是否通过产出缺口/通胀**间接**影响央行（Taylor 规则），还是完全隔离？建议间接、平滑、有上限。
6. **UI**：是否需要宏观仪表盘、事件账本、“为什么涨跌”面板？

---

## 12. 总结

**大型整合包的可扩展翻译 = 规范资源图 + 工艺图 + 事件代数 + 预期差 + 单向传导。**

- 资源：用标签/别名/配方图归一，不按模组枚举；
- 工艺：从 `RecipeManager` 编译输入/输出/能耗/tier/VA，运行时查表；
- 事件：只保留六类经济流量 + 四个修饰类，净 VA、stock/flow、novelty、confidence；
- 世界：天气/村庄/污染/维度/基础设施作为慢变量，按日采样；
- 传导：商品供需、公司盈利、板块因子、agent 估值；不直接改盘口；
- 防刷：净额、上限、循环检测、守恒、审计；
- 未知模组：`OTHER` 低权重 + 未映射账本 + 数据包 override，保证“能跑、可补、不崩”。
