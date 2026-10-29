# 1.0.4 Technical Update

应用版本保持 1.0.4 / 10004，快捷指令 XML appVersion、结构及设置 XML 格式不变。

- 小组件只按实际执行记录选择结果：显示结果 / 成功通知和提醒不再追加最终结果；否则使用最后快速查看或正常输出。错误仍可见，其余入口的运行方式不变。
- 变量相关附加控件与显示结果的支持取消默认收在“更多”；操作输入 / 设置、底部四个按钮保留可见。变量类操作没有添加为变量和“更多”。
- 大屏编辑器增加常驻动作库：平板约 1/3，折叠屏约 1/2，默认左侧，设置可选右侧；只对平板 / 折叠屏显示设置。窄窗口恢复底部面板，大屏横屏主页导航移到左侧，并避让系统报告的竖向分隔铰链。
- 设置及系统无障碍页均提供自动化辅助用途说明。可选服务不读取屏幕或执行手势，只尝试恢复已启用且未暂停的监视，不保证后台常驻。
- 指定时间使用 M3E VibrantTimePickerDialog，支持表盘 / 键盘模式及本地化的系统 12 / 24 小时格式；原规则仍保存 HH:mm。
- 新建默认名称按当前语言生成唯一数字后缀，并在同时打开的编辑器之间预留；已有流程和草稿名称保留。
- 按用户要求不使用真机；平板 / 折叠屏的厂商特性与实际触摸布局未做硬件验证。

Android 依据：[时间选择器](https://developer.android.com/develop/ui/compose/components/time-pickers)、[折叠设备与 WindowManager](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware)、[无障碍服务](https://developer.android.com/guide/topics/ui/accessibility/service)。
