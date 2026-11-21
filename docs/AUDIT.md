# Financial Market 模组代码库与构建状态审计报告（AUDIT）

- 审计人：researcher
- 项目路径：`F:\financialmarket`
- 目标平台：Minecraft **1.21.1** / **NeoForge 21.1.119**（Java 21）
- 审计日期：基于当前工作树（含未提交改动）
- 审计范围：`src/**`、`build.gradle`、`gradle.properties`、`settings.gradle`、`error.txt`、`crash.log`、`deps.txt`

---

## 1. 执行摘要（Executive Summary）

1. **代码规模**：约 **150+ 个 Java 源文件**，覆盖股市、银行、央行、大宗商品、宏观周期、危机、融资融券、量化、公司、金融战争等完整经济模拟子系统，代码量庞大、功能面广。
2. **编译状态**：**当前工作树 `compileJava` 编译通过（BUILD SUCCESSFUL，2026-08-14 复核）**，ASM 依赖图已统一到 **9.7**。
3. **历史构建阻塞（ASM 9.5/9.7 冲突）**：`error.txt` 明确记录了 `Module named org.objectweb.asm.tree.analysis was already on the JVMs module path ... asm-analysis-9.7.jar but class-path contains it at location ... asm-analysis-9.5.jar`。该冲突已在 `build.gradle` 通过 `force 9.7` + neoforge 排除 + 显式 ASM 依赖解决，`deps.txt` 已确认全部解析到 9.7。
4. **运行时阻塞**：`crash.log`（2026-08-03，已过期）记录的 `BootstrapLauncher ... NoSuchElementException: No value present`（启动器在 module layer 找不到 `Consumer` 启动服务）已在后续修复中消失——`runs/client/logs/latest.log`（2026-08-13）显示**游戏已成功完整启动并正常退出**。
5. **两个历史运行时崩溃已在当前代码中修复**（详见 §6）：
   - `crash-2026-08-11`：`Invalid null NBT value with key fund`（`MarketEngine.save` 空值写 NBT）——已加空值保护。
   - `crash-2026-08-12`：`Cannot read field "drift" because "profile" is null`（`PriceDiscoveryEngine.step`）——已重构为 `GbmState` 驱动。
6. **仍存在的主要问题**（资源/健壮性层）：物品纹理路径放错导致缺纹理告警、部分语言文件缺失、遗留测试/备份文件、若干死代码、Web 依赖外部 CDN（ECharts）。

> 结论：项目已达到**可编译、可启动**的基线，但**“可运行基线”尚未在本会话中被端到端重新验证到进入游戏主菜单**（仅验证 `compileJava` 通过 + 依据 08-13 历史日志确认曾成功启动）。后续建议先做一次完整 `runClient` 冒烟验证，再迭代扩展。

---

## 2. 技术栈与构建配置

| 项 | 值 |
|---|---|
| MC 版本 | 1.21.1 |
| NeoForge | `net.neoforged:neoforge:21.1.119`（userdev 插件 `7.0.145`） |
| Java | Toolchain 21（`gradle.properties` 指定 `F:/lesture/jdk-21.0.7`） |
| 构建工具 | Gradle 8.9（wrapper） |
| 外部依赖 | `mcef-neoforge-2.1.6-1.21.1.jar`（本地 `libs/`，`files()` 引入）、`com.google.code.gson:gson:2.10.1` |
| ASM 处理 | `configurations.all { resolutionStrategy.force('org.ow2.asm:*:9.7') }` + neoforge 排除 asm + 显式 `implementation 'org.ow2.asm:*:9.7'` |
| Web 前端 | 内嵌 HTTP 服务（`com.sun.net.httpserver`，端口 **8080**）+ MCEF 内嵌浏览器加载 `http://localhost:8080` + ECharts 5.4.3（jsdelivr CDN） |
| 存档存储 | NeoForge Attachments + NBT（`CompoundTag`）持久化到 world 数据 |

### 关键构建文件要点
- `build.gradle`：定义 `client`/`server`/`data` 运行配置；repositories 含 mavenCentral、curseMaven、flatDir(`libs`)；jar 清单设置 Specification/Implementation 元数据。
- `gradle.properties`：`mod_id=financialmarket`、`neo_version=21.1.119`、`minecraft_version=1.21.1`。
- `settings.gradle`：仅 `pluginManagement`（mavenLocal、gradlePluginPortal、neoforged releases）+ rootProject。

---

## 3. 特性清单（Feature Inventory）

### 3.1 物品 / 交互入口
- `financial_tablet`（财务平板）→ 打开 **`FinancialMCEFScreen`**（MCEF 浏览器加载 `http://localhost:8080`，即内嵌 Web 前端）。
- `bank_tablet`（银行平板）→ 打开 **`BankTabletScreen`**（原生 GuiGraphics 界面）。
- `government_tablet`（政府平板）→ 打开 **`GovernmentTabletScreen`**。
- 三者均加入 `TOOLS_AND_UTILITIES` 创造标签。

### 3.2 核心经济引擎（`engine/`）
- **市场引擎 `MarketEngine`**：总调度。每日 tick、订单撮合、T+1 结算、涨跌停/熔断、盘口推送、持仓同步、AI 公司注册、资产初始化、save/load。
- **价格发现 `PriceDiscoveryEngine`**：GBM（几何布朗运动）价格路径 + 宏观驱动漂移/波动率、K 线聚合（TICK/QUARTER_DAY/HALF_DAY/DAILY/MONTHLY）、技术指标（MA5/10/20/60、MACD、RSI、KDJ）、订单流、K线缓存。
- **盘口 `OrderBook` / `Order` / `Trade`**：限价单、滑点撮合（`SLIPPAGE_FACTOR=0.002`）、T+1 清算（`SettlementSystem`）、手续费（`COMMISSION_RATE=0.001`）。
- **市场状态 `MarketState`**：资产快照、风险偏好、信用脉冲、通胀压力、指数值。
- **市场情绪 `MarketSentiment`**：买卖比、基本面分、全局情绪、政策预期。
- **宏观环境 `MacroEnvironment`**：利率 `r`、政策不确定性 `p`、恐慌指数 `v`（VIX）、动态漂移/波动率/流动性乘数、涨跌停阈值、熔断判定。
- **宏观经济 `MacroEconomy` / `MacroStateEngine`**：政策利率、准备金率、基础货币、货币供给、经济健康度、通胀、政府债务、GDP、失业率、利率。
- **经济周期 `EconomicCycleEngine` / `EconomicCycle` / `MacroStateEngine`**：周期相位。
- **基本面 `FundamentalEngine`**：EPS/PE/收入增长/ROE/股息等（配合 `MarketDataCollector`、`TechnicalIndicatorCalculator`）。
- **指数 `Excellent24Index`**：成分股指数（24 指数）、定期再平衡。

### 3.3 银行 / 央行 / 政府（`engine/banking/`）
- `BankManager`/`Bank`：储备金、库藏现金、存款、贷款、权益、基准/存款利率、投资组合、银行间市场。
- `CentralBank`：基础货币、贴现率、准备金率、买卖国债、紧急贷款、准备金调节、逆回购（`ReverseRepoOperation`）、结算回购。
- `InterbankMarket`：银行间拆借。
- `Government`：发债（`GovernmentBond`）、偿还、支出、征税、财政立场、国债、破产判定。
- `InflationSystem`：通胀系统。
- `CreditSystem`：私人/公共债务、不良率（NPL）、信用增长。
- `CurrencySystem`：货币存取（以物品计）。

### 3.4 公司 / 上市（`company/`、`engine/ai/`、`command/`）
- `CompanyManager`/`CompanyData`：公司创建、持股人、等级（`CompanyLevel` 1-5）、状态（`CompanyStatus`）、净资产、近期利润、总股本、IPO 价、绑定容器坐标、上市/成立时间。
- `AssetScanner`、`ListingValidator`、`DelistingMonitor`：资产扫描、上市校验、退市监控。
- `AICompanyManager`/`AICompanyData`：AI 公司（种子生成）、注册到市场。
- 命令：`/fmarket company create/level/...`（见 `command/CompanyCommand.java` 与 `company/CompanyCommand.java`，两个同名类）。

### 3.5 大宗商品（`engine/CommodityMarket`、`engine/commodity`）
- `CommodityMarket`/`CommodityEntry`：按 `Item` 注册商品（基础价 + 行业）、买卖、tick 价格演化。
- `CommodityRegistry`：`autoRegister` + 从 `data/financialmarket/commodity_prices.json` 加载（铁锭、金锭、铜锭、钻石、绿宝石等）。

### 3.6 金融衍生 / 产品 / 量化
- **融资融券**（`engine/margin/`）：`MarginAccount`、`MarginManager`、`ShortPosition`。
- **金融产品**（`engine/products/`）：`FinancialProduct`（SAVINGS/FUND/BOND/INSURANCE/TRUST/STOCK）、`SavingsProduct`、`FundProduct`、`InsuranceProduct`。
- **量化**（`engine/quant/`）：`QuantAPI`、`QuantScriptEngine`、`Strategy`。
- **证券/研究**（`engine/securities/`）：`SecuritiesCompany`、`SecuritiesManager`（自营交易）、`ResearchReport`。
- **回购**（`engine/buyback/`）：`StockBuyback`。
- **金融战争**（`engine/war/`）：`FinancialWarSystem`（做空/攻击目标）。
- **危机**（`engine/crisis/`）：`CrisisEngine`（危机类型、危机水平）、`EconomicCycle`。
- **熔断**（`engine/circuit/`）：`CircuitBreaker`。

### 3.7 多智能体（`engine/agent/`）
- `MultiAgentManager`：为每个资产初始化 20-40 个代理，更新公司数据，宏观驱动下的模拟交易对手盘。

### 3.8 数据持久化 / 存储 / 网络
- `attachment/`：`MarketAttachment`（按 Level 挂接引擎）、`PlayerEconomy`（现金/持仓/自选/保证金账户）、`Holding`。
- `storage/KlineFileStorage`：K 线文件存储。
- `network/ModMessages` + `network/packets/*`（**约 40 个包**）：客户端→服务端（下单、请求行情/K线/市场列表/持仓/商品价、商品买卖、公司操作、存取款、贷款、量化和解、保证金、政府操作、金融战争、开银行、买国债）+ 服务端→客户端（行情/K线、持仓同步、市场列表、商品价同步、公司数据、盘口快照、保证金/贷款/银行/政府数据）。

### 3.9 Web / UI
- `web/LocalHttpServer`（端口 8080）：静态 HTML + `/api/stocks`、`/api/index`、`/api/kline` 等 JSON API。
- `web/LocalWebServerManager`、`web/ClientMarketDataCache`：客户端缓存（K线、股票列表、深度）。
- `resources/assets/financialmarket/html/index.html`（657 行）：ECharts 看盘界面，标签页：**股票、商品、持仓、公司、借贷、量化、云图**；含分时/K线/副图、盘口（买五卖五）、基本面（EPS/PE/ROE/股息）、周期切换等。
- 屏幕：`FinancialMCEFScreen`（MCEF）、`FinancialTabletScreen`（80KB 旧版原生屏，仍被网络包 handler 引用）、`BankTabletScreen`、`GovernmentTabletScreen`。

---

## 4. 架构与关键类职责地图

```
FinancialMarket (入口 @Mod)
  └─ 构造：ItemInit.ITEMS.register + AttachmentInit.register
  └─ Events (内部类，EventBusSubscriber)
       ├─ onServerStarted     → LocalHttpServer.start()（端口 8080）
       ├─ onPlayerLoggedIn    → 创建/获取引擎、推送 MarketList/Portfolio/StockData
       ├─ onLevelTick(Post)   → MarketAttachment.getEngine(level).tick(level)
       ├─ onRegisterCommands  → CompanyCommand.register
       └─ onServerStopping    → LocalHttpServer.stop() + MarketAttachment.saveEngine

MarketEngine (每个 ServerLevel 一个，经 MarketAttachment 挂载)
  ├─ PriceDiscoveryEngine   ── 价格路径(GBM)+K线+技术指标
  ├─ MacroEnvironment / MacroStateEngine / EconomicCycleEngine ── 宏观驱动
  ├─ MarketState / MarketSentiment / FundamentalEngine / MarketDataCollector
  ├─ AICompanyManager / MultiAgentManager ── 供给端与对手盘
  ├─ CompanyManager / Excellent24Index
  ├─ CommodityMarket / CommodityRegistry ── 商品
  ├─ BankManager / Bank / CentralBank / Government / InterbankMarket
  │    ├─ InflationSystem / CreditSystem / Loan
  ├─ SecuritiesManager / FinancialWarSystem / CrisisEngine / CircuitBreaker
  ├─ OrderBook / Trade / SettlementSystem ── 撮合与清算
  └─ save()/load() → 全链路 NBT 持久化

数据流（玩家侧）：
  玩家 use 物品 → setScreen(FinancialMCEFScreen/MCEF → http://localhost:8080)
  或 setScreen(Bank/GovernmentTabletScreen) → 发送 Request*Packet（Client→Server）
  → Server 端 Packet.handle → 更新引擎 → 回发 Response/Sync*Packet（Server→Client）
  → ClientMarketDataCache + 屏幕 update*() 刷新 UI
```

### 关键依赖方向
- `MarketEngine` 是所有子系统的**组合根**（Composition Root），聚合 20+ 子系统。
- 网络包 `*Packet::handle` 直接访问 `MarketAttachment.getEngine(level)` 获取引擎并执行动作。
- Web API（`LocalHttpServer`）同样经由 `MarketAttachment` / `ServerLifecycleHooks` 读取引擎状态。
- 持久化：`MarketEngine.save/load` 串起 priceEngine、macroEngine、fundEngine、state、companyManager、aiCompanies、excellent24。

---

## 5. 依赖关系（Dependencies）

### 运行时依赖
1. **NeoForge 21.1.119**（本体，含 modlauncher、securejarhandler、bus、nashorn 等）。
2. **MCEF** `libs/mcef-neoforge-2.1.6-1.21.1.jar`（本地 `files()`）：内嵌 Chromium 浏览器，用于财务平板 Web 界面。**未打包进 JarJar**，仅 dev 环境 classpath；发布需考虑分发方式。
3. **Gson 2.10.1**：Web API 与 JSON 序列化。
4. **ECharts 5.4.3**：Web 前端图表（**来自 jsdelivr CDN，离线/无外网环境会缺失**）。
5. **ASM 9.7 全家桶**：因 neoforge 依赖 + 显式声明 + force 统一（见 §6.1）。

### 运行时依赖解析验证（`deps.txt`，2026-07-30）
- `runtimeClasspath` 中 **所有 `org.ow2.asm:*` 均为 9.7**（`asm-analysis` 9.7 → asm-tree 9.7 → asm 9.7），neoforge 传递闭包内也全部 `9.7 (*)`。
- 依赖树中无残留 9.5。
- `dependencies` 任务返回 `BUILD SUCCESSFUL`。

> 说明：`deps.txt`（07-30）与 `error.txt`（08-03）时间不同，二者反映的是 ASM 修复前后两个状态；当前 `compileJava` 复核（08-14）确认依赖解析与编译均正常。

---

## 6. 编译 / 运行错误清单与根因分析

### 6.1 【已解决】ASM 9.5/9.7 类路径冲突（原始构建阻塞）
- **证据**（`error.txt` 第 122-124 行）：
  ```
  Exception in thread "main" java.lang.IllegalStateException: Module named
  org.objectweb.asm.tree.analysis was already on the JVMs module path loaded from
  ...asm-analysis-9.7.jar but class-path contains it at location ...asm-analysis-9.5.jar
  ```
- **根因**：Java 9+ 模块系统禁止同一模块同时出现在**模块路径（module path，`-p`）**和**类路径（classpath，`-cp`）**。本项目中 `asm-analysis` 在启动器模块路径上被解析为 9.7，而 classpath（`writeMinecraftClasspathClient` 生成的 legacy classpath）中仍混入了 **9.5**（来自 neoforge 或其它依赖的传递闭包），导致 JVM 拒绝启动。
- **处置（已存在）**：`build.gradle` 做了三件事：① `configurations.all { force 'org.ow2.asm:*:9.7' }`；② 在 `implementation('net.neoforged:neoforge:21.1.119')` 中 `exclude org.ow2.asm` 五个模块；③ 显式 `implementation 'org.ow2.asm:*:9.7'`。
- **当前状态**：`deps.txt` 确认运行时全 9.7；`compileJava` 复核通过。
- **注意**：`force` + 显式 ASM 依赖是已知的“脆弱”方案（见 [NeoForge issue #1540](https://github.com/neoforged/NeoForge/issues/1540)），它把 ASM 同时塞进模块路径与 classpath，虽然当前因版本一致而不报错，但**不是推荐的 MDK 做法**，后续重构应移除显式 ASM 依赖、只保留 neoforge 原生命令，从根本上消除模块路径/类路径重复。

### 6.2 【已过期】启动器 `BootstrapLauncher ... No value present`
- **证据**（`crash.log`，2026-08-03）：
  ```
  Exception in thread "main" java.util.NoSuchElementException: No value present
    at cpw.mods.bootstraplauncher.BootstrapLauncher.run(BootstrapLauncher.java:210)
  ```
- **根因（字节码确认）**：bootstraplauncher 2.0.2 源码第 210 行对应
  `ServiceLoader.load(gameLayer, Consumer.class).stream().findFirst().orElseThrow()` —— 即在构建游戏 ModuleLayer 后，`ServiceLoader` **找不到任何实现 `Consumer` 的启动服务提供者**（游戏启动目标）。
- **结论**：该错误早于 08-13 的修复；`runs/client/logs/latest.log`（2026-08-13 21:22）显示游戏**已成功启动并正常退出**（完整会话、存盘、`Stopping!`、`JCEF is still running, killing to avoid lingering processes`）。即启动阻塞已消失。

### 6.3 【已在当前代码修复】运行时崩溃①：空值写 NBT（保存崩溃）
- **证据**（`runs/client/crash-reports/crash-2026-08-11_00.09.44-server.txt`）：
  ```
  java.lang.IllegalArgumentException: Invalid null NBT value with key fund
    at ...MarketEngine.save(MarketEngine.java:261)
    at ...MarketAttachment.saveEngine(MarketAttachment.java:50)
    at ...FinancialMarket$Events.onServerStopping(FinancialMarket.java:135)
  ```
- **根因**：`MarketEngine.save()` 对 `fundEngine.save()` 的返回结果直接 `tag.put("fund", ...)`，若某子系统返回 `null` 则抛异常（服务器停服/存档时崩溃）。
- **当前状态**：**已修复**。现行 `MarketEngine.save()`（第 362-368 行）对 `price/macro/fund/state/companies` 全部采用 `x != null ? x : new CompoundTag()` 空值保护。

### 6.4 【已在当前代码修复】运行时崩溃②：`profile` 空指针（tick 崩溃）
- **证据**（`runs/client/crash-reports/crash-2026-08-12_15.17.28-server.txt`）：
  ```
  java.lang.NullPointerException: Cannot read field "drift" because "profile" is null
    at ...PriceDiscoveryEngine.step(PriceDiscoveryEngine.java:174)
    at ...MarketEngine.tick(MarketEngine.java:183)
    at ...FinancialMarket$Events.onLevelTick(FinancialMarket.java:127)
  ```
- **根因**：旧版 `PriceDiscoveryEngine.step()` 在遍历资产时解引用可能为空的 `AssetProfile profile`（读取 `profile.drift`）。
- **当前状态**：**已修复**。现行 `step()`（第 163-196 行）已重构为遍历 `gbmStates` 的 `GbmState`（`gbm == null continue` 防御），不再读取可空 `profile`；文件中 `profile` 仅剩第 552 行 `private record AssetProfile(...)` 的类型声明（疑似遗留）。

### 6.5 【仍存在】物品纹理缺失告警（资源路径错误）
- **证据**（`latest.log`）：
  ```
  Missing textures in model financialmarket:bank_tablet#inventory / government_tablet / financial_tablet
  ```
- **根因**：`models/item/*.json` 引用 `financialmarket:item/<name>`，对应纹理应放在 `assets/financialmarket/textures/item/<name>.png`；但实际 PNG 被放在 **`assets/financialmarket/models/item/`** 目录，且 `textures/item/` 目录不存在。
- **影响**：物品渲染为紫黑块/无纹理（UI 可点但视觉缺失）。
- **修复方向**：将 4 个 `models/item/*.png`（bank/financial/government_tablet + TestWebGUIItem）移动到 `textures/item/`。

### 6.6 【仍存在】遗留 / 冗余文件与死代码
- `FinancialMarket - 副本.java`（项目根，1954 字节）：备份副本，不在 `src/` 下不会被编译，但应清理或移除（含旧版 `onServerStarted`/`getOrCreate` 逻辑）。
- `models/item/TestWebGUIItem.json/.png`：测试残留，`ItemInit` 中并无 `test_webgui_item` 注册，应删除。
- `item/FinancialTabletItem.java`：**死代码**——全工程仅自身声明引用；`ItemInit` 用匿名 Item 注册 `financial_tablet`。可删除或改用。
- `command/CompanyCommand.java` 与 `company/CompanyCommand.java` **两个同名类**，职责重叠，需厘清唯一入口。
- `FinancialTabletScreen.java`（80KB 旧版原生屏）与 `FinancialMCEFScreen.java`（MCEF）并存：网络包 handler 仍引用前者做兼容更新，但 `financial_tablet` 实际打开后者。技术债：维护两套 UI。
- `lang/en_us.json` 仅含 `financial_tablet` 一条，`bank_tablet`/`government_tablet` 等无本地化条目。

### 6.7 【风险】其它健壮性注意点
- `MarketEngine` 中大量 `System.out.println`/`System.err` 直接输出（中文 + emoji），未用 log4j 统一日志，运行时可能乱码（日志中已见 `鈿狅紵` 等编码问题）。
- 源文件存在**混合编码/CRLF-LF 混用**（git 提示 LF 将被替换为 CRLF）。
- Web 前端 `index.html` 依赖 jsdelivr CDN 的 ECharts，离线环境图表不可用。
- MCEF 本地 jar 通过 `files()` 引入，打包发布时需明确分发方式（JarJar 或 mods 目录）。

---

## 7. 当前状态结论（基线判定）

| 维度 | 状态 | 依据 |
|---|---|---|
| 依赖解析 | ✅ 正常（全 ASM 9.7） | `deps.txt`（07-30）BUILD SUCCESSFUL |
| Java 编译 | ✅ 通过 | 本会话 `./gradlew compileJava` → **BUILD SUCCESSFUL**（2026-08-14 复核） |
| ASM 冲突 | ✅ 已解决 | `error.txt` 记录的历史冲突已由 force+exclude 消除 |
| 客户端启动 | ✅ 曾成功 | `latest.log`（08-13）完整会话 + 正常退出 |
| 运行时崩溃①（NBT 空值） | ✅ 已修复 | 现行 `MarketEngine.save()` 有空值保护 |
| 运行时崩溃②（profile NPE） | ✅ 已修复 | 现行 `step()` 用 GbmState + 空判 |
| 物品纹理 | ⚠️ 缺失 | `textures/item/` 不存在，PNG 放错目录 |
| 端到端冒烟 | ⚠️ 未在本会话重跑 | 仅 compileJava + 历史日志佐证 |

> **基线结论**：项目具备“可编译 + 可启动”的基础；正式宣称为“可运行基线”前，建议由 builder 在本会话做一次 `runClient` 端到端冒烟（确认进入主菜单并正常加载 mod），并顺手修复纹理路径。

---

## 8. 后续开发 / 修复建议（按优先级排序）

### P0（阻断发布 / 必须先修）
1. **端到端冒烟验证**：`./gradlew runClient`，确认进入主菜单、mod 加载、创建世界后经济引擎正常 tick、`/fmarket` 命令可用、无崩溃。这是“恢复可运行基线”的最终判据。
2. **修复物品纹理路径**：把 `models/item/*.png` 移到 `assets/financialmarket/textures/item/`，消除 3 个物品的缺纹理告警。
3. **清理项目根备份文件**：删除 `FinancialMarket - 副本.java`（防止误用/混淆）。

### P1（健壮性与代码卫生）
4. **移除脆弱 ASM 处理**：删除 `build.gradle` 中显式 `implementation 'org.ow2.asm:*:9.7'` 与 `force`；只保留 neoforge 原生命令，从根本规避模块路径/classpath 重复（参考 [NeoForge #1540](https://github.com/neoforged/NeoForge/issues/1540)）。若 mcef 是 9.5 来源，则单独处理 mcef 的传递依赖。
5. **统一日志**：把 `System.out/err` 替换为 NeoForge log4j Logger（`LogUtils.getLogger()`/`@EventBusSubscriber` 自带 LOGGER），修复中文乱码，便于排查。
6. **统一公司命令入口**：合并 `command/CompanyCommand.java` 与 `company/CompanyCommand.java`，删除冗余。
7. **删除死代码**：`item/FinancialTabletItem.java`、`TestWebGUIItem` 模型/纹理；`FinancialTabletScreen`（80KB 旧屏）若 MCEF 已接管，评估下线以减维护成本。
8. **补全本地化**：为 `bank_tablet`/`government_tablet` 及其它物品补 `lang/en_us.json` 条目（可加 `zh_cn.json`）。

### P2（功能迭代 / 扩展）
9. **发布打包方案**：MCEF 本地 jar 的 JarJar 打包 / mods 分发路径；ECharts 改为本地资源（避免 CDN 依赖）。
10. **存档健壮性**：为 `MarketEngine.save()` 各子系统增加版本字段与容错 `try/catch`，避免单子系统异常导致整档保存失败。
11. **多智能体 / 量化 / 金融战争细化**：`QuantScriptEngine`、`EconomicCycle`、`Strategy` 等目前为骨架（部分仅 4 行），按需求扩展。
12. **Web 前端增强**：云图（`marketmap`）、商品、借贷、量化标签页的交互与数据完整打通（当前部分为占位）。
13. **代码规范**：统一编码（UTF-8）与行尾（LF），建立 checkstyle/spotless，避免 CRLF-LF 混用。

---

## 9. 附录：与审计相关的外部参考
- NeoForge ASM 模块路径/类路径冲突：<https://github.com/neoforged/NeoForge/issues/1540>
- NeoForge runClient 相关：<https://github.com/neoforged/NeoForge/issues/1664>

*（本报告基于 2026-08-14 当前工作树快照；未提交改动约 892 增 / 955 删行，集中于 PriceDiscoveryEngine、MarketEngine、MultiAgentManager、MacroEnvironment、LocalHttpServer、index.html 等，即前述崩溃修复与 UI 改版。）*
