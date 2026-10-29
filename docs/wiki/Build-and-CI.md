# 构建与 GitHub Actions

## 环境

| 依赖 | 仓库配置 |
| --- | --- |
| JDK | 17 |
| Android SDK / Build Tools | 37.0 / 37.0.0（Gradle compileSdk 为 37） |
| NDK | `30.0.16248370` |
| Rust | CI 固定 `1.99.1` |
| Android Gradle Plugin | `9.3.1` |
| Kotlin | `2.2.10` |
| Compose BOM / Material 3 | `2026.09.00` / `1.5.0-beta01` |

Material 3 使用预发布的 Expressive API。实际依赖以仓库 Gradle / Cargo 配置为准，不要仅替换单个版本号后假定兼容。

克隆源码，在 `local.properties` 配置 `sdk.dir`，或设置 `ANDROID_HOME`。准备好 SDK、NDK、Rust，并安装目标：

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
```

Gradle 通过 `tools/build-native.ps1` 或 `tools/build-native.sh`，按 Cargo.lock 为四种 ABI 构建数学库。`cargo` 需在 PATH 或配置 CARGO_HOME。

## 本地测试和 Debug

Windows PowerShell：

```powershell
.\gradlew.bat :core:test :app:lintDebug :app:assembleDebug
cargo test --manifest-path math-rust/Cargo.toml --locked
```

Linux / macOS：

```sh
chmod +x gradlew
./gradlew :core:test :app:lintDebug :app:assembleDebug
cargo test --manifest-path math-rust/Cargo.toml --locked
```

Debug 产物为 `app/build/outputs/apk/debug/app-debug.apk`。Android 测试 APK 可用 `:app:assembleDebugAndroidTest` 构建；真机测试会涉及权限和系统界面，需先备份数据并确认测试范围，不能把主应用卸载 / 清数据当作覆盖升级测试。

## Release 签名

私钥和密码保存在仓库之外，准备属性文件：

```properties
storeFile=/private/path/shortcuts-release.p12
storeType=PKCS12
storePassword=YOUR_PRIVATE_STORE_PASSWORD
keyAlias=shortcuts-release
keyPassword=YOUR_PRIVATE_KEY_PASSWORD
```

PowerShell：

```powershell
$env:SHORTCUTS_SIGNING_PROPERTIES = 'D:/private/signing.properties'
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

Linux / macOS：

```sh
export SHORTCUTS_SIGNING_PROPERTIES=/private/signing.properties
./gradlew :app:assembleRelease :app:lintRelease
```

配置签名时输出 `app/build/outputs/apk/release/app-release.apk`。不设置签名属性则生成未签名 Release，需要另行签名才能安装。后续更新必须使用相同发布密钥；Debug、其他人的构建及不同 CI 调试签名通常不能覆盖现有 Release。

Release 关闭 debuggable，当前不启用 R8 混淆，Rust 库按 release 优化。发布前可用 SDK 的 `apksigner verify --verbose --print-certs` 和 `aapt2 dump badging` 检查签名、包名与版本。

## GitHub Actions

`.github/workflows/android.yml` 的 **Build Android APKs** 在以下情况运行：推送 `main`、向 `main` 提交 PR、手动 workflow_dispatch。流程会安装工具链，运行 Rust / core 测试、Debug / Release Lint 并生成两个 APK，不运行真机测试。

在仓库 Actions 打开相应运行，完成后下载 `Shortcuts-运行编号-apks`，其中有 APK 和 SHA-256 文件；reports 产物包含构建报告。产物保留 14 天，工作流不创建 GitHub Release。

## 配置 CI 发布签名

在仓库 Actions Secrets 添加以下四项：

| Secret | 内容 |
| --- | --- |
| `SHORTCUTS_KEYSTORE_BASE64` | 发布 keystore 的 Base64 内容 |
| `SHORTCUTS_STORE_PASSWORD` | keystore 密码 |
| `SHORTCUTS_KEY_ALIAS` | 发布别名 |
| `SHORTCUTS_KEY_PASSWORD` | 私钥密码 |

四项须完整配置。推送和手动运行可读取签名，PR 一律构建未签名 Release。未配置时产物名称包含 `unsigned`；Base64 仍包含私钥信息，不应作为普通文件提交。

CI 只把临时签名数据写入 Runner 临时目录并在结束时删除。现有维护者的发布私钥不会随克隆源码获得；自行分发应管理自己的稳定签名。

## 贡献代码与文档

`core/` 实现平台无关逻辑，`app/` 适配 Android，`math-rust/` 实现数学内核。新增操作需同时考虑处理器、动作库、输出契约、XML 参数、所有语言资源及必要验证。

Wiki 源文件在 `docs/wiki/`；执行 `python tools/package-wiki.py` 可生成可上传包，详细上传步骤见主仓库 `docs/WIKI_UPLOAD.md`。
