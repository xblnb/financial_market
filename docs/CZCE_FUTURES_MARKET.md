# CZCE 商品期货市场接入（2026-11-08）

## 已实现

新增独立 `engine/futures/czce`：

- **品种规格表**：SR/CF/TA/MA/FG/SA/ZC/AP/RM/OI/UR/SF/SM，含每手乘数、最小变动、保证金、涨跌停、手续费、限仓、夜盘标记。
- **合约日历**：按 6000 tick/交易日派生 `YYMM` 合约（如 `SR2505`），上市近 6 个月。
- **CZCE 规则**：
  - 涨跌停锚昨结算，连续 2 日停板后扩板 + 提高保证金；
  - 日盘/夜盘时段门控；
  - 逐日盯市、保证金重算、维持线强平；
  - 到期现金交割（按现货锚）；
  - 每手手续费、限仓、开/平仓。
- **JQuantLib 定价**：`JQuantLibFuturesPricer` 用持有成本
  `F = S·exp((storage−convenience)T)/discountFactor(r,T)`，完整版 jar 内置 JQuantLib，精简版回退内部折现。
- **QuantConnect 桥**：`QuantConnectBridge` 读取
  `config/financialmarket/quantconnect/czce_targets.json`，把 LEAN/外部算法的目标手数转成 CZCE 市场单（专用 `quantconnect` 账户）。
- **入口**：
  - `MarketEngine` 每 20 tick 调 `CzceFuturesMarket.tick(...)`，现货优先取 `CommodityMarket` 对应 item 价格；
  - `GET /api/czce` 返回合约/盘口/quantconnect 账户；
  - `/fm czce status|list|open|close`。

## 品种参数（简化版）

| 代码 | 品种 | 乘数 | 最小变动 | 保证金 | 涨跌停 | 手续费/手 | 夜盘 |
|---|---|---:|---:|---:|---:|---:|---|
| SR | 白糖 | 10 | 1 | 7% | 4% | 3 | 是 |
| CF | 棉花 | 5 | 5 | 7% | 4% | 4.3 | 是 |
| TA | PTA | 5 | 2 | 7% | 4% | 3 | 是 |
| MA | 甲醇 | 10 | 1 | 7% | 4% | 2 | 是 |
| FG | 玻璃 | 20 | 1 | 9% | 4% | 6 | 是 |
| SA | 纯碱 | 20 | 1 | 9% | 4% | 3.5 | 是 |
| ZC | 动力煤 | 100 | 0.2 | 10% | 4% | 4 | 是 |
| AP | 苹果 | 10 | 1 | 10% | 5% | 5 | 否 |
| RM | 菜粕 | 10 | 1 | 7% | 4% | 1.5 | 是 |
| OI | 菜油 | 10 | 1 | 7% | 4% | 2.5 | 是 |
| UR | 尿素 | 20 | 1 | 7% | 4% | 5 | 是 |
| SF | 硅铁 | 5 | 2 | 9% | 6% | 3 | 否 |
| SM | 锰硅 | 5 | 2 | 9% | 6% | 3 | 否 |

> 这是规则级近似，不是交易所官方参数表；上线前请按目标年份的 CZCE 公告校准。

## QuantConnect 桥格式

```json
{"account":"quantconnect","targets":[
  {"symbol":"SR2505","lots":2},
  {"symbol":"CF2505","lots":-1}
]}
```

- 路径：`config/financialmarket/quantconnect/czce_targets.json`
- 可覆盖：`-Dfinancialmarket.quantconnect.czce.targets=/path/file.json`
- 外部 LEAN 算法只需写目标手数；mod 侧按 net target 调仓。
- 不是嵌入 .NET LEAN 运行时，而是进程外桥接。

## 验证

- `CzceProbe`：**12/12**（品种/合约、开仓保证金、逐日结算、非交易时段拒绝、QuantConnect 目标、JQuantLib 公平价）。
- `./gradlew compileJava`：**BUILD SUCCESSFUL**。
- JQuantLib 完整版 jar 已包含 `org/jquantlib`；精简版自动回退。

## 边界

1. 交易日历/合约最后交易日用 30 天月 + 每月 15 日近似，未实现交易所节假日/最后交易日表。
2. 交割为现金结算，未实现仓单、质检、升贴水、交割库。
3. 交易时段在 6000 tick/日上压缩，未细分 09:00–11:30/13:30–15:00 午休。
4. 玩家账户是 10M 沙盒账本，尚未与 `PlayerEconomy` 现金/银行账户打通。
5. CZCE 状态未进 `MarketEngine.save()`；重启会重建合约/账户。
