package org.example.tool;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.example.TerminalStart;
import org.example.core.AgentRun;

public class WriteFileTool extends Tool {

    public WriteFileTool() {
        super("write_file", "创建新文件或覆盖写入整个文件内容",
                new Param("path", "string", "文件路径", true),
                new Param("content", "string", "要写入的完整内容", true));
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        return doExecuteDetailed(args).getContent();
    }

    @Override
    protected ToolResult doExecuteDetailed(JsonObject args) throws Exception {
        String path = args.get("path").getAsString();
        String newContent = args.get("content").getAsString();

        File file = ToolUtils.resolveFileSafe(path);
        JsonObject details = new JsonObject();
        details.addProperty("type", "file_write");
        details.addProperty("path", file.getAbsolutePath());
        details.addProperty("risk", "write");
        if (file.exists() && file.isDirectory()) {
            details.addProperty("error", "path_is_directory");
            return new ToolResult("目标路径是目录，无法写入文件: " + file.getAbsolutePath(), details);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            details.addProperty("error", "parent_create_failed");
            return new ToolResult("创建父目录失败: " + parent.getAbsolutePath(), details);
        }

        String diff;
        if (file.exists()) {
            String oldContent = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())), StandardCharsets.UTF_8);
            if (oldContent.equals(newContent)) {
                details.addProperty("changed", false);
                details.addProperty("oldChars", oldContent.length());
                details.addProperty("newChars", newContent.length());
                return new ToolResult("写入内容与现有文件相同，文件未修改: " + file.getAbsolutePath(), details);
            }
            diff = DiffUtils.buildWriteDiff(file.getAbsolutePath(), oldContent, newContent);
            details.addProperty("oldChars", oldContent.length());
        } else {
            diff = DiffUtils.buildCreateDiff(file.getAbsolutePath(), newContent);
            details.addProperty("created", true);
        }
        System.out.println(diff);

        AgentRun run = TerminalStart.getCurrentRun();
        if (run != null) run.getChangeJournal().capture(file);
        ToolUtils.writeAtomically(Paths.get(file.getAbsolutePath()), newContent.getBytes(StandardCharsets.UTF_8));
        if (run != null) run.getChangeJournal().recordWritten(file);
        details.addProperty("diff", diff);
        details.addProperty("newChars", newContent.length());
        details.addProperty("changed", true);
        if (run != null) run.getTaskState().markModified(file.getAbsolutePath());
        return new ToolResult("文件已写入: " + file.getAbsolutePath(), details);
    }
}
