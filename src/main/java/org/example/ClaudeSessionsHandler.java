package org.example;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class ClaudeSessionsHandler implements HttpHandler {

    private final ClaudeHistoryReader reader;
    private final Gson gson = new Gson();

    public ClaudeSessionsHandler(ClaudeHistoryReader reader) {
        this.reader = reader;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        addCorsHeaders(exchange);
        String path = exchange.getRequestURI().getPath();

        try {
            if ("/claude-api/projects".equals(path)) {
                handleProjects(exchange);
            } else if ("/claude-api/sessions".equals(path)) {
                handleSessions(exchange);
            } else if ("/claude-api/session".equals(path)) {
                handleSession(exchange);
            } else if ("/claude-api/search".equals(path)) {
                handleSearch(exchange);
            } else if ("/claude-api/check".equals(path)) {
                handleCheck(exchange);
            } else {
                sendError(exchange, 404, "未知端点");
            }
        } catch (Exception e) {
            sendError(exchange, 500, "服务器错误: " + e.getMessage());
        }
    }

    private void handleCheck(HttpExchange exchange) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("exists", reader.exists());
        result.addProperty("path", reader.getClaudeDir().toString());
        sendJson(exchange, result, 200);
    }

    private void handleProjects(HttpExchange exchange) throws IOException {
        JsonArray projects = new JsonArray();
        for (JsonObject p : reader.listProjects()) {
            projects.add(p);
        }
        JsonObject result = new JsonObject();
        result.add("projects", projects);
        sendJson(exchange, result, 200);
    }

    private void handleSessions(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String projectHash = getParam(query, "project");
        if (projectHash == null || projectHash.isEmpty()) {
            sendError(exchange, 400, "缺少 project 参数");
            return;
        }
        JsonArray sessions = new JsonArray();
        for (JsonObject s : reader.listSessions(projectHash)) {
            sessions.add(s);
        }
        JsonObject result = new JsonObject();
        result.add("sessions", sessions);
        result.addProperty("project", projectHash);
        sendJson(exchange, result, 200);
    }

    private void handleSession(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String projectHash = getParam(query, "project");
        String sessionId = getParam(query, "id");
        if (projectHash == null || sessionId == null) {
            sendError(exchange, 400, "缺少 project 或 id 参数");
            return;
        }
        JsonObject session = reader.getSession(projectHash, sessionId);
        if (session == null) {
            sendError(exchange, 404, "会话不存在");
            return;
        }
        sendJson(exchange, session, 200);
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String keyword = getParam(query, "q");
        if (keyword == null || keyword.trim().isEmpty()) {
            sendError(exchange, 400, "缺少搜索关键词");
            return;
        }
        JsonArray results = new JsonArray();
        for (JsonObject r : reader.searchSessions(keyword.trim())) {
            results.add(r);
        }
        JsonObject result = new JsonObject();
        result.add("results", results);
        result.addProperty("total", results.size());
        sendJson(exchange, result, 200);
    }

    private String getParam(String query, String name) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) {
                try {
                    return URLDecoder.decode(kv[1], "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private void sendJson(HttpExchange exchange, JsonObject json, int code) throws IOException {
        byte[] resp = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        JsonObject err = new JsonObject();
        err.addProperty("error", message);
        sendJson(exchange, err, code);
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }
}
