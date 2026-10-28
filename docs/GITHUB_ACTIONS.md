# GitHub Actions 自动编译

工作流为 `.github/workflows/android.yml`：推送 `main`、提交到 `main` 的 PR，或手动触发时，自动安装 JDK 17、Android SDK 37.0、NDK r30、Rust 1.99.1，运行核心/Rust 测试和 Lint，并编译 Debug、Release。不会连接手机或运行真机测试。

打开仓库的 **Actions → Build Android APKs**，选择一次运行，完成后在页面下方 **Artifacts** 下载 `Shortcuts-运行编号-apks`。压缩包包含两个 APK 和 SHA-256 校验文件。也可以点 **Run workflow** 手动编译。产物保留 14 天，工作流不创建 GitHub Release。

默认 Debug 已签名、Release 未签名，Release 文件名明确带 `unsigned`；未签名 APK 需要签名后才能安装。如果要让 Actions 使用本项目已有的发布密钥，在仓库 **Settings → Secrets and variables → Actions → New repository secret** 配置四个 Secrets：

| 名称 | 内容 |
| --- | --- |
| `SHORTCUTS_KEYSTORE_BASE64` | 私有 `shortcuts-release.p12` 文件的 Base64 内容 |
| `SHORTCUTS_STORE_PASSWORD` | `signing.properties` 的 storePassword |
| `SHORTCUTS_KEY_ALIAS` | `signing.properties` 的 keyAlias，本项目为 shortcuts-release |
| `SHORTCUTS_KEY_PASSWORD` | `signing.properties` 的 keyPassword |

Windows 可在自己的私有目录生成 Base64 文本，再将内容粘贴到对应 Secret：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('D:/private/shortcuts-release.p12')) | Set-Content -NoNewline 'D:/private/keystore-base64.txt'
```

Base64 文本同样包含私钥，不能提交到仓库。本地签名备份目录不会自动上传；是否配置以上 Secrets 由维护者决定。四项需同时配置，配置不完整会明确失败。仅 `push` 和手动构建读取签名 Secrets，PR 构建始终使用未签名 Release。

临时密钥只写入 Runner 的私有临时目录，结束时删除；上传路径仅包含 APK 和构建报告。工作流仅授予仓库内容读取权限。CI 的调试签名和本机调试签名可能不同；对外常规更新应始终使用同一 Release 签名。

官方说明：[Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[下载工作流产物](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)。
