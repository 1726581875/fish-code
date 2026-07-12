package org.example.tool;

import com.google.gson.JsonObject;
import org.example.TerminalStart;

import java.io.*;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class SearchTextTool extends Tool {

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 300;
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    public SearchTextTool() {
        super("search_text", "在项目文本文件内容中搜索字符串或正则，返回文件路径、行号和匹配行",
                new Param("query", "string", "要搜索的文本或正则表达式", true),
                new Param("path", "string", "搜索目录或文件，默认当前工作目录", false),
                new Param("glob", "string", "可选文件 glob，如 *.java 或 **/*.ts", false),
                new Param("regex", "boolean", "是否将 query 当作正则，默认 false", false),
                new Param("caseSensitive", "boolean", "是否区分大小写，默认 true", false),
                new Param("maxResults", "integer", "最大匹配数，默认100，最大300", false));
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        String query = args.get("query").getAsString();
        String path = args.has("path") ? args.get("path").getAsString() : ".";
        String glob = args.has("glob") ? args.get("glob").getAsString() : "";
        boolean regex = args.has("regex") && args.get("regex").getAsBoolean();
        boolean caseSensitive = !args.has("caseSensitive") || args.get("caseSensitive").getAsBoolean();
        int maxResults = args.has("maxResults") ? args.get("maxResults").getAsInt() : DEFAULT_MAX_RESULTS;
        maxResults = Math.max(1, Math.min(maxResults, MAX_RESULTS));

        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException e) {
            return "正则表达式无效: " + e.getMessage();
        }

        File start = ToolUtils.resolveFileSafe(path);
        if (!start.exists()) return "路径不存在: " + start.getAbsolutePath();

        File workspace = new File(TerminalStart.getCurrentCwd()).getCanonicalFile();
        SearchState state = new SearchState(workspace, pattern, glob, maxResults);
        search(start.getCanonicalFile(), state, 0, new HashSet<String>());
        if (state.count == 0) {
            return "未找到匹配: " + query;
        }
        if (state.count >= maxResults) {
            state.output.append("...(结果达到上限 ").append(maxResults).append(")\n");
        }
        return state.output.toString().trim();
    }

    private void search(File file, SearchState state, int depth, Set<String> seenDirs) throws IOException {
        if (state.count >= state.maxResults || depth > ToolConstants.MAX_SEARCH_DEPTH) return;
        if (!isInside(state.workspace, file)) return;

        if (file.isFile()) {
            searchFile(file, state);
            return;
        }
        if (!file.isDirectory()) return;

        String canonical = file.getCanonicalPath();
        if (!seenDirs.add(canonical)) return;
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (state.count >= state.maxResults) return;
            if (child.isDirectory() && shouldSkipDirectory(child.getName())) continue;
            search(child, state, depth + 1, seenDirs);
        }
    }

    private void searchFile(File file, SearchState state) throws IOException {
        if (file.length() > MAX_FILE_BYTES || !matchesGlob(file, state)) return;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!state.pattern.matcher(line).find()) continue;
                String display = line.length() > 500 ? line.substring(0, 500) + "..." : line;
                state.output.append(relativePath(state.workspace, file)).append(':')
                        .append(lineNumber).append(": ").append(display).append('\n');
                state.count++;
                if (state.count >= state.maxResults) return;
            }
        } catch (MalformedInputException ignored) {
            // Binary or non-UTF-8 files are outside this tool's scope.
        }
    }

    private boolean matchesGlob(File file, SearchState state) throws IOException {
        if (state.glob.isEmpty()) return true;
        String rel = relativePath(state.workspace, file).replace('\\', '/').toLowerCase();
        String glob = state.glob.replace('\\', '/').toLowerCase();
        String target = glob.contains("/") || glob.contains("**") ? rel : file.getName().toLowerCase();
        return ToolUtils.globMatch(target, glob);
    }

    private static boolean shouldSkipDirectory(String name) {
        return ".git".equals(name) || "target".equals(name) || "node_modules".equals(name)
                || ".idea".equals(name) || ".svn".equals(name) || "__pycache__".equals(name)
                || name.startsWith(".");
    }

    private static boolean isInside(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private static String relativePath(File root, File file) throws IOException {
        return root.toPath().relativize(file.getCanonicalFile().toPath()).toString().replace(File.separatorChar, '/');
    }

    private static final class SearchState {
        final File workspace;
        final Pattern pattern;
        final String glob;
        final int maxResults;
        final StringBuilder output = new StringBuilder();
        int count;

        SearchState(File workspace, Pattern pattern, String glob, int maxResults) {
            this.workspace = workspace;
            this.pattern = pattern;
            this.glob = glob == null ? "" : glob.trim();
            this.maxResults = maxResults;
        }
    }
}
