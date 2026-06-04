package org.example;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class WebAgent {

    private static final Gson GSON = new Gson();
    private static final int PORT = 8080;
    private static DeepSeekAgent agent;
    private static String webUser;
    private static String webPassword;
    private static final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private static final long TOKEN_EXPIRE = 24 * 60 * 60 * 1000L;

    public static void main(String[] args) {
        JsonObject config = loadConfig();

        String apiUrl = getConfigString(config, "api_url", "DEEPSEEK_API_URL",
                "https://api.deepseek.com/chat/completions");
        String apiKey = getConfigString(config, "api_key", "DEEPSEEK_API_KEY", "");
        String model = getConfigString(config, "model", "DEEPSEEK_MODEL", "deepseek-chat");
        webUser = getConfigString(config, "web_user", "WEB_USER", "admin");
        webPassword = getConfigString(config, "web_password", "WEB_PASSWORD", "fish2024");

        if (apiKey.isEmpty()) {
            System.out.println("请配置 api_key (config.json 或环境变量 DEEPSEEK_API_KEY)");
            return;
        }

        agent = new DeepSeekAgent(apiUrl, apiKey, model);
        agent.setMode(AgentMode.PLAN);

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext("/", new IndexHandler());
            server.createContext("/login", new LoginHandler());
            server.createContext("/chat", new ChatHandler());
            server.createContext("/mode", new ModeHandler());
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();

            System.out.println("\n  Fish Code Web 已启动: \u001B[36mhttp://localhost:" + PORT + "\u001B[0m");
            System.out.println("  默认账号: " + webUser + " / " + webPassword);
            System.out.println("  按 Ctrl+C 退出\n");
        } catch (IOException e) {
            System.err.println("启动失败: " + e.getMessage());
        }
    }

    private static boolean checkAuth(HttpExchange exchange) {
        String token = null;

        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            token = authHeaders.get(0).replace("Bearer ", "");
        }

        if (token == null) {
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2 && "token".equals(kv[0])) {
                        token = kv[1];
                        break;
                    }
                }
            }
        }

        if (token != null) {
            Long expire = tokens.get(token);
            if (expire != null && System.currentTimeMillis() < expire) {
                return true;
            }
        }
        return false;
    }

    private static String extractToken(HttpExchange exchange) {
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            return authHeaders.get(0).replace("Bearer ", "");
        }
        return null;
    }

    private static void sendJson(HttpExchange exchange, JsonObject json, int code) throws IOException {
        byte[] resp = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    static class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkAuth(exchange)) {
                byte[] html = readHtml("login.html");
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, html.length);
                exchange.getResponseBody().write(html);
                exchange.close();
                return;
            }
            byte[] html = readHtml("index.html");
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String body = readBody(exchange);
            JsonObject req = GSON.fromJson(body, JsonObject.class);
            String user = req.get("username").getAsString();
            String pass = req.get("password").getAsString();

            JsonObject result = new JsonObject();
            if (webUser.equals(user) && webPassword.equals(pass)) {
                String token = UUID.randomUUID().toString();
                tokens.put(token, System.currentTimeMillis() + TOKEN_EXPIRE);
                result.addProperty("success", true);
                result.addProperty("token", token);
            } else {
                result.addProperty("success", false);
            }
            sendJson(exchange, result, 200);
        }
    }

    static class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
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
            String message = req.get("message").getAsString();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream capture = new PrintStream(baos, true, "UTF-8");
            PrintStream original = System.out;
            System.setOut(capture);

            JsonObject result = new JsonObject();
            try {
                String reply = agent.chat(message);
                result.addProperty("reply", reply != null ? reply : "");
            } catch (Exception e) {
                result.addProperty("reply", "错误: " + e.getMessage());
            } finally {
                System.setOut(original);
            }

            String toolOutput = baos.toString("UTF-8");
            result.addProperty("toolOutput", toolOutput);
            result.addProperty("mode", agent.getMode().label());
            sendJson(exchange, result, 200);
        }
    }

    static class ModeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
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
                String modeName = req.get("mode").getAsString();
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

    private static JsonObject loadConfig() {
        try (InputStream in = WebAgent.class.getResourceAsStream("/config.json")) {
            if (in != null) {
                byte[] bytes = new byte[in.available()];
                in.read(bytes);
                String s = new String(bytes, StandardCharsets.UTF_8);
                return GSON.fromJson(s, JsonObject.class);
            }
        } catch (IOException ignored) {}
        return new JsonObject();
    }

    private static String getConfigString(JsonObject config, String key, String envKey, String def) {
        if (config.has(key) && !config.get(key).isJsonNull()) return config.get(key).getAsString();
        String v = System.getenv(envKey);
        if (v != null && !v.trim().isEmpty()) return v;
        return def;
    }

    private static byte[] readHtml(String name) throws IOException {
        Path devPath = Paths.get("src/main/resources/web/" + name);
        if (Files.exists(devPath)) {
            return Files.readAllBytes(devPath);
        }
        try (InputStream in = WebAgent.class.getResourceAsStream("/web/" + name)) {
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
