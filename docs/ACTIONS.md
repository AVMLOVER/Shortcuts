# 操作清单与契约

本版操作库共 **78** 项。类型 ID 是 XML 中稳定的 `action.type`。除控制操作外均由注册表显式映射到处理器；仅有界面而未实现的操作不出现在操作库。

## 基础

| 操作 | 类型 ID |
| --- | --- |
| 文本 | `text` |
| 等待 | `delay` |
| 输出结果 | `output` |
| 快速查看 | `quick_look` |
| 要求输入 | `ask_input` |
| 生成 UUID | `generate_uuid` |

## 文本处理

| 操作 | 类型 ID |
| --- | --- |
| 追加文本 | `append_text` |
| 替换文本 | `replace_text` |
| 正则替换 | `regex_replace` |
| 匹配文本 | `match_text` |
| 更改大小写 | `text_case` |
| 修剪首尾空白 | `trim_text` |
| 统计字符 | `count_text` |
| 拆分文本 | `split_text` |
| 合并文本 | `join_text` |

## 变量

| 操作 | 类型 ID |
| --- | --- |
| 增加变量 | `create_variable` |
| 获取变量 | `get_variable` |
| 添加到变量 | `append_variable` |

## 计算

| 操作 | 类型 ID |
| --- | --- |
| 数字 | `number` |
| 计算 | `calculate` |
| 计算表达式 | `calculate_expression` |
| 随机数字 | `random_number` |

## 字典

| 操作 | 类型 ID |
| --- | --- |
| 字典 | `dictionary` |
| 获取字典值 | `get_dictionary_value` |
| 设置字典值 | `set_dictionary_value` |
| 获取字典所有键 | `dictionary_keys` |

## 流程控制

| 操作 | 类型 ID |
| --- | --- |
| 如果 | `if` |
| 从菜单中选择 | `menu` |
| 重复 | `repeat` |
| 停止快捷指令 | `stop` |
| 重复处理每一项 | `repeat_each` |

## 系统

| 操作 | 类型 ID |
| --- | --- |
| 发送通知 | `notification` |
| 创建日程 | `calendar` |
| 运行 Shell 命令 | `shell` |
| 发送提醒 | `reminder` |

## 应用

| 操作 | 类型 ID |
| --- | --- |
| 安装软件 | `install_apk` |
| 打开应用 / Intent | `intent` |
| 打开软件 | `open_app` |

## 列表

| 操作 | 类型 ID |
| --- | --- |
| 列表 | `list` |
| 选取列表内容 | `get_list_item` |
| 从列表中选择 | `choose_from_list` |
| 排序列表 | `sort_list` |
| 统计列表项目 | `count_items` |
| 反转列表 | `reverse_list` |

## 日期时间

| 操作 | 类型 ID |
| --- | --- |
| 当前日期与时间 | `current_date` |
| 格式化日期 | `format_date` |
| 读取指定日期日程 | `read_calendar` |

## 编码与摘要

| 操作 | 类型 ID |
| --- | --- |
| 计算文本摘要 | `hash_text` |
| Base64 编码 | `encode_base64` |
| Base64 解码 | `decode_base64` |
| URL 编码 | `encode_url` |
| URL 解码 | `decode_url` |

## 网络与链接

| 操作 | 类型 ID |
| --- | --- |
| 获取 URL 内容 | `get_url` |
| 打开链接 | `open_url` |

## 设备

| 操作 | 类型 ID |
| --- | --- |
| 获取剪贴板 | `get_clipboard` |
| 拷贝到剪贴板 | `set_clipboard` |
| 获取设备信息 | `device_info` |
| 获取截图 | `screenshot` |

## 文件

| 操作 | 类型 ID |
| --- | --- |
| 创建文件夹 | `create_folder` |
| 创建文件 | `create_file` |
| 读取文件 | `read_file` |
| 写入文件 | `write_file` |
| 追加到文件 | `append_file` |
| 获取文件列表 | `list_files` |
| 删除文件 | `delete_file` |
| 获取文件信息 | `file_info` |

## 变量与输出

值包括文本、十进制数字、布尔值、字典、有序列表。变量名为 1–64 个 Unicode 字母/数字/下划线，以字母或下划线开头，`input` 为保留名。普通字面量默认是文本。

- `create_variable`：`name`、`valueType`（auto/text/number/boolean）、`value`。名称必须未存在；新建默认生成唯一名称。
- `get_variable`：`name`，读取已存在变量。
- `append_variable`：`name`、`value`，已有标量先转列表，新标量追加一项，新列表依序加入其所有项。不存在的名称报错。
- 通用 `resultVariable`：把该操作输出存到命名变量（存在时更新）。要求输入默认生成 `input_1` 等独立名称。
- `{{input}}` 是上一操作结果，`{{name}}` 是命名变量，`{{@step-id}}` 是指定操作的最近一次输出。引用整个字段时保留类型，嵌在文本中时转换为文本。
- 模板单次替换，变量内容中的模板不会递归求值。引用未执行分支中的输出或尚未定义变量会报错。重复中引用某一步得到它最近一轮的结果。
- 操作下“将此操作增加为变量”插入新变量操作，默认值为 `{{@该操作ID}}`。复制分支时内部的操作输出引用映射到复制后的 ID。

## 字典和列表

字典 `entry.N.key/type/value`，列表 `item.N.type/value`，N 从 0 开始，是序列化索引。UI 的列表选择编号从 1 开始，二者不要混淆。字典键唯一、非空；最多 256 项。列表每项可以使用前面的变量，保留其类型；最多 256 项，可为空列表。

`get_list_item`：`source`，`selection` 为 first/last/random/index/range，`index` 和 `endIndex` 为从 1 开始的编号，范围包含两端。越界报错。

`choose_from_list`：`source`、`title`、`multiple`。单选返回该项原类型，多选返回列表；字典向用户显示键、返回选中键对应的值。取消即停止流程。列表排序 `numeric=true` 时按数字，否则按不区分大小写的文本排序；`descending=true` 为倒序。

## 输入、计算与流程控制

`ask_input`：`title`、`prompt`（浅色占位提示）、`initial`、`inputType`（text/number/date）。数字输入验证后输出 Number；日期时间使用 ISO 时间或 Unix 毫秒；取消停止后续执行。

`calculate`：`left`、`operation`（+ - * / % ^）、`right`；旧版 `expression` 参数仍兼容。`calculate_expression`：`expression`，可写 `{{变量}}` 或直接写数字变量名，如 `price * quantity`。支持括号、十进制、科学计数、整数幂 -100..100、abs/min/max/round，最长 4096 字符；使用 Decimal128，除零/非数字报错。没有执行 JavaScript 或任意代码。

`if`：`left`、`operator`、`right`；then 分支必需，else 可移除。支持等于/不等于、包含、不包含、比较、空值、真、正则匹配。只执行选中的分支，再继续后续操作。

`menu`：`title`，branch.label 为选项标题；选择后只执行该选项下的步骤，`menu_choice` 保存标签。`repeat`：count 为 1..1000；`repeat_each`：source 为列表/字典值列表，提供 repeat_item 和从 1 开始的 repeat_index，输出各轮最终结果组成的列表。嵌套循环恢复外层循环变量。`stop` 立即结束整个快捷指令。

运行前校验整个启用树，不先执行部分副作用再发现未注册动作；运行中动态错误仍会停止，之前的外部副作用不回滚。

## 文本、预览与通知

替换动作 source 默认为 `{{input}}`，find/pattern 和 replacement 可引用变量。普通替换为字面替换；正则采用 RE2/J，groups=true 时 replacement 支持 `$1` 等捕获组。匹配和拆分输出真正的 List，合并接受 List/Dictionary 值列表。

`quick_look` 在其执行位置弹出指定 value，可复制文本；没有这个动作就不自动弹出预览。最后一个 quick_look 之后的动作仍继续执行。

`notification`：title/body 均可自定义及插入变量。`reminder`：只填 body，标题固定 Shortcuts，立即发出通知；它不是定时任务管理器。定时提醒用时间自动化调用带通知的快捷指令。系统通知权限或渠道被禁用会报错。

## 文件与网络

文件操作均使用 folder 自定义相对文件夹；文件操作还有 filename。create_file 创建空文件且拒绝覆盖；write_file 覆盖/新建，append_file 追加/新建，content 默认为上一步。read_file 返回 UTF-8 文本，其他写操作返回相对路径，list_files 返回该文件夹下文件的路径列表（不递归），delete_file 仅删除指定文件，file_info 返回 name/path/size（字节）/modified（Unix 毫秒）字典。可将前面创建的相对路径直接传给 filename，避免重复文件夹前缀。

get_url 为 HTTPS GET，自动重定向最多 6 次、连接/读取各 15 秒，非 2xx 报错，响应最多 1 MiB；输出文本受单步 256 Ki 字符限制。没有 POST/自定义请求头/二进制下载。open_url 用系统 VIEW Intent 打开链接。

## Android 与特权接口

open_app 保存从启动器应用列表选择的 packageName/className。intent 支持 action/data/mimeType/packageName/className/categories 和 extra.N.key/type/value（string/int/long/double/boolean，最多 50 项），只跳转外部公开 Activity，不绕过 Android 组件权限。

calendar：title/start/end 必填，时间为 ISO 日期时间或 Unix 毫秒，end 必须晚于 start；可填 description/location/calendarId/reminderMinutes。通过系统 CalendarContract 新增事件和可选提醒，需要设备存在可写日历并授权读写日历；多日历时选择目标。

install_apk：添加时通过系统选择器选 APK，持久保留读权限。标准模式申请“安装未知应用”权限后调用系统安装器；Root 验证 UID 0，Shizuku 每次验证 Binder/授权，再将 APK 通过 stdin/文件描述符提供给 pm install。读取临时副本限制 1 GiB，安装超时 120 秒，必须获得退出码 0 和 Success 响应；不会把缓存目录设为全局可读。

shell：command、timeoutSeconds（1..120）。标准模式应用 UID、Root su -c、Shizuku AIDL UserService；stdout/stderr 各有上限，非零退出码停止流程，变量 shell_stdout/shell_stderr/shell_exit_code 保留。仅作用于已选模式，不自动提升或降级权限。

## 与苹果操作的对应关系

依据 Apple 官方用户指南核对变量、输入、列表、菜单、条件和重复等核心模型。本版的表格/类型/平台接口按 Android 实现，不宣称已移植全部苹果或第三方动作。

| 官方能力类别 | 本版情况 |
| --- | --- |
| 变量、操作输出、添加到变量 | 已实现，追加形成有序列表 |
| 列表、选择列表、获取项目 | 已实现，含随机、1 起始编号、范围、多选 |
| 文本、字典、输入、计算、流程控制 | 已实现上述清单；没有任意脚本求值 |
| 文件、日程、通知、打开应用 | 使用 Android 对应的目录、系统 API、Intent |
| 个人自动化 | 首批 9 类 Android 状态/事件条件，详见 README 的调度边界 |
| Apple Health / HomeKit / Siri / Apple Intelligence | 未接入，依赖 Apple 平台能力 |
| 第三方 App 专用动作 | 可通过公开 Intent/URI 衔接；尚无独立插件适配 |
| 照片、联系人、定位、音视频处理 | 本轮未实现 |

参考：[Apple 列表操作](https://support.apple.com/guide/shortcuts/use-list-actions-apd4dcacc115/ios)、[Apple 变量类型](https://support.apple.com/guide/shortcuts-mac/variable-types-apdd2b316022/mac)、[Apple 要求输入](https://support.apple.com/guide/shortcuts/use-the-ask-for-input-action-apd68b5c9161/ios)、[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[Android 日历提供程序](https://developer.android.com/identity/providers/calendar-provider)、[Shizuku API](https://github.com/RikkaApps/Shizuku-API)。


## 0.6 新增操作契约

`number`：value 默认 0。支持手填十进制、科学计数及变量。完整引用保留原类型，再按现有严格规则转换为 Number；布尔值、字典、列表、非数字文本不自动变为零。可在输入栏选择操作输出。

`read_calendar`：date 为 yyyy-MM-dd、ISO 时间或 Unix 毫秒；zone 为空表示系统时区，否则为系统支持的 ZoneId。日期输入按指定时区换算成当地一天，边界按日历计算，因此夏令时切换日可为 23/25 小时。需要 READ_CALENDAR。查询可见日历的展开实例，覆盖重复、跨天、全天事件，全天事件按 UTC 日期处理。输出 List<Dictionary>；字段 id/title/start/end/allDay/description/location/calendarId。start/end 是 Unix 毫秒，allDay 是布尔值；无结果为空列表，超过 256 项或总输出限制时报错。

`screenshot`（1.0.0）：filename 默认 screenshot.png，支持变量且必须为单个 .png 文件名。通过系统逐次授权后的 MediaProjection + ImageReader 截图，保存到公共 Pictures/Screenshots（通常 /sdcard/Pictures/Screenshots），输出 content:// 媒体 URI。Android 10+ 使用 MediaStore 的 RELATIVE_PATH 和 IS_PENDING；Android 8/9 按需申请旧版写入权限。保存失败会清理未完成记录，不覆盖已有照片。同名图片由系统或递增后缀处理。旧 XML 的 folder 字段不再影响截图目录。快速查看和图片操作支持该 URI。单次 PNG 最多 32 MiB、最多 1600 万初始像素；受 FLAG_SECURE 保护的内容仍由系统屏蔽。

`format_date`：source 接受 yyyy-MM-dd，局部日期时间按所选 zone 解释；带 Z/偏移的时间保持原始瞬间再格式化。zone 弹框支持搜索全部系统时区，空值跟随系统。日期和格式预设只是参数填写辅助，仍可使用变量与自定义格式。

控制中心入口属于快捷指令管理 UI，不是流程内操作。4 个 TileService 分别存储快捷指令 ID，系统仅允许每个服务有一个磁贴。绑定不会复制流程；修改后运行最新保存内容。占用位置重新绑定会替换旧绑定；删除快捷指令清空绑定，不强制删除用户的系统磁贴。Android 13+ 请求系统添加，拒绝时保留绑定供手动添加。锁屏需解锁，所有模式和操作权限仍按原规则检查。


## 0.7 操作补充与行为变化

| 新操作 | type | 输入与输出 |
| --- | --- | --- |
| 显示结果 | show_result | title/body/allowCancel；确定继续，允许取消时取消会结束流程；无输出变量 |
| 获取快捷指令数量 | shortcut_count | 输出 files/shortcuts 中已保存的快捷指令数量，不计草稿、文件夹或自动化 |
| 选择文件 | choose_file | 系统 SAF；可解码 UTF-8 文本直接输出，自定义值 XML 恢复类型；其他文件输出持久 URI |
| 获取图片 | get_image | source 留空调用系统选图；非空校验图片并返回引用 |
| 拍照 | take_photo | 按需申请相机权限，系统拍照 Activity，JPEG 保存到 Photos，输出相对路径 |
| 裁剪图片 | crop_image | source、x/y/width/height 像素，folder/filename；拒绝越界，输出 PNG 相对路径 |
| 获取图片信息 | image_info | source；字典含 width/height/mimeType/size/name/path，单项以 @action-id.width 等引用 |
| 振动 | vibrate | duration/unit/amplitude；1..255，无振幅控制的设备用系统默认强度，无输出变量 |
| 获取 HTML | get_html | url + query.N.key/value；HTTPS GET，输出源码 |
| 查看 HTML | view_html | html；非全屏 WebView，对静态 HTML/CSS 渲染，禁用脚本、外部网络、文件/Content 访问；无输出变量 |
| 获取文件元数据 | file_metadata | folder/filename；大小、路径、修改时间等；原 file_info 改为读取内容 |

等待保留 milliseconds 参数作为数值字段，新增 unit=milliseconds/seconds，单位切换会换算数值。振动同样支持单位和 +/-。最终时长必须是 0..60000 的整数毫秒；允许精确到毫秒的小数秒。

calculate 新增 sin/cos/tan/asin/acos/atan/abs/sqrt/round/min/max/gcd/lcm/mod。一元函数只使用 left；二元函数用 left/right。% 代表 left*right/100。calculate_expression 仍支持中缀 % 取余、mod(a,b) 非负模、gcd/lcm 整数运算；三角函数遵循设置中的角度单位（默认角度，可选弧度、百分度），正三角函数转换输入，反三角函数转换输出；非三角函数和 pi 常量不受单位影响。gcd/lcm 拒绝非整数，定义域和除零错误会停止流程。Rust bigdecimal 对十进制步骤按 34 位有效数字舍入，超出既有限制的精度/指数/嵌套拒绝；三角函数经过有限 f64 计算。主机 Kotlin 解析器用于 JVM 测试，Android 明确加载 Rust 库并注入计算后端。

append_text 新增 target，默认 {{input}}；输出目标加 value。generate_uuid 新增 seed，默认 {{input}}，相同非空 UTF-8 种子通过 nameUUIDFromBytes 得到相同 UUID v3；空种子产生随机 UUID。

encode_base64：inputType=text/image/file，source 为文本或文件引用，lineLength=0/64/76（文件参数可为 4..256）；二进制输入最多 3 MiB，编码结果最多 4 Mi 字符。decode_base64 接受 ASCII 空白换行，输出 UTF-8 文本；没有把任意二进制自动转换成图片的解码输出。普通步骤和容器仍受原文本限制。

get_url/get_html 使用 query.N.key/type/value；key/value 可插变量，按 UTF-8 URL 编码追加在 fragment 前，不覆盖 URL 中已有参数。最多 50 项，最终 URL 最长 8192。现有网络动作是 HTTPS GET，不包含 POST、认证管理或 Cookie 会话。

写文件遇到字典/列表/数值/布尔时编码为带版本的值 XML，读取文件或 file_info 恢复原类型，文本文件仍是普通 UTF-8。append_file 是字节层面的文本追加，不用于合并多个结构化值文档。工作目录以外的绝对目录需要 external=true 和 ROOT；标准模式仍保持应用目录边界，其他文件可用 SAF 选择。

ShellPolicy 在模板展开后评估命令，禁止根目录批量删除和显式裸设备写入，其余可能写入/删除及复杂脚本展示当前目录清单、命令并要求确认。确认不是 Shell 沙箱，也不宣称能静态证明任意脚本或被调用程序的安全性。用户确认后的命令仍具有所选后端的权限。Root 文件读写使用引用路径及 stdin，不拼接未引用的文件名或把数据内容当作 Shell 代码。Root 文本读取目前还受 Shell 管道 64 KiB 上限约束。

NFC 自动化按卡片 ID 匹配，录入不写卡。前台主页使用 ReaderMode；后台依赖 Android 将匹配技术类型的卡片交给 Shortcuts，可能需要用户在系统中选择默认处理应用，不承诺锁屏静默扫描所有卡片。没有 NFC 硬件时入口停用。

指定 Wi-Fi 自动化按 SSID 的连接上升沿触发。Android 10+ 对普通应用读取历史网络列表有限制：标准模式可能只获得当前网络或空列表，可手填名称；Root/Shizuku 通过 cmd wifi list-networks 尝试读取历史列表。SSID 读取受精确位置、附近设备、系统定位开关和后台位置授权影响；没有权限时不会伪造匹配。


## 当前位置与角度单位

`current_location`（设备分类）：按需请求前台粗略/精确位置权限，接受系统近似位置授权。通过已开启的 GPS/网络定位获取一次更新，最多等待 30 秒；停止流程会注销监听。没有可用定位来源或超时明确报错，不将任意历史缓存坐标冒充当前位置。经纬度采用十进制度，定位精度和海拔以米为单位。输出为字典，字段 latitude、longitude、accuracy、altitude、timestamp（毫秒时间戳）、provider；无法提供的精度/海拔为空文本。经纬度及其他字段可分别选作魔法变量，也可用获取字典值读取。设置的权限分组显示位置授权状态并可请求授权；精确程度仍由 Android 管理。未默认申请后台位置权限。

设置 → 计算设置 → 角度单位：DEGREES（默认，整周 360°）、RADIANS（整周 2π rad）、GRADIANS（整周 400 gon）。每次运行读取一次设置，计算和计算表达式共享同一单位，包含 sin/cos/tan/asin/acos/atan 和嵌套调用，后台自动化、小组件和磁贴也使用该设置。例如默认 sin(30)≈0.5、asin(0.5)≈30；百分度 sin(100)≈1。旧 settings.xml 缺少 angleUnit 属性时使用角度。


## 搜索、数学输出和取整补充

主页搜索按钮位于文件夹按钮旁，点按后在标题栏显示 Material 3 SearchBar，结果在原主页列表中按名称实时筛选，不进入全屏搜索。匹配忽略大小写及搜索词两端空格，搜索范围包含所有文件夹；点击结果编辑，播放按钮运行，清除或返回恢复完整列表。

Rust 与 JVM 数学实现对三角/根式的 f64 结果统一保留 15 位有效数字，避免把二进制尾数显示成 `0.49999999999999994`。例如角度模式 sin(30)=0.5、asin(0.5)=30；整象限 sin/cos 返回准确的 0/±1，tan 在奇数倍四分之一周报定义域错误。不是把所有小数截断或将所有小量归零；普通十进制运算保持原来的 34 位有效数字规则。

计算及表达式均支持 sqrt(x)、cbrt(x)、root(x,n)。n 为 1..1000 的整数；立方根和奇次方根接受负数，负数偶次方根报错。普通计算选择 root 时，右侧数值作为根指数。

独立操作 `round_number` 属于计算分类：source（默认上一步）、rounding=nearest/up/down（默认 nearest）、digits（默认 0，整数 −100..100）。输出 Number，可设置命名变量、添加变量或直接选作魔法变量。普通 calculate 的 operation=round 使用相同 rounding/digits 参数。nearest 为 HALF_UP，up 向正无穷，down 向负无穷；负数时 ceil(−1.234,2)=−1.23，floor(−1.234,2)=−1.24。表达式支持 round(x,n)、ceil(x,n)、floor(x,n)，省略 n 等于 0。负位数用于十位、百位等，数值输出不保留无意义的末尾零。

编辑器拖拽排序和从动作库拖入时，候选插入位置会展开 56dp 的主题色空隙；分支内部或结束标记之后的空隙对应真实插入范围。指针停在展开空隙内时保持目标，避免目标因让位动画反复跳动。

诊断文件仅在未捕获异常导致崩溃时同步写入 crash-*.log，保留堆栈、线程、设备和当前步骤。普通运行、启动、权限回调与已处理错误不写 trace；日志查看/导出仅包含崩溃日志。历史版本已产生的 trace 文件不会继续追加，也不显示在导出内容中。

`current_date` 改名为“当前日期与时间”，XML 类型标识保持不变。参数 format 支持自定义 DateTimeFormatter 模式，zone 留空跟随系统，也可用时区选择器；输出 Text，不再返回 Unix 时间戳。新操作根据当前语言地区初始化格式，简中为 yyyy-MM-dd HH:mm:ss，美国为 MM/dd/yyyy HH:mm:ss，英国为 dd/MM/yyyy HH:mm:ss，德国为 dd.MM.yyyy HH:mm:ss，日本/台湾为 yyyy/MM/dd HH:mm:ss。时间部分始终默认 HH:mm:ss。自定义格式保存后不随语言切换被覆盖；旧操作缺少 format 时使用当前地区默认。默认格式及 ISO 的带空格日期时间可继续传给格式化日期、读取日程等操作；任意自定义文本不能自动推断为日期。
