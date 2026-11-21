# 第三方资产打包（最终版，2026-11-08）

## 默认完整版

```text
./gradlew clean jar
```

产物：

```text
build/libs/financialmarket-1.0.0-full.jar   22,855,395 bytes
```

内容：

| 资产 | 处理 |
|---|---|
| Kronos fp16 权重（MIT） | `assets/financialmarket/models/kronos/` + NOTICE |
| JQuantLib 0.3.0（BSD-2-Clause） | 类解包到 jar 根 |
| finmath-lib 6.1.10（Apache-2.0） | 类解包到 jar 根 |
| commons-math3 3.6.1（Apache-2.0） | 类解包到 jar 根 |
| 许可证 | `META-INF/licenses/KRONOS-MIT.txt`、`JQUANTLIB-BSD-2-CLAUSE.txt`、`FINMATH-APACHE-2.0.txt`、`COMMONS-MATH-APACHE-2.0.txt` |
| 第三方声明 | `META-INF/THIRD_PARTY_NOTICES.md` |

## 精简版

```text
./gradlew jar -PbundleModels=false -PbundleJQuantLib=false -PbundleFinmath=false
```

产物：

```text
build/libs/financialmarket-1.0.0.jar   1,800,925 bytes（旧版本大小；重新构建以实际为准）
```

- 无 Kronos 权重。
- 无 JQuantLib / finmath 类。
- 期货可回退手写模型；DCF 回退内部曲线；期权 API 在缺 finmath 时不可用。

## Gradle 开关

```groovy
bundleModels      // 默认 true
bundleJQuantLib   // 默认 true
bundleFinmath     // 默认 true
```

源码资产目录：

```text
third_party/models/kronos/
third_party/jquantlib/
third_party/finmath/
```

这些 jar/权重被 `third_party/.gitignore` 忽略，不提交仓库。

## 验证

- `./gradlew compileJava`：**BUILD SUCCESSFUL**
- `./gradlew jar`：**BUILD SUCCESSFUL**
- 完整 jar 内：
  - `.safetensors`：2
  - `org/jquantlib`：1272 条
  - `net/finmath`：存在 `AnalyticFormulas.class`
  - `org/apache/commons/math3`：存在 `NormalDistribution.class`
  - licenses：4 个
- 期权探针：Black-76 call 3.958，IV 回算 0.2000。

## 发布检查

1. 发布 `financialmarket-1.0.0-full.jar`，不要发 lean。
2. 保留 `META-INF/licenses/` 和 `THIRD_PARTY_NOTICES.md`。
3. 不要提交 `third_party/*.jar` 或 `.models/`。
4. 服务器热更新 Kronos：把权重放 `config/financialmarket/models/kronos/`，外部优先。
5. 若上游库升级，替换 `third_party/` 资产并重跑 `jar`。
