# Financial Market 1.0.0 — 分发清单（RELEASE_NOTES / 依赖说明 / zip 结构）

> 角色：asset-dev（分发打包/资源补齐）· 依据 `SMOKE_DISTRIBUTION_SPEC.md` §3（分发准备清单）
> 状态：1.0.0 分发准备完成，产物 `build/dist/financialmarket-1.0.0-dist.zip`

---

## 1. 版本与元数据

| 项 | 值 | 一致性核对 |
|---|---|---|
| modId | `financialmarket` | mods.toml / gradle.properties `mod_id` 一致 |
| version | `1.0.0` | mods.toml / build.gradle `version` / 产物文件名一致 |
| displayName | Financial Market | — |
| authors | `xblnb` | 已替换占位 `YourName`（git 作者 `xblnb <xblnb_114514@outlook.com>`） |
| license | MIT | mods.toml + 分发 LICENSE |
| minecraft_version | 1.21.1 | gradle.properties |
| neo_version | 21.1.119 | gradle.properties / build.gradle `neoforge:21.1.119` |
| Java | 21 | java.toolchain.languageVersion |
| logoFile | `META-INF/financialmarket.png`（841 B，存在） | mods.toml 引用有效 |

## 2. 依赖说明（运行期可达性）

| 依赖 | 打包方式 | 运行期可达性 | 证据 |
|---|---|---|---|
| H2 2.2.224 | **jarJar 嵌套**进 `financialmarket-1.0.0-all.jar`：`META-INF/jarjar/h2-2.2.224.jar`（2,614,933 B）+ `metadata.json`（range `[2.2.224,)`） | ✅ NeoForge jarJar 系统在 mod 类加载器注入 | 解包 all.jar 断言（§5） |
| Gson 2.10.1 | 不打包、不 jarJar | ✅ **由游戏本体提供**：MC 1.21.1 自带库 `com.google.code.gson:gson:2.10.1`（版本.json libraries 确认），编译坐标与运行版本完全一致，无需打包 | `forge_gradle/minecraft_repo/versions/1.21.1/version.json` |
| MCEF 2.1.6（外部必需 mod） | **不打包进 all.jar**，分发包 mods/ 目录附带 `mcef-neoforge-2.1.6-1.21.1.jar`（215,373 B）；mods.toml 已声明 `type="required" versionRange="[2.1.6,)"` | ✅ 方案 A：mods 双 jar + 依赖声明；MCEF 原生库首次运行自动下载 `mods/mcef-libraries/windows_amd64`（sha256 校验） | libs/ 内 MCEF mods.toml（`modId="mcef"`） |
| NeoForge | 依赖声明 `[21.1.0,)` | ✅ 玩家自装 | mods.toml |

> **方案选择记录（SD-14）**：MCEF 采用规格 §3.1 方案 A（mods 目录双 jar + mods.toml 依赖声明），否决方案 B（jarJar 嵌套 MCEF）——MCEF 自带 `mcef.mixins.json` 与独立 mods.toml，嵌套需实机验证 mixin/mod 发现，风险高且无必要（215 KB 单 jar 随附即可）。
> **降级语义记录**：`FinancialTabletItem.openScreen` 对 MCEF 运行期失败 `catch(Throwable)` 降级不崩（打印控制台错误）——该保护覆盖 **MCEF mod 已加载但原生库加载失败** 等运行期场景；若玩家完全移除 mcef jar，mods.toml `required` 声明会使 NeoForge 在加载期给出明确缺依赖错误（设计内行为，见 README「故障排除」）。

## 3. 分发 zip 结构（build/dist/financialmarket-1.0.0-dist.zip）

```
financialmarket-1.0.0-dist.zip
├── mods/
│   ├── financialmarket-1.0.0-all.jar   3,705,089 B（含 H2 jarJar；assets/data/元数据）
│   └── mcef-neoforge-2.1.6-1.21.1.jar    215,373 B（外部必需依赖，方案 A）
├── README.md    安装：NeoForge 21.1.x + Java 21；MCEF 原生库下载/离线预置；存档兼容说明
└── LICENSE      MIT
```

发布物使用 **all.jar**（单 jar 自包含 H2）；`financialmarket-1.0.0.jar`（纯模组 jar，1,209,883 B）仅供源码调试/库引用，**不用于分发**。

## 4. 资源与音频清单（SD-13/15 静态门）

| 检查项 | 结果 | 证据 |
|---|---|---|
| 语言 `lang/en_us.json` ↔ `zh_cn.json` | ✅ 键集一致，各 13 键，无缺失；译文互异（真翻译） | UTF-8 解析逐键 diff |
| 纹理 | ✅ 4 方块 16×16 + 3 平板 32×32 合法 RGBA PNG，**非 1×1/非全白/非全透明**；block 纹理为 2 色/纯色最小可用艺术（exchange_screen 深蓝 #0C1422、trading_terminal 深灰 #14181E 为屏幕/终端本体色），item 为平板外形（透明底 + 深色机身 + 品牌色） | PNG IHDR + 像素统计 |
| 模型/blockstates | ✅ 4 方块 block+item 模型、3 平板 item 模型全部存在；`exchange_screen`/`trading_terminal` blockstates 含 facing 四向 + y 旋转（匹配 HorizontalDirectionalBlock）；全部 texture 引用可解析 | 模型 JSON ↔ textures/ 对拍 |
| 音频 | ✅ `exchange_open_bell.ogg`（170,532 B）/ `exchange_close_bell.ogg`（44,267 B）合法 Ogg；**`PLACE_AUDIO_HERE.txt` 已从资源树移除**（不再进入 jar/zip）；sounds.json 键与 SoundInit 注册一致；双语言 subtitle 键齐 | 资源树 + jar 解包断言 |
| Web 前端 | ✅ `html/echarts.min.js`（1,024,740 B 真 echarts 包）与 `html/index.html`（189,413 B）本地打包；index.html **零外部 URL**（无 CDN），离线可渲染 | 正则扫描 src/href |
| 数据 | ✅ `data/financialmarket/commodity_prices.json`（6,419 B）在产物内 | jar 解包断言 |

## 5. 构建/产物断言（SD-0 分发侧）

1. `.\gradlew.bat clean build` → **exit 0**（日志 `.verify/dist_build.log`）。
2. `financialmarket-1.0.0-all.jar` 内含：`META-INF/jarjar/h2-2.2.224.jar` + `metadata.json`、`META-INF/neoforge.mods.toml`（含 mcef 依赖）、`assets/financialmarket/**`（40 项）、`data/financialmarket/**`；**不含** `sounds/block/PLACE_AUDIO_HERE.txt`。
3. `financialmarket-1.0.0.jar` 不含 H2（纯模组 jar 语义正确）。

## 6. 存档前向兼容（SD-11 分发侧说明）

- H2 迁移幂等：`H2DatabaseManager` 使用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（ai_company / company 两处，可重跑不炸），schema_version=1 语义不变。
- NBT/H2 读档兜底：`MarketEngine`（49 处）、`CompanyData`（12 处）、`AssetScanner`（14 处）、`BankManager`（8 处）等 load 路径普遍 `contains` 兜底，缺字段走默认值，读旧档不失败。
- **升级路径契约（1.0.0 → 后续版本）**：只允许「读旧档成功 + 新字段默认值」；引擎 blob 结构变更时递增 schema_version 并写迁移逻辑；禁止破坏性 schema 变更。本版本不做新迁移，1.0.0 直接读 1.0.0-Beta 前旧档（缺字段默认值兜底）。

## 7. 已知行为与限制（分发说明）

- **MCEF 必须随附**：分发 zip 已含 mcef jar；移除将导致加载期缺依赖错误（required 声明，设计内）。
- MCEF 原生库为运行期下载（`mods/mcef-libraries/`），离线环境需首次在有网机器跑通后连同 `mcef-libraries` 目录一起分发，或由用户预置（README 有说明）。
- 无头 runServer 无玩家时引擎懒初始化不启动（`/api/*` 可能返回空列表但 handler 不 500）——全玩法闭环以 runClient 集成服为准（冒烟侧项）。
- 占位纹理已确认非白图、可接受为最小可用艺术；后续版本可升级为精细贴图（不阻断 1.0.0 分发）。
