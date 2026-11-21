# CZCE 十年数据 → 60 天 ABM 测试（2026-11-08）

## 结论

- 已拉取 **郑商所 13 个主力连续品种 2016–2025 十年日线**，并构建动态等权 CZCE 指数。
- 训练了 **逐品种 ridge 锚点模型**（只用 2024-12-15 前数据，无测试泄漏），生成 2025-04-21→07-17 的 60 天锚点。
- 用同一套 TABL-ABM（50 seeds × 60 天 × 60 step）跑 12 个有 2025 数据的品种 + CZCE 指数；ZC 因 2022 年后无数据跳过。
- 结果：波动率经一轮校准后接近真实；**峰度仍普遍低于真实十年**（尤其 AP/SM），说明尾部/跳跃不足；VR 接近 1，杠杆接近 0，无破产。
- **没有对 Kronos 权重做 fine-tune**；这里“训练”指 CZCE ridge 锚点模型 + ABM 波动率校准。Kronos 仍是预训练模型。

## 数据

来源：AKShare `futures_main_sina` 主力连续；保存到 `.data/czce/`。

| 品种 | 代码 | 起始 | 行数 |
|---|---|---|---|
| 白糖 | SR0 | 2016-01 | 2427 |
| 棉花 | CF0 | 2016-01 | 2425 |
| PTA | TA0 | 2016-01 | 2426 |
| 甲醇 | MA0 | 2016-01 | 2424 |
| 玻璃 | FG0 | 2016-01 | 2424 |
| 纯碱 | SA0 | 2019-12 | 1472 |
| 动力煤 | ZC0 | 2016-01 | 1611（止于 2022） |
| 苹果 | AP0 | 2017-12 | 1942 |
| 菜粕 | RM0 | 2016-01 | 2427 |
| 菜油 | OI0 | 2016-01 | 2424 |
| 尿素 | UR0 | 2019-08 | 1551 |
| 硅铁 | SF0 | 2016-01 | 2414 |
| 锰硅 | SM0 | 2016-01 | 2422 |
| 等权指数 | CZCE_INDEX | 2016-01 | 2427 |

## 训练

`czce_train.py`：

- 特征：1/2/5/10/20 日收益、20/60 日波动、振幅、收盘位置、成交量 z、持仓量 z、基差、MA 偏离。
- 目标：未来 1–10 日累计收益。
- 训练集：2016-01 至 2024-12-15；测试：2025-04-21 至 2025-07-17（60 个交易日）。
- 逐品种 ridge（λ=10），输出 10 档锚点；测试日只用 t-1 特征。
- 指标：`.verify/czce_abm_2025/czce_model_metrics.csv`

### 锚点模型 IC（h=1）

| 品种 | R²(train) | IC(test) | 方向准确率 |
|---|---:|---:|---:|
| AP0 | 0.0186 | +0.1712 | 0.483 |
| CF0 | 0.0085 | −0.0722 | 0.400 |
| CZCE_INDEX | 0.0057 | +0.0183 | 0.533 |
| FG0 | 0.0085 | +0.1843 | 0.583 |
| MA0 | 0.0070 | +0.0356 | 0.517 |
| OI0 | 0.0018 | +0.2877 | 0.533 |
| RM0 | 0.0046 | −0.1070 | 0.500 |
| SA0 | 0.0164 | −0.1767 | 0.383 |
| SF0 | 0.0199 | +0.0687 | 0.417 |
| SM0 | 0.0255 | −0.1311 | 0.500 |
| SR0 | 0.0042 | −0.0040 | 0.383 |
| TA0 | 0.0033 | −0.0226 | 0.550 |
| UR0 | 0.0140 | +0.1671 | 0.450 |

> R² 很低，IC 符号不稳定；这是 60 天样本的实际情况，不应包装成“稳定 alpha”。

## ABM 结果（50 seeds 均值）

| 品种 | 波动 | 峰度 | VR(5) | 量价相关 | 杠杆 | 破产率 |
|---|---:|---:|---:|---:|---:|---:|
| AP0 | 2.16% | 3.23 | 0.941 | 0.564 | −0.026 | 0 |
| CF0 | 1.52% | 3.73 | 0.907 | 0.515 | −0.034 | 0 |
| CZCE_INDEX | 1.32% | 4.67 | 0.962 | 0.470 | −0.018 | 0 |
| FG0 | 1.91% | 2.83 | 0.947 | 0.520 | −0.027 | 0 |
| MA0 | 1.72% | 3.13 | 0.973 | 0.514 | −0.022 | 0 |
| OI0 | 1.51% | 3.74 | 0.925 | 0.509 | −0.030 | 0 |
| RM0 | 1.76% | 3.17 | 0.939 | 0.518 | −0.016 | 0 |
| SA0 | 2.17% | 2.32 | 0.925 | 0.506 | −0.018 | 0 |
| SF0 | 2.03% | 4.41 | 0.931 | 0.553 | −0.033 | 0 |
| SM0 | 1.90% | 4.70 | 1.018 | 0.523 | −0.011 | 0 |
| SR0 | 1.28% | 4.96 | 0.929 | 0.445 | −0.016 | 0 |
| TA0 | 1.60% | 3.42 | 0.976 | 0.510 | −0.043 | 0 |
| UR0 | 1.79% | 3.14 | 0.874 | 0.540 | −0.010 | 0 |

真实十年对照（`.verify/czce_abm_2025/czce_real_stats.csv`）：

- AP0：vol 2.04%、kurt 67.05
- SM0：vol 1.84%、kurt 21.60
- SR0：vol 0.87%、kurt 5.37
- MA0：vol 1.70%、kurt 2.96
- CZCE_INDEX：vol 0.84%、kurt 3.03

**问题**：AP/SM 这种十年峰度极高的品种，ABM 仍只有 3–5，尾部明显偏薄；SR/指数的 ABM 波动仍高于真实。ABM 只按 10 年波动做了一轮校准，没有对峰度/跳跃做品种级拟合。

## 图

- 面板对比：[czce_panel_stats.png](assets/czce_abm_2025/charts/czce_panel_stats.png)
- 甲醇 MA0：[abm_seed1_dashboard.png](assets/czce_abm_2025/MA0/out/charts/abm_seed1_dashboard.png)
- 苹果 AP0：[abm_seed1_dashboard.png](assets/czce_abm_2025/AP0/out/charts/abm_seed1_dashboard.png)
- 白糖 SR0：[abm_seed1_dashboard.png](assets/czce_abm_2025/SR0/out/charts/abm_seed1_dashboard.png)
- CZCE 指数：[abm_seed1_dashboard.png](assets/czce_abm_2025/CZCE_INDEX/out/charts/abm_seed1_dashboard.png)
- 真实 MA/AP/SR/指数：各目录 `out/charts/real_2025_*.png`

## 数据

- 原始十年 CSV：`.data/czce/*.csv`（13 品种 + 指数）
- 锚点：`.verify/czce_abm_2025/<code>/anchors.csv`
- 每日仿真：`.verify/czce_abm_2025/<code>/out/daily_all.csv`
- 指标明细：`.verify/czce_abm_2025/<code>/out/indicators_abm_seed1.csv`
- 模型指标：`.verify/czce_abm_2025/czce_model_metrics.csv`
- 汇总：`.verify/czce_abm_2025/czce_summary.csv`
- 真实对照：`.verify/czce_abm_2025/czce_real_stats.csv`
- 指标 IC/信号：各 `out/indicator_stats.csv`、`indicator_signals.csv`

## 复跑

```powershell
python .verify/tabl_sim/czce_fetch.py
python .verify/tabl_sim/czce_index.py
python .verify/tabl_sim/czce_train.py
python .verify/tabl_sim/czce_run.py
python .verify/tabl_sim/czce_analyze.py
python .verify/tabl_sim/czce_panel_charts.py
```

## 下一步（如果要更真实）

1. 按品种拟合跳跃频率/幅度/DF，把 AP/SM 等峰度拉近真实；否则 60 天测试会低估尾部风险。
2. 使用 CZCE 官方最后交易日/节假日/夜盘细分，替换 30 天月 + 15 日近似。
3. 如果要严格“训练 Kronos”，需要 PyTorch/GPU fine-tune 流程；当前没有做，不能声称已 fine-tune。
