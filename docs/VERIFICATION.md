# 验证记录 · 1.0.2

日期：2026-10-28，Asia/Shanghai。versionName=1.0.2，versionCode=10002。

- 使用同签名 Release 从设备原 0.7.0 覆盖升级，未卸载主应用或清除数据。升级前备份原 XML/工作文件及偏好，测试后核对原始文件指纹和已有偏好键值；原始 1 个文件保留。测试专用包与主应用使用同一证书。
- 真机功能检查 30 项通过，升级前后数据检查各 1/1 项通过。覆盖图像像素、蒙版透明度、叠放/拼接、调整尺寸/压缩、文本生成图像/PDF、原生因数/统计、空流程退出、变量栏、条件性重复及次数、运行时图像预览、共享文本输入、已有语言/XML/Shell 回归。旧版 45 项记录不计入本轮。
- :core:test、:app:assembleDebug、:app:assembleRelease、:app:assembleDebugAndroidTest、:app:lintDebug、:app:lintRelease 通过。核心 162 项无失败；Rust 11 项通过，包含统计、64 位质数/因数与数学回归。
- Debug/Release Lint 均为 0 错误，警告分别为 66/66。未将 Lint 检查当作真实系统交互验证。
- 两个 APK 通过 apksigner 验签和 aapt2 包名/版本核对；Release debuggable=false，Debug=true。Release 沿用原有独立发布密钥，密钥与密码位于仓库外。
- 102 个动作、665 个本地化字符串键、7 个资源目录检查通过；所有开关使用共享 M3E 勾/X 组件。新增示例 XML 结构检查通过。
- 共享入口按已保存且已启用的分享动作发布，接收阶段保留授权内容副本；输出文件通过只读 FileProvider URI 共享。图像参数操作和预览已接入，PNG 保留透明区域，内存和像素有界。
- 新包验签后删除旧 APK：Shortcuts-1.0.1-debug.apk, Shortcuts-1.0.1-release.apk。旧源码归档保留。

## APK 校验

Debug SHA-256：`76f7b37d65c6a7c79f8cbd5627477f6e484951fba4fb0d1365b0164a061d007e`。

Release SHA-256：`fba221613ec04c457109a14b07399132aa9fcdd561d9302b781d39bf4acd76fa`。

Release 证书 SHA-256：`4c4411dcecbd44cfa591f3f7040fd71f26479bfe7d1f6a92b46974d0111f2507`。

## 验证边界

系统分享面板的目标排序与缓存、其他厂商机型、复杂外部应用返回行为和不同文件提供者授权不能由本次单设备检查概括。Android 分享直达区域有数量上限，应用内选择列表展示全部适用流程；输入提取不自动下载网页图像或执行 OCR。图片大尺寸内存边界和 PDF 100 页上限见 CONTENT-1.0.2.md。测试结束恢复了充电保持亮屏设置。

GitHub Actions 保留 main 推送、PR 和手动触发。云端未配置发布签名 Secrets 时 Release 为 unsigned；本地 Release 已使用原密钥签名。状态见 [Actions](https://github.com/AVMLOVER/Shortcuts/actions)。
