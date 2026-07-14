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
    private static final AtomicInteger COUNTER = new AtomicInteger((int) (System.currentTimeMillis() & 0xFFFF));
    private final List<JsonObject> index = new CopyOnWriteArrayList<>();
    private final Path baseDir;
    private final Path sessionsDir;
    private final Path indexFile;
    private final Path tasksDir;

    public SessionManager() {
        this(Paths.get(System.getProperty("user.home"), ".fish-code"));
    }

    SessionManager(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.sessionsDir = this.baseDir.resolve("sessions");
        this.indexFile = this.baseDir.resolve("sessions-index.json");
        this.tasksDir = this.baseDir.resolve("task-states");
        ensureDirs();
        loadIndex();
    }

    public synchronized String createSession(String cwd, String model) {
        String id = generateId();
        LocalDate today = LocalDate.now();
        String datePath = today.format(DATE_FMT);
        String fileName = id + ".jsonl";

        Path sessionFile;
        try {
            Path sessionDirectory = FileSecurity.ensurePrivateSubdirectory(sessionsDir, Paths.get(datePath));
            sessionFile = sessionDirectory.resolve(fileName);
            Files.createFile(sessionFile);
            FileSecurity.restrictFile(sessionFile);
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
        try {
            saveIndexOrThrow();
        } catch (IOException e) {
            index.remove(entry);
            try { Files.deleteIfExists(sessionFile); } catch (IOException ignored) {}
            throw new RuntimeException("保存会话索引失败: " + e.getMessage(), e);
        }
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

    public synchronized void appendMessage(String sessionId, JsonObject message) throws IOException {
        Path sessionFile = resolveSessionFile(sessionId);
        if (sessionFile == null) throw new IOException("会话不存在: " + sessionId);

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

        Files.write(sessionFile,
                (GSON.toJson(wrapped) + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

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
        saveIndexOrThrow();
    }

    public synchronized void saveTaskState(String sessionId, JsonObject taskState) {
        Path target = resolveTaskFile(sessionId);
        if (target == null || taskState == null) return;
        try {
            FileSecurity.ensurePrivateDirectory(tasksDir);
            Path temp = Files.createTempFile(tasksDir, sessionId + "-", ".tmp");
            try {
                Files.write(temp, GSON.toJson(taskState).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                FileSecurity.restrictFile(target);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            System.err.println("保存任务状态失败: " + e.getMessage());
        }
    }

    public JsonObject loadTaskState(String sessionId) {
        Path target = resolveTaskFile(sessionId);
        if (target == null) return null;
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
        if (!isValidSessionId(sessionId)) return;
        Path sessionFile = resolveSessionFile(sessionId);
        if (sessionFile != null) {
            try {
                Files.deleteIfExists(sessionFile);
            } catch (IOException ignored) {}
        }
        Path taskFile = resolveTaskFile(sessionId);
        if (taskFile != null) {
            try { Files.deleteIfExists(taskFile); } catch (IOException ignored) {}
        }
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
        if (!isValidSessionId(sessionId)) return null;
        for (JsonObject entry : index) {
            if (entry.get("id").getAsString().equals(sessionId)) {
                Path resolved = sessionsDir.resolve(entry.get("file").getAsString()).normalize();
                return resolved.startsWith(sessionsDir) ? resolved : null;
            }
        }
        return null;
    }

    private Path resolveTaskFile(String sessionId) {
        if (!isValidSessionId(sessionId)) return null;
        Path resolved = tasksDir.resolve(sessionId + ".json").normalize();
        return resolved.startsWith(tasksDir) ? resolved : null;
    }

    private static boolean isValidSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || sessionId.length() > 100) return false;
        for (int i = 0; i < sessionId.length(); i++) {
            char value = sessionId.charAt(i);
            if (!(Character.isLetterOrDigit(value) || value == '-' || value == '_')) return false;
        }
        return true;
    }

    private static String generateId() {
        long time = System.currentTimeMillis();
        int counter = COUNTER.incrementAndGet();
        return String.format("%08x%04x", time & 0xFFFFFFFFL, counter & 0xFFFF);
    }

    private void ensureDirs() {
        try {
            FileSecurity.ensurePrivateDirectory(baseDir);
            FileSecurity.ensurePrivateDirectory(sessionsDir);
            FileSecurity.ensurePrivateDirectory(tasksDir);
            FileSecurity.secureTree(baseDir);
        } catch (IOException e) {
            System.err.println("创建会话目录失败: " + e.getMessage());
        }
    }

    private void loadIndex() {
        index.clear();
        if (!Files.exists(indexFile)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(indexFile);
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
            saveIndexOrThrow();
        } catch (IOException e) {
            System.err.println("保存会话索引失败: " + e.getMessage());
        }
    }

    private void saveIndexOrThrow() throws IOException {
        FileSecurity.ensurePrivateDirectory(indexFile.getParent());
        JsonArray arr = new JsonArray();
        for (JsonObject entry : index) arr.add(entry);
        Path tmp = indexFile.resolveSibling(indexFile.getFileName() + ".tmp");
        try {
            Files.write(tmp, GSON.toJson(arr).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
            }
            FileSecurity.restrictFile(indexFile);
        } finally {
            Files.deleteIfExists(tmp);
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
