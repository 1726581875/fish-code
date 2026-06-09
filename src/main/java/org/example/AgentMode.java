package org.example;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public enum AgentMode {

    PLAN("plan", "\u001B[36m", "规划",
            "你处于【规划模式】。你可以使用 read_file 和 find_file 工具全面阅读和分析项目代码。" +
            "基于用户的需求，你需要先制定一个详细的执行计划，包括：将要修改哪些文件、每个文件的具体修改内容、" +
            "以及修改的顺序和依赖关系。请以清晰的结构化格式输出你的计划（如 Markdown 列表或编号步骤）。" +
            "不能使用 edit_file、write_file 或 run_command 实际执行修改。" +
            "用户审核计划后，可切换到 auto 或 confirm 模式执行。\n\n" +
            "工具参数参考：\n" +
            "- read_file(path, [offset], [limit]): path 为必填，offset/limit 为可选行号（从1开始）\n" +
            "- find_file(pattern, [dir]): pattern 支持 glob 如 **/*.java，dir 指定搜索目录",
            new HashSet<>(Arrays.asList("read_file", "find_file"))),

    AUTO("auto", "\u001B[32m", "自动执行",
            "你处于【自动执行模式】。你可以使用全部工具：read_file、find_file、edit_file、write_file、run_command。" +
            "无需用户确认，直接执行修改和命令。请仔细检查后谨慎操作，确保每一步正确无误。\n\n" +
            "工具参数参考：\n" +
            "- read_file(path, [offset], [limit]): path 为必填，offset/limit 为可选行号（从1开始）\n" +
            "- find_file(pattern, [dir]): pattern 支持 glob 如 **/*.java，dir 指定搜索目录\n" +
            "- edit_file(path, oldString, newString): oldString 必须与文件中的内容完全匹配（包括空格和换行），不能包含行号前缀\n" +
            "- write_file(path, content): 创建或覆盖文件\n" +
            "- run_command(command): 执行 shell 命令",
            new HashSet<>(Arrays.asList("read_file", "find_file", "edit_file", "write_file", "run_command"))),

    CONFIRM("confirm", "\u001B[33m", "手动确认",
            "你处于【手动确认模式】。你可以调用全部工具，但在实际执行 edit_file、write_file、run_command 等变更操作前，" +
            "系统会请求用户确认。你可以大胆提出修改方案，用户会逐一审核每个实际操作。\n\n" +
            "工具参数参考：\n" +
            "- read_file(path, [offset], [limit]): path 为必填，offset/limit 为可选行号（从1开始）\n" +
            "- find_file(pattern, [dir]): pattern 支持 glob 如 **/*.java，dir 指定搜索目录\n" +
            "- edit_file(path, oldString, newString): oldString 必须与文件中的内容完全匹配（包括空格和换行），不能包含行号前缀\n" +
            "- write_file(path, content): 创建或覆盖文件\n" +
            "- run_command(command): 执行 shell 命令",
            new HashSet<>(Arrays.asList("read_file", "find_file", "edit_file", "write_file", "run_command")));

    private final String label;
    private final String color;
    private final String shortDesc;
    private final String systemPrompt;
    final Set<String> allowedTools;

    AgentMode(String label, String color, String shortDesc, String systemPrompt, Set<String> allowedTools) {
        this.label = label;
        this.color = color;
        this.shortDesc = shortDesc;
        this.systemPrompt = systemPrompt;
        this.allowedTools = allowedTools;
    }

    public String label() { return label; }
    public String color() { return color; }
    public String shortDesc() { return shortDesc; }
    public String systemPrompt() { return systemPrompt; }

    public AgentMode next() {
        AgentMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public AgentMode prev() {
        AgentMode[] values = values();
        return values[(ordinal() - 1 + values.length) % values.length];
    }
}
