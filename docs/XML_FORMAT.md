# XML v1 / v2 与扩展约定

所有文档使用 UTF-8。单个快捷指令及设置最大 1 MiB，快捷指令合集最大 8 MiB。`version` 为必填的 schema 版本，和应用版本独立；不支持的版本拒绝加载，未来应显式迁移。应用自身 XML 序列化不使用 Kotlin 反射或类名。

## 快捷指令

```xml
<?xml version="1.0" encoding="UTF-8"?>
<shortcut version="1" id="example-001" createdAt="1793100000000" updatedAt="1793100000000">
  <name>你好</name>
  <steps>
    <action id="step-1" type="text" enabled="true">
      <parameter key="value">你好，Shortcuts！</parameter>
    </action>
    <action id="step-2" type="delay" enabled="true">
      <parameter key="milliseconds">300</parameter>
    </action>
    <action id="step-3" type="output" enabled="true" />
  </steps>
</shortcut>
```

- 文件名必须与 `shortcut.id` 相同。ID 仅接受 1–80 个 ASCII 字母、数字、连字符、下划线。
- 时间为 Unix 毫秒，`updatedAt >= createdAt >= 0`。
- 名称非空且不超过 200 字符；动作不超过 1000 个，动作 ID 不能重复。
- 顺序以 XML 中元素顺序为准；停用动作仍保存在文档中。
- 动作参数是字符串映射，key 不可重复。编码时按 key 排序，让输出可比较。
- 换行、回车和制表符编码为数字字符引用，避免属性值和文本的 XML 换行归一化破坏数据。
- 未注册的动作仍可读取、编辑其他步骤并保存，执行时启用的未知动作会使整个流程在执行前失败。
- 未知结构元素会拒绝加载；未知属性目前忽略。添加新的结构字段需要增加 schema 版本，不能依赖旧版保留未知属性。
- 任何 XML 错误、未来版本或损坏文件都会报告给调用方，不跳过文件、不覆盖数据。

## 设置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings version="1" mode="STANDARD" theme="SYSTEM" />
```

`mode`：`STANDARD` / `SHIZUKU` / `ROOT`。仅保存用户选择，绝不保存已获得权限的标志。文件尚不存在时默认标准模式；存在但损坏则报错。

`theme`：`SYSTEM` / `LIGHT` / `DARK`，缺失时为 `SYSTEM`。该字段为兼容的可选属性，不需要改变 schema 版本。

## 动作参数扩展（兼容旧版）

`resultVariable` 为通用的可选输出变量名。字典条目使用 `entry.<索引>.key/type/value`，Intent Extras 使用 `extra.<索引>.key/type/value`。这些均是原有 action parameter 元素，不改变 XML 结构。运行值类型和变量只存在内存中，值类型在相应动作参数中声明。

示例见 `examples/dictionary-calculation.xml`。详细动作契约见 [ACTIONS.md](ACTIONS.md)。

## 原子写入

仓库在 IO 线程持有实例互斥锁，将完整内容写到同目录临时文件、同步文件描述符，再使用 `ATOMIC_MOVE` 替换目标。失败不使用可能截断旧数据的非原子降级。临时文件以 `.tmp` 结尾，读取只扫描 `.xml`。

互斥范围为同一个仓库实例，应用依赖装配确保单例；不支持跨进程并发写入。文件内容已 fsync，但不提供目录 fsync 或跨文件事务，因此不承诺突然断电时的完整事务耐久性。

## 注册新动作

实现 `ActionHandler`，使用稳定的 `type` 字符串，在 `ActionRegistry` 中显式注册。`validate` 只能校验，不能产生外部副作用；`execute` 接收上一动作的有类型值、变量作用域与运行模式，返回下一步的 `Value`。可取消的实现应使用挂起 API，并传播 `CancellationException`。

特权动作在执行入口即时检查后端能力和真实授权，失败应清楚返回给执行引擎。不要将 `ExecutionMode.ROOT` 本身视为授权，也不要自动降级或自动切换到更高权限。

## 嵌套工作流 v2

没有分支时仍输出 v1；有分支时输出 v2，读取同时支持两者。branch 是 action 的子元素，直接包含顺序 action，不额外包 steps。if 用 then/else，repeat 和 repeat_each 用 body，menu 用稳定分支 ID 和 label。

```xml
<action id="check" type="if" enabled="true">
  <parameter key="left">{{input}}</parameter>
  <parameter key="operator">greater</parameter>
  <parameter key="right">0</parameter>
  <branch id="then" label="">
    <action id="preview" type="quick_look" enabled="true">
      <parameter key="value">Positive</parameter>
    </action>
  </branch>
</action>
```

全树最多 1000 个操作、16 层嵌套，动作 ID 全树唯一，分支 ID 在所属操作内唯一，每个操作最多 30 个分支。禁用动作仍保存；运行前只解析启用动作。

列表项目用 `item.N.type/key/value` 表示，key 对列表无业务含义，N 从 0 开始。`{{@action-id}}` 引用操作输出，按字符串保存于参数中；运行值仅在内存存在。旧 set_variable 是可更新变量的兼容动作，新操作库显示 create_variable/get_variable/append_variable 三种操作。

## 自动化规则

规则在 `files/automations/<id>.xml`，schema 为独立的 version 1，每条最多 64 KiB，最多 100 条。例：

```xml
<automation version="1" id="morning" name="Morning" shortcutId="example-001"
    trigger="TIME" enabled="true" lastMatched="false" lastToken="">
  <parameter key="time">09:00</parameter>
</automation>
```

trigger：TIME / BATTERY_BELOW / CHARGING / DISCHARGING / WIFI_CONNECTED / NETWORK_CONNECTED / SCREEN_ON / SCREEN_OFF / BOOT。TIME 使用 time（HH:mm），BATTERY_BELOW 使用 level（1..100）；其余无必填参数。lastMatched 用于状态上升沿去重，lastToken 记录最近触发的日期和时间。更新运行状态时进行比较后写入，避免监视覆盖用户编辑/停用或重新创建已删除规则。

导入/导出支持单个快捷指令及快捷指令合集，不包含自动化规则、APK 文件本身、工作文件或运行变量。导入不兼容 Apple `.shortcut`/iCloud 快捷指令链接。

## 快捷指令图标（0.4）

shortcut 根元素增加可选 icon / color 属性，如 `icon="heart" color="green"`。它们是稳定的小写标识符，旧文件缺失时默认 layers / purple；仍使用 v1 / v2，不改变分支结构。未知但格式合法的标识符往返保留，界面回退到默认图案/颜色。名称只允许小写 ASCII 字母开头、随后字母/数字/下划线，最多 40 字符。

内置图案：layers、bolt、star、heart、music、folder、calendar、camera、chat、globe、code、book、coffee、home、person、check。

内置颜色：red、orange、yellow、green、teal、blue、purple、pink。图标与颜色是快捷指令元数据，不属于运行变量。


## 自定义颜色与合集（0.6）

color 除原有标识符外，可使用 6 位 RGB 十六进制，例如 color="#25A0FE"，不保存 alpha。其余图标、分支和变量格式保持兼容。UI 中的变量胶囊仍编码为 {{name}} / {{@step-id}}，显示名称不是 XML 身份。

导出全部使用 `<shortcuts version="1">` 根元素，直接包含多个完整 `<shortcut version="1或2" ...>`，不嵌套 XML 声明；最多 1000 条、总文件最多 8 MiB。每个快捷指令遵守原有约束。导入先解码和验证整个文档，拒绝重复快捷指令 ID、DTD、实体、未知结构/版本；为每个导入项生成新的 UUID，保留动作 ID、内部输出引用、图标和颜色。仓库预先编码全部文档并拒绝覆盖已有 ID，写入异常会清理已写入的新条目；突然断电时不承诺跨文件事务。

合集只保存已提交的快捷指令配置。自动化、系统磁贴绑定、工作文件、APK URI 授权、运行结果和草稿不打包。APK 动作跨设备导入后需要重新选择文件。磁贴 ID 映射保存在 Android SharedPreferences 的 control_tiles.xml，键 slot0 至 slot3。


## 文件夹、有类型的工作文件与小组件（0.7）

shortcut 新增可选 folder 属性，为所属文件夹 ID，空值表示不分类；格式与 shortcut.id 相同。文件夹元数据独立存放 files/folders/<id>.xml，复用同一有界 XML 编解码器和原子仓库（无操作的 shortcut 结构，保存名称、图标、颜色）。单项/合集快捷指令导入不会自动创建文件夹，会清空外来 folder 引用；导出不打包文件夹元数据。

有类型的工作文件使用 XML 声明、`<!-- Shortcuts typed value v1 -->` 标记以及 `<shortcuts-value version="1">` 根。其唯一子元素为 v，type 为 text/number/boolean/list/dictionary；列表嵌套 v，字典嵌套 entry key="..."，entry 内唯一 v。文本和属性保留换行字符引用，不接受 DTD/实体、未知版本、重复字典键或超限层级。普通文本不加标记，恰好以保留头开头的文本会被包装为 text 值以避免歧义。

图片信息属性引用是 `{{@action-id.width}}` 等形式；克隆分支会同时重映射基础 action-id 和属性后缀。无输出副作用操作不注册可引用输出，旧 XML 中残留的 resultVariable 字段不再产生值。

小组件配置在 SharedPreferences 的 widgets.xml，widget<系统id> 保存所选快捷指令 ID 列表，最多 8 个；Launcher 负责尺寸与系统 ID。WidgetConfigureActivity 验证系统 ID 属于本应用，空选择返回取消，不创建占位项目。布局为原生 RemoteViews，运行中使用宿主内的圆形进度指示器，避免前台服务高频重画小组件。浮层仅显示文本结果或最后一次快速查看值，最长 4096 字符、最多 10 行，约 7 秒后关闭；完整完成结果仍在应用内保存。


设置 XML 新增可选 `angleUnit="DEGREES|RADIANS|GRADIANS"`，默认 DEGREES，不改变 mode/theme 的兼容行为。位置操作无参数：`<action id="location" type="current_location" enabled="true"/>`；属性引用如 `{{@location.latitude}}`、`{{@location.longitude}}`。位置输出保留数值类型，可继续计算或写成有类型的 XML。

`round_number` 参数为 source/rounding/digits，rounding 取 nearest、up、down，digits 为 −100..100 整数；输出数值并沿用原有 resultVariable/@action-id。calculate 的 round 操作也接受这两个新增配置，缺失时按四舍五入至整数执行。

`current_date` 新增 format 与 zone 参数，输出改为格式化日期时间文本；缺少/留空 format 时使用当前地区默认格式，缺少/留空 zone 时使用系统时区。重命名显示标题不会更改已有动作 ID 或变量引用。
