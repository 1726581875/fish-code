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
        if (offset < 0) {
            return "offset 不能为负数";
        }
        if (limit <= 0) {
            return "limit 必须大于 0";
        }
        limit = Math.min(limit, ToolConstants.OUTPUT_TRUNCATE_CHARS);

        File file = ToolUtils.resolveFileSafe(path);
        if (!file.exists()) {
            boolean isBareName = !path.contains(File.separator) && !path.contains("/");
            if (isBareName) {
                List<String> matches = ToolUtils.searchFiles(path, false);
                if (matches.size() == 1) {
                    File resolved = ToolUtils.resolveFileSafe(matches.get(0));
                    if (resolved.exists()) {
                        file = resolved;
                    }
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
            File[] children = file.listFiles();
            if (children == null) {
                return "";
            }
            java.util.Arrays.sort(children, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            });
            StringBuilder sb = new StringBuilder();
            for (File child : children) {
                sb.append(child.getName()).append(child.isDirectory() ? "/" : "").append("\n");
            }
            return sb.toString();
        }
        if (!file.isFile()) {
            return "不是普通文件: " + file.getAbsolutePath();
        }

        Path filePath = Paths.get(file.getAbsolutePath());
        long totalLen = countChars(filePath);
        if (offset >= totalLen) {
            return "(文件共 " + totalLen + " 字符，offset 超出范围)";
        }

        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            long skipped = skipFully(reader, offset);
            if (skipped < offset) {
                return "(文件共 " + totalLen + " 字符，offset 超出范围)";
            }
            char[] buf = new char[Math.min(limit, 4096)];
            StringBuilder sb = new StringBuilder();
            int charsRead = 0;
            while (charsRead < limit) {
                int want = Math.min(buf.length, limit - charsRead);
                int n = reader.read(buf, 0, want);
                if (n < 0) break;
                sb.append(buf, 0, n);
                charsRead += n;
            }
            String result = sb.toString();
            if (offset + charsRead < totalLen) {
                result += "\n...(已显示 offset " + offset + " 起的 "
                        + charsRead + " / " + totalLen + " 字符)";
            }
            return result;
        }
    }

    private long countChars(Path filePath) throws IOException {
        long total = 0;
        char[] buf = new char[8192];
        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            int n;
            while ((n = reader.read(buf)) >= 0) {
                total += n;
            }
        }
        return total;
    }

    private long skipFully(Reader reader, long chars) throws IOException {
        long skipped = 0;
        while (skipped < chars) {
            long n = reader.skip(chars - skipped);
            if (n <= 0) {
                if (reader.read() < 0) break;
                n = 1;
            }
            skipped += n;
        }
        return skipped;
    }
}
