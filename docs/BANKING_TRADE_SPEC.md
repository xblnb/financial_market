# BANKING_TRADE_SPEC — 商业银行 + 储蓄卡 + 贸易方块（市场/交割）需求规格（需求契约，供 t2/t3/t4 实施、t5 验证、t6 评审）

> - 角色：architect（方案架构 / 银行贸易需求规格）为任务 t1 产出。
> - 输入：团队 Goal + 当前源码审计（本规格对照实际源码给出文件/方法/字段/默认参数）。已交付前置基座：`ECON_FIAT_M2_SPEC.md`（法币 M0/M1/M2 / 央行 / 银行派生存款 / 税收 / 通胀）、`A_SHARE_TRADING_SPEC.md`（`TradingCalendar` 时间模型 + A 股规则）、`COMMODITY_FUTURES_SPEC.md`（商品动态注册/经济学定价/流体账本 + CZCE 期货 `FuturesMarket`）、`COMPANY_SPEC.md`（玩家公司 / AssetScanner / IPO / 股权 / 财报分红 / 退市）、`H2_MIGRATION.md`（H2 主真相 + NBT 附件镜像双写）。
> - 用途：engine-dev 按本规格实现 t2（4 家商业银行 + 储蓄卡签发/Luhn/UUID 绑定 + 持久化）、t3（持卡门控：市场/期货/商品/创建公司 + 公司账户绑定储蓄卡）、t4（市场方块 + 交割方块 + 期货实物交割接入）；test-engineer 按本规格实现 t5（整体验证 + 构建/可复现/持久化）；reviewer 按本规格验收 t6。
> - 约束：Minecraft NeoForge 1.21.1 主线程 tick 驱动、离线、单次 20-tick 步进内 O(玩家)/O(物品)/O(合约) 常数级计算。**时间权威用引擎 tick 计数器（`MarketEngine.tickCounter`）**，非墙钟、非 `gameTime`。
> - **可复现性硬约束（继承 P0/A0-3）**：同一种子同行为必须保持。储蓄卡号**生成**属随机源，必须走 `SeedableRandom`（`engine/random/SeedableRandom.java`，SplitMix64）子流并登记，**禁用 `ThreadLocalRandom` / 裸 `new Random()` / `Math.random()`**；Luhn 校验、门控、方块售货结算、期货实物交割全部为确定性逻辑。
> - **兼容边界硬约束（不动已定稿语义）**：
>   1. **不修改** A 股 `submitOrder`/`OrderBook`/`SettlementSystem`/`TradingCalendar`/`PriceDiscoveryEngine` GBM（文首约束）；本规格仅在 `submitOrder`/`FuturesMarket.open/close`/`CommodityMarket.buy/sell` 的**入口**叠加「持卡门控」，不改内部撮合/结算公式。
>   2. **不修改** `CommodityEntry` 定价、`FuturesMarket` 结算/涨跌停/保证金内部公式；市场方块售货只**复用** `CommodityMarket` 现有卖货记账的**价格与供给压力语义**，但收款改为绑定储蓄账户（非玩家背包现金）。
>   3. **不修改** 法币 M2 / 央行 / 银行派生存款公式；储蓄卡余额映射到**既有银行活期存款层**（`Bank.depositAccounts`），保持 M1/M2 分层度量不回归。
>   4. **不修改** `CompanyData`/IPO/分红/退市核心语义；仅给 `CompanyData` 增加 `boundCard`（储蓄卡绑定）字段，并给 `create` 加持卡前置校验。

---

## 0. 目标与全局验收（对应团队 Goal）

> 让 Financial Market 模组落地**银行 + 储蓄卡 + 贸易方块**：①商业银行——游戏内默认 **4 家商业银行**，玩家可在银行**办储蓄卡**，卡号**随机生成且符合现实卡号逻辑（Luhn 校验）**，卡号与**玩家 UUID 绑定**，**有储蓄卡才能进入市场（股市/期货）交易、也是创建公司的基础**，**公司账户绑定储蓄卡**；②贸易方块——**市场方块**（可用漏斗/其他模组管道往内输入贩卖的商品获取收益，**须绑定银行卡才能收款**）与**交割方块**（**绑定期货合约，交割日实物交割**，其余交互与市场方块一致）。与已交付经济引擎 / A 股 / 法币 M2 / 公司 / 商品 / 期货协同，保持确定性可复现、H2 持久化兼容。完成时须同时满足：

| 编号 | 全局验收 | 度量 | 责任 |
|---|---|---|---|
| BT-0 | 编译通过 | `./gradlew compileJava` 退出码 0；`./gradlew build` 出包 | 每项实施 + t5 |
| BT-1 | 可复现性不回归（继承 P0） | 同 `engineSeed` 跑固定 tick，卡号/门控/方块售货结算序列两次逐元素一致；新增随机全部走 `SeedableRandom`（全仓 `grep` 无 P2 之后新增的 `Math.random`/`new Random`/`ThreadLocalRandom` 调用；Luhn/门控/结算/交割为纯确定性函数） | t2~t4 + t5 |
| BT-2 | **4 家默认商业银行（t2）** | `BankManager` 默认注册 **4 家**商业银行（`BANK_A/BANK_B/BANK_C` + 新增 `BANK_D`）；均可被玩家办卡、查开户行、存取款；读档/新档数量恒为 4（不重复膨胀） | **t2** + t5 |
| BT-3 | **储蓄卡签发 + Luhn + UUID 绑定（t2）** | 玩家可向银行**办卡**（一次至多 N 张，默认 1 张/人）；卡号 **16 位**、**末位为 Luhn 校验位**（任意给定卡号可独立校验真伪，`Luhn.verify` 成立）；卡号与 `ownerUuid` 唯一绑定（`cardNumber↔ownerUuid` 双向可查）；同 `engineSeed` + 同玩家 + 同开户行 → 同卡号（SeedableRandom 子流可复现） | **t2** + t5 |
| BT-4 | **持卡门控：市场/期货/商品（t3）** | 无储蓄卡玩家**不可** A 股 `submitOrder`、期货 `FuturesMarket.open/close`、商品 `CommodityMarket.buy/sell(物品/流体)`，收到中文「请先办理储蓄卡」；有卡放行（其余 A 股/期货/商品既有校验照常） | **t3** + t5 |
| BT-5 | **持卡门控：创建公司（t3）** | 无储蓄卡玩家**不可**创建公司（`CompanyCommand create` / `CompanyActionPacket("create")`），收到「请先办理储蓄卡」；有卡放行 | **t3** + t5 |
| BT-6 | **公司账户绑定储蓄卡（t3）** | 创建公司时把创始人的储蓄卡号写入 `CompanyData.boundCard`（持久化）；公司分红/退市回购/经营现金可经绑定卡对应的储蓄账户流转（可选增强，字段与绑定为硬性） | **t3** + t5 |
| BT-7 | **市场方块（t4）** | 放置市场方块后可**绑定一张储蓄卡/账户**（右键交互/命令）；漏斗或其他模组管道（IItemHandler 能力）往内输入物品 → 方块按**当前商品价**卖出 → 收益**入绑定的储蓄账户**（非玩家背包现金）；未绑定卡时收入**无法入账**（提示先绑卡）；休市门控与商品市场一致 | **t4** + t5 |
| BT-8 | **交割方块（t4）** | 放置交割方块后可**绑定一张储蓄卡 + 一个期货合约代码**；交割日对绑定合约执行**实物交割**（多头收标的物品进方块库存、空头交付物品），交割价用最后结算价；其余交互（漏斗输入售货/收款）与市场方块一致 | **t4** + t5 |
| BT-9 | **H2 / NBT 持久化不回归** | 卡注册表、方块绑定（市场卡 / 交割卡+合约）、公司 `boundCard` 随 `MarketEngine.save()/loadInternal()` blob（`engine_kv.engine_main` + NBT 镜像）序列化/恢复；H2 镜像表新增列用 `ADD COLUMN IF NOT EXISTS` 前向兼容；旧存档缺字段用 `contains` 兜底重建，读档不失败 | t2/t3/t4 + t5 |
| BT-10 | **隔离（不回归）** | `banking.enabled=false` 时**不签发卡、不加门控**（回落到「无卡也可交易/建公司」基线，与既有行为逐位一致）；`tradeBlock.enabled=false` 时不注册方块/方块实体/不 tick 售货接入点 | t2/t3/t4 + t5 |

**验收流程（t5 落地）**：固定 `engineSeed` 跑固定 tick；走「办卡 → 卡号 Luhn 校验 + UUID 绑定 → 无卡被拒 / 有卡放行（股市/期货/商品/建公司）→ 公司绑定卡 → 市场方块绑卡 + 漏斗输入售货 → 收益入储蓄账户 → 交割方块绑约 + 交割日实物交割」全流程冒烟；对每次状态迁移断言确定性字段；两次同种子运行校验卡号/结算序列哈希一致（BT-1）；验证 H2 读档后卡/绑定/方块状态完整恢复（BT-9）；开关隔离与基线对比（BT-10）。

---

## 1. 总设计原则（约束所有实现）

1. **纯增量、可开关**：新增逻辑集中在 `engine/banking/` 的储蓄卡子包 + `block/`（或 `blockentity/`）+ `network/packets/` 新增包 + `MarketEngine`/`CompanyCommand` 的少数接入点。每个子项有独立 `enabled` flag（`banking.enabled`、`tradeBlock.enabled`）；全关时与现有基线逐位一致（BT-10）。
2. **卡号 = 确定性可复现的「随机」**：卡号生成必须走 `SeedableRandom` 子流（密钥 = `engineSeed` 派生子流 + `ownerUuid` + `bankId` + 签发计数器），同种子同玩家同开户行同状态 → 同卡号（BT-1/BT-3）。生成后立即按 Luhn 校验位补齐，保证任意卡号可独立验真。
3. **持卡 = 市场准入 + 建公司准入**：市场（A 股/期货/商品）与创建公司均要求玩家 `ownerUuid` 在卡注册表中有有效卡（BT-4/BT-5）。门控是**准入校验**，不改交易/结算语义。
4. **收款走储蓄账户（M2 协同）**：市场/交割方块的售货收益入**绑定卡对应的银行活期存款**（`Bank.depositAccounts[ownerUuid]`，`Bank.deposit()`），即 M1 存款层扩张，保持法币分层记账不回归（BT-7）。玩家现金仍可经存取款在 `PlayerEconomy.cash` 与储蓄账户间流转（存取款命令/前端）。
5. **方块交互确定性**：漏斗/管道输入物品 → 方块实体在 tick 内按 `CommodityEntry.price` 确定性结算（无随机）；收款金额 = `price × 数量`，入储蓄账户。休市/关闭时售货被拒（与 `CommodityMarket.canTrade` 一致）。
6. **期货实物交割经交割方块**：`FuturesMarket` 现有 `deliverExpired`（`FuturesMarket.java:351-369`）为**现金结算**（CF-7 允许抽象品种现金兜底）。本规格引入**实物交割**：绑定交割方块 + 已绑定卡的合约，交割日把多头持仓交付实物（标的物品进方块库存）/空头交付物品；未绑定交割方块的合约**保持现金结算兜底**（向后兼容，CF-7 不回归）。交割价用最后结算价（`prevSettlePrice`，`FuturesContract.java:24`）。

---

## 2. 现状诊断（代码审计结论）

| 类 | 文件 | 现状 | 问题（银行/卡/贸易方块视角） |
|---|---|---|---|
| `BankManager` | `engine/banking/BankManager.java` | 默认注册 **3 家** AI 银行（`:17-19` `BANK_A/BANK_B/BANK_C`），`registerBank` 可增；`saveToNBT/loadFromNBT` 已持久化银行货币状态 | **默认只有 3 家**，Goal 要求 4 家；无储蓄卡概念 |
| `Bank` | `engine/banking/Bank.java` | `depositAccounts Map<UUID,Double>` 活期存款账本（`:19`）、`deposit/depositTime/depositMoneyMarket/withdraw`（`:81-128`）、`getTotalDepositsAll` M1/M2 分层（`:60-74`） | 已有活期存款层可作储蓄账户；**缺按客户查询余额 getter**（`getDepositBalance(UUID)`）与存取款到玩家现金的公开入口 |
| `PlayerEconomy` | `attachment/PlayerEconomy.java` | `cash` 初始 1000、`setCash/addCash`（`:30-33`）、`CODEC` 持久化（`:73-83`） | 无卡字段；现金与银行储蓄账户无直接流转 |
| `MarketEngine` | `engine/MarketEngine.java` | 持 `bankManager`（`:63`）、`commodityMarket`、`futuresMarket`、`companyManager`；`submitOrder`（`:1280`）；`tick` 调 `commodityMarket.tick`/`futuresMarket.tick`（`:848/:855`）；`save()/loadInternal()` 持久化（`:1450/:1621`） | 无卡注册表、无持卡门控接入点、无方块接入；`getBankManager()` 已有（`:1405`） |
| `FuturesMarket` | `engine/futures/FuturesMarket.java` | `open/close`（`:363/:398`）、`canTrade`（`:372`）、`deliverExpired` 现金结算（`:351-369`）、`getOrCreateAccount`（`:436`） | 期货开仓无持卡门控；**交割只有现金结算，无实物交割/交割方块** |
| `CommodityMarket` | `engine/CommodityMarket.java` | `buy/sell/buyFluid/sellFluid`（`:84/:100/:116/:130`）收款进 `PlayerEconomy.cash`；`tick` 时间权威化（`:186`） | 无「从方块按物品售出 + 入储蓄账户」的非玩家入口（`sell` 需 `ServerPlayer` 与背包库存） |
| `CompanyCommand` / `CompanyActionPacket` | `command/CompanyCommand.java` / `network/packets/CompanyActionPacket.java` | `create` 创建公司 | 无持卡前置校验、无 `boundCard` 写入 |
| `FinancialMarket` | `FinancialMarket.java` | 注册 item/attachment/command/packet；`onLevelTick` 驱动 `engine.tick`（`:117-124`） | **无 Block/BlockEntity 注册**（`init/` 只有 `ItemInit`），贸易方块需新增 BlockInit/BlockEntityInit 并在此注册 |
| `LocalHttpServer` | `web/LocalHttpServer.java` | 有 `/api/session` 绑定玩家（`:610-649`）、A 股下单等 | 需新增办卡/存取款/方块绑卡/售货结算查询 API（可选增强，§9） |
| `H2DatabaseManager` | `storage/db/H2DatabaseManager.java` | `CREATE TABLE IF NOT EXISTS`（`:130-217`）、`ADD COLUMN IF NOT EXISTS` 先例（`:246/:260`）、`engine_kv` blob（`:212`） | 卡注册表/方块绑定/公司 `boundCard` 走 blob + `ADD COLUMN` 镜像（§8） |

**根因**：①默认仅 3 家银行（Goal 要 4 家）；②完全无储蓄卡体系（签发/Luhn/UUID 绑定/门控）；③无市场/交割方块及 BlockEntity 注册；④期货交割仅有现金结算、无实物交割；⑤方块售货收款需接入银行储蓄账户而非玩家背包现金。本规格把银行储蓄卡 + 两个贸易方块 + 期货实物交割作为增量接入上述已交付基座。

---

## 3. AS-1 4 家商业银行 + 储蓄账户层（t2）

> 目标：让 `BankManager` 默认注册 **4 家**商业银行，并在银行提供**储蓄账户**（复用 `Bank.depositAccounts`）与**储蓄卡**（§4）。

### 3.1 改动文件
- `engine/banking/BankManager.java`：构造默认注册第 4 家银行 `BANK_D`（如「虚空联合银行」）；新增 `getDefaultBanks()`（返回 4 家，供 UI/命令枚举）。
- `engine/banking/Bank.java`：新增 `getDepositBalance(UUID customerId)`（读 `depositAccounts`，缺省 0）；新增 `depositFromCash(UUID, double)` / `withdrawToCash(UUID, double)` 公开入口（内部复用 `deposit`/`withdraw`，供玩家存取款走 PlayerEconomy 现金 ↔ 储蓄账户，保持 M1 分层记账）。
- `MarketEngine.java`：`getBankManager()` 已存在（`:1405`），无需改接口；新增 `getBankCardService()` 供门控/方块读取（§4）。

### 3.2 第 4 家银行（BT-2）
```java
// BankManager 构造新增（确定性常量，无随机）
Bank bankD = new Bank("BANK_D", "虚空联合银行", null, 1500000, 0.10, 0.045, 0.008);
bankD.addProduct(new SavingsProduct("活期存款", 1, 0, 0.005, true));
banks.put(bankD.getBankId(), bankD);
```
- 4 家银行**默认恒为 4**（新档/读档）；`loadFromNBT`（`BankManager.java:203`）只覆盖已有 bankId 状态，不新增/不删除，故读档后仍 4 家（BT-2）。
- 默认银行数量常量 `DEFAULT_BANK_COUNT = 4`（放 `BankManager`）。

### 3.3 储蓄账户（复用存款层，M2 协同）
- 储蓄账户 = `Bank.depositAccounts[ownerUuid]`（活期存款，计入 M1/M2，见 `ECON_FIAT_M2_SPEC`）。
- 新增 `Bank.getDepositBalance(UUID)`：返回该客户活期存款余额（缺省 0），供 UI/方块/命令查询。**确定性、无随机**。
- 新增 `Bank.depositFromCash(UUID, double)`：把等额现金从玩家 `PlayerEconomy.cash` 转入该行活期存款（`eco.setCash(cash−amt)` + `bank.deposit(owner, amt)`），M1 现金→活期转移，总量不变。
- 新增 `Bank.withdrawToCash(UUID, double)`：反向（`bank.withdraw` + `eco.setCash(cash+amt)`）；余额不足返回 false。
- 存取款命令：新增 `/fmarket bank deposit <bankId> <amount>` / `withdraw`（普通玩家）或前端银行页按钮（协同增强，非硬性，§9）。**收款入储蓄账户**后玩家可经此命令/前端取现到 `PlayerEconomy.cash`。

### 3.4 验收（t2 自身）
- `BankManager` 构造后 `getAllBanks().size()==4`；新档/读档后仍 4 家（BT-2）。
- 玩家可向任意银行存取款：现金↔活期存款正确转移，`computeM1`（现金+活期）总量不变（BT-2 + FM-5 不回归）。
- 同种子两次运行，银行货币状态逐值一致（BT-1）。
- `banking.enabled=false` 时不开通存取款/储蓄账户命令（隔离，BT-10）。

---

## 4. AS-2 储蓄卡签发 + Luhn 校验 + UUID 绑定（t2）

> 目标：玩家向银行**办储蓄卡**，卡号 16 位、Luhn 合法、与 `ownerUuid` 唯一绑定；卡号生成确定性可复现。

### 4.1 改动文件
- 新增 `engine/banking/card/SavingsCard.java`（数据模型：cardNumber/bankId/ownerUuid/issuedTick）
- 新增 `engine/banking/card/BankCardService.java`（签发/校验/查询/撤销，确定性 + SeedableRandom 子流）
- 新增 `engine/banking/card/Luhn.java`（纯静态：卡号校验位计算 + 校验，**无状态无随机**）
- 新增 `network/packets/CardIssuePacket.java` / `CardQueryPacket.java`（可选，办卡/查卡请求）
- 新增命令 `command/BankCommand.java`（办卡/查卡/撤销/存取款，§3.3 + §4）
- 改 `engine/MarketEngine.java`：构造 `new BankCardService(seedSubstream)`；`save()/loadInternal()` 持久化卡注册表（§4.4）；`getBankCardService()`

### 4.2 `SavingsCard` 数据模型
```java
public class SavingsCard {
    String cardNumber;   // 16 位卡号（含 Luhn 校验位）
    String bankId;       // 开户行（BANK_A..D）
    UUID   ownerUuid;    // 持卡玩家 UUID（唯一绑定）
    long   issuedTick;   // 签发时的引擎 tick（确定性，用于可复现/过期/排序）
    boolean active;      // 是否有效（撤销后 false）
    // NBT save/load（contains 兜底）
}
```

### 4.3 `Luhn`（现实卡号校验逻辑，纯函数）
```java
public final class Luhn {
    /** 对 15 位前缀计算校验位（末位），返回 16 位完整卡号。 */
    public static String appendCheckDigit(String prefix);   // prefix 为 15 位数字
    /** 校验一个完整卡号是否符合 Luhn（末位校验位正确）。 */
    public static boolean verify(String cardNumber);        // 只对数字字符串判定
    private static int computeCheckDigit(String digits);    // 标准 Luhn 算法（偶位×2-9）
}
```
- 实现为标准 Luhn：从右往左，偶数位×2 若>9 减 9，累和，校验位 = (10 − 累和%10)%10。
- **验收**：`verify` 对生成卡号成立；对任意篡改一位的卡号不成立（独立验真，BT-3）。

### 4.4 `BankCardService`（签发/校验，确定性）
```java
public class BankCardService {
    public static final int CARD_DIGITS = 16;        // 卡号长度
    public static final int MAX_CARDS_PER_PLAYER = 1; // 每玩家默认 1 张（可调）

    private final SeedableRandom rng;                  // 引擎种子派生子流（可播种）
    private final Map<String, SavingsCard> byNumber;   // cardNumber -> card
    private final Map<UUID, String> ownerToNumber;     // ownerUuid -> cardNumber（每人至多 1 张）

    public BankCardService(long seed);                 // seed = engineSeed 派生子流（P0 §6 模式）
    /** 玩家向某行办卡；成功返回 cardNumber，失败返回 null/原因。 */
    public String issueCard(UUID ownerUuid, String bankId, long issuedTick);
    /** 玩家是否持有有效卡。 */
    public boolean hasActiveCard(UUID ownerUuid);
    public SavingsCard getCardByNumber(String cardNumber);
    public SavingsCard getCardOf(UUID ownerUuid);
    public String getBankOf(UUID ownerUuid);           // 查开户行
    /** 撤销玩家卡（可重新办，确定性）。 */
    public boolean revokeCard(UUID ownerUuid);

    public CompoundTag save();
    public void load(CompoundTag tag);                 // contains 兜底为空
}
```
- **卡号生成（BT-1/BT-3）**：
  ```java
  String genCardNumber(UUID ownerUuid, String bankId, long issuedTick) {
      long s = SeedableRandom.mix64(ownerUuid.getMostSignificantBits()
                 ^ ownerUuid.getLeastSignificantBits() ^ bankId.hashCode()
                 ^ (issuedTick * 0x9E3779B97F4A7C15L) ^ rng.nextLong());
      SeedableRandom sub = new SeedableRandom(s);          // 子流
      // 15 位数字前缀：前 1 位 BIN 标识（1=借/储蓄）+ 6 位发卡行段（bankId 确定性映射）+ 8 位随机段
      String prefix = binPrefix(bankId) + digits(sub, 8);
      return Luhn.appendCheckDigit(prefix);                // 第 16 位 = 校验位
  }
  ```
  - `binPrefix(bankId)` 确定性映射（如 BANK_A→"6222 01"... 取 7 位），不耗随机。
  - `digits(sub, n)` 用 `sub.nextLong()` 模 10 逐位（`%10`），**走 SeedableRandom**，同种子同玩家同银行同 tick → 同卡号。
  - 碰撞：若 `byNumber` 已存在，重抽一次随机段（同种子可复现）；`issuedTick` 为签发时的 `engine.getTickCounter()`。
- **签发规则**：`hasActiveCard(ownerUuid)` 为 true 时不重复签发（每人默认 1 张，`MAX_CARDS_PER_PLAYER`）；`bankId` 必须是 4 家默认银行之一。
- **UUID 绑定**：`byNumber` 与 `ownerToNumber` 双向保证 `cardNumber↔ownerUuid` 唯一（BT-3）。`getCardOf(ownerUuid)` 门控用。

### 4.5 持久化（BT-9）
- `BankCardService.save()/load()`：对每张卡存 `cardNumber/bankId/ownerUuid/issuedTick/active`；`load` 用 `contains` 兜底（旧档无卡注册表 → 空表，读档不失败）。
- `MarketEngine.save()` 增 `tag.put("bankCards", bankCardService.save())`；`loadInternal()`（`:1621`）用 `if (tag.contains("bankCards")) ... load(...)` 恢复（BT-9）。

### 4.6 命令（BT-3，可选前端协同）
```
/fmarket bank issue <bankId>        # 办储蓄卡（普通玩家）
/fmarket bank info                  # 查我的卡号/开户行
/fmarket bank revoke                # 撤销我的卡（管理或玩家，默认玩家可）
/fmarket bank deposit <bankId> <amt> # 现金存入储蓄账户（§3.3）
/fmarket bank withdraw <bankId> <amt># 储蓄账户取现
```
- 权限：`issue/info/deposit/withdraw` 普通玩家；`revoke` 普通玩家（自己）或管理。
- 全部确定性校验，失败发中文 `sendFailure`。

### 4.7 验收（t2 自身）
- 办卡后卡号 16 位、`Luhn.verify` 成立、`getCardOf(owner)` 返回该卡、`getCardByNumber` 返回同卡（BT-3）。
- 同 `engineSeed` + 同玩家 + 同开户行 + 同 tick 两次办卡 → 同卡号（BT-1/BT-3）。
- 已持卡再办卡被拒（每人默认 1 张）；无效 `bankId` 被拒。
- 撤销后可重办；读档后卡注册表完整恢复、缺字段旧档读档不失败（BT-9）。
- `banking.enabled=false` 不签发卡（隔离，BT-10）。

---

## 5. AS-3 持卡门控：市场 / 期货 / 商品 / 创建公司（t3）

> 目标：无储蓄卡玩家**不可**进入市场交易（A 股/期货/商品）与创建公司；有卡放行。门控是**准入校验**，不改交易/结算语义（BT-4/BT-5）。

### 5.1 改动文件
- 改 `engine/MarketEngine.java`：`submitOrder`（`:1280`）入口叠加持卡校验。
- 改 `engine/futures/FuturesMarket.java`：`open`（`:378`）/`close`（`:413`）入口叠加持卡校验。
- 改 `engine/CommodityMarket.java`：`buy/sell/buyFluid/sellFluid`（`:84-145`）入口叠加持卡校验。
- 改 `command/CompanyCommand.java`：`create`（`:48-53`）叠加持卡校验；写 `CompanyData.boundCard`（§5.4）。
- 新增 `MarketEngine.hasBankCard(UUID ownerUuid)`（委托 `bankCardService.hasActiveCard`），供各入口读取。

### 5.2 门控语义（BT-4）
```java
// MarketEngine.hasBankCard(UUID)
public boolean hasBankCard(UUID ownerUuid) {
    if (!bankingEnabled) return true;      // 隔离：banking 关闭不设门控（BT-10）
    return bankCardService.hasActiveCard(ownerUuid);
}
```
- **A 股**：`submitOrder(Order)`（`:1280`）在 A 股休市校验（`:1281`）**之前**加：
  `if (!hasBankCard(order.getPlayerId())) { /* 中文「请先办理储蓄卡」；返回 */ }`
  - 注意 `Order.playerId` 可能是代理/ZI 合成 UUID（`MarketEngine.java:93`）——这些合成流**不参与真实玩家门控**，仅对**真实在线玩家**（`playerId` 命中在线玩家）校验（与 A 股 T+1 只对真实玩家记账一致）。实现时 `hasBankCard` 对非在线玩家返回 true（跳过）。
- **期货**：`FuturesMarket.open/close` 在 `canTrade()`（`:372`）之后、`contracts.get` 之前加 `if (!engine.hasBankCard(player.getUUID())) return "请先办理储蓄卡";`（`player` 为真实 `ServerPlayer`）。
- **商品**：`CommodityMarket.buy/sell/buyFluid/sellFluid` 在 `canTrade()`（`:78`）之后加 `if (!engine.hasBankCard(player.getUUID())) return false;`。
- **Web（LocalHttpServer）**：A 股/期货/商品写入在 `resolveTradingPlayer`（`:457`）后按绑定玩家校验 `hasBankCard`（与游戏内一致，§9 协同）。
- **隔离**：`bankingEnabled=false` 时 `hasBankCard` 恒 true，无门控（与基线一致，BT-10）。

### 5.3 创建公司门控（BT-5）
- `CompanyCommand.create`（`:48-53`）与 `CompanyActionPacket("create")` 在创建前加：
  `if (!engine.hasBankCard(player.getUUID())) { /* 中文「请先办理储蓄卡」；拒绝 */ }`
- 公司等级为私有（`PRIVATE`）且创始人持卡是创建前提。

### 5.4 公司账户绑定储蓄卡（BT-6）
- `CompanyData` 新增字段 `String boundCard`（创始人储蓄卡号，可为空表示未绑/无卡时因门控不会创建）。
- `write()/read()` 增加 `boundCard`（`contains` 兜底为空字符串），随公司 blob 持久化（`MarketEngine.java:1463` `companyManager.save()`）。
- 创建时：`company.setBoundCard(bankCardService.getCardOf(player.getUUID()) != null ? card.cardNumber : "")`。
- H2 `company` 镜像表新增列 `bound_card VARCHAR(19)`（`ADD COLUMN IF NOT EXISTS`，`H2DatabaseManager.java:260` 先例），镜像仅查询，blob 是权威。
- **可选增强**：分红（`COMPANY_SPEC §6.2`）/退市回购（§7.1）现金可经绑定卡对应储蓄账户流转（`Bank.deposit` 到 `ownerUuid` 而非直接玩家现金）；本期默认仍走 `PlayerEconomy.setCash`（不回归），字段绑定为硬性（BT-6）。

### 5.5 验收（t3 自身）
- 无卡玩家 A 股下单/期货开平仓/商品买卖（物品+流体）均被拒并收到「请先办理储蓄卡」（BT-4）。
- 有卡玩家上述操作放行，其余 A 股/期货/商品校验照常（BT-4）。
- 无卡玩家 `create` 公司被拒；有卡创建成功且 `CompanyData.boundCard` = 卡号（BT-5/BT-6）。
- 合成/ZI 订单流不受门控影响（不回归 A 股/期货基线）。
- `banking.enabled=false` 无卡也可交易/建公司（隔离，BT-10）。
- 读档后 `boundCard` 恢复、缺字段旧档读档不失败（BT-9）。

---

## 6. AS-4 市场方块（t4）

> 目标：市场方块接受漏斗/其他模组管道输入物品并按当前商品价卖出，收益入绑定储蓄账户；未绑卡无法收款（BT-7）。

### 6.1 改动文件（新增 `block/` 与 `blockentity/` 包）
- 新增 `block/MarketBlock.java`（`Block`，可放置，右键绑卡/查询）
- 新增 `blockentity/MarketBlockEntity.java`（方块实体：输入库存 + 售货结算 + 绑定账户）
- 新增 `init/BlockInit.java`（`DeferredRegister.Blocks` + `DeferredRegister.BlockEntityTypes`）
- 新增 `init/BlockEntityInit.java`（或并入 `BlockInit`）
- 改 `FinancialMarket.java`：`BlockInit`/`BlockEntityInit` 注册到 `modEventBus`（构造 `:36-39`）
- 改 `engine/MarketEngine.java`：tick 接入 `MarketBlockEntity` 售货结算（`try/catch` 防御，不阻断 tick）
- 改 `engine/CommodityMarket.java`：新增**非玩家售货入口** `sellFromBlock(Item item, int qty, UUID creditAccount)`（§6.3）
- 改 `engine/banking/Bank.java`：`deposit(UUID, double)` 已存在（`:81`），用于入账
- 资源：`blockstates/market_block.json`、`models/block/market_block.json`、`models/item/market_block.json`、lang key、纹理

### 6.2 `MarketBlock` / `MarketBlockEntity`
- **Block**：`useWithoutItem`/右键交互 → 打开配置（绑卡/查询）；支持方块实体（`newBlockEntity`）。
- **BlockEntity**：
  - 持有输入库存（`ItemStackHandler`，实现 IItemHandler 能力 → 漏斗/管道可输入，NeoForge capability）。
  - 绑定字段：`boundOwnerUuid`（绑定的持卡玩家 UUID）+ `boundCardNumber`（其卡号，校验 `Luhn.verify` + `getCardByNumber` 归属一致）。
  - 绑定方式：玩家右键（持卡）选择「绑定我的卡」→ 写 `boundOwnerUuid=player.getUUID()`、`boundCardNumber=bankCardService.getCardOf(...).cardNumber`。**未绑卡玩家不可绑定**（提示先办卡）。
  - `tick()`（方块实体 tick，随 `LevelTickEvent`）：当输入库存有可售物品且**市场开市**（`engine` 日历 `isMarketOpen` 或 `commodityMarket.canTrade()`）时，逐槽取出物品按 `CommodityEntry.price` 结算：
    ```java
    // 每 tick 至多结算 MAX_SELL_PER_TICK（默认 64 物品）防单 tick 过度；确定性
    ItemStack stack = handler.extractItem(slot, 1, false);
    CommodityEntry entry = commodityMarket.getCommodity(stack.getItem());
    if (entry != null && marketOpen) {
        double revenue = entry.getPrice();
        if (boundOwnerUuid != null && boundCardNumber != null) {
            bank.deposit(boundOwnerUuid, revenue);   // 入储蓄账户（M1 活期）
            pendingPayoutTotal += revenue;           // 可选累计对账
            commodityMarket.onBlockSell(stack.getItem(), 1); // 供给压力（复用 sell 语义）
        } else {
            // 未绑卡：不结算、物品保留在库存（提示先绑卡）——BT-7「未绑卡无法收款」
        }
    }
    ```
  - **休市门控**：`marketOpen=false` 时不结算（物品保留），与 `CommodityMarket.canTrade`（`:78`）一致。
  - **入账**：收益经 `Bank.deposit(boundOwnerUuid, revenue)` 入绑卡玩家活期存款（M1 扩张，M2 协同，BT-7）。
  - 持久化：`boundOwnerUuid/boundCardNumber/pendingPayoutTotal/输入库存 ItemStackHandler` 随方块实体 NBT 保存（`saveAdditional/loadAdditional`）。**注**：方块实体数据存方块 NBT，引擎 blob 之外；`engine_kv` blob 存**跨世界状态**（卡注册表/公司 boundCard），方块级绑定存方块实体 NBT（BT-9）。

### 6.3 `CommodityMarket.sellFromBlock`（非玩家售货入口）
```java
// 新增：从方块按 item 卖 qty，扣方块库存（方块已取走物品），只更新供给压力 + 返回单价供入账
public double sellFromBlock(Item item, int qty, UUID creditAccount) {
    if (!canTrade()) return 0.0;
    CommodityEntry entry = commodities.get(item);
    if (entry == null || qty <= 0) return 0.0;
    double totalRevenue = entry.getPrice() * qty;
    entry.sellPressure(qty);                    // 复用既有供给压力（不回归 CF 定价）
    propagateSupply(item, qty * 0.5);           // 复用下游供给传播（不回归）
    return totalRevenue;                        // 由 MarketBlockEntity 调用 Bank.deposit 入账
}
```
- **不直接改玩家背包/现金**：方块售货无 `ServerPlayer`，收款走 `Bank.deposit`（§6.2 由方块实体完成），因此 `CommodityMarket` 新增入口**只返回金额 + 更新压力**，入账由方块实体负责（保持 `CommodityMarket` 与玩家现金解耦，不回归现有 `sell`）。
- `sellFromBlock` 返回单价，供方块实体把 `totalRevenue` 入 `Bank.deposit(boundOwnerUuid, ...)`。

### 6.4 验收（t4 自身）
- 放置市场方块 → 右键绑定持卡玩家 → 漏斗输入物品 → 开市 tick 后 `Bank.getDepositBalance(boundOwnerUuid)` 增加 `price×qty`；`sellPressure` 更新（BT-7）。
- 未绑卡方块：漏斗输入物品**不结算**、物品保留（提示先绑卡）（BT-7）。
- 休市：输入物品不结算（BT-7 + CF-4 一致）。
- 同种子两次运行，售货入账序列一致（BT-1）。
- 读档后方块绑定与库存恢复（BT-9）。
- `tradeBlock.enabled=false` 不注册方块/方块实体（隔离，BT-10）。

---

## 7. AS-5 交割方块 + 期货实物交割（t4）

> 目标：交割方块绑定一张储蓄卡 + 一个期货合约，交割日对绑定合约**实物交割**（多头收标的物品进方块库存、空头交付物品）；其余交互（漏斗输入售货/收款）与市场方块一致（BT-8）。

### 7.1 改动文件
- 新增 `block/DeliveryBlock.java` + `blockentity/DeliveryBlockEntity.java`（扩展/复用 `MarketBlockEntity` 的售货/收款逻辑，叠加合约绑定与交割）
- 改 `engine/futures/FuturesMarket.java`：`deliverExpired`（`:351-369`）改造——对**已绑定交割方块 + 已绑卡**的合约走实物交割，其余保持现金结算兜底（§7.3）
- 新增 `engine/futures/FuturesDeliveryBlock.java`（可选用，交割结算辅助）或并入 `FuturesMarket`
- 改 `init/BlockInit.java`：注册 `DELIVERY_BLOCK`（并入 §6.1）
- 资源：`blockstates/delivery_block.json`、`models/block/delivery_block.json`、`models/item/delivery_block.json`、lang key

### 7.2 `DeliveryBlock` / `DeliveryBlockEntity`
- **绑定**：玩家右键 → 「绑定我的卡」+「绑定合约 `<code>`」。
  - 卡绑定同 §6.2（须持卡，`Luhn` + 归属校验）。
  - 合约绑定：`FuturesMarket.getContract(code)` 存在且 `status==LISTED/DELIVERING` 才可绑；`boundContractCode` 持久化。
- **交互（与市场方块一致）**：漏斗输入物品 → 按 `CommodityEntry.price` 售货 → 收益入绑定储蓄账户（复用 §6 逻辑）。
- **交割（BT-8）**：交割日（合约 `status==DELIVERING`，见 `FuturesMarket.java:162-170`）对**该绑定方块绑定的合约**执行实物交割：
  - 找该合约所有玩家持仓（`FuturesMarket.accounts`）。
  - **多头**：按 `multiplier × longLots` 生成标的物品 `ItemStack` 进该交割方块输入库存（可堆叠物品 `item.isStackable`）。
  - **空头**：从该玩家背包/存储扣减标的物品交付（不足 → 按交割价现金补偿——确定性规则，复用 `deliverExpired` 的现金兜底语义）；或要求玩家往交割方块存入标的物品。
  - 交割价 = 该合约最后结算价（`prevSettlePrice`，`FuturesContract.java:24`），与 `deliverExpired`（`:354`）一致。
  - 交割后：多空对冲平仓、保证金结清、合约 `EXPIRED`（复用 `deliverExpired` 的结清逻辑，`:362-367`）。

### 7.3 `FuturesMarket.deliverExpired` 改造（向后兼容）
```java
private void deliverExpired() {
    for (FuturesContract c : new ArrayList<>(contracts.values())) {
        if (c.status != DELIVERING) continue;
        double deliveryPrice = c.prevSettlePrice > 0 ? c.prevSettlePrice : c.lastPrice;
        DeliveryBlockEntity bound = deliveryBlockRegistry.get(c.code); // 新增：合约→交割方块
        if (bound != null && bound.hasBoundCard()) {
            physicalDeliver(c, deliveryPrice, bound);   // 实物交割（§7.2）
        } else {
            cashSettle(c, deliveryPrice);               // 原现金结算（CF-7 兜底，不回归）
        }
        c.openInterest = 0;
        c.status = EXPIRED;
    }
}
```
- `deliveryBlockRegistry`：`Map<String code, DeliveryBlockEntity>`，由 `DeliveryBlockEntity` 绑约时登记、解绑/移除时注销（确定性，无随机）。注册表放 `FuturesMarket` 或独立 `FuturesDeliveryRegistry`。
- **实物交割确定性**：生成物品数量/进库存、空头扣减、现金补偿全部为确定性逻辑，无随机（BT-1）。
- **未绑交割方块** → 保持现金结算兜底（向后兼容，不破坏 `COMMODITY_FUTURES_SPEC` CF-7）。

### 7.4 验收（t4 自身）
- 放置交割方块 → 绑卡 + 绑约 → 到交割日，该合约多头持仓转成标的物品进方块库存、空头交付/补偿，保证金结清、合约 `EXPIRED`（BT-8）。
- 未绑交割方块合约保持现金结算兜底（BT-8 + CF-7 不回归）。
- 交割方块漏斗输入售货收款与市场方块一致（BT-7/BT-8）。
- 同种子两次运行，交割物品数量/现金补偿序列一致（BT-1）。
- 读档后交割方块卡绑定/合约绑定/库存恢复（BT-9）。
- `tradeBlock.enabled=false` 不注册交割方块（隔离，BT-10）。

---

## 8. 兼容性（继承 P0/P1/P2/A 股/公司/商品/期货，银行贸易增量）

### 8.1 网络包 / UI / Web
- **不删除/不重构**既有 packet 结构。新增卡/方块 packet 为新包（`network/ModMessages.java` 注册，`ModMessages.java:35-37,79-82` 先例）：`CardIssuePacket`/`CardQueryPacket`（办卡/查卡）、`MarketBlockBindPacket`/`DeliveryBlockBindPacket`（方块绑卡/绑约，可选）。
- `LocalHttpServer` 既有 `/api/*` 只读 API 不动；**新增**（§9 可选）：`GET /api/cards`、`POST /api/card/issue`、`POST /api/bank/deposit`、`POST /api/bank/withdraw`、方块绑定/售货查询（以 `/api/session` 绑定玩家身份）。
- 前端（`FinancialTabletScreen` 银行页/方块交互）为**协同增强**（非硬性验收），服务端逻辑先行就绪。

### 8.2 H2 / NBT 持久化（对齐 H2_MIGRATION.md）
- 权威在引擎 blob（`MarketEngine.save()/loadInternal()` 的 `engine_kv.engine_main` + NBT 附件镜像双写）。
- 新增 `tag.put("bankCards", bankCardService.save())`；`loadInternal` 用 `contains` 兜底（缺失空表，读档不失败，BT-9）。
- `CompanyData.boundCard` 随公司 blob 持久化 + H2 `company` 表 `ADD COLUMN IF NOT EXISTS bound_card`（`H2DatabaseManager.java:260` 先例，镜像仅查询）。
- 方块级绑定（`boundOwnerUuid/boundCardNumber/boundContractCode` 与输入库存）存**方块实体 NBT**（`saveAdditional/loadAdditional`）；`deliveryBlockRegistry` 读档后由方块实体重新登记（确定性，`loadAdditional` 时注册）。
- 旧存档缺卡注册表/`boundCard`/方块绑定字段：`contains` 兜底默认（空表/空字符串/空库存），读档不失败（BT-9）。

### 8.3 可复现（银行 × 卡 × 方块 × 交割 × SeedableRandom）
- 卡号生成走 `SeedableRandom` 子流（`genCardNumber`，§4.4）；Luhn 校验、门控、方块售货结算、期货实物交割全部为确定性逻辑，无随机。
- **验证**：t5 固定 `engineSeed` 两次运行，卡号/方块售货入账/交割物品与现金补偿序列哈希一致（BT-1）。

### 8.4 与已交付引擎协同（不破坏）
- 不动 A 股 `submitOrder`/`OrderBook`/`SettlementSystem`/`TradingCalendar`/`PriceDiscoveryEngine`、期货 `FuturesMarket` 结算/涨跌停/保证金、商品 `CommodityEntry` 定价、法币 M2/央行公式（文首硬约束）。
- 储蓄卡余额映射到既有 `Bank.depositAccounts` 活期层，`computeM1/M2`（`BankManager.java:100-107`）不回归。
- 方块售货复用 `CommodityEntry.price` + `sellPressure`/`propagateSupply`（不回归 CF-3/CF-4）。
- 期货实物交割是 `deliverExpired` 的**增量分支**（绑约才走），未绑约保持现金结算（不回归 CF-7）。
- 公司 `boundCard` 是 `CompanyData` 增量字段，不动 IPO/股权/分红/退市核心语义。

---

## 9. 网络包 / 前端 / API 协同（BT-3/BT-7/BT-8 可玩性，协同增强非硬性）

- **游戏内网络包**（`network/packets/`）：新增 `CardIssuePacket`（办卡：bankId）、`CardQueryPacket`（查我的卡）、`MarketBlockBindPacket`（绑卡：方块pos）、`DeliveryBlockBindPacket`（绑卡+绑约：方块pos、contractCode）；`ModMessages` 注册；主线程 `enqueueWork` 内执行。
- **`LocalHttpServer` API**（可选新增，以 `/api/session` 绑定玩家身份）：
  - `GET /api/cards` → `[{cardNumber,bankId,ownerUuid,issuedTick,active}]`（绑定玩家）
  - `POST /api/card/issue` body `{bankId}` → `{success,cardNumber,message}`
  - `POST /api/bank/deposit` body `{bankId,amount}` → `{success,balance,message}`
  - `POST /api/bank/withdraw` body `{bankId,amount}` → `{success,balance,message}`
  - 方块绑定/售货查询（`GET /api/marketblock/<pos>`、`GET /api/deliveryblock/<pos>`，可选）
- 写入全部走 `MarketEngine`/`Bank`/`BankCardService`/方块实体确定性方法，与游戏内一致（持卡门控、休市拒绝、Luhn 校验、收款入储蓄账户）。
- **前端**（`FinancialTabletScreen` 银行页/方块交互）为协同增强：办卡表单、卡号展示、存取款、方块绑卡/绑约/余额。展示层零随机、零引擎确定性改动；语义权威在后端。

---

## 10. 参数包与调参约束

集中为 `BankManager`/`BankCardService`/`MarketBlockEntity`/`DeliveryBlockEntity` 内常量（推荐独立 `engine/banking/card/CardParams.java` + `block/TradeBlockParams.java` 常量类，纯 Java，无随机）。默认值与硬约束：

```
// ===== 商业银行 + 储蓄账户（t2） =====
banking.enabled                = true
banking.defaultBankCount       = 4        // BANK_A/BANK_B/BANK_C/BANK_D
// ===== 储蓄卡（t2） =====
card.cardDigits                = 16       // 卡号长度（含 Luhn 校验位）
card.maxCardsPerPlayer         = 1        // 每玩家默认 1 张
card.rngSubstream              = ENGINE_SEED 派生（P0 §6 模式，可播种）
// ===== 持卡门控（t3） =====
gate.requireCardForStock       = true
gate.requireCardForFutures     = true
gate.requireCardForCommodity   = true
gate.requireCardForCompany     = true
// ===== 市场方块（t4） =====
tradeBlock.enabled             = true
tradeBlock.maxSellPerTick      = 64      // 方块每 tick 至多结算物品数（防单 tick 过度）
tradeBlock.creditToSavings     = true    // 收款入储蓄账户（Bank.deposit）
// ===== 交割方块 / 期货实物交割（t4） =====
delivery.physicalWhenBound     = true    // 绑约+绑卡时实物交割；否则现金结算兜底（CF-7）
delivery.priceSource           = LAST_SETTLE   // 交割价 = 最后结算价（prevSettlePrice）
```

- **隔离**：`banking.enabled=false` → 不签发卡、不加门控、不开通存取款/办卡命令（无卡也可交易/建公司，BT-10）；`tradeBlock.enabled=false` → 不注册方块/方块实体、不 tick 售货接入点（BT-10）。两者全关与既有基线逐位一致。
- **收敛性**：储蓄卡余额增加是玩家现金→活期存款的**转移**（M1 总量不变，FM-5 不回归）；方块售货收益入储蓄账户是该玩家经营所得（受 `CommodityEntry.price` 与销量约束，有界）。若现金/储蓄膨胀，优先降 `CommodityEntry` 定价带宽（`COMMODITY_FUTURES_SPEC §9`），不因本模块发散。
- **不引入**：多卡并行/信用卡/透支/跨行转账/存款利息复利（本期不做，见非目标边界）。

---

## 11. 对 t5（整体验证 + 构建验证）的需求

1. **构建验证**：`./gradlew compileJava` 通过（BT-0）；`./gradlew build` 出包。
2. **编译/随机审计**：`grep -rn "Math.random\|new Random\|ThreadLocalRandom" src/main/java` 不得出现银行/卡/方块增量引入的调用（允许既有历史；增量必须用 `SeedableRandom` 或纯确定性，BT-1）。
3. **4 家银行 + 卡签发验证（BT-2/BT-3）**：`BankManager` 默认 4 家；办卡后卡号 16 位、`Luhn.verify` 成立、UUID 唯一绑定；同种子两次办卡卡号一致。
4. **持卡门控验证（BT-4/BT-5）**：无卡玩家 A 股/期货/商品/建公司被拒；有卡放行；合成流不回归；`banking.enabled=false` 无卡可交易。
5. **公司绑卡验证（BT-6）**：创建公司写 `boundCard`；读档恢复。
6. **市场方块验证（BT-7）**：绑卡 + 漏斗输入 → 储蓄账户入账；未绑卡不结算；休市不结算；入账序列两次哈希一致。
7. **交割方块验证（BT-8）**：绑卡+绑约 → 交割日实物交割（多头收物品/空头交付/补偿）、合约 EXPIRED；未绑约现金结算兜底不回归 CF-7。
8. **持久化校验（BT-9）**：跑流程后 save→load，卡注册表/`boundCard`/方块绑定/库存完整恢复；旧档缺字段读档不失败。
9. **隔离验证（BT-10）**：`banking.enabled=false`/`tradeBlock.enabled=false` 回落基线，与现有行为对比证明贡献。
10. **可复现脚本（BT-1）**：固定 `engineSeed` 一键重跑 N tick（跨交易日/交割日边界），卡号 + 方块售货 + 交割序列两次哈希一致。

---

## 12. 实施顺序与依赖（供 scheduler）

```
t1 (本规格) ──→ t2 (4 家银行 + 储蓄账户 + 储蓄卡签发/Luhn/UUID 绑定 + 卡持久化 + BankCommand)
        └──→ t3 (持卡门控: 市场/期货/商品/建公司 + CompanyData.boundCard)
                └──→ t4 (市场方块 + 交割方块 + 期货实物交割接入: BlockInit/BlockEntityInit + MarketBlock/DeliveryBlock + FuturesMarket 实物分支) ──→ t5 (验证) ──→ t6 (评审)
```

- **依赖**：t2 依赖 t1（卡模型/注册表/Luhn/存取款基座）；t3 依赖 t2（门控读 `hasActiveCard`、建公司写 `boundCard`）；t4 依赖 t2（方块绑卡读卡注册表）与 t3（方块收款/建卡一致性）与 `COMMODITY_FUTURES_SPEC`（`CommodityMarket`/`FuturesMarket` 已交付基座）。
- **优先级**：t2（4 家银行 + 储蓄卡体系，BT-2/BT-3）为核心基座（门控与方块绑卡都依赖卡注册表）；t3（持卡门控 + 公司绑卡，BT-4~BT-6）其次；t4（市场/交割方块 + 实物交割，BT-7/BT-8）收口。
- **注**：银行/卡/方块增量新增随机**仅限卡号生成走 `SeedableRandom` 子流**，其余全部确定性；若实现需要任何随机，一律走既有 `SeedableRandom` 派生模式（P0 §6）并登记，否则违反 BT-1。

---

## 13. 与非目标边界（防范围蔓延）

- **不动** A 股 `submitOrder`/`OrderBook`/`SettlementSystem`/`TradingCalendar`/`PriceDiscoveryEngine`、期货 `FuturesMarket` 结算/涨跌停/保证金、商品 `CommodityEntry` 定价、法币 M2/央行/银行派生存款公式（文首硬约束）。
- **不做**：多卡并行（默认 1 张/人）、信用卡/透支/分期、跨行转账、存款利息复利、自动理财、卡失效日期/安全码（CVV）、离线支付、真实银行接口。
- **不做**：商品直接持仓/库存全局供给统计（沿用 `COMMODITY_FUTURES_SPEC`，方块售货只更新既有 `sellPressure`/`propagateSupply`）。
- **前端公司页/银行页/方块交互 UI 为协同增强**，服务端逻辑先行就绪，前端改动不作为 BT-3~BT-8 硬性验收项。
- **方块级绑定存方块 NBT**（非引擎 blob）；引擎 blob 只存跨世界状态（卡注册表/公司 `boundCard`）。不把方块级数据混入 `engine_kv` 避免膨胀。
- 全部分支为 O(玩家)/O(卡)/O(物品)/O(合约) 常数级；方块售货每 tick 至多 `MAX_SELL_PER_TICK` 物品，无 per-tick 线性扫描大集合。

---

## 14. 输入/来源索引

- 团队 Goal：商业银行（4 家默认）+ 储蓄卡（Luhn/UUID 绑定）+ 持卡门控（市场/建公司 + 公司绑卡）+ 贸易方块（市场方块售货收款 + 交割方块实物交割）。
- 已交付基座：`ECON_FIAT_M2_SPEC.md`（法币/M2/银行派生存款）、`A_SHARE_TRADING_SPEC.md`（TradingCalendar/A 股）、`COMMODITY_FUTURES_SPEC.md`（商品/期货/FuturesMarket/CF-7 交割）、`COMPANY_SPEC.md`（CompanyData/AssetScanner/IPO/股权/分红/退市）、`H2_MIGRATION.md`（H2/NBT 双写）、`FRONTEND_THS_SPEC.md`（前端 + LocalHttpServer）。
- 代码证据：`engine/banking/BankManager.java`（`:17-19` 3 家银行、`registerBank`、`saveToNBT/loadFromNBT`、`computeM1/M2` `:100-107`）、`engine/banking/Bank.java`（`depositAccounts` `:19`、`deposit/withdraw` `:81-128`、`getTotalDepositsAll` `:60-74`）、`attachment/PlayerEconomy.java`（`cash/setCash/addCash` `:30-33`、`CODEC` `:73-83`）、`engine/MarketEngine.java`（`bankManager` `:63`、`commodityMarket` `:61`、`futuresMarket` `:118`、`submitOrder` `:1280`、`tick` `:766/:848/:855`、`save` `:1450`、`loadInternal` `:1621`、`getBankManager` `:1405`）、`engine/futures/FuturesMarket.java`（`open/close` `:363/:398`、`canTrade` `:372`、`deliverExpired` `:351-369`、`getOrCreateAccount` `:436`）、`engine/CommodityMarket.java`（`buy/sell/buyFluid/sellFluid` `:84-145`、`canTrade` `:78`）、`engine/futures/FuturesContract.java`（`prevSettlePrice` `:24`、`itemId/item` `:18-19`、`Status` `:14`）、`engine/calendar/TradingCalendar.java`（`isMarketOpen/isTradingDayStart/isSessionEnd` `:79-110`）、`engine/random/SeedableRandom.java`（SplitMix64）、`command/CompanyCommand.java`（`create` `:48-53`）、`init/ItemInit.java`（注册先例）、`attachment/AttachmentInit.java`（attachment 先例）、`FinancialMarket.java`（构造 `:36-39` 注册、`onLevelTick` `:117-124`、`onRegisterCommands` `:126-130`）、`web/LocalHttpServer.java`（`/api/session` `:610-649`、`resolveTradingPlayer` `:457`）、`storage/db/H2DatabaseManager.java`（`CREATE TABLE IF NOT EXISTS` `:130-217`、`ADD COLUMN IF NOT EXISTS` `:246/:260`、`engine_kv` `:212`）、`network/ModMessages.java`（packet 注册先例）。
- 现实卡号逻辑依据（本规格引用的公开标准）：[Luhn 算法（ISO/IEC 7812）](https://en.wikipedia.org/wiki/Luhn_algorithm)（卡号校验位计算与校验）。
