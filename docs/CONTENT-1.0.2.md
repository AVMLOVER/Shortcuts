# 1.0.2 内容与共享操作

初版新增 23 项操作；Re-Release 增加显式的设置变量入口与三项 URL 操作，操作库共 106 项；原有 `join_text`、`hash_text` 保留 XML 类型标识并扩展配置。全部新增文字提供简体中文、英语、德语、日语、繁体中文资源；简体资源统一使用“图像”。

## 变量栏、空流程与条件性重复

变量类操作始终加入变量栏并隐藏 `inVariableBar` 开关。其他有输出的操作提供该开关，默认关闭，`share_input` 及五项 `get_*_from_input` 默认开启。开启后，作用域内后续操作的底部变量栏显示该输出，点击直接插入胶囊变量；关闭不影响手动选择魔法变量或运行。注释、共享、等待返回等无输出动作不提供该开关。

“完成”不会保存没有操作的快捷指令；新建空流程直接关闭，已有条目删除全部操作后完成则移除该条目及对应的完成结果、控制中心绑定。空 XML 导入项会跳过，全空导入报错。放弃修改仍按原有语义保留原流程。

`repeat_until` 只有一个 `body` 分支，至少运行一次；停止条件放在“结束重复”处。使用 `left`（输出/变量）、`operator`、`right`（比较值/变量），完全复用如果的全部算子及类型规则，包括 greater/less/greater_equal/less_equal、equals/not_equals、contains/not_contains、is_empty/is_not_empty/is_true、matches。结束条件可选择本轮已完成操作的输出。匹配时主输出保持最后一轮结果；`{{@操作ID.repeatCount}}` 独立输出数字类型的重复次数，在魔法变量中可选择。嵌套循环恢复外层 repeat_index。当前实现最多 100000 次；条件性重复内部及其子调用另共享最多 1 亿步预算，普通执行仍共享 5000 步。

## 因数与新增图像/PDF

- `factor_number`：`factorType` 为 prime_factors/divisors/is_prime。前两项输出文本列表，质数判断输出布尔值；prime_factors 可设 `powers=true` 将重复因数显示为 `2^3`。Rust 使用确定性 64 位 Miller–Rabin 与有步数上限的 Pollard rho。支持无符号 64 位整数，分解要求正整数；1 的质因数为空，正因子为 1。因子列表仍受 256 项值容器上限约束，超限报错。
- `resize_image`：width/height 指定像素，某一边为 0 时自动计算；keepAspect 默认开启，两个边界均给出时等比例适配。支持运行时预览。
- `compress_image`：quality 为 0–100%，指编码质量而非目标文件大小比例；imageFormat 为 jpeg/webp。支持滑条、直接输入和运行时预览；JPEG 将透明区域填白，输出扩展名须与格式对应。
- `text_image`：source 为文本，width、fontSize、padding、alignment 控制排版；foregroundColor/backgroundColor 可用 M3E 取色器，留空跟随主题，transparentBackground 可开启透明背景。输出 PNG。
- `make_pdf`：pdfInput 为 auto/text/images，支持文本和图像混合；pageSize 为 a4/letter，fontSize、margin 为点。文本自动分页，图像逐张居中适配页面。最多 100 页、32 MiB。编辑器可通过系统文件窗口选择默认目录，`saveDirectoryUri` 保存持久的目录授权；未设置或授权失效时，运行会打开系统“保存文件”窗口。输出保存后的 `content://` 文件 URI，可读取或共享；原有 folder 参数不再决定 PDF 保存位置。图像页为限制合成内存会缩小采样。

## 从共享表单运行

添加 **从共享表单获取内容**（`share_input`）并保存，再从其他应用分享至 Shortcuts。选择界面只列出该操作及其父分支已启用的已保存快捷指令，可限定接收 any/text/images/urls/files。外部 Intent 不会自动执行，仍须用户点选流程。

应用按已保存流程启用或隐藏分享入口，并通过 ShortcutManagerCompat 发布直接分享目标。系统目标受数量、排序与缓存限制；Shortcuts 的半屏界面列出全部适用流程。删除、编辑或导入保存后同步目标，草稿不发布。

支持 `ACTION_SEND`、`ACTION_SEND_MULTIPLE`，文本最多 256 Ki 字符；每次最多 16 个文件，单文件 32 MiB，总计 64 MiB。接收期间将已授权的 `content://` 文件原子复制到软件工作目录的 `Shared/`，保留扩展名，不在运行后自动删除。

引擎为每次运行独立保留原始有类型输入，不受前面操作改变上一步输出的影响。“任何内容”返回 `_shared` 标识及 `text`、`images`、`files`、`urls` 字典；图片同时属于文件。限定类型时输出对应文本或列表。

## 从输入提取

| 类型 | 输出 |
| --- | --- |
| `get_text_from_input` | 文本表示，列表逐项换行；共享文本优先；只有文件时读取 UTF-8 文本。文件最多 1 MiB，输出最多 256 Ki 字符，二进制报错。 |
| `get_images_from_input` | 验证可解码的本地图片或已授权 URI，返回引用列表。 |
| `get_urls_from_input` | 从文本提取 HTTP(S)、含 `://` 的自定义协议、mailto、tel，保留平衡括号，返回列表。 |
| `get_files_from_input` | 工作目录文件或内容 URI 的引用列表。 |
| `get_numbers_from_input` | 数字列表；支持正负号、小数、科学计数法、全角数字和 `1,234.56` 分组。小数点使用 `.`。 |

文件遵循既有目录及 URI 授权，不自动下载网页图片、不进行 OCR。可用“重复处理每一项”继续处理列表。

## 图片操作

`source` 接收图片、图片列表或共享输入，输出为 `folder` 中的 PNG 路径。`filename` 留空自动生成，指定时须以 `.png` 结束。输入的 EXIF 旋转/镜像信息会应用到显示方向。

| 操作 | 参数 |
| --- | --- |
| `mask_image` 蒙版图像 | `maskShape`：ellipse/circle/rounded/rectangle/custom；`radius`：0–50% 圆角；`secondary`：图像蒙版，留空取输入第二张；`maskMode`：alpha/luminance；`invert`：反转。 |
| `overlay_image` 叠放图像 | 第一张为底图，`secondary` 或第二张为叠图；`scale`：底图宽度的 1–400%；`opacity`：0–100%；`rotation`：−360–360 度；`offsetX/Y`：相对中心的像素偏移，−16384–16384。 |
| `flip_image` 翻转图像 | `flipDirection`：horizontal/vertical/both。 |
| `combine_images` 拼接图像 | 1–16 张；`direction`：horizontal/vertical/grid；`spacing`：0–1024 像素；`columns`：1–16；`matchDimensions`：统一尺寸并保持比例。留白透明。 |

四项均提供 **运行时预览并调整** 开关，默认关闭。开启后显示 M3E 对话框，通过选项、滑条和数值输入调整，确定后按原始像素参数输出，取消停止流程。预览缩小采样并合并连续滑条更新，不逐帧写入文件。

单张输入/输出最多 1600 万像素，输出边长最多 16384；总合成内存另按进程堆上限检查，超限报错。蒙版使用带透明通道的 ARGB 缓冲，避免 Android 将 ALPHA_8 解释为画笔覆盖率而无法清除形状外区域；后台处理和 I/O，完成、取消或失败时释放位图。

## 文本、计数、哈希、统计

- **拼接文本**（`join_text`）：`separatorType` 为 newline/space/comma/none/custom；custom 使用 `separator`。新操作默认换行；旧 XML 未设 separatorType 时保留旧 separator。
- **注释**（`comment`）：纯文本 `note`，不执行模板，不改变传递的数据，不产生魔法变量或输出变量。
- **计数**（`count`）：`countType` 为 items/characters/words/sentences/lines；输出数字。字符按 Unicode 码点，词/句按当前地区 BreakIterator 分段，空文本行数为 0。
- **获取哈希**（`hash_text`）：`inputType` 为 text/image/file，文本 UTF-8，文件读取原始字节；输出小写十六进制。15 种算法：MD5、SHA-1、SHA-224/256/384/512、SHA3-224/256/384/512、BLAKE2b-512、BLAKE2s-256、RIPEMD-160、SM3、CRC32。使用 Bouncy Castle 1.86 轻量实现，不修改系统提供者。
- **计算统计数据**（`statistics`）：1–256 项数字列表或以逗号、分号、空白分隔的数字文本。`statistic` 为 sum/average/minimum/maximum/median/mode/range/variance/stddev/count。Android 经 JNI 调用 Rust，34 位有效数字。方差/标准差为总体统计，众数输出全部并列项的升序列表。
- **格式化数字**（`format_number`）：`digits` 为 0–20 小数位，`grouping` 千位分隔，`locale` 为 BCP 47 语言标签（空白跟随系统）；四舍五入并补足小数位，输出文本。
- **获取当前电池电量**（`battery_level`）：系统电池 level/scale 转为 0–100 的整数，不附 `%`。

## 共享结果和等待返回

首页、编辑器、共享运行页的结果卡片均有共享按钮。独立 **共享**（`share`）也接受变量或上一步输出，打开系统 Sharesheet。文件通过只读 FileProvider URI 分享，多文件用 SEND_MULTIPLE；缓存超过 24 小时后在后续分享时清理。该操作不产生自己的输出变量。

**等待返回**（`wait_return`）放在打开应用、打开 URL 或 Intent 后：启动时登记离开事件，返回 Shortcuts 后继续；等待动作开始前已快速返回也可识别。没有前置跳转且已在前台时直接继续；无前置登记且在后台时使用既有返回应用通知。若 Intent 未造成离开，最多等待 3 秒确认。进程被系统结束后不会重放或恢复操作，也不推断外部应用任务是否完成。

## 平台依据

- [Apple：从其他 App 启动快捷指令](https://support.apple.com/guide/shortcuts/launch-a-shortcut-from-another-app-apd163eb9f95/ios)。
- [Android：接收共享内容](https://developer.android.com/training/sharing/receive)、[分享文件](https://developer.android.com/training/secure-file-sharing)、[直接分享目标](https://developer.android.google.cn/develop/ui/compose/sharing/direct-share-targets?hl=en)。
- [Bouncy Castle Java 1.86 及 MIT 许可](https://www.bouncycastle.org/download/bouncy-castle-java/)。
