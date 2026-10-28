# 验证记录 · 0.7.0

日期：2026-10-28，Asia/Shanghai。

- JVM 核心测试：135 项，10 个测试类，0 失败、0 错误、0 跳过。
- Rust cargo test：7 项通过；decimal、优先级、负数/幂、三角函数、gcd/lcm/mod、定义域和深度上限。NDK 30.0.16248370 交叉编译四种 ABI，APK 内逐一核对 ELF 库。
- 真机 instrumentation：45 项全部通过，23116PN5BC / Android 17 / API 37。使用 adb install -r + am instrument，原始最终输出见 DEVICE_TESTS.txt；不使用 Gradle connected 的安装/清理过程。
- 构建：:core:test、:app:assembleDebug、:app:assembleDebugAndroidTest、:app:lintDebug 全部通过。Lint：0 错误、49 警告，保留依赖/便利 API/旧系统属性/电池等提示。QUERY_ALL_PACKAGES 的局部 lint 注释对应用户明确要求的已安装包 Intent 选择器，不代表已完成商店审核。
- 操作库 79 项，字符串 517 键、7 个资源目录，覆盖英语、简中、德语、日语、繁中；注册表、格式占位符、XML 和全部开关统一组件检查通过。
- 包名 com.germanium.shortcuts，versionName 0.7.0 / versionCode 7，minSdk 26 / targetSdk 37。aapt2 核对身份，apksigner 验证调试签名。
- 测试先备份当前应用私有数据，更新安装保留数据。临时快捷指令、测试日历、临时 WidgetHost 会清理；不会卸载目标应用。物理 NFC 卡、真实 Root/Shizuku 设备策略、真实 APK 安装及各厂商桌面/控制中心仍需分别验证，不将公开接口接入视为所有设备实测。
- 新 APK 验签后删除旧交付 APK：Shortcuts-0.4.0-debug.apk。旧源码 ZIP 保留。

APK SHA-256：`2bb498d2056629c48d0d395f0582511a44823098c72562b1ca530df8459efb28`。

源码 ZIP 包含 Kotlin/Rust 源码、Cargo.lock、跨平台原生构建脚本、JVM/Rust/设备测试、XML 示例、说明与测试截图。排除 build、target、Gradle 缓存、SDK、local.properties、签名私钥和手机数据备份。未使用 Release 发布签名。


## Release 构建与 GitHub 源码

- 保留原仓库 LICENSE，GPLv3；源码包含 Kotlin/Compose 与 Rust，发布签名通过仓库外属性文件加载。密钥和密码未纳入 Git 或源码 ZIP。
- :app:assembleDebug、:app:assembleRelease、:app:lintRelease 通过；Release Lint 0 错误。Debug APK 与已完成 45 项真机测试的 APK 完全一致，功能源码未因签名配置改变。
- Release 包名/版本/minSdk/targetSdk 与 Debug 一致，debuggable=false；四种 ABI 的 Rust ELF 均已核对。Release 和 Debug 分别通过 apksigner 验证，使用不同证书。
- GitHub Release 及其附件由维护者自行发布，本轮只推送源代码。

Release APK SHA-256：`104aea61648b75abf623e8af42793ecca6c46ed54f9f12b33cb0b82e59cb8b87`。

Release 证书 SHA-256：`4c4411dcecbd44cfa591f3f7040fd71f26479bfe7d1f6a92b46974d0111f2507`。
