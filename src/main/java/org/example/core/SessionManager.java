package org.example.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.tool.ToolConstants;

public class SessionManager {

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Path BASE_DIR = Paths.get(System.getProperty("user.home"), ".fish-code");
    private static final Path SESSIONS_DIR = BASE_DIR.resolve("sessions");
    private static final Path INDEX_FILE = BASE_DIR.resolve("sessions-index.json");
    private static final Path TASKS_DIR = BASE_DIR.resolve("task-states");

    private static final AtomicInteger COUNTER = new AtomicInteger((int) (System.currentTimeMillis() & 0xFFFF));
    private final List<JsonObject> index = new CopyOnWriteArrayList<>();

    public SessionManager() {
        ensureDirs();
        loadIndex();
    }

    public synchronized String createSession(String cwd, String model) {
        String id = generateId();
        LocalDate today = LocalDate.now();
        String datePath = today.format(DATE_FMT);
        String fileName = id + ".jsonl";

        Path sessionFile = SESSIONS_DIR.resolve(datePath).resolve(fileName);
        try {
            Files.createDirectories(sessionFile.getParent());
            Files.createFile(sessionFile);
        } catch (IOException e) {
            throw new RuntimeException("创建会话文件失败: " + e.getMessage(), e);
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.addProperty("title", "");
        entry.addProperty("model", model);
        entry.addProperty("cwd", cwd);
        entry.addProperty("createdAt", System.currentTimeMillis());
        entry.addProperty("updatedAt", System.currentTimeMillis());
        entry.addProperty("messageCount", 0);
        entry.addProperty("file", datePath + "/" + fileName);

        index.add(entry);
        saveIndex();
        return id;
    }

    public synchronized void updateTitle(String sessionId, String title) {
        for (int i = 0; i < index.size(); i++) {
            JsonObject entry = index.get(i);
            if (entry.get("id").getAsString().equals(sessionId)) {
                JsonObject updated = deepCopy(entry);
                updated.addProperty("title", title);
                index.set(i, updated);
                saveIndex();
                return;
            }
        }
    }

    public synchronized void appendMessage(String sessionId, JsonObject message) {
        Path sessionFile = resolveSessionFile(sessionId);
        if (sessionFile == null) return;

        JsonObject wrapped = new JsonObject();
        wrapped.addProperty("timestamp", System.currentTimeMillis());
        wrapped.addProperty("role", message.get("role").getAsString());

        if (message.has("content") && !message.get("content").isJsonNull()) {
            wrapped.addProperty("content", message.get("content").getAsString());
        }

        if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {
            wrapped.add("tool_calls", message.get("tool_calls"));
        }

        if (message.has("tool_call_id") && !message.get("tool_call_id").isJsonNull()) {
            wrapped.addProperty("toolCallId", message.get("tool_call_id").getAsString());
        }

        try {
            Files.write(sessionFile,
                    (GSON.toJson(wrapped) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("追加消息失败: " + e.getMessage());
        }

        for (int i = 0; i < index.size(); i++) {
            JsonObject entry = index.get(i);
            if (entry.get("id").getAsString().equals(sessionId)) {
                JsonObject updated = deepCopy(entry);
                updated.addProperty("updatedAt", System.currentTimeMillis());
                updated.addProperty("messageCount", entry.get("messageCount").getAsInt() + 1);
                index.set(i, updated);
                break;
            }
        }
        saveIndex();
    }

    public synchronized void saveTaskState(String sessionId, JsonObject taskState) {
        if (sessionId == null || sessionId.trim().isEmpty() || taskState == null) return;
        try {
            Files.createDirectories(TASKS_DIR);
            Path target = TASKS_DIR.resolve(sessionId + ".json");
            Path temp = Files.createTempFile(TASKS_DIR, sessionId + "-", ".tmp");
            try {
                Files.write(temp, GSON.toJson(taskState).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            System.err.println("保存任务状态失败: " + e.getMessage());
        }
    }

    public JsonObject loadTaskState(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return null;
        Path target = TASKS_DIR.resolve(sessionId + ".json");
        if (!Files.exists(target)) return null;
        try {
            return GSON.fromJson(new String(Files.readAllBytes(target), StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    public List<JsonObject> loadSession(String sessionId) {
        Path sessionFile = resolveSessionFile(sessionId);
        if (sessionFile == null || !Files.exists(sessionFile)) {
            return Collections.emptyList();
        }

        List<JsonObject> messages = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(Files.newInputStream(sessionFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    JsonObject msg = GSON.fromJson(line, JsonObject.class);
                    messages.add(wrapToApiFormat(msg));
                } catch (Exception e) {
                    skipped++;
                }
            }
        } catch (IOException e) {
            System.err.println("读取会话文件失败: " + e.getMessage());
        }
        if (skipped > 0) {
            System.err.println("  \u001B[33m(警告: 跳过了 " + skipped + " 条损坏的消息)\u001B[0m");
        }
        return messages;
    }

    public List<JsonObject> listSessions() {
        List<JsonObject> result = new ArrayList<>();
        for (int i = index.size() - 1; i >= 0; i--) {
            result.add(index.get(i));
        }
        return result;
    }

    public JsonObject getSessionInfo(String sessionId) {
        if (sessionId == null) return null;
        for (JsonObject entry : index) {
            if (entry.has("id") && sessionId.equals(entry.get("id").getAsString())) return deepCopy(entry);
        }
        return null;
    }

    public String getSessionIdByIndex(int displayIndex) {
        List<JsonObject> list = listSessions();
        if (displayIndex < 1 || displayIndex > list.size()) {
            return null;
        }
        return list.get(displayIndex - 1).get("id").getAsString();
    }

    public String getLastSessionId() {
        if (index.isEmpty()) return null;
        return index.get(index.size() - 1).get("id").getAsString();
    }

    public synchronized void deleteSession(String sessionId) {
        Path sessionFile = resolveSessionFile(sessionId);
        if (sessionFile != null) {
            try {
                Files.deleteIfExists(sessionFile);
            } catch (IOException ignored) {}
        }
        try { Files.deleteIfExists(TASKS_DIR.resolve(sessionId + ".json")); } catch (IOException ignored) {}
        for (int i = 0; i < index.size(); i++) {
            if (index.get(i).get("id").getAsString().equals(sessionId)) {
                index.remove(i);
                break;
            }
        }
        saveIndex();
    }

    public static String generateTitle(String firstMessage) {
        if (firstMessage == null || firstMessage.isEmpty()) return "新会话";
        String cleaned = firstMessage.replaceAll("[\\r\\n]+", " ").trim();
        int maxLen = ToolConstants.SESSION_TITLE_MAX_LENGTH;
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) + "..." : cleaned;
    }

    private Path resolveSessionFile(String sessionId) {
        for (JsonObject entry : index) {
            if (entry.get("id").getAsString().equals(sessionId)) {
                return SESSIONS_DIR.resolve(entry.get("file").getAsString());
            }
        }
        return null;
    }

    private static String generateId() {
        long time = System.currentTimeMillis();
        int counter = COUNTER.incrementAndGet();
        return String.format("%08x%04x", time & 0xFFFFFFFFL, counter & 0xFFFF);
    }

    private void ensureDirs() {
        try {
            Files.createDirectories(SESSIONS_DIR);
        } catch (IOException e) {
            System.err.println("创建会话目录失败: " + e.getMessage());
        }
    }

    private void loadIndex() {
        index.clear();
        if (!Files.exists(INDEX_FILE)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(INDEX_FILE);
            String content = new String(bytes, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return;
            JsonArray arr = GSON.fromJson(content, JsonArray.class);
            for (JsonElement el : arr) {
                index.add(el.getAsJsonObject());
            }
        } catch (Exception e) {
            System.err.println("读取会话索引失败: " + e.getMessage());
        }
    }

    private void saveIndex() {
        try {
            Files.createDirectories(INDEX_FILE.getParent());
            JsonArray arr = new JsonArray();
            for (JsonObject entry : index) {
                arr.add(entry);
            }
            Path tmp = INDEX_FILE.resolveSibling(INDEX_FILE.getFileName() + ".tmp");
            Files.write(tmp,
                    GSON.toJson(arr).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, INDEX_FILE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("保存会话索引失败: " + e.getMessage());
        }
    }

    private JsonObject deepCopy(JsonObject src) {
        return GSON.fromJson(GSON.toJson(src), JsonObject.class);
    }

    private JsonObject wrapToApiFormat(JsonObject stored) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", stored.get("role").getAsString());

        if (stored.has("content") && !stored.get("content").isJsonNull()) {
            msg.addProperty("content", stored.get("content").getAsString());
        } else {
            msg.add("content", JsonNull.INSTANCE);
        }

        if (stored.has("tool_calls") && !stored.get("tool_calls").isJsonNull()) {
            msg.add("tool_calls", stored.get("tool_calls"));
        }

        if (stored.has("toolCallId") && !stored.get("toolCallId").isJsonNull()) {
            msg.addProperty("tool_call_id", stored.get("toolCallId").getAsString());
        }

        return msg;
    }
}
