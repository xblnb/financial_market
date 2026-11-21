# 已部署的金融模型与撮合内核（本地、可分发）

本文件记录模组内**自带、可离线运行**的模型/引擎部署层。部署完成即可用，
**尚未接入任何玩法逻辑**（用户指示：先部署，再决定怎么用）。

## 1. 部署清单

| 组件 | 上游 | 形态 | 许可 | 运行方式 |
|---|---|---|---|---|
| **Kronos**（K 线基础模型，AAAI-40） | [shiyu-coder/Kronos](https://github.com/shiyu-coder/Kronos) · [arXiv:2508.02739](https://arxiv.org/abs/2508.02739) | `NeoQuasar/Kronos-mini`（4.1M）+ `Kronos-Tokenizer-base`（3.96M），fp16 | MIT | 纯 Java 前向（`engine/ml`），无原生库 |
| **CoinTossX**（JSE 规则限价簿与撮合引擎） | [dharmeshsing/CoinTossX](https://github.com/dharmeshsing/CoinTossX) | 撮合内核移植（`engine/cointossx`） | MIT | 纯 Java，无 Aeron/SBE/Unsafe |

> 原先计划的 ATOM 没有可分发的开放权重（RBC Borealis 的 Asynchronous Temporal Model 是闭源的，
> `tvdboom/ATOM` 是 Python AutoML 库），按用户指示改为 CoinTossX。

## 2. Kronos（价格序列预测）

### 2.1 打包与体积

```
assets/financialmarket/models/kronos/
├── kronos-mini.safetensors        8,244,666 B   (80 tensors)
├── kronos-tokenizer.safetensors   7,946,293 B   (96 tensors)
└── NOTICE.txt                     许可与 SHA-256 出处
```

原始 fp32 为 32.3 MB，转换 fp16 后 **16.2 MB**（脚本：`tools/kronos/convert_weights.py`）。
jar 从 3.7 MB 增至 **16.6 MB**。

### 2.2 代码结构（`src/main/java/com/example/financialmarket/engine/ml/`）

| 类 | 职责 |
|---|---|
| `Safetensors` | 只读 safetensors（F32/F16/BF16/I64），从 classpath 加载 |
| `MlMath` | linear / rmsNorm / softmax / SiLU / RoPE / 因果注意力 / 交叉注意力 |
| `KronosTransformerBlock` | pre-norm 块（RoPE + SwiGLU），tokenizer 与主模型共用 |
| `KronosTokenizer` | BSQ 分词器：OHLCV → s1/s2 20-bit token 及反向解码 |
| `KronosModel` | Kronos-mini：分层嵌入 + 时间嵌入 + 4 层 transformer + 双头 + 依赖感知交叉注意力 |
| `KronosForecaster` | 对外入口：标准化 → 分词 → 自回归采样 → 解码 → 反标准化 |

### 2.3 调用

```java
KronosForecaster model = KronosForecaster.getInstance();   // 首次加载 ~60 ms
KronosForecaster.Params p = new KronosForecaster.Params(
        1.0f, 0, 0.9f, 1, 42L, 256);   // temperature, topK, topP, sampleCount, seed, context
float[][] future = model.predict(bars, xStamp, yStamp, 12, p);   // [12][6]
```

- `bars`：`[n][6]` = open, high, low, close, volume, amount
- `xStamp` / `yStamp`：`[n][5]` / `[predLen][5]` = minute, hour, weekday(0-6), day(1-31), month(1-12)
- `predict` 为 `synchronized`，**必须**在服务端线程之外调用

### 2.4 实测与验证

- 加载 62 ms；历史 200 根、context=256、预测 12 根 = **4.47 s**（≈373 ms/根，纯 Java 无优化）
- 与独立 NumPy 实现对比：token 流逐位相同；解码误差 ≤ 8.4e-7；logits 误差 ≤ 9.7e-6
- 同 seed 逐位可复现；数值全部有限
- 证据：`.verify/ml_evidence/`；对比脚本 `tools/kronos/compare.py`、`tools/kronos/reference_check.py`

## 3. CoinTossX（限价订单簿 + 价格-时间优先撮合）

### 3.1 为什么是"移植内核"而不是嵌入 jar

上游是 Java 8 多模块 HFT 平台：Aeron 网络、SBE 消息编解码、`sun.misc.Unsafe` 堆外内存、
ObjectLayout/MapDB/HPPC、Socket/Web 网关。直接依赖在 MC 的模块化类加载器和 Java 21 下不可用，
也无法"适合分发"。因此只移植**市场微观结构内核**，其余（会话状态机、行情分发、撮合报告编码）
由模组自身承担。

### 3.2 代码结构（`src/main/java/com/example/financialmarket/engine/cointossx/`）

| 类 | 职责 |
|---|---|
| `CoinTossXEngine` | 对外入口：`submit(order)`、`cancelById(id)`、`book()`、`trades()` |
| `CtOrderBook` | 价格档（`TreeMap`，买降/卖升）+ 档内优先级队列 + best bid/offer + 成交列表 |
| `CtOrder` | 订单（普通字段替代上游 Unsafe 布局，`setQuantity`/`removeQuantity` 语义一致） |
| `CtTrade` | 成交记录 `(id, price, quantity)` |
| `package-info.java` | 许可与移植范围说明 |

**已移植语义**

- 价格优先、同价按**提交时间**排序；同价同时间时**可见单优先于隐藏单**（上游 `timePriorityCompare`）
- 主动单扫簿、市价单按对手价成交、限价单跨界先入簿再扫（`ADD_AND_AGGRESS`），剩余挂簿
- 最小执行量（MES）约束、隐藏单（不参与可见最优价，低于 MRS/MES 自动撤除）
- 止损/止损限价单（parked，最新成交价穿越触发价后转为市价/限价）
- IOC 不挂簿、FOK 不能全成则整单拒绝、撤单

**未移植（明确范围）**：JSE 交易会话状态机（开盘/盘中/波动/收盘集合竞价）、集合竞价 uncross、
熔断会话切换、SBE 执行报告、行情广播、替换单预处理器（上游本身也注释掉了）。

### 3.3 调用

```java
CoinTossXEngine engine = new CoinTossXEngine();
engine.setMrs(stock.getMRS());

CtOrder o = new CtOrder();
o.setSide(CoinTossXEngine.SIDE_BID);              // SIDE_BID / SIDE_OFFER
o.setType(CoinTossXEngine.Type.LIMIT.code());     // MARKET/LIMIT/STOP/STOP_LIMIT/HIDDEN_LIMIT
o.setPrice(10_000);
o.setQuantity(1_000);
o.setSubmittedTime(System.currentTimeMillis());
o.setClientOrderId(42);
engine.submit(o);

List<CtOrder> book = engine.book().snapshot();    // 买降序 + 卖升序，档内按优先级
List<CtTrade> trades = engine.trades();
engine.cancelById(orderId);
```

### 3.4 与上游测试向量对拍

用上游 `MatchingEngine/src/test/resources/crossing/**` 的 CSV 向量回放（与上游
`CrossingProcessorTest` 同样的判定：终态簿 + 成交列表逐项相等）：

| 组 | 通过/总数 | 说明 |
|---|---|---|
| `uncross/limitOrder` | **12/12** | 限价单入簿、同价时间优先、跨价撮合 |
| `cancelOrder` | **10/10** | 撤单与拒撤 |
| `uncross/stopOrder` | **11/12** | 止损触发与选举顺序 |
| `uncross/stopLimitOrder` | **10/12** | 止损限价 |
| `uncross/marketOrder` | **6/6** | 市价单扫簿与剩余作废 |
| `uncross/hiddenOrder` | **9/17** | 隐藏单可见性/优先级已覆盖，主动隐藏单与价格改进的边界未完全对齐 |
| `replaceOrder` | 7/36 | 上游替换单预处理器本身停用，未移植 |
| `auction` / `filterAndUncross` | 2/7 | 集合竞价/过滤 uncross 不属本次范围 |

合计 **67/112 通过**；在"已声明移植范围"内（排除 replace/auction/filterAndUncross）
为 **67/78 ≈ 86%**。已知偏差与上游行为差异逐条记录在
`.verify/ct_evidence/ct_fixture_report.txt`（每条 FAIL 附实际/期望对照）。

复跑：

```powershell
javac -d .verify/ct_classes src/main/java/com/example/financialmarket/engine/cointossx/*.java
javac -cp .verify/ct_classes -d .verify/ct_probe/classes .verify/ct_probe/CtFixtureProbe.java
java -cp ".verify/ct_probe/classes;.verify/ct_classes" CtFixtureProbe `
     .models/_src/CoinTossX-repo/MatchingEngine/src/test/resources/crossing .verify/ct_evidence
```

## 4. 构建与体积

| 项 | 值 |
|---|---|
| `compileJava` | exit 0 |
| class 数 | 307 → **324**（ml 9 + cointossx 8） |
| jar | `financialmarket-1.0.0.jar` 16.6 MB（含 Kronos 权重） |
| 新增外部依赖 | **无**（Kronos 读 safetensors 用游戏自带 Gson；CoinTossX 为纯 JDK） |

## 5. 已知边界

- Kronos 上下文上限 512 根；采样随机（固定 seed 可复现）；fp16 与 fp32 的数值差异远小于采样噪声。
- Kronos 每根 K 线约 0.37 s（context 256）；接入 tick 前建议先加 KV cache。
- CoinTossX 移植版不含集合竞价/会话状态机/替换单；隐藏单的主动方边界行为与上游有偏差。
- 两个组件当前都**未被任何玩法代码调用**，只提供 API 与验证证据。
