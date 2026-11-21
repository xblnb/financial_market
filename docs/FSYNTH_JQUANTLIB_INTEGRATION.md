# fsynth 基本面 + JQuantLib Agent 分析接入（2026-11-08）

## 结论

- **公司基本面生成**已改为 fsynth 风格的 **Heston 方差 + 跳跃扩散**季度冲击，落在
  `AICompanyData.updateFundamentals`，替代原先的裸随机增长/净利率/周转率抽样。
- **Agent 基本面分析**通过 `JQuantLibBridge` 接入 JQuantLib 的 `InterestRate` 折现曲线；
  `AICompanyData.computeFairValue` 的 DCF 项改由 JQuantLib 计算 5 年显式 FCF + 终值。
- Agent 侧继续读 `AICompanyData.getFairValue()`，因此 ABM VALUE / 动量 / 散户的定价锚自动使用
  新的 fsynth 基本面 + JQuantLib DCF。

## fsynth 部分

fsynth 本身是合成价格数据生成器（Heston 随机波动 + 跳跃扩散），不是现成的“公司财务面板”库。
这里**借鉴其模型结构**，用 Java 实现 `FsynthFundamentals`：

- 均值回归方差：`v_{t+1}=v+κ(θ−v)dt+ξ√(v·dt)Z`
- 3% 概率跳跃，冲击增长/净利率
- 输出 `revenueGrowth/netMargin/turnover/equityMultiplier`
- 同种子逐位可复现，无 wall clock

接入点：
`AICompanyData.updateFundamentals()` 里原来 4 次 `rng.nextDouble()` 由 `fsynth.next()` 替代；
宏观/行业/通胀 overlay 保留。

## JQuantLib 部分

- 完整版默认把 `third_party/jquantlib/jquantlib-core-0.3.0.jar` 的类解包进 mod jar 根；
  精简版不打包，运行时外部提供。
- `JQuantLibBridge` 用反射加载，避免编译期硬依赖：
  - `org.jquantlib.termstructures.InterestRate`
  - `org.jquantlib.daycounters.Actual365Fixed` / `DayCounter`
  - `discountFactor(double)`
- `AICompanyData.computeFairValue()` 的 DCF：
  - 有 JQuantLib → 5 年显式 FCF + Gordon 终值，用 JQuantLib 折现因子；
  - 无 jar → 等价内部折现曲线，保证不崩。
- `JQuantLibBridge.analyze(...)` 同时给出 `dcfPerShare/npv/irr/source`，供后续 agent 分析面板/策略扩展。

## 验证

- `FsynthProbe`：**5/5**（确定性、增长/净利率/周转率范围、序列有变化）。
- `JQuantLibProbe`：**4/4**（jar 可用、DCF 有限、source=jquantlib、NPV>0）。
- `MacroStockProbe`：**7/7**（宏观→fairValue 因子、价格 tilt、spread/depth 边界）。
- `AbmAgentProbe`：**8/8**；`TablPriceProbe`：**7/7**。
- `./gradlew compileJava`：**BUILD SUCCESSFUL**。

## 注意

1. **JQuantLib 打包**：完整版默认解包进 mod jar 根（BSD-2-Clause，附 `THIRD_PARTY_NOTICES.md`）；精简版不打包，外部提供。若 JQuantLib 类加载失败，`JQuantLibBridge` 自动回退内部 DCF，不会崩。
2. **DCF 公式变化**：从“增长永续”改为“5 年显式 + 终值”，fairValue 会变，需要重新跑一轮校准。
3. **fsynth 是模型借鉴**，不是直接调用 Python 包；若你要严格使用 fsynth 生成的财务面板，需要额外做离线 JSON 导入适配。
4. 未做真实服务器长跑。

参考：
- [fsynth (GitHub)](https://github.com/welcra/fsynth)
- [JQuantLib core 0.3.0 (Maven)](https://mvnrepository.com/artifact/co.dv01.jquantlib/core/0.3.0)
