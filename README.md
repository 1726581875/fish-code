# Fish Code

Fish Code 是一个用 Java 实现的个人 AI 编程助手，用于学习和探索 Harness、Agent 工具调用、任务状态、确认、验证与回滚等机制。

它适合在本机、可信项目和可恢复的工作区中使用。目前定位仍是个人学习项目，不是操作系统级沙箱，也不建议直接暴露到公网。

## 快速开始

要求：

- JDK 8 或更高版本
- Maven 3.6.3 或更高版本
- 一个 OpenAI Chat Completions 兼容的模型接口

构建可执行包：

```bash
mvn clean package
```

启动 Web 界面，并指定允许 Agent 操作的工作目录：

```bash
java -jar target/fish-code.jar /path/to/project
```

浏览器访问：

```text
http://127.0.0.1:10000
```

首次启动后可以点击页面底部的齿轮按钮配置模型。也可以通过环境变量配置默认 DeepSeek 接口：

```bash
export DEEPSEEK_API_KEY="your-api-key"
export DEEPSEEK_MODEL="deepseek-chat"
java -jar target/fish-code.jar /path/to/project
```

终端模式：

```bash
java -cp target/fish-code.jar org.example.TerminalStart /path/to/project
```

终端中可用 `Shift+Tab` 循环切换运行模式；输入 `/model` 可通过上下键或序号选择模型，
也可用 `/model <序号或模型名>` 直接切换。模型选择会保存到 `~/.fish-code/config.json`。

## 配置

持久化配置保存在 `~/.fish-code/config.json`。常用字段示例：

```json
{
  "workspace_dir": "/path/to/project",
  "web_bind_address": "127.0.0.1",
  "web_auth_enabled": false,
  "allowed_workspace_roots": [
    "/path/to/projects"
  ]
}
```

工具读写文件、搜索文件和执行命令都会限制在当前工作目录内：

- 启动时指定：`java -jar target/fish-code.jar /path/to/project`
- 配置默认目录：设置 `workspace_dir`
- 终端运行时切换：`/cwd /path/to/project`
- Web 页面切换：在顶部工作目录输入框中输入或选择目录

项目文件树中的文件可以作为 `@相对路径` 追加到输入框，Agent 会优先读取这些引用文件。

## Web 登录与局域网使用

服务默认只监听 `127.0.0.1`。监听非本机地址时，Fish Code 会强制要求启用登录，并要求显式配置允许访问的工作区根目录。

```json
{
  "web_bind_address": "0.0.0.0",
  "web_auth_enabled": true,
  "web_user": "fish",
  "web_password": "replace-with-a-strong-password",
  "allowed_workspace_roots": [
    "/path/to/projects"
  ]
}
```

局域网使用时建议放在 HTTPS 反向代理后面，并设置 `web_secure_cookie: true` 或环境变量 `WEB_SECURE_COOKIE=true`。如果直接通过 HTTP 访问，不要启用 Secure Cookie，否则浏览器不会发送登录 Cookie。

登录令牌只保存在 HttpOnly Cookie 中。连续登录失败达到上限后会锁定 5 分钟。`~/.fish-code` 下的配置、会话、任务状态和回滚快照在支持 POSIX 权限的文件系统上会自动限制为仅当前用户可读写。

## Agent 工具

- `find_file`：按文件名或 glob 定位文件
- `search_text`：按文本或正则搜索代码内容，返回文件和行号
- `read_file`：支持按行读取并显示行号，也兼容按字符分页
- `edit_file` / `write_file`：修改或创建文件，并返回 diff；整文件读写上限为 10MB，大型差异会自动降级为摘要
- `run_command`：在工作目录内执行检查、测试和构建，支持超时、输出截断和完整进程树取消
- `update_task`：记录复杂任务的阶段、步骤、下一步和阻塞原因

Confirm 模式下，文件写入和有副作用的命令需要确认；`git status`、`git diff`、`rg` 等只读检查命令可以直接执行。Web 确认卡片支持单次允许、拒绝或允许本次任务的后续操作。

Auto 模式会在本机直接运行模型生成的命令，不是操作系统沙箱。只应在可信项目和可恢复的工作区中使用。

修改文件后，Agent 必须完成达到要求等级的验证，否则任务会以“受阻”结束，不会显示为已完成。

## 会话与任务恢复

- 回复期间可以继续发送问题，后续消息会进入可见队列
- 每次执行都有独立 `runId`
- 取消、确认、工具去重、任务状态和回滚都绑定到具体运行实例
- 任务检查点会随会话保存
- “重试中断任务”会恢复原有步骤和验证状态
- 回滚前会确认文件仍是 Agent 最后写入的版本，避免覆盖用户后续修改

## 开发验证

```bash
mvn test
```

建议在提交前同时执行：

```bash
mvn clean package
java -jar target/fish-code.jar /path/to/a/test/project
```
