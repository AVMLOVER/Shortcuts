# 验证记录 · 1.0.1

日期：2026-10-28，Asia/Shanghai。versionName=1.0.1，versionCode=10001。

- 按用户要求未连接手机、未安装 APK、未运行真机或模拟器测试。设备测试源码已编译，主页搜索用例适配固定标题栏；0.7.0 的 45 项设备记录仅作历史参考。
- :core:test、:app:assembleDebug、:app:assembleRelease、:app:assembleDebugAndroidTest、:app:lintDebug、:app:lintRelease 通过。核心 135 项无失败；Rust 7 项通过。
- Debug/Release Lint 均为 0 错误；警告分别为 52/52，主要涉及 API/依赖/权限声明提示。
- 两个 APK 都通过 apksigner 验签和 aapt2 包名/版本核对；Release debuggable=false，Debug=true。Release 沿用原有独立密钥，密钥和密码位于仓库外。
- 79 个操作、530 个本地化字符串键、7 个资源目录检查通过；全体开关仍使用共享 M3E 组件。
- 预制颜色移除名称，保留色块、选中标记与序号无障碍说明；XML 颜色标识保持兼容。首页三个按钮使用共享 TooltipBox，导入使用新的向下箭头；设置导入同步更新。
- 首页移除字典示例入口；标题栏从滚动列表移入 Scaffold.topBar，移除额外的 28dp 顶部留白，仅由系统 safeDrawing 顶部/水平安全区域避让状态栏及屏幕开孔。列表内容从标题栏下方开始。
- 新包验签后删除旧 APK：Shortcuts-1.0.0-debug.apk, Shortcuts-1.0.0-release.apk。旧源码归档保留。

## APK 校验

Debug SHA-256：`04307ec39063905f03265dc05b22293e14bb1cd7408ecbb5e33e2bc49e31b4ea`。

Release SHA-256：`eb5f93d661778fc5ebce255e5bbec3bb5b7a89d773747b3673fb9389944f5bc0`。

Release 证书 SHA-256：`4c4411dcecbd44cfa591f3f7040fd71f26479bfe7d1f6a92b46974d0111f2507`。

ColorOS 标题栏间距、长按提示及配色选择的设备表现由用户手动验证，本轮没有将静态检查或旧版测试视为这些行为已在 1.0.1 真机通过。

## 云端构建

保留已有 GitHub Actions：main 推送、Pull Request 和手动触发构建；未配置发布签名 Secrets 时云端 Release 为 unsigned。本地签名 Release 使用原有密钥，未将密钥上传到仓库。每次运行结果以 [Actions](https://github.com/AVMLOVER/Shortcuts/actions) 为准。
