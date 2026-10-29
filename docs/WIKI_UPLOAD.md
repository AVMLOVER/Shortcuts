# Shortcuts Wiki 上传说明

本套文档包含 18 个正文页面，以及 `_Sidebar.md` 和 `_Footer.md`，提供首页、侧栏和页脚。内容来自当前项目实现，没有更新日志。

Wiki 源文件已与 README 一起保存在主仓库 `docs/wiki/`，单独的 GitHub Wiki 由你上传。GitHub Wiki 使用独立仓库，推送项目主仓库不会自动发布 Wiki。

## 方式一：上传已打包文档

1. 解压 `Shortcuts-Wiki.zip`，得到 `wiki` 文件夹和这份说明。
2. 打开 [Shortcuts Wiki](https://github.com/AVMLOVER/Shortcuts/wiki)。如果尚未建立，先在网页创建并保存任意一个初始页面，以初始化 Wiki 仓库；仓库未启用 Wiki 时先在仓库设置启用。
3. 在解压后的目录打开 PowerShell，执行：

```powershell
git clone https://github.com/AVMLOVER/Shortcuts.wiki.git Shortcuts.wiki
Copy-Item -Path './wiki/*.md' -Destination './Shortcuts.wiki/' -Force
git -C Shortcuts.wiki status --short
git -C Shortcuts.wiki add -- '*.md'
git -C Shortcuts.wiki commit -m 'Add Shortcuts user guide'
git -C Shortcuts.wiki push
```

`Home.md` 是首页，侧栏和页脚文件也要复制。这里的复制会更新 Wiki 内同名页面；其他不同名页面保留。提交前可先查看 `git -C Shortcuts.wiki diff`。如果内容相同而 Git 提示没有可提交修改，跳过 commit 即可。

Linux / macOS 的复制命令换成：

```sh
cp wiki/*.md Shortcuts.wiki/
```

其余 Git 命令相同。使用 Wiki 克隆时默认检出的分支直接提交并 push，无需猜测分支名，也不需要强制推送。

## 方式二：从主仓库重新生成

在项目根目录运行 Python 3：

```sh
python tools/package-wiki.py --output-dir ../Shortcuts-Wiki
```

脚本在主仓库旁生成 `Shortcuts-Wiki/wiki/`、`Shortcuts-Wiki/上传说明.md` 和 `Shortcuts-Wiki.zip`。不传参数时输出到主仓库的 `dist/wiki-export/` 及同名 ZIP。

主仓库文档使用带 `.md` 的相对链接，方便在 GitHub 代码页面阅读；打包脚本会校验内部目标，并转换为 GitHub Wiki 的页面链接。因此建议上传打包后的文件，不直接把主仓库 Markdown 原样推到 Wiki。

## 上传完成后

打开 Wiki 检查首页、侧栏以及“变量”“图像与 PDF”“操作参考”几个页面的跳转。若之前有其他页面，需要删除时单独审阅并处理；本包不要求删除已有历史页面。

后续维护可在主仓库修改 `docs/wiki/`，重新打包，再将页面复制到本地 Wiki 仓库提交。

GitHub 官方说明：[本地编辑与推送 Wiki](https://docs.github.com/en/communities/documenting-your-project-with-wikis/adding-or-editing-wiki-pages#adding-or-editing-wiki-pages-locally)。
