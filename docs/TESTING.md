# 测试说明

1.0.0 本轮按用户要求只做主机测试、编译、Lint、签名和静态检查，没有连接手机、安装 APK 或运行 instrumentation。设备测试源码仅编译，截图用例已改为检查 MediaStore URI 与公共 Screenshots 路径。

0.7.0 的 45 项设备回归原始记录保留在 DEVICE_TESTS-0.7.0.txt，仅供历史参考；不能视为 1.0.0 的设备验证。当前验证见 VERIFICATION.md。

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

保持手机解锁并连接 ADB，用保留数据的更新安装运行 instrumentation：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.germanium.shortcuts.DeviceRegressionTest com.germanium.shortcuts.test/androidx.test.runner.AndroidJUnitRunner
```

成功以 `OK (N tests)` 判断，JUnitRunner 的成功 INSTRUMENTATION_CODE 为 -1。不要把设备代码 -1 当成测试失败。

当前项目设置 `android.injected.androidTest.leaveApksInstalledAfterRun=true`，避免 Gradle connected 测试默认清理目标应用。测试前应备份应用数据或使用专用测试设备；本轮最终使用上面的 adb 更新安装与直接 runner，不再使用 connected 安装/清理流程。只卸载独立的 com.germanium.shortcuts.test 测试包，不卸载用户应用。

设备用例使用独立 UUID 的临时快捷指令。编辑器窗口在测试期间保持亮屏。截图为测试数据，不执行第三方应用、真实安装软件或发送通知；这些接口仍需单独的设备授权和测试场景。

Espresso 显式固定 3.7.0，以替换 Compose 测试传递引入的 3.5.0，避免旧版反射调用已移除的 InputManager.getInstance。依据 [AndroidX Test 官方发布记录](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0-alpha04)。

主机测试覆盖：XML 与设置、变量/字典/列表、流程执行及取消、表达式与文本、子进程、应用工作目录、自动化边沿与存储、排序与输出作用域、图标数据兼容性。

设备测试覆盖：Android ICU、XML、标准 Shell/stdin、日志写入、编辑器运行/菜单/条件/快速查看、多组字典编辑与保存、输出选择、拖动排序与边缘滚动、图标与颜色、删除结果/错误、返回时保留编辑内容和五种语言资源。


0.7 构建需 Rust 与 NDK。主机原生测试：`cargo test --manifest-path math-rust/Cargo.toml`；Gradle preBuild 依赖 buildRust，构建并打包四种 Android ABI。Android 用例直接检查 JNI 后端、十进制和三角/整数函数。源码归档排除 math-rust/target。

设备扩展用例包括确认/取消对话框、有类型文件往返、像素裁剪及属性、Shell 确认和动态输出、结束标记、编辑后共享结果清理，以及临时 AppWidgetHost 绑定/渲染。日历使用独立临时本地日历，测试后清理；小组件使用本应用的随机临时宿主，不修改 Launcher 的现有小组件。UiAutomation 临时采用必要的系统权限并在 finally 释放。测试中不运行被阻止的 rm 命令，只验证它在进程启动前报错。

UI 用例需要前台窗口和稳定的触摸输入；测试时请不要切到其他应用。断言失败后会取消该测试流程并清理 UUID 测试文件，避免挂起对话框影响后续用例。设备结果以本次最终 runner 日志为准；不以中间失败日志或主机通过推断设备已通过。

角度单位测试覆盖三种单位、六种三角函数、嵌套调用、XML 旧格式默认值，以及设置 UI → XML → 真机 Rust 运行链路。定位使用合成 Location 检查数值字段与单独魔法变量，避免在测试日志和截图中记录手机真实坐标。

新增验证：角度按钮切换后逐一检查宽高相等；标题栏搜索匹配/空结果/清除/运行；Rust 的准确三角显示、奇偶根定义域、正负数十进制取整；独立数字取整设置；普通运行与已处理异常不会修改任何日志文件，崩溃写入另用合成异常测试。系统原生全局拖拽期间 Android 会暂时改变窗口焦点，因此该阶段使用系统截图和放下后的保存顺序验证，避免用只检索有焦点 Compose 窗口的断言误判。
