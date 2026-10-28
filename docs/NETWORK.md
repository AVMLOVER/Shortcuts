# 网络操作 · 1.0.2 Re-Release

操作库共 106 项。URL 操作的参数和可编辑键/值均保存在普通 XML 参数中，值支持变量胶囊。旧版只有 URL 和查询参数的 `get_url` 继续按 GET 执行。

## 获取 URL 内容

`get_url` 方法提供 GET、POST、PUT、PATCH、DELETE。选择每次询问会在运行时弹出方法菜单，取消即结束流程；选择变量可在方法输入框内使用命名变量或魔法变量，解析后只接受这五种方法。

URL、查询参数和头部值都可使用变量。请求头名称不区分大小写，禁止重复名称或换行注入，最多 50 项；连接分帧字段由客户端管理。HTTP 与 HTTPS 均可由用户指定，HTTPS 使用系统证书校验。

POST、PUT、PATCH 显示请求体配置：

- JSON：下方键/值支持自动、文本、数字、布尔类型；自动类型可传入字典或列表。也可在上方直接填入 JSON 文本或字典/列表变量；留空则序列化下方字段。
- 表单：下方键/值使用 UTF-8 `application/x-www-form-urlencoded` 编码。
- 文件：从系统文件选择器或变量指定文件，发送原始字节，Content-Type 可自定义，默认 `application/octet-stream`。单个请求体最多 32 MiB，仍受文件读取后端限制。
- 文本：发送 UTF-8 原文，Content-Type 可自定义。GET 和 DELETE 不发送请求体。

响应正文按 Content-Type 的 charset 解码，默认 UTF-8，以文本输出；不自动将响应 JSON 转成字典或下载为图像。传输读取上限 1 MiB，流程单步输出仍受 262144 字符限制。非 2xx 状态报错，204/205 输出空文本。连接和读取各 15 秒超时。

## 扩展 URL 与响应头

`expand_url` 跟随 HTTP 重定向并输出最终地址。`get_url_headers` 输出响应头字典，键统一小写；同一头部有多个值时输出列表，否则输出文本，可连接获取字典值和列表操作。

两者先发 HEAD；终点不支持 HEAD（405/501）时，改用 GET 获取响应头而不读取响应正文。支持相对 Location，最多检查 8 个地址，循环或无效地址报错。307/308 保留原方法与请求体，303 转为 GET（HEAD 保持），301/302 的 POST 转为 GET；跨源跳转不转发 Authorization、Cookie 或 Proxy-Authorization。

## URL 组件与输入提取

`get_url_component` 可选择方案、用户名、密码、主机、端口、路径、查询、片段或全部组件。全部组件输出字典，键为 `scheme`、`user`、`password`、`host`、`port`、`path`、`query`、`fragment`。有端口时输出数字，其余为文本；不存在的部分为空文本，查询保留百分号编码。组件解析不发网络请求，支持自定义应用协议。

`get_urls_from_input` 从文本、列表、字典或共享文件的文本内容中提取链接，输出 URL 文本组成的列表，保留自定义应用协议。默认加入变量栏。它与共享输入、获取列表项及上述 URL 操作可直接连接。

方法及 JSON/表单/文件配置参考 [Apple：请求第一个 API](https://support.apple.com/guide/shortcuts/request-your-first-api-apd58d46713f/10.0/ios/27)。Android PATCH 使用公开的 HttpURLConnection 调用；Android 平台实现支持 PATCH，未使用反射或隐藏 API，见 [AOSP 实现](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/com/android/okhttp/internal/huc/HttpURLConnectionImpl.java)。
