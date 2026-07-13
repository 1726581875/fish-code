package org.example.tool;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.example.TerminalStart;
import org.example.core.AgentRun;

public class EditFileTool extends Tool {

    public EditFileTool() {
        super("edit_file", "精确替换文件中的指定文本内容",
                new Param("path", "string", "要修改的文件路径", true),
                new Param("oldString", "string", "要被替换的原文本", true),
                new Param("newString", "string", "替换后的新文本", true));
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        return doExecuteDetailed(args).getContent();
    }

    @Override
    protected ToolResult doExecuteDetailed(JsonObject args) throws Exception {
        String path = args.get("path").getAsString();
        String oldString = args.get("oldString").getAsString();
        String newString = args.get("newString").getAsString();
        if (oldString.isEmpty()) {
            return new ToolResult("oldString 不能为空，否则无法安全定位替换位置");
        }

        File file = ToolUtils.resolveFileSafe(path);
        JsonObject details = new JsonObject();
        details.addProperty("type", "file_edit");
        details.addProperty("path", file.getAbsolutePath());
        details.addProperty("risk", "write");
        if (!file.exists()) {
            details.addProperty("error", "file_not_found");
            return new ToolResult("文件不存在: " + file.getAbsolutePath() + "\n提示: 使用 read_file 检查文件是否存在并确认路径正确", details);
        }
        if (!file.isFile()) {
            details.addProperty("error", "not_a_file");
            return new ToolResult("不是普通文件: " + file.getAbsolutePath(), details);
        }
        String oldContent = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())), StandardCharsets.UTF_8);
        if (!oldContent.contains(oldString)) {
            String preview = oldString.length() > 80 ? oldString.substring(0, 80) + "..." : oldString;
            details.addProperty("error", "old_string_not_found");
            return new ToolResult("文件中未找到要替换的文本\n文件: " + file.getAbsolutePath() + "\n查找内容: " + preview.replace("\n", "\\n") + "\n提示: oldString 必须与文件中的内容完全匹配（包括空格和换行）", details);
        }
        int idx = oldContent.indexOf(oldString);
        int secondIdx = oldContent.indexOf(oldString, idx + oldString.length());
        if (secondIdx != -1) {
            details.addProperty("error", "multiple_matches");
            return new ToolResult("找到多处匹配，请提供更完整的上下文以精确定位", details);
        }
        if (oldString.equals(newString)) {
            details.addProperty("changed", false);
            return new ToolResult("替换前后内容相同，文件未修改: " + file.getAbsolutePath(), details);
        }
        String newContent = oldContent.substring(0, idx) + newString + oldContent.substring(idx + oldString.length());
        String diff = DiffUtils.buildEditDiff(file.getAbsolutePath(), oldContent, newContent, oldString, newString);
        System.out.println(diff);
        Path filePath = Paths.get(file.getAbsolutePath());
        AgentRun run = TerminalStart.getCurrentRun();
        if (run != null) run.getChangeJournal().capture(file);
        ToolUtils.writeAtomically(filePath, newContent.getBytes(StandardCharsets.UTF_8));
        if (run != null) run.getChangeJournal().recordWritten(file);
        details.addProperty("diff", diff);
        details.addProperty("changed", !oldContent.equals(newContent));
        details.addProperty("oldChars", oldContent.length());
        details.addProperty("newChars", newContent.length());
        if (run != null) run.getTaskState().markModified(file.getAbsolutePath());
        return new ToolResult("文件已修改: " + file.getAbsolutePath(), details);
    }
}
