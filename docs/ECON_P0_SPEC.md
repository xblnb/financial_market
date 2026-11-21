# ECON_P0_SPEC — P0 落地实施规格（需求契约，供 t2/t4~t8 实施、t9 验证）

> - 角色：behavior-consultant（行为金融/调参顾问）为任务 t1 产出。
> - 输入：`ECON_IMPROVEMENT_PLAN.md`（P0 分级路线）+ 代码审计（本规格已对照实际源码逐条给出文件/行号/方法签名）。
> - 用途：engine-dev 按本规格实现 t2/t4/t5/t6/t7/t8；test-engineer 按本规格实现 t9（StylizedFacts 基线 + 整体构建验证）；reviewer 按本规格验收。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick 步进内 O(资产/agent) 常数级计算。
> - 范围：仅 P0。P1（行为金融因子）/P2（微观结构）不在此规格内（但本规格预留参数与埋点，避免 P1 返工）。

---

## 0. 目标与全局验收（对应团队 Goal）

P0 完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| A0-1 | 编译通过 | `./gradlew compileJava` 退出码 0 | 每项实施 |
| A0-2 | 可运行 | server/client run 启动无 NPE、无死循环，`MarketEngine.tick` 持续推进 | t9 冒烟 |
| A0-3 | 同种子可复现 | 同一 `engineSeed` 跑固定 tick 数，两次成交价序列一致 | t7 + t9 |
| A0-4 | 基本面不再冻结 | 关闭基本面接线后（即打开接线并跑数季度），EPS/ROE/fairValue 随时间变化 | t4 + t9 |
| A0-5 | 订单簿不再无限堆积 | 运行 N tick 后每资产未成交挂单数有上界（=过期窗口内有限） | t6 + t9 |
| A0-6 | 宏观传导到成交价 | 加息/恐慌（VIX 上升）时成交价出现可观察的方向性下行 | t5 + t9 |

验收流程（t9 落地）：用固定 `engineSeed` 跑固定 tick 数，输出上述指标。P0 前（基线）接近「高斯、无聚集、无方向性」；P0 后对比向现实区间移动。

---

## 1. P0-1 GARCH(1,1) 波动率聚集（t2，对应 G-11）

### 1.1 改动文件
- `src/main/java/com/example/financialmarket/engine/PriceDiscoveryEngine.java`

### 1.2 需求
为每个资产的 GBM 路径引入**条件方差**状态，替代当前恒定 `sigma`（当前 `step()` 内 `gbm.sigma = effectiveSigma`，见 `PriceDiscoveryEngine.java:185`）。

1. **状态扩展** `GbmState`（`PriceDiscoveryEngine.java:23-34`）新增字段：
   - `double sigmaSq;`        // 条件方差 σ_t²
   - `double lastEpsilon;`    // 上一步标准化收益冲击 ε_{t-1}
   - `double garchOmega;`     // GARCH 常数项 ω
   - `double garchAlpha;`     // α
   - `double garchBeta;`      // β

2. **参数初始化**（在 `addAsset` 注册时，用**可播种** PRNG——见 §7，禁止 `ThreadLocalRandom`/裸 `new Random`）：
   - `garchAlpha = 0.05 + rng.nextDouble() * 0.10` → 落在 (0.05, 0.15)
   - `garchBeta  = 0.80 + rng.nextDouble() * 0.13` → 落在 (0.80, 0.93)
   - 约束校验：若 `alpha + beta >= 1`，强制 `beta = min(0.93, 1 - alpha - 0.01)`，保证平稳。
   - `sigmaSq` 初值 = `sigma * sigma`（用注册时 volatility）。
   - `lastEpsilon` 初值 = 0。

3. **GARCH 更新**（`step()`，`PriceDiscoveryEngine.java:163-196`）逐资产执行：
   ```
   // 用可播种 PRNG 生成标准化冲击 ε_t ~ N(0,1)
   double epsilon = rng.nextGaussian();
   // 更新条件方差（第 t 步）
   double longTermSigma = macroEnv.getDynamicVolatility();   // 宏观长期基线
   double omega = garchOmega 若已初始化，否则 omega = sigmaSq * (1 - alpha - beta)
   sigmaSq = omega + alpha * lastEpsilon*lastEpsilon + beta * sigmaSq;
   // 下限保护
   sigmaSq = Math.max(1e-8, sigmaSq);
   double sigma_t = Math.sqrt(sigmaSq);
   // 用 sigma_t 喂现有 GBM（替换当前 gbm.sigma）
   gbm.sigma = sigma_t;
   gbm.lastEpsilon = epsilon;
   double volTerm = gbm.sigma * Math.sqrt(DT) * epsilon;
   double newPrice = gbm.price * Math.exp(gbm.mu * DT + volTerm);
   ```
   > 宏观 `getDynamicVolatility()` 作为**长期方差基线**：初始 `omega = longTermSigma^2 * (1 - alpha - beta)`，使无条件方差锚定宏观基线。此公式保证 GARCH 稳定且波动水平不漂移。
   > 需保留软启动：`effectiveSigma` 的冷启动逻辑可用 `sigma_t * (0.1 + 0.9*noiseMultiplier)` 缩放（保持 KALMAN_WARMUP 行为）。

4. **约束**：
   - `alpha+beta < 1` 硬性保证（初始化和每次更新均校验）。
   - 单资产常数级计算，不触碰撮合/订单簿。
   - **不引入跳跃项**（P0 仅 GARCH；EGARCH/杠杆效应留待 P1）。

### 1.3 验收（t2 自身）
- `step()` 后每资产 `sigmaSq` 在冲击后一段时间内高于均值 → 复现「大波动后跟大波动」。
- 不破坏 `getKlines`/`getRecentCloses`/`getTechnicalKlines` 输出结构（K 线字段不变）。

---

## 2. P0-2 fairValue 动态化 + 基本面接线（t4，对应 G-01/G-02/G-04）

### 2.1 改动文件
- `MarketEngine.java`、`AICompanyData.java`、`AICompanyManager.java`、`MultiAgentManager.java`

### 2.2 需求

1. **接线基本面更新**：`MarketEngine.tick()`（`MarketEngine.java:149`）按「季度/年度」节奏调用
   `aiCompanyManager.updateAll(state, riskAppetite, inflation)`。
   - `AICompanyManager.updateAll` 已存在（`AICompanyManager.java:47`）且内部调 `AICompanyData.updateFundamentals`（`AICompanyData.java:77`）。
   - 节奏定义：新增常量 `FUNDAMENTAL_UPDATE_QUARTER_TICKS`（建议 `24000L * 30 * 3` = 一季，或复用现有 `TICKS_PER_DAY`，由 implementer 确定并注释），用 `gameTime` 取模触发。
   - `riskAppetite` 来源：优先 `macroEngine.getRiskAppetite()` 或 `state.getRiskAppetite()`（`MacroStateEngine` 输出，见 §6）。若两者皆 0 兜底，可用 `macroEnvironment.getRiskAppetite()`。
   - `inflation` 来源：`inflationSystem.getInflationRate()`。
   - 接线顺序：先 `macroEngine.step`/`cycleEngine.tick`（产出 riskAppetite/inflation），再 `updateAll`，保证用最新宏观值。

2. **`AICompanyData.updateFundamentals` 内 `Math.random()` 全部替换为可播种 PRNG**（`AICompanyData.java:79,83,90`），并让公司内部状态可持续变化（已有逻辑，只需被调用 + 可播种随机）。

3. **`MultiAgentManager.stepAgents` 已实时重算 fairValue**（`MultiAgentManager.java:177-180`），无需改动公式——基本面一变 fairValue 自然跟着动。**本项仅确保 `updateCompanyData(assetId, company)` 在公司基本面变化后被刷新**（`MarketEngine.tick` 已每 tick 调用，见 `MarketEngine.java:201`）。

### 2.3 量纲修复（P0-3，合并进 t4，对应 G-03）
- `AICompanyData.java` 构造（`:40-74`）：构造末尾已有 `updateFairValue()`（`:73`），故**构造后 `ipoPrice` 与 `fairValue` 可能差 2~4 倍**。需求：
  - 在构造完成 fairValue 后，令 `this.ipoPrice = fairValue`（或在 `registerAICompany` 中 `seedPrice` 用 `fairValue`）。
  - 同步确保 `MarketEngine.init()`（`:106-109`）与 `registerAICompany`（`:135`）播种价用 fairValue。
- 效果：消除「上市冲高到静态 fairValue 再横盘」的系统性高估。

### 2.4 持久化（P0-2 + G-04，合并进 t4）
- `MarketEngine.save()` 的 `aiCompanies` ListTag（`:384-395`）当前只存 id/name/industry/asset/us/cn → **读档重随机**。需求：扩充字段，持久化 `eps/roe/fairValue/revenue/revenueGrowth/netProfit/netAssetValue/growthStage/profitabilityScore/financialHealthScore/dividendYield/debtRatio` 等基本面字段。
- `loadInternal`（`:424-435`）加载时恢复这些字段，而非 `new AICompanyData(...)` 重随机。**前向兼容**：读旧存档缺字段时用默认值/重初始化兜底（复用 `MarketAttachment` 的「空资产→init」逻辑）。
- `PriceDiscoveryEngine.save()/load()`（`:447-547`）：`gbm` 标签增存 `sigmaSq/lastEpsilon/garchOmega/garchAlpha/garchBeta`；`load` 缺失时用默认重建并触发重初始化。

### 2.5 验收（t4 自身）
- 跑多个季度后，同资产的 EPS/ROE/fairValue 发生变化（不再恒定）。
- 同一种子下基本面序列可复现（见 §7）。
- 读档后基本面字段保持（非重随机）。
- `ipoPrice ≈ fairValue`（相对误差 < 1%）。

---

## 3. P0-4 混合定价锚 + 宏观/情绪反馈（t5，对应 G-01 锚层）

### 3.1 改动文件
- `MultiAgentManager.java`（四个决策方法 `valueDecision/retailDecision/momentumDecision/marketMakerDecision`，`:236-375`）

### 3.2 需求
把 agent 的限价锚从「纯 fairValue」升级为混合锚：

```
anchor = fairValue * (1 + β_gbm·(gbmPrice/fairValue − 1) + β_macro·(riskAppetite − 0.5))
```

1. 在 `stepAgents` 计算 `anchor`（`stepAgents` 已接收 `gbmPrice`，见 `MultiAgentManager.java:148`；`fairValue`、`riskAppetite` 已在方法内算出，`:157,179`）。
2. 四个决策方法**签名增加 `anchor` 参数**，`price = anchor * (1 + priceOffset)`（替换原 `fairValue * (1 + priceOffset)`，`:243,284,320,356`）。
3. **情绪接入下单方向/大小**：在 `retailDecision`/`momentumDecision`/`marketMakerDecision` 中用 `sentiment.getBuyRatio(assetId)`（需给 `MultiAgentManager` 注入 `MarketSentiment` 引用 + 在 `MarketEngine` 构造/init 时 `setSentiment(...)`）：
   - `isBuy` 方向当 `deviation` 在散户「抛硬币」区间（`retailDecision` `:270`）时，不再 `random.nextBoolean()`，改为 `random.nextDouble() < sentiment.getBuyRatio(assetId)`。
   - 可选：将 `(sentiment.getBuyRatio - 0.5)` 以 `β_sent` 系数并入 `anchor` 或 `priceOffset`。
4. **参数默认值**（集中为常量，便于调参）：
   - `BETA_GBM = 0.3`（可调，建议 0.1~0.5）
   - `BETA_MACRO = 0.3`（可调，建议 0.1~0.5）
   - `BETA_SENT = 0.2`（可调，建议 0~0.3）
   - **硬约束**：`|β_gbm| + |β_macro| + |β_sent| < 0.9` 防发散；`anchor` 钳制到 `[fairValue*0.5, fairValue*1.5]` 内防极端。

### 3.3 验收（t5 自身）
- 宏观加息（r↑）/恐慌（VIX↑→riskAppetite↓）时，`anchor` 下行 → 价值/散户买盘减少 → 成交价出现可观察下行。
- 固定种子下方向性可复现。
- 不引入价格振荡（用 §3.2 硬约束保证）。

---

## 4. P0-5 订单簿生命周期：撤单/过期（t6，对应 G-09）

### 4.1 改动文件
- `Order.java`、`OrderBook.java`、`MarketEngine.java`

### 4.2 需求
1. **`Order` 增加生命周期字段**：
   - `long placedTick;`       // 挂单时的游戏 tick
   - `int expireTicks;`       // 有效时长（默认，如 100 tick = 5 次 AI 步骤 × 20）
   - 构造器增加 `(long placedTick)` 或 setter，由挂单方传入。
2. **`OrderBook` 增加 `purgeExpired(long currentTick)`**：
   - 遍历 bids/asks，删除 `currentTick - placedTick >= expireTicks` 的未成交单（即过期单）。空档位价格移除。
3. **`MarketEngine.tick` 撮合前置**（`:233` 前）调用 `book.purgeExpired(gameTime)`；`submitOrder`（`:317`）撮合前同样 purge。
4. **AI 订单周期性重挂**：AI 订单当前每 20 tick 生成（`AI_ORDER_INTERVAL=20`，`:70`）。需求：未成交单超过 `AI_ORDER_EXPIRE=100` tick 即被 purge（上述过期机制天然实现「AI 每 N 次撤销未成交并重挂」）。可选：显式实现 `cancelUnfilled` 供 AI 定期调用，二选一，**以过期机制为主**。
5. **持久化**：`OrderBook` 当前不进 NBT/H2（读档重建）。P0 接受「读档后订单簿从空重建、由 agent 重新挂单」为可接受行为（不连续但非阻塞）；若实现者在 `MarketEngine.save()` 增存订单簿则视为增强，非必须。

### 4.3 验收（t6 自身）
- 运行 ≥ 200 tick 后，每资产未成交挂单数有上界（≈ 过期窗口内有限），不再无限堆积。
- 快照结构（`OrderBookSnapshotPacket` bids/asks 结构）保持不变，前端兼容。

---

## 5. P0-6 死代码接线（t8，对应 G-12）

### 5.1 改动文件
- `MarketEngine.java`（`tick()`）

### 5.2 需求
在 `MarketEngine.tick()` 依序接线（每个子系统一行调用 + 传参，若某依赖链缺失则在构造/init 补齐）：

1. `macroEngine.step(state, dataCollector)` → 产出 riskAppetite/creditImpulse/inflationPressure（写回 `state`）。`MacroStateEngine.step` 已实现（`MacroStateEngine.java:15`）。
2. `cycleEngine.tick(gameTime, creditSystem, commodityMarket)` → 产出 economicHealth。签名 `EconomicCycleEngine.tick(long, CreditSystem, CommodityMarket)`（`EconomicCycleEngine.java:14`）。
3. `fundEngine.step(state, dataCollector, macroEngine, companyManager, commodityMarket, aiCompanyManager)` → 公司 NAV 随行业商品价调整。签名匹配（`FundamentalEngine.java:9`）。
4. `sentiment.updateGlobalSentiment(commodityMarket, inflationSystem, government, centralBank, nplRatio, economicHealth)` → 产出 globalSentiment。签名匹配（`MarketSentiment.java:44`）。`nplRatio = creditSystem.getNplRatio()`，`economicHealth = cycleEngine.getEconomicHealth()`。
   - **接线频率**：以上按宏观节奏（如每 20 tick 或每季）调用，避免每 tick 高成本；`sentiment` 与 `macroEngine` 建议每 20 tick。
5. `MacroEconomy`：**明确删除**（当前全工程零引用，见审计）——删除 `src/main/java/com/example/financialmarket/engine/macro/MacroEconomy.java`，或保留但加 `@Deprecated` + 注释「P1 再实例化」。**建议删除**避免误导维护（P0 无宏观总量消费方）。
6. **基本面节奏协调**：P0-2 的 `updateAll` 依赖 `macroEngine`/`inflationSystem` 就绪，接线顺序须在 `updateAll` 前完成 macro 更新。

### 5.3 验收（t8 自身）
- `tick()` 无 NPE、无死循环，启动与读档正常（复用「空资产→init」兜底）。
- `macroEngine.step`、`cycleEngine.tick`、`fundEngine.step`、`sentiment.updateGlobalSentiment` 被实际调用（可通过打印/日志或断点确认）。
- `MacroEconomy` 被删除或明确降级标注。

---

## 6. P0-7 可播种 PRNG（t7，对应 G-11 辅助 + 可复现性）

### 6.1 改动文件
- `PriceDiscoveryEngine.java`、`MultiAgentManager.java`、`MacroEnvironment.java`、`AICompanyData.java`、`OrderBook.java`、`MarketSentiment.java`、`MarketEngine.java`、`MarketAttachment.java`

### 6.2 需求
1. **新增轻量 `SeedableRandom`**（建议 `src/main/java/com/example/financialmarket/engine/SeedableRandom.java`，基于 SplitMix64，约几十行），提供 `nextDouble()/nextGaussian()/nextInt()/nextBoolean()`。
2. **世界种子派生引擎种子**：`MarketAttachment.getOrCreateEngine`（`:73` 当前 `new MarketEngine(System.currentTimeMillis())`）改为 `engineSeed = mix64(level.getSeed() ^ SALT)`，传入 `new MarketEngine(engineSeed)`。
3. **MarketEngine 维护共享可播种 PRNG 池**：用 `seed = mix64(engineSeed + 模块/资产 id)` 为每个资产与模块派生**子流**，沿构造链传递给各引擎：
   - `PriceDiscoveryEngine`（`step` 的 epsilon、历史 K 线、GARCH 参数）
   - `MultiAgentManager`（agent 初始化、决策随机）
   - `MacroEnvironment`（OU 过程、跳变）
   - `AICompanyData`（`Math.random()` → 子流）
   - `OrderBook`（滑点、撮合，若有随机）
   - `MarketSentiment`（噪声）
   - 替换所有 `ThreadLocalRandom`（`PriceDiscoveryEngine.java:128,135,188`）与裸 `new Random()`（`MultiAgentManager.java:76`、`MacroEnvironment.java:17`、`MarketSentiment.java:10`、`MarketEngine.java` 各 `Math.random()`）。
4. **持久化 PRNG 状态**：GARCH/GBM 状态已含（见 §2.4）；如需读档后行情连续可继续复现，`PriceDiscoveryEngine.save/load` 存各资产子流当前位置。P0 最小集：至少 `engineSeed` 固定 + 子流派生确定，使「同种子同行情」。
5. **`AICompanyManager` 已用 `new Random(seed)`**（`AICompanyManager.java:12`）——保留但确保 seed 来自引擎种子派生（而非 0 或时间）。

### 6.3 验收（t7 自身）
- 同一 `engineSeed` 构造两个 MarketEngine，跑固定 N tick，成交价序列逐元素一致。
- 不同 `engineSeed` 结果显著不同。
- 各处随机已无 `ThreadLocalRandom` 与裸 `new Random()`（grep 校验）。

---

## 7. 参数包与调参约束（behavior-consultant 建议）

集中为 `MarketEngine` 内常量或独立 `EconParams` 常量类：

```
garch.alpha        ∈ (0.05, 0.15)  建议初值 0.10（α+β<1 硬约束）
garch.beta         ∈ (0.80, 0.93)  建议初值 0.85
anchor.betaGbm     = 0.3
anchor.betaMacro   = 0.3
anchor.betaSent    = 0.2
anchor.maxOffset   = 0.5  (anchor∈[fairValue*0.5, fairValue*1.5])
order.aiExpireTicks  = 100  (5 次 AI 步骤)
fundamental.quarter = 24000*30*3  (一季，可调短以加速测试)
sentiment.updateEvery = 20  tick
```

- **收敛性依赖** `β_gbm/β_macro/β_sent` 组合：P0 落地后先离线调参再定默认值（t9 用 StylizedFacts 面板监控发散）。
- 若价格振荡，优先降 `β_macro`/`β_sent`，并检查 `anchor` 钳制是否生效。

---

## 8. 对 t9（StylizedFacts 基线 + 整体构建验证）的需求

1. **新增 `engine/validation/StylizedFactsValidator.java`**：对每资产收盘价 `r_t = ln(P_t/P_{t-1})` 计算（复用 `PriceDiscoveryEngine.getRecentCloses`）：
   - 峰度 `κ`（目标 P0 后 > 4，波动聚集）
   - `|r_t|`/`r_t²` 的 Ljung–Box 自相关（前 20 阶）
   - `r_t` 自相关 ≈ 0 与 `|r_t|` 自相关为正
   - 收益-波动相关（杠杆效应，P0 可仅记录）
   - 记录 P0 前（关闭接线）与 P0 后（打开）两套基线指标，供 P1/P2 对比。
2. **构建验证**：`./gradlew compileJava` 通过；`./gradlew build` 出包。
3. **整体冒烟**：`./gradlew runServer`（或 runClient）启动，确认无 NPE、tick 推进、A0-3/A0-4/A0-5/A0-6 指标达标。
4. **可复现脚本**：用固定 `engineSeed` 一键重跑 N tick，输出成交价序列哈希，两次应一致。

---

## 9. 实施顺序与依赖（供 scheduler）

```
t2 (P0-1 GARCH) ──┐
t4 (P0-2/3/4 基本面+量纲) ──┼─→ t7 (P0-7 PRNG) ──→ t8 (P0-6 死码接线) ──→ t9 (验证) ──→ t10 (评审)
t5 (P0-4 混合锚) ─┘   (t5 依赖 t4 的 fairValue 锚)
t6 (P0-5 撤单) ── 独立，仅依赖 t1 规格
```
- 依赖方已由队长在 DAG 中体现（t2/t4/t6 依赖 t1；t5 依赖 t4；t7 依赖 t2/t4/t5；t8 依赖 t4/t7；t9 依赖 t2/t4/t5/t6/t7/t8）。

---

## 10. 网络/UI/持久化兼容性（P0 全部满足）

- **网络包**：P0 不新增/删除/改字段任何 packet（`OrderBookSnapshotPacket`/`CompanyDataResponsePacket`/`StockDataResponsePacket` 结构不变，仅数值不再恒定）→ 前端零改动。
- **UI/MCEF/Web**：K 线字段名与类型不变。
- **H2 持久化**：`ai_company` 表（`saveRelationsToH2`，`:547-563`）增基本面列；`loadFromH2` 读到旧 blob/旧表缺失字段用默认值重建并触发重初始化（复用 `MarketAttachment` 兜底）。`engine_kv` 的 `engine_main` blob 增 fairValue/PRNG/GARCH 状态。
