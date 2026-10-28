# Debug 与 Release 构建

本项目按 [GNU GPLv3](../LICENSE) 发布。应用包名为 `com.germanium.shortcuts`，构建环境和 Rust/NDK 配置见 [README](../README.md)。

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug 输出：`app/build/outputs/apk/debug/app-debug.apk`，使用 Android 调试签名。

Release 使用独立的发布密钥。密钥和密码保存在仓库外；不要将它们放进源码归档或上传 GitHub。在私有目录准备属性文件：

```properties
storeFile=/private/path/shortcuts-release.p12
storeType=PKCS12
storePassword=REPLACE_WITH_PRIVATE_STORE_PASSWORD
keyAlias=shortcuts-release
keyPassword=REPLACE_WITH_PRIVATE_KEY_PASSWORD
```

`storeFile` 可以是绝对路径，或相对于该属性文件的路径。Windows 路径建议使用 `/`。

```powershell
$env:SHORTCUTS_SIGNING_PROPERTIES = 'D:/private/signing.properties'
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

```bash
export SHORTCUTS_SIGNING_PROPERTIES=/private/signing.properties
./gradlew :app:assembleRelease :app:lintRelease
```

已配置签名时输出 `app/build/outputs/apk/release/app-release.apk`。未设置环境变量时构建未签名的 Release，需用自己的密钥签名后分发。

Release 明确关闭 debuggable；当前保留类名、不启用 R8 混淆，以维持 JNI、Shizuku 和系统接口的可验证行为。Rust 库使用 release 优化，打包四种 ABI。

后续 Release 更新必须使用同一发布密钥。Debug 与 Release 的签名不同，不能直接相互覆盖安装；切换前可通过应用中的 XML 导出/导入保存快捷指令。

上传附件前可使用 Android SDK 的 `apksigner verify --verbose --print-certs` 和 `aapt2 dump badging` 检查签名、包名、版本与 debuggable 标记。Git 仓库只包含源码和文档，APK 由维护者自行上传至 GitHub Release。
