package org.example.core;

import org.example.tool.ToolResult;

import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class AgentRun {
    private final String runId;
    private final String model;
    private final String apiUrl;
    private final String apiKey;
    private final String cwd;
    private final TaskState taskState;
    private final ChangeJournal changeJournal;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> confirmations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ToolResult> executedToolCalls = new ConcurrentHashMap<>();
    private final Set<Process> activeProcesses = Collections.newSetFromMap(new ConcurrentHashMap<Process, Boolean>());
    private final AtomicInteger confirmationCounter = new AtomicInteger();
    private volatile boolean stopRequested;
    private volatile boolean approveAllRemaining;
    private volatile HttpURLConnection activeConnection;
    private volatile String lastFailureKey = "";
    private volatile int repeatedFailureCount;
    private volatile long updatedAt = System.currentTimeMillis();
    private volatile boolean running;

    public AgentRun(String runId, String objective, String model, String apiUrl, String apiKey, String cwd) {
        this(runId, objective, model, apiUrl, apiKey, cwd, null);
    }

    public AgentRun(String runId, String objective, String model, String apiUrl, String apiKey,
                    String cwd, TaskState restoredState) {
        this.runId = runId == null || runId.trim().isEmpty() ? UUID.randomUUID().toString() : runId;
        this.model = model;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.cwd = cwd;
        this.taskState = restoredState == null ? new TaskState(this.runId, objective) : restoredState;
        if (!this.runId.equals(this.taskState.getRunId())) {
            throw new IllegalArgumentException("恢复的任务状态与 runId 不匹配");
        }
        this.changeJournal = new ChangeJournal(this.runId, cwd);
    }

    public String getRunId() { return runId; }
    public String getModel() { return model; }
    public String getApiUrl() { return apiUrl; }
    public String getApiKey() { return apiKey; }
    public String getCwd() { return cwd; }
    public TaskState getTaskState() { return taskState; }
    public ChangeJournal getChangeJournal() { return changeJournal; }
    public boolean isStopRequested() { return stopRequested; }
    public boolean isApproveAllRemaining() { return approveAllRemaining; }
    public void setApproveAllRemaining(boolean value) { approveAllRemaining = value; touch(); }
    public long getUpdatedAt() { return updatedAt; }
    public boolean isRunning() { return running; }
    public void setRunning(boolean value) { running = value; touch(); }

    public String createConfirmationRequest() {
        String key = runId + "-confirm-" + confirmationCounter.incrementAndGet();
        confirmations.put(key, new CompletableFuture<Boolean>());
        touch();
        return key;
    }

    public boolean awaitConfirmation(String key, long timeout, TimeUnit unit) {
        CompletableFuture<Boolean> future = confirmations.get(key);
        if (future == null) return false;
        try {
            return future.get(timeout, unit);
        } catch (Exception ignored) {
            return false;
        } finally {
            confirmations.remove(key);
            touch();
        }
    }

    public boolean resolveConfirmation(String key, boolean approved, boolean approveAll) {
        if (approveAll && approved) approveAllRemaining = true;
        CompletableFuture<Boolean> future = confirmations.remove(key);
        if (future == null) return false;
        future.complete(approved);
        touch();
        return true;
    }

    public void registerConnection(HttpURLConnection connection) {
        activeConnection = connection;
        touch();
    }

    public void clearConnection(HttpURLConnection connection) {
        if (activeConnection == connection) activeConnection = null;
        touch();
    }

    public void registerProcess(Process process) {
        if (process != null) activeProcesses.add(process);
        touch();
    }

    public void clearProcess(Process process) {
        if (process != null) activeProcesses.remove(process);
        touch();
    }

    public synchronized void requestStop() {
        if (stopRequested) return;
        stopRequested = true;
        approveAllRemaining = false;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        for (Process process : new ArrayList<>(activeProcesses)) destroyProcessTree(process);
        for (CompletableFuture<Boolean> future : confirmations.values()) future.complete(false);
        confirmations.clear();
        taskState.cancel();
        touch();
    }

    public void prepareRetry() {
        stopRequested = false;
        approveAllRemaining = false;
        taskState.resume();
        clearFailureStreak();
        touch();
    }

    public ToolResult getExecutedResult(String callId) {
        if (callId == null) return null;
        ToolResult inMemory = executedToolCalls.get(callId);
        if (inMemory != null) return inMemory;
        com.google.gson.JsonObject persisted = taskState.getToolResult(callId);
        if (persisted == null) return null;
        String content = persisted.has("content") ? persisted.get("content").getAsString() : "";
        com.google.gson.JsonObject details = persisted.has("details") && persisted.get("details").isJsonObject()
                ? persisted.getAsJsonObject("details") : new com.google.gson.JsonObject();
        ToolResult restored = new ToolResult(content, details);
        executedToolCalls.put(callId, restored);
        return restored;
    }

    public void recordExecutedResult(String callId, ToolResult result) {
        if (callId != null && !callId.isEmpty() && result != null) {
            executedToolCalls.put(callId, result);
            taskState.recordToolResult(callId, result.getContent(), result.getDetails());
        }
        touch();
    }

    public int recordFailure(String toolName, String args, String error) {
        String key = String.valueOf(toolName) + "\n" + String.valueOf(args) + "\n" + String.valueOf(error);
        if (key.equals(lastFailureKey)) repeatedFailureCount++;
        else {
            lastFailureKey = key;
            repeatedFailureCount = 1;
        }
        touch();
        return repeatedFailureCount;
    }

    public void clearFailureStreak() {
        lastFailureKey = "";
        repeatedFailureCount = 0;
        touch();
    }

    private void touch() { updatedAt = System.currentTimeMillis(); }

    public static void destroyProcessTree(Process process) {
        if (process == null) return;
        List<Object> children = new ArrayList<>();
        Class<?> processHandleClass = null;
        Object rootHandle = null;
        try {
            processHandleClass = Class.forName("java.lang.ProcessHandle");
            rootHandle = Process.class.getMethod("toHandle").invoke(process);
            Object descendants = processHandleClass.getMethod("descendants").invoke(rootHandle);
            if (descendants instanceof Stream) {
                ((Stream<?>) descendants).forEach(children::add);
                ((Stream<?>) descendants).close();
            }
        } catch (Exception ignored) {}
        if (processHandleClass != null && rootHandle != null) {
            final Class<?> handleClass = processHandleClass;
            final Object root = rootHandle;
            children.sort((left, right) -> Integer.compare(
                    handleDepth(handleClass, left, root), handleDepth(handleClass, right, root)));
        }
        if (processHandleClass != null) {
            for (Object child : children) {
                destroyHandle(processHandleClass, child);
            }
        }
        // A shell waiting on its children needs a brief chance to reap them.
        // Killing the parent immediately can leave a zombie adopted by init.
        if (!children.isEmpty()) {
            try { process.waitFor(500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        try { process.destroy(); } catch (Exception ignored) {}
        try {
            process.waitFor(250, TimeUnit.MILLISECONDS);
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        if (processHandleClass != null) {
            for (Object child : children) {
                try {
                    boolean alive = (Boolean) processHandleClass.getMethod("isAlive").invoke(child);
                    if (alive) destroyHandle(processHandleClass, child);
                } catch (Exception ignored) {}
            }
        }
    }

    private static void destroyHandle(Class<?> processHandleClass, Object handle) {
        try { processHandleClass.getMethod("destroyForcibly").invoke(handle); }
        catch (Exception ignored) {}
        try {
            boolean alive = (Boolean) processHandleClass.getMethod("isAlive").invoke(handle);
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            if (alive && !windows) {
                long pid = (Long) processHandleClass.getMethod("pid").invoke(handle);
                Process killer = new ProcessBuilder("kill", "-KILL", String.valueOf(pid)).start();
                killer.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {}
    }

    private static int handleDepth(Class<?> processHandleClass, Object handle, Object root) {
        try {
            long rootPid = (Long) processHandleClass.getMethod("pid").invoke(root);
            Object current = handle;
            for (int depth = 1; depth < 64; depth++) {
                Object optional = processHandleClass.getMethod("parent").invoke(current);
                boolean present = (Boolean) optional.getClass().getMethod("isPresent").invoke(optional);
                if (!present) return depth;
                Object parent = optional.getClass().getMethod("get").invoke(optional);
                long parentPid = (Long) processHandleClass.getMethod("pid").invoke(parent);
                if (parentPid == rootPid) return depth;
                current = parent;
            }
        } catch (Exception ignored) {}
        return 64;
    }
}
