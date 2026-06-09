package org.example.tool;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class WriteFileTool extends Tool {

    public WriteFileTool() {
        super("write_file", "创建新文件或覆盖写入整个文件内容",
                new Param("path", "string", "文件路径", true),
                new Param("content", "string", "要写入的完整内容", true));
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        String path = args.get("path").getAsString();
        String newContent = args.get("content").getAsString();

        File file = ToolUtils.resolveFileSafe(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        if (file.exists()) {
            String oldContent = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())), StandardCharsets.UTF_8);
            DiffUtils.printWriteDiff(file.getAbsolutePath(), oldContent, newContent);
        } else {
            DiffUtils.printCreateDiff(file.getAbsolutePath(), newContent);
        }

        Files.write(Paths.get(file.getAbsolutePath()), newContent.getBytes(StandardCharsets.UTF_8));
        return "文件已写入: " + file.getAbsolutePath();
    }
}
