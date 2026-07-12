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



public class TerminalStart {

    private static final Gson GSON = new Gson();

    private static final Set<String> WRITE_TOOLS = new HashSet<>(Arrays.asList(
            "edit_file", "write_file", "run_command"));

    private static volatile boolean stopRequested = false;
    private static volatile boolean approveAllRemaining = false;
    private static volatile HttpURLConnection activeConnection = null;
    private static volatile Process activeProcess = null;

    private volatile String apiUrl;
    private volatile String apiKey;
    private volatile String model;

    // Per-request working directory - ThreadLocal for tool execution context
    private static final ThreadLocal<String> currentCwd = new ThreadLocal<>();

    public static void setCurrentCwd(String cwd) { currentCwd.set(cwd); }
    public static void clearCurrentCwd() { currentCwd.remove(); }
    public static String getCurrentCwd() {
        String cwd = currentCwd.get();
        return cwd != null ? cwd : System.getProperty("user.dir");
    }

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
        approveAllRemaining = approve;
    }

    public static boolean isApproveAllRemaining() {
        return approveAllRemaining;
    }

    public static void resetStopRequest() {
        stopRequested = false;
    }

    public static void requestStop() {
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
        activeProcess = process;
    }

    public static void clearActiveProcess(Process process) {
        if (activeProcess == process) {
            activeProcess = null;
        }
    }

    public static boolean isStopRequested() {
        return stopRequested;
    }

    // Per-request model override support
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> requestModelOverrides = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, String[]> requestConnOverrides = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setRequestOverride(String model, String apiUrl, String apiKey) {
        long tid = Thread.currentThread().getId();
        if (model != null) requestModelOverrides.put(tid, model);
        if (apiUrl != null || apiKey != null) requestConnOverrides.put(tid, new String[]{apiUrl, apiKey});
    }

    public static void clearRequestOverride() {
        long tid = Thread.currentThread().getId();
        requestModelOverrides.remove(tid);
        requestConnOverrides.remove(tid);
    }

    private String getOverrideModel() {
        return requestModelOverrides.get(Thread.currentThread().getId());
    }

    private String[] getOverrideConn() {
        return requestConnOverrides.get(Thread.currentThread().getId());
    }
    private final List<JsonObject> messages = new ArrayList<>();
    private List<Tool> tools;
    private final Map<String, Tool> toolMap = new HashMap<>();
    private AgentMode currentMode;
    private Terminal terminal;
    private Reader terminalReader;
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
    public String getWorkspaceDir() { return System.getProperty("user.dir"); }

    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setModel(String model) { this.model = model; }

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
        this.terminalReader = new InputStreamReader(terminal.input(), StandardCharsets.UTF_8);
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
        currentSessionId = sessionId;
        messagesDirty = true;
        return true;
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

    private void persistMessage(JsonObject msg) {
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
        long startTime = System.currentTimeMillis();
        prepareUserInput(userInput);

        JsonObject message;
        for (int round = 0; round < ToolConstants.MAX_TOOL_ROUNDS; round++) {
            if (stopRequested) {
                return new ChatResult("(对话已被用户终止)", System.currentTimeMillis() - startTime, estimateTokens());
            }
            message = callApiWithRetry(false);
            JsonElement toolCalls = message.get("tool_calls");
            String content = message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : null;

            if (noToolCalls(toolCalls)) {
                messages.add(message);
                persistMessage(message);
                long duration = System.currentTimeMillis() - startTime;
                return new ChatResult(content != null ? content : "(无回复)", duration, estimateTokens());
            }

            messages.add(message);
            persistMessage(message);
            processToolCalls(toolCalls.getAsJsonArray());
        }

        long duration = System.currentTimeMillis() - startTime;
        return new ChatResult("(达到最大工具调用轮次)", duration, estimateTokens());
    }

    public ChatResult chatStream(String userInput, StreamCallback callback) throws Exception {
        long startTime = System.currentTimeMillis();
        prepareUserInput(userInput);

        JsonObject message;
        for (int round = 0; round < ToolConstants.MAX_TOOL_ROUNDS; round++) {
            if (stopRequested) {
                callback.onComplete(new ChatResult("(对话已被用户终止)", System.currentTimeMillis() - startTime, estimateTokens()));
                return new ChatResult("(对话已被用户终止)", System.currentTimeMillis() - startTime, estimateTokens());
            }

            message = callApiStreaming(callback);
            if (stopRequested) {
                ChatResult result = new ChatResult("(对话已被用户终止)", System.currentTimeMillis() - startTime, estimateTokens());
                callback.onComplete(result);
                return result;
            }
            messages.add(message);
            persistMessage(message);

            JsonElement toolCalls = message.get("tool_calls");
            if (noToolCalls(toolCalls)) {
                long duration = System.currentTimeMillis() - startTime;
                String reply = message.has("content") && !message.get("content").isJsonNull()
                        ? message.get("content").getAsString() : "(无回复)";
                ChatResult result = new ChatResult(reply, duration, estimateTokens());
                callback.onComplete(result);
                return result;
            }

            processToolCallsStreaming(toolCalls.getAsJsonArray(), callback);
        }

        long duration = System.currentTimeMillis() - startTime;
        ChatResult result = new ChatResult("(达到最大工具调用轮次)", duration, estimateTokens());
        callback.onComplete(result);
        return result;
    }

    private void prepareUserInput(String userInput) {
        ensureSession(userInput);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userInput);
        messages.add(userMsg);
        persistMessage(userMsg);
        messagesDirty = true;
    }

    private static boolean noToolCalls(JsonElement toolCalls) {
        return toolCalls == null || toolCalls.isJsonNull() || toolCalls.getAsJsonArray().size() == 0;
    }

    // === TOOL CALL PROCESSING ===
    private void processToolCalls(JsonArray toolCalls) throws IOException {
        for (JsonElement tc : toolCalls) {
            if (stopRequested) return;
            JsonObject toolCall = tc.getAsJsonObject();
            String fnName = toolCall.getAsJsonObject("function").get("name").getAsString();
            String fnArgs = toolCall.getAsJsonObject("function").get("arguments").getAsString();
            String callId = toolCall.get("id").getAsString();

            if (shouldConfirm(fnName, fnArgs)) {
                boolean approved = confirmAction(fnName, fnArgs);
                if (!approved) {
                    addRejectionMessage(callId);
                    continue;
                }
            }
            if (stopRequested) return;

            System.out.println("[调用工具] " + fnName + "(" + fnArgs + ")");
            executeAndAddToolResult(fnName, fnArgs, callId);
        }
    }

    private void processToolCallsStreaming(JsonArray toolCalls, StreamCallback callback) throws IOException {
        for (JsonElement tc : toolCalls) {
            if (stopRequested) return;
            JsonObject toolCall = tc.getAsJsonObject();
            String fnName = toolCall.getAsJsonObject("function").get("name").getAsString();
            String fnArgs = toolCall.getAsJsonObject("function").get("arguments").getAsString();
            String callId = toolCall.get("id").getAsString();

            if (shouldConfirm(fnName, fnArgs)) {
                boolean approved;
                if (approveAllRemaining) {
                    approved = true;
                } else if (terminal == null) {
                    String confirmKey = createConfirmationRequest();
                    callback.onConfirmRequired(confirmKey, fnName, fnArgs);
                    try {
                        java.util.concurrent.CompletableFuture<Boolean> future = pendingConfirmations.get(confirmKey);
                        if (future != null) {
                            approved = future.get(300, java.util.concurrent.TimeUnit.SECONDS);
                        } else {
                            approved = false;
                        }
                    } catch (Exception e) {
                        approved = false;
                    } finally {
                        pendingConfirmations.remove(confirmKey);
                    }
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
            if (stopRequested) return;

            callback.onToolCall(fnName, fnArgs, "running");
            System.out.println("\n[调用工具] " + fnName + "(" + fnArgs + ")");
            ToolResult result = executeAndAddToolResult(fnName, fnArgs, callId);
            callback.onToolResult(fnName, fnArgs, "done", result.getDetails());
        }
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

    private void addRejectionMessage(String callId) {
        JsonObject toolMsg = new JsonObject();
        toolMsg.addProperty("role", "tool");
        toolMsg.addProperty("tool_call_id", callId);
        toolMsg.addProperty("content", "用户拒绝了此操作。请调整方案后重试，或说明原因。");
        messages.add(toolMsg);
        persistMessage(toolMsg);
        messagesDirty = true;
    }

    private ToolResult executeAndAddToolResult(String fnName, String fnArgs, String callId) {
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
            java.nio.file.Files.createDirectories(HISTORY_FILE.getParent());
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, inputHistory.size() - MAX_HISTORY);
            for (int i = start; i < inputHistory.size(); i++) {
                sb.append(inputHistory.get(i)).append('\n');
            }
            java.nio.file.Files.write(HISTORY_FILE,
                    sb.toString().getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
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
        activeConnection = conn;
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
            if (activeConnection == conn) activeConnection = null;
            throw new IOException("API error [" + statusCode + "]: " + truncateError(responseBody));
        }

        try {
            JsonObject response = GSON.fromJson(responseBody.trim(), JsonObject.class);
            if (response != null && response.has("choices") && response.get("choices").isJsonArray()
                    && response.getAsJsonArray("choices").size() > 0) {
                JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("message") && choice.get("message").isJsonObject()) {
                    return choice.getAsJsonObject("message");
                }
            }
            if (response != null && response.has("role")) {
                return response;
            }
            throw new IOException("API response missing choices[0].message");
        } finally {
            if (activeConnection == conn) activeConnection = null;
        }
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
        activeConnection = conn;
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
            if (activeConnection == conn) activeConnection = null;
            throw new IOException("API error [" + statusCode + "]: " + truncateError(errorBody));
        }

        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        Map<Integer, JsonObject> toolCallsMap = new LinkedHashMap<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int emptyLineCount = 0;
            while ((line = br.readLine()) != null) {
                if (stopRequested) {
                    break;
                }
                if (line.isEmpty()) {
                    emptyLineCount++;
                    if (emptyLineCount > 10) break;
                    continue;
                }
                emptyLineCount = 0;
                if (!line.startsWith("data: ")) continue;

                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                JsonObject chunk;
                try {
                    chunk = GSON.fromJson(data, JsonObject.class);
                } catch (Exception e) {
                    continue;
                }

                if (!chunk.has("choices") || chunk.get("choices").isJsonNull()) continue;
                JsonObject choice = chunk.getAsJsonArray("choices").get(0).getAsJsonObject();

                if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                    String finishReason = choice.get("finish_reason").getAsString();
                    if ("length".equals(finishReason)) {
                        callback.onError("(响应因长度限制被截断)");
                    }
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
            if (!stopRequested) {
                throw e;
            }
        } finally {
            if (activeConnection == conn) activeConnection = null;
        }

        if (fullContent.length() == 0 && fullThinking.length() > 0) {
            fullContent.append(fullThinking);
        }
        JsonObject message = buildAssistantMessage(fullContent, toolCallsMap);
        messagesDirty = true;
        return message;
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
            for (int i = 0; i < toolCallsMap.size(); i++) {
                tcArray.add(toolCallsMap.get(i));
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
        int samples = 0;
        for (int i = fromInclusive; i < toExclusive && samples < 6; i++) {
            JsonObject msg = messages.get(i);
            String role = msg.has("role") ? msg.get("role").getAsString() : "unknown";
            if (!msg.has("content") || msg.get("content").isJsonNull()) continue;
            String content = msg.get("content").getAsString().replaceAll("\\s+", " ").trim();
            if (content.isEmpty()) continue;
            if (content.length() > 120) {
                content = content.substring(0, 120) + "...";
            }
            sb.append("\n- ").append(role).append(": ").append(content);
            samples++;
        }
        sb.append("\n请基于保留的近期上下文继续任务；如缺少细节，请主动重新读取项目文件。");
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
        if (approveAllRemaining) {
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
                approveAllRemaining = true;
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
        String workspaceDir = args.length > 0
                ? args[0]
                : ConfigLoader.getConfigString("workspace_dir", "FISH_CODE_WORKDIR", System.getProperty("user.dir"));

        if (apiKey.isEmpty()) {
            System.out.println("请配置 api_key (环境变量 DEEPSEEK_API_KEY 或 ~/.fish-code/config.json)");
            return;
        }

        SessionManager sessionManager = new SessionManager();
        TerminalStart agent = new TerminalStart(apiUrl, apiKey, model, sessionManager);
        agent.setMode(AgentMode.CONFIRM);
        try {
            agent.setWorkspaceDir(workspaceDir);
        } catch (IOException e) {
            System.out.println("工作目录配置无效，继续使用当前目录: " + System.getProperty("user.dir"));
            System.out.println("原因: " + e.getMessage());
        }

        printBanner(model);
        System.out.println("  " + modeStatusBar(AgentMode.CONFIRM) + "  Shift+Tab 切换 | /cwd 切换目录 | /help 帮助 | exit 退出\n");

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
                    System.out.println("  模式: " + agent.getMode().label() + "\u001B[0m");
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

            if (c == '\t' && line.length() == 0) {
                AgentMode newMode = currentMode.next();
                setMode(newMode);
                String newPrompt = buildPrompt(newMode);
                terminal.writer().print("\r" + newPrompt + "  (" + newMode.shortDesc() + ")");
                terminal.writer().print("\r" + newPrompt);
                terminal.writer().flush();
                continue;
            }

            if (c == 27) {
                int peek = terminalReader.read();
                if (peek == '[') {
                    int seq = terminalReader.read();
                    if (seq == 'Z') {
                        AgentMode newMode = currentMode.prev();
                        setMode(newMode);
                        String newPrompt = buildPrompt(newMode);
                        terminal.writer().print("\r" + newPrompt + line);
                        terminal.writer().flush();
                        continue;
                    } else if (seq == 'A') {
                        if (historyIndex == -1) {
                            savedLine = line.toString();
                            historyIndex = inputHistory.size();
                        }
                        if (historyIndex > 0) {
                            historyIndex--;
                            replaceLine(line, terminal, inputHistory.get(historyIndex));
                        }
                        continue;
                    } else if (seq == 'B') {
                        if (historyIndex >= 0 && historyIndex < inputHistory.size() - 1) {
                            historyIndex++;
                            replaceLine(line, terminal, inputHistory.get(historyIndex));
                        } else if (historyIndex == inputHistory.size() - 1) {
                            historyIndex = -1;
                            replaceLine(line, terminal, savedLine);
                        }
                        continue;
                    }
                }
                continue;
            }

            if (c == 8 || c == 127) {
                if (line.length() > 0) {
                    int removed = line.charAt(line.length() - 1);
                    line.deleteCharAt(line.length() - 1);
                    if (removed > 127) {
                        terminal.writer().print("\b\b  \b\b");
                    } else {
                        terminal.writer().print("\b \b");
                    }
                    terminal.writer().flush();
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

    private void replaceLine(StringBuilder line, Terminal terminal, String replacement) throws IOException {
        while (line.length() > 0) {
            int removed = line.charAt(line.length() - 1);
            line.deleteCharAt(line.length() - 1);
            if (removed > 127) {
                terminal.writer().print("\b\b  \b\b");
            } else {
                terminal.writer().print("\b \b");
            }
        }
        line.append(replacement);
        terminal.writer().print(replacement);
        terminal.writer().flush();
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

    private static void handleUndo(TerminalStart agent) {
        System.out.println("  \u001B[33m当前版本不再自动生成 .bak 文件。请根据会话中的 diff 手动回滚，或让 Agent 按 diff 反向修改。\u001B[0m");
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
        System.out.println(cyan + "  model: " + model + "  |  Tab 切换模式  |  /help 帮助  |  exit 退出" + reset);
        System.out.println();
    }

    private static void printHelp() {
        String reset = "\u001B[0m";
        System.out.println();
        System.out.println("  快捷键:");
        System.out.println("    Tab        - 切换到下一个模式（仅空行时）");
        System.out.println("    Shift+Tab  - 切换到上一个模式");
        System.out.println("    Ctrl+C     - 退出程序");
        System.out.println("    上下箭头    - 浏览历史输入");
        System.out.println();
        System.out.println("  模式命令:");
        System.out.println("    /mode        - 切换到下一个模式");
        System.out.println("    /mode plan   - 切换到规划模式（只读分析）");
        System.out.println("    /mode auto   - 切换到自动执行模式（无需确认）");
        System.out.println("    /mode confirm- 切换到手动确认模式（每次写入需确认）");
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
