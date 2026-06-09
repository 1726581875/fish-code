package org.example.tool;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public class ReadFileTool extends Tool {

    public ReadFileTool() {
        super("read_file", "读取指定路径的文本文件内容。可用offset和limit读取大文件的指定区间",
                new Param("path", "string", "文件路径", true),
                new Param("offset", "integer", "起始字符位置（默认0）", false),
                new Param("limit", "integer", "最大读取字符数（默认8000）", false));
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        String path = args.get("path").getAsString();
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        int limit = args.has("limit") ? args.get("limit").getAsInt() : ToolConstants.OUTPUT_TRUNCATE_CHARS;

        File file = ToolUtils.resolveFileSafe(path);
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
        int totalLen = content.length();
        if (offset >= totalLen) {
            return "(文件共 " + totalLen + " 字符，offset 超出范围)";
        }
        int end = Math.min(offset + limit, totalLen);
        String result = content.substring(offset, end);
        if (end < totalLen) {
            result += "\n...(已显示 " + offset + "-" + end + " / " + totalLen + " 字符)";
        }
        return result;
    }
}
