# JSON → H2 数据库迁移方案（H2_MIGRATION）

- 设计人：researcher
- 目标：把 ① K线/元数据 JSON、② MarketEngine 主状态（当前世界附件 NBT）、③ 量化脚本（`quant_strategies/*.js`）迁移到 H2 嵌入式数据库。
- 产出物：本设计文档 `H2_MIGRATION.md`。
- 平台：Minecraft 1.21.1 / NeoForge 21.1.119 / Java 21。

---

## 1. 现状梳理（基于当前工作树代码）

### 1.1 现有持久化路径
| 数据 | 当前存储方式 | 所在代码 |
|---|---|---|
| K线/价格元数据 | `KlineFileStorage` 写 `financial_market/klines.json`、`metadata.json`（世界目录下） | `storage/KlineFileStorage.java` |
| 引擎主状态（价格/GBM/K线/成交点/日开收/AI公司/指数等） | `MarketEngine.save()→CompoundTag`，经 `MarketAttachment.saveEngine` 写入**世界级 NeoForge Attachment**（`level.setData(type, tag)`，持久化在 `level.dat`） | `engine/MarketEngine.java:357-398`、`attachment/MarketAttachment.java:58-68` |
| 量化脚本 | `FinancialTabletScreen.saveQuantFile/loadQuantFile` 读写 `quant_strategies/<name>.js`，**相对工作目录**（`Paths.get("quant_strategies", quantFileName)`） | `screen/FinancialTabletScreen.java:950-963` |
| 商品价格 | `data/financialmarket/commodity_prices.json`（classpath 静态资源，只读） | `engine/CommodityRegistry.java` |

### 1.2 关键事实（影响方案设计）
- **`KlineFileStorage` 目前是孤儿代码**：全工程仅 `KlineFileStorage.java` 自身声明引用它，没有任何调用方。真正落盘的 K线数据来自 `PriceDiscoveryEngine.save()`（`engine/MarketEngine.java` 经 Attachment 写入 `level.dat` 的 NBT `klines` 子树）。
- **K线数据模型**：`Map<assetId, Map<KlineType, LinkedList<OHLCData>>>`；`KlineType` = `{TICK, QUARTER_DAY, HALF_DAY, DAILY, MONTHLY}`；`OHLCData` = `{open, high, low, close, volume}`（见 `OHLCData.java`）。顺序由 `LinkedList` 下标决定。
- **`MarketEngine.save()` 的 NBT 顶层键**：`price`、`macro`、`fund`、`state`、`companies`、`aiCompanies`（ListTag）、`excellent24`。
- **`PriceDiscoveryEngine.save()` 子键**：`prices`、`gbm`、`klines`、`tradePoints`、`daily`。
- **量化脚本**：目前基于工作目录而非世界目录，多世界会共享/冲突；`QuantScriptEngine` 目前仅是 4 行骨架（`engine/quant/QuantScriptEngine.java`），尚无执行逻辑。
- **世界目录获取方式**：`level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)`（即 `runs/<side>/saves/<world>/`），`KlineFileStorage.getDataDir` 已用此方式拼接 `financial_market` 子目录。

---

## 2. H2 依赖与 build.gradle 接入

### 2.1 版本
- **H2 2.2.224**（`org.h2:h2:2.2.224`）。用户已确认本地 Maven 缓存该版本；如缓存缺失，Gradle 会自动从 Maven Central 拉取（`build.gradle` 已含 `mavenCentral()`）。

### 2.2 build.gradle 修改
在 `dependencies` 块追加（放在 ASM 显式依赖之后、Gson 附近）：

```gradle
dependencies {
    // ... 现有 neoforge / ASM / mcef / gson ...

    // H2 嵌入式数据库（JSON→H2 迁移）
    implementation 'org.h2:h2:2.2.224'

    // 可选：发布打包时通过 JarJar 把 H2 打进 mod（生产分发必需）
    // jarJar('org.h2:h2:2.2.224') { jarJar.ranged(it, '[2.2.224,)') }
}
```

> 说明：
> - dev 环境（`runClient`）下 `implementation` 即把 H2 放到运行时 classpath，可直接用。
> - 生产分发（把 mod jar 交给玩家）时，必须把 H2 通过 NeoForge **JarJar** 打包进 mod（否则玩家端缺库）。上面 `jarJar` 行为标准做法（`net.neoforged.moddev` 插件需开启，或用 `net.neoforged.gradle.userdev` 的 jarJar 支持）；若暂不发布，可先只保留 `implementation`。
> - 不要用 `compileOnly`：H2 是运行时必需。

### 2.3 依赖确认命令
```bash
./gradlew dependencies --configuration runtimeClasspath   # 应出现 org.h2:h2:2.2.224
```

---

## 3. H2 文件存放路径

### 3.1 位置
- 数据库文件放在**世界目录**下，与引擎状态同生命期、随世界存档：
  ```
  <world>/financial_market/db/financialmarket.mv.db
  <world>/financial_market/db/financialmarket.trace.db   # H2 可选 trace 文件
  ```
- 获取世界目录：`server.getWorldPath(LevelResource.ROOT)`（复用 `KlineFileStorage.getDataDir` 的逻辑，保证与现有 `financial_market` 数据目录一致）。

### 3.2 JDBC URL
```java
Path worldDir = server.getWorldPath(LevelResource.ROOT).resolve("financial_market").resolve("db");
Files.createDirectories(worldDir);
String jdbcUrl = "jdbc:h2:" + worldDir.resolve("financialmarket").toString()
        + ";DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=true";
```
- `DB_CLOSE_ON_EXIT=FALSE`：连接生命周期由我们掌控（ServerStopping 手动关），避免 JVM 退出竞态。
- `DATABASE_TO_UPPER=false` + `CASE_INSENSITIVE_IDENTIFIERS=true`：方便小写标识符与 asset id 大小写无关。
- `MODE=MySQL` 可选（语法亲和），非必需。
- 建议在 `financial_market_data` 与 world 目录之间只放 DB 子目录 `db/`，与 `KlineFileStorage` 的 `financial_market` 目录分开或复用均可；推荐复用同一 `financial_market` 根并在其下建 `db/`。

---

## 4. 连接生命周期（JDBC）

### 4.1 打开 / 关闭挂点
利用 `FinancialMarket$Events` 现有事件（`FinancialMarket.java:44-134`）：

| 事件 | 动作 |
|---|---|
| `ServerStartedEvent`（`onServerStarted`） | `MarketDatabase.open(server)`：建目录 → 建表（幂等 `CREATE TABLE IF NOT EXISTS`）→ 设置 schema_version → **首次启动执行存量迁移** → 打开单连接 |
| `ServerStoppingEvent`（`onServerStopping`） | 保存引擎 → `MarketDatabase.close()`：`COMMIT` 未提交事务 → 关闭 `Connection`/`Statement`/`ResultSet` → 置空单例 |

顺序建议：在 `onServerStopping` 中**先 `MarketAttachment.saveEngine(level)`（现有落盘）再关 H2**，或在迁移完成后改由 H2 落盘，二者择一（见 §6 迁移路径）。

### 4.2 推荐：`MarketDatabase` 单例门面
新增 `storage/db/MarketDatabase.java`（不在本次 in-scope 改动，属后续实现，此处仅设计）：
- 持有 `private static Connection conn;`
- `open(server)`、`close()`、`transaction(Supplier<T>)`、各 DAO 方法（`saveKlines`、`loadKlines`、`saveEngineState`、`loadEngineState`、`saveQuantScript`、`loadQuantScript`…）。
- 所有写操作包在 `try { conn.setAutoCommit(false); ...; conn.commit(); } finally { conn.setAutoCommit(true); }` 中，保证原子性。

---

## 5. 服务器主线程单线程模型下的连接策略

### 5.1 事实
- Minecraft 服务器逻辑（`LevelTickEvent`、`ServerStarted/Stopping`、服务端网络包 `*Packet::handle`）都运行在**单一服务器线程**。
- **例外**：`LocalHttpServer`（`web/LocalHttpServer.java`）用 `Executors.newCachedThreadPool()` 处理 HTTP 请求，运行在**HTTP 工作线程**；其 `/api/*` 目前从引擎内存读取。

### 5.2 三种策略对比
| 策略 | 说明 | 适用 | 风险 |
|---|---|---|---|
| A. 单连接复用（推荐） | 打开一条 `Connection`，只在服务器线程访问；写操作用事务批量提交 | 引擎 tick / 存档 / 服务端 packet | 若 HTTP 线程误用同一连接 → 非线程安全 |
| B. 阻塞写队列 | 服务器线程提交写任务，DB 写入串行化；HTTP 读走独立短连接或只读连接 | 需要 HTTP 线程读 H2 | 需额外同步，复杂度高 |
| C. 连接池（HikariCP） | 多线程各取连接 | 多线程读 + 服务器线程写 | H2 嵌入式对并发写/锁有约束；引入额外依赖 |

### 5.3 推荐方案（单连接复用 + HTTP 读走缓存/服务器线程代理）
1. **服务器线程**：单一 `Connection` 复用，所有引擎状态读写走它。因单线程串行，无需锁；用 `autoCommit` 事务保证批量写原子。
2. **HTTP 线程（Web API）**：
   - 优先方案：**继续走内存缓存**（现有 `web/ClientMarketDataCache`）或**转发到服务器线程**执行（`server.execute(() -> db.query(...))` + `Future`/回调），不直接碰服务器线程持有的同一 `Connection`。
   - 若确需 HTTP 线程直读 H2：为读操作单独开一条**短命只读连接**（`jdbc:h2:...;ACCESS_MODE_DATA=r`）或引入 HikariCP 小连接池，且写入始终只在服务器线程。**禁止**多条线程共享同一 `Connection`。
3. **兜底**：`MarketDatabase` 内部对每条 SQL 用 `try-with-resources` 保证 `Statement/ResultSet` 及时释放，避免连接内资源泄漏。

> 设计原则：**单连接归服务器线程所有**；任何其它线程的 DB 需求一律通过“服务器线程代理”或“独立连接/池”解决，绝不在 HTTP 线程上复用服务器线程的连接。

---

## 6. 表结构设计与字段映射

> 设计基调：**热路径（K线、资产价、公司）做关系化；深层嵌套 NBT 子树（macro/fund）做 KV-BLOB**，避免过度反规范化，保证可执行、可演进。所有表带 `schema` 版本管理。

### 6.1 `schema_meta`（版本/迁移记录）
```sql
CREATE TABLE IF NOT EXISTS schema_meta (
    meta_key   VARCHAR(64) PRIMARY KEY,
    meta_value VARCHAR(255) NOT NULL
);
-- 行：('schema_version','1') ('data_version','1') ('migrated_at','<ISO8601>')
```

### 6.2 K线（对应 `PriceDiscoveryEngine.klineCache` + `OHLCData`）
```sql
CREATE TABLE IF NOT EXISTS kline (
    asset_id   VARCHAR(64)  NOT NULL,
    kline_type VARCHAR(16)  NOT NULL,   -- TICK/QUARTER_DAY/HALF_DAY/DAILY/MONTHLY
    bar_index  INT          NOT NULL,   -- LinkedList 中的顺序位（0..n-1）
    open       DOUBLE       NOT NULL,
    high       DOUBLE       NOT NULL,
    low        DOUBLE       NOT NULL,
    close      DOUBLE       NOT NULL,
    volume     BIGINT       NOT NULL,
    PRIMARY KEY (asset_id, kline_type, bar_index)
);
CREATE INDEX IF NOT EXISTS idx_kline_asset ON kline (asset_id, kline_type);
```
- 字段映射：`OHLCData.getOpen/getHigh/getLow/getClose/getVolume` → `open/high/low/close/volume`；`KlineType.name()` → `kline_type`；`LinkedList` 下标 → `bar_index`。
- 规模提示：K线量可能较大（每资产每类型最多 500 根，见 `aggregateToPeriod` 的 `while (target.size() > 500)`），写入可分批 `batch`。

### 6.3 资产实时价 / GBM / 日开收（对应 `PriceDiscoveryEngine.prices/gbm/daily` + `AssetSnapshot`）
```sql
CREATE TABLE IF NOT EXISTS asset (
    asset_id       VARCHAR(64) PRIMARY KEY,
    last_price     DOUBLE,
    daily_open     DOUBLE,
    daily_close    DOUBLE,
    gbm_price      DOUBLE,
    gbm_mu         DOUBLE,
    gbm_sigma      DOUBLE,
    nav            DOUBLE,      -- AssetSnapshot.netAssetValue
    industry       VARCHAR(64)
);
```

### 6.4 成交点 / 订单流（对应 `PriceDiscoveryEngine.tradePoints`，`TradePoint(price,qty,isBuy)`）
```sql
CREATE TABLE IF NOT EXISTS trade_point (
    asset_id VARCHAR(64) NOT NULL,
    seq      INT         NOT NULL,
    price    DOUBLE      NOT NULL,
    quantity INT         NOT NULL,
    is_buy   BOOLEAN     NOT NULL,
    PRIMARY KEY (asset_id, seq)
);
```

### 6.5 AI 公司（对应 `MarketEngine.save()` 的 `aiCompanies`）
```sql
CREATE TABLE IF NOT EXISTS ai_company (
    id       VARCHAR(64) PRIMARY KEY,
    name     VARCHAR(128) NOT NULL,
    industry VARCHAR(64),
    net_asset DOUBLE NOT NULL,
    us_code  VARCHAR(32),
    cn_code  VARCHAR(32),
    ipo_price DOUBLE
);
```

### 6.6 玩家公司（对应 `CompanyManager.save()` 的 `companies`）
```sql
CREATE TABLE IF NOT EXISTS company (
    stock_id       VARCHAR(64) PRIMARY KEY,
    owner_uuid     VARCHAR(36) NOT NULL,
    name           VARCHAR(128) NOT NULL,
    level          INT,
    status         VARCHAR(32),
    net_asset      DOUBLE,
    recent_profit  DOUBLE,
    total_shares   BIGINT,
    ipo_price      DOUBLE,
    founded_time   BIGINT,
    listed_time    BIGINT
);
```

### 6.7 指数（对应 `excellent24`）
```sql
CREATE TABLE IF NOT EXISTS index_state (
    id             VARCHAR(32) PRIMARY KEY,  -- 固定行 'excellent24'
    value          DOUBLE NOT NULL,
    last_rebalance BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS index_constituent (
    index_id  VARCHAR(32) NOT NULL,
    asset_id  VARCHAR(64) NOT NULL,
    seq       INT         NOT NULL,
    PRIMARY KEY (index_id, asset_id)
);
```

### 6.8 引擎嵌套状态 KV-BLOB（对应 `macro`、`fund` 等深层 NBT 子树）
```sql
CREATE TABLE IF NOT EXISTS engine_kv (
    k     VARCHAR(64) PRIMARY KEY,
    v     CLOB      NOT NULL,   -- JSON 序列化
    dtype VARCHAR(32)           -- 'nbt-json' | 'string' | ...
);
-- 行示例：('macro', '{...}', 'nbt-json')、('fund', '{...}', 'nbt-json')
```
- 目的：`MacroStateEngine.save()`、`FundamentalEngine.save()` 返回的 `CompoundTag` 可 `Gson.toJson`/NBT→JSON 后存入 `engine_kv`，避免为其内部几十个字段逐一建表；这些属低频读取。

### 6.9 量化脚本（对应 `quant_strategies/*.js`，`FinancialTabletScreen.saveQuantFile/loadQuantFile`）
```sql
CREATE TABLE IF NOT EXISTS quant_script (
    script_id   VARCHAR(128) PRIMARY KEY,   -- 原文件名，如 'strategy1.js'
    name        VARCHAR(128),
    code        CLOB NOT NULL,              -- .js 源码全文
    language    VARCHAR(16) DEFAULT 'javascript',
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
);
```

### 6.10 字段映射汇总表
| H2 表 | 来源（现有代码） | 映射说明 |
|---|---|---|
| `kline` | `PriceDiscoveryEngine.klineCache` / `OHLCData` / `KlineFileStorage` | asset_id, kline_type, bar_index, OHLC 5 字段 |
| `asset` | `PriceDiscoveryEngine.lastPrices/gbm/daily`、`MarketState.assets` | 每资产一行实时价 + GBM 参数 + 日开收 + NAV |
| `trade_point` | `PriceDiscoveryEngine.tradePoints`（`TradePoint`） | price/quantity/isBuy → 列 |
| `ai_company` | `MarketEngine.save()` 的 `aiCompanies` | 现有 6 字段 + ipo_price |
| `company` | `CompanyManager` / `CompanyData` | 玩家公司字段 |
| `index_state`/`index_constituent` | `Excellent24Index` | 指数值 + 成分 |
| `engine_kv` | `macroEngine.save()`、`fundEngine.save()` 等 | 嵌套 NBT → JSON CLOB |
| `quant_script` | `FinancialTabletScreen` 的 `quant_strategies/*.js` | 文件内容 → CLOB |

---

## 7. 存量数据迁移路径（一次性导入 / 首次启动转换）

### 7.1 迁移触发
- `MarketDatabase.open()` 时读取 `schema_meta('schema_version')`；若为空（首次），进入**迁移模式**。
- 迁移在**服务器启动、数据加载完成后**执行一次，成功后写 `schema_version=1` 并 `data_version=1`；后续启动直接读 H2。

### 7.2 分源迁移步骤
1. **K线 / 元数据**
   - 真实来源是 `MarketEngine`（`getOrCreateEngine` 已从世界附件 NBT 载入内存）。迁移时直接把内存中 `priceEngine` 的 `klineCache`、`lastPrices`、`gbmStates`、`tradePoints`、`dailyOpen/Close` 批量写入 `kline`/`asset`/`trade_point` 表。
   - 若存在 `KlineFileStorage` 遗留的 `financial_market/klines.json`、`metadata.json`，作为**次级来源**：先读 JSON 导入（`KlineFileStorage.loadKlines/loadMetadata` 现成方法可复用），再以内存 NBT 为准覆盖。
2. **引擎主状态**
   - 首次：`MarketAttachment.getOrCreateEngine(level)` 已从 `level.dat` 附件加载 → `engine.save()` 得到的 `CompoundTag` 拆解写入 §6 各表（`asset`/`ai_company`/`company`/`index_*`/`engine_kv`）。
   - 迁移完成后，**切换主存储**：`MarketAttachment.saveEngine` 改为写 H2（或双写过渡一段版本再关闭 NBT 路径）。
3. **量化脚本**
   - 首次启动扫描工作目录 `quant_strategies/`（`Files.list`），将每个 `.js` 读入 `quant_script.code`；`script_id` = 原文件名。
   - 迁移后 `FinancialTabletScreen.saveQuantFile/loadQuantFile` 改为读写 H2（不再依赖 CWD）。
4. **幂等与失败**：迁移包在单个事务内；任一步失败则 `rollback`、`schema_version` 不前进，记录日志并回退到内存运行（不崩溃），下次启动重试。

### 7.3 迁移后的读写切换
- `KlineFileStorage`：迁移完成后可下线（本身即孤儿代码）；K线读写改走 `MarketDatabase`。
- `MarketAttachment`：保留内存 `engineCache` 逻辑；`saveEngine/loadEngine` 的 NBT 附件路径在迁移完成后由 H2 取代（可在 `onServerStopping` 双写一个版本再停用 NBT）。

### 7.4 保持现状（明确不迁移）
- **`CommodityRegistry` 的 `commodity_prices.json`**：classpath 只读静态配置（`data/financialmarket/commodity_prices.json`），由 `CommodityRegistry.loadFromJson/autoRegister` 在启动时加载到内存，**保持不变、不迁移**。商品**运行时价格状态**若需持久化属另一课题（本次范围外）。
- `schema_meta` 本身、H2 文件路径，与 Minecraft 原生存档（level.dat / 区域文件）不冲突。

---

## 8. 线程安全与性能建议（落地要点）

1. **单连接归服务器线程**：`MarketDatabase` 的连接只在 `ServerStarted→ServerStopping` 之间、服务器线程上使用。
2. **HTTP 线程隔离**：`LocalHttpServer` 的 `/api/*` 不直接复用该连接；读走 `ClientMarketDataCache` 或 `server.execute` 代理。若必须直读，用独立只读连接/池。
3. **事务化存档**：`onServerStopping` 保存时 `setAutoCommit(false)`，全部引擎状态一次 `commit()`，失败 `rollback()` 并保留内存状态供下回合再存。
4. **批量写**：K线等大批量数据用 `PreparedStatement` + `addBatch/executeBatch`（几千行量级无压力）。
5. **索引**：`kline(asset_id,kline_type)`、`trade_point(asset_id)`、`company(owner_uuid)`。
6. **日志**：统一用 NeoForge Logger（避免 `System.out` 中文乱码），DB 连接失败仅告警不阻断游戏（内存可运行，下次启动再试）。

---

## 9. 落地清单（后续实现建议，非本次改动）

1. `storage/db/MarketDatabase.java`（连接 + schema + DAO + 事务门面）。
2. `storage/db/SchemaMigrator.java`（schema_meta 版本管理 + §7 存量导入）。
3. `storage/db/KlineDao`、`EngineStateDao`、`QuantScriptDao`。
4. 改造：`FinancialMarket$Events.onServerStarted/onServerStopping` 挂接 open/close；`PriceDiscoveryEngine` 增加 H2 读写入口；`FinancialTabletScreen.saveQuantFile/loadQuantFile` 改读 H2。
5. `build.gradle` 加 H2 依赖（+ 发布期 JarJar）。
6. 下线 `KlineFileStorage`（孤儿代码）与 NBT 附件持久化路径（迁移稳定后）。
7. `./gradlew runClient` 冒烟：建世界 → 交易 → 退服保存 → 重进验证 H2 加载一致。

---

## 10. 结论
本方案在**不改变 Minecraft 原生存档结构**的前提下，用 H2 嵌入式库统一接管 K线/引擎状态/量化脚本三类数据：**单连接 + 服务器线程 + 事务化存档**，HTTP 线程通过缓存/代理隔离，**首次启动事务性导入**存量 JSON/NBT/JS，`schema_meta` 保证可迁移、可回滚；`CommodityRegistry.commodity_prices.json` 明确**保持 classpath 只读静态配置不变**。该设计为后续把 `QuantScriptEngine`、更多经济子系统持久化到 H2 留好了扩展位。
