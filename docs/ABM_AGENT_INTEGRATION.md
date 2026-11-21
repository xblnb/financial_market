# TABL-ABM Agent 决策接入（2026-11-08）

## 结论

`MultiAgentManager` 的 agent 决策已默认切换为 TABL-ABM 语义：

- **VALUE**：`log(price/fairValue)` 死区 `0.025`，目标仓位 `-1500 × eff`，按 300 步/日渐进调仓。
- **MOMENTUM**：日频再平衡（每日一次），目标 `±200` 股，按杠杆/权益上限截断。
- **RETAIL**：AR(1) 订单流 `flow = 0.2·flow + 0.98·N(0,1)`，按价差决定交易概率，单笔 5–25 股。
- **MARKET_MAKER**：2 档双边报价，半价差随 spread/风险偏好放大，按整手取量。

手写 `valueDecision/retailDecision/momentumDecision/marketMakerQuotes` 仍保留，作为 fallback。

## 开关

```text
-Dfinancialmarket.abm.agents=false
```

关闭后回到手写决策；默认 `true`。

## 代码

- `engine/agent/AbmAgentMath.java`：纯函数 sizing/target，可离线验证。
- `engine/agent/MultiAgentManager.java`：
  - 新增 `abmAgentsEnabled`、`AgentState.lastMomentumDay/retailFlow`；
  - `stepAgents` 在 ABM 模式下调用 `abmDecision` / `abmMarketMakerQuotes`；
  - 原有 switch 决策保留在 `else` 分支。

## 备份

`backup/market_handwritten_20261108/MultiAgentManager.java` 是接入前的完整手写版本；回退时复制回 `src/main/java/com/example/financialmarket/engine/agent/` 即可。

## 验证

- `AbmAgentProbe`：**8/8**（价值买/卖、死区、sizing、step qty、动量 ±、散户数量范围）。
- `TablPriceProbe`：**7/7**。
- `./gradlew compileJava`：**BUILD SUCCESSFUL**。
- 未做真实服务器长跑；ABM MM 目前只发报价流，不更新 MM 存货/现金账本；ABM 模式下会跳过旧 `MarketMakerAgent` 的自动报价流（保留 fallback），存货管理仍需后续补到 ABM MM。
