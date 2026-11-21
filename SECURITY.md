# Security Policy

## 不要提交敏感数据

本仓库不包含也不接受以下内容：

- 玩家世界、服务器存档、H2 数据库；
- 支付密码、银行卡完整号、私钥、API token、`.env`；
- 服务器 RCON 密码、正版/离线认证凭据；
- 本机 Gradle/JDK 绝对路径。

`.gitignore` 已覆盖常见运行目录与密钥文件。

## 本地 HTTP

模组自带的 Web 终端和本地 API 仅用于本机/受信任环境，默认绑定 loopback。不要把 8080 直接暴露到公网。

## 报告漏洞

请通过 GitHub Security Advisory 或维护者邮箱私下报告，不要在公开 issue 中贴出可复现的账户/支付漏洞细节。
