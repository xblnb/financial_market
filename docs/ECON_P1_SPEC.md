# ECON_P1_SPEC — P1 行为金融因子落地实施规格（需求契约，供 t2/t3/t4 实施、t5 验证、t6 评审）

> - 角色：tuning-consultant（行为模型/参数调优顾问）为任务 t1 产出。
> - 输入：`ECON_IMPROVEMENT_PLAN.md` §2.2/§3（P1-1~P1-4）+ 当前代码审计（P0 已落地：GARCH、混合锚、可播种 PRNG、撤单、死码接线）。
> - 用途：behavior-dev 按本规格实现 t2/t3/t4；test-engineer 按本规格实现 t5（P1 stylized facts 验证 + 整体构建）；reviewer 按本规格验收 t6。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick 步进内 O(agent)/O(asset) 常数级计算。**不动价格模型与订单簿**（`PriceDiscoveryEngine.step` 的 GBM/GARCH、`OrderBook` 撮合均不修改）。
> - **可复现性硬约束（继承 P0/A0-3）**：同一种子同行情必须保持。所有新增随机一律使用 `SeedableRandom`（`engine/random/SeedableRandom.java`，SplitMix64），禁用 `ThreadLocalRandom`/裸 `new Random()`/`Math.random()`。
> - **实施优先级（船长定调）**：羊群效应（P1-1 Cont–Bouchaud）是**核心因子**，作为主打磨对象——传染网络、参数档位、簇状同步→肥尾机制都做到最细；前景/处置（P1-2）、BSV（P1-3）、情绪正反馈（P1-4）作为**辅助因子**配合，参数取保守默认，实现优先级低于羊群。**behavior-dev 应先把羊群做到位，其余因子在羊群稳定后叠加。**
> - 范围：仅 P1 行为因子。P2（订单簿/ABM 微观结构重构）不在此规格内。

---

## 0. 目标与全局验收（对应团队 Goal）

P1 完成时须在保持 P0 可复现性的前提下，让价格出现人性化的泡沫/崩盘、趋势/反转、波动聚集与长记忆。**羊群效应是达成「泡沫/崩盘 + 波动聚集 + 长记忆」的主要驱动**，其余因子辅助。全局验收：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| A1-0 | 编译通过 | `./gradlew compileJava` 退出码 0 | 每项实施 |
| A1-1 | 可复现性不回归（继承 P0） | 同 `engineSeed` 跑固定 tick，两次成交价序列逐元素一致 | t3/t4 + t5 |
| A1-2 | **羊群泡沫/崩盘可观察（核心）** | 固定种子下，放大羊群强度 `a` 到强档位（0.25），出现清晰「涨—追涨—再涨」泡沫段与随后崩盘；`a=0`（关）时无此形态 | **t2** + t5 |
| A1-3 | 处置效应不对称（辅助） | 盈利 agent 卖出倾向 > 亏损 agent（赢家拿不住、亏家扛着） | t3 + t5 |
| A1-4 | 短期动量 + 长期反转（辅助） | 收益出现短期正自相关（动量）与长期负自相关（反转） | t4 + t5 |
| A1-5 | 情绪-价格正反馈闭环（辅助） | `globalSentiment`/`buyRatio` ↔ 价格形成 self-reinforcing loop，且不发散（k_fb < 阈值） | t4 + t5 |
| A1-6 | 波动聚集/长记忆增强（核心，羊群驱动） | P0 基线（GARCH 已给波动聚集）叠加羊群后，肥尾峰度、绝对收益自相关、长记忆（Hurst）进一步向现实区间移动；**核心证据是 `a` 从 0→0.15→0.25 时指标单调增强** | **t2** + t5 |

验收流程（t5 落地）：用固定 `engineSeed` 跑固定 tick 数，输出 P0 基线（已有 `StylizedFactsValidator`）与 P1 后两套指标；**并以 `a` 为扫描轴（0 / 0.15 / 0.25）测羊群强度梯度**，证明羊群因子贡献（核心验收）。

---

## 1. 总设计原则（约束所有实现）

1. **轻量参数化**：所有因子以参数包形式集中配置（见 §6），默认值满足收敛性（`a<0.3`、`k_fb<0.1` 等硬上限）。
2. **纯增量嵌入**：只改 `MultiAgentManager` 决策函数与 `MarketSentiment`，新增 `engine/behavior/HerdingModel.java`（核心）、`engine/behavior/BehaviorUtility.java`（辅助），**不修改** `PriceDiscoveryEngine.step` 的 GBM/GARCH、`OrderBook.matchWithSlippage`、`AICompanyData` 的 fairValue 公式。
3. **可复现**：每个新模块用 `engineSeed` 派生子流（`SeedableRandom.mix64(engineSeed ^ "<module>.hashCode()")`），沿用 P0 模式。羊群的 `shouldHerd` 随机判定、参数初始化均走 `SeedableRandom`，保证同种子同行情。
4. **开关可控 / 因子隔离**：`a=0` 时羊群完全关闭且行为与 P0 逐位一致。每个因子有独立启用标志，便于隔离验证与 `a` 梯度测试。

---

## 2. P1-1 Cont–Bouchaud 羊群自旋模型（t2，核心因子，G-05/07）【主打磨对象】

> 本因子是本规格的**重心**。以下传染网络、参数档位、机制均须完整实现并可调，实现顺序优先。

### 2.1 改动文件
- 新增 `src/main/java/com/example/financialmarket/engine/behavior/HerdingModel.java`（核心，需写最完整）
- 改 `MultiAgentManager.java`

### 2.2 传染网络（拓扑）

**A. 分组（group）作为自旋网络节点**
- 每个 agent 视为一个自旋（spin）：`+1` 买 / `−1` 卖 / `0` 观望。
- `group` 键复用 `company.getIndustry()`（`AICompanyData.getIndustry()`，`AICompanyData.java:208`），即**同行业公司 agent 互相连接**，贴合 Minecraft 行业群世界观。
- 每个 group 维护一个**聚合净方向** `netDirection ∈ [-1,1]`，作为该行业的「群体净情绪」；agent 以概率 `a` 向这个群体净方向同步。

**B. 网络连接度与簇**
- 每个 group 记录 `agentCount`（连接节点数）与 `clusterCap`（簇大小上限，默认 50，防单簇无限同步）。
- 簇状同步：当 `a` 接近临界值时，多个 agent 同时向 `netDirection` 同步 → 订单方向集中出现 → 单个方向的大订单流 → **肥尾 + 波动聚集 + 长记忆**（正好补现有 GBM 最缺的三件事）。
- 网络随机（若需连接度初始化随机）走 `SeedableRandom` 派生，保证可复现。

### 2.3 羊群强度 `a`：区间 / 默认值 / 调参档位

| 档位 | `a` 取值 | 效果 | 用途 |
|---|---|---|---|
| **关闭** | `a = 0` | 无羊群，行为 = P0 基线 | 因子隔离、回归基线 |
| **轻羊群** | `a = 0.15`（默认） | 温和簇状同步，出现轻度动量与波动聚集 | 默认稳定档 |
| **强羊群** | `a = 0.25` | 强簇状同步，清晰泡沫段「涨—追涨—再涨」+ 随后崩盘 | A1-2 核心验收档 |
| **临界** | `a ≥ 0.30` | 过度同步，可能整体漂移/跑飞 | **硬上限 `a < 0.3`，禁止越过** |

- `a` 硬约束：实现处 `a = Math.min(0.3, a)` 强制钳制。
- `a` 以参数包暴露（`herding.a`），t5 用 `a ∈ {0, 0.15, 0.25}` 梯度扫描验证。

### 2.4 数据结构与方法（`HerdingModel`，纯 Java，不依赖 Minecraft 运行时类）

```java
public class HerdingModel {
    private final SeedableRandom rng;          // P0-7 派生，同种子可复现
    private final double a;                    // 羊群强度 (0~0.3)
    private final double memory;               // 净方向 EMA 记忆系数 (默认 0.10)
    private final int clusterCap;              // 簇上限 (默认 50)
    private final Map<String, GroupState> groups = new HashMap<>();

    static class GroupState {
        double netDirection;   // ∈ [-1,1]
        int agentCount;
    }

    void setGroup(String group, int agentCount);       // 初始化/更新
    void updateBias(String group, double netFlow);     // EMA: nd = nd*(1-mem) + netFlow*mem，钳制 [-1,1]
    double getBias(String group);                      // 供 agent 方向跟随
    boolean shouldHerd();                              // 以概率 a 返回 true（跟随群体），否则独立
}
```

- `shouldHerd()`：`return rng.nextDouble() < a;`（`rng` 为模块派生子流）。
- `updateBias` 的 `netFlow`：由 agent 决策后回写（见 §2.5.B.3）。

### 2.5 `MultiAgentManager` 嵌入（决策入口改造）

**A. 初始化**
- `MultiAgentManager(long engineSeed)`（`MultiAgentManager.java:106`）增加：
  `this.herding = new HerdingModel(SeedableRandom.mix64(engineSeed ^ "herding".hashCode()), params);`
  （或由外部注入，保持构造签名兼容——推荐在构造内创建，避免 `MarketEngine` 改动面过大。）
- `stepAgents`（`MultiAgentManager.java:177`）内，`company` 已可拿到（`:182-183`），用 `company.getIndustry()` 作为 `group`，并 `herding.setGroup(group, agents.size())`。

**B. 方向决策叠加**
1. 在每个决策函数（`valueDecision/retailDecision/momentumDecision/marketMakerDecision`，`MultiAgentManager.java:274-429`）算出独立 `isBuy` 后：
   ```java
   if (herding.shouldHerd()) {
       isBuy = herding.getBias(group) >= 0;   // 跟随群体净方向
   }
   ```
2. **理性度缩放（推荐）**：羊群强度对理性型弱化——乘 `(1 - agent.fundamentalSensitivity)`：
   - VALUE（fundamentalSensitivity 0.9~1.0）几乎不羊群（×~0.1）
   - RETAIL（0.6~0.9）中等羊群
   - MOMENTUM 天然趋势跟随，羊群增强其追涨
   - 实现：`if (herding.rng.nextDouble() < a * (1 - agent.fundamentalSensitivity)) isBuy = bias>=0;`
3. **净流向回写**：`stepAgents` 生成 `newOrders` 后，对每订单 `isBuy ? +1 : -1` 累加为 `netFlow`，`herding.updateBias(group, netFlow)`（单位化到 `group` 大小附近，避免放大订单量）。

**C. 簇状同步 → 肥尾机制（实现时保证）**
- 多个 agent 以概率 `a` 同时向 `netDirection` 同步 → 同一 tick 出现**成串同向订单** → 订单簿单侧深度被消耗 → 价格出现**大单步**（肥尾收益）。
- `a` 越接近 0.3，簇越同步，收益尾部越厚；`a=0` 时各 agent 独立，收益接近高斯（P0 基线）。

### 2.6 验收（t2 自身）
- `a=0` 时决策结果与 P0 完全一致（等价于未启用羊群）。
- `a=0.25` 时，固定种子下多资产出现「buyRatio 上升 → 价格上涨 → buyRatio 再上升」的正反馈泡沫段，随后方向反转崩盘段。
- **`a` 梯度**：`a=0.15` 比 `a=0` 出现更强波动聚集；`a=0.25` 比 `a=0.15` 出现更明显泡沫/崩盘形态（可用 `getBias` 或成交价序列目视/打印验证）。
- 不引入额外随机源：`HerdingModel` 随机全部走 `SeedableRandom`；同 `engineSeed` 两次运行成交价一致。

---

## 3. P1-2 前景理论 / 损失厌恶 / 处置效应（t3，辅助因子，G-06）

> 辅助定位：默认参数保守，不干扰羊群主效果。实现顺序在羊群（t2）之后叠加。

### 3.1 改动文件
- 新增 `src/main/java/com/example/financialmarket/engine/behavior/BehaviorUtility.java`（辅助，实现保持轻量）
- 改 `MultiAgentManager.java`（决策层的卖出倾向）

### 3.2 需求

**A. 新增 `BehaviorUtility`**（纯 Java，无随机）：
1. 分段幂价值函数（前景理论）：
   `V(x) = x^α (x≥0)`, `V(x) = −λ·(−x)^β (x<0)`
   参数默认：`λ=2.0`（损失厌恶），`α=0.88`，`β=0.88`（保守文献值）。
2. 处置效应强度：`k_dispo=1.2`（保守默认，避免过度卖出/持有极端化）。
3. 方法：
   - `double prospectValue(double x)`：返回分段幂价值。
   - `double dispositionSellMultiplier(double gain)`：`gain = (lastPrice − avgCost)/avgCost`，参考点用 `AgentState.avgCost`（`MultiAgentManager.java:20` 已存在）。返回卖出倾向乘数：
     - `gain > 0`（盈利）→ `> 1`（赢家更倾向卖出，处置效应）
     - `gain < 0`（亏损）→ `< 1`（亏家更倾向持有）
     - 具体：`mult = 1 + k_dispo * gain`（gain 钳制到 ±0.5 防发散），再钳制到 `[0.5, 2.0]`。

**B. `MultiAgentManager` 嵌入卖出倾向**：
- 在 `retailDecision`（`MultiAgentManager.java:307`）与 `momentumDecision`（`:352`）的**卖出分支**（`isBuy==false` 且 `agent.holdings>0`）应用处置乘数：
  - 计算 `gain = (lastPrice - agent.avgCost) / agent.avgCost`（`avgCost>0` 时）。
  - `double sellMult = behaviorUtility.dispositionSellMultiplier(gain);`
  - `if (random.nextDouble() < 1.0 / sellMult) return null;`（持有），否则继续卖出。
  - **注**：VALUE 型因基本面主导，处置效应可弱化（乘 `(1 - agent.fundamentalSensitivity)`），保持价值型理性。

### 3.3 验收（t3 自身）
- 固定种子下，对同一组 agent：`gain>0` 时卖出订单数量/概率 > `gain<0` 时（处置效应不对称）。
- 不改变 `createOrder`/订单结构；不动 `OrderBook`；不削弱羊群泡沫/崩盘形态（回归对比 `a=0.25` 仍可观察到泡沫段）。

---

## 4. P1-3 BSV 过度反应 / 反应不足（t4，辅助因子，G-05/07）

> 辅助定位：默认参数保守，配合羊群提供「短期动量 + 长期反转」的微观结构，但不作为主驱动。

### 4.1 改动文件
- 改 `MarketSentiment.java`（增加事件缓冲 + 预期修正）
- 改 `MultiAgentManager.java`（事件感知接入 agent 预期）

### 4.2 需求

1. **事件缓冲**：`MarketSentiment` 增加 `Map<String, Deque<Double>> eventBuffer`（每资产保留最近 N 个事件幅度，如 N=10）与 `Map<String, Double> expectedSentiment`（资产级预期）。
2. **事件注入点**：`adjustAssetSentiment(assetId, delta)`（`MarketSentiment.java:82`，已被 `SecuritiesCompany` 调用）升级为同时记录事件到 `eventBuffer` 并触发预期修正；新增 `updateGlobalSentiment`（`:55`）输出 `globalSentiment` 时，global 级事件同样入缓冲。
3. **预期修正**：`ΔE = k_resp · (news − expected)`，`k_resp` 为反应因子（**保守默认 0.6**）。`expected = expectedSentiment.getOrDefault(assetId, current)`。
   - **成串检测**：统计 eventBuffer 中**连续同号**幅度个数 `run`：
     - `run < θ_run`（成串阈值，默认 3）→ **持续型（反应不足/动量）**：`k = k_resp`（小修正，趋势延续）。
     - `run ≥ θ_run` → **均值回归型（过度反应/反转）**：`k = k_resp * 1.5` 且对同号做超调后反转修正（可设 `k_rev` 反转系数，**保守默认 1.0**）。
   - `expectedSentiment` 用 `expected += ΔE` 更新，钳制 [0,1]。
4. **agent 预期接入**：`MultiAgentManager` 在决策时把 `sentiment.getBuyRatio(assetId)`（`MultiAgentManager.java:231`）替换/叠加为考虑 `expectedSentiment` 的修正 buyRatio：`effectiveBuyRatio = clamp( buyRatio + k_exp·(expected − current), 0, 1)`，`k_exp` 为预期渗透系数（**保守默认 0.5**）。

### 4.3 验收（t4 自身）
- 构造一串同号事件后，buyRatio 沿同方向持续放大（反应不足/动量）；超过 `θ_run` 后出现方向反转（过度反应/反转）。
- 事件缓冲与 `expectedSentiment` 状态在固定种子下可复现；状态量可随存档序列化（见 §7 兼容）。
- 不与羊群主效果冲突：BSV 开关独立，关闭时 `a=0.25` 羊群泡沫形态仍成立。

---

## 5. P1-4 情绪–价格正反馈（泡沫/崩盘）（t4，辅助因子，G-07）

> 辅助定位：`k_fb` 取保守默认，作为羊群之外的正反馈补充，非主驱动；硬上限防发散。

### 5.1 改动文件
- 改 `MarketSentiment.java`、`MultiAgentManager.java`、`PriceDiscoveryEngine.java`

### 5.2 需求

1. **价格变动 → 情绪**：在 `PriceDiscoveryEngine.updateLastPrice(assetId, price)`（`PriceDiscoveryEngine.java:424`）中，用价格收益率回馈情绪：
   `priceReturn = (price - prevPrice)/prevPrice`（prevPrice 用 `gbmStates.get(assetId).price` 或上一步 lastPrice）。
   `sentiment.adjustAssetSentiment(assetId, k_fb · priceReturn)`，`k_fb` 为反馈系数（**保守默认 0.05**，**硬约束 k_fb < 0.1 防发散**）。
   - 因 `PriceDiscoveryEngine` 已持 `sentiment` 引用（`setMacroDependencies`，`:110`），此处可直接调用；若为解耦，也可在 `MarketEngine.tick` 撮合后调用（`MarketEngine.java:290` `updateLastPrice` 处）。**推荐在 `PriceDiscoveryEngine.updateLastPrice` 内实现**，保证 `submitOrder` 路径（`MarketEngine.java:369`）也生效。
2. **情绪 → agent 下单**：已由 P0-4 实现（`stepAgents` 用 `buyRatio` 影响 isBuy 与下单大小，`MultiAgentManager.java:231,282-283`）。P1 保持并叠加 P1-3 的预期修正。
3. **闭环**：价格涨 → 情绪升 → buyRatio 升 → 更多买单 → 价格再涨（泡沫）；价格跌 → 情绪降 → 卖压 → 崩盘。`k_fb` 防发散钳制 + `getBuyRatio` 已有 [0.1,0.9] 钳制（`MarketSentiment.java:48`）双重保障。

### 5.3 验收（t4 自身）
- 固定种子下，`k_fb=0.05` 辅助羊群出现泡沫-崩盘形态（上涨动量段 + 随后崩盘，收益分布左偏）。
- `k_fb=0.15`（超阈值）可能出现发散/振荡——用此验证硬上限必要性，但默认取 0.05。
- 同种子两次运行成交价序列一致（正反馈不破坏可复现性）。

---

## 6. 行为因子参数包与调参约束（tuning-consultant 建议）

集中为 `MarketEngine` 内常量或独立 `EconParams` 常量类（推荐 `engine/behavior/BehaviorParams.java`）：

```
// ===== 核心因子：羊群 =====
herding.a            = 0.15      // 默认轻羊群；档位：0 关 / 0.15 轻 / 0.25 强；硬上限 < 0.3
herding.memory       = 0.10      // 净方向 EMA 记忆
herding.clusterCap   = 50
// ===== 辅助因子 =====
prospect.lambda      = 2.0       // 保守
prospect.alpha       = 0.88      // 保守
prospect.beta        = 0.88      // 保守
disposition.k        = 1.2       // 保守
bsv.k_resp           = 0.6       // 保守
bsv.thetaRun         = 3         // 成串阈值
bsv.k_rev            = 1.0       // 反转系数
bsv.k_exp            = 0.5       // 预期渗透到 buyRatio
feedback.k_fb        = 0.05      // 保守，必须 < 0.1 防发散
feedback.globalK_fb  = 0.02      // globalSentiment 对价格的反馈（可选）
```

- **收敛性依赖**：发散主要由 `k_fb` 与 `a` 驱动。`k_fb<0.1`、`a<0.3` 设硬上限（在 `BehaviorParams` 或构造处 `Math.min` 强制）。
- **调参策略（t5 用固定种子一键重跑对比）**：
  - **核心验证**：以 `a` 为扫描轴（0 / 0.15 / 0.25）测羊群强度梯度，确认肥尾/波动聚集/泡沫形态随 `a` 单调增强。
  - 若价格振荡/跑飞，优先降 `k_fb`、再降 `a`；若羊群不显，升 `a`（<0.3 内）。
  - 辅助因子默认保守，不参与主验证的强耦合，避免调参相互干扰。
- 所有参数默认值先离线调参（t5 用固定种子一键重跑对比），确认后再定默认值。

---

## 7. 兼容性（继承 P0 §5，P1 增量）

### 7.1 网络包 / UI / Web
- P1 不新增/删除任何 packet 字段。`globalSentiment`/`buyRatio` 如需 UI 展示为增量字段（加到 `CompanyDataResponsePacket` 或 Web JSON，用默认值向后兼容），可选非必须。

### 7.2 H2 / NBT 持久化
- `engine_kv` 的 `engine_main` blob 增加行为因子状态：`HerdingModel` 各 group 的 `netDirection`、`MarketSentiment` 的 `eventBuffer`/`expectedSentiment`、`BehaviorParams` 参数包（整块 NBT 序列化天然支持，只加字段）。
- `ai_company` 表结构优先不动，避免 H2 迁移。
- 前向兼容：读旧 blob 缺新字段用默认值重建（复用 `MarketAttachment`「空资产→init」兜底）。

### 7.3 可复现（羊群 × SeedableRandom 结合点）
- `HerdingModel` 用 `SeedableRandom.mix64(engineSeed ^ "herding".hashCode())` 派生子流；`shouldHerd()` 的随机判定、参数初始化全部走该子流 → **同 `engineSeed` 下，羊群同步时序完全确定，行情可复现**。
- `HerdingModel.netDirection` 状态如随 blob 序列化，读档后羊群状态恢复，行情继续复现；若不同步则读档后从默认状态重建（可接受，P1 记录为已知限制）。
- **验证**：t5 固定 `engineSeed` 两次运行，成交价序列哈希一致（A1-1）；且 `a=0` 结果与 P0 基线逐位一致（隔离）。

---

## 8. 对 t5（P1 stylized facts 验证 + 整体构建验证）的需求

1. **扩展 `StylizedFactsValidator`**（`engine/validation/StylizedFactsValidator.java`，P0 已建）增加 P1 专属指标：
   - 收益分布**左偏**（`skewness < 0`，崩盘特征）
   - **短期动量**：`acfReturns` 滞后 1~5 出现正自相关（羊群+BSV 动量）
   - **长期反转**：`acfReturns` 滞后 15~20 出现负自相关（BSV 反转）
   - 对比 P0 基线与 P1 后：肥尾峰度、`|r|` 自相关、Hurst 长记忆是否向现实移动。
   - **核心因子隔离（羊群梯度）**：同种子分别跑 `a=0` / `a=0.15` / `a=0.25`，对比泡沫/崩盘形态、动量、肥尾指标，证明羊群强度梯度贡献（A1-2/A1-6 核心）。
2. **构建验证**：`./gradlew compileJava` 通过；`./gradlew build` 出包。
3. **整体冒烟**：`./gradlew runServer` 启动无 NPE、tick 推进。
4. **可复现脚本**：固定 `engineSeed` 一键重跑 N tick，成交价序列哈希两次一致（A1-1）。

---

## 9. 实施顺序与依赖（供 scheduler）

```
t1 (本规格) ──→ t2 (P1-1 羊群【核心优先】: HerdingModel) ──┐
        └──→ t4 (P1-3 BSV + P1-4 正反馈【辅助】: MarketSentiment) ──┼─→ t3 (P1-2 前景/处置【辅助】: BehaviorUtility) ──→ t5 (验证) ──→ t6 (评审)
```
- 依赖已由队长在 DAG 体现：t2/t4 依赖 t1；t3 依赖 t2；t5 依赖 t3/t4；t6 依赖 t5。
- **优先级**：t2（羊群）为第一优先，须做到位并通过 A1-2/A1-6 核心验收；t3/t4（辅助因子）在羊群稳定后叠加，默认保守。

---

## 10. 与非目标边界（防范围蔓延）

- **不动** `PriceDiscoveryEngine.step` 的 GBM/GARCH 公式（P0 已定稿）与 `OrderBook` 撮合/滑点。
- **不动** `AICompanyData` fairValue 公式与 `ipoPrice` 量纲（P0 已定稿）。
- **不引入** P2 的 `OrderFlowModel`/`Hawkes`/价格冲击/校准注入。
- 行为因子全部以轻量参数化形式嵌入 `stepAgents` 决策层 + `MarketSentiment`，保持 O(agent)/O(asset) 常数级。
