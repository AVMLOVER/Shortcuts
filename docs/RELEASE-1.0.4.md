# 1.0.4

应用版本 1.0.4 / 10004，快捷指令 XML appVersion 增加为 10004；结构 v1/v2 保留，旧文件自动迁移。

- 单条流程上限 10000 操作，XML 单项 8 MiB / 合集 32 MiB；普通运行共享 50000 步，条件性重复内部及其子调用共享最多 10 亿步，单个条件性重复仍最多 100000 次。历史记录使用有界队列。
- 独立 URL 默认 HTTPS；打开无协议 URL 补 HTTP，并优先交给系统默认浏览器。
- 小组件有上层权限时直接启动服务，桌面展示结果与常用交互；无权限只提示授权，不自动执行。
- 新增 JSON 解析 / 编码、日期调整、日期时长、文件大小格式化、旋转 / 转换图像、ZIP 压缩解压、二维码与条码、朗读、媒体音量、手电筒，以及 Root 电源与分区操作。总计 129 个动作，文字资源同步所有语言。
- 日期时长与文件大小换算继续接入 Rust，日期日历规则由时区 API 处理。
- 快速查看保留输入类型，直接预览图像、图像列表及分享图像，不显示图像文件路径；前台和桌面浮层共用采样及 EXIF 方向处理。
- 关机 / 重启检查真实 Root。重启支持系统、Recovery、Bootloader、Download、EDL，按设备固件能力工作，不自动换目标。
- 写入分区限定 Root、raw .img 和实际块设备，校验大小并强制高危确认；不在真机回归执行该操作或电源切换。

Apple 参照：[字典与 JSON](https://support.apple.com/en-ke/guide/shortcuts/apd43b69f337/ios)、[日期调整](https://support.apple.com/en-ca/guide/shortcuts/apdfb33b0e17/ios)、[转换操作](https://support.apple.com/en-qa/guide/shortcuts-mac/apd1d9413cfb/mac)、[二维码与其他更新](https://support.apple.com/en-lk/125148)、[朗读操作](https://support.apple.com/en-hk/101583)。Android 依据：[小组件交互启动前台服务](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[系统 TTS](https://developer.android.com/reference/android/speech/tts/TextToSpeech)。二维码 / 条码使用 [ZXing](https://github.com/zxing/zxing)，Apache 2.0 许可。
