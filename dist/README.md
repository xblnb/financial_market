# Financial Market 1.0.0 — full release

## 文件

```text
financialmarket-1.0.0-full.jar   22,864,893 bytes
SHA256SUMS.txt                    SHA-256 checksum
```

校验：

```bash
sha256sum -c SHA256SUMS.txt
# 4d17a54c61eac9e8e0e427220391a9f5792e1040e67af408dddf13bed515def6
```

## 安装

1. Minecraft **1.21.1** + NeoForge **21.1.x**。
2. 把 `financialmarket-1.0.0-full.jar` 放入 `mods/`。
3. **同时安装 MCEF 2.1.6+**（`mods/mcef-neoforge-2.1.6-1.21.1.jar`）。本 mod jar 不解包 MCEF；MCEF 是 LGPL-2.1 外部依赖。
4. MCEF 首次运行会下载 Chromium 原生库；离线环境需预置 `mods/mcef-libraries/`。

## 内容

- A 股市场、TABL/Kronos、ABM 交易者；
- CZCE 期货、保证金/强平、连续封板、实物交割；
- SABR 期权链、做市与 Delta/Vega 对冲；
- 商品自动定价与 60 天价格动态；
- 公司 IPO/财报/分红、产线资产估值；
- AK + Cobb–Douglas 宏观生产函数、跨资产传导；
- 银行卡/支付/转账/商户付款；
- Web 交易终端。

## 许可证

- 本 mod 代码：MIT，见仓库根目录 `LICENSE`。
- 完整 jar 内含第三方组件与许可证：
  - Kronos 权重：MIT；
  - JQuantLib：BSD-2-Clause；
  - finmath-lib / Commons Math：Apache-2.0；
  - MCEF：LGPL-2.1（外部依赖，不随 mod jar 解包，许可证文本随 jar 提供）。

完整声明见仓库 `THIRD_PARTY_NOTICES.md` 和 jar 内 `META-INF/THIRD_PARTY_NOTICES.md`。

源码：https://github.com/xblnb/financial_market
