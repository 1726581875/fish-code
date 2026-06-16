package org.example;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.core.*;
import org.example.tool.ToolConstants;

public class WebStart {

    private static final Gson GSON = new Gson();
    private static final int PORT = 10000;
    private static HttpServer server;
    private static TerminalStart agent;
    private static SessionManager sessionManager;
    private static String webUser;
    private static String webPassword;
    private static final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> loginAttempts = new ConcurrentHashMap<>();
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
        webUser = ConfigLoader.getConfigString("web_user", "WEB_USER", "admin");
        webPassword = ConfigLoader.getConfigString("web_password", "WEB_PASSWORD", "fish2024");

        if (apiKey.isEmpty()) {
            System.out.println("请配置 api_key (环境变量 DEEPSEEK_API_KEY 或 ~/.fish-code/config.json)");
            return;
        }

        sessionManager = new SessionManager();
        agent = new TerminalStart(apiUrl, apiKey, model, sessionManager);
        agent.setMode(AgentMode.CONFIRM);

        String claudeDir = ConfigLoader.getConfigString("claude_dir", "CLAUDE_DIR", "");
        claudeReader = new ClaudeHistoryReader(claudeDir);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null) server.stop(2);
            cleanupExecutor.shutdownNow();
        }));

        cleanupExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            tokens.entrySet().removeIf(e -> now >= e.getValue());
        }, 1, 1, TimeUnit.HOURS);

        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext("/", new IndexHandler());
            server.createContext("/login", new LoginHandler());
            server.createContext("/logout", new LogoutHandler());
            server.createContext("/chat", new ChatHandler());
            server.createContext("/mode", new ModeHandler());
            server.createContext("/model", new ModelHandler());
            server.createContext("/cwd", new CwdHandler());
            server.createContext("/cwd/list", new CwdListHandler());
            server.createContext("/confirm", new ConfirmHandler());
            server.createContext("/config", new ConfigHandler());
            server.createContext("/sessions", new SessionsHandler());
            server.createContext("/health", new HealthHandler());
            server.createContext("/home", new HomePageHandler());
            server.createContext("/claude-history", new ClaudeHistoryPageHandler());
            server.createContext("/claude-conversations", new ClaudeConversationsPageHandler());
            server.createContext("/claude-flowchart", new FlowchartPageHandler());
            ClaudeSessionsHandler claudeHandler = new ClaudeSessionsHandler(claudeReader);
            server.createContext("/claude-api", claudeHandler);
            server.setExecutor(new ThreadPoolExecutor(16, 16, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100), r -> {
                        Thread t = new Thread(r, "http-worker");
                        t.setDaemon(true);
                        return t;
                    }));
            server.start();

            System.out.println("\n  Fish Code Web 已启动: \u001B[36mhttp://localhost:" + PORT + "\u001B[0m");
            System.out.println("  默认账号: " + webUser + " / " + webPassword);
            System.out.println("  按 Ctrl+C 退出\n");
        } catch (IOException e) {
            System.err.println("启动失败: " + e.getMessage());
        }
    }

    private static boolean checkAuth(HttpExchange exchange) {
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

    private static void sendJson(HttpExchange exchange, JsonObject json, int code) throws IOException {
        addCorsHeaders(exchange);
        byte[] resp = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, Cookie");
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
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            String remoteAddr = exchange.getRemoteAddress().getAddress().getHostAddress();
            AtomicInteger attempts = loginAttempts.computeIfAbsent(remoteAddr, k -> new AtomicInteger(0));
            if (attempts.get() >= 10) {
                sendJson(exchange, GSON.fromJson("{\"success\":false,\"error\":\"登录尝试过多，请稍后再试\"}", JsonObject.class), 429);
                return;
            }

            String body = readBody(exchange);
            JsonObject req = GSON.fromJson(body, JsonObject.class);
            String user = req.has("username") ? req.get("username").getAsString() : "";
            String pass = req.has("password") ? req.get("password").getAsString() : "";

            JsonObject result = new JsonObject();
            if (webUser.equals(user) && webPassword.equals(pass)) {
                attempts.set(0);
                String token = UUID.randomUUID().toString();
                long expireMs = (long) ToolConstants.TOKEN_EXPIRE_HOURS * 60 * 60 * 1000;
                tokens.put(token, System.currentTimeMillis() + expireMs);
                addCorsHeaders(exchange);
                exchange.getResponseHeaders().add("Set-Cookie",
                    "fish_token=" + token + "; Path=/; HttpOnly");
                result.addProperty("success", true);
                result.addProperty("token", token);
            } else {
                attempts.incrementAndGet();
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
            if (message.length() > 50000) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"消息过长，上限50000字符\"}", JsonObject.class), 400);
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
                TerminalStart.setRequestOverride(reqModel, resolvedApiUrl, resolvedApiKey);
            }
            if (reqCwd != null && !reqCwd.isEmpty()) {
                TerminalStart.setCurrentCwd(reqCwd);
            }
            TerminalStart.setApproveAllRemaining(false);
            try {
                if (stream) {
                    handleStream(exchange, message);
                } else {
                    handleSync(exchange, message);
                }
            } finally {
                TerminalStart.clearRequestOverride();
                TerminalStart.clearCurrentCwd();
            }
        }

        private void handleStream(HttpExchange exchange, String message) throws IOException {
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            OutputStream os = exchange.getResponseBody();

            try {
                StreamCallback sseCallback = new StreamCallback() {
                    @Override
                    public void onToken(String token) {
                        try {
                            JsonObject evt = new JsonObject();
                            evt.addProperty("type", "token");
                            evt.addProperty("content", token);
                            String data = "data: " + GSON.toJson(evt) + "\n\n";
                            os.write(data.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } catch (IOException ignored) {}
                    }

                    @Override
                    public void onThinking(String text) {}

                    @Override
                    public void onToolCall(String fnName, String fnArgs, String status) {
                        try {
                            JsonObject evt = new JsonObject();
                            evt.addProperty("type", "tool_call");
                            evt.addProperty("name", fnName);
                            evt.addProperty("args", fnArgs);
                            evt.addProperty("status", status);
                            String data = "data: " + GSON.toJson(evt) + "\n\n";
                            os.write(data.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } catch (IOException ignored) {}
                    }

                    @Override
                    public void onConfirmRequired(String confirmKey, String fnName, String fnArgs) {
                        try {
                            JsonObject evt = new JsonObject();
                            evt.addProperty("type", "confirm_required");
                            evt.addProperty("confirmKey", confirmKey);
                            evt.addProperty("name", fnName);
                            evt.addProperty("args", fnArgs);
                            String data = "data: " + GSON.toJson(evt) + "\n\n";
                            os.write(data.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } catch (IOException ignored) {}
                    }

                    @Override
                    public void onComplete(ChatResult result) {
                        try {
                            JsonObject doneEvent = new JsonObject();
                            doneEvent.addProperty("type", "done");
                            doneEvent.addProperty("reply", result.getReply());
                            doneEvent.addProperty("durationMs", result.getDurationMs());
                            doneEvent.addProperty("contextTokens", result.getContextTokens());
                            doneEvent.addProperty("mode", agent.getMode().label());
                            doneEvent.addProperty("sessionId", agent.getCurrentSessionId() != null ? agent.getCurrentSessionId() : "");
                            String data = "data: " + GSON.toJson(doneEvent) + "\n[END]\n\n";
                            os.write(data.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } catch (IOException ignored) {}
                    }

                    @Override
                    public void onError(String error) {
                        try {
                            JsonObject errorEvent = new JsonObject();
                            errorEvent.addProperty("type", "error");
                            errorEvent.addProperty("error", error);
                            String data = "data: " + GSON.toJson(errorEvent) + "\n\n";
                            os.write(data.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } catch (IOException ignored) {}
                    }
                };
                agent.chatStream(message, sseCallback);
            } catch (Exception e) {
                JsonObject errorEvent = new JsonObject();
                errorEvent.addProperty("type", "error");
                errorEvent.addProperty("error", e.getMessage());
                String data = "data: " + GSON.toJson(errorEvent) + "\n\n";
                try {
                    os.write(data.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {}
            } finally {
                try { os.close(); } catch (IOException ignored) {}
            }
        }

        private void handleSync(HttpExchange exchange, String message) throws IOException {
            JsonObject result = new JsonObject();
            try {
                ChatResult chatResult = agent.chat(message);
                result.addProperty("reply", chatResult.getReply() != null ? chatResult.getReply() : "");
                result.addProperty("durationMs", chatResult.getDurationMs());
                result.addProperty("contextTokens", chatResult.getContextTokens());
            } catch (Exception e) {
                result.addProperty("reply", "错误: " + e.getMessage());
                result.addProperty("durationMs", 0);
                result.addProperty("contextTokens", 0);
            }

            result.addProperty("toolOutput", "");
            result.addProperty("mode", agent.getMode().label());
            result.addProperty("sessionId", agent.getCurrentSessionId() != null ? agent.getCurrentSessionId() : "");
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
                sendJson(exchange, result, 200);
            } else if ("POST".equals(method)) {
                String body = readBody(exchange);
                JsonObject req = GSON.fromJson(body, JsonObject.class);
                String modeName = req.has("mode") ? req.get("mode").getAsString() : "";
                switch (modeName) {
                    case "plan":    agent.setMode(AgentMode.PLAN);    break;
                    case "auto":    agent.setMode(AgentMode.AUTO);    break;
                    case "confirm": agent.setMode(AgentMode.CONFIRM); break;
                    default: sendJson(exchange, GSON.fromJson("{\"error\":\"invalid mode\"}", JsonObject.class), 400); return;
                }
                JsonObject result = new JsonObject();
                result.addProperty("mode", agent.getMode().label());
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
                if (req.has("model") && !req.get("model").getAsString().isEmpty()) {
                    agent.setModel(req.get("model").getAsString());
                }
                if (req.has("apiUrl") && !req.get("apiUrl").getAsString().isEmpty()) {
                    agent.setApiUrl(req.get("apiUrl").getAsString());
                }
                if (req.has("apiKey") && !req.get("apiKey").getAsString().isEmpty()) {
                    agent.setApiKey(req.get("apiKey").getAsString());
                }
                JsonObject result = new JsonObject();
                result.addProperty("model", agent.getModel());
                result.addProperty("apiUrl", agent.getApiUrl());
                sendJson(exchange, result, 200);
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
            JsonObject config = ConfigLoader.loadConfig();
            JsonObject result = new JsonObject();
            if (config.has("models")) {
                result.add("models", config.get("models"));
            } else {
                result.add("models", new JsonArray());
            }
            result.addProperty("model", agent.getModel());
            result.addProperty("apiUrl", agent.getApiUrl());
            result.addProperty("apiKey", agent.getApiKey());
            if (config.has("cur_model")) {
                result.addProperty("cur_model", config.get("cur_model").getAsString());
            }
            sendJson(exchange, result, 200);
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
                java.io.File dir = new java.io.File(newCwd);
                if (!dir.exists() || !dir.isDirectory()) {
                    sendJson(exchange, GSON.fromJson("{\"error\":\"目录不存在: " + newCwd + "\"}", JsonObject.class), 400);
                    return;
                }
                JsonObject result = new JsonObject();
                result.addProperty("cwd", dir.getAbsolutePath());
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
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2 && "path".equals(kv[0])) {
                        path = java.net.URLDecoder.decode(kv[1], "UTF-8");
                        break;
                    }
                }
            }
            if (path.isEmpty()) {
                path = TerminalStart.getCurrentCwd();
            }
            if (path.matches("^[A-Za-z]:$")) {
                path = path + "\\";
            }
            java.io.File dir = new java.io.File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"目录不存在\"}", JsonObject.class), 400);
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("path", dir.getAbsolutePath());
            JsonArray dirs = new JsonArray();
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.isDirectory() && !f.getName().startsWith(".")) {
                        dirs.add(f.getName());
                    }
                }
            }
            result.add("dirs", dirs);
            sendJson(exchange, result, 200);
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
            String key = req.has("key") ? req.get("key").getAsString() : "";
            boolean approved = req.has("approved") && req.get("approved").getAsBoolean();
            boolean approveAll = req.has("approveAll") && req.get("approveAll").getAsBoolean();
            if (key.isEmpty()) {
                sendJson(exchange, GSON.fromJson("{\"error\":\"key不能为空\"}", JsonObject.class), 400);
                return;
            }
            if (approveAll) {
                TerminalStart.setApproveAllRemaining(true);
            }
            boolean resolved = TerminalStart.resolveConfirmation(key, approved);
            JsonObject result = new JsonObject();
            result.addProperty("ok", resolved);
            sendJson(exchange, result, 200);
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
                sendJson(exchange, result, 200);
            } else if ("POST".equals(method)) {
                String body = readBody(exchange);
                JsonObject req = GSON.fromJson(body, JsonObject.class);
                String action = req.has("action") ? req.get("action").getAsString() : "";

                if ("new".equals(action)) {
                    agent.newConversation();
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("message", "已创建新会话");
                    sendJson(exchange, result, 200);
                } else if ("resume".equals(action)) {
                    String sessionId = req.has("sessionId") ? req.get("sessionId").getAsString() : "";
                    if (agent.loadConversation(sessionId)) {
                        JsonObject result = new JsonObject();
                        result.addProperty("success", true);
                        result.addProperty("message", "已恢复会话 " + sessionId);
                        sendJson(exchange, result, 200);
                    } else {
                        sendJson(exchange, GSON.fromJson("{\"error\":\"未找到会话\"}", JsonObject.class), 404);
                    }
                } else if ("clear".equals(action)) {
                    agent.clearConversation();
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("message", "已清空当前会话上下文");
                    sendJson(exchange, result, 200);
                } else if ("delete".equals(action)) {
                    String sessionId = req.has("sessionId") ? req.get("sessionId").getAsString() : "";
                    if (sessionId.isEmpty()) {
                        sendJson(exchange, GSON.fromJson("{\"error\":\"缺少sessionId\"}", JsonObject.class), 400);
                        return;
                    }
                    sessionManager.deleteSession(sessionId);
                    if (sessionId.equals(agent.getCurrentSessionId())) {
                        agent.newConversation();
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("message", "已删除会话 " + sessionId);
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
            byte[] html = readHtml("claude-flowchart.html");
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStreamReader r = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(r)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
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
}
