# Contributing

## 开发环境

- Java 21
- 使用仓库自带 Gradle wrapper：`./gradlew`
- 不要提交本机 `org.gradle.java.home`；设置 `JAVA_HOME` 即可。

## 提交前

```bash
./gradlew compileJava
./gradlew clean jar
```

如改动前端资源，构建后确认 jar 内资源与源码一致。

## 不要提交

- `runs/`、`build/`、`.gradle/`、`.idea/`；
- 世界存档、MCEF 缓存、日志、崩溃报告；
- `.verify/`、`.models/`、`.data/`、`.agent-teams/`；
- token、私钥、`.env`、服务器密码；
- 生成的大型二进制，除非是明确的第三方依赖并附许可证。

## 第三方组件

新增第三方 jar/模型时：

1. 放到 `third_party/` 对应目录；
2. 在 `THIRD_PARTY_NOTICES.md` 登记来源、版本、许可证；
3. 将许可证文本放到 `third_party/licenses/`；
4. 确认 `build.gradle` 的打包逻辑包含/排除正确。

## 文档

设计/报告放在 `docs/`，截图放在 `docs/assets/`。根目录只保留 README、LICENSE、CONTRIBUTING、SECURITY、CHANGELOG 和构建文件。
