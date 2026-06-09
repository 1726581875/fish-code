package org.example.tool;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class EditFileTool extends Tool {

    public EditFileTool() {
        super("edit_file", "精确替换文件中的指定文本内容",
                new Param("path", "string", "要修改的文件路径", true),
                new Param("oldString", "string", "要被替换的原文本", true),
                new Param("newString", "string", "替换后的新文本", true));
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        String path = args.get("path").getAsString();
        String oldString = args.get("oldString").getAsString();
        String newString = args.get("newString").getAsString();

        File file = ToolUtils.resolveFileSafe(path);
        if (!file.exists()) {
            return "文件不存在: " + file.getAbsolutePath() + "\n提示: 使用 read_file 检查文件是否存在并确认路径正确";
        }
        String oldContent = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())), StandardCharsets.UTF_8);
        if (!oldContent.contains(oldString)) {
            String preview = oldString.length() > 80 ? oldString.substring(0, 80) + "..." : oldString;
            return "文件中未找到要替换的文本\n文件: " + file.getAbsolutePath() + "\n查找内容: " + preview.replace("\n", "\\n") + "\n提示: oldString 必须与文件中的内容完全匹配（包括空格和换行）";
        }
        int idx = oldContent.indexOf(oldString);
        int secondIdx = oldContent.indexOf(oldString, idx + oldString.length());
        if (secondIdx != -1) {
            return "找到多处匹配，请提供更完整的上下文以精确定位";
        }
        String newContent = oldContent.replaceFirst(
                java.util.regex.Pattern.quote(oldString),
                java.util.regex.Matcher.quoteReplacement(newString));
        DiffUtils.printEditDiff(file.getAbsolutePath(), oldContent, newContent, oldString, newString);
        Path filePath = Paths.get(file.getAbsolutePath());
        Path bakPath = Paths.get(file.getAbsolutePath() + ".bak");
        Files.copy(filePath, bakPath, StandardCopyOption.REPLACE_EXISTING);
        Files.write(filePath, newContent.getBytes(StandardCharsets.UTF_8));
        return "文件已修改: " + file.getAbsolutePath() + " (备份: " + bakPath.getFileName() + ")";
    }
}
