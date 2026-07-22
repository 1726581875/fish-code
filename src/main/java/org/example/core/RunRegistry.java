package org.example.core;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RunRegistry {
    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();

    public AgentRun create(String objective, String model, String apiUrl, String apiKey, String cwd) {
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), objective, model, apiUrl, apiKey, cwd);
        runs.put(run.getRunId(), run);
        return run;
    }

    public AgentRun create(String objective, String model, String apiUrl, String apiKey,
                           String reasoningEffort, String cwd) {
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), objective, model, apiUrl, apiKey,
                reasoningEffort, cwd);
        runs.put(run.getRunId(), run);
        return run;
    }

    public AgentRun resume(String runId, String objective, String model, String apiUrl, String apiKey,
                           String cwd, com.google.gson.JsonObject persistedState) throws IOException {
        return resume(runId, objective, model, apiUrl, apiKey, null, cwd, persistedState);
    }

    public AgentRun resume(String runId, String objective, String model, String apiUrl, String apiKey,
                           String reasoningEffort, String cwd,
                           com.google.gson.JsonObject persistedState) throws IOException {
        AgentRun existing = get(runId);
        if (existing != null) {
            if (!new java.io.File(existing.getCwd()).getCanonicalFile().equals(new java.io.File(cwd).getCanonicalFile())) {
                throw new IOException("恢复任务的工作区与原任务不一致");
            }
            if (existing.getTaskState().getPhase() == TaskState.Phase.COMPLETE) {
                throw new IOException("已完成任务不能再次恢复");
            }
            existing.prepareRetry();
            return existing;
        }
        TaskState state = TaskState.fromJson(persistedState);
        if (state == null || !runId.equals(state.getRunId())) throw new IOException("任务状态不存在或 runId 不匹配");
        if (state.getPhase() == TaskState.Phase.COMPLETE) throw new IOException("已完成任务不能再次恢复");
        AgentRun restored = new AgentRun(runId, objective, model, apiUrl, apiKey, reasoningEffort, cwd, state);
        restored.prepareRetry();
        AgentRun raced = runs.putIfAbsent(runId, restored);
        return raced == null ? restored : raced;
    }

    public AgentRun get(String runId) { return runId == null ? null : runs.get(runId); }

    public boolean cancel(String runId) {
        AgentRun run = get(runId);
        if (run == null) return false;
        run.requestStop();
        return true;
    }

    public boolean confirm(String runId, String key, boolean approved, boolean approveAll) {
        AgentRun run = get(runId);
        return run != null && run.resolveConfirmation(key, approved, approveAll);
    }

    public boolean submitUserInput(String runId, String key, String answer) {
        AgentRun run = get(runId);
        return run != null && run.resolveUserInput(key, answer);
    }

    public List<String> rollback(String runId) throws IOException {
        AgentRun run = get(runId);
        if (run == null) throw new IOException("任务不存在或已过期");
        if (run.isRunning()) throw new IOException("任务仍在执行，不能回滚");
        List<String> restored = run.getChangeJournal().rollback();
        run.getTaskState().markRolledBack();
        return restored;
    }

    public void cleanup(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        runs.entrySet().removeIf(entry -> entry.getValue().getUpdatedAt() < cutoff);
    }
}
