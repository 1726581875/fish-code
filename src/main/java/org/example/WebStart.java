package org.example;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import org.example.core.*;
import org.example.tool.ToolConstants;

public class WebStart {

    private static final Gson GSON = new Gson();
    private static final int PORT = Integer.getInteger("fish.web.port", 10000);
    private static HttpServer server;
    private static TerminalStart agent;
    private static SessionManager sessionManager;
    private static String webUser;
    private static String webPassword;
    private static boolean webAuthEnabled;
    private static boolean webSecureCookie;
    private static boolean generatedWebPassword;
    private static final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private static final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();
    private static final Object agentLock = new Object();
    private static final Semaphore chatSlot = new Semaphore(1, true);
    private static final RunRegistry runRegistry = new RunRegistry();
    private static WorkspacePolicy workspacePolicy;
    private static String bindAddress;
    private static boolean localOnlyBind;
    private static ClaudeHistoryReader claudeReader;
    private static final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "token-cleanup");
        t.setDaemon(true);
        return t;
    });

    public static void main(String[] args) {
        String apiUrl = ConfigLoader.getConfigString("cur_api_url", "DEEPSEEK_API_URL",
                "https://api.deepseek.com/chat/completions");
        String apiKey = ConfigLoader.getConfigString("cur_api_key", "DEEPSEEK_API_KEY", "");
        String model = ConfigLoader.getConfigString("cur_model", "DEEPSEEK_MODEL", "deepseek-chat");
        String workspaceDir = args.length > 0
                ? args[0]
                : ConfigLoader.getConfigString("workspace_dir", "FISH_CODE_WORKDIR", System.getProperty("user.dir"));
        webAuthEnabled = ConfigLoader.getConfigBoolean("web_auth_enabled", "WEB_AUTH_ENABLED", false);
        webSecureCookie = ConfigLoader.getConfigBoolean("web_secure_cookie", "WEB_SECURE_COOKIE", false);
        webUser = ConfigLoader.getConfigString("web_user", "WEB_USER", "fish");
        webPassword = ConfigLoader.getConfigString("web_password", "WEB_PASSWORD", "");
        bindAddress = ConfigLoader.getConfigString("web_bind_address", "WEB_BIND_ADDRESS", "127.0.0.1");
        if (webAuthEnabled && (webPassword == null || webPassword.trim().isEmpty())) {
            webPassword = "fish-" + UUID.randomUUID().toString().substring(0, 8);
            generatedWebPassword = true;
        }

        if (apiKey.isEmpty()) {
            System.out.println("未配置 api_key，Web 会启动但聊天接口会提示配置引导。");
            System.out.println("请设置环境变量 DEEPSEEK_API_KEY 或 ~/.fish-code/config.json 中的 cur_api_key");
        }

        sessionManager = new SessionManager();
        agent = new TerminalStart(apiUrl, apiKey, model, sessionManager);
        agent.setMode(AgentMode.CONFIRM);
        try {
            agent.setWorkspaceDir(workspaceDir);
        } catch (IOException e) {
            System.out.println("工作目录配置无效，继续使用当前目录: " + System.getProperty("user.dir"));
            System.out.println("原因: " + e.getMessage());
        }
        try {
            localOnlyBind = InetAddress.getByName(bindAddress).isLoopbackAddress();
            if (!localOnlyBind && !webAuthEnabled) {
                System.err.println("拒绝启动：监听非本机地址时必须启用 WEB_AUTH_ENABLED=true");
                return;
            }
        } catch (IOException e) {
            System.err.println("无效的监听地址: " + bindAddress);
            return;
        }
        try {
            workspacePolicy = WorkspacePolicy.fromConfig(ConfigLoader.loadConfig(),
                    agent.getWorkspaceDir(), localOnlyBind);
        } catch (IOException e) {
            System.err.println("工作区安全策略初始化失败: " + e.getMessage());
            return;
        }

        String claudeDir = ConfigLoader.getConfigString("claude_dir", "CLAUDE_DIR", "");
        claudeReader = new ClaudeHistoryReader(claudeDir);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null) server.stop(2);
            cleanupExecutor.shutdownNow();
        }));

        cleanupExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            tokens.entrySet().removeIf(e -> now >= e.getValue());
            loginAttempts.entrySet().removeIf(e -> e.getValue().isExpiredAndIdle(now));
            runRegistry.cleanup(TimeUnit.HOURS.toMillis(24));
        }, 1, 1, TimeUnit.HOURS);

        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, PORT), 0);
            server.createContext("/", new IndexHandler());
            server.createContext("/login", new LoginHandler());
            server.createContext("/logout", new LogoutHandler());
            server.createContext("/chat", new ChatHandler());
            server.createContext("/mode", new ModeHandler());
            server.createContext("/model", new ModelHandler());
            server.createContext("/cwd", new CwdHandler());
            server.createContext("/cwd/list", new CwdListHandler());
            server.createContext("/project/tree", new ProjectTreeHandler());
            server.createContext("/confirm", new ConfirmHandler());
            server.createContext("/cancel", new CancelHandler());
            server.createContext("/rollback", new RollbackHandler());
            server.createContext("/config", new ConfigHandler());
            server.createContext("/sessions", new SessionsHandler());
            server.createContext("/health", new HealthHandler());
            server.createContext("/home", new HomePageHandler());
            server.createContext("/claude-history", new ClaudeHistoryPageHandler());
            server.createContext("/claude-conversations", new ClaudeConversationsPageHandler());
            server.createContext("/claude-flowchart", new FlowchartPageHandler());
            ClaudeSessionsHandler claudeHandler = new ClaudeSessionsHandler(claudeReader);
            server.createContext("/claude-api", exchange -> {
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    handleOptions(exchange);
                    return;
                }
                if (!checkAuth(exchange)) {
                    sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                    return;
                }
                claudeHandler.handle(exchange);
            });
            server.setExecutor(new ThreadPoolExecutor(16, 16, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100), r -> {
                        Thread t = new Thread(r, "http-worker");
                        t.setDaemon(true);
                        return t;
                    }));
            server.start();

            System.out.println("\n  Fish Code Web 已启动: \u001B[36mhttp://" + bindAddress + ":" + PORT + "\u001B[0m");
            if (webAuthEnabled) {
                System.out.println("  登录已启用，账号: " + webUser);
                if (generatedWebPassword) {
                    System.out.println("  本次临时密码: " + webPassword);
                    System.out.println("  建议在环境变量 WEB_PASSWORD 或 ~/.fish-code/config.json 中配置固定密码");
                }
            } else {
                System.out.println("  登录未启用。如需开启，请设置 WEB_AUTH_ENABLED=true 或 web_auth_enabled=true");
            }
            System.out.println("  按 Ctrl+C 退出\n");
        } catch (IOException e) {
            System.err.println("启动失败: " + e.getMessage());
        }
    }

    private static boolean checkAuth(HttpExchange exchange) {
        if (!webAuthEnabled) {
            return true;
        }
        String token = extractToken(exchange);
        if (token != null) {
            Long expire = tokens.get(token);
            if (expire != null && System.currentTimeMillis() < expire) {
                return true;
            }
            if (expire != null) {
                tokens.remove(token);
            }
        }
        return false;
    }

    private static File requireOrAuthorizeWorkspace(File candidate) throws IOException {
        try {
            return workspacePolicy.requireAllowed(candidate);
        } catch (IOException denied) {
            if (!localOnlyBind) throw denied;
            return workspacePolicy.authorizeExplicit(candidate);
        }
    }

    private static String extractToken(HttpExchange exchange) {
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            String headerValue = authHeaders.get(0);
            if (headerValue.startsWith("Bearer ")) {
                return headerValue.substring(7);
            }
        }
        List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
        if (cookieHeaders != null) {
            for (String header : cookieHeaders) {
                for (String part : header.split(";")) {
                    String[] kv = part.trim().split("=", 2);
                    if (kv.length == 2 && "fish_token".equals(kv[0].trim())) {
                        return kv[1].trim();
                    }
                }
            }
        }
        return null;
    }

    private static String authCookie(String value, boolean clear) {
        StringBuilder cookie = new StringBuilder("fish_token=")
                .append(value == null ? "" : value)
                .append("; Path=/; HttpOnly; SameSite=Lax");
        if (clear) cookie.append("; Max-Age=0");
        if (webSecureCookie) cookie.append("; Secure");
        return cookie.toString();
    }

    private static void sendJson(HttpExchange exchange, JsonObject json, int code) throws IOException {
        addCorsHeaders(exchange);
        byte[] resp = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private static boolean writeSse(OutputStream output, JsonObject event) {
        return writeSse(output, event, false);
    }

    private static boolean writeSse(OutputStream output, JsonObject event, boolean endStream) {
        try {
            String data = "data: " + GSON.toJson(event) + "\n\n";
            if (endStream) data += "[END]\n\n";
            synchronized (output) {
                output.write(data.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (isAllowedOrigin(origin, exchange)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, Cookie");
    }

    private static boolean isAllowedOrigin(String origin, HttpExchange exchange) {
        if (origin == null || origin.trim().isEmpty()) {
            return false;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && origin.equals("http://" + host)) {
            return true;
        }
        return origin.startsWith("http://localhost:")
                || origin.startsWith("http://127.0.0.1:")
                || origin.startsWith("http://[::1]:");
    }

    private static void handleOptions(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    static class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                byte[] html = readHtml("login.html");
                addCorsHeaders(exchange);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, html.length);
                exchange.getResponseBody().write(html);
                exchange.close();
                return;
            }
            byte[] html = readHtml("index.html");
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JsonObject result = new JsonObject();
            result.addProperty("status", "ok");
            sendJson(exchange, result, 200);
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!webAuthEnabled) {
                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("authEnabled", false);
                sendJson(exchange, result, 200);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            String remoteAddr = exchange.getRemoteAddress().getAddress().getHostAddress();
            LoginAttempt attempts = loginAttempts.computeIfAbsent(remoteAddr, k -> new LoginAttempt());
            long now = System.currentTimeMillis();
            long retryAfter = attempts.retryAfterSeconds(now);
            if (retryAfter > 0) {
                exchange.getResponseHeaders().set("Retry-After", String.valueOf(retryAfter));
                JsonObject throttled = new JsonObject();
                throttled.addProperty("success", false);
                throttled.addProperty("error", "登录尝试过多，请在 " + retryAfter + " 秒后重试");
                sendJson(exchange, throttled, 429);
                return;
            }

            String body = readBody(exchange);
            JsonObject req;
            try {
                req = GSON.fromJson(body, JsonObject.class);
            } catch (JsonParseException e) {
                req = null;
            }
            if (req == null) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "登录请求格式无效");
                sendJson(exchange, error, 400);
                return;
            }
            JsonElement userValue = req.get("username");
            JsonElement passValue = req.get("password");
            String user = userValue != null && userValue.isJsonPrimitive() ? userValue.getAsString() : "";
            String pass = passValue != null && passValue.isJsonPrimitive() ? passValue.getAsString() : "";

            JsonObject result = new JsonObject();
            if (webUser.equals(user) && webPassword.equals(pass)) {
                loginAttempts.remove(remoteAddr);
                String token = UUID.randomUUID().toString();
                long expireMs = (long) ToolConstants.TOKEN_EXPIRE_HOURS * 60 * 60 * 1000;
                tokens.put(token, System.currentTimeMillis() + expireMs);
                addCorsHeaders(exchange);
                exchange.getResponseHeaders().add("Set-Cookie", authCookie(token, false));
                result.addProperty("success", true);
            } else {
                attempts.recordFailure(now);
                result.addProperty("success", false);
            }
            sendJson(exchange, result, 200);
        }
    }

    static class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String token = extractToken(exchange);
            if (token != null) {
                tokens.remove(token);
            }
            exchange.getResponseHeaders().add("Set-Cookie", authCookie("", true));
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            sendJson(exchange, result, 200);
        }
    }

    static class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String body = readBody(exchange);
            JsonObject req = GSON.fromJson(body, JsonObject.class);
            String message = req.has("message") ? req.get("message").getAsString() : "";
            boolean stream = req.has("stream") && req.get("stream").getAsBoolean();
            String reqModel = req.has("model") ? req.get("model").getAsString() : null;
            String reqCwd = req.has("cwd") ? req.get("cwd").getAsString() : null;

            if (message.isEmpty()) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"消息不能为空\"}", JsonObject.class), 400);
                return;
            }
            if (message.length() > ToolConstants.CHAT_MESSAGE_MAX_CHARS) {
                JsonObject result = new JsonObject();
                result.addProperty("error", "消息过长，上限" + ToolConstants.CHAT_MESSAGE_MAX_CHARS + "字符");
                sendJson(exchange, result, 400);
                return;
            }
            String resolvedApiUrl = null;
            String resolvedApiKey = null;
            if (reqModel != null) {
                JsonObject config = ConfigLoader.loadConfig();
                if (config.has("models")) {
                    for (JsonElement el : config.getAsJsonArray("models")) {
                        JsonObject m = el.getAsJsonObject();
                        if (reqModel.equals(m.get("value").getAsString())) {
                            resolvedApiUrl = m.has("api_url") ? m.get("api_url").getAsString() : null;
                            resolvedApiKey = m.has("api_key") ? m.get("api_key").getAsString() : null;
                            break;
                        }
                    }
                }
                boolean hasRequestKey = resolvedApiKey != null && !resolvedApiKey.isEmpty();
                boolean hasDefaultKey = agent.getApiKey() != null && !agent.getApiKey().isEmpty();
                if (!hasRequestKey && !hasDefaultKey) {
                    JsonObject result = new JsonObject();
                    result.addProperty("error", "未配置 API Key。请设置环境变量 DEEPSEEK_API_KEY，或在 ~/.fish-code/config.json 中配置 cur_api_key / models[].api_key 后重启服务。");
                    sendJson(exchange, result, 503);
                    return;
                }
            } else if (agent.getApiKey() == null || agent.getApiKey().isEmpty()) {
                JsonObject result = new JsonObject();
                result.addProperty("error", "未配置 API Key。请设置环境变量 DEEPSEEK_API_KEY，或在 ~/.fish-code/config.json 中配置 cur_api_key 后重启服务。");
                sendJson(exchange, result, 503);
                return;
            }
            String effectiveCwd = agent.getWorkspaceDir();
            if (reqCwd != null && !reqCwd.isEmpty()) {
                try {
                    effectiveCwd = workspacePolicy.requireAllowed(new File(reqCwd)).getPath();
                } catch (IOException e) {
                    JsonObject result = new JsonObject();
                    result.addProperty("error", e.getMessage());
                    sendJson(exchange, result, 400);
                    return;
                }
            }
            String effectiveModel = reqModel != null ? reqModel : agent.getModel();
            String effectiveApiUrl = resolvedApiUrl != null ? resolvedApiUrl : agent.getApiUrl();
            String effectiveApiKey = resolvedApiKey != null && !resolvedApiKey.isEmpty() ? resolvedApiKey : agent.getApiKey();
            if (!chatSlot.tryAcquire()) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"已有任务正在执行，请等待当前任务完成或先取消\"}", JsonObject.class), 409);
                return;
            }
            try {
                String resumeRunId = req.has("resumeRunId") ? req.get("resumeRunId").getAsString() : "";
                String resumeSessionId = req.has("sessionId") ? req.get("sessionId").getAsString() : "";
                AgentRun run;
                if (!resumeRunId.isEmpty()) {
                    JsonObject persisted = runRegistry.get(resumeRunId) == null && !resumeSessionId.isEmpty()
                            ? sessionManager.loadTaskState(resumeSessionId) : null;
                    try {
                        run = runRegistry.resume(resumeRunId, message, effectiveModel, effectiveApiUrl,
                                effectiveApiKey, effectiveCwd, persisted);
                    } catch (IOException e) {
                        JsonObject error = new JsonObject();
                        error.addProperty("error", "无法恢复任务: " + e.getMessage());
                        sendJson(exchange, error, 409);
                        return;
                    }
                } else {
                    run = runRegistry.create(message, effectiveModel, effectiveApiUrl, effectiveApiKey, effectiveCwd);
                }
                if (stream) {
                    handleStream(exchange, message, run);
                } else {
                    handleSync(exchange, message, run);
                }
            } finally {
                chatSlot.release();
            }
        }

        private void handleStream(HttpExchange exchange, String message, AgentRun run) throws IOException {
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            OutputStream os = exchange.getResponseBody();

            try {
                StreamCallback sseCallback = new StreamCallback() {
                    @Override
                    public void onRunStarted(String runId, String sessionId) {
                        JsonObject event = new JsonObject();
                        event.addProperty("type", "run_started");
                        event.addProperty("runId", runId);
                        event.addProperty("sessionId", sessionId);
                        if (!writeSse(os, event)) run.requestStop();
                    }

                    @Override
                    public void onTaskUpdate(JsonObject taskState) {
                        JsonObject event = new JsonObject();
                        event.addProperty("type", "task_update");
                        event.addProperty("runId", run.getRunId());
                        event.add("task", taskState == null ? new JsonObject() : taskState);
                        if (!writeSse(os, event)) run.requestStop();
                    }

                    @Override
                    public void onVerification(JsonObject verification) {
                        JsonObject event = new JsonObject();
                        event.addProperty("type", "verification");
                        event.addProperty("runId", run.getRunId());
                        event.add("verification", verification == null ? new JsonObject() : verification);
                        if (!writeSse(os, event)) run.requestStop();
                    }

                    @Override
                    public void onToken(String token) {
                        JsonObject evt = new JsonObject();
                        evt.addProperty("type", "token");
                        evt.addProperty("content", token);
                        if (!writeSse(os, evt)) run.requestStop();
                    }

                    @Override
                    public void onThinking(String text) {}

                    @Override
                    public void onToolCall(String fnName, String fnArgs, String status) {
                        JsonObject evt = new JsonObject();
                        evt.addProperty("type", "tool_call");
                        evt.addProperty("name", fnName);
                        evt.addProperty("args", fnArgs);
                        evt.addProperty("status", status);
                        if (!writeSse(os, evt)) run.requestStop();
                    }

                    @Override
                    public void onToolResult(String fnName, String fnArgs, String status, JsonObject result) {
                        JsonObject evt = new JsonObject();
                        evt.addProperty("type", "tool_call");
                        evt.addProperty("name", fnName);
                        evt.addProperty("args", fnArgs);
                        evt.addProperty("status", status);
                        evt.add("result", result == null ? new JsonObject() : result);
                        if (!writeSse(os, evt)) run.requestStop();
                    }

                    @Override
                    public void onConfirmRequired(String confirmKey, String fnName, String fnArgs) {
                        onConfirmRequired(run.getRunId(), confirmKey, fnName, fnArgs);
                    }

                    @Override
                    public void onConfirmRequired(String runId, String confirmKey, String fnName, String fnArgs) {
                        JsonObject evt = new JsonObject();
                        evt.addProperty("type", "confirm_required");
                        evt.addProperty("confirmKey", confirmKey);
                        evt.addProperty("runId", runId);
                        evt.addProperty("name", fnName);
                        evt.addProperty("args", fnArgs);
                        if (!writeSse(os, evt)) run.requestStop();
                    }

                    @Override
                    public void onComplete(ChatResult result) {
                        JsonObject doneEvent = new JsonObject();
                        doneEvent.addProperty("type", "done");
                        doneEvent.addProperty("reply", result.getReply());
                        doneEvent.addProperty("durationMs", result.getDurationMs());
                        doneEvent.addProperty("contextTokens", result.getContextTokens());
                        doneEvent.addProperty("replyChars", result.getReply() == null ? 0 : result.getReply().length());
                        doneEvent.addProperty("contextChars", agent.getContextCharCount());
                        doneEvent.addProperty("mode", agent.getMode().label());
                        doneEvent.addProperty("sessionId", agent.getCurrentSessionId() != null ? agent.getCurrentSessionId() : "");
                        JsonObject task = run.getTaskState().toJson();
                        doneEvent.addProperty("runId", run.getRunId());
                        doneEvent.addProperty("finalStatus", run.getTaskState().getPhase().name());
                        doneEvent.add("task", task);
                        doneEvent.add("modifiedFiles", task.getAsJsonArray("modifiedFiles"));
                        doneEvent.add("verifications", task.getAsJsonArray("verifications"));
                        doneEvent.add("remainingRisks", task.getAsJsonArray("remainingRisks"));
                        writeSse(os, doneEvent, true);
                    }

                    @Override
                    public void onError(String error) {
                        JsonObject errorEvent = new JsonObject();
                        errorEvent.addProperty("type", "error");
                        errorEvent.addProperty("error", error);
                        writeSse(os, errorEvent);
                    }
                };
                synchronized (agentLock) {
                    agent.chatStream(message, sseCallback, run);
                }
            } catch (Exception e) {
                JsonObject errorEvent = new JsonObject();
                errorEvent.addProperty("type", "error");
                errorEvent.addProperty("error", e.getMessage());
                errorEvent.addProperty("runId", run.getRunId());
                errorEvent.add("task", run.getTaskState().toJson());
                writeSse(os, errorEvent);
            } finally {
                try { os.close(); } catch (IOException ignored) {}
            }
        }

        private void handleSync(HttpExchange exchange, String message, AgentRun run) throws IOException {
            JsonObject result = new JsonObject();
            try {
                ChatResult chatResult;
                synchronized (agentLock) {
                    chatResult = agent.chat(message, run);
                }
                result.addProperty("reply", chatResult.getReply() != null ? chatResult.getReply() : "");
                result.addProperty("durationMs", chatResult.getDurationMs());
                result.addProperty("contextTokens", chatResult.getContextTokens());
                result.addProperty("replyChars", chatResult.getReply() == null ? 0 : chatResult.getReply().length());
                result.addProperty("contextChars", agent.getContextCharCount());
            } catch (Exception e) {
                result.addProperty("reply", "错误: " + e.getMessage());
                result.addProperty("durationMs", 0);
                result.addProperty("contextTokens", 0);
                result.addProperty("replyChars", 0);
                result.addProperty("contextChars", agent.getContextCharCount());
            }

            result.addProperty("toolOutput", "");
            result.addProperty("mode", agent.getMode().label());
            result.addProperty("sessionId", agent.getCurrentSessionId() != null ? agent.getCurrentSessionId() : "");
            result.addProperty("runId", run.getRunId());
            result.addProperty("finalStatus", run.getTaskState().getPhase().name());
            result.add("task", run.getTaskState().toJson());
            sendJson(exchange, result, 200);
        }
    }

    static class ModeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                JsonObject result = new JsonObject();
                result.addProperty("mode", agent.getMode().label());
                synchronized (agentLock) {
                    result.addProperty("contextChars", agent.getContextCharCount());
                }
                sendJson(exchange, result, 200);
            } else if ("POST".equals(method)) {
                String body = readBody(exchange);
                JsonObject req = GSON.fromJson(body, JsonObject.class);
                String modeName = req.has("mode") ? req.get("mode").getAsString() : "";
                synchronized (agentLock) {
                    switch (modeName) {
                        case "plan":    agent.setMode(AgentMode.PLAN);    break;
                        case "auto":    agent.setMode(AgentMode.AUTO);    break;
                        case "confirm": agent.setMode(AgentMode.CONFIRM); break;
                        default: sendJson(exchange, GSON.fromJson("{\"error\":\"invalid mode\"}", JsonObject.class), 400); return;
                    }
                }
                JsonObject result = new JsonObject();
                result.addProperty("mode", agent.getMode().label());
                synchronized (agentLock) {
                    result.addProperty("contextChars", agent.getContextCharCount());
                }
                sendJson(exchange, result, 200);
            }
        }
    }

    static class ModelHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                JsonObject result = new JsonObject();
                result.addProperty("model", agent.getModel());
                result.addProperty("apiUrl", agent.getApiUrl());
                sendJson(exchange, result, 200);
            } else if ("POST".equals(method)) {
                String body = readBody(exchange);
                JsonObject req = GSON.fromJson(body, JsonObject.class);
                synchronized (agentLock) {
                    if (req.has("model") && !req.get("model").getAsString().isEmpty()) {
                        agent.setModel(req.get("model").getAsString());
                    }
                    if (req.has("apiUrl") && !req.get("apiUrl").getAsString().isEmpty()) {
                        agent.setApiUrl(req.get("apiUrl").getAsString());
                    }
                    if (req.has("apiKey") && !req.get("apiKey").getAsString().isEmpty()) {
                        agent.setApiKey(req.get("apiKey").getAsString());
                    }
                }
                JsonObject result = new JsonObject();
                result.addProperty("model", agent.getModel());
                result.addProperty("apiUrl", agent.getApiUrl());
                sendJson(exchange, result, 200);
            }
        }
    }

    static class WorkspaceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }

            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                JsonObject result = new JsonObject();
                result.addProperty("workspaceDir", agent.getWorkspaceDir());
                sendJson(exchange, result, 200);
            } else if ("POST".equals(method)) {
                String body = readBody(exchange);
                JsonObject req = GSON.fromJson(body, JsonObject.class);
                String path = req.has("path") ? req.get("path").getAsString() : "";
                try {
                    String cwd;
                    synchronized (agentLock) {
                        cwd = agent.setWorkspaceDir(requireOrAuthorizeWorkspace(new File(path)).getPath());
                        agent.newConversation();
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("workspaceDir", cwd);
                    sendJson(exchange, result, 200);
                } catch (IOException e) {
                    JsonObject result = new JsonObject();
                    result.addProperty("error", e.getMessage());
                    sendJson(exchange, result, 400);
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        }
    }

    static class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                sendJson(exchange, buildConfigResponse(ConfigLoader.loadConfig()), 200);
                return;
            }
            if (!"POST".equals(method)) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            try {
                JsonObject request = GSON.fromJson(readBody(exchange), JsonObject.class);
                if (request == null) throw new IllegalArgumentException("请求内容不能为空");
                String action = request.has("action") ? request.get("action").getAsString() : "";
                JsonObject updated;
                synchronized (agentLock) {
                    JsonObject current = ConfigLoader.loadConfig();
                    switch (action) {
                        case "saveModel":
                            updated = ModelConfigManager.saveModel(current, request);
                            break;
                        case "selectModel":
                            updated = ModelConfigManager.selectModel(current, requestString(request, "value"));
                            break;
                        case "deleteModel":
                            updated = ModelConfigManager.deleteModel(current, requestString(request, "value"));
                            break;
                        default:
                            throw new IllegalArgumentException("未知操作");
                    }
                    ConfigLoader.saveConfig(updated);
                    applyRuntimeModel(updated);
                }
                sendJson(exchange, buildConfigResponse(updated), 200);
            } catch (IllegalArgumentException | JsonParseException e) {
                JsonObject result = new JsonObject();
                result.addProperty("error", e.getMessage() == null ? "模型配置无效" : e.getMessage());
                sendJson(exchange, result, 400);
            } catch (IOException e) {
                JsonObject result = new JsonObject();
                result.addProperty("error", "保存配置失败：" + e.getMessage());
                sendJson(exchange, result, 500);
            }
        }

        private static String requestString(JsonObject request, String key) {
            if (!request.has(key) || request.get(key).isJsonNull()) return "";
            return request.get(key).getAsString();
        }

        private static void applyRuntimeModel(JsonObject config) {
            if (config.has("cur_model")) agent.setModel(config.get("cur_model").getAsString());
            if (config.has("cur_api_url")) agent.setApiUrl(config.get("cur_api_url").getAsString());
            if (config.has("cur_api_key")) agent.setApiKey(config.get("cur_api_key").getAsString());
        }

        private static JsonObject buildConfigResponse(JsonObject config) {
            JsonObject result = new JsonObject();
            boolean anyModelApiKeyConfigured = false;
            if (config.has("models") && config.get("models").isJsonArray()) {
                JsonArray safeModels = new JsonArray();
                for (JsonElement el : config.getAsJsonArray("models")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject source = el.getAsJsonObject();
                    JsonObject model = new JsonObject();
                    for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
                        String key = entry.getKey();
                        if ("api_key".equals(key) || "apiKey".equals(key)) {
                            boolean configured = !entry.getValue().isJsonNull()
                                    && !entry.getValue().getAsString().isEmpty();
                            anyModelApiKeyConfigured = anyModelApiKeyConfigured || configured;
                            model.addProperty("apiKeyConfigured", configured);
                        } else {
                            model.add(key, entry.getValue());
                        }
                    }
                    safeModels.add(model);
                }
                result.add("models", safeModels);
            } else {
                result.add("models", new JsonArray());
            }
            String currentModel = config.has("cur_model") ? config.get("cur_model").getAsString() : agent.getModel();
            String currentApiUrl = config.has("cur_api_url") ? config.get("cur_api_url").getAsString() : agent.getApiUrl();
            result.addProperty("model", currentModel);
            result.addProperty("apiUrl", currentApiUrl);
            result.addProperty("workspaceDir", agent.getWorkspaceDir());
            result.addProperty("webAuthEnabled", webAuthEnabled);
            boolean apiKeyConfigured = (agent.getApiKey() != null && !agent.getApiKey().isEmpty()) || anyModelApiKeyConfigured;
            result.addProperty("apiKeyConfigured", apiKeyConfigured);
            result.addProperty("configured", apiKeyConfigured);
            if (!apiKeyConfigured) {
                result.addProperty("setupMessage", "未配置 API Key：请使用页面上的模型配置功能添加可用模型。");
            }
            if (config.has("cur_model")) {
                result.addProperty("cur_model", config.get("cur_model").getAsString());
            }
            return result;
        }
    }

    static class CwdHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                JsonObject result = new JsonObject();
                result.addProperty("cwd", TerminalStart.getCurrentCwd());
                synchronized (agentLock) {
                    result.addProperty("contextChars", agent.getContextCharCount());
                }
                sendJson(exchange, result, 200);
            } else if ("POST".equals(method)) {
                String body = readBody(exchange);
                JsonObject req = GSON.fromJson(body, JsonObject.class);
                String newCwd = req.has("cwd") ? req.get("cwd").getAsString() : "";
                if (newCwd.isEmpty()) {
                    sendJson(exchange, GSON.fromJson("{\"error\":\"cwd不能为空\"}", JsonObject.class), 400);
                    return;
                }
                if (newCwd.matches("^[A-Za-z]:$")) {
                    newCwd = newCwd + "\\";
                }
                java.io.File dir;
                try {
                    dir = requireOrAuthorizeWorkspace(new java.io.File(newCwd));
                } catch (IOException e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage());
                    sendJson(exchange, error, 403);
                    return;
                }
                if (!dir.exists() || !dir.isDirectory()) {
                    sendJson(exchange, GSON.fromJson("{\"error\":\"目录不存在: " + newCwd + "\"}", JsonObject.class), 400);
                    return;
                }
                JsonObject result = new JsonObject();
                try {
                    String cwd;
                    synchronized (agentLock) {
                        String before = agent.getWorkspaceDir();
                        cwd = agent.setWorkspaceDir(dir.getAbsolutePath());
                        if (!before.equals(cwd)) {
                            agent.newConversation();
                        }
                    }
                    result.addProperty("cwd", cwd);
                    synchronized (agentLock) {
                        result.addProperty("contextChars", agent.getContextCharCount());
                    }
                } catch (IOException e) {
                    result.addProperty("error", e.getMessage());
                    sendJson(exchange, result, 400);
                    return;
                }
                sendJson(exchange, result, 200);
            }
        }
    }

    static class CwdListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            String path = "";
            boolean includeHidden = false;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2 && "path".equals(kv[0])) {
                        path = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    } else if (kv.length == 2 && "hidden".equals(kv[0])) {
                        includeHidden = "true".equalsIgnoreCase(kv[1]);
                    }
                }
            }
            if (path.isEmpty()) path = TerminalStart.getCurrentCwd();
            if (path.matches("^[A-Za-z]:$")) {
                path = path + "\\";
            }
            java.io.File dir = new java.io.File(path).getCanonicalFile();
            if (!workspacePolicy.isAllowed(dir)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"目录超出允许的工作区范围\"}", JsonObject.class), 403);
                return;
            }
            if (!dir.exists() || !dir.isDirectory()) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"目录不存在\"}", JsonObject.class), 400);
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("path", dir.getAbsolutePath());
            File parent = dir.getParentFile();
            result.addProperty("parent", parent == null || !workspacePolicy.isAllowed(parent) ? "" : parent.getCanonicalPath());
            File home = new File(System.getProperty("user.home")).getCanonicalFile();
            result.addProperty("home", workspacePolicy.isAllowed(home) ? home.getPath() : "");
            result.addProperty("current", new File(agent.getWorkspaceDir()).getCanonicalPath());
            JsonArray roots = new JsonArray();
            for (File root : workspacePolicy.getRoots()) {
                JsonObject rootItem = new JsonObject();
                rootItem.addProperty("name", root.getAbsolutePath());
                rootItem.addProperty("path", root.getCanonicalPath());
                roots.add(rootItem);
            }
            result.add("roots", roots);
            JsonArray dirs = new JsonArray();
            java.io.File[] files = dir.listFiles();
            if (files == null) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"没有权限读取此目录\"}", JsonObject.class), 403);
                return;
            }
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (java.io.File f : files) {
                if (f.isDirectory() && (includeHidden || !f.isHidden())) {
                    if (!workspacePolicy.isAllowed(f)) continue;
                    JsonObject dirItem = new JsonObject();
                    dirItem.addProperty("name", f.getName());
                    dirItem.addProperty("path", f.getCanonicalPath());
                    dirItem.addProperty("readable", f.canRead());
                    dirs.add(dirItem);
                }
            }
            result.add("dirs", dirs);
            sendJson(exchange, result, 200);
        }
    }

    static class ProjectTreeHandler implements HttpHandler {
        private static final int MAX_ITEMS = 300;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            String relPath = queryParam(exchange, "path");
            File root = new File(agent.getWorkspaceDir()).getCanonicalFile();
            File dir = relPath == null || relPath.isEmpty()
                    ? root
                    : new File(root, relPath).getCanonicalFile();

            if (!isInside(root, dir) || !dir.exists() || !dir.isDirectory()) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"目录不存在或越界\"}", JsonObject.class), 400);
                return;
            }

            JsonObject result = new JsonObject();
            result.addProperty("root", root.getAbsolutePath());
            result.addProperty("path", relativize(root, dir));
            JsonArray items = new JsonArray();
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                int count = 0;
                for (File f : files) {
                    if (count >= MAX_ITEMS) break;
                    String name = f.getName();
                    if (shouldHideProjectItem(name)) continue;
                    JsonObject item = new JsonObject();
                    item.addProperty("name", name);
                    item.addProperty("path", relativize(root, f.getCanonicalFile()));
                    item.addProperty("type", f.isDirectory() ? "dir" : "file");
                    items.add(item);
                    count++;
                }
            }
            result.add("items", items);
            sendJson(exchange, result, 200);
        }

        private static String queryParam(HttpExchange exchange, String name) throws UnsupportedEncodingException {
            String query = exchange.getRequestURI().getQuery();
            if (query == null) return "";
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && name.equals(kv[0])) {
                    return java.net.URLDecoder.decode(kv[1], "UTF-8");
                }
            }
            return "";
        }

        private static boolean isInside(File root, File file) throws IOException {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        }

        private static String relativize(File root, File file) {
            Path rootPath = root.toPath();
            Path filePath = file.toPath();
            if (rootPath.equals(filePath)) return "";
            return rootPath.relativize(filePath).toString().replace(File.separatorChar, '/');
        }

        private static boolean shouldHideProjectItem(String name) {
            return ".git".equals(name) || "target".equals(name) || "node_modules".equals(name)
                    || ".idea".equals(name) || ".svn".equals(name) || "__pycache__".equals(name)
                    || name.endsWith(".bak");
        }
    }

    static class ConfirmHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String body = readBody(exchange);
            JsonObject req = GSON.fromJson(body, JsonObject.class);
            String runId = req.has("runId") ? req.get("runId").getAsString() : "";
            String key = req.has("key") ? req.get("key").getAsString() : "";
            boolean approved = req.has("approved") && req.get("approved").getAsBoolean();
            boolean approveAll = req.has("approveAll") && req.get("approveAll").getAsBoolean();
            if (runId.isEmpty() || key.isEmpty()) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"runId和key不能为空\"}", JsonObject.class), 400);
                return;
            }
            boolean resolved = runRegistry.confirm(runId, key, approved, approveAll);
            JsonObject result = new JsonObject();
            result.addProperty("ok", resolved);
            sendJson(exchange, result, 200);
        }
    }

    static class CancelHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            JsonObject req = GSON.fromJson(readBody(exchange), JsonObject.class);
            String runId = req != null && req.has("runId") ? req.get("runId").getAsString() : "";
            if (runId.isEmpty()) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"runId不能为空\"}", JsonObject.class), 400);
                return;
            }
            boolean cancelled = runRegistry.cancel(runId);
            JsonObject result = new JsonObject();
            result.addProperty("success", cancelled);
            sendJson(exchange, result, cancelled ? 200 : 404);
        }
    }

    static class RollbackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { handleOptions(exchange); return; }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            JsonObject req = GSON.fromJson(readBody(exchange), JsonObject.class);
            String runId = req != null && req.has("runId") ? req.get("runId").getAsString() : "";
            if (runId.isEmpty()) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"runId不能为空\"}", JsonObject.class), 400);
                return;
            }
            try {
                List<String> restored;
                AgentRun registered = runRegistry.get(runId);
                String sessionId = req.has("sessionId") ? req.get("sessionId").getAsString() : "";
                if (registered != null) {
                    restored = runRegistry.rollback(runId);
                    if (!sessionId.isEmpty()) {
                        sessionManager.saveTaskState(sessionId, registered.getTaskState().toPersistentJson());
                    }
                } else {
                    JsonObject persisted = sessionManager.loadTaskState(sessionId);
                    JsonObject session = sessionManager.getSessionInfo(sessionId);
                    if (persisted == null || session == null || !persisted.has("runId")
                            || !runId.equals(persisted.get("runId").getAsString())) {
                        throw new IOException("任务不存在、已过期或会话不匹配");
                    }
                    File cwd = requireOrAuthorizeWorkspace(new File(session.get("cwd").getAsString()));
                    restored = new ChangeJournal(runId, cwd.getPath()).rollback();
                    TaskState restoredState = TaskState.fromJson(persisted);
                    restoredState.markRolledBack();
                    sessionManager.saveTaskState(sessionId, restoredState.toPersistentJson());
                }
                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                JsonArray files = new JsonArray();
                for (String file : restored) files.add(file);
                result.add("restoredFiles", files);
                sendJson(exchange, result, 200);
            } catch (IOException e) {
                JsonObject result = new JsonObject();
                result.addProperty("error", e.getMessage());
                sendJson(exchange, result, 400);
            }
        }
    }

    static class SessionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleOptions(exchange);
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                JsonArray sessions = new JsonArray();
                for (JsonObject s : sessionManager.listSessions()) {
                    sessions.add(s);
                }
                JsonObject result = new JsonObject();
                result.add("sessions", sessions);
                result.addProperty("currentSessionId", agent.getCurrentSessionId() != null ? agent.getCurrentSessionId() : "");
                synchronized (agentLock) {
                    synchronized (agentLock) {
                        result.addProperty("contextChars", agent.getContextCharCount());
                    }
                }
                sendJson(exchange, result, 200);
            } else if ("POST".equals(method)) {
                String body = readBody(exchange);
                JsonObject req = GSON.fromJson(body, JsonObject.class);
                String action = req.has("action") ? req.get("action").getAsString() : "";

                if ("new".equals(action)) {
                    synchronized (agentLock) {
                        agent.newConversation();
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("message", "已创建新会话");
                    result.addProperty("contextChars", agent.getContextCharCount());
                    sendJson(exchange, result, 200);
                } else if ("resume".equals(action)) {
                    String sessionId = req.has("sessionId") ? req.get("sessionId").getAsString() : "";
                    boolean loaded;
                    synchronized (agentLock) {
                        if (req.has("cwd") && !req.get("cwd").getAsString().isEmpty()) {
                            try {
                                agent.setWorkspaceDir(requireOrAuthorizeWorkspace(new File(req.get("cwd").getAsString())).getPath());
                            } catch (IOException e) {
                                JsonObject result = new JsonObject();
                                result.addProperty("error", e.getMessage());
                                sendJson(exchange, result, 400);
                                return;
                            }
                        }
                        loaded = agent.loadConversation(sessionId);
                    }
                    if (loaded) {
                        List<JsonObject> messages = sessionManager.loadSession(sessionId);
                        JsonArray messageArray = new JsonArray();
                        for (JsonObject msg : messages) {
                            messageArray.add(msg);
                        }
                        JsonObject result = new JsonObject();
                        result.addProperty("success", true);
                        result.addProperty("message", "已恢复会话 " + sessionId);
                        result.add("messages", messageArray);
                        JsonObject taskState = sessionManager.loadTaskState(sessionId);
                        if (taskState != null) {
                            TaskState restoredTask = TaskState.fromJson(taskState);
                            result.add("task", restoredTask == null ? new JsonObject() : restoredTask.toJson());
                        }
                        result.addProperty("sessionId", sessionId);
                        result.addProperty("cwd", agent.getWorkspaceDir());
                        synchronized (agentLock) {
                            result.addProperty("contextChars", agent.getContextCharCount());
                        }
                        sendJson(exchange, result, 200);
                    } else {
                        sendJson(exchange, GSON.fromJson("{\"error\":\"未找到会话\"}", JsonObject.class), 404);
                    }
                } else if ("clear".equals(action)) {
                    synchronized (agentLock) {
                        agent.clearConversation();
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("message", "已清空当前会话上下文");
                    synchronized (agentLock) {
                        result.addProperty("contextChars", agent.getContextCharCount());
                    }
                    sendJson(exchange, result, 200);
                } else if ("delete".equals(action)) {
                    String sessionId = req.has("sessionId") ? req.get("sessionId").getAsString() : "";
                    if (sessionId.isEmpty()) {
                        sendJson(exchange, GSON.fromJson("{\"error\":\"缺少sessionId\"}", JsonObject.class), 400);
                        return;
                    }
                    synchronized (agentLock) {
                        sessionManager.deleteSession(sessionId);
                        if (sessionId.equals(agent.getCurrentSessionId())) {
                            agent.newConversation();
                        }
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("message", "已删除会话 " + sessionId);
                    sendJson(exchange, result, 200);
                } else if ("rename".equals(action)) {
                    String sessionId = req.has("sessionId") ? req.get("sessionId").getAsString() : "";
                    String title = req.has("title") ? req.get("title").getAsString().trim() : "";
                    if (sessionId.isEmpty()) {
                        sendJson(exchange, GSON.fromJson("{\"error\":\"缺少sessionId\"}", JsonObject.class), 400);
                        return;
                    }
                    if (title.isEmpty()) {
                        sendJson(exchange, GSON.fromJson("{\"error\":\"标题不能为空\"}", JsonObject.class), 400);
                        return;
                    }
                    sessionManager.updateTitle(sessionId, title);
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("message", "已重命名会话");
                    sendJson(exchange, result, 200);
                } else {
                    sendJson(exchange, GSON.fromJson("{\"error\":\"未知操作\"}", JsonObject.class), 400);
                }
            }
        }
    }


    static class HomePageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                addCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            byte[] html = readHtml("home.html");
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        }
    }

    static class ClaudeConversationsPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                addCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            byte[] html = readHtml("claude-conversations.html");
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        }
    }

    static class ClaudeHistoryPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                addCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            byte[] html = readHtml("claude-history.html");
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        }
    }

    static class FlowchartPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                addCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!checkAuth(exchange)) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"unauthorized\"}", JsonObject.class), 401);
                return;
            }
            byte[] html = readHtml("claude-flowchart.html");
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        final int maxBytes = 1024 * 1024;
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new IOException("请求体过大，上限 1MB");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readHtml(String name) throws IOException {
        Path devPath = Paths.get("src/main/resources/web/" + name);
        if (Files.exists(devPath)) {
            return Files.readAllBytes(devPath);
        }
        try (InputStream in = WebStart.class.getResourceAsStream("/web/" + name)) {
            if (in != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[4096];
                int n;
                while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
                return buf.toByteArray();
            }
        }
        throw new IOException("找不到 " + name);
    }

    private static final class LoginAttempt {
        private int failures;
        private long lockedUntil;
        private long lastFailureAt;

        synchronized void recordFailure(long now) {
            long resetAfter = TimeUnit.SECONDS.toMillis(ToolConstants.LOGIN_LOCKOUT_SECONDS);
            if ((lockedUntil > 0 && now >= lockedUntil)
                    || (lastFailureAt > 0 && now - lastFailureAt >= resetAfter)) {
                failures = 0;
                lockedUntil = 0;
            }
            failures++;
            lastFailureAt = now;
            if (failures >= ToolConstants.LOGIN_MAX_FAILURES) {
                lockedUntil = now + TimeUnit.SECONDS.toMillis(ToolConstants.LOGIN_LOCKOUT_SECONDS);
            }
        }

        synchronized long retryAfterSeconds(long now) {
            if (lockedUntil <= now) {
                if (lockedUntil > 0) {
                    failures = 0;
                    lockedUntil = 0;
                }
                return 0;
            }
            return Math.max(1, TimeUnit.MILLISECONDS.toSeconds(lockedUntil - now) + 1);
        }

        synchronized boolean isExpiredAndIdle(long now) {
            long resetAfter = TimeUnit.SECONDS.toMillis(ToolConstants.LOGIN_LOCKOUT_SECONDS);
            return (lockedUntil > 0 && lockedUntil <= now)
                    || (lockedUntil == 0 && lastFailureAt > 0 && now - lastFailureAt >= resetAfter);
        }
    }
}
