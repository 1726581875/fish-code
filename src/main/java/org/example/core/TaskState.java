package org.example.core;

import com.google.gson.*;
import java.util.*;

public final class TaskState {
    public enum Phase {
        DISCOVER, PLAN, EXECUTE, VERIFY, COMPLETE, BLOCKED, CANCELLED, INTERRUPTED
    }

    public enum VerificationLevel {
        NONE(0), SANITY(1), STATIC(2), BUILD(3), TEST(4);
        private final int rank;
        VerificationLevel(int rank) { this.rank = rank; }
        public boolean covers(VerificationLevel required) { return rank >= required.rank; }
    }

    private final String runId;
    private String sessionId = "";
    private String objective;
    private final List<String> acceptanceCriteria = new ArrayList<>();
    private final List<TaskStep> steps = new ArrayList<>();
    private final LinkedHashSet<String> modifiedFiles = new LinkedHashSet<>();
    private final List<JsonObject> verifications = new ArrayList<>();
    private final List<String> remainingRisks = new ArrayList<>();
    private final LinkedHashMap<String, JsonObject> toolLedger = new LinkedHashMap<>();
    private Phase phase = Phase.DISCOVER;
    private String blockedReason = "";
    private String nextAction = "定位相关代码和约束";
    private int rounds;
    private boolean hasWrites;
    private boolean verifiedAfterLastWrite;
    private boolean verificationReminderSent;
    private long lastWriteAt;
    private long lastVerificationAt;
    private VerificationLevel requiredVerificationLevel = VerificationLevel.SANITY;
    private VerificationLevel bestVerificationLevel = VerificationLevel.NONE;
    private final long createdAt;
    private long updatedAt;
    private transient Runnable changeListener;

    public TaskState(String runId, String objective) {
        this(runId, objective, System.currentTimeMillis(), true);
    }

    private TaskState(String runId, String objective, long createdAt, boolean addDefaults) {
        this.runId = runId;
        this.objective = shorten(objective, 1000);
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        if (addDefaults) {
            acceptanceCriteria.add("满足用户请求并保持无关代码不变");
            acceptanceCriteria.add("代码修改后完成适当验证，或明确说明阻塞原因");
            steps.add(new TaskStep("理解需求并定位相关代码", "in_progress"));
            steps.add(new TaskStep("制定并执行修改", "pending"));
            steps.add(new TaskStep("运行验证并检查结果", "pending"));
        }
    }

    public synchronized void setChangeListener(Runnable listener) { this.changeListener = listener; }
    public synchronized String getRunId() { return runId; }
    public synchronized String getSessionId() { return sessionId; }
    public synchronized Phase getPhase() { return phase; }
    public synchronized boolean hasWrites() { return hasWrites; }
    public synchronized boolean isVerifiedAfterLastWrite() { return verifiedAfterLastWrite; }
    public synchronized boolean isVerificationReminderSent() { return verificationReminderSent; }
    public synchronized List<String> getModifiedFiles() { return new ArrayList<>(modifiedFiles); }
    public synchronized String getBlockedReason() { return blockedReason; }
    public synchronized String getObjective() { return objective; }
    public synchronized VerificationLevel getRequiredVerificationLevel() { return requiredVerificationLevel; }
    public synchronized VerificationLevel getBestVerificationLevel() { return bestVerificationLevel; }
    public synchronized boolean isTerminal() {
        return phase == Phase.COMPLETE || phase == Phase.BLOCKED
                || phase == Phase.CANCELLED || phase == Phase.INTERRUPTED;
    }

    public synchronized void setSessionId(String value) {
        sessionId = value == null ? "" : value;
        changed();
    }

    public synchronized void incrementRound() {
        rounds++;
        changed();
    }

    public synchronized void updateFromAgent(String requestedPhase, String newObjective,
                                             String stepTitle, String stepStatus,
                                             String newNextAction, String newBlockedReason,
                                             String remainingRisk) {
        if (newObjective != null && !newObjective.trim().isEmpty()) objective = shorten(newObjective, 1000);
        if (stepTitle != null && !stepTitle.trim().isEmpty()) updateStep(stepTitle, stepStatus);
        if (newNextAction != null && !newNextAction.trim().isEmpty()) nextAction = shorten(newNextAction, 500);
        if (newBlockedReason != null && !newBlockedReason.trim().isEmpty()) blockedReason = shorten(newBlockedReason, 1000);
        if (remainingRisk != null && !remainingRisk.trim().isEmpty() && !remainingRisks.contains(remainingRisk.trim())) {
            remainingRisks.add(shorten(remainingRisk, 500));
        }
        Phase parsed = parsePhase(requestedPhase);
        if (parsed != null && parsed != Phase.COMPLETE) phase = parsed;
        changed();
    }

    public synchronized void addAcceptanceCriterion(String criterion) {
        String value = shorten(criterion, 500);
        if (!value.isEmpty() && !acceptanceCriteria.contains(value)) {
            acceptanceCriteria.add(value);
            changed();
        }
    }

    public synchronized void recordToolResult(String callId, String content, JsonObject details) {
        if (callId == null || callId.trim().isEmpty()) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("content", shorten(content, 4000));
        JsonObject compactDetails = new JsonObject();
        if (details != null) {
            String[] keys = {"type", "error", "exitCode", "timedOut", "truncated", "verification",
                    "verificationLevel", "path", "changed", "created", "blocked", "risk"};
            for (String key : keys) {
                if (details.has(key) && details.get(key).isJsonPrimitive()) compactDetails.add(key, details.get(key));
            }
        }
        entry.add("details", compactDetails);
        toolLedger.put(callId, entry);
        while (toolLedger.size() > 200) toolLedger.remove(toolLedger.keySet().iterator().next());
        changed();
    }

    public synchronized JsonObject getToolResult(String callId) {
        JsonObject value = callId == null ? null : toolLedger.get(callId);
        return value == null ? null : value.deepCopy();
    }

    public synchronized void markToolActivity(String toolName) {
        if (isTerminal()) return;
        if ("read_file".equals(toolName) || "find_file".equals(toolName) || "search_text".equals(toolName)) {
            if (phase == Phase.DISCOVER) phase = Phase.PLAN;
            completeStep(0);
        } else if ("edit_file".equals(toolName) || "write_file".equals(toolName)) {
            phase = Phase.EXECUTE;
            completeStep(0);
            setStepStatus(1, "in_progress");
        } else if ("run_command".equals(toolName)) {
            phase = Phase.VERIFY;
            completeStep(1);
            setStepStatus(2, "in_progress");
        }
        changed();
    }

    public synchronized void markModified(String path) {
        if (path != null && !path.trim().isEmpty()) modifiedFiles.add(path);
        hasWrites = true;
        VerificationLevel inferred = requiredLevelForPath(path);
        if (inferred.rank > requiredVerificationLevel.rank) requiredVerificationLevel = inferred;
        bestVerificationLevel = VerificationLevel.NONE;
        verifiedAfterLastWrite = false;
        verificationReminderSent = false;
        lastWriteAt = System.currentTimeMillis();
        phase = Phase.EXECUTE;
        completeStep(0);
        setStepStatus(1, "in_progress");
        changed();
    }

    public synchronized void recordVerification(String command, int exitCode, boolean timedOut, String output) {
        recordVerification(command, exitCode, timedOut, output, inferVerificationLevel(command).name());
    }

    public synchronized void recordVerification(String command, int exitCode, boolean timedOut,
                                                String output, String levelName) {
        VerificationLevel level = parseVerificationLevel(levelName);
        JsonObject record = new JsonObject();
        record.addProperty("command", shorten(command, 1000));
        record.addProperty("exitCode", exitCode);
        record.addProperty("timedOut", timedOut);
        record.addProperty("success", exitCode == 0 && !timedOut);
        record.addProperty("level", level.name());
        record.addProperty("requiredLevel", requiredVerificationLevel.name());
        record.addProperty("output", shorten(output, 2000));
        record.addProperty("timestamp", System.currentTimeMillis());
        verifications.add(record);
        lastVerificationAt = System.currentTimeMillis();
        if (exitCode == 0 && !timedOut && level.rank > bestVerificationLevel.rank) bestVerificationLevel = level;
        verifiedAfterLastWrite = hasWrites && lastVerificationAt >= lastWriteAt
                && exitCode == 0 && !timedOut && level.covers(requiredVerificationLevel);
        phase = Phase.VERIFY;
        completeStep(1);
        setStepStatus(2, verifiedAfterLastWrite ? "completed" : "blocked");
        if (verifiedAfterLastWrite) {
            blockedReason = "";
        } else {
            blockedReason = exitCode == 0 && !timedOut
                    ? "验证通过，但强度不足：需要 " + requiredVerificationLevel.name()
                    : "最近一次验证未通过";
        }
        changed();
    }

    public synchronized void markVerificationReminderSent() {
        verificationReminderSent = true;
        phase = Phase.VERIFY;
        nextAction = "运行与修改相关的测试、构建或静态检查";
        setStepStatus(2, "in_progress");
        changed();
    }

    public synchronized void complete() {
        phase = Phase.COMPLETE;
        blockedReason = "";
        nextAction = "";
        for (TaskStep step : steps) {
            if (!"blocked".equals(step.getStatus())) step.setStatus("completed");
        }
        changed();
    }

    public synchronized void block(String reason) {
        phase = Phase.BLOCKED;
        blockedReason = shorten(reason, 1000);
        nextAction = "根据阻塞原因补充信息或重新执行";
        changed();
    }

    public synchronized void cancel() {
        if (phase == Phase.CANCELLED) return;
        phase = Phase.CANCELLED;
        blockedReason = "用户取消了任务";
        nextAction = "";
        changed();
    }

    public synchronized void interrupt(String reason) {
        phase = Phase.INTERRUPTED;
        blockedReason = shorten(reason, 1000);
        nextAction = "重试中断的任务";
        changed();
    }

    public synchronized void resume() {
        if (phase == Phase.COMPLETE) return;
        phase = Phase.DISCOVER;
        blockedReason = "";
        verificationReminderSent = false;
        if (nextAction == null || nextAction.isEmpty()) nextAction = "核对工作区和上次任务状态后继续";
        changed();
    }

    public synchronized void markRolledBack() {
        modifiedFiles.clear();
        toolLedger.clear();
        hasWrites = false;
        verifiedAfterLastWrite = false;
        bestVerificationLevel = VerificationLevel.NONE;
        phase = Phase.CANCELLED;
        blockedReason = "本次任务修改已回滚";
        nextAction = "";
        changed();
    }

    public synchronized JsonObject toJson() {
        return buildJson(false);
    }

    public synchronized JsonObject toPersistentJson() {
        return buildJson(true);
    }

    private JsonObject buildJson(boolean includeToolLedger) {
        JsonObject json = new JsonObject();
        json.addProperty("runId", runId);
        json.addProperty("sessionId", sessionId);
        json.addProperty("objective", objective);
        json.addProperty("phase", phase.name());
        json.addProperty("blockedReason", blockedReason);
        json.addProperty("nextAction", nextAction);
        json.addProperty("rounds", rounds);
        json.addProperty("hasWrites", hasWrites);
        json.addProperty("verifiedAfterLastWrite", verifiedAfterLastWrite);
        json.addProperty("requiredVerificationLevel", requiredVerificationLevel.name());
        json.addProperty("bestVerificationLevel", bestVerificationLevel.name());
        json.addProperty("createdAt", createdAt);
        json.addProperty("updatedAt", updatedAt);
        JsonArray criteria = new JsonArray();
        for (String value : acceptanceCriteria) criteria.add(value);
        json.add("acceptanceCriteria", criteria);
        JsonArray stepArray = new JsonArray();
        for (TaskStep step : steps) stepArray.add(step.toJson());
        json.add("steps", stepArray);
        JsonArray files = new JsonArray();
        for (String value : modifiedFiles) files.add(value);
        json.add("modifiedFiles", files);
        JsonArray verificationArray = new JsonArray();
        for (JsonObject value : verifications) verificationArray.add(value.deepCopy());
        json.add("verifications", verificationArray);
        JsonArray risks = new JsonArray();
        for (String value : remainingRisks) risks.add(value);
        json.add("remainingRisks", risks);
        if (includeToolLedger) {
            JsonObject ledger = new JsonObject();
            for (Map.Entry<String, JsonObject> entry : toolLedger.entrySet()) {
                ledger.add(entry.getKey(), entry.getValue().deepCopy());
            }
            json.add("toolLedger", ledger);
        }
        return json;
    }

    public static TaskState fromJson(JsonObject json) {
        if (json == null) return null;
        String runId = stringValue(json, "runId", UUID.randomUUID().toString());
        String objective = stringValue(json, "objective", "继续未完成任务");
        long createdAt = longValue(json, "createdAt", System.currentTimeMillis());
        TaskState state = new TaskState(runId, objective, createdAt, false);
        state.sessionId = stringValue(json, "sessionId", "");
        state.phase = parsePhase(stringValue(json, "phase", "DISCOVER"));
        if (state.phase == null) state.phase = Phase.DISCOVER;
        state.blockedReason = stringValue(json, "blockedReason", "");
        state.nextAction = stringValue(json, "nextAction", "核对工作区后继续");
        state.rounds = intValue(json, "rounds", 0);
        state.hasWrites = booleanValue(json, "hasWrites", false);
        state.verifiedAfterLastWrite = booleanValue(json, "verifiedAfterLastWrite", false);
        state.requiredVerificationLevel = parseVerificationLevel(stringValue(json,
                "requiredVerificationLevel", "SANITY"));
        state.bestVerificationLevel = parseVerificationLevel(stringValue(json,
                "bestVerificationLevel", "NONE"));
        state.updatedAt = longValue(json, "updatedAt", createdAt);
        if (json.has("acceptanceCriteria") && json.get("acceptanceCriteria").isJsonArray()) {
            for (JsonElement item : json.getAsJsonArray("acceptanceCriteria")) state.acceptanceCriteria.add(item.getAsString());
        }
        if (json.has("steps") && json.get("steps").isJsonArray()) {
            for (JsonElement item : json.getAsJsonArray("steps")) {
                TaskStep step = item.isJsonObject() ? TaskStep.fromJson(item.getAsJsonObject()) : null;
                if (step != null) state.steps.add(step);
            }
        }
        if (json.has("modifiedFiles") && json.get("modifiedFiles").isJsonArray()) {
            for (JsonElement item : json.getAsJsonArray("modifiedFiles")) state.modifiedFiles.add(item.getAsString());
        }
        if (json.has("verifications") && json.get("verifications").isJsonArray()) {
            for (JsonElement item : json.getAsJsonArray("verifications")) {
                if (item.isJsonObject()) state.verifications.add(item.getAsJsonObject().deepCopy());
            }
        }
        if (json.has("remainingRisks") && json.get("remainingRisks").isJsonArray()) {
            for (JsonElement item : json.getAsJsonArray("remainingRisks")) state.remainingRisks.add(item.getAsString());
        }
        if (json.has("toolLedger") && json.get("toolLedger").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("toolLedger").entrySet()) {
                if (entry.getValue().isJsonObject()) state.toolLedger.put(entry.getKey(), entry.getValue().getAsJsonObject().deepCopy());
            }
        }
        return state;
    }

    public synchronized String buildCheckpoint() {
        StringBuilder summary = new StringBuilder();
        summary.append("任务检查点\n目标: ").append(objective)
                .append("\n阶段: ").append(phase.name())
                .append("\n下一步: ").append(nextAction);
        if (!modifiedFiles.isEmpty()) summary.append("\n已修改文件: ").append(String.join(", ", modifiedFiles));
        if (hasWrites) summary.append("\n验证要求: ").append(requiredVerificationLevel.name())
                .append("，当前最佳: ").append(bestVerificationLevel.name());
        if (!acceptanceCriteria.isEmpty()) {
            summary.append("\n验收条件:");
            for (String criterion : acceptanceCriteria) summary.append("\n- ").append(criterion);
        }
        if (!blockedReason.isEmpty()) summary.append("\n阻塞/错误: ").append(blockedReason);
        summary.append("\n步骤:");
        for (TaskStep step : steps) summary.append("\n- [").append(step.getStatus()).append("] ").append(step.getTitle());
        if (!verifications.isEmpty()) {
            JsonObject last = verifications.get(verifications.size() - 1);
            summary.append("\n最近验证: ").append(last.get("command").getAsString())
                    .append(" (exit=").append(last.get("exitCode").getAsInt()).append(')');
        }
        return summary.toString();
    }

    private void updateStep(String title, String status) {
        String clean = shorten(title, 300);
        for (TaskStep step : steps) {
            if (step.getTitle().equals(clean)) {
                step.setStatus(status);
                return;
            }
        }
        steps.add(new TaskStep(clean, status));
    }

    private void completeStep(int index) { setStepStatus(index, "completed"); }
    private void setStepStatus(int index, String status) {
        if (index >= 0 && index < steps.size()) steps.get(index).setStatus(status);
    }

    private void changed() {
        updatedAt = System.currentTimeMillis();
        Runnable listener = changeListener;
        if (listener != null) listener.run();
    }

    private static Phase parsePhase(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try { return Phase.valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static VerificationLevel requiredLevelForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(java|kt|kts|groovy|js|jsx|ts|tsx|py|go|rs|c|cc|cpp|cxx|h|hpp|cs|rb|php|swift|scala)$")) {
            return VerificationLevel.STATIC;
        }
        return VerificationLevel.SANITY;
    }

    private static VerificationLevel inferVerificationLevel(String command) {
        String lower = command == null ? "" : command.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(test|tests|pytest|jest|vitest|cargo test|go test)\\b.*")) return VerificationLevel.TEST;
        if (lower.matches(".*\\b(build|compile|package|javac|tsc)\\b.*")) return VerificationLevel.BUILD;
        if (lower.matches(".*\\b(lint|check|eslint|ruff|shellcheck|stylelint)\\b.*")) return VerificationLevel.STATIC;
        return VerificationLevel.SANITY;
    }

    private static VerificationLevel parseVerificationLevel(String value) {
        try { return VerificationLevel.valueOf(value == null ? "NONE" : value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return VerificationLevel.NONE; }
    }

    private static String stringValue(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }
    private static long longValue(JsonObject json, String key, long fallback) {
        try { return json.has(key) ? json.get(key).getAsLong() : fallback; } catch (Exception ignored) { return fallback; }
    }
    private static int intValue(JsonObject json, String key, int fallback) {
        try { return json.has(key) ? json.get(key).getAsInt() : fallback; } catch (Exception ignored) { return fallback; }
    }
    private static boolean booleanValue(JsonObject json, String key, boolean fallback) {
        try { return json.has(key) ? json.get(key).getAsBoolean() : fallback; } catch (Exception ignored) { return fallback; }
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
}
