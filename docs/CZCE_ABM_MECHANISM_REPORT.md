# CZCE ABM：涨跌停封板 + 保证金强平机制（2026-11-08）

## 直接回答

| 层 | 涨跌停 | 保证金强平 | 状态 |
|---|---|---|---|
| `CzceFuturesMarket`（mod 市场层） | 有：锚昨结算、连续停板扩板、交割月保证金 | 有：逐日盯市、维持线、`forceClose` | 已有 |
| `TablAbm3`（本次 60 天测试的 ABM） | 之前只有“开盘触板当天不动”，无连续封板状态 | 之前没有 | **本轮补上** |
| 生产 `MultiAgentManager` 的 ABM 决策层 | 仍不等同于交易所撮合层 | 仍不等同 | 待接线 |

所以：**规则层有，ABM 行为层之前不够；这轮把 ABM 的机制性断层补上了。**

## 新增机制

### 1. 连续封板状态机

- 开盘触及涨/跌停且 gap 达限幅 → 进入封板，持续 **1–4 个交易日**。
- 封板日：`open = high = low = close`，盘中不再定价，成交量为 0，做市商深度降到接近 0。
- 连续封板 ≥2 日 → 限幅扩大到 **1.5×**，上限 10%（CZCE 扩板逻辑）。
- 开板后才恢复盘中撮合。

### 2. 保证金强平队列

- 新增 8× 名义敞口的 `FUTURES` 账户；维持保证金 = 名义敞口的 **8%**。
- 权益跌破维持线 → 强平；如果当天封板，强平**排队到开板首日开盘**执行，不能假装在封板中成交。
- 强平流计入开板首日冲击；输出 `forced_liq` 计数。
- `value`/`momentum` 仍按原杠杆，但同样检查维持线。

### 3. 输出字段

`daily_all.csv` 新增：

```text
locked, forced_liq, board_days, futures_eq, futures_pos
```

`moments.csv` 新增：

```text
limit_days, forced_liq, max_board_days
```

## 60 天重跑结果

同一窗口：2025-04-21 → 07-17；50 seeds × 60 天 × 60 step；13 品种逐品种参数。

### 机制触发

| 品种 | 封板天数/seed | 强平次数/seed | 最长连续封板 |
|---|---:|---:|---:|
| AP0 | 2.2 | 0.9 | 1.2 |
| CF0 | 1.8 | 0.5 | 1.2 |
| CZCE_INDEX | 1.9 | 0.4 | 1.2 |
| FG0 | 2.0 | 0.8 | 1.0 |
| MA0 | 2.5 | 0.6 | 1.2 |
| OI0 | 7.6 | 1.0 | 2.7 |
| RM0 | 8.1 | 1.0 | 2.8 |
| SA0 | 9.7 | 1.2 | 3.1 |
| SF0 | 1.1 | 1.2 | 0.6 |
| SM0 | 0.9 | 0.8 | 0.6 |
| SR0 | 2.1 | 0.4 | 1.1 |
| TA0 | 8.5 | 1.0 | 2.8 |
| UR0 | 8.1 | 1.1 | 3.1 |

- 13 品种 × 50 seeds × 60 天 = 39,000 个交易日
- 封板日 **2,825**（约 7.2%）
- 强平 **538** 次
- 最长连续封板 **3.1 天**
- 无破产

### 统计结果

| 品种 | vol | kurt | VR(5) | ACF(1) | 强平/seed |
|---|---:|---:|---:|---:|---:|
| AP0 | 1.92% | 4.20 | 1.199 | 0.108 | 0.9 |
| CF0 | 1.47% | 4.41 | 1.293 | 0.144 | 0.5 |
| CZCE_INDEX | 1.38% | 5.04 | 1.417 | 0.175 | 0.4 |
| FG0 | 1.81% | 3.23 | 1.189 | 0.094 | 0.8 |
| MA0 | 1.76% | 3.49 | 1.289 | 0.129 | 0.6 |
| OI0 | 2.11% | 4.00 | 1.900 | 0.379 | 1.0 |
| RM0 | 2.24% | 3.76 | 2.012 | 0.399 | 1.0 |
| SA0 | 2.51% | 3.06 | 1.942 | 0.409 | 1.2 |
| SF0 | 1.83% | 4.65 | 1.147 | 0.086 | 1.2 |
| SM0 | 1.80% | 5.11 | 1.022 | 0.076 | 0.8 |
| SR0 | 1.46% | 4.96 | 1.369 | 0.139 | 0.4 |
| TA0 | 2.18% | 3.88 | 1.947 | 0.403 | 1.0 |
| UR0 | 2.34% | 3.34 | 1.891 | 0.364 | 1.1 |

## 解读

- 封板/强平把 VR 从上一版的 0.89–1.01 推到 **1.02–2.01**，ACF(1) 从接近 0 推到 **0.08–0.41**。这是机制性断层带来的**正自相关/趋势延续**，不是随机波动。
- 封板越多的品种（OI/RM/SA/TA/UR）VR 越高，说明“封板—排队—开板强平”确实在制造单边延续。
- 代价是：这些品种的 ACF/VR 现在可能偏高；真实 CZCE 的封板延续通常集中在政策/逼仓事件，不是每个 60 天窗口都这么多。
- 波动率仍高于清洗后的真实值（如 SR 1.46% vs 0.85%），因为封板/强平本身会放大已实现波动；下一步要按品种限制封板频率。

## 图

- 机制事件图：[czce_limit_events.png](.verify/czce_abm_2025/charts/czce_limit_events.png)
- 面板统计：[czce_panel_stats.png](.verify/czce_abm_2025/charts/czce_panel_stats.png)
- MA0：[abm_seed1_dashboard.png](.verify/czce_abm_2025/MA0/out/charts/abm_seed1_dashboard.png)
- AP0：[abm_seed1_dashboard.png](.verify/czce_abm_2025/AP0/out/charts/abm_seed1_dashboard.png)
- SR0：[abm_seed1_dashboard.png](.verify/czce_abm_2025/SR0/out/charts/abm_seed1_dashboard.png)
- CZCE 指数：[abm_seed1_dashboard.png](.verify/czce_abm_2025/CZCE_INDEX/out/charts/abm_seed1_dashboard.png)

## 数据

- 机制后汇总：`.verify/czce_abm_2025/czce_summary.csv`
- 逐 seed 机制计数：`.verify/czce_abm_2025/czce_moments.csv`
- 逐日封板/强平：`.verify/czce_abm_2025/<code>/out/daily_all.csv`
- 旧清洗版报告：[CZCE_ABM_10Y_REPORT_CLEAN.md](CZCE_ABM_10Y_REPORT_CLEAN.md)

## 复跑

```powershell
python .verify/tabl_sim/czce_run.py
python .verify/tabl_sim/czce_analyze.py
python .verify/tabl_sim/czce_panel_charts.py
python .verify/tabl_sim/czce_mechanism_chart.py
```

## 边界

1. 封板是**状态机+限幅**，没有真实委托队列/集合竞价队列位置；“排队强平”只保证不在封板中成交。
2. 强平账户是 8× 静态杠杆，没有交易所临时提高保证金、经纪商分层强平。
3. 这是 60 天研究 harness（`.verify/tabl_sim/TablAbm3.java`），还没有把封板/强平状态机接进生产 `CzceFuturesMarket` 的撮合循环。
4. 真实极端肥尾还包含宏观政策突发、仓单/交割逼仓；本轮只补了价格限制和保证金两条机制。
