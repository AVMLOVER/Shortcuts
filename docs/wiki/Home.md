# Shortcuts 使用手册

Shortcuts 是使用 Jetpack Compose 和 Material 3 Expressive 构建的 Android 工作流应用。把操作串起来，传递文本、数字、列表、字典、图像或文件，即可组合出自己的快捷任务。

本手册对应当前仓库实现，介绍使用方法、操作契约、权限和构建方式，不包含版本更新日志。应用要求 Android 8.0 及以上，包名为 `com.germanium.shortcuts`，提供简体中文、繁体中文、英语、德语和日语。

## 从这里开始

| 目标 | 页面 |
| --- | --- |
| 创建、保存和运行第一条流程 | [快速上手](Getting-Started.md) |
| 添加、拖动、删除、撤销和调试 | [编辑器](Editor.md) |
| 理解上一步结果、魔法变量和命名变量 | [变量与数据类型](Variables.md) |
| 使用如果、菜单、循环和停止 | [流程控制](Control-Flow.md) |
| 把流程拆成可复用的子流程 | [快捷指令调用与返回](Shortcut-Calls.md) |
| 查找全部 129 个操作 | [操作参考](Actions.md) |
| 直接照着搭建流程 | [示例工作流](Examples.md) |

## 按功能阅读

- [文本、列表、字典与编码](Text-and-Data.md)
- [计算与数字处理](Math.md)
- [图像与 PDF](Images-and-PDF.md)
- [文件、XML 与备份](Files-and-XML.md)
- [网络、URL 与 HTML](Network.md)
- [自动化](Automation.md)
- [权限与执行模式](Permissions-and-Modes.md)
- [设备操作与 Root](Device-and-Root.md)
- [桌面小组件与快捷设置](Widgets-and-Tiles.md)
- [构建与 GitHub Actions](Build-and-CI.md)
- [常见问题与排查](FAQ.md)

## 先理解四件事

1. **操作按顺序运行**：有输出的操作把结果交给后面，无输出的操作主要执行交互或系统动作。
2. **变量有类型**：完整引用保留数字、列表等类型，嵌入句子后会成为文本。
3. **配置需要保存**：主页、共享入口、自动化及子流程调用使用已保存的快捷指令；编辑器可以运行当前草稿。
4. **权限决定系统能力**：标准 / Shizuku / Root 是后端选择，授权仍在运行时检查。

[项目仓库](https://github.com/AVMLOVER/Shortcuts) · [下载 APK](https://github.com/AVMLOVER/Shortcuts/releases) · [反馈问题](https://github.com/AVMLOVER/Shortcuts/issues)
