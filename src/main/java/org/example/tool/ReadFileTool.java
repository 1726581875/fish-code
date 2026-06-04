package org.example.tool;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public class ReadFileTool extends Tool {

    public ReadFileTool() {
        super("read_file", "读取指定路径的文本文件内容",
                new Param("path", "string", "文件路径", true));
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        String path = args.get("path").getAsString();
        File file = ToolUtils.resolveFile(path);
        if (!file.exists()) {
            boolean isBareName = !path.contains(File.separator) && !path.contains("/");
            if (isBareName) {
                List<String> matches = ToolUtils.searchFiles(path, false);
                if (matches.size() == 1) {
                    file = new File(matches.get(0));
                } else if (matches.size() > 1) {
                    StringBuilder sb = new StringBuilder("找到多个匹配文件:\n");
                    for (String m : matches) {
                        sb.append("  ").append(m).append("\n");
                    }
                    sb.append("请指定完整路径");
                    return sb.toString();
                }
            }
            if (!file.exists()) {
                return "文件不存在: " + file.getAbsolutePath();
            }
        }
        if (file.isDirectory()) {
            StringBuilder sb = new StringBuilder();
            String[] children = file.list();
            if (children != null) {
                for (String child : children) {
                    sb.append(child).append("\n");
                }
            }
            return sb.toString();
        }
        byte[] bytes = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.length() > 8000) {
            content = content.substring(0, 8000) + "\n...(内容已截断)";
        }
        return content;
    }
}
