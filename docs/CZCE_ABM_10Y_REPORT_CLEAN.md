# CZCE 十年数据 → 60 天 ABM 测试（清洗版，2026-11-08）

## 这轮修了什么

1. **发现并修复换月伪影**：`futures_main_sina` 是未做换月调整的主力连续序列。AP 原始十年出现 +35%/−35% 日收益，超过郑商所 5% 限价，属于合约拼接跳空，不是真实行情。
2. 新增 `czce_clean.py`：按品种限价裁剪日收益并重建 OHLC，原始文件保留在 `.data/czce/`，清洗文件在 `.data/czce_clean/`。
3. 给 ABM 增加可校准尾部参数：`tabl.df`、`tabl.shockProb`、`tabl.jumpMin`、`tabl.jumpRange`。
4. 每个品种搜索 4 组尾部 regime，并把波动率校准到十年真实值；取最优后重跑 60 天 × 50 seeds。
5. **仍然没有 fine-tune Kronos**；“训练”指逐品种 ridge 锚点模型 + ABM 校准。

## 数据

13 个 CZCE 主力连续品种 2016–2025：

| 品种 | 代码 | 起始 | 行数 |
|---|---|---|---|
| 白糖 | SR0 | 2016-01 | 2427 |
| 棉花 | CF0 | 2016-01 | 2425 |
| PTA | TA0 | 2016-01 | 2426 |
| 甲醇 | MA0 | 2016-01 | 2424 |
| 玻璃 | FG0 | 2016-01 | 2424 |
| 纯碱 | SA0 | 2019-12 | 1472 |
| 动力煤 | ZC0 | 2016-01 | 1611（止于 2022，只训练不测试） |
| 苹果 | AP0 | 2017-12 | 1942 |
| 菜粕 | RM0 | 2016-01 | 2427 |
| 菜油 | OI0 | 2016-01 | 2424 |
| 尿素 | UR0 | 2019-08 | 1551 |
| 硅铁 | SF0 | 2016-01 | 2414 |
| 锰硅 | SM0 | 2016-01 | 2422 |
| 等权指数 | CZCE_INDEX | 2016-01 | 2427 |

## 训练与测试

- 特征：1/2/5/10/20 日收益、20/60 日波动、振幅、收盘位置、量/持仓 z、基差、MA 偏离。
- 目标：未来 1–10 日累计收益。
- 训练：2016-01 至 2024-12-15；测试：**2025-04-21 → 2025-07-17，60 个交易日**。
- 逐品种 ridge（λ=10），只用 t-1 特征生成锚点。
- ABM：50 seeds × 60 天 × 60 step，按品种设置 4%/5%/6% 涨跌停。

### 锚点模型（清洗后）

| 品种 | R²(train) | IC(test) | 方向准确率 |
|---|---:|---:|---:|
| AP0 | 0.0105 | +0.0338 | 0.433 |
| CF0 | 0.0065 | −0.0234 | 0.383 |
| CZCE_INDEX | 0.0055 | +0.0210 | 0.533 |
| FG0 | 0.0106 | +0.2514 | 0.633 |
| MA0 | 0.0072 | −0.0415 | 0.483 |
| OI0 | 0.0031 | +0.3362 | 0.633 |
| RM0 | 0.0048 | −0.0654 | 0.433 |
| SA0 | 0.0156 | −0.1014 | 0.400 |
| SF0 | 0.0145 | −0.0180 | 0.400 |
| SM0 | 0.0159 | −0.2215 | 0.433 |
| SR0 | 0.0048 | −0.0077 | 0.433 |
| TA0 | 0.0042 | −0.0950 | 0.583 |
| UR0 | 0.0136 | +0.1771 | 0.483 |

R² 仍然很低，IC 不稳定；OI/FG 正 IC 不能当成稳定 alpha。

## ABM 结果（清洗后，50 seeds 均值）

| 品种 | ABM vol | ABM kurt | 真实 vol | 真实 kurt | VR(5) | 破产率 |
|---|---:|---:|---:|---:|---:|---:|
| AP0 | 1.80% | 3.89 | 1.59% | 1.92 | 0.916 | 0 |
| CF0 | 1.42% | 4.28 | 1.20% | 2.16 | 0.952 | 0 |
| CZCE_INDEX | 1.31% | 4.94 | 0.84% | 2.48 | 0.939 | 0 |
| FG0 | 1.77% | 3.10 | 1.65% | 0.38 | 0.956 | 0 |
| MA0 | 1.76% | 3.06 | 1.57% | 0.34 | 0.961 | 0 |
| OI0 | 1.48% | 3.91 | 1.21% | 1.39 | 0.938 | 0 |
| RM0 | 1.59% | 3.41 | 1.47% | 0.66 | 1.005 | 0 |
| SA0 | 1.97% | 2.71 | 1.92% | −0.22 | 0.909 | 0 |
| SF0 | 2.07% | 4.71 | 1.71% | 2.44 | 0.974 | 0 |
| SM0 | 1.94% | 4.69 | 1.63% | 2.97 | 0.893 | 0 |
| SR0 | 1.28% | 4.98 | 0.85% | 2.68 | 0.903 | 0 |
| TA0 | 1.66% | 3.45 | 1.44% | 0.95 | 0.930 | 0 |
| UR0 | 2.01% | 2.97 | 1.63% | 0.49 | 0.959 | 0 |

## 结论

- **换月伪影是上一版“AP 峰度 67 vs ABM 3.2”的主因**；清洗后 AP 真实峰度 1.92，ABM 3.89，差距回到同一量级。
- 当前 ABM 峰度 2.7–5.0，整体不再系统性偏薄，部分品种（AP/CF/index/SR）反而略厚；这是尾部 regime 搜索的结果。
- **波动率仍偏高**：SR 1.28% vs 0.85%，指数 1.31% vs 0.84%；ABM 的跳跃/流动性底噪在低波动品种上有下限，继续压 sigma 也压不下去。这是下一轮要修的模型结构问题。
- VR 0.89–1.01，接近随机游走；杠杆接近 0；无破产。
- 锚点模型 IC 不稳定，不能据此声称有可交易 alpha。

## 图

- 面板对比：[czce_panel_stats.png](.verify/czce_abm_2025/charts/czce_panel_stats.png)
- 甲醇 MA0：[abm_seed1_dashboard.png](.verify/czce_abm_2025/MA0/out/charts/abm_seed1_dashboard.png)
- 苹果 AP0：[abm_seed1_dashboard.png](.verify/czce_abm_2025/AP0/out/charts/abm_seed1_dashboard.png)
- 白糖 SR0：[abm_seed1_dashboard.png](.verify/czce_abm_2025/SR0/out/charts/abm_seed1_dashboard.png)
- CZCE 指数：[abm_seed1_dashboard.png](.verify/czce_abm_2025/CZCE_INDEX/out/charts/abm_seed1_dashboard.png)

## 数据

- 原始十年：`.data/czce/*.csv`
- 清洗后十年：`.data/czce_clean/*.csv`
- 锚点：`.verify/czce_abm_2025/<code>/anchors.csv`
- 每日仿真：`.verify/czce_abm_2025/<code>/out/daily_all.csv`
- 模型指标：`.verify/czce_abm_2025/czce_model_metrics.csv`
- 汇总：`.verify/czce_abm_2025/czce_summary.csv`
- 真实对照：`.verify/czce_abm_2025/czce_real_stats.csv`

## 复跑

```powershell
python .verify/tabl_sim/czce_fetch.py
python .verify/tabl_sim/czce_index.py
python .verify/tabl_sim/czce_clean.py
python .verify/tabl_sim/czce_train.py
python .verify/tabl_sim/czce_run.py
python .verify/tabl_sim/czce_analyze.py
python .verify/tabl_sim/czce_panel_charts.py
```

## 下一步

1. 修低波动品种的 vol 下限：把跳跃/流动性 shock 改为按品种目标波动缩放，而不是固定倍数。
2. 对 AP/SM 这类限价频繁触发的品种，单独建模“触板/扩板”机制，而不是用连续收益裁剪。
3. 若要严格“训练 Kronos”，需要 PyTorch/GPU fine-tune；当前没有做。
