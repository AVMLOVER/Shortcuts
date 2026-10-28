# 验证记录 · 1.0.2 Re-Release

日期：2026-10-28，Asia/Shanghai。versionName=1.0.2，versionCode=10002。

- 原设备 1.0.2 使用同一发布签名覆盖升级，未卸载主应用、清除数据或改写用户快捷指令。升级前保存 XML/工作文件和偏好快照；原始 3 个文件均保留；其中一条流程在测试期间由用户手动将添加到变量改为设置变量，用户已明确确认，未回滚其编辑。其他原文件及已有偏好与首份快照一致；对当前全部文件补充快照后再次覆盖安装、运行回归并逐项核对指纹。充电亮屏设置恢复。拖到 X 的交互由用户在本机手动确认正常；本轮不重复该自动化手势检查。
- 本轮真机 19 项功能检查通过，升级前后数据检查各 1/1 项通过。覆盖原生除法循环、变量直接选择、失效引用错误标记、整组移除、重复分支向外拖动、完整块拖放目标、主页复制、撤销/重做与三个长按提示、五种 HTTP 方法及请求体、头部/短链跳转和跨源凭据隔离、普通拖动、边缘滚动、动作库拖动及已有语言/XML 回归。逐项记录见 [真机记录](DEVICE_TESTS-1.0.2-Re-Release.txt)。
- 核心 180 项测试通过，Rust 11 项通过。Debug/Release APK、真机测试 APK、Debug/Release Lint 均构建通过。Lint 0 错误，警告分别 66/66 项；Lint 检查不替代真机验证。
- 106 个动作、699 个字符串键、7 个资源目录检查通过，全部开关使用共享勾/X 组件。用户提供 XML 的修正版单独交付，原文件保留，不写入公开仓库。
- 两个 APK 均通过 apksigner 和 aapt2 检查。Release debuggable=false，沿用原独立发布密钥；本次真机运行的 Release 与交付文件 SHA-256 一致。密钥、密码、用户数据及测试备份在仓库外。
- 新包验签后删除旧 APK：Shortcuts-1.0.2-debug.apk, Shortcuts-1.0.2-release.apk。保留旧源码归档。

## APK 校验

Debug SHA-256：`f257b5b79951445de79d74bff7f113c1a15ee199f699522d96d326dadd4693e8`。

Release SHA-256：`91b40624b085883b247e711b472198815611c1004e3353102268d71dd48f4f6e`。

Release 证书 SHA-256：`4c4411dcecbd44cfa591f3f7040fd71f26479bfe7d1f6a92b46974d0111f2507`。

## 本轮范围

本轮集中验证变量、流程编辑与复制；图像、相机、系统截图与 PDF 未在本轮重复测试，前一轮独立的 [1.0.2 真机记录](DEVICE_TESTS-1.0.2.txt) 保留其当时的 APK 指纹。未修改这些模块。其他厂商及不同系统版本仍需单独验证。

GitHub Actions 保留 main 推送、PR、手动触发；云端缺少发布签名 Secrets 时生成 unsigned Release。本地交付 Release 已签名。状态见 [Actions](https://github.com/AVMLOVER/Shortcuts/actions)。
