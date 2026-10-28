# 验证记录 · 1.0.0

日期：2026-10-28，Asia/Shanghai。versionName=1.0.0，versionCode=10000。

- 按用户要求未连接手机、未安装 APK、未运行真机或模拟器测试。设备测试源码已编译，截图用例改为验证公共 MediaStore URI；0.7.0 的 45 项设备记录单独保留为 DEVICE_TESTS-0.7.0.txt，不用于本版验证结论。
- :core:test、:app:assembleDebug、:app:assembleRelease、:app:assembleDebugAndroidTest、:app:lintDebug、:app:lintRelease 通过。核心 135 项无失败；Rust 7 项通过。
- Debug/Release Lint 均为 0 错误；警告分别为 51/51，主要涉及 API/依赖/权限声明提示。
- 两个 APK 都通过 apksigner 验签和 aapt2 包名/版本核对；Release debuggable=false，Debug=true。Release 沿用 0.7.0 的同一独立密钥，密钥和密码位于仓库外。
- 79 个操作、528 个本地化字符串键、7 个资源目录检查通过；全体开关仍使用共享 M3E 组件。
- Workflow YAML、触发条件、只读内容权限及固定 Actions SHA 检查通过。可选签名流程使用合成密钥数据验证未配置/配置完整/配置不完整/清理分支；产物收集脚本已用本地两个 APK 验证。
- 新包验签后删除旧 APK：无。旧源码归档保留。

## APK 校验

Debug SHA-256：`2bd37b400e3f59cfee78c2d506d5385bb222635f2aac780d8bcd3737a4512ceb`。

Release SHA-256：`0eb8a7a05b1af59b5ad5c38403e894ee324104ab2834fd7d08196d54ef2faf22`。

Release 证书 SHA-256：`4c4411dcecbd44cfa591f3f7040fd71f26479bfe7d1f6a92b46974d0111f2507`。

公共截图目录、删除区手势、系统权限页面以及德语布局的设备行为留给用户手动验证，本轮没有将静态检查或旧版测试视为这些行为已在 1.0.0 真机通过。

## 云端构建

首次 GitHub Actions 运行 [40655969831](https://github.com/AVMLOVER/Shortcuts/actions/runs/40655969831) 已成功，提交 f059876；Linux 环境完成 Rust/核心测试、两个变体的 Lint 和编译，上传 Shortcuts-1-apks 与报告。当前截图写入已进一步明确为主共享存储卷，最新源码的云端状态以 Actions 最新运行记录为准。
