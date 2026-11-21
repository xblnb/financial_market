# EXCHANGE_SCREEN_SPEC — 交易所屏幕 + 交易终端 + 开市/休市钟声

> 模组：`financialmarket`（NeoForge 21.1.119 / Java 21）
> 对应任务：`t1` 需求规格（architecture 角色）
> 目标串：交易所屏幕方块（可拼接组合成显示屏，三模式切换）+ 交易终端方块（金融平板方块化，MCEF 浏览器）+ 开市/休市钟声。
> 交付验收项（ES-1…ES-7）见文末映射表。

---

## 0. 边界与前提（先读）

本条规格只有「基于现有架构的增量」，**不允许**：

1. 修改已有引擎公式 / 新增字段级公式（不改 `MarketEngine`、`PriceDiscoveryEngine`、`TradingCalendar`、
   `Excellent24Index`、黄金、法币 M2 等核心语义）。指数/大盘/宏观的数据一律**只读复用**现有
   `MarketEngine` 与 `LocalHttpServer` 派生接口。
2. 引入裸 `java.util.Random` / 非确定性随机。任何需要随机的演示/装饰逻辑必须使用
   `SeedableRandom` 及其 `mix64/derive` 子流。
3. 改 `H2DatabaseManager` 表结构或引擎 blob 序列化。引擎状态仍由 `MarketAttachment`（H2 主 + NBT 镜像）管。
   方块实体自身的状态存**方块实体 NBT**（`saveAdditional/loadAdditional`），不混入引擎 blob。
4. 影响已交付的 `BankTabletScreen` / `FinancialTabletScreen` / `FinancialMCEFScreen` 行为。

关键既有资产（实现时直接引用）：

| 资产 | 位置 | 作用 |
|---|---|---|
| 方块注册先例 | `init/BlockInit.java` | `RegisterEvent` 注册方块/BE 类型；`RegisterCapabilitiesEvent` 注册能力 |
| 物品注册先例 | `init/ItemInit.java` | `DeferredRegister.Items` + `BlockItem`；CreativeModeTab 挂 `TOOLS_AND_UTILITIES` |
| 方块 + BE 先例 | `block/MarketBlock.java`、`block/MarketBlockEntity.java` | 可放置、`EntityBlock`、`getTicker`、`useWithoutItem`、BE NBT |
| 引擎挂载 | `attachment/MarketAttachment.java` | `getEngine(ServerLevel)` 取引擎（每 level 单例） |
| 交易 UI 先例 | `item/FinancialTabletItem.java` + `screen/FinancialMCEFScreen.java` | 右键打开 MCEF 浏览器（仅客户端，`@OnlyIn(CLIENT)` + `isClientSide`） |
| HTTP 数据源 | `web/LocalHttpServer.java` | `/api/index`、`/api/stocks`、`/api/macro`、`/api/companies` |
| 网络包先例 | `network/ModMessages.java` | `RegisterPayloadHandlersEvent` / `PayloadRegistrar` |
| 日历/时间权威 | `engine/calendar/TradingCalendar.java` | `getTick()`、`isMarketOpen()`、`isSessionEnd()`、`isTradingDayStart()`、`nextOpenTick()` |
| 可播种 PRNG | `engine/random/SeedableRandom.java` | `mix64(long)`、`derive(String)`、`nextLong/nextDouble/nextInt` |
| 引擎索引值来源 | `engine/MarketEngine.java` internal `excellent24Index` | 唯一真实指数值；**现有 `MarketState.getIndexValue()` 为 null 桩**（见 §4.1 关键事实） |

---

## 1. 总体设计决策（含取舍）

### 1.1 两个方块采用两种截然不同的交互/渲染形态

| 方块 | 交互 | 渲染 | 数据 |
|---|---|---|---|
| **交易所屏幕** `exchange_screen` | 右键循环切换模式 | **方块实体渲染（BlockEntityRenderer，BER）** | 服务端权威快照经 BE 同步到客户端 |
| **交易终端** `trading_terminal` | 右键打开 MCEF 浏览器 | **MCEF 浏览器（复用现有 `FinancialMCEFScreen`）** | 复用 `LocalHttpServer` 全套交易 UI |

这符合需求表述：交易终端「使用方式与金融平板一致，基于 MCEF 浏览器」；交易所屏幕强调「可拼接组合成显示屏」。

### 1.2 交易所屏幕为何用 BER 而非「每方块一个 MCEF 浏览器」（核心取舍）

需求原话是「可拼接组合成显示屏」。两个候选：

**候选 A：MCEF 浏览器方块化（每块一个 browser，渲染世界面）**
- 优点：可直接复用现有 web UI（图表、盘口、云图），视觉丰富。
- 缺点：
  1. 每个浏览器实例都是独立 CEF 进程/上下文，**多个方块 = 多个浏览器实例**，内存/CPU 开销随方块数线性膨胀；
  2. MCEF 的 browser 是**全屏矩形**，很难在世界面上沿多个方块无缝切分为一段连续画面（拼接要靠每块独立缩放/裁剪，几何错位风险高）；
  3. 世界内浏览器生命周期/失焦/卸载（chunk 卸载）管理复杂，易泄漏；
  4. 与「拼接成一块屏」的语义天然冲突。
- 结论：**不用于交易所屏幕实体渲染**。

**候选 B：方块实体渲染（BER）+ 逻辑分屏**
- 优点：
  1. **天然契合拼接**：每块只画「同一份逻辑内容」的对应子矩形，几何无缝；
  2. 轻量、确定性、无 CEF 开销；
  3. 颜色语义（红涨绿跌）完全可控；
  4. 数据源为服务端权威快照，客户端不依赖引擎、不增加网络随机性。
- 缺点：内容是文本/表格级（指数、涨跌幅、板块），**不做**复杂交互图表（交易图表交给终端/平板）。
- 结论：**采用**。交易所屏幕定位为「展示屏」而非「可交互交易屏」。

> **明确分工**：需要交互图表/下单 → 交易终端方块或金融平板（MCEF）；需要拼接展示 → 交易所屏幕方块（BER）。

---

## 2. 方块注册（对照 `BlockInit` 先例）

新增两个方块（放 `block/` 包）+ 两个 BE（放 `block/` 包），在 `BlockInit` 内追加注册，
item 在 `ItemInit` 内追加 `BlockItem`。沿用其 `@EventBusSubscriber(Bus.MOD)` 静态注册方式，
无需改动 `FinancialMarket` 构造接线。

### 2.1 ID 常量（追加到 `BlockInit`）

```java
public static final ResourceLocation EXCHANGE_SCREEN_ID   = ResourceLocation.fromNamespaceAndPath(MODID, "exchange_screen");
public static final ResourceLocation TRADING_TERMINAL_ID  = ResourceLocation.fromNamespaceAndPath(MODID, "trading_terminal");
public static final ResourceLocation EXCHANGE_SCREEN_BE_ID = ResourceLocation.fromNamespaceAndPath(MODID, "exchange_screen_entity");
public static final ResourceLocation TRADING_TERMINAL_BE_ID= ResourceLocation.fromNamespaceAndPath(MODID, "trading_terminal_entity");

public static Block EXCHANGE_SCREEN;
public static Block TRADING_TERMINAL;
public static BlockEntityType<ExchangeScreenBlockEntity> EXCHANGE_SCREEN_BE_TYPE;
public static BlockEntityType<TradingTerminalBlockEntity> TRADING_TERMINAL_BE_TYPE;
```

### 2.2 方块属性

- `exchange_screen`：`BlockBehaviour.Properties.of().strength(2.0f, 6.0f)`（与市场方块一致）。
  - **使用水平朝向状态**（`HorizontalDirectionalBlock` 风格 `FACING`，或 `HorizontalDirectionalFacingBlock`），
    使方块可「贴墙」放置且拼接时朝向一致。需注册 `blockstate`（`assets/financialmarket/blockstates/exchange_screen.json`）。
- `trading_terminal`：同 `strength(2.0f,6.0f)`，同样带 `FACING`。

> 注：现有 `MarketBlock`/`DeliveryBlock` 无 `FACING`（实体类直接继承 `Block`），
> 交易所屏幕/终端**必须**带朝向以便多块拼接对齐与贴墙。方块类与 BE 类型注册照抄 `BlockInit` 模式。

### 2.3 ItemInit 追加

```java
public static final DeferredItem<Item> EXCHANGE_SCREEN_ITEM   = ITEMS.register("exchange_screen",
        () -> new BlockItem(BlockInit.EXCHANGE_SCREEN, new Item.Properties()));
public static final DeferredItem<Item> TRADING_TERMINAL_ITEM  = ITEMS.register("trading_terminal",
        () -> new BlockItem(BlockInit.TRADING_TERMINAL, new Item.Properties()));
```
并加入 `buildCreativeTabContents` 的 `TOOLS_AND_UTILITIES`。

### 2.4 模型/贴图/语言

- `models/block/exchange_screen.json`、`models/block/trading_terminal.json`
- `models/item/exchange_screen.json`、`models/item/trading_terminal.json`（`block/...` parent）
- `blockstates/exchange_screen.json`、`blockstates/trading_terminal.json`（含 `facing`）
- `textures/block/exchange_screen.png`、`textures/block/trading_terminal.png`
- `lang/en_us.json` 追加两处翻译。

---

## 3. 数据接入（只读复用 `MarketEngine` / `LocalHttpServer`，ES-2）

### 3.1 服务端快照（交易所屏幕数据源）

交易所屏幕**不能**在客户端直接聊引擎（客户端无 `MarketEngine`）。由单例
`ExchangeScreenDataService`（新增，服务端侧，纯只读）每 `N` tick 从引擎抓取一份**紧凑快照**，供
`ExchangeScreenBlockEntity` 拉取并同步到客户端。快照是**纯函数派生**，无随机。

快照结构（`ExchangeScreenSnapshot`，POJO）：

```java
public record ExchangeScreenSnapshot(
    int mode,                 // 0=核心指数 1=大盘行情 2=市场总览（见 §5.1）
    boolean marketOpen,       // engine.getCalendar().isMarketOpen()
    long tick,                // engine.getTickCounter()
    double indexValue,        // ★ 见 §4.1：以 excellent24Index.getIndexValue() 为真源
    double indexChangePoint,  // 相对上一采样点滚动增量
    double indexChangePct,    // (cur-prev)/prev*100（同 /api/index change 语义）
    List<BoardRow> board,     // 大盘行情：TopN 行（代码/现价/涨跌幅）
    double upCount, double downCount, double flatCount,   // 市场总览：涨跌家数
    double totalVolume, double totalAmount,               // 市场总览：总成交量/额
    double advDeclineRatio                                  // 涨跌比
) {}
```

- `BoardRow record(String id, String name, double price, double changePct)`（涨跌幅 sign 决定颜色）。
- 抓取节流：默认每 `10` tick 一采样（常量 `SNAPSHOT_INTERVAL_TICKS`），tickCounter 门控，确定、可复现
  （同 tick 数 → 同快照）。参考 `MarketBlockEntity.tick` 的 `level instanceof ServerLevel sl` + `MarketAttachment.getEngine(sl)` 取引擎模式。
- **确定性约束**：`indexChangePoint/indexChangePct` 采用「相对上一采样点」滚动增量（对齐 `/api/index`
  现有实现 `lastIndexValue`），**不使用**日历跨日 prevClose 之外的新派生，避免引入新公式。

### 3.2 客户端拿到快照的途径（BER 数据）

- **服务端 BE**（`ExchangeScreenBlockEntity`）：缓存 `latestSnapshot`，每次快照变化 `setChanged()` 并触发一次
  chunk/BE 同步（`getUpdateTag()` 返回紧凑 NBT 或经一简单 `payLoad`）。节流到 `SNAPSHOT_INTERVAL_TICKS`，
  避免每 tick 全量广播。
- **客户端 BE**：`loadAdditional` 从更新标签还原 `latestSnapshot`，BER 直接读取绘制。

> 也可选择让客户端BER周期性 `fetch` `LocalHttpServer`（同源、复用现有 web 数据），作为**可选降级/兜底**；
> 但主推「服务端快照经BE同步」以避免请求时延与双数据源不一致。实现二选一并注明，默认为 BE 同步。

---

## 4. 关键事实与必须处理的数据缺口（ES-2 前置）

### 4.1 `MarketState.getIndexValue()` 是 null 桩

`engine/MarketState.java#getIndexValue()` 返回 `null`，`MarketState#setIndexValue(double)` 为空实现。
真实指数值是 `MarketEngine.excellent24Index.getIndexValue()`（由 `rebalance`/`updateValue` 维护）。

- **现有 `/api/index` 的问题**：`LocalHttpServer.getIndexJson()` 用 `eng.getState().getIndexValue()`（会得到
  `null`→`value=0`），即当前 web UI 显示的指数恒为 0——这是既有缺陷，不是本功能引入。
- **本规格要求**：交易所屏幕「核心指数」模式的 `indexValue/indexChangePct` 必须以
  `engine.excellent24Index.getIndexValue()` 为真源（通过新增**只读 getter** 或本模块快照服务读取）。
  为不改引擎公式，允许在 `MarketEngine` 增加一个**只读访问器**（例如 `public double getIndexValueEx()`，
  仅返回 `excellent24Index.getIndexValue()`），属访问器级改动、不触碰公式；或直接让快照服务经
  反射/已有公开面读取。**推荐加只读 getter**（最小、无公式变更）。

### 4.2 交易终端屏幕复用

交易终端右键 → 打开 `FinancialMCEFScreen`（现成）。该屏已接入 `LocalHttpServer` 全套
`/api/*`（含 `requestIndex/requestMarketList/requestPortfolio/placeOrder` 等桥接），
「使用方式与金融平板一致」满足。

---

## 5. 交易所屏幕渲染与三模式（ES-3）

### 5.1 三种显示模式

| 模式值 | 名称 | 内容 | 布局 |
|---|---|---|---|
| 0 | 核心指数（Index） | 指数值 + 涨跌点数 + 涨跌幅；是否开市徽标 | 大号数值 + 涨跌色角标 |
| 1 | 大盘行情（Board） | TopN 个股（代码/名称/现价/涨跌幅）表格 | 行式表格、红涨绿跌着色 |
| 2 | 市场总览（Overview） | 总指数 + 涨/跌/平家数 + 总成交额/量 + 涨跌比 | 统计面板 |

- 数据来源：§3 快照（`mode` 字段 + 对应数据）。
- `marketOpen=false` 时显示「休市 / CLOSED」角标，指标保留最近值（**不进入**随机补点）。

### 5.2 拼接模型（ES-3 核心）

「可拼接成显示屏」= 同一面墙上、**朝向一致、正交相邻**的若干 `exchange_screen` 方块，逻辑上组成
**一块**连续显示屏。规则（确定性、无中心协调器）：

1. **分组**：以某组特征（同 `facing` + 相邻正交）定义一个显示屏组。实现上以「组内 min (x,z) 的
   `(col,row)` 基准坐标系」为参考，扫描正交相邻同方块类型，得到组宽 `W`（横向块数）与组高 `H`（纵向）。
   限定 `W,H ∈ [1,8]`（常量 `MAX_GRID_W/H`），超出按 1×1 退化为单块。
2. **分片**：各方块依据自身相对组内 min 块的 `(col,row)` 计算逻辑子矩形。**内容是完全统一生成的**：
   每个块都持有同一份 `latestSnapshot`，并用**同一种纯函数**把快照渲染成一块「整屏」内容，
   每块只绘制属于自己的子矩形。因为内容 = `f(snapshot)`（纯函数 + 相同输入），所以所有块天然一致，
   无需块间通信或中心节点（**确定性 + 去中心化**）。
3. **画面切割**：BER 中，用 `W×H` 网格把逻辑内容划分；方块在行/列中的偏移决定它绘制哪一格。
   - 指数/统计类：只取 index/overview 对应子面板；表格类：按行切分（第 r 块只画第 r 行及属于它的条目）。
4. **漫游/识别**：BER 不要求全局唯一「主块」；任何一块被放置后，自主计算它在当前组内的几何分片。
   `W/H` 由该块运行时统计正交同类型块得出（仅基于世界方块状态，无随机）。

> 实现建议：BER 为每个 `exchange_screen` 方块绘制一个面向观察者的 `quad`（贴墙面），
> 用 `GuiGraphics`/`MultiBufferSource` 画文本与色块。分片用「该块世界的 (x,z) 相对组min位置」算
> `col=dx, row=dz`（与 `facing` 对齐）。

### 5.3 方块实体渲染（BER）接线

- 新方块实体渲染器：`client/blockentity/ExchangeScreenBlockEntityRenderer`
  （实现 `BlockEntityRenderer<ExchangeScreenBlockEntity>`）。
- 注册：用 `EntityRenderersEvent.RegisterRenderers`（MOD 总线）通过
  `event.registerBlockEntityRenderer(BlockInit.EXCHANGE_SCREEN_BE_TYPE, ExchangeScreenBlockEntityRenderer::new)`。
  该注册仅客户端（`@OnlyIn(Dist.CLIENT)`），须避免服务端加载客户端类崩溃。
- 渲染：读取客户端 BE 的 `latestSnapshot`；未收到快照时画「—— / NO DATA」占位；不阻塞主逻辑。

---

## 6. 模式切换交互（ES-3）

- 右键 `exchange_screen` → 循环切换模式 `0→1→2→0`（服务端 BE 改变 `mode` 并 `setChanged()`；客户端经同步标签更新）。
- 服务端 `useWithoutItem`：仿 `MarketBlock`，在 `ServerLevel` 下取 `ExchangeScreenBlockEntity`，`cycleMode()`，
  向玩家 `displayClientMessage` 提示当前模式名（「核心指数 / 大盘行情 / 市场总览」）。
- `mode` 持久化到 BE NBT（`mode` int），读档恢复。

---

## 7. 交易终端方块（ES-4）

- 方块 `trading_terminal` + BE `TradingTerminalBlockEntity`。
- `useWithoutItem`：
  ```
  if (level.isClientSide) { openScreen(); }   // 仅客户端打开 MCEF
  return InteractionResult.sidedSuccess(true);
  ```
  `openScreen()` 用 `@OnlyIn(Dist.CLIENT)` + try/catch，打开 `new FinancialMCEFScreen()`，
  **完全对齐 `FinancialTabletItem.use()`**（含 `RuntimeDistCleaner` 规避服务端加载客户端 Screen 崩溃）。
- 无世界面渲染（不贴图）；一个 BE 占位，主要保证服务端有实体/可破坏性。BE 仅存 `FACING`/blockstate，无业务状态。
- 打开的是现成 MCEF 屏，数据与交互零新增。

---

## 8. 开市/休市钟声（ES-5，由交易所屏幕发出）

### 8.1 触发条件与触发点

- **触发源**：`ExchangeScreenBlockEntity` 的服务端 tick（已每 tick 运行），观察
  `engine.getCalendar().isMarketOpen()` 的**跳变**：
  - `false→true`：开市钟（`exchange_open_bell`）。
  - `true→false`：休市钟（`exchange_close_bell`）。
- **去抖/单次**：BE 缓存 `lastMarketOpen`，仅在新旧不等且 `level instanceof ServerLevel` 且
  `MarketAttachment.getEngine(sl) != null` 时触发一次；触发后更新缓存。
  （避免多个显示屏方块叠加多次，也避免连 tick 重复）。
- **确定性**：触发只依赖日历跳变（tick 驱动的确定性纯函数），无随机。
- **播放位置/方式**：`ServerLevel.playSound(null, pos, soundEvent, SoundSource.BLOCKS, 0.6f, 1.0f)`
  （`pos = 方块实体 pos`，广播到附近玩家）。用 `SoundSource.BLOCKS`。
- **优先级说明**：如果多个显示屏方块各自检测会重复响 —— 通过「组内 min `(x,z)` 块才播报」去重
  （用 §5.2 的分组规则判定本块是否组基准块）。单块（独立放置）则直接播。

### 8.2 音频资源加载路径（用户提供文件）

- 音频文件目录：`src/main/resources/assets/financialmarket/sounds/`
  - `assets/financialmarket/sounds/block/exchange_open_bell.ogg`
  - `assets/financialmarket/sounds/block/exchange_close_bell.ogg`
- `assets/financialmarket/sounds.json`（**注意**：sounds.json 在 `assets/<namesp>/sounds.json`，不在 sounds 子目录内）：
  ```json
  {
    "block.exchange_open_bell":  { "subtitle": "subtitles.financialmarket.exchange_open_bell",
                                    "sounds": [ "financialmarket:block/exchange_open_bell" ] },
    "block.exchange_close_bell": { "subtitle": "subtitles.financialmarket.exchange_close_bell",
                                    "sounds": [ "financialmarket:block/exchange_close_bell" ] }
  }
  ```
- 若用户只提供一个音频文件用于开市/休市，允许复用同一 `.ogg` 注册两个 `SoundEvent`（提示用户提供第二个可区分音色）。
- **SoundEvent 注册**：新增 `init/SoundInit.java`，用 `DeferredRegister<SoundEvent> SOUNDS`
  + `SoundEvent.createVariableRangeEvent(id)`，注册：
  - `financialmarket:exchange_open_bell`
  - `financialmarket:exchange_close_bell`
  （沿用 `ItemInit/BlockInit` 的 `@EventBusSubscriber(Bus.MOD)` 自注册模式。）

> **重要**：`sounds.json` 文件名是关键字，必须位于 `assets/financialmarket/sounds.json`（不是
> `sounds/sounds.json`）。音频 `.ogg` 实际放 `assets/financialmarket/sounds/`。两处路�径勿混。

---

## 9. 可复现性（ES-6，SeedableRandom 子流 / 禁裸随机）

- 交易所屏幕/终端的数据全部为**引擎权威的确定性派生**（指数、大盘、家数、成交额），**不引入任何随机点**。
- 若有装饰性随机（如隐藏的刷屏/闪烁/故障色块），必须：
  ```
  long seed = SeedableRandom.mix64(worldSeed ^ (long)blockPos.asLong());
  SeedableRandom rng = new SeedableRandom(seed);
  rng.derive("exchange_screen_glitch");   // 独立子流
  ```
  且该随机**只影响视觉、不影响数据**，并随世界种子 + 方块坐标确定。
- 明确禁止：`new java.util.Random()`、`Math.random()`、线程/会话相关的非确定随机。
- 快照采样节流用 `tickCounter % SNAPSHOT_INTERVAL_TICKS`（同 tick → 同快照），时间权威对齐 `TradingCalendar`。

---

## 10. 持久化兼容（ES-7，H2/NBT）

- 引擎状态：**不碰**。仍由 `MarketAttachment.saveEngine`（H2 主 + NBT 镜像）管。
- 方块实体状态：存**方块实体 NBT**（`saveAdditional/loadAdditional`），不混入引擎 blob：
  - `ExchangeScreenBlockEntity`：`mode`（int）、`lastMarketOpen`（boolean，可选恢复去抖态）。
  - `TradingTerminalBlockEntity`：无需业务状态（可只存 `FACING` 由 blockstate 承担）。
- `FACING` 由 blockstate（世界方块状态）持久化，不依赖 BE NBT。
- 读档兜底：BE `loadAdditional` 缺字段用 `contains` 兜底（`mode` 缺省 0、`lastMarketOpen` 缺省跟随当前引擎状态），**读档不失败**（对齐 `TradingCalendar.load` 的兜底风格）。
- **H2 兼容性**：不新增表、不改 `H2DatabaseManager`。引擎 schema 零改动。
- **隔离**：交易所屏幕/终端是纯展示/交互入口，若 `banking/tradeBlock` 等开关关闭，屏幕仍可显示（只读），
  交易终端下单沿用 `/api/order` 现有服务端校验（A 股开市/涨跌停/T+1），不另设校验。

---

## 11. 颜色语义（红涨绿跌，明确）

- **涨（changePct > 0）→ 红**：`0xFFFF5252`（对齐 `FinancialTabletScreen.COLOR_RISE` 的 CN 色）。
- **跌（changePct < 0）→ 绿**：`0xFF00A3FF`（对齐其 `COLOR_FALL` 的 CN 色）。
  > 注：`FinancialTabletScreen` CN 色 `COLOR_RISE=0xFFFF5252`（红）、`COLOR_FALL=0xFF00A3FF`（绿）——
  > 交易所屏幕必须沿用「红涨绿跌」，不得用美股「绿涨红跌」默认。
- **平/无变化（changePct==0）→ 中性灰**：`0xFF9E9E9E`。
- 常量统一放 `ExchangeScreenColors`（如 `RISE=0xFFFF5252`, `FALL=0xFF00A3FF`, `FLAT=0xFF9E9E9E`），BER 引用。

---

## 12. 文件清单（实现增量）

**新增**
- `block/ExchangeScreenBlock.java`、`block/ExchangeScreenBlockEntity.java`
- `block/TradingTerminalBlock.java`、`block/TradingTerminalBlockEntity.java`
- `screen/data/ExchangeScreenSnapshot.java`、`screen/data/ExchangeScreenDataService.java`（服务端只读抽样）
  （包名可与现 `screen` 保持同 `screen` 或新建 `screen/data`；建议 `screen/data`，避免与 GUI `screen` 混淆）
- `client/blockentity/ExchangeScreenBlockEntityRenderer.java`
- `init/SoundInit.java`
- `assets/financialmarket/sounds/block/exchange_open_bell.ogg`、`exchange_close_bell.ogg`（用户提供）
- `assets/financialmarket/sounds.json`
- `assets/financialmarket/blockstates/{exchange_screen,trading_terminal}.json`
- `assets/financialmarket/models/block/{exchange_screen,trading_terminal}.json`
- `assets/financialmarket/models/item/{exchange_screen,trading_terminal}.json`
- `assets/financialmarket/textures/block/{exchange_screen,trading_terminal}.png`

**修改**
- `init/BlockInit.java`（注册两方块 + 两 BE 类型）
- `init/ItemInit.java`（注册两个 BlockItem + CreativeTab）
- `assets/financialmarket/lang/en_us.json`（两个名称翻译）
- `engine/MarketEngine.java`（**可选**：新增只读 `getIndexValueEx()`，只返回 `excellent24Index.getIndexValue()`，零公式变更）

---

## 13. 验收映射（ES-1…ES-7）

| 验收项 | 需求 | 判据 |
|---|---|---|
| ES-1 方块注册 | §2 | 两个方块经 `ItemInit` 可放置/创造栏可见；BE 类型在 `BlockEntityType` 注册；带 `FACING` |
| ES-2 数据接入 | §3/§4 | 快照来自 `MarketEngine`（index 以 `excellent24Index` 为真源），节流 tick 采样，无随机 |
| ES-3 三模式渲染 | §5/§6 | 三种模式内容正确渲染；拼接按 `(col,row)` 分片；红涨绿跌正确；右键切换模式并持久化 |
| ES-4 交易终端 | §7 | 右键打开现成 MCEF 屏；`@OnlyIn(CLIENT)` + `isClientSide`，服务端不崩 |
| ES-5 钟声音效 | §8 | 开市/休市跳变各播一次；音频按 §8.2 路径加载；组基准块去重 |
| ES-6 可复现 | §9 | 无裸 `Random`；装饰随机用 `SeedableRandom` 子流；同世界种子+同 tick → 同画面 |
| ES-7 持久化兼容 | §10 | 引擎 H2/NBT 零改动；方块实体状态存 BE NBT 且读档兜底不失败 |

---

## 14. 明确排外（不做）

1. 不做交易所屏幕内的**交互式图表/下单**（交给交易终端/金融平板 MCEF）。
2. 不新增任何引擎公式或 `MarketEngine` 公共行为；最多加一个只读 `getIndexValueEx()`。
3. 不改 `MarketState.getIndexValue()` 实现为真实值（避免触碰引擎核心语义）——虽有此缺陷，属既有代码，
   本功能通过「以 `excellent24Index` 为真源」规避，并在快照服务侧读取真源。
4. 不把钟声做成引擎级事件总线广播；仅在交易所屏幕方块实体上触发。
