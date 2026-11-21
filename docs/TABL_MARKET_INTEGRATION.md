# TABL 市场模型接入说明（2026-11-08）

## 结论

- **默认价格路径已切换到 TABL/Kronos 锚点模型**：
  `PriceDiscoveryEngine.step()` 优先调用 `TablPriceModel`，不再先跑手写 GBM/GARCH。
- 手写价格模型完整备份在：
  `backup/market_handwritten_20261108/`
- 手写 GBM/GARCH 分支保留为 fallback：
  `-Dfinancialmarket.tabl.enabled=false` 或锚点未就绪/失效时自动回退。
- 订单簿/执行层保留；agent 决策已切换为 TABL-ABM（见 `ABM_AGENT_INTEGRATION.md`）。
- **agent 决策已切换为 TABL-ABM 语义**（默认）：VALUE 死区锚定、MOMENTUM 日频再平衡、RETAIL AR(1) 订单流、MM 2 档趋势偏斜报价；手写决策保留 fallback。

## 改动

| 文件 | 改动 |
|---|---|
| `engine/tabl/TablPriceModel.java` | 新增：TABL 日锚 → 日内插值 + 确定性桥噪声 + ±10% 限价；Kronos 异步预热 |
| `engine/tabl/TablIntradayMath.java` | 新增：纯函数日内路径，供离线探针 |
| `engine/tabl/TablAnchorManager.java` | 新增 `refreshDailyAsync` / `anchorIfReady`，Kronos 在 `tabl-anchor-prewarm` 守护线程执行，主线程不阻塞 |
| `engine/PriceDiscoveryEngine.java` | 新增 `TablPriceModel` 字段/开关；`step()` 优先 TABL，锚点无效才落到 GBM/GARCH |
| `engine/MarketEngine.java` | 构造期注入 `new TablPriceModel(seed)`；AI/玩家公司历史 K 线从 60 根提升到 256 根，满足锚点 lookback |

## 备份

`backup/market_handwritten_20261108/`：

- `PriceDiscoveryEngine.java`
- `MultiAgentManager.java`
- `OrderFlowModel.java`
- `MarketSentiment.java`
- `AssetSnapshot.java`
- `README.md`（回退步骤）

回退时按 README 复制回原路径，并删除 `setTablModel` 接线或设置 `-Dfinancialmarket.tabl.enabled=false`。

## 行为

1. 日初：`TablPriceModel` 取最近 256 根日 K，异步调用 Kronos 生成 60 日 `AnchorPath`。
2. 日内：`close[0]` 作为当日锚，从昨收线性插值到当日锚，加 Brownian-bridge 形状噪声。
3. 限价：目标价钳制在昨收 ±10%；锚点 `stale` 或未就绪时返回 `NaN`，由 `PriceDiscoveryEngine` 回退 GBM/GARCH。
4. 订单簿/agent 仍围绕 `AssetSnapshot.lastPrice` 交易；TABL 只提供基本面路径。
5. ABM agent 模式默认开启；`-Dfinancialmarket.abm.agents=false` 回退手写 VALUE/RETAIL/MOMENTUM/MM 决策。

## 验证

- `./gradlew compileJava`：**BUILD SUCCESSFUL**。
- `TablPriceProbe`：**7/7**（开盘=昨收、收盘=锚、涨跌停钳制、单调、确定性、噪声有界）。
- 此前的宏观/持久化/原生画像探针仍通过。
- **未做**真实服务器长跑；Kronos 模型文件缺失时会自动回退 GBM，需在目标整合包确认模型资源可用。
