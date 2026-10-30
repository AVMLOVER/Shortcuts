# 第三方组件

本项目代码使用 GPLv3；依赖保留各自许可和声明。以下是 1.0.5 新使用的组件，其他原有依赖见 Gradle 配置。

| 组件 | 固定版本 | 用途 / 来源 |
| --- | --- | --- |
| Flexmark | 0.64.8 | Markdown / HTML 转换，[源码与 BSD-2-Clause 许可](https://github.com/vsch/flexmark-java) |
| jsoup | 1.21.2 | HTML 清理与文字提取，[源码与 MIT 许可](https://github.com/jhy/jsoup) |
| Square gifencoder | 0.10.1 | GIF 编码，[源码与 Apache-2.0 许可](https://github.com/square/gifencoder) |
| Glide gifdecoder | 4.16.0 | GIF 解码与帧处置，[源码及许可](https://github.com/bumptech/glide/tree/v4.16.0/third_party/gif_decoder) |
| Google ML Kit 文字识别 | 16.0.1，拉丁 / 中文 / 日文模型 | 随应用打包的设备端识别，[官方说明及使用条款入口](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) |

GIF 转 MP4 和 PDF 页渲染使用 Android 系统 MediaCodec / MediaMuxer / PdfRenderer，没有引入 FFmpeg 二进制。
