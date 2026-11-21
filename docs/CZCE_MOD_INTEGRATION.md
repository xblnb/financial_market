# CZCE 接入 mod（默认期货市场）

## 当前状态

- **默认**：`MarketEngine` 使用 `CzceFuturesMarket` 作为期货市场。
- **legacy 回退**：`-Dfinancialmarket.futures.mode=legacy` 使用旧物品期货 `FuturesMarket`。
- 旧实现已备份到 `backup/futures_legacy_20261108/`。
- `./gradlew compileJava`：**BUILD SUCCESSFUL**
- `./gradlew jar`：**BUILD SUCCESSFUL**
- 完整 jar：`build/libs/financialmarket-1.0.0-full.jar`（18,182,291 bytes）

## 已接入

| 面 | 状态 |
|---|---|
| 引擎 tick | CZCE 默认；旧市场仅在 legacy |
| 存档/读档 | H2 `engine_kv.engine_main`（含 CZCE NBT）+ 关系镜像 `czce_contract/account/position/sabr` |
| 玩家账户 | `open(ServerPlayer)` 自动从 `PlayerEconomy` 补足保证金+手续费；平仓清算回钱包 |
| 命令 | `/fm czce status|list|deposit|withdraw|open|close` |
| HTTP 列表 | `/api/futures` 返回 CZCE 合约（旧 UI 字段映射） |
| HTTP 持仓 | `/api/futures/positions` 返回 CZCE 玩家持仓 |
| HTTP 下单 | `/api/futures/order` 走 CZCE |
| 专用 API | `/api/czce` 保留 |
| 期权 API | `/api/options?code=MA2505` 返回 SABR IV 期权链/Greeks |
| 期权台 | `CzceOptionDesk`：每日按标的收益更新 α/ρ/ν，参数进 NBT |
| 期权命令 | `/fm option chain MA2505` |
| 期权库 | `CzceOptionMarket` + `SabrModel`；完整版 jar 内置 finmath-lib + commons-math3 |
| 封板 | 生产市场增加连续封板状态、封板中拒绝成交、开板强平 |
| 强平 | 维持线强平；封板中排队到开板 |

## 玩家使用

```text
/fm czce status
/fm czce list
/fm czce deposit 100000
/fm czce open SR2505 long 2
/fm czce close SR2505 long 1
/fm czce withdraw 50000
/fm option chain MA2505
```

- 开仓时如果 CZCE 账户余额不足，会自动从 `PlayerEconomy` 划入“保证金+手续费”，不需要先手动 deposit。
- 平仓且无持仓时，账户余额会自动划回玩家现金。
- 手动 `deposit/withdraw` 用于主动管理期货账户资金。

## 配置

```text
-Dfinancialmarket.futures.mode=czce     # 默认
-Dfinancialmarket.futures.mode=legacy   # 回退旧物品期货
```

## 兼容性/未完成

1. **旧存档不迁移**：旧 `futures` NBT 仍会保存/读取，但默认模式不会展示旧合约/账户；需要旧数据时切 legacy。
2. **实物交割方块**仍绑定旧 `FuturesMarket`；CZCE 模式没有物品合约，实物交割功能实际不可用。CZCE 当前是现金交割。
3. **杠杆票据的 FUTURES 标的**仍引用旧 `FuturesMarket`；CZCE 模式下该标的不可用，需要后续桥接。
4. **未跑真实服务器 smoke**：目前只有编译和 jar 验证；首次进服建议先备份世界，检查 `/fm czce` 与 `/api/futures`。
5. 生产 CZCE 封板状态机是简化版：没有委托队列/集合竞价位置，强平排队只保证开板后执行。
6. `CzceFuturesMarket` 的玩家账户从 0 开始，旧 10M 沙盒只保留给 QuantConnect 专用账户。

## 回滚

```powershell
# 运行时回退
-Dfinancialmarket.futures.mode=legacy
```

H2 表结构/读档细节：[H2_CZCE_PERSISTENCE.md](H2_CZCE_PERSISTENCE.md)

或将 `backup/futures_legacy_20261108/` 的文件覆盖回原路径（同时改回 `MarketEngine` 调用）。
