# Financial Market / Kronos Markets

一个运行在 **Minecraft 1.21.1 / NeoForge 21.1** 上的综合金融市场模拟模组。它把股票、CZCE 期货、SABR 期权、商品市场、公司基本面、世界宏观、玩家产线和银行支付接成一套可交互的经济系统，并提供一套浏览器交易终端。

> 这是游戏模组，不是真实金融产品，也不构成任何投资建议。

## 功能

- **A 股市场**：TABL/Kronos 锚定、订单簿、涨跌停、T+1、集合竞价、ABM 交易者。
- **CZCE 期货**：12 个品种、连续涨跌停板、保证金/强平、封板队列、实物交割。
- **SABR 期权**：Hagan IV、随机参数演化、杠杆效应、多行权价、做市与 Delta/Vega 对冲。
- **商品市场**：自动初始价值定价（配方/稀有度/耐久/食物/mod 命名空间）+ 供需/均值回归/动量/波动聚集/季节性。
- **公司基本面**：玩家公司、IPO、财报、分红、退市；机器/多方块/产线资产估值与动态利用率。
- **宏观层**：世界资源流 → 商品/公司 → 股票/期货/期权；AK 内生增长 + Cobb–Douglas 生产函数；跨资产传导。
- **玩家经济**：银行卡、支付密码、冻结、限额、存取款、转账、商户付款、交易流水。
- **Web 终端**：股票、商品、CZCE、SABR 期权、持仓/订单、宏观、公司、银行卡页面。

## 环境要求

| 依赖 | 版本 |
|---|---|
| Java | 21 |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| MCEF | 2.1.6+（外部必需） |
| Gradle | 8.9（wrapper 已包含） |

MCEF 是外部依赖，分发包 `mods/` 必须包含 `mcef-neoforge-2.1.6-1.21.1.jar`。MCEF 首次运行会下载原生库；离线环境需预置 `mods/mcef-libraries/`。

## 构建

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew clean jar
```

产物：

```text
build/libs/financialmarket-1.0.0-full.jar
```

`build.gradle` 默认启用完整发布：

- `-PbundleModels=true`：打包 Kronos fp16 权重；
- `-PbundleJQuantLib=true`：解包 JQuantLib；
- `-PbundleFinmath=true`：解包 finmath-lib + commons-math3。

Lean 构建（不带第三方二进制）：

```bash
./gradlew clean jar -PbundleModels=false -PbundleJQuantLib=false -PbundleFinmath=false
```

第三方组件与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 运行

```bash
./gradlew runClient
./gradlew runServer
```

默认期货模式为 CZCE。旧版物品期货：

```bash
./gradlew runServer -Dfinancialmarket.futures.mode=legacy
```

本地 Web 终端默认监听 `8080`；Windows 下 loopback 可能是 IPv6，使用 `http://localhost:8080` 或 `http://[::1]:8080`。

## 仓库结构

```text
src/main/java/                 模组源码
src/main/resources/            资源、Web 终端、语言文件
third_party/                   第三方 jar、Kronos 权重、许可证
libs/                          本地 MCEF 依赖
docs/                          设计文档、模型说明、验证报告
docs/assets/                   README/报告截图
```

## 文档

- [模型与实现索引](docs/README.md)
- [银行与支付](docs/BANK_PAYMENT_SYSTEM.md)
- [商品定价](docs/COMMODITY_MARKET_PRICING.md)
- [商品 smoke 报告](docs/COMMODITY_SMOKE_REPORT.md)
- [AK/CD 生产函数](docs/AK_CD_PRODUCTION_MODEL.md)
- [跨资产宏观](docs/CROSS_ASSET_MACRO.md)
- [实物交割](docs/PHYSICAL_DELIVERY.md)
- [产线资产基本面](docs/PRODUCTION_ASSET_FUNDAMENTALS.md)
- [CZCE 期权](docs/CZCE_OPTIONS_60D_REPORT_FINAL.md)
- [前端设计](docs/FRONTEND_REDESIGN.md)

## 开发

```bash
./gradlew compileJava
./gradlew spotlessApply   # 如果已配置格式化插件
```

提交前请不要把以下内容加入 Git：

- `runs/`、`build/`、`.gradle/`、IDE 目录；
- 崩溃日志、JVM `hs_err`/`replay` 文件；
- 本地世界、MCEF 缓存、验证数据；
- 任何 token、密钥、私钥、`.env`。

`.gitignore` 已覆盖这些路径；本地数据仍保留在工作区，不会被删除。

## 许可证

本项目代码采用 **MIT License**，见 [LICENSE](LICENSE)。

Kronos 权重、JQuantLib、finmath-lib、Apache Commons Math 的许可证与分发说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
