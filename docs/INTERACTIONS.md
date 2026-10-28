# 交互与平台实现依据

| 参照 | 本版实现 |
| --- | --- |
| [Apple：使用变量](https://support.apple.com/guide/shortcuts/use-variables-apdd02c2780c/ios) | 文本框内蓝色胶囊；原编辑器中切换输出选择状态，操作下显示可点击输出；在原光标位置插入；退格删除整个变量。橘色 x 用于醒目区分变量。 |
| [Apple：调整变量](https://support.apple.com/guide/shortcuts/adjust-variables-apda36b9018b/ios) | 点击变量在底部显示工具面板，可替换、移除、定位来源；定位时滚动并突出来源。尚未实现 Apple Content Graph 的完整类型/属性转换。 |
| [SukiSU Ultra 悬浮底栏](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/main/manager/app/src/main/java/com/sukisu/ultra/ui/component/bottombar/BottomBarMaterial.kt) | 独立编写 Compose 悬浮胶囊、选中项目展开。固定外框，统一 180ms 过渡驱动背景与项目宽度，避免多个动画相互追赶。未复制其实现源码。 |
| [Compose 文本输入](https://developer.android.com/develop/ui/compose/text/user-input) | 保留 BasicTextField 的光标、选择和 IME，VisualTransformation 显示变量名称，OffsetMapping 映射回 XML 模板位置；编辑协调器扩大涉及变量的删除范围。 |
| [Android Calendar Provider](https://developer.android.com/identity/providers/calendar-provider) | CalendarContract.Instances 读取指定日的事件实例，权限按需申请。 |
| [Android MediaProjection](https://developer.android.com/media/grow/media-projection) | 每次新授权，短时 mediaProjection 前台服务、一次 VirtualDisplay、ImageReader PNG，结束释放。 |
| [Android Quick Settings tiles](https://developer.android.com/develop/ui/views/quicksettings-tiles) | 4 个受 BIND_QUICK_SETTINGS_TILE 保护的 TileService，Android 13+ requestAddTileService；运行入口 Activity 不对外导出。不同厂商控制中心的展示由系统决定。 |

M3EColorPicker 是本项目中使用 Material 3 Expressive Slider 实现的连续 HSV 颜色组件，没有引入未确认来源的同名第三方依赖。RGB 写入 XML，浅色背景上的图案自动使用深色。

性能调整按编辑器结构与引用标签版本缓存布局，参数更新只复制祖先路径，运行结果按 ID 索引，草稿校验在 Default 调度器执行。1000 操作连续输入用例验证草稿和光标完整性；设备计时包含测试同步，并非 FPS、纯输入耗时或相对旧版的量化提升。
