package org.example;

import com.google.gson.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.example.core.*;
import org.example.tool.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;



public class TerminalStart {

    private static final Gson GSON = new Gson();

    private static final Set<String> WRITE_TOOLS = new HashSet<>(Arrays.asList(
            "edit_file", "write_file", "run_command"));

    private static volatile boolean stopRequested = false;
    private static volatile boolean approveAllRemaining = false;
    private static volatile HttpURLConnection activeConnection = null;
    private static volatile Process activeProcess = null;
    private static final ThreadLocal<AgentRun> currentRun = new ThreadLocal<>();
    private static volatile AgentRun legacyActiveRun;
    private static final long ESCAPE_SEQUENCE_TIMEOUT_MS = 100L;
    private static final double AGENT_TEMPERATURE = 0.2;
    private static final long USER_INPUT_TIMEOUT_SECONDS = 600L;

    private volatile String apiUrl;
    private volatile String apiKey;
    private volatile String model;
    private volatile String reasoningEffort = "";

    // Per-request working directory - ThreadLocal for tool execution context
    private static final ThreadLocal<String> currentCwd = new ThreadLocal<>();

    public static void setCurrentCwd(String cwd) { currentCwd.set(cwd); }
    public static void clearCurrentCwd() { currentCwd.remove(); }
    public static String getCurrentCwd() {
        AgentRun run = currentRun.get();
        if (run != null && run.getCwd() != null && !run.getCwd().trim().isEmpty()) return run.getCwd();
        String cwd = currentCwd.get();
        return cwd != null ? cwd : System.getProperty("user.dir");
    }

    public static AgentRun getCurrentRun() { return currentRun.get(); }

    // Web confirmation mechanism
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Boolean>> pendingConfirmations = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int confirmCounter = 0;

    public static String createConfirmationRequest() {
        String key = "confirm-" + (++confirmCounter);
        pendingConfirmations.put(key, new java.util.concurrent.CompletableFuture<>());
        return key;
    }

    public static boolean resolveConfirmation(String key, boolean approved) {
        java.util.concurrent.CompletableFuture<Boolean> future = pendingConfirmations.remove(key);
        if (future != null) {
            future.complete(approved);
            return true;
        }
        return false;
    }

    public static void setApproveAllRemaining(boolean approve) {
        AgentRun run = currentRun.get();
        if (run != null) {
            run.setApproveAllRemaining(approve);
            return;
        }
        approveAllRemaining = approve;
    }

    public static boolean isApproveAllRemaining() {
        AgentRun run = currentRun.get();
        if (run != null) return run.isApproveAllRemaining();
        return approveAllRemaining;
    }

    public static void resetStopRequest() {
        stopRequested = false;
    }

    public static void requestStop() {
        AgentRun run = legacyActiveRun;
        if (run != null) {
            run.requestStop();
            return;
        }
        stopRequested = true;
        approveAllRemaining = false;
        HttpURLConnection conn = activeConnection;
        if (conn != null) {
            conn.disconnect();
        }
        Process process = activeProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        for (java.util.concurrent.CompletableFuture<Boolean> future : pendingConfirmations.values()) {
            future.complete(false);
        }
        pendingConfirmations.clear();
    }

    public static void registerActiveProcess(Process process) {
        AgentRun run = currentRun.get();
        if (run != null) {
            run.registerProcess(process);
            return;
        }
        activeProcess = process;
    }

    public static void clearActiveProcess(Process process) {
        AgentRun run = currentRun.get();
        if (run != null) {
            run.clearProcess(process);
            return;
        }
        if (activeProcess == process) {
            activeProcess = null;
        }
    }

    public static boolean isStopRequested() {
        AgentRun run = currentRun.get();
        if (run != null) return run.isStopRequested();
        return stopRequested;
    }

    // Web requests can override the CLI/global defaults without mutating the shared agent.
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> requestModelOverrides = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, String[]> requestConnOverrides = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> requestReasoningOverrides = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setRequestOverride(String model, String apiUrl, String apiKey) {
        setRequestOverride(model, apiUrl, apiKey, null);
    }

    public static void setRequestOverride(String model, String apiUrl, String apiKey, String reasoningEffort) {
        long tid = Thread.currentThread().getId();
        if (model != null) requestModelOverrides.put(tid, model);
        if (apiUrl != null || apiKey != null) requestConnOverrides.put(tid, new String[]{apiUrl, apiKey});
        if (reasoningEffort != null) requestReasoningOverrides.put(tid, reasoningEffort);
    }

    public static void clearRequestOverride() {
        long tid = Thread.currentThread().getId();
        requestModelOverrides.remove(tid);
        requestConnOverrides.remove(tid);
        requestReasoningOverrides.remove(tid);
    }

    private String getOverrideModel() {
        AgentRun run = currentRun.get();
        if (run != null && run.getModel() != null && !run.getModel().trim().isEmpty()) return run.getModel();
        return requestModelOverrides.get(Thread.currentThread().getId());
    }

    private String[] getOverrideConn() {
        AgentRun run = currentRun.get();
        if (run != null && (run.getApiUrl() != null || run.getApiKey() != null)) {
            return new String[]{run.getApiUrl(), run.getApiKey()};
        }
        return requestConnOverrides.get(Thread.currentThread().getId());
    }

    private String getOverrideReasoningEffort() {
        AgentRun run = currentRun.get();
        // A run keeps the exact reasoning setting it started with, including empty = provider default.
        if (run != null && run.getReasoningEffort() != null) return run.getReasoningEffort();
        String override = requestReasoningOverrides.get(Thread.currentThread().getId());
        return override != null ? override : reasoningEffort;
    }
    private final List<JsonObject> messages = new ArrayList<>();
    private List<Tool> tools;
    private final Map<String, Tool> toolMap = new HashMap<>();
    private AgentMode currentMode;
    private Terminal terminal;
    private NonBlockingReader terminalReader;
    private String terminalBackwardTabSequence;
    private ActionConfirmer actionConfirmer;
    private final SessionManager sessionManager;
    private String currentSessionId;
    private final List<String> inputHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 1000;
    private static final java.nio.file.Path HISTORY_FILE =
            java.nio.file.Paths.get(System.getProperty("user.home"), ".fish-code", "input-history.txt");
    private int historyIndex = -1;

    private JsonArray cachedMessagesJson;
    private boolean messagesDirty = true;
    private volatile AgentRun lastCompletedRun;

    public TerminalStart(String apiUrl, String apiKey, String model, SessionManager sessionManager) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.sessionManager = sessionManager;
        loadHistory();
        setMode(AgentMode.CONFIRM);
    }

    public String getApiUrl() { return apiUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getReasoningEffort() { return reasoningEffort; }
    public String getWorkspaceDir() { return System.getProperty("user.dir"); }

    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setModel(String model) { this.model = model; }
    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = normalizeReasoningEffort(reasoningEffort);
    }

    public String setWorkspaceDir(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IOException("工作目录不能为空");
        }
        File dir = ToolUtils.resolveFile(path.trim());
        String canonical = dir.getCanonicalPath();
        File canonicalDir = new File(canonical);
        if (!canonicalDir.exists()) {
            throw new IOException("工作目录不存在: " + canonical);
        }
        if (!canonicalDir.isDirectory()) {
            throw new IOException("不是目录: " + canonical);
        }
        System.setProperty("user.dir", canonical);
        return canonical;
    }

    public void setTerminal(Terminal terminal) throws IOException {
        this.terminal = terminal;
        this.terminalReader = terminal.reader();
        this.terminalBackwardTabSequence = normalizeEscapeSequence(
                terminal.getStringCapability(InfoCmp.Capability.key_btab));
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public interface ActionConfirmer {
        boolean confirm(String fnName, String fnArgs) throws IOException;
    }

    public void setActionConfirmer(ActionConfirmer actionConfirmer) {
        this.actionConfirmer = actionConfirmer;
    }

    public void setMode(AgentMode mode) {
        this.currentMode = mode;
        this.tools = registerTools(mode);
        messagesDirty = true;

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", mode.systemPrompt());
        if (!messages.isEmpty() && "system".equals(messages.get(0).get("role").getAsString())) {
            messages.set(0, systemMsg);
        } else {
            messages.add(0, systemMsg);
        }
    }

    public AgentMode getMode() {
        return currentMode;
    }

    public void newConversation() {
        messages.clear();
        currentSessionId = null;
        messagesDirty = true;
        setMode(currentMode);
    }

    public void clearConversation() {
        messages.clear();
        currentSessionId = null;
        messagesDirty = true;
        setMode(currentMode);
    }

    public boolean loadConversation(String sessionId) {
        List<JsonObject> loaded = sessionManager.loadSession(sessionId);
        if (loaded.isEmpty()) return false;
        messages.clear();
        setMode(currentMode);
        for (JsonObject message : loaded) {
            if (!message.has("role") || !"system".equals(message.get("role").getAsString())) {
                messages.add(message);
            }
        }
        JsonObject previousTask = sessionManager.loadTaskState(sessionId);
        if (previousTask != null) {
            JsonObject checkpoint = new JsonObject();
            checkpoint.addProperty("role", "system");
            checkpoint.addProperty("content", buildPersistedTaskCheckpoint(previousTask));
            messages.add(1, checkpoint);
        }
        currentSessionId = sessionId;
        messagesDirty = true;
        return true;
    }

    private String buildPersistedTaskCheckpoint(JsonObject state) {
        StringBuilder text = new StringBuilder("上次任务检查点");
        if (state.has("objective")) text.append("\n目标: ").append(state.get("objective").getAsString());
        if (state.has("phase")) text.append("\n阶段: ").append(state.get("phase").getAsString());
        if (state.has("nextAction")) text.append("\n下一步: ").append(state.get("nextAction").getAsString());
        if (state.has("blockedReason") && !state.get("blockedReason").getAsString().isEmpty()) {
            text.append("\n阻塞原因: ").append(state.get("blockedReason").getAsString());
        }
        if (state.has("modifiedFiles")) text.append("\n已修改文件: ").append(state.get("modifiedFiles").toString());
        text.append("\n如用户要求继续，请先核对工作区现状再执行。");
        return text.toString();
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    private void ensureSession(String firstUserMessage) {
        if (currentSessionId == null) {
            String cwd = getCurrentCwd();
            currentSessionId = sessionManager.createSession(cwd, model);
            String title = SessionManager.generateTitle(firstUserMessage);
            sessionManager.updateTitle(currentSessionId, title);
        }
    }

    private void persistMessage(JsonObject msg) throws IOException {
        if (currentSessionId != null) {
            sessionManager.appendMessage(currentSessionId, msg);
        }
    }

    private List<Tool> registerTools(AgentMode mode) {
        List<Tool> all = new ArrayList<>();
        all.add(new ReadFileTool());
        all.add(new FindFileTool());
        all.add(new SearchTextTool());
        all.add(new EditFileTool());
        all.add(new WriteFileTool());
        all.add(new RunCommandTool());
        all.add(new RequestUserInputTool());
        all.add(new UpdateTaskTool());

        List<Tool> list = new ArrayList<>();
        toolMap.clear();
        for (Tool t : all) {
            if (mode.getAllowedTools().contains(t.getName())) {
                list.add(t);
                toolMap.put(t.getName(), t);
            }
        }
        return list;
    }

    public ChatResult chat(String userInput) throws Exception {
        AgentRun run = new AgentRun(null, userInput, model, apiUrl, apiKey, getCurrentCwd());
        return chat(userInput, run);
    }

    public ChatResult chat(String userInput, AgentRun run) throws Exception {
        bindRun(run, null);
        try {
            return chatBound(userInput, run);
        } catch (Exception e) {
            if (!run.isStopRequested()) run.getTaskState().interrupt(e.getMessage());
            throw e;
        } finally {
            unbindRun(run);
        }
    }

    private ChatResult chatBound(String userInput, AgentRun run) throws Exception {
        long startTime = System.currentTimeMillis();
        prepareUserInput(userInput);

        JsonObject message;
        for (int round = 0; round < ToolConstants.MAX_TOOL_ROUNDS; round++) {
            run.getTaskState().incrementRound();
            if (isStopRequested()) {
                run.getTaskState().cancel();
                return new ChatResult("(对话已被用户终止)", System.currentTimeMillis() - startTime, estimateTokens());
            }
            message = callApiWithRetry(false);
            JsonElement toolCalls = message.get("tool_calls");
            String content = message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : null;

            if (noToolCalls(toolCalls)) {
                messages.add(message);
                persistMessage(message);
                int finalization = finalizeOrRequestVerification(run);
                if (finalization == 1) continue;
                long duration = System.currentTimeMillis() - startTime;
                return new ChatResult(content != null ? content : "(无回复)", duration, estimateTokens());
            }

            messages.add(message);
            persistMessage(message);
            processToolCalls(toolCalls.getAsJsonArray());
        }

        run.getTaskState().block("达到最大工具调用轮次 " + ToolConstants.MAX_TOOL_ROUNDS);
        long duration = System.currentTimeMillis() - startTime;
        return new ChatResult("(达到最大工具调用轮次)", duration, estimateTokens());
    }

    public ChatResult chatStream(String userInput, StreamCallback callback) throws Exception {
        AgentRun run = new AgentRun(null, userInput, model, apiUrl, apiKey, getCurrentCwd());
        return chatStream(userInput, callback, run);
    }

    public ChatResult chatStream(String userInput, StreamCallback callback, AgentRun run) throws Exception {
        bindRun(run, callback);
        try {
            return chatStreamBound(userInput, callback, run);
        } catch (Exception e) {
            if (!run.isStopRequested()) {
                run.getTaskState().interrupt(e.getMessage());
                callback.onTaskUpdate(run.getTaskState().toJson());
            }
            throw e;
        } finally {
            unbindRun(run);
        }
    }

    private ChatResult chatStreamBound(String userInput, StreamCallback callback, AgentRun run) throws Exception {
        long startTime = System.currentTimeMillis();
        prepareUserInput(userInput);

        JsonObject message;
        for (int round = 0; round < ToolConstants.MAX_TOOL_ROUNDS; round++) {
            run.getTaskState().incrementRound();
            if (isStopRequested()) {
                run.getTaskState().cancel();
                callback.onComplete(new ChatResult("(对话已被用户终止)", System.currentTimeMillis() - startTime, estimateTokens()));
                return new ChatResult("(对话已被用户终止)", System.currentTimeMillis() - startTime, estimateTokens());
            }

            message = callApiStreamingWithRetry(callback);
            if (isStopRequested()) {
                run.getTaskState().cancel();
                ChatResult result = new ChatResult("(对话已被用户终止)", System.currentTimeMillis() - startTime, estimateTokens());
                callback.onComplete(result);
                return result;
            }
            messages.add(message);
            persistMessage(message);

            JsonElement toolCalls = message.get("tool_calls");
            if (noToolCalls(toolCalls)) {
                int finalization = finalizeOrRequestVerification(run);
                callback.onTaskUpdate(run.getTaskState().toJson());
                if (finalization == 1) continue;
                long duration = System.currentTimeMillis() - startTime;
                String reply = message.has("content") && !message.get("content").isJsonNull()
                        ? message.get("content").getAsString() : "(无回复)";
                ChatResult result = new ChatResult(reply, duration, estimateTokens());
                callback.onComplete(result);
                return result;
            }

            processToolCallsStreaming(toolCalls.getAsJsonArray(), callback);
        }

        run.getTaskState().block("达到最大工具调用轮次 " + ToolConstants.MAX_TOOL_ROUNDS);
        callback.onTaskUpdate(run.getTaskState().toJson());
        long duration = System.currentTimeMillis() - startTime;
        ChatResult result = new ChatResult("(达到最大工具调用轮次)", duration, estimateTokens());
        callback.onComplete(result);
        return result;
    }

    private void prepareUserInput(String userInput) throws IOException {
        ensureSession(userInput);
        AgentRun run = currentRun.get();
        if (run != null) run.getTaskState().setSessionId(currentSessionId);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userInput);
        messages.add(userMsg);
        persistMessage(userMsg);
        messagesDirty = true;
    }

    private void bindRun(final AgentRun run, final StreamCallback callback) {
        currentRun.set(run);
        run.setRunning(true);
        legacyActiveRun = run;
        final boolean[] announced = {false};
        run.getTaskState().setChangeListener(() -> {
            if (callback != null && !announced[0] && !run.getTaskState().getSessionId().isEmpty()) {
                announced[0] = true;
                callback.onRunStarted(run.getRunId(), run.getTaskState().getSessionId());
            }
            if (sessionManager != null && !run.getTaskState().getSessionId().isEmpty()) {
                sessionManager.saveTaskState(run.getTaskState().getSessionId(), run.getTaskState().toPersistentJson());
            }
            if (callback != null) callback.onTaskUpdate(run.getTaskState().toJson());
        });
    }

    private void unbindRun(AgentRun run) {
        currentRun.remove();
        run.getTaskState().setChangeListener(null);
        run.setRunning(false);
        lastCompletedRun = run;
        if (legacyActiveRun == run) legacyActiveRun = null;
    }

    public List<String> rollbackLastRun() throws IOException {
        AgentRun run = lastCompletedRun;
        if (run == null || !run.getChangeJournal().hasChanges()) return Collections.emptyList();
        List<String> restored = run.getChangeJournal().rollback();
        run.getTaskState().markRolledBack();
        if (sessionManager != null && !run.getTaskState().getSessionId().isEmpty()) {
            sessionManager.saveTaskState(run.getTaskState().getSessionId(), run.getTaskState().toPersistentJson());
        }
        return restored;
    }

    private int finalizeOrRequestVerification(AgentRun run) throws IOException {
        TaskState state = run.getTaskState();
        if (state.getPhase() == TaskState.Phase.BLOCKED) return 2;
        if (state.hasWrites() && !state.isVerifiedAfterLastWrite()) {
            if (!state.isVerificationReminderSent()) {
                state.markVerificationReminderSent();
                JsonObject reminder = new JsonObject();
                reminder.addProperty("role", "system");
                reminder.addProperty("content", "你已经修改了文件，但尚未完成成功验证。请运行相关测试、构建或静态检查；若确实无法验证，请使用 update_task 将任务标记为 BLOCKED 并说明原因。不要声称任务已经完成。");
                messages.add(reminder);
                persistMessage(reminder);
                messagesDirty = true;
                return 1;
            }
            state.block("文件已修改，但没有成功的修改后验证");
            return 2;
        }
        state.complete();
        return 0;
    }

    private static boolean noToolCalls(JsonElement toolCalls) {
        return toolCalls == null || toolCalls.isJsonNull() || toolCalls.getAsJsonArray().size() == 0;
    }

    // === TOOL CALL PROCESSING ===
    private void processToolCalls(JsonArray toolCalls) throws IOException {
        for (JsonElement tc : toolCalls) {
            if (isStopRequested()) return;
            AgentRun activeRun = currentRun.get();
            if (activeRun != null && activeRun.getTaskState().isTerminal()) return;
            JsonObject toolCall = tc.getAsJsonObject();
            String fnName = toolCall.getAsJsonObject("function").get("name").getAsString();
            String fnArgs = toolCall.getAsJsonObject("function").get("arguments").getAsString();
            String callId = toolCall.get("id").getAsString();

            if (RequestUserInputTool.NAME.equals(fnName)) {
                System.out.println("[等待用户补充] " + fnArgs);
                requestUserInputAndAddResult(fnArgs, callId, null);
                continue;
            }

            if (shouldConfirm(fnName, fnArgs)) {
                boolean approved = confirmAction(fnName, fnArgs);
                if (!approved) {
                    addRejectionMessage(callId);
                    continue;
                }
            }
            if (isStopRequested()) return;

            System.out.println("[调用工具] " + fnName + "(" + fnArgs + ")");
            executeAndAddToolResult(fnName, fnArgs, callId);
            if (activeRun != null && activeRun.getTaskState().isTerminal()) return;
        }
    }

    private void processToolCallsStreaming(JsonArray toolCalls, StreamCallback callback) throws IOException {
        for (JsonElement tc : toolCalls) {
            if (isStopRequested()) return;
            AgentRun activeRun = currentRun.get();
            if (activeRun != null && activeRun.getTaskState().isTerminal()) return;
            JsonObject toolCall = tc.getAsJsonObject();
            String fnName = toolCall.getAsJsonObject("function").get("name").getAsString();
            String fnArgs = toolCall.getAsJsonObject("function").get("arguments").getAsString();
            String callId = toolCall.get("id").getAsString();

            if (RequestUserInputTool.NAME.equals(fnName)) {
                callback.onToolCall(fnName, fnArgs, "waiting");
                ToolResult result = requestUserInputAndAddResult(fnArgs, callId, callback);
                callback.onToolResult(fnName, fnArgs, "done", result.getDetails());
                if (activeRun != null && activeRun.getTaskState().isTerminal()) return;
                continue;
            }

            if (shouldConfirm(fnName, fnArgs)) {
                boolean approved;
                AgentRun run = currentRun.get();
                if (run != null && run.isApproveAllRemaining()) {
                    approved = true;
                } else if (terminal == null && run != null) {
                    String confirmKey = run.createConfirmationRequest();
                    callback.onConfirmRequired(run.getRunId(), confirmKey, fnName, fnArgs);
                    approved = run.awaitConfirmation(confirmKey, 300, java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    callback.onToolCall(fnName, fnArgs, "confirming");
                    approved = confirmAction(fnName, fnArgs);
                }
                if (!approved) {
                    callback.onToolCall(fnName, fnArgs, "rejected");
                    addRejectionMessage(callId);
                    continue;
                }
            }
            if (isStopRequested()) return;

            callback.onToolCall(fnName, fnArgs, "running");
            System.out.println("\n[调用工具] " + fnName + "(" + fnArgs + ")");
            ToolResult result = executeAndAddToolResult(fnName, fnArgs, callId);
            callback.onToolResult(fnName, fnArgs, "done", result.getDetails());
            if ("run_command".equals(fnName) && result.getDetails().has("verification")
                    && result.getDetails().get("verification").getAsBoolean()) {
                callback.onVerification(result.getDetails());
            }
            if (activeRun != null && activeRun.getTaskState().isTerminal()) return;
        }
    }

    /**
     * 将模型生成的结构化问题转换成网页事件或终端提示，并把答案记录为工具结果，
     * 这样模型可以在同一次任务中带着用户的决定继续推理。
     */
    private ToolResult requestUserInputAndAddResult(String fnArgs, String callId,
                                                     StreamCallback callback) throws IOException {
        AgentRun run = currentRun.get();
        if (run != null) {
            run.getTaskState().markToolActivity(RequestUserInputTool.NAME);
            ToolResult previous = run.getExecutedResult(callId);
            if (previous != null) {
                JsonObject replayDetails = previous.getDetails().deepCopy();
                replayDetails.addProperty("replayed", true);
                return addToolResultMessage(callId,
                        new ToolResult(previous.getContent() + "\n(已复用之前的用户回答)", replayDetails));
            }
        }

        ToolResult prepared;
        try {
            JsonObject parsedArgs = GSON.fromJson(fnArgs, JsonObject.class);
            if (parsedArgs == null) parsedArgs = new JsonObject();
            prepared = toolMap.get(RequestUserInputTool.NAME).executeDetailed(parsedArgs);
        } catch (Exception e) {
            JsonObject details = new JsonObject();
            details.addProperty("error", "invalid_user_input_request");
            details.addProperty("message", e.getMessage());
            prepared = new ToolResult("澄清问题参数无效: " + e.getMessage(), details);
        }
        if (prepared.getDetails().has("error")) {
            if (run != null) run.recordExecutedResult(callId, prepared);
            return addToolResultMessage(callId, prepared);
        }

        JsonObject request = prepared.getDetails().deepCopy();
        String answer = null;
        if (terminal != null) {
            answer = promptForUserInput(request);
        } else if (callback != null && run != null) {
            String inputKey = run.createUserInputRequest();
            callback.onUserInputRequired(run.getRunId(), inputKey, request.deepCopy());
            answer = run.awaitUserInput(inputKey, USER_INPUT_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS);
        }

        ToolResult result;
        if (answer == null || answer.trim().isEmpty()) {
            JsonObject details = request.deepCopy();
            String status;
            String content;
            if (run != null && run.isStopRequested()) {
                status = "cancelled";
                content = "用户取消了当前任务，未回答澄清问题。";
            } else if (callback == null && terminal == null) {
                status = "unsupported";
                content = "当前请求方式不支持交互式澄清，请在回复中直接向用户提问。";
            } else {
                status = "timeout";
                content = "用户未在规定时间内回答澄清问题，请停止执行并说明仍需确认的内容。";
            }
            details.addProperty("status", status);
            result = new ToolResult(content, details);
        } else {
            String normalizedAnswer = answer.trim();
            JsonObject details = request.deepCopy();
            details.addProperty("status", "answered");
            details.addProperty("answer", normalizedAnswer);
            String selectedOption = findSelectedOption(request, normalizedAnswer);
            if (!selectedOption.isEmpty()) details.addProperty("selectedOption", selectedOption);
            String content = selectedOption.isEmpty()
                    ? "用户补充了自己的描述：" + normalizedAnswer + "\n请依据这段描述继续当前任务。"
                    : "用户选择了方案：" + normalizedAnswer + "\n请依据该选择继续当前任务。";
            result = new ToolResult(content, details);
        }
        if (run != null) run.recordExecutedResult(callId, result);
        return addToolResultMessage(callId, result);
    }

    private String promptForUserInput(JsonObject request) throws IOException {
        PrintWriter writer = terminal.writer();
        writer.println();
        writer.println("  需要你选择：" + request.get("question").getAsString());
        JsonArray options = request.getAsJsonArray("options");
        for (int i = 0; i < options.size(); i++) {
            JsonObject option = options.get(i).getAsJsonObject();
            writer.println("  [" + (i + 1) + "] " + option.get("label").getAsString()
                    + (option.get("recommended").getAsBoolean() ? "（推荐）" : ""));
            String description = option.get("description").getAsString();
            if (!description.isEmpty()) writer.println("      " + description);
        }
        writer.print("  输入序号或自己的描述：");
        writer.flush();
        String input = readInputLine();
        if (input == null) return null;
        String trimmed = input.trim();
        try {
            int selected = Integer.parseInt(trimmed);
            if (selected >= 1 && selected <= options.size()) {
                return options.get(selected - 1).getAsJsonObject().get("label").getAsString();
            }
        } catch (NumberFormatException ignored) {}
        return trimmed;
    }

    private static String findSelectedOption(JsonObject request, String answer) {
        JsonArray options = request.getAsJsonArray("options");
        for (JsonElement element : options) {
            JsonObject option = element.getAsJsonObject();
            if (option.get("label").getAsString().equals(answer)) {
                return option.get("id").getAsString();
            }
        }
        return "";
    }

    private boolean shouldConfirm(String fnName, String fnArgs) {
        if (currentMode != AgentMode.CONFIRM || !WRITE_TOOLS.contains(fnName)) {
            return false;
        }
        if ("run_command".equals(fnName)) {
            try {
                JsonObject args = GSON.fromJson(fnArgs, JsonObject.class);
                String command = args != null && args.has("command") ? args.get("command").getAsString() : "";
                return !RunCommandTool.isReadOnlyCommand(command);
            } catch (Exception ignored) {
                return true;
            }
        }
        return true;
    }

    private void addRejectionMessage(String callId) throws IOException {
        JsonObject toolMsg = new JsonObject();
        toolMsg.addProperty("role", "tool");
        toolMsg.addProperty("tool_call_id", callId);
        toolMsg.addProperty("content", "用户拒绝了此操作。请调整方案后重试，或说明原因。");
        messages.add(toolMsg);
        persistMessage(toolMsg);
        messagesDirty = true;
    }

    private ToolResult executeAndAddToolResult(String fnName, String fnArgs, String callId) throws IOException {
        AgentRun run = currentRun.get();
        if (run != null) {
            run.getTaskState().markToolActivity(fnName);
            ToolResult previous = run.getExecutedResult(callId);
            if (previous != null) {
                JsonObject replayDetails = previous.getDetails().deepCopy();
                replayDetails.addProperty("replayed", true);
                return addToolResultMessage(callId,
                        new ToolResult(previous.getContent() + "\n(相同 tool_call_id 已执行，已复用原结果)", replayDetails));
            }
        }
        Tool tool = toolMap.get(fnName);
        ToolResult result;
        if (tool == null) {
            JsonObject details = new JsonObject();
            details.addProperty("error", "unknown_tool");
            result = new ToolResult("未知工具: " + fnName, details);
        } else {
            try {
                JsonObject parsedArgs = GSON.fromJson(fnArgs, JsonObject.class);
                if (parsedArgs == null) parsedArgs = new JsonObject();
                result = tool.executeDetailed(parsedArgs);
            } catch (Exception e) {
                JsonObject details = new JsonObject();
                details.addProperty("error", "invalid_tool_arguments");
                details.addProperty("message", e.getMessage());
                result = new ToolResult("工具参数解析失败: " + e.getMessage() + "\n请重新生成合法 JSON 参数后再调用此工具。", details);
            }
        }
        if (run != null) {
            boolean failed = result.getDetails().has("error")
                    || (result.getDetails().has("exitCode") && result.getDetails().get("exitCode").getAsInt() != 0)
                    || (result.getDetails().has("timedOut") && result.getDetails().get("timedOut").getAsBoolean());
            if (failed) {
                String error = result.getDetails().has("error")
                        ? result.getDetails().get("error").getAsString() : result.getContent();
                int repeated = run.recordFailure(fnName, fnArgs, error);
                if (repeated >= 3) {
                    JsonObject details = result.getDetails().deepCopy();
                    details.addProperty("repeatedFailure", repeated);
                    details.addProperty("blocked", true);
                    result = new ToolResult(result.getContent()
                            + "\n同一工具调用已连续失败 " + repeated + " 次，禁止原样重试；请调整参数或向用户说明阻塞原因。", details);
                    run.getTaskState().block("同一工具调用连续失败 " + repeated + " 次: " + fnName);
                }
            } else {
                run.clearFailureStreak();
            }
            run.recordExecutedResult(callId, result);
        }
        return addToolResultMessage(callId, result);
    }

    private ToolResult addToolResultMessage(String callId, ToolResult result) throws IOException {
        JsonObject toolMsg = new JsonObject();
        toolMsg.addProperty("role", "tool");
        toolMsg.addProperty("tool_call_id", callId);
        toolMsg.addProperty("content", result.getContent());
        messages.add(toolMsg);
        persistMessage(toolMsg);
        messagesDirty = true;
        return result;
    }

    int estimateTokens() {
        return countContextChars() / 3;
    }

    public int getContextCharCount() {
        return countContextChars();
    }

    private int countContextChars() {
        int total = 0;
        for (JsonObject msg : messages) {
            if (msg.has("content") && !msg.get("content").isJsonNull()) {
                total += msg.get("content").getAsString().length();
            }
            if (msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull()) {
                total += msg.get("tool_calls").toString().length();
            }
        }
        return total;
    }

    private void loadHistory() {
        if (!java.nio.file.Files.exists(HISTORY_FILE)) return;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(java.nio.file.Files.newInputStream(HISTORY_FILE), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty()) inputHistory.add(line);
            }
        } catch (IOException ignored) {}
    }

    private void saveHistory() {
        try {
            FileSecurity.ensurePrivateDirectory(HISTORY_FILE.getParent());
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, inputHistory.size() - MAX_HISTORY);
            for (int i = start; i < inputHistory.size(); i++) {
                sb.append(inputHistory.get(i)).append('\n');
            }
            java.nio.file.Files.write(HISTORY_FILE,
                    sb.toString().getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            FileSecurity.restrictFile(HISTORY_FILE);
        } catch (IOException ignored) {}
    }

    private JsonArray getMessagesJson() {
        if (messagesDirty || cachedMessagesJson == null) {
            cachedMessagesJson = new JsonArray();
            for (JsonObject msg : messages) {
                cachedMessagesJson.add(msg);
            }
            messagesDirty = false;
        }
        return cachedMessagesJson;
    }

    private JsonObject getToolsJson() {
        JsonArray toolsJson = new JsonArray();
        for (Tool t : tools) {
            toolsJson.add(t.toJson());
        }
        JsonObject body = new JsonObject();
        String overrideModel = getOverrideModel();
        String effectiveModel = (overrideModel != null) ? overrideModel : model;
        body.addProperty("model", effectiveModel);
        body.addProperty("temperature", AGENT_TEMPERATURE);
        String effectiveReasoningEffort = normalizeReasoningEffort(getOverrideReasoningEffort());
        if (!effectiveReasoningEffort.isEmpty()) {
            // Omit the field for default so OpenAI-compatible providers that lack it still work.
            body.addProperty("reasoning_effort", effectiveReasoningEffort);
        }
        body.add("messages", getMessagesJson());
        body.add("tools", toolsJson);
        return body;
    }

    private JsonObject callApiWithRetry(boolean stream) throws IOException {
        int retries = ToolConstants.API_MAX_RETRIES;
        IOException lastException = null;
        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                return callApi(stream);
            } catch (IOException e) {
                lastException = e;
                if (!(e instanceof RetryableApiException)) throw e;
                if (attempt < retries - 1) {
                    try {
                        Thread.sleep((long) (ToolConstants.API_RETRY_BASE_DELAY_MS * Math.pow(2, attempt)));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw lastException;
    }

    private JsonObject callApi(boolean stream) throws IOException {
        trimContextIfNeeded();
        JsonObject body = getToolsJson();
        if (stream) {
            body.addProperty("stream", true);
        }

        String jsonBody = GSON.toJson(body);

        String[] connOverride = getOverrideConn();
        String effectiveApiUrl = (connOverride != null && connOverride[0] != null) ? connOverride[0] : apiUrl;
        String effectiveApiKey = (connOverride != null && connOverride[1] != null) ? connOverride[1] : apiKey;

        URL url = new URL(effectiveApiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        registerActiveConnection(conn);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + effectiveApiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(ToolConstants.API_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(ToolConstants.API_READ_TIMEOUT_MS);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int statusCode = conn.getResponseCode();
        InputStream inputStream = (statusCode >= 200 && statusCode < 300)
                ? conn.getInputStream() : conn.getErrorStream();

        String responseBody;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            responseBody = sb.toString();
        }

        if (statusCode != 200) {
            clearActiveConnection(conn);
            String message = "API error [" + statusCode + "]: " + truncateError(responseBody);
            if (statusCode == 429 || statusCode >= 500) throw new RetryableApiException(message);
            throw new IOException(message);
        }

        try {
            JsonObject response = GSON.fromJson(responseBody.trim(), JsonObject.class);
            if (response != null && response.has("choices") && response.get("choices").isJsonArray()
                    && response.getAsJsonArray("choices").size() > 0) {
                JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                    String reason = choice.get("finish_reason").getAsString();
                    if ("length".equals(reason) || "content_filter".equals(reason)) {
                        throw new IOException("模型响应未正常完成: finish_reason=" + reason);
                    }
                }
                if (choice.has("message") && choice.get("message").isJsonObject()) {
                    return choice.getAsJsonObject("message");
                }
            }
            if (response != null && response.has("role")) {
                return response;
            }
            throw new IOException("API response missing choices[0].message");
        } finally {
            clearActiveConnection(conn);
        }
    }

    private JsonObject callApiStreamingWithRetry(StreamCallback callback) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < ToolConstants.API_MAX_RETRIES; attempt++) {
            try {
                return callApiStreaming(callback);
            } catch (StreamInterruptedException e) {
                if (e.outputStarted || attempt >= ToolConstants.API_MAX_RETRIES - 1) throw e;
                last = e;
            } catch (IOException e) {
                last = e;
                if (!(e instanceof RetryableApiException) || attempt >= ToolConstants.API_MAX_RETRIES - 1) throw e;
            }
            try {
                Thread.sleep((long) (ToolConstants.API_RETRY_BASE_DELAY_MS * Math.pow(2, attempt)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw last;
            }
        }
        throw last;
    }

    private JsonObject callApiStreaming(StreamCallback callback) throws IOException {
        trimContextIfNeeded();
        JsonObject body = getToolsJson();
        body.addProperty("stream", true);

        String jsonBody = GSON.toJson(body);

        String[] connOverride = getOverrideConn();
        String effectiveApiUrl = (connOverride != null && connOverride[0] != null) ? connOverride[0] : apiUrl;
        String effectiveApiKey = (connOverride != null && connOverride[1] != null) ? connOverride[1] : apiKey;

        URL url = new URL(effectiveApiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        registerActiveConnection(conn);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + effectiveApiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(ToolConstants.API_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(ToolConstants.API_READ_TIMEOUT_MS);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int statusCode = conn.getResponseCode();
        InputStream inputStream = (statusCode >= 200 && statusCode < 300)
                ? conn.getInputStream() : conn.getErrorStream();

        if (statusCode != 200) {
            String errorBody;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                errorBody = sb.toString();
            }
            clearActiveConnection(conn);
            String message = "API error [" + statusCode + "]: " + truncateError(errorBody);
            if (statusCode == 429 || statusCode >= 500) throw new RetryableApiException(message);
            throw new IOException(message);
        }

        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        Map<Integer, JsonObject> toolCallsMap = new LinkedHashMap<>();
        boolean sawDone = false;
        String finishReason = null;
        int malformedChunks = 0;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int emptyLineCount = 0;
            while ((line = br.readLine()) != null) {
                if (isStopRequested()) {
                    break;
                }
                if (line.isEmpty()) {
                    emptyLineCount++;
                    if (emptyLineCount > 10) break;
                    continue;
                }
                emptyLineCount = 0;
                if (!line.startsWith("data:")) continue;

                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    sawDone = true;
                    break;
                }

                JsonObject chunk;
                try {
                    chunk = GSON.fromJson(data, JsonObject.class);
                } catch (Exception e) {
                    malformedChunks++;
                    continue;
                }

                if (!chunk.has("choices") || !chunk.get("choices").isJsonArray()) continue;
                JsonArray choices = chunk.getAsJsonArray("choices");
                if (choices.size() == 0 || !choices.get(0).isJsonObject()) continue;
                JsonObject choice = choices.get(0).getAsJsonObject();

                if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                    finishReason = choice.get("finish_reason").getAsString();
                }

                JsonObject delta = choice.getAsJsonObject("delta");
                if (delta == null) continue;

                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                    String token = delta.get("content").getAsString();
                    fullContent.append(token);
                    callback.onToken(token);
                }

                if (delta.has("thinking") && !delta.get("thinking").isJsonNull()) {
                    String thinkToken = delta.get("thinking").getAsString();
                    fullThinking.append(thinkToken);
                    callback.onThinking(thinkToken);
                }

                if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                    JsonArray tcArray = delta.getAsJsonArray("tool_calls");
                    accumulateToolCallsDelta(toolCallsMap, tcArray);
                }
            }
        } catch (IOException e) {
            if (!isStopRequested()) {
                boolean started = fullContent.length() > 0 || fullThinking.length() > 0 || !toolCallsMap.isEmpty();
                throw new StreamInterruptedException("模型流中断: " + e.getMessage(), started, e);
            }
        } finally {
            clearActiveConnection(conn);
        }

        if (!isStopRequested()) {
            boolean started = fullContent.length() > 0 || fullThinking.length() > 0 || !toolCallsMap.isEmpty();
            if ("length".equals(finishReason)) {
                throw new StreamInterruptedException("模型响应因长度限制被截断", started, null);
            }
            if (finishReason != null && !"stop".equals(finishReason) && !"tool_calls".equals(finishReason)) {
                throw new StreamInterruptedException("模型响应未正常完成: finish_reason=" + finishReason, started, null);
            }
            if (malformedChunks > 0) {
                throw new StreamInterruptedException("模型流包含 " + malformedChunks + " 个损坏的数据块", started, null);
            }
            if (!sawDone && finishReason == null) {
                throw new StreamInterruptedException("模型流未收到正常结束标记", started, null);
            }
        }

        if (fullContent.length() == 0 && fullThinking.length() > 0) {
            fullContent.append(fullThinking);
        }
        JsonObject message = buildAssistantMessage(fullContent, toolCallsMap);
        messagesDirty = true;
        return message;
    }

    private void registerActiveConnection(HttpURLConnection connection) {
        AgentRun run = currentRun.get();
        if (run != null) run.registerConnection(connection);
        else activeConnection = connection;
    }

    private void clearActiveConnection(HttpURLConnection connection) {
        AgentRun run = currentRun.get();
        if (run != null) run.clearConnection(connection);
        else if (activeConnection == connection) activeConnection = null;
    }

    private static final class StreamInterruptedException extends IOException {
        final boolean outputStarted;

        StreamInterruptedException(String message, boolean outputStarted, Throwable cause) {
            super(message, cause);
            this.outputStarted = outputStarted;
        }
    }

    private static final class RetryableApiException extends IOException {
        RetryableApiException(String message) { super(message); }
    }

    private void accumulateToolCallsDelta(Map<Integer, JsonObject> toolCallsMap, JsonArray tcArray) {
        for (JsonElement tcEl : tcArray) {
            JsonObject tcObj = tcEl.getAsJsonObject();
            int idx = tcObj.has("index") ? tcObj.get("index").getAsInt() : 0;

            if (!toolCallsMap.containsKey(idx)) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", "");
                entry.addProperty("type", "function");
                JsonObject fn = new JsonObject();
                fn.addProperty("name", "");
                fn.addProperty("arguments", "");
                entry.add("function", fn);
                toolCallsMap.put(idx, entry);
            }

            JsonObject entry = toolCallsMap.get(idx);
            if (tcObj.has("id") && !tcObj.get("id").isJsonNull()) {
                entry.addProperty("id", tcObj.get("id").getAsString());
            }
            if (tcObj.has("function")) {
                JsonObject fnDelta = tcObj.getAsJsonObject("function");
                JsonObject fnEntry = entry.getAsJsonObject("function");
                if (fnDelta.has("name") && !fnDelta.get("name").isJsonNull()) {
                    fnEntry.addProperty("name",
                            fnEntry.get("name").getAsString() + fnDelta.get("name").getAsString());
                }
                if (fnDelta.has("arguments") && !fnDelta.get("arguments").isJsonNull()) {
                    fnEntry.addProperty("arguments",
                            fnEntry.get("arguments").getAsString() + fnDelta.get("arguments").getAsString());
                }
            }
        }
    }

    private JsonObject buildAssistantMessage(StringBuilder fullContent, Map<Integer, JsonObject> toolCallsMap) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        if (fullContent.length() > 0) {
            message.addProperty("content", fullContent.toString());
        } else {
            message.add("content", JsonNull.INSTANCE);
        }

        if (!toolCallsMap.isEmpty()) {
            JsonArray tcArray = new JsonArray();
            for (JsonObject toolCall : toolCallsMap.values()) {
                tcArray.add(toolCall);
            }
            message.add("tool_calls", tcArray);
        }
        return message;
    }

    private void trimContextIfNeeded() {
        int totalChars = countContextChars();
        if (totalChars <= ToolConstants.CONTEXT_SOFT_LIMIT_CHARS) return;

        int keepFrom = 1;
        int cumulative = 0;
        for (int i = messages.size() - 1; i >= 1; i--) {
            JsonObject msg = messages.get(i);
            int msgLen = 0;
            if (msg.has("content") && !msg.get("content").isJsonNull()) {
                msgLen += msg.get("content").getAsString().length();
            }
            if (msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull()) {
                msgLen += msg.get("tool_calls").toString().length();
            }
            cumulative += msgLen;
            if (cumulative > ToolConstants.CONTEXT_HARD_LIMIT_CHARS / 2) {
                keepFrom = i;
                break;
            }
        }

        if (keepFrom > 1) {
            // Tool calls and their results must stay in the same request. Move the
            // cut point back to the beginning of the current user turn.
            while (keepFrom > 1) {
                JsonObject candidate = messages.get(keepFrom);
                if (candidate.has("role") && "user".equals(candidate.get("role").getAsString())) {
                    break;
                }
                keepFrom--;
            }
            if (keepFrom <= 1) {
                return;
            }
            JsonObject systemMsg = null;
            if (!messages.isEmpty() && "system".equals(messages.get(0).get("role").getAsString())) {
                systemMsg = messages.get(0);
            }
            List<JsonObject> trimmed = new ArrayList<>();
            if (systemMsg != null) {
                trimmed.add(systemMsg);
            }
            JsonObject truncationNote = new JsonObject();
            truncationNote.addProperty("role", "system");
            truncationNote.addProperty("content", buildContextSummary(1, keepFrom));
            trimmed.add(truncationNote);
            for (int i = keepFrom; i < messages.size(); i++) {
                trimmed.add(messages.get(i));
            }
            messages.clear();
            messages.addAll(trimmed);
            messagesDirty = true;
        }
    }

    private String buildContextSummary(int fromInclusive, int toExclusive) {
        int omitted = Math.max(0, toExclusive - fromInclusive);
        StringBuilder sb = new StringBuilder();
        sb.append("上下文已压缩：为控制请求长度，省略了较早的 ")
                .append(omitted).append(" 条消息。");
        AgentRun run = currentRun.get();
        if (run != null) sb.append("\n").append(run.getTaskState().buildCheckpoint());
        sb.append("\n旧对话原文不再可靠；继续前请根据检查点重新读取相关文件并核对当前工作区。");
        return sb.toString();
    }

    private boolean confirmAction(String fnName, String fnArgs) throws IOException {
        ActionConfirmer confirmer = actionConfirmer;
        if (confirmer != null) {
            return confirmer.confirm(fnName, fnArgs);
        }
        if (terminal == null) {
            return false;
        }
        if (isApproveAllRemaining()) {
            return true;
        }
        PrintWriter w = terminal.writer();
        String reset = "\u001B[0m";
        String yellow = "\u001B[33m";
        w.print("\r\n  " + yellow + "\u26A0 确认执行 " + fnName + "(" + fnArgs + ") [y/N/a=全部批准] " + reset);
        w.flush();

        while (true) {
            int c = terminalReader.read();
            if (c == 'y' || c == 'Y') {
                w.println("y");
                w.flush();
                return true;
            }
            if (c == 'a' || c == 'A') {
                setApproveAllRemaining(true);
                w.println("a (全部批准)");
                w.flush();
                return true;
            }
            if (c == '\r' || c == '\n') {
                w.println("N");
                w.flush();
                return false;
            }
            if (c == 'n' || c == 'N' || c == 27) {
                w.println((char) c);
                w.flush();
                return false;
            }
        }
    }

    private static String truncateError(String errorBody) {
        if (errorBody.length() > 500) {
            return errorBody.substring(0, 500) + "...";
        }
        return errorBody;
    }

    private static String buildPrompt(AgentMode mode) {
        return "\r" + mode.color() + mode.label() + " \u001B[0m\u25B8 ";
    }

    private static String modeStatusBar(AgentMode mode) {
        String reset = "\u001B[0m";
        StringBuilder sb = new StringBuilder();
        for (AgentMode m : AgentMode.values()) {
            if (m == mode) {
                sb.append(m.color()).append("[").append(m.label().toUpperCase()).append("]").append(reset);
            } else {
                sb.append(" ").append(m.label()).append(" ");
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        String apiUrl = ConfigLoader.getConfigString("cur_api_url", "DEEPSEEK_API_URL",
                "https://api.deepseek.com/chat/completions");
        String apiKey = ConfigLoader.getConfigString("cur_api_key", "DEEPSEEK_API_KEY", "");
        String model = ConfigLoader.getConfigString("cur_model", "DEEPSEEK_MODEL", "deepseek-chat");
        String reasoningEffort = ConfigLoader.getConfigString("cur_reasoning_effort", "FISH_REASONING_EFFORT", "");
        String workspaceDir = args.length > 0
                ? args[0]
                : ConfigLoader.getConfigString("workspace_dir", "FISH_CODE_WORKDIR", System.getProperty("user.dir"));

        if (apiKey.isEmpty()) {
            System.out.println("请配置 api_key (环境变量 DEEPSEEK_API_KEY 或 ~/.fish-code/config.json)");
            return;
        }

        SessionManager sessionManager = new SessionManager();
        TerminalStart agent = new TerminalStart(apiUrl, apiKey, model, sessionManager);
        agent.setReasoningEffort(reasoningEffort);
        agent.setMode(AgentMode.CONFIRM);
        try {
            agent.setWorkspaceDir(workspaceDir);
        } catch (IOException e) {
            System.out.println("工作目录配置无效，继续使用当前目录: " + System.getProperty("user.dir"));
            System.out.println("原因: " + e.getMessage());
        }

        printBanner(model);
        System.out.println("  " + modeStatusBar(AgentMode.CONFIRM)
                + "  Shift+Tab 切换模式 | /model 选择模型 | /help 帮助 | exit 退出\n");

        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder()
                    .jansi(true)
                    .system(true)
                    .build();
            terminal.enterRawMode();
            agent.setTerminal(terminal);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                stopRequested = true;
            }));

            while (true) {
                AgentMode mode = agent.getMode();
                String prompt = buildPrompt(mode);
                terminal.writer().print(prompt);
                terminal.writer().flush();

                String line = agent.readInputLine();

                if (line == null) break;

                String input = line.trim();

                if (input.isEmpty()) continue;
                if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                    terminal.writer().println("再见！");
                    terminal.writer().flush();
                    break;
                }
                if ("/help".equalsIgnoreCase(input) || "/h".equalsIgnoreCase(input)) {
                    printHelp();
                    continue;
                }

                AgentMode newMode = parseModeCommand(input, agent.getMode());
                if (newMode != null) {
                    if (newMode != agent.getMode()) {
                        agent.setMode(newMode);
                    }
                    System.out.println("  " + modeStatusBar(newMode));
                    continue;
                }

                if ("/new".equalsIgnoreCase(input)) {
                    agent.newConversation();
                    System.out.println("  \u001B[36m已创建新会话\u001B[0m");
                    continue;
                }
                if ("/clear".equalsIgnoreCase(input)) {
                    agent.clearConversation();
                    System.out.println("  \u001B[36m已清空当前会话上下文\u001B[0m");
                    continue;
                }
                if ("/history".equalsIgnoreCase(input)) {
                    printSessionHistory(sessionManager);
                    continue;
                }
                if ("/model".equalsIgnoreCase(input) || input.toLowerCase(Locale.ROOT).startsWith("/model ")) {
                    handleModelCommand(agent, input);
                    continue;
                }
                if (input.startsWith("/cwd")) {
                    handleWorkspaceCommand(agent, input);
                    continue;
                }
                if (input.startsWith("/status")) {
                    int msgCount = agent.messages.size();
                    int chars = agent.estimateTokens() * 3;
                    int tokens = agent.estimateTokens();
                    String sid = agent.getCurrentSessionId() != null ? agent.getCurrentSessionId() : "(无)";
                    System.out.println("  \u001B[36m会话: " + sid);
                    System.out.println("  工作目录: " + agent.getWorkspaceDir());
                    System.out.println("  消息数: " + msgCount + "  |  上下文: " + chars + " 字符 (~" + tokens + " tokens)");
                    System.out.println("  模式: " + agent.getMode().label() + "  |  模型: " + agent.getModel() + "\u001B[0m");
                    continue;
                }
                if (input.startsWith("/delete")) {
                    String arg = input.length() > 7 ? input.substring(7).trim() : "";
                    if (arg.isEmpty()) {
                        System.out.println("  \u001B[33m用法: /delete <会话序号或ID>\u001B[0m");
                    } else {
                        String targetId = arg;
                        try {
                            int idx = Integer.parseInt(arg);
                            targetId = sessionManager.getSessionIdByIndex(idx);
                        } catch (NumberFormatException ignored) {}
                        if (targetId != null) {
                            sessionManager.deleteSession(targetId);
                            if (targetId.equals(agent.getCurrentSessionId())) {
                                agent.newConversation();
                            }
                            System.out.println("  \u001B[36m已删除会话 " + targetId + "\u001B[0m");
                        } else {
                            System.out.println("  \u001B[33m未找到会话: " + arg + "\u001B[0m");
                        }
                    }
                    continue;
                }
                if (input.startsWith("/resume")) {
                    handleResume(agent, sessionManager, input);
                    continue;
                }
                if ("/undo".equalsIgnoreCase(input)) {
                    handleUndo(agent);
                    continue;
                }

                try {
                    final boolean[] firstToken = {true};
                    StreamCallback cliCallback = new StreamCallback() {
                        @Override public void onToken(String token) {
                            if (firstToken[0]) {
                                firstToken[0] = false;
                                System.out.print("\r\u001B[2K");
                            }
                            System.out.print(token);
                            System.out.flush();
                        }
                        @Override public void onThinking(String text) {}
                        @Override public void onToolCall(String fnName, String fnArgs, String status) {}
                        @Override public void onConfirmRequired(String confirmKey, String fnName, String fnArgs) {}
                        @Override public void onComplete(ChatResult result) {}
                        @Override public void onError(String error) {
                            System.out.print(error);
                            System.out.flush();
                        }
                    };
                    System.out.print("\u001B[90m  \u23F3 思考中...\u001B[0m");
                    System.out.flush();
                    approveAllRemaining = false;
                    ChatResult result = agent.chatStream(input, cliCallback);
                    System.out.println();
                    System.out.println("\u001B[90m  \u23F1 " + result.getDurationMs() + "ms | 上下文 " + result.getContextTokens() + " 字符\u001B[0m");
                } catch (Exception e) {
                    System.err.println("错误: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("终端初始化失败: " + e.getMessage());
        } finally {
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private String readInputLine() throws IOException {
        StringBuilder line = new StringBuilder();
        historyIndex = -1;
        String savedLine = "";

        while (true) {
            int c = terminalReader.read();
            if (c == -1) return null;

            if (c == 3) {
                terminal.writer().println();
                terminal.writer().println("再见！");
                terminal.writer().flush();
                return null;
            }

            if (c == '\r' || c == '\n') {
                terminal.writer().println();
                terminal.writer().flush();
                String result = line.toString();
                if (!result.trim().isEmpty()) {
                    inputHistory.remove(result);
                    inputHistory.add(result);
                    saveHistory();
                }
                return result;
            }

            if (c == '\t') {
                // 普通 Tab 不承担模式切换，也不向输入中插入不可见字符。
                continue;
            }

            if (c == 27) {
                String sequence = readEscapeSequence();
                if (isBackwardTabSequence(sequence, terminalBackwardTabSequence)) {
                    switchInputMode(currentMode.next(), line);
                    continue;
                }
                if (isArrowSequence(sequence, 'A')) {
                    if (historyIndex == -1) {
                        savedLine = line.toString();
                        historyIndex = inputHistory.size();
                    }
                    if (historyIndex > 0) {
                        historyIndex--;
                        replaceLine(line, inputHistory.get(historyIndex));
                    }
                    continue;
                }
                if (isArrowSequence(sequence, 'B')) {
                    if (historyIndex >= 0 && historyIndex < inputHistory.size() - 1) {
                        historyIndex++;
                        replaceLine(line, inputHistory.get(historyIndex));
                    } else if (historyIndex == inputHistory.size() - 1) {
                        historyIndex = -1;
                        replaceLine(line, savedLine);
                    }
                    continue;
                }
                continue;
            }

            if (c == 8 || c == 127) {
                if (line.length() > 0) {
                    int start = Character.offsetByCodePoints(line, line.length(), -1);
                    line.delete(start, line.length());
                    redrawInputLine(line);
                }
                continue;
            }

            if (c >= 32) {
                line.append((char) c);
                terminal.writer().write(c);
                terminal.writer().flush();
            }
        }
    }

    private String readEscapeSequence() throws IOException {
        int first = terminalReader.read(ESCAPE_SEQUENCE_TIMEOUT_MS);
        if (first < 0) return "";

        StringBuilder sequence = new StringBuilder();
        sequence.append((char) first);
        if (first != '[' && first != 'O') return sequence.toString();

        while (sequence.length() < 32) {
            int next = terminalReader.read(ESCAPE_SEQUENCE_TIMEOUT_MS);
            if (next < 0) break;
            sequence.append((char) next);
            if (next >= 0x40 && next <= 0x7e) break;
        }
        return sequence.toString();
    }

    static boolean isBackwardTabSequence(String sequence) {
        return isBackwardTabSequence(sequence, null);
    }

    static boolean isBackwardTabSequence(String sequence, String terminalSequence) {
        if (sequence == null || sequence.isEmpty()) return false;
        String normalizedTerminalSequence = normalizeEscapeSequence(terminalSequence);
        if (!normalizedTerminalSequence.isEmpty() && sequence.equals(normalizedTerminalSequence)) {
            return true;
        }
        if ((sequence.charAt(0) == '[' || sequence.charAt(0) == 'O') && sequence.endsWith("Z")) {
            return true;
        }
        // ESC+Tab, xterm modifyOtherKeys and kitty keyboard protocol variants.
        return "\t".equals(sequence) || "[27;2;9~".equals(sequence)
                || "[9;2u".equals(sequence) || "[9;2~".equals(sequence);
    }

    private static String normalizeEscapeSequence(String sequence) {
        if (sequence == null || sequence.isEmpty()) return "";
        return sequence.charAt(0) == 27 ? sequence.substring(1) : sequence;
    }

    private static boolean isArrowSequence(String sequence, char direction) {
        return sequence != null && sequence.length() >= 2
                && (sequence.charAt(0) == '[' || sequence.charAt(0) == 'O')
                && sequence.charAt(sequence.length() - 1) == direction;
    }

    private void switchInputMode(AgentMode newMode, StringBuilder line) {
        setMode(newMode);
        redrawInputLine(line);
    }

    private void redrawInputLine(CharSequence line) {
        terminal.writer().print("\r\u001B[2K" + buildPrompt(currentMode) + line);
        terminal.writer().flush();
    }

    private void replaceLine(StringBuilder line, String replacement) {
        line.setLength(0);
        line.append(replacement);
        redrawInputLine(line);
    }

    private static void handleResume(TerminalStart agent, SessionManager sessionManager, String input) {
        String arg = input.length() > 7 ? input.substring(7).trim() : "";
        if (arg.isEmpty()) {
            String lastId = sessionManager.getLastSessionId();
            if (lastId != null && agent.loadConversation(lastId)) {
                System.out.println("  \u001B[36m已恢复上一个会话 (" + lastId + ")\u001B[0m");
            } else {
                System.out.println("  \u001B[33m没有可恢复的会话\u001B[0m");
            }
        } else {
            String targetId = arg;
            try {
                int idx = Integer.parseInt(arg);
                targetId = sessionManager.getSessionIdByIndex(idx);
            } catch (NumberFormatException ignored) {}
            if (targetId != null && agent.loadConversation(targetId)) {
                System.out.println("  \u001B[36m已恢复会话 (" + targetId + ")\u001B[0m");
            } else {
                System.out.println("  \u001B[33m未找到会话: " + arg + "\u001B[0m");
            }
        }
    }

    private static void handleWorkspaceCommand(TerminalStart agent, String input) {
        String arg = input.length() > 4 ? input.substring(4).trim() : "";
        if (arg.isEmpty()) {
            System.out.println("  \u001B[36m当前工作目录: " + agent.getWorkspaceDir() + "\u001B[0m");
            System.out.println("  \u001B[90m用法: /cwd <目录路径>\u001B[0m");
            return;
        }
        try {
            String cwd = agent.setWorkspaceDir(arg);
            agent.newConversation();
            System.out.println("  \u001B[36m已切换工作目录: " + cwd + "\u001B[0m");
            System.out.println("  \u001B[90m已为新目录创建空白会话上下文\u001B[0m");
        } catch (IOException e) {
            System.out.println("  \u001B[31m切换失败: " + e.getMessage() + "\u001B[0m");
        }
    }

    private static void handleModelCommand(TerminalStart agent, String input) {
        JsonObject config = ConfigLoader.loadConfig();
        List<JsonObject> models = configuredModels(config);
        if (models.isEmpty()) {
            System.out.println("  \u001B[33m没有已配置的模型，请先在 ~/.fish-code/config.json 的 models 中添加模型。\u001B[0m");
            return;
        }

        String selection = input.length() > 6 ? input.substring(6).trim() : "";
        if (selection.isEmpty()) {
            printModelChoices(models, agent.getModel());
            selection = agent.readModelSelection(models, agent.getModel());
            if (selection == null) {
                System.out.println("  \u001B[90m已取消模型切换\u001B[0m");
                return;
            }
        }

        JsonObject selected = resolveConfiguredModel(config, selection);
        if (selected == null) {
            System.out.println("  \u001B[33m未找到模型: " + selection + "\u001B[0m");
            System.out.println("  \u001B[90m可使用序号、模型名称或模型标识，例如 /model 2\u001B[0m");
            return;
        }

        String value = configString(selected, "value");
        try {
            JsonObject updated = ModelConfigManager.selectModel(config, value);
            String selectedApiUrl = configString(updated, "cur_api_url");
            if (configString(updated, "cur_api_key").isEmpty()
                    && !agent.getApiKey().isEmpty()
                    && selectedApiUrl.equals(agent.getApiUrl())) {
                // Models on the same endpoint commonly share one top-level API key.
                updated.addProperty("cur_api_key", agent.getApiKey());
            }
            ConfigLoader.saveConfig(updated);
            agent.setModel(configString(updated, "cur_model"));
            agent.setApiUrl(configString(updated, "cur_api_url"));
            agent.setApiKey(configString(updated, "cur_api_key"));
            agent.setReasoningEffort(configString(updated, "cur_reasoning_effort"));

            String label = configString(selected, "label");
            System.out.println("  \u001B[36m已切换模型: " + label + " (" + value + ")\u001B[0m");
            if (agent.getApiKey().isEmpty()) {
                System.out.println("  \u001B[33m提示: 该模型尚未配置 API Key。\u001B[0m");
            }
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("  \u001B[31m模型切换失败: " + e.getMessage() + "\u001B[0m");
        }
    }

    private String readModelSelection(List<JsonObject> models, String currentModel) {
        int selectedIndex = 0;
        for (int i = 0; i < models.size(); i++) {
            if (Objects.equals(currentModel, configString(models.get(i), "value"))) {
                selectedIndex = i;
                break;
            }
        }

        StringBuilder typed = new StringBuilder();
        redrawModelSelection(selectedIndex, models.size(), typed);
        try {
            while (true) {
                int c = terminalReader.read();
                if (c == -1 || c == 3) {
                    terminal.writer().println();
                    terminal.writer().flush();
                    return null;
                }
                if (c == '\r' || c == '\n') {
                    terminal.writer().println();
                    terminal.writer().flush();
                    if (typed.length() == 0) return String.valueOf(selectedIndex + 1);
                    try {
                        int choice = Integer.parseInt(typed.toString());
                        if (choice >= 1 && choice <= models.size()) return typed.toString();
                    } catch (NumberFormatException ignored) {}
                    System.out.println("  \u001B[33m请输入 1 - " + models.size() + " 之间的序号。\u001B[0m");
                    typed.setLength(0);
                    redrawModelSelection(selectedIndex, models.size(), typed);
                    continue;
                }
                if (c == 27) {
                    String sequence = readEscapeSequence();
                    if (sequence.isEmpty()) {
                        terminal.writer().println();
                        terminal.writer().flush();
                        return null;
                    }
                    if (isArrowSequence(sequence, 'A')) {
                        selectedIndex = (selectedIndex - 1 + models.size()) % models.size();
                        typed.setLength(0);
                        redrawModelSelection(selectedIndex, models.size(), typed);
                    } else if (isArrowSequence(sequence, 'B')) {
                        selectedIndex = (selectedIndex + 1) % models.size();
                        typed.setLength(0);
                        redrawModelSelection(selectedIndex, models.size(), typed);
                    }
                    continue;
                }
                if (c == 8 || c == 127) {
                    if (typed.length() > 0) typed.deleteCharAt(typed.length() - 1);
                    redrawModelSelection(selectedIndex, models.size(), typed);
                    continue;
                }
                if (c >= '0' && c <= '9') {
                    typed.append((char) c);
                    redrawModelSelection(selectedIndex, models.size(), typed);
                }
            }
        } catch (IOException e) {
            terminal.writer().println();
            terminal.writer().flush();
            System.out.println("  \u001B[31m读取模型选择失败: " + e.getMessage() + "\u001B[0m");
            return null;
        }
    }

    private void redrawModelSelection(int selectedIndex, int count, CharSequence typed) {
        String value = typed.length() == 0 ? String.valueOf(selectedIndex + 1) : typed.toString();
        terminal.writer().print("\r\u001B[2K  选择模型 [1-" + count + "]（↑/↓ 调整，Enter 确认，Esc 取消）: " + value);
        terminal.writer().flush();
    }

    private static void printModelChoices(List<JsonObject> models, String currentModel) {
        String reset = "\u001B[0m";
        String cyan = "\u001B[36m";
        String gray = "\u001B[90m";
        System.out.println();
        System.out.println("  可用模型:");
        for (int i = 0; i < models.size(); i++) {
            JsonObject model = models.get(i);
            String value = configString(model, "value");
            String marker = Objects.equals(value, currentModel) ? cyan + "●" + reset : "○";
            System.out.println("    " + marker + " " + (i + 1) + ". " + configString(model, "label")
                    + "  " + gray + value + reset);
        }
        System.out.println();
    }

    static JsonObject resolveConfiguredModel(JsonObject config, String selection) {
        List<JsonObject> models = configuredModels(config);
        String query = selection == null ? "" : selection.trim();
        if (query.isEmpty()) return null;

        try {
            int index = Integer.parseInt(query);
            if (index >= 1 && index <= models.size()) return models.get(index - 1);
        } catch (NumberFormatException ignored) {}

        for (JsonObject model : models) {
            if (query.equalsIgnoreCase(configString(model, "value"))
                    || query.equalsIgnoreCase(configString(model, "label"))) {
                return model;
            }
        }
        return null;
    }

    private static List<JsonObject> configuredModels(JsonObject config) {
        List<JsonObject> models = new ArrayList<>();
        if (config == null || !config.has("models") || !config.get("models").isJsonArray()) return models;
        for (JsonElement element : config.getAsJsonArray("models")) {
            if (element.isJsonObject() && !configString(element.getAsJsonObject(), "value").isEmpty()) {
                models.add(element.getAsJsonObject());
            }
        }
        return models;
    }

    private static String configString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        return object.get(key).getAsString().trim();
    }

    private static String normalizeReasoningEffort(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "default".equals(normalized)) return "";
        if ("low".equals(normalized) || "medium".equals(normalized) || "high".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private static void handleUndo(TerminalStart agent) {
        try {
            List<String> restored = agent.rollbackLastRun();
            if (restored.isEmpty()) {
                System.out.println("  \u001B[33m最近一次任务没有可回滚的文件修改。\u001B[0m");
            } else {
                System.out.println("  \u001B[36m已回滚 " + restored.size() + " 个文件:\u001B[0m");
                for (String file : restored) System.out.println("    " + file);
            }
        } catch (IOException e) {
            System.out.println("  \u001B[31m回滚失败: " + e.getMessage() + "\u001B[0m");
        }
    }

    private static void printBanner(String model) {
        String cyan  = "\033[36m";
        String green = "\033[32m";
        String reset = "\033[0m";

        System.out.println(cyan);
        System.out.println("            ><(((º>");
        System.out.println("           /  º   \\");
        System.out.println("          |   O    |~~");
        System.out.println("           \\      /");
        System.out.println("            `----'");
        System.out.println();

        System.out.println(reset + green + "  ███████╗ ██╗ ███████╗ ██╗  ██╗       ██████╗  ██████╗ ██████╗ ███████╗" + reset);
        System.out.println(green + "  ██╔════╝ ██║ ██╔════╝ ██║  ██║      ██╔════╝ ██╔═══██╗██╔══██╗██╔════╝" + reset);
        System.out.println(green + "  █████╗   ██║ ███████╗ ███████║      ██║      ██║   ██║██║  ██║█████╗  " + reset);
        System.out.println(green + "  ██╔══╝   ██║ ╚════██║ ██╔══██║      ██║      ██║   ██║██║  ██║██╔══╝  " + reset);
        System.out.println(green + "  ██║      ██║ ███████║ ██║  ██║      ╚██████╗ ╚██████╔╝██████╔╝███████╗" + reset);
        System.out.println(green + "  ╚═╝      ╚═╝ ╚══════╝ ╚═╝  ╚═╝       ╚═════╝  ╚═════╝ ╚═════╝ ╚══════╝" + reset);
        System.out.println();
        System.out.println(cyan + "  model: " + model
                + "  |  Shift+Tab 切换模式  |  /model 选择模型  |  /help 帮助  |  exit 退出" + reset);
        System.out.println();
    }

    private static void printHelp() {
        String reset = "\u001B[0m";
        System.out.println();
        System.out.println("  快捷键:");
        System.out.println("    Shift+Tab  - 循环切换运行模式");
        System.out.println("    Ctrl+C     - 退出程序");
        System.out.println("    上下箭头    - 浏览历史输入");
        System.out.println();
        System.out.println("  模式命令:");
        System.out.println("    /mode        - 切换到下一个模式");
        System.out.println("    /mode plan   - 切换到规划模式（只读分析）");
        System.out.println("    /mode auto   - 切换到自动执行模式（无需确认）");
        System.out.println("    /mode confirm- 切换到手动确认模式（每次写入需确认）");
        System.out.println();
        System.out.println("  模型命令:");
        System.out.println("    /model       - 打开模型选择列表");
        System.out.println("    /model <n>   - 按序号切换模型");
        System.out.println("    /model <name>- 按模型名称或标识切换模型");
        System.out.println();
        System.out.println("  会话命令:");
        System.out.println("    /new         - 新建空白会话");
        System.out.println("    /clear       - 清空当前会话上下文");
        System.out.println("    /status      - 查看当前会话状态");
        System.out.println("    /cwd         - 查看当前工作目录");
        System.out.println("    /cwd <path>  - 切换工作目录并开启新上下文");
        System.out.println("    /history     - 查看历史会话列表");
        System.out.println("    /resume      - 恢复上一个会话");
        System.out.println("    /resume <id> - 恢复指定会话");
        System.out.println("    /delete <n>  - 删除指定会话");
        System.out.println("    /undo        - 恢复最近一次文件编辑");
        System.out.println();
        System.out.println("  模式说明:");
        for (AgentMode m : AgentMode.values()) {
            System.out.println("    " + m.color() + m.label() + reset + " - " + m.shortDesc());
        }
        System.out.println();
    }

    private static void printSessionHistory(SessionManager sm) {
        String reset = "\u001B[0m";
        String cyan = "\033[36m";
        String gray = "\033[90m";
        List<JsonObject> sessions = sm.listSessions();
        if (sessions.isEmpty()) {
            System.out.println("  " + gray + "暂无历史会话" + reset);
            return;
        }
        System.out.println();
        int idx = 1;
        for (JsonObject s : sessions) {
            String id = s.get("id").getAsString();
            String title = s.has("title") && !s.get("title").getAsString().isEmpty()
                    ? s.get("title").getAsString() : "(无标题)";
            int msgCount = s.has("messageCount") ? s.get("messageCount").getAsInt() : 0;
            long updatedAt = s.has("updatedAt") ? s.get("updatedAt").getAsLong() : 0;
            String timeStr = updatedAt > 0
                    ? formatTime(updatedAt)
                    : "未知";
            System.out.println("  " + cyan + idx + reset + "  " + title
                    + "  " + gray + id + " | " + msgCount + "条消息 | " + timeStr + reset);
            idx++;
        }
        System.out.println();
    }

    private static String formatTime(long millis) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(millis);
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDateTime dt = java.time.LocalDateTime.ofInstant(instant, zone);
        return String.format("%02d-%02d %02d:%02d",
                dt.getMonthValue(), dt.getDayOfMonth(), dt.getHour(), dt.getMinute());
    }

    private static AgentMode parseModeCommand(String input, AgentMode current) {
        if (input.equals("/mode") || input.equals("/m")) {
            return current.next();
        }
        if (input.startsWith("/mode ")) {
            String arg = input.substring(6).trim().toLowerCase();
            return switchMode(arg);
        }
        if (input.startsWith("/m ")) {
            String arg = input.substring(3).trim().toLowerCase();
            return switchMode(arg);
        }
        return null;
    }

    private static AgentMode switchMode(String name) {
        switch (name) {
            case "plan":    return AgentMode.PLAN;
            case "auto":    return AgentMode.AUTO;
            case "confirm": return AgentMode.CONFIRM;
            default:        return null;
        }
    }
}
