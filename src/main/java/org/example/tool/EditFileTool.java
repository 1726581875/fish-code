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

        File file = ToolUtils.resolveFile(path);
        if (!file.exists()) {
            return "文件不存在: " + file.getAbsolutePath();
        }
        String oldContent = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())), StandardCharsets.UTF_8);
        if (!oldContent.contains(oldString)) {
            return "文件中未找到要替换的文本";
        }
        int count = 0;
        int idx = 0;
        while ((idx = oldContent.indexOf(oldString, idx)) != -1) {
            count++;
            idx += oldString.length();
        }
        if (count > 1) {
            return "找到 " + count + " 处匹配，请提供更完整的上下文以精确定位";
        }
        String newContent = oldContent.replace(oldString, newString);
        DiffUtils.printEditDiff(file.getAbsolutePath(), oldContent, newContent, oldString, newString);
        Files.write(Paths.get(file.getAbsolutePath()), newContent.getBytes(StandardCharsets.UTF_8));
        return "文件已修改: " + file.getAbsolutePath();
    }
}
