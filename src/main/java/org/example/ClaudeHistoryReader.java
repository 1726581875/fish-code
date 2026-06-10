package org.example;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class ClaudeHistoryReader {

    private final Path claudeDir;

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
        return projects;
    }

    public List<JsonObject> listSessions(String projectHash) {
        List<JsonObject> sessions = new ArrayList<>();
        Path projectDir = claudeDir.resolve("projects").resolve(projectHash);
        if (!Files.isDirectory(projectDir)) {
            return sessions;
        }
        List<Path> sessionFiles = listSessionFiles(projectDir);
        for (Path file : sessionFiles) {
            JsonObject session = parseSessionMeta(file);
            if (session != null) {
                sessions.add(session);
            }
        }
        sessions.sort((a, b) -> {
            String t1 = a.has("timestamp") ? a.get("timestamp").getAsString() : "";
            String t2 = b.has("timestamp") ? b.get("timestamp").getAsString() : "";
            return t2.compareTo(t1);
        });
        return sessions;
    }

    public JsonObject getSession(String projectHash, String sessionId) {
        Path projectDir = claudeDir.resolve("projects").resolve(projectHash);
        Path sessionFile = findSessionFile(projectDir, sessionId);
        if (sessionFile == null) {
            return null;
        }
        return parseSessionFull(sessionFile);
    }

    public List<JsonObject> searchSessions(String keyword) {
        List<JsonObject> results = new ArrayList<>();
        String lowerKeyword = keyword.toLowerCase();
        Path projectsDir = claudeDir.resolve("projects");
        if (!Files.isDirectory(projectsDir)) {
            return results;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectsDir)) {
            for (Path projectDir : stream) {
                if (!Files.isDirectory(projectDir)) continue;
                String projectHash = projectDir.getFileName().toString();
                List<Path> sessionFiles = listSessionFiles(projectDir);
                for (Path file : sessionFiles) {
                    List<JsonObject> matches = searchInFile(file, projectHash, lowerKeyword);
                    results.addAll(matches);
                }
            }
        } catch (IOException ignored) {}
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
        path = path.replace('-', '/');
        if (!path.startsWith("/")) path = "/" + path;
        return path;
    }

    private JsonObject parseSessionMeta(Path file) {
        String fileName = file.getFileName().toString().replace(".jsonl", "");
        JsonObject meta = new JsonObject();
        meta.addProperty("id", fileName);

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int messageCount = 0;
            String firstUserMsg = null;
            String timestamp = null;
            String cwd = null;
            int totalLines = 0;

            while ((line = reader.readLine()) != null) {
                totalLines++;
                if (totalLines > 500) break;
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
                        messageCount++;
                    }
                    if (firstUserMsg == null && "user".equals(type)) {
                        firstUserMsg = extractTextFromMessage(entry);
                    }
                } catch (Exception ignored) {}
            }
            meta.addProperty("timestamp", timestamp != null ? timestamp : "");
            meta.addProperty("cwd", cwd != null ? cwd : "");
            meta.addProperty("messageCount", messageCount);
            meta.addProperty("summary", firstUserMsg != null ? truncate(firstUserMsg, 100) : "");
            meta.addProperty("lastModified", Files.getLastModifiedTime(file).toMillis());
            return meta;
        } catch (IOException e) {
            return null;
        }
    }

    private JsonObject parseSessionFull(Path file) {
        String fileName = file.getFileName().toString().replace(".jsonl", "");
        JsonObject session = new JsonObject();
        session.addProperty("id", fileName);
        JsonArray messages = new JsonArray();
        String cwd = null;
        String timestamp = null;

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
                        JsonObject msg = parseMessageEntry(entry);
                        if (msg != null) {
                            messages.add(msg);
                        }
                    } else if ("summary".equals(type)) {
                        JsonObject msg = new JsonObject();
                        msg.addProperty("role", "system");
                        msg.addProperty("type", "summary");
                        msg.addProperty("content", extractSummaryText(entry));
                        messages.add(msg);
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            return null;
        }

        session.add("messages", messages);
        session.addProperty("cwd", cwd != null ? cwd : "");
        session.addProperty("timestamp", timestamp != null ? timestamp : "");
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
                    if ("text".equals(obj.get("type").getAsString())) {
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
                    if (el.isJsonObject() && "text".equals(el.getAsJsonObject().get("type").getAsString())) {
                        sb.append(el.getAsJsonObject().has("text") ? el.getAsJsonObject().get("text").getAsString() : "");
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

    private List<JsonObject> searchInFile(Path file, String projectHash, String lowerKeyword) {
        List<JsonObject> matches = new ArrayList<>();
        String sessionId = file.getFileName().toString().replace(".jsonl", "");
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
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
