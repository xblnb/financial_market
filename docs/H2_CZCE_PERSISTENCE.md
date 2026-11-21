# CZCE 数据 H2 持久化

## 结论

H2 现在同时保存 CZCE 的**完整状态 blob** 和**关系化镜像**：

- `engine_kv.engine_main`：整个 `MarketEngine.save()` NBT 的压缩 Base64，包含
  `czceFutures` / `czceOptions`；这是读档权威来源。
- `czce_contract` / `czce_account` / `czce_position` / `czce_sabr`：关系化镜像，便于 SQL 查询、
  对账和外部工具读取。

NBT 世界附件仍保留作为降级镜像：H2 不可用或首次迁移时才回退。

## 表

```sql
czce_contract(
  contract_code PK, product, delivery_month, last_trading_tick,
  prev_settle, last_price, limit_up, limit_down, margin_ratio,
  status, consecutive_limit_days, open_interest, day_volume, day_notional,
  limit_locked, board_days_left, board_decrement_day
)

czce_account(
  account_id PK, cash, margin, pending_force
)

czce_position(
  account_id, contract_code, long_lots, short_lots, avg_long, avg_short,
  PRIMARY KEY(account_id, contract_code)
)

czce_sabr(
  product PK, alpha, beta, rho, nu, base_alpha, base_rho, base_nu, last_day
)
```

## 写入链路

```text
ServerStopping / saveEngine
  → MarketEngine.saveToH2()
    → engine_kv.engine_main = Base64(NBT.gz(save()))
    → saveRelationsToH2(c)
      → CzceFuturesMarket.saveToH2(c)
          DELETE czce_contract / czce_position
          INSERT contracts, MERGE accounts, INSERT positions
      → CzceOptionDesk.saveToH2(c)
          DELETE czce_sabr, INSERT SABR params
```

- 服务器线程单连接，所有 SQL 在 `saveToH2` 内同步完成。
- 关系化镜像每次覆盖（合约/持仓/SABR），账户 MERGE；不做增量 diff，数据量小。

## 读档链路

```text
MarketAttachment.load
  → MarketEngine.loadFromH2()
    → engine_kv.engine_main → NBT
      → MarketEngine.loadInternal()
        → czceFutures.load()
        → czceOptions.load()
```

关系表不参与读档，避免“blob 与镜像不一致时以谁为准”的歧义。需要分析时直接查表。

## 验证

- `./gradlew compileJava`：**BUILD SUCCESSFUL**
- 新增 H2 DDL 使用 `CREATE TABLE IF NOT EXISTS`，旧库自动补表。
- 尚未做真实服务器重启 smoke；首次上线建议备份世界并检查：
  ```sql
  SELECT COUNT(*) FROM czce_contract;
  SELECT COUNT(*) FROM czce_account;
  SELECT * FROM czce_sabr;
  ```
