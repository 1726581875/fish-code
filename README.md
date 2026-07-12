本项目用于个人学习与探索Harness的基本原理，代码使用本人熟悉的Java语言以及AI辅助生成，希望能更直观探索Harness，完整理解现在各种agent工具的原理。

## 工作目录

工具读写文件、搜索文件和执行命令都会限制在当前工作目录内。

- 配置默认目录：在 `~/.fish-code/config.json` 中设置 `workspace_dir`
- 终端启动指定目录：`java ... org.example.TerminalStart /path/to/project`
- 终端运行时切换：`/cwd /path/to/project`
- Web 页面切换：底部工具栏输入目录后点击“切换”

## Agent 工具

- `find_file`：按文件名或 glob 定位文件
- `search_text`：按文本或正则搜索代码内容，返回文件和行号
- `read_file`：支持按行读取并显示行号，也兼容按字符分页
- `edit_file` / `write_file`：修改或创建文件，并返回 diff
- `run_command`：在工作目录内执行命令，支持超时和取消

Confirm 模式下，文件写入和有副作用的命令需要确认；`git status`、`git diff`、`rg` 等只读检查命令可以直接执行。Web 确认卡片支持单次允许、拒绝或允许本次任务的后续操作。

项目文件树中的文件可以作为 `@相对路径` 追加到输入框，Agent 会优先读取这些引用文件。

Agent 回复期间仍可继续输入并发送，后续问题会进入可见队列，并在当前回复结束后依次自动执行。思考状态、工具调用和最终答复会合并显示在同一条 AI 消息中。
