package org.example.core;

import com.google.gson.*;
import org.example.tool.ToolUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class ChangeJournal {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final String runId;
    private final Path workspace;
    private final Path journalDir;
    private final Path journalFile;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    public ChangeJournal(String runId, String cwd) {
        this.runId = runId;
        this.workspace = Paths.get(cwd).toAbsolutePath().normalize();
        this.journalDir = Paths.get(System.getProperty("user.home"), ".fish-code", "runs", runId);
        this.journalFile = journalDir.resolve("journal.json");
        load();
    }

    public synchronized void capture(File file) throws IOException {
        File canonical = file.getCanonicalFile();
        ensureInside(canonical.toPath());
        String path = canonical.getPath();
        if (entries.containsKey(path)) return;

        FileSecurity.ensurePrivateSubdirectory(
                Paths.get(System.getProperty("user.home"), ".fish-code"),
                Paths.get("runs", runId));
        boolean existed = canonical.exists();
        String snapshot = existed ? digest(path) + ".snapshot" : "";
        if (existed) {
            if (!canonical.isFile()) throw new IOException("只能记录普通文件: " + path);
            ToolUtils.writeAtomically(journalDir.resolve(snapshot), Files.readAllBytes(canonical.toPath()));
        }
        entries.put(path, new Entry(path, existed, snapshot, ""));
        persist();
    }

    public synchronized void recordWritten(File file) throws IOException {
        File canonical = file.getCanonicalFile();
        ensureInside(canonical.toPath());
        Entry entry = entries.get(canonical.getPath());
        if (entry == null) throw new IOException("文件写入前未记录回滚快照: " + canonical.getPath());
        if (!canonical.isFile()) throw new IOException("写入后的目标不是普通文件: " + canonical.getPath());
        entry.writtenHash = fileHash(canonical.toPath());
        persist();
    }

    public synchronized List<String> rollback() throws IOException {
        List<Entry> ordered = new ArrayList<>(entries.values());
        Collections.reverse(ordered);
        for (Entry entry : ordered) validateRollbackEntry(entry);
        List<String> restored = new ArrayList<>();
        for (Entry entry : ordered) {
            Path target = Paths.get(entry.path).toAbsolutePath().normalize();
            ensureInside(target);
            if (entry.existed) {
                Path snapshot = journalDir.resolve(entry.snapshot).normalize();
                if (!snapshot.startsWith(journalDir) || !Files.exists(snapshot)) {
                    throw new IOException("回滚快照缺失: " + entry.path);
                }
                ToolUtils.writeAtomically(target, Files.readAllBytes(snapshot));
            } else {
                Files.deleteIfExists(target);
            }
            restored.add(entry.path);
        }
        entries.clear();
        persist();
        return restored;
    }

    public synchronized boolean hasChanges() { return !entries.isEmpty(); }
    public String getRunId() { return runId; }
    public String getWorkspace() { return workspace.toString(); }

    private void load() {
        if (!Files.exists(journalFile)) return;
        try {
            JsonObject json = GSON.fromJson(new String(Files.readAllBytes(journalFile), StandardCharsets.UTF_8), JsonObject.class);
            if (json == null || !json.has("entries")) return;
            for (JsonElement element : json.getAsJsonArray("entries")) {
                JsonObject item = element.getAsJsonObject();
                Entry entry = new Entry(item.get("path").getAsString(), item.get("existed").getAsBoolean(),
                        item.has("snapshot") ? item.get("snapshot").getAsString() : "",
                        item.has("writtenHash") ? item.get("writtenHash").getAsString() : "");
                entries.put(entry.path, entry);
            }
        } catch (Exception ignored) {}
    }

    private void persist() throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("runId", runId);
        json.addProperty("workspace", workspace.toString());
        JsonArray array = new JsonArray();
        for (Entry entry : entries.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("path", entry.path);
            item.addProperty("existed", entry.existed);
            item.addProperty("snapshot", entry.snapshot);
            item.addProperty("writtenHash", entry.writtenHash);
            array.add(item);
        }
        json.add("entries", array);
        ToolUtils.writeAtomically(journalFile, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    private void ensureInside(Path path) throws IOException {
        Path canonicalWorkspace = workspace.toFile().getCanonicalFile().toPath();
        Path canonicalPath = path.toFile().getCanonicalFile().toPath();
        if (!canonicalPath.equals(canonicalWorkspace) && !canonicalPath.startsWith(canonicalWorkspace)) {
            throw new IOException("回滚路径超出任务工作区: " + path);
        }
    }

    private void validateRollbackEntry(Entry entry) throws IOException {
        Path target = Paths.get(entry.path).toAbsolutePath().normalize();
        ensureInside(target);
        if (entry.writtenHash == null || entry.writtenHash.isEmpty()) {
            throw new IOException("回滚记录缺少写入版本，无法安全覆盖: " + entry.path);
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("文件已被移动或删除，回滚已停止: " + entry.path);
        }
        if (!entry.writtenHash.equals(fileHash(target))) {
            throw new IOException("文件在任务结束后又发生变化，拒绝覆盖: " + entry.path);
        }
        if (entry.existed) {
            Path snapshot = journalDir.resolve(entry.snapshot).normalize();
            if (!snapshot.startsWith(journalDir) || !Files.isRegularFile(snapshot)) {
                throw new IOException("回滚快照缺失: " + entry.path);
            }
        }
    }

    private static String fileHash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder value = new StringBuilder();
            for (byte b : digest.digest()) value.append(String.format("%02x", b & 0xff));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("当前运行环境不支持 SHA-256", e);
        }
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 12; i++) result.append(String.format("%02x", hash[i]));
            return result.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static final class Entry {
        final String path;
        final boolean existed;
        final String snapshot;
        String writtenHash;

        Entry(String path, boolean existed, String snapshot, String writtenHash) {
            this.path = path;
            this.existed = existed;
            this.snapshot = snapshot;
            this.writtenHash = writtenHash;
        }
    }
}
