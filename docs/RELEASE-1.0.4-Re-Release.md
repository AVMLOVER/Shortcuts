# 1.0.4 Re-Release

应用版本保持 1.0.4 / 10004，XML appVersion 保持 10004，无新增格式迁移。

- 输出结果、显示结果、快速查看及小组件桌面结果保留原始值类型，使用同一套图像 / 文档预览。支持图像列表、分享内容、PDF 翻页、UTF-8 文本预览及其他格式的文档卡片；外部打开使用只读 URI 授权。
- 发送通知 / 提醒使用安卓原生大图样式显示图像或 PDF 缩略图，提供完整内容的预览入口；通知载荷通过有界缓存保存，避免把大列表放进 PendingIntent。
- 图像 / 文档小组件结果保留到手动关闭或再次运行，普通文本保留约 7 秒。
- 添加动作面板增加滑入 / 滑出及渐显 / 渐隐，拖入操作时立即让出编辑区域。
- 按用户要求不连接或操作真机；主机测试、构建、验签与静态检查结果见对应验证记录。

Android 依据：[BigPictureStyle](https://developer.android.com/reference/android/app/Notification.BigPictureStyle)、[PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)。
