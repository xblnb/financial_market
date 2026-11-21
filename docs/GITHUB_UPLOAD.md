# GitHub 上传准备

当前仓库已整理为干净的 `main` 分支：412 个文件、约 28.5MB，不包含 `runs/`、`build/`、`.verify/`、`.idea/`、日志或缓存。

## 远程

```text
origin  https://github.com/xblnb/financial_market.git
```

`origin/main` 是旧历史。本次 `main` 是清理后的新历史，因此首次上传需要 force-with-lease：

```bash
git push --force-with-lease origin main
```

执行前确认：

```bash
git status
git log --oneline -5
git ls-files | grep -E '^(runs|build|\.verify|\.idea)/'   # 应为空
```

## 旧历史

旧 Git 历史已打包到仓库外：

```text
F:\financialmarket_git_archive_20261121.bundle
```

需要恢复时：

```bash
git clone F:\financialmarket_git_archive_20261121.bundle archive-restore
```

## 发布前检查

```bash
./gradlew clean jar
jar tf build/libs/financialmarket-1.0.0-full.jar | grep -E 'META-INF/licenses|kronos-mini'
```

CI 使用 `.github/workflows/build.yml`，Ubuntu + Temurin 21，构建完整 jar 并上传 artifact。

## 不提交

- 世界/服务器运行目录、MCEF 缓存、日志；
- `third_party` 以外的模型缓存（`.models/`）；
- `.verify/` 验证数据（截图副本已放入 `docs/assets/`）；
- 任何 token、私钥、服务器密码。
