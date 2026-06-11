package org.example.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ClaudeHistoryReader {

    private final Path claudeDir;
    private volatile List<JsonObject> projectsCache;
    private volatile long projectsCacheTime;
    private static final long CACHE_TTL_MS = 30_000;

    public ClaudeHistoryReader(String customPath) {
        if (customPath != null && !customPath.trim().isEmpty()) {
            this.claudeDir = Paths.get(customPath);
        } else {
            this.claudeDir = Paths.get(System.getProperty("user.home"), ".claude");
        }
    }

    public Path getClaudeDir() {
        return claudeDir;
    }

    public boolean exists() {
        return Files.isDirectory(claudeDir);
    }

    public List<JsonObject> listProjects() {
        long now = System.currentTimeMillis();
        if (projectsCache != null && now - projectsCacheTime < CACHE_TTL_MS) {
            return projectsCache;
        }
        List<JsonObject> projects = new ArrayList<>();
        Path projectsDir = claudeDir.resolve("projects");
        if (!Files.isDirectory(projectsDir)) {
            return projects;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectsDir)) {
            for (Path projectDir : stream) {
                if (!Files.isDirectory(projectDir)) continue;
                String dirName = projectDir.getFileName().toString();
                List<Path> sessionFiles = listSessionFiles(projectDir);
                if (sessionFiles.isEmpty()) continue;

                JsonObject project = new JsonObject();
                project.addProperty("hash", dirName);
                project.addProperty("path", decodeProjectPath(dirName));
                project.addProperty("sessionCount", sessionFiles.size());

                long lastModified = sessionFiles.stream()
                        .mapToLong(p -> {
                            try { return Files.getLastModifiedTime(p).toMillis(); }
                            catch (IOException e) { return 0L; }
                        })
                        .max().orElse(0L);
                project.addProperty("lastModified", lastModified);
                projects.add(project);
            }
        } catch (IOException ignored) {}
        projects.sort((a, b) -> Long.compare(b.get("lastModified").getAsLong(), a.get("lastModified").getAsLong()));
        projectsCache = projects;
        projectsCacheTime = now;
        return projects;
    }

    public void invalidateCache() {
        projectsCache = null;
    }

    public List<JsonObject> listSessions(String projectHash, String after, String before) {
        List<JsonObject> sessions = new ArrayList<>();
        Path projectDir = claudeDir.resolve("projects").resolve(projectHash);
        if (!Files.isDirectory(projectDir)) {
            return sessions;
        }
        List<Path> sessionFiles = listSessionFiles(projectDir);
        for (Path file : sessionFiles) {
            JsonObject session = parseSessionMeta(file);
            if (session == null) continue;
            if (after != null || before != null) {
                String ts = session.has("timestamp") ? session.get("timestamp").getAsString() : "";
                if (!ts.isEmpty()) {
                    if (after != null && ts.compareTo(after) < 0) continue;
                    if (before != null && ts.compareTo(before) > 0) continue;
                }
            }
            sessions.add(session);
        }
        sessions.sort((a, b) -> {
            String t1 = a.has("timestamp") ? a.get("timestamp").getAsString() : "";
            String t2 = b.has("timestamp") ? b.get("timestamp").getAsString() : "";
            return t2.compareTo(t1);
        });
        return sessions;
    }

    public JsonObject getSession(String projectHash, String sessionId, int offset, int limit) {
        Path projectDir = claudeDir.resolve("projects").resolve(projectHash);
        Path sessionFile = findSessionFile(projectDir, sessionId);
        if (sessionFile == null) {
            return null;
        }
        return parseSessionFull(sessionFile, offset, limit);
    }

    public JsonObject getSessionMeta(String projectHash, String sessionId) {
        Path projectDir = claudeDir.resolve("projects").resolve(projectHash);
        Path sessionFile = findSessionFile(projectDir, sessionId);
        if (sessionFile == null) {
            return null;
        }
        JsonObject meta = parseSessionMeta(sessionFile);
        if (meta != null) {
            meta.addProperty("projectHash", projectHash);
        }
        return meta;
    }

    public List<JsonObject> searchSessions(String keyword, int maxResults) {
        List<JsonObject> results = new ArrayList<>();
        String lowerKeyword = keyword.toLowerCase();
        Path projectsDir = claudeDir.resolve("projects");
        if (!Files.isDirectory(projectsDir)) {
            return results;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectsDir)) {
            for (Path projectDir : stream) {
                if (!Files.isDirectory(projectDir)) continue;
                if (results.size() >= maxResults) break;
                String projectHash = projectDir.getFileName().toString();
                List<Path> sessionFiles = listSessionFiles(projectDir);
                for (Path file : sessionFiles) {
                    if (results.size() >= maxResults) break;
                    List<JsonObject> matches = searchInFile(file, projectHash, lowerKeyword, maxResults - results.size());
                    results.addAll(matches);
                }
            }
        } catch (IOException ignored) {}
        return results;
    }

    public String exportSessionMarkdown(String projectHash, String sessionId) {
        Path projectDir = claudeDir.resolve("projects").resolve(projectHash);
        Path sessionFile = findSessionFile(projectDir, sessionId);
        if (sessionFile == null) return null;

        StringBuilder md = new StringBuilder();
        md.append("# Claude Code Session: ").append(sessionId).append("\n\n");

        try (BufferedReader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonObject entry = GsonUtil.parse(line);
                    if (entry == null) continue;
                    String type = entry.has("type") ? entry.get("type").getAsString() : "";

                    if ("user".equals(type)) {
                        String text = extractTextFromMessage(entry);
                        if (text != null && !text.isEmpty()) {
                            md.append("## User\n\n").append(text).append("\n\n");
                        }
                    } else if ("assistant".equals(type)) {
                        JsonObject message = entry.has("message") ? entry.getAsJsonObject("message") : null;
                        if (message != null && message.has("content") && message.get("content").isJsonArray()) {
                            for (JsonElement el : message.get("content").getAsJsonArray()) {
                                if (!el.isJsonObject()) continue;
                                JsonObject obj = el.getAsJsonObject();
                                String blockType = obj.has("type") ? obj.get("type").getAsString() : "";
                                if ("text".equals(blockType)) {
                                    String text = obj.has("text") ? obj.get("text").getAsString() : "";
                                    if (!text.isEmpty()) {
                                        md.append("## Assistant\n\n").append(text).append("\n\n");
                                    }
                                } else if ("tool_use".equals(blockType)) {
                                    String name = obj.has("name") ? obj.get("name").getAsString() : "";
                                    String input = obj.has("input") ? obj.get("input").toString() : "{}";
                                    md.append("### Tool Call: ").append(name).append("\n\n```json\n").append(input).append("\n```\n\n");
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            return null;
        }
        return md.toString();
    }

    public List<JsonObject> listPromptHistory(int limit, String projectFilter) {
        List<JsonObject> results = new ArrayList<>();
        Path historyFile = claudeDir.resolve("history.jsonl");
        if (!Files.isRegularFile(historyFile)) {
            return results;
        }
        try (BufferedReader reader = Files.newBufferedReader(historyFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonObject entry = GsonUtil.parse(line);
                    if (entry == null) continue;

                    if (projectFilter != null && !projectFilter.isEmpty()) {
                        String project = entry.has("project") ? entry.get("project").getAsString() : "";
                        if (!project.contains(projectFilter)) continue;
                    }

                    JsonObject item = new JsonObject();
                    item.addProperty("display", entry.has("display") ? entry.get("display").getAsString() : "");
                    item.addProperty("timestamp", entry.has("timestamp") ? entry.get("timestamp").getAsLong() : 0);
                    item.addProperty("project", entry.has("project") ? entry.get("project").getAsString() : "");
                    item.addProperty("sessionId", entry.has("sessionId") ? entry.get("sessionId").getAsString() : "");
                    results.add(item);
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}

        results.sort((a, b) -> Long.compare(
                b.get("timestamp").getAsLong(),
                a.get("timestamp").getAsLong()));
        if (results.size() > limit) {
            return new ArrayList<>(results.subList(0, limit));
        }
        return results;
    }

    private List<Path> listSessionFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    files.add(p);
                }
            }
        } catch (IOException ignored) {}
        return files;
    }

    private String decodeProjectPath(String hash) {
        String path = hash;
        if (path.startsWith("-")) path = path.substring(1);
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            path = path.replace("/", "-");
        } else {
            path = path.replace('-', '/');
            if (!path.startsWith("/")) path = "/" + path;
        }
        return path;
    }

    private JsonObject parseSessionMeta(Path file) {
        String fileName = file.getFileName().toString().replace(".jsonl", "");
        JsonObject meta = new JsonObject();
        meta.addProperty("id", fileName);

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int messageCount = 0;
            int toolCallCount = 0;
            int totalInputTokens = 0;
            int totalOutputTokens = 0;
            String firstUserMsg = null;
            String summaryText = null;
            String timestamp = null;
            String cwd = null;
            int totalLines = 0;

            while ((line = reader.readLine()) != null) {
                totalLines++;
                if (totalLines > 2000) break;
                try {
                    JsonObject entry = GsonUtil.parse(line);
                    if (entry == null) continue;

                    if (timestamp == null && entry.has("timestamp")) {
                        timestamp = entry.get("timestamp").getAsString();
                    }
                    if (cwd == null && entry.has("cwd")) {
                        cwd = entry.get("cwd").getAsString();
                    }

                    String type = entry.has("type") ? entry.get("type").getAsString() : "";

                    if ("summary".equals(type) && summaryText == null) {
                        summaryText = extractSummaryText(entry);
                    }

                    if ("user".equals(type) || "assistant".equals(type)) {
                        messageCount++;
                    }

                    if ("assistant".equals(type)) {
                        JsonObject message = entry.has("message") ? entry.getAsJsonObject("message") : null;
                        if (message != null) {
                            if (message.has("content") && message.get("content").isJsonArray()) {
                                for (JsonElement el : message.get("content").getAsJsonArray()) {
                                    if (el.isJsonObject()) {
                                        JsonObject obj = el.getAsJsonObject();
                                        if ("tool_use".equals(obj.get("type").getAsString())) {
                                            toolCallCount++;
                                        }
                                    }
                                }
                            }
                            if (message.has("usage")) {
                                JsonObject usage = message.getAsJsonObject("usage");
                                totalInputTokens += usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0;
                                totalOutputTokens += usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0;
                            }
                        }
                    }

                    if (firstUserMsg == null && "user".equals(type)) {
                        firstUserMsg = extractTextFromMessage(entry);
                    }
                } catch (Exception ignored) {}
            }
            meta.addProperty("timestamp", timestamp != null ? timestamp : "");
            meta.addProperty("cwd", cwd != null ? cwd : "");
            meta.addProperty("messageCount", messageCount);
            meta.addProperty("toolCallCount", toolCallCount);
            meta.addProperty("totalInputTokens", totalInputTokens);
            meta.addProperty("totalOutputTokens", totalOutputTokens);
            meta.addProperty("summary", summaryText != null ? truncate(summaryText, 100) :
                    (firstUserMsg != null ? truncate(firstUserMsg, 100) : ""));
            meta.addProperty("lastModified", Files.getLastModifiedTime(file).toMillis());
            return meta;
        } catch (IOException e) {
            return null;
        }
    }

    private JsonObject parseSessionFull(Path file, int offset, int limit) {
        String fileName = file.getFileName().toString().replace(".jsonl", "");
        JsonObject session = new JsonObject();
        session.addProperty("id", fileName);
        JsonArray messages = new JsonArray();
        String cwd = null;
        String timestamp = null;
        int totalMessages = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int toolCallCount = 0;

        List<JsonObject> allMessages = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonObject entry = GsonUtil.parse(line);
                    if (entry == null) continue;

                    if (timestamp == null && entry.has("timestamp")) {
                        timestamp = entry.get("timestamp").getAsString();
                    }
                    if (cwd == null && entry.has("cwd")) {
                        cwd = entry.get("cwd").getAsString();
                    }

                    String type = entry.has("type") ? entry.get("type").getAsString() : "";
                    if ("user".equals(type) || "assistant".equals(type)) {
                        totalMessages++;
                        JsonObject msg = parseMessageEntry(entry);
                        if (msg != null) {
                            allMessages.add(msg);
                            if ("assistant".equals(type)) {
                                JsonObject message = entry.has("message") ? entry.getAsJsonObject("message") : null;
                                if (message != null) {
                                    if (message.has("content") && message.get("content").isJsonArray()) {
                                        for (JsonElement el : message.get("content").getAsJsonArray()) {
                                            if (el.isJsonObject() && "tool_use".equals(el.getAsJsonObject().get("type").getAsString())) {
                                                toolCallCount++;
                                            }
                                        }
                                    }
                                    if (message.has("usage")) {
                                        JsonObject usage = message.getAsJsonObject("usage");
                                        totalInputTokens += usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0;
                                        totalOutputTokens += usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0;
                                    }
                                }
                            }
                        }
                    } else if ("summary".equals(type)) {
                        JsonObject msg = new JsonObject();
                        msg.addProperty("role", "system");
                        msg.addProperty("type", "summary");
                        msg.addProperty("content", extractSummaryText(entry));
                        allMessages.add(msg);
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            return null;
        }

        int end = Math.min(offset + limit, allMessages.size());
        for (int i = offset; i < end; i++) {
            messages.add(allMessages.get(i));
        }

        session.add("messages", messages);
        session.addProperty("cwd", cwd != null ? cwd : "");
        session.addProperty("timestamp", timestamp != null ? timestamp : "");
        session.addProperty("totalMessages", totalMessages);
        session.addProperty("offset", offset);
        session.addProperty("limit", limit);
        session.addProperty("hasMore", end < allMessages.size());
        session.addProperty("totalInputTokens", totalInputTokens);
        session.addProperty("totalOutputTokens", totalOutputTokens);
        session.addProperty("toolCallCount", toolCallCount);
        return session;
    }

    private JsonObject parseMessageEntry(JsonObject entry) {
        JsonObject msg = new JsonObject();
        String type = entry.get("type").getAsString();
        msg.addProperty("role", type);

        if (entry.has("timestamp")) {
            msg.addProperty("timestamp", entry.get("timestamp").getAsString());
        }

        JsonObject message = entry.has("message") ? entry.getAsJsonObject("message") : null;
        if (message == null) {
            msg.addProperty("content", "");
            return msg;
        }

        if (message.has("content")) {
            JsonElement content = message.get("content");
            if (content.isJsonPrimitive()) {
                msg.addProperty("content", content.getAsString());
            } else if (content.isJsonArray()) {
                JsonArray blocks = content.getAsJsonArray();
                JsonArray parsed = new JsonArray();
                for (JsonElement block : blocks) {
                    if (block.isJsonObject()) {
                        JsonObject blockObj = block.getAsJsonObject();
                        String blockType = blockObj.has("type") ? blockObj.get("type").getAsString() : "";
                        if ("text".equals(blockType)) {
                            JsonObject textBlock = new JsonObject();
                            textBlock.addProperty("type", "text");
                            textBlock.addProperty("text", blockObj.has("text") ? blockObj.get("text").getAsString() : "");
                            parsed.add(textBlock);
                        } else if ("tool_use".equals(blockType)) {
                            JsonObject toolBlock = new JsonObject();
                            toolBlock.addProperty("type", "tool_use");
                            toolBlock.addProperty("id", blockObj.has("id") ? blockObj.get("id").getAsString() : "");
                            toolBlock.addProperty("name", blockObj.has("name") ? blockObj.get("name").getAsString() : "");
                            toolBlock.add("input", blockObj.has("input") ? blockObj.get("input") : new JsonObject());
                            parsed.add(toolBlock);
                        } else if ("tool_result".equals(blockType)) {
                            JsonObject resultBlock = new JsonObject();
                            resultBlock.addProperty("type", "tool_result");
                            resultBlock.addProperty("toolUseId", blockObj.has("tool_use_id") ? blockObj.get("tool_use_id").getAsString() : "");
                            resultBlock.addProperty("content", extractToolResultContent(blockObj));
                            parsed.add(resultBlock);
                        } else if ("thinking".equals(blockType)) {
                            JsonObject thinkBlock = new JsonObject();
                            thinkBlock.addProperty("type", "thinking");
                            thinkBlock.addProperty("thinking", blockObj.has("thinking") ? blockObj.get("thinking").getAsString() : "");
                            parsed.add(thinkBlock);
                        }
                    }
                }
                msg.add("content", parsed);
            } else {
                msg.addProperty("content", content.toString());
            }
        }

        if (message.has("model")) {
            msg.addProperty("model", message.get("model").getAsString());
        }
        if (message.has("usage")) {
            msg.add("usage", message.get("usage"));
        }

        return msg;
    }

    private String extractTextFromMessage(JsonObject entry) {
        JsonObject message = entry.has("message") ? entry.getAsJsonObject("message") : null;
        if (message == null) return null;

        if (message.has("content") && message.get("content").isJsonPrimitive()) {
            return message.get("content").getAsString();
        }
        if (message.has("content") && message.get("content").isJsonArray()) {
            for (JsonElement el : message.get("content").getAsJsonArray()) {
                if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    String t = obj.has("type") ? obj.get("type").getAsString() : "";
                    if ("text".equals(t)) {
                        return obj.has("text") ? obj.get("text").getAsString() : null;
                    }
                }
            }
        }
        return null;
    }

    private String extractToolResultContent(JsonObject blockObj) {
        if (blockObj.has("content")) {
            JsonElement c = blockObj.get("content");
            if (c.isJsonPrimitive()) return c.getAsString();
            if (c.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement el : c.getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        JsonObject obj = el.getAsJsonObject();
                        String t = obj.has("type") ? obj.get("type").getAsString() : "";
                        if ("text".equals(t)) {
                            sb.append(obj.has("text") ? obj.get("text").getAsString() : "");
                        }
                    }
                }
                return sb.toString();
            }
        }
        if (blockObj.has("output")) {
            return blockObj.get("output").getAsString();
        }
        return "";
    }

    private String extractSummaryText(JsonObject entry) {
        if (entry.has("summary")) {
            JsonElement s = entry.get("summary");
            if (s.isJsonPrimitive()) return s.getAsString();
            if (s.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement el : s.getAsJsonArray()) {
                    if (el.isJsonPrimitive()) sb.append(el.getAsString());
                }
                return sb.toString();
            }
        }
        return "";
    }

    private Path findSessionFile(Path projectDir, String sessionId) {
        Path direct = projectDir.resolve(sessionId + ".jsonl");
        if (Files.isRegularFile(direct)) return direct;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectDir, sessionId + "*.jsonl")) {
            for (Path p : stream) {
                return p;
            }
        } catch (IOException ignored) {}
        return null;
    }

    private List<JsonObject> searchInFile(Path file, String projectHash, String lowerKeyword, int maxResults) {
        List<JsonObject> matches = new ArrayList<>();
        String sessionId = file.getFileName().toString().replace(".jsonl", "");
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                if (matches.size() >= maxResults) break;
                lineNum++;
                if (line.toLowerCase().contains(lowerKeyword)) {
                    try {
                        JsonObject entry = GsonUtil.parse(line);
                        if (entry == null) continue;
                        String type = entry.has("type") ? entry.get("type").getAsString() : "";
                        if (!"user".equals(type) && !"assistant".equals(type)) continue;

                        String text = extractTextFromMessage(entry);
                        if (text != null && text.toLowerCase().contains(lowerKeyword)) {
                            JsonObject match = new JsonObject();
                            match.addProperty("projectHash", projectHash);
                            match.addProperty("sessionId", sessionId);
                            match.addProperty("lineNum", lineNum);
                            match.addProperty("type", type);
                            match.addProperty("timestamp", entry.has("timestamp") ? entry.get("timestamp").getAsString() : "");
                            match.addProperty("snippet", findSnippet(text, lowerKeyword));
                            match.addProperty("highlight", findHighlight(text, lowerKeyword));
                            matches.add(match);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return matches;
    }

    private String findSnippet(String text, String lowerKeyword) {
        String lower = text.toLowerCase();
        int idx = lower.indexOf(lowerKeyword);
        if (idx < 0) return truncate(text, 150);
        int start = Math.max(0, idx - 60);
        int end = Math.min(text.length(), idx + lowerKeyword.length() + 60);
        String snippet = (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
        return snippet;
    }

    private String findHighlight(String text, String lowerKeyword) {
        String lower = text.toLowerCase();
        int idx = lower.indexOf(lowerKeyword);
        if (idx < 0) return "";
        int start = Math.max(0, idx - 40);
        int end = Math.min(text.length(), idx + lowerKeyword.length() + 40);
        return text.substring(start, end);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", "");
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static class GsonUtil {
        private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
        static JsonObject parse(String json) {
            try {
                return GSON.fromJson(json, JsonObject.class);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
