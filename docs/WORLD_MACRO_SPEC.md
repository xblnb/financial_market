# 世界行动 → 经济事件 → 宏观层 规格（v0.1）

> 目标：把玩家在世界内的**所有可观测行动与进度**（挖矿、合成、建机器、种田、战斗、探索、法术、仪式、交易、成就……）
> 翻译成**具有经济学意义的事件**，再经公司/板块/宏观层影响股市。
>
> 约束：NeoForge 1.21.1、ServerLevel 主线程 tick、离线、确定性可复现、大量模组（科技/魔法/未知）。
>
> 结论先行：**不要为每个动作写一条“股价影响”**。正确做法是
> **动作 → 经济流量（生产/消费/投资/R&D/风险/物流）→ 公司/板块/宏观状态 → 资产因子 → 盘口**，
> 并且只让**预期差（surprise）**而不是“动作本身”进入价格。

---

## 1. 五条核心原则

1. **动作不是事件**：挖一块石头、放一个方块本身没有宏观意义；有意义的是它带来的**净产出、净投入、资本形成、技术/风险变化**。
2. **流量与存量分开**：机器/祭坛/法术书是**资本存量（capex）**，运行时的产出是**流量（production）**；一次性建 1000 台机器不能当成 1000 次当期收入。
3. **只交易预期差**：每个流量都有一个 EWMA 预期 `E_t`；只有 `surprise = (X_t − E_t)/σ` 才影响价格，重复刷同一动作的边际影响递减。
4. **加总到公司/板块/世界，而不是逐玩家定价**：玩家是“微观主体”，公司是“资产负债表”，板块是“因子”，宏观是“状态”。
5. **可解释、可封顶、可复现**：每条事件保留“原始动作 → 经济映射 → 影响”账本；单玩家/单公司/单板块有日影响上限；同种子同动作逐位可复现。

---

## 2. 分层架构

```text
┌────────────────────────────────────────────────────────────────────┐
│ L0 CAPTURE   NeoForge 事件 / Capability 采样 / 配方完成 / 容器差分    │
│              （科技机器、魔法祭坛、法术释放、成就、击杀、交易……）      │
└───────────────────────────────┬────────────────────────────────────┘
                                ▼
┌────────────────────────────────────────────────────────────────────┐
│ L1 NORMALIZE  WorldAction → EconomicEvent                           │
│   {actor, company, type, inputs[], outputs[], tick, mod, confidence}│
└───────────────────────────────┬────────────────────────────────────┘
                                ▼
┌────────────────────────────────────────────────────────────────────┐
│ L2 VALUE      ItemEconomicProfile + RecipeGraph + InputOutputMatrix │
│   净增加值 VA、上游成本、技术 tier、瓶颈、替代弹性、未知模组 fallback │
└───────────────────────────────┬────────────────────────────────────┘
                                ▼
┌────────────────────────────────────────────────────────────────────┐
│ L3 AGGREGATE  CompanyState / SectorState / WorldMacroState          │
│   生产指数、产能利用率、库存、capex、R&D、能源、风险、预期 EWMA       │
└───────────────────────────────┬────────────────────────────────────┘
                                ▼
┌────────────────────────────────────────────────────────────────────┐
│ L4 TRANSMIT   CommodityMarket / CompanyData / AssetFactor           │
│   商品供需、成本传导、公司盈利、板块因子、折现率/风险溢价、情绪       │
└───────────────────────────────┬────────────────────────────────────┘
                                ▼
┌────────────────────────────────────────────────────────────────────┐
│ L5 MARKET     PriceDiscoveryEngine / MultiAgentManager / 盘口        │
│   agent 只读 WorldMacroSnapshot + SectorSignal，不直接改宏观          │
└────────────────────────────────────────────────────────────────────┘
```

**单向数据流**：世界动作 → 世界宏观（L3）→ 资产因子/商品/公司（L4）→ 盘口（L5）。
不要让盘口成交价反向写回 L3 的总量；最多通过 `MarketSentiment` 做**情绪正反馈**（只影响微观）。

---

## 3. 事件模型

### 3.1 原始动作 `WorldAction`

```java
public record WorldAction(
        long tick,
        long seq,
        UUID actor,
        String companyId,        // 可为 null（个体户/散户）
        ActionType action,       // BREAK, PLACE, CRAFT, SMELT, CAST, RITUAL, KILL, HARVEST, TRADE...
        String sourceMod,        // "minecraft", "create", "mekanism", "botania", "ars_nouveau"
        String sourceId,         // registry id / recipe id / spell id / advancement id
        List<ResourceStack> inputs,
        List<ResourceStack> outputs,
        BlockPos pos,
        ResourceLocation dimension,
        double confidence        // 0..1，通用适配器给低置信，原生适配器给高
) {}
```

`ResourceStack` 只放 **registry id + 数量 + 类别**，不在这里做价格；价格在 L2 由 `ItemEconomicProfile` 查。

### 3.2 经济事件 `EconomicEvent`

```java
public record EconomicEvent(
        long tick,
        long seq,
        EconomicType type,       // EXTRACTION / PRODUCTION / CONSUMPTION / INVESTMENT / RND /
                                 // DISCOVERY / LABOR / RISK / LOSS / LOGISTICS / SPELL / RITUAL
        UUID actor,
        String companyId,
        String sector,           // materials / energy / food / tools / arcane / services ...
        List<ResourceFlow> inputs,
        List<ResourceFlow> outputs,
        double valueAdded,       // Σ output.value − Σ input.value
        double novelty,          // 0..1：首次/新配方/新法术高，重复低
        double persistence,      // 0=flow, 1=stock
        String sourceMod,
        String sourceId,
        double confidence
) {}
```

`ResourceFlow` 在 L2 填充：`id, quantity, unitValue, category, sector, tags`。

### 3.3 经济类型（只保留六类主流量 + 四个修饰类）

| 类型 | 含义 | 典型动作 | 进入哪里 |
|---|---|---|---|
| `EXTRACTION` | 从世界抽取原始资源 | 挖矿、伐木、采集、抽油 | 商品供给↑、资源折耗、环境外部性 |
| `PRODUCTION` | 投入→产出的净增加值 | 合成、熔炼、粉碎、装配、产线 | 商品供给、公司毛利、GPP |
| `CONSUMPTION` | 最终/中间消耗 | 吃食物、烧燃料、法术试剂、弹药 | 商品需求、CPI、公司收入 |
| `INVESTMENT` | 形成未来产能的资本支出 | 建机器、建祭坛、铺轨道、开矿 | 资本存量 K↑、当期需求、未来供给 |
| `RND` | 技术进步/知识存量 | 研究、附魔、新法术、成就、Boss 首杀 | TFP↑、未来成本↓、风险↓/↑ |
| `RISK` | 风险/不确定性/外部性 | 死亡、爆炸、魔法失控、污染、战争 | 风险溢价、保险需求、监管 |
| `LOGISTICS` | 运输/仓储/流动性 | 火车、传送、仓库、商队 | 区域价差、交付延迟、库存 |
| `LABOR` | 劳动投入/损失 | 玩家在线劳动、村民工作、死亡 | 工资、产出、失业 |
| `DISCOVERY` | 新资源/新维度/新配方 | 探索、遗迹、新矿脉 | 储量/ frontiers、未来供给 |
| `SPELL` / `RITUAL` | 魔法服务/大额仪式 | 施法、仪式、召唤 | 服务产出、试剂需求、风险 |
| `TRADE` | 玩家间/村民交易 | 买卖、拍卖 | 流动性、成交量，不改基本面 |

> 规则：**一个原始动作最多生成 1~2 条经济事件**；不能把“左键方块”直接映射成“股价下跌”。

### 3.4 预期差

对每个 `(company, sector, type, resource)`：

```text
E_t = λ·E_{t-1} + (1−λ)·X_t              // EWMA，λ≈0.9（日）
σ_t = max(σ_floor, EWMA(|X−E|))          // 波动尺度
surprise = clamp((X_t − E_t)/σ_t, −4, +4)
impact = cap · tanh(surprise) · novelty · horizon(τ)
```

- 重复刷同一动作：`novelty` 指数衰减，`surprise` 趋近 0。
- 首次建产线/首次法术：`novelty` 高，但只给一次性的**资本/技术**冲击，不是永久流量。

---

## 4. 模组无关的分类与估值

### 4.1 三层适配器（覆盖大量模组的关键）

| 层 | 做法 | 覆盖率 | 代码量 | 适用 |
|---|---|---|---|---|
| **A. 通用适配器** | `ItemCraftedEvent` / `BlockEvent` / `EntityPlaceEvent` / 容器差分 / Capability（Item/Fluid/Energy）采样 | 60–80% | 低 | 所有模组，包括未知模组 |
| **B. 数据画像** | 数据包 JSON：物品/配方/法术的 `ItemEconomicProfile`、`sector`、`tier`、`elasticity` | 补到 90%+ | 零代码 | 模组作者/整合包作者 |
| **C. 原生适配器** | 可选：Create 的 kinetic/train、Mekanism 的 factory、Botania 的 mana、Ars 的 source、GT 的 voltage | 精确 | 每个模组 1 个类 | 头部科技/魔法模组 |

**原则**：A 保证“能跑”，B 保证“可调”，C 只在 A/B 明显不够时写；不要为每个模组硬编码。

### 4.2 物品经济画像 `ItemEconomicProfile`

```json
{
  "item": "mekanism:ingot_osmium",
  "category": "metal",
  "sector": "materials",
  "unit_value": 12.0,
  "elasticity": 0.8,
  "tier": 2,
  "substitutes": ["mekanism:ingot_tin"],
  "tags": ["c:ingots", "c:osmium"],
  "notes": "ore-processing output"
}
```

字段：

| 字段 | 作用 |
|---|---|
| `category` | raw / intermediate / capital / consumer / luxury / energy / reagent / spell / research |
| `sector` | materials / energy / food / tools / arcane / services / finance |
| `unit_value` | 缺价时的估值锚（可由配方成本覆盖） |
| `elasticity` | 供需价格弹性（同一供需冲击对不同物品影响不同） |
| `tier` | 科技/魔法层级（用于 TFP、capex、折旧） |
| `substitutes` | 替代品（一种物品涨價，需求转向替代品） |
| `tags` | 自动分类与 fallback |

存放：`data/<modid>/financialmarket/economy/items.json`；整合包可覆盖。
扫描顺序：**整合包 override → 模组自带 → 自动生成（tags/recipe graph）→ `OTHER` 低权重**。

### 4.3 配方图与投入产出矩阵

从 `RecipeManager` 构建：

```text
节点 = 物品/流体/能量/法术
边   = 配方：inputs → outputs（带产出率、机器类型、tier、时间）
```

计算：

- **直接成本**：`cost(i) = Σ_j a_ij · price(j) + 机器/能源成本`；
- **增加值**：`VA(r) = Σ p_out·q_out − Σ p_in·q_in`；
- **技术 tier**：`tier(out) = max(tier(in)) + processTier`；
- **瓶颈分数**：上游节点少、替代少、tier 高的物品 → 瓶颈；
- **Leontief 逆**：`(I − A)^{-1}` 用于成本冲击传导（上游涨价 → 下游成本）；
- **循环检测**：配方图有环 → 套利/刷钱风险，标记并加交易成本/上限。

运行时只查**预计算矩阵**，不在 tick 里遍历配方图。

### 4.4 未知模组 fallback

1. **标签优先**：`c:ores` / `c:ingots` / `c:foods` / `c:gems` / `c:dusts` / `c:raw_materials` / `c:storage_blocks`。
2. **命名空间画像**：`<modid>.json` 给默认 sector/tier/elasticity。
3. **配方图角色**：只有输入没有输出 = raw；只有输出没有输入 = source；中间 = intermediate；被大量配方引用 = 瓶颈。
4. **默认 `OTHER`**：`unit_value=1`、`sector=other`、`impact_weight=0.1`；不静默丢弃，进“未映射账本”供管理员补画像。
5. **离线分类器（可选）**：构建期用配方图/标签/名称给建议 JSON；运行时只读确定性结果，不跑 LLM。

---

## 5. 科技模组：产线怎么翻译

### 5.1 通用采集

| 动作 | 采集方式 | 经济事件 |
|---|---|---|
| 放置机器/多方块 | `BlockEvent.EntityPlaceEvent` + 方块实体类型 | `INVESTMENT`：capex 存量，按 tier 折旧 |
| 机器产出 | `ItemCraftedEvent` 或输出容器差分（低频采样） | `PRODUCTION`：outputs − inputs = VA |
| 机器耗能 | `Capabilities.EnergyStorage` 采样/差分 | `CONSUMPTION`：energy 需求 |
| 输入被消耗 | 输入容器差分 | `CONSUMPTION`：原料需求 |
| 矿车/火车/传送 | 实体/方块事件 + 低频位置采样 | `LOGISTICS`：运力、区域价差 |
| 污染/爆炸 | 爆炸/伤害/方块事件 | `RISK`：外部性、监管、保险 |

**不要每 tick 扫描所有机器**：按公司/区块做 1 秒~1 天的采样，或用模组自己的 recipe completion 事件；采样成本 O(公司×机器)。

### 5.2 模组示例（不硬依赖，走 A/B/C）

| 模组 | 产线语义 | 映射 |
|---|---|---|
| **Create** | 粉碎/洗涤/混合、kinetic stress、火车物流 | 配方产出→PRODUCTION；stress→ENERGY 消耗；列车→LOGISTICS；大型 contraption→INVESTMENT |
| **Mekanism** | 矿石 2x–5x、工厂、裂变/聚变 | 矿石倍增→材料供给↑；工厂→capex/tier；发电→能源供给；能量消耗→需求 |
| **GregTech** | 电压 tier、多方块、矿处理链、污染 | 电压 tier→TFP/ capex；多方块→资本存量；污染→RISK/监管 |
| **Thermal / Immersive** | 机器、能源、流体 | 通用 A 层即可；tier 用机器方块画像 |
| **Botania** | mana、花、符文、盖亚 | mana→能源；符文/泰拉钢→材料；祭坛→capex；盖亚→RISK/DISCOVERY |
| **Ars Nouveau** | source、法术、仪式、使魔 | source→能源；法术→SPELL 服务；仪式→RITUAL/capex；使魔→LABOR |
| **Blood Magic** | LP、祭坛、仪式 | LP→LABOR/能源；高风险→RISK/监管；仪式→capex |

> 科技产线的经济本质：**capex 形成 K，recipe 形成 VA，tier 形成 TFP，能源/原料形成成本，物流形成交付能力**。

---

## 6. 魔法模组：法术怎么翻译

魔法不是“另一个科技”，它有三类独特经济品：

1. **能源**：mana / source / aura / LP —— 像电力，有生产、存储、传输、价格。
2. **服务**：法术释放 —— 像软件/服务，边际成本低，但消耗试剂/法力，可能提升其他部门生产率。
3. **风险/知识**：失控、污染、禁忌、新法术 —— 像 R&D + 事故风险 + 监管。

### 6.1 事件映射

| 魔法动作 | 经济事件 | 影响 |
|---|---|---|
| 施法（buff/传送/治疗/攻击） | `SPELL` 服务产出 + 试剂/法力 `CONSUMPTION` | 服务业收入、试剂需求、施法者生产率 |
| 建造祭坛/法阵/魔力池 | `INVESTMENT` | capex 存量、当期材料需求、未来法术产能 |
| 研究新法术/附魔 | `RND` | TFP/新服务品类、长期成本↓ |
| 仪式（大型） | `RITUAL` + `RISK` | 一次性大额 capex/材料需求、区域风险溢价 |
| 魔法污染/失控/爆炸 | `RISK` / `LOSS` | 保险、监管、风险溢价、局部停产 |
| 稀有试剂/遗物 | `EXTRACTION` / `DISCOVERY` | 稀缺品价格、替代品需求 |
| 魔法物品/装备 | `PRODUCTION` / `CONSUMPTION` | 奢侈品/资本品，tier 提升工具效率 |

### 6.2 魔法 → 科技外溢

魔法不应是孤岛：

- **附魔/法术工具** → 采矿/建造效率 → 材料部门 TFP↑；
- **治疗/传送** → 劳动时间节约 → LABOR 有效投入↑；
- **魔法能源** → 替代燃料 → 能源价格↓、传统能源需求↓；
- **魔法风险** → 保险费率↑、监管成本↑、风险资产折价。

### 6.3 采集方式

- 原生事件（若模组提供 spell cast/ritual 事件）→ 高置信；
- 通用 fallback：法力/源力存储差分、试剂容器差分、法术书/祭坛方块事件、冷却/粒子/音效（低置信）；
- 对未知魔法模组：默认把“法力”当 `energy`、把“法术书/仪式”当 `RND`/`INVESTMENT`，并用 `OTHER` 低权重，避免把普通方块误判为魔法。

---

## 7. 进度/成就：技术进步，不是印钱

成就/任务/科技树解锁应映射为 **R&D 存量 / 技术 tier / TFP**，而不是一次性收入：

```text
RNDStock_{t+1} = (1−δ_r)·RNDStock_t + Σ novelty·VA_rnd
TFP_s = A0_s · exp(κ·techTier_s + λ·RNDStock_s)
```

- 首次解锁某配方/法术：`novelty=1`，但只提升未来产能/降低成本；
- 重复完成同一成就：`novelty→0`；
- Boss 首杀/新维度：`DISCOVERY`（储量/前沿）+ `RISK`（威胁）；
- 死亡/团灭：`LABOR` 损失 + `RISK`（保险赔付），不是“需求增加”。

---

## 8. 聚合与经济模型

### 8.1 三层状态

```java
// 公司（现有 CompanyData 扩展）
record CompanyState(String id, String sector, double capital, double inventory,
                    double production, double capex, double rnd, double techTier,
                    double labor, double energy, double risk) {}

// 板块
record SectorState(String id, double outputIndex, double capacityUtil,
                   double inputCostIndex, double inventoryDays, double capex,
                   double tfp, double risk, double margin) {}

// 世界宏观（底部向上，与现有 MacroSnapshot 并行）
record WorldMacroState(long tick, double productionIndex, double ppi, double cpi,
                       double capacityUtil, double eventRisk, double novelty,
                       Map<String, SectorState> sectors) {}
```

> 现有 `MacroSnapshot` 是**政策/周期**（顶部向下）；`WorldMacroState` 是**玩家经济**（底部向上）。
> 两者在 `MacroTransmission` 合并，**不把玩家动作写回政策利率**；政策仍由 Taylor 规则对产出缺口/通胀反应。

### 8.2 生产函数

对每个公司/板块：

```text
Y = A · K^α · L^β · E^γ · M^δ
K_{t+1} = (1−δ_k)·K_t + capex_t
A = A0 · exp(κ·techTier + λ·RNDStock)
```

- 机器/祭坛/轨道 → `K`（存量，折旧）；
- 玩家劳动/村民/使魔 → `L`；
- 能源/法力 → `E`；
- 原料 → `M`；
- 技术/成就/新法术 → `A`（TFP）。

### 8.3 成本与价格

- 商品：现有 `CommodityEntry`：
  ```text
  excess = (D − S)/(D + S)
  price = costAnchor · (1 + 0.20·excess) · macroFactor
  ```
  生产事件 `S += q`，消费事件 `D += q`，上游成本 `costAnchor` 按配方图更新；
- 板块成本：`Δcost_s = Σ_u a_{s,u}·Δprice_u`（Leontief）；
- 公司毛利：`margin = revenue − inputCost − energyCost − laborCost − depreciation`；
- CPI/PPI：一篮子商品价格加权；能源/食品单独权重。

### 8.4 事件 → 价格（只通过因子/基本面，不直接改盘口）

```text
eventImpact_s = β_s · tanh(surprise) · novelty · decay(τ)
fundamental_s = DCF(earnings_s, discountRate_s, growth_s)
fairValue_i = Σ_s exposure_{i,s} · fundamental_s
```

- `CommodityMarket`：供需 → 商品价；
- `CompanyData`：VA/成本/产能 → 盈利/净资产/财报；
- `AssetSnapshot`：板块暴露 → 公允价值；
- `MultiAgentManager`：agent 读 `fairValue`/`sectorMomentum`/`risk`，自己下单价；
- `MarketSentiment`：事件叙事 → 情绪（只影响微观，不写宏观）。

**禁止**：`event → lastPrice *= factor` 的直接价格补丁。价格必须由订单簿/agent 产生。

---

## 9. 防刷、一致性与性能

### 9.1 防刷

| 风险 | 机制 |
|---|---|
| 刷石机无限产出 | 净增加值 VA；供给饱和；单玩家/公司日上限；`tanh` 饱和；商品价格下限 |
| 合成-出售套利 | 配方图环检测；交易成本；售价 < 成本时不产生正 VA；审计标记 |
| 建 1000 台机器当收入 | 放置 = capex 存量，按折旧入账；只有运行产出才是流量 |
| 反复施法刷 GDP | 法术服务按 `novelty` 衰减；试剂/法力成本扣除；服务需求有饱和 |
| 单玩家操纵板块 | 单玩家/公司/板块日影响 cap；大额事件拆分为多日；市场深度/冲击成本 |
| 未知模组误分类 | `OTHER` 低权重；未映射账本；管理员 override |

### 9.2 性能

- 事件队列：每 tick 收集，每 20 tick 聚合一次；上限（如 20k/日），溢出按公司/类型聚合。
- 采样：公司机器 1 秒~1 天一次；不要每 tick 遍历区块/方块实体。
- 预计算：配方图/IO 矩阵/物品画像在服务器启动或数据包重载时构建；运行时 O(事件 + 板块 + 资产)。
- 线程：全部 ServerLevel 主线程；Kronos/TABL 推理仍异步/离线。
- 持久化：`WorldMacroState`、公司扩展字段、事件账本（可滚动/采样）进 NBT/H2；读档按 tick 重建派生量。

### 9.3 可复现

- 事件按 `(tick, seq)` 排序；`SeedableRandom` 只用于世界生成，不用于事件排序；
- 同种子 + 同动作序列 → 同商品价/公司盈利/板块因子；
- 禁止 `Math.random()`、wall clock、玩家客户端时间。

---

## 10. 代码落点（与现有工程对接）

### 10.1 新增包 `engine.macro.world`

| 类 | 职责 |
|---|---|
| `WorldActionCollector` | NeoForge 事件订阅 + Capability/容器采样 → `WorldAction` |
| `EconomicEvent` / `EconomicType` / `ResourceFlow` | L1 事件模型 |
| `EconomicEventBus` | 有界队列、按 tick 聚合、审计 |
| `ItemEconomicProfile` / `EconomicProfileRegistry` | L2 物品画像/JSON |
| `RecipeGraph` / `InputOutputMatrix` | 配方图、成本/VA/tier/瓶颈/Leontief |
| `CompanyEconomy` | 公司 K/L/E/M/tech/risk，扩展 `CompanyData` |
| `SectorEconomy` / `WorldMacroState` | 板块/世界聚合 |
| `MacroFlowAggregator` | L3 每 20 tick 聚合、EWMA/surprise |
| `MacroTransmission` | L4 商品/公司/资产因子/情绪 |
| `WorldMacroSnapshot` | 只读快照，供 agent/UI |

### 10.2 现有接入点

| 现有类 | 接入 |
|---|---|
| `FinancialMarket.Events` | 增加 `BlockEvent.BreakEvent` / `EntityPlaceEvent` / `ItemCraftedEvent` / `LivingDeathEvent` / `AdvancementEvent` 订阅，转 `WorldActionCollector` |
| `MarketEngine.tick` | 每 20 tick 调 `macroFlowAggregator.step(level, calendar)`；`buildMacroSnapshot` 合并 `WorldMacroState` |
| `CommodityMarket` / `CommodityEntry` | 新增 `applyProduction/Consumption(event)`；`adjustSupply/Demand`；`industries` 映射板块 |
| `CompanyData` / `CompanyManager` | 增加 `sector/capital/production/capex/rnd/techTier/risk`；`CompanyFinancialReport` 记录 VA/成本 |
| `AssetScanner` | 保留日度库存/资产扫描；新增“容器差分 → 生产/消费”低频采样，避免与事件双计 |
| `AssetSnapshot` / `PriceDiscoveryEngine` | 增加 `sectorExposure`/`eventFairValue`/`eventRisk` 因子 |
| `MultiAgentManager` | agent 读 `WorldMacroSnapshot`/`SectorSignal`；不写回 |
| `MarketSentiment` | `applyEconomicEvent(event)`：叙事/情绪，只影响微观 |
| `MacroStateEngine` / `MacroEnvironment` | 保持政策层；可读 `WorldMacroState` 的产出缺口/通胀，但不直接读原始动作 |

### 10.3 一个事件的生命周期（示例：Mekanism 5x 矿石产线）

```text
1. 玩家放置 20 台富集仓/碎矿机
   → INVESTMENT：capex=20×machineValue，K↑，当期材料需求↑
2. 机器运行：输入 1000 矿石，输出 5000 锭（5x）
   → PRODUCTION：VA = 5000×ingotPrice − 1000×orePrice − energyCost
   → CommodityEntry(ore).demand += 1000；CommodityEntry(ingot).supply += 5000
3. 产出卖到市场/公司库存
   → 公司 revenue↑、inventory↑；商品价格因供给↑而↓
4. 板块 materials 的 outputIndex↑、margin 变化
   → 资产 factor 更新 → agent fairValue 调整 → 盘口价格变化
5. 预期 EWMA：第二天同样产出的 surprise 下降；只有“新增产线/新配方/新法术”才有高 novelty
```

---

## 11. MVP 与验收

### 11.1 分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M1 通用** | 事件模型/总线；原版 + 标签/配方；3 个板块（材料/能源/消费）；公司/商品聚合；审计命令 | 覆盖 ≥80% 原版物品；供给↑→价格↓；无 NaN；tick 预算 |
| **M2 科技** | 配方图/IO 矩阵；Create/Mekanism/Thermal 通用适配；capex/tier/TFP | 5x 矿石产线能正确传导到锭价/公司毛利 |
| **M3 魔法** | Botania/Ars/Blood 的 mana/source/LP；SPELL/RITUAL/RISK；魔法→科技外溢 | 施法→试剂需求/服务收入；仪式→capex；失控→风险溢价 |
| **M4 传输** | 板块因子/公司财报/资产 fairValue/agent 消费；UI/账本；参数校准 | 事件只解释 5–20% 日波动；单玩家不能操纵板块 |

### 11.2 硬验收

1. **经济符号**：`production↑ → price↓`；`demand↑ → price↑`；`capex↑ → 未来产出↑`；`R&D↑ → TFP↑`；`risk↑ → discount↑`。
2. **守恒**：公司 VA ≈ 收入 − 投入 − 成本；商品供需账本可对账；无负库存/负价格。
3. **防刷**：单玩家/公司日影响 ≤ cap；循环配方被检测；重复动作边际递减。
4. **性能**：平均 <0.05 ms/tick，峰值 <0.5 ms；事件队列有界。
5. **可复现**：同种子/同动作序列逐位一致；无 `Math.random()`/wall clock。
6. **可解释**：`/fm macro why <asset>` 输出：哪些事件、哪个板块、多少 surprise、传导链。

---

## 12. 开放问题（需要拍板）

1. **模组清单**：优先支持哪些？Create / Mekanism / GregTech / Thermal / Botania / Ars Nouveau / Blood Magic / Thaumcraft / 其他？
2. **主体粒度**：只按公司（`CompanyData`）还是也按玩家个体？建议：公司为主，个体户自动建“个人公司”。
3. **服务器规模**：预计玩家数/公司数/机器数？决定采样频率与事件上限。
4. **数据包作者**：是否允许整合包作者写 `economy/items.json` 覆盖？建议允许。
5. **UI**：是否需要宏观仪表盘/事件账本/“为什么涨跌”面板？
6. **政策耦合**：玩家经济是否允许影响央行（通过产出缺口/通胀的 Taylor 规则间接影响），还是完全隔离？建议间接、平滑、上限。

---

## 13. 一句话总结

**把玩家行动变成“净增加值 + 资本形成 + 技术/风险变化”，用配方图和物品画像做模组无关的翻译；加总到公司/板块/世界；只让预期差通过商品供需、公司盈利、板块因子和 agent 估值进入股市。**
科技模组提供 capex/产能/能源/物流，魔法模组提供 mana 能源/法术服务/R&D/风险；未知模组用标签+配方图+低权重 fallback，绝不静默丢弃。
