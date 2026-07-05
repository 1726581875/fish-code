本项目用于个人学习与探索Harness的基本原理，代码使用本人熟悉的Java语言以及AI辅助生成，希望能更直观探索Harness，完整理解现在各种agent工具的原理。

## 工作目录

工具读写文件、搜索文件和执行命令都会限制在当前工作目录内。

- 配置默认目录：在 `~/.fish-code/config.json` 中设置 `workspace_dir`
- 终端启动指定目录：`java ... org.example.TerminalStart /path/to/project`
- 终端运行时切换：`/cwd /path/to/project`
- Web 页面切换：底部工具栏输入目录后点击“切换”
