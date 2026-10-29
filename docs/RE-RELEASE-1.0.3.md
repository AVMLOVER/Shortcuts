# 1.0.3 Re-Release

本次保持 `versionName=1.0.3`、`versionCode=10003` 和 XML `appVersion=10003` 不变，XML 结构仍为 v1 / v2。

## 条件性重复

条件性重复的上限提高到 **100000 次**，仍至少执行一次，在每轮结束时检查停止条件。第 100000 轮满足条件会正常返回结果和次数；仍不满足则报错，不执行第 100001 轮。

为避免原 5000 步总限制提前阻断，条件性重复内部操作及其子调用使用另一份整条调用链共享的最多 1 亿步预算；嵌套条件性重复共享该预算，退出后恢复普通执行预算。普通执行及其子调用仍共享 5000 步，指定次数重复仍最多 1000 次，配置最多 1000 个操作等限制不变。历史输出继续按原容量裁剪，循环保持取消检查。

README、Wiki 源文档及相关操作说明同步区分上述限制；各语言的循环上限错误提示同步更新。

## 导航栏沉浸

首页、编辑器、动作库、各底部面板、共享入口、小组件配置及磁贴运行页统一处理系统安全区。手势导航底部不再预留小白条高度，内容延伸至透明导航区域；保留状态栏 / 横向安全区、键盘避让及三键导航的点击区域保护。

移除系统自动导航栏对比遮罩，导航图标明暗继续跟随主题。实现依据：[Android 系统栏透明显示](https://developer.android.com/develop/ui/compose/system/system-bars)、[WindowInsets 与 tappableElement](https://developer.android.com/develop/ui/compose/system/insets)。

## 验证

按本次要求不连接设备、不安装、不执行真机测试。核心 198 项测试、Debug / Release Kotlin 编译及 Lint 均通过。详情见 [验证记录](VERIFICATION-1.0.3-Re-Release.md)。
