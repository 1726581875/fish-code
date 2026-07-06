package org.example.core;

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

    private ClaudeHistoryReader getReader(HttpExchange exchange) {
        String dir = getParam(exchange.getRequestURI().getQuery(), "dir");
        if (dir != null && !dir.trim().isEmpty()) {
            return new ClaudeHistoryReader(dir.trim());
        }
        return reader;
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
            } else if ("/claude-api/session-meta".equals(path)) {
                handleSessionMeta(exchange);
            } else if ("/claude-api/search".equals(path)) {
                handleSearch(exchange);
            } else if ("/claude-api/check".equals(path)) {
                handleCheck(exchange);
            } else if ("/claude-api/export".equals(path)) {
                handleExport(exchange);
            } else if ("/claude-api/history".equals(path)) {
                handleHistory(exchange);
            } else {
                sendError(exchange, 404, "未知端点");
            }
        } catch (Exception e) {
            sendError(exchange, 500, "服务器错误: " + e.getMessage());
        }
    }

    private void handleCheck(HttpExchange exchange) throws IOException {
        ClaudeHistoryReader r = getReader(exchange);
        JsonObject result = new JsonObject();
        result.addProperty("exists", r.exists());
        result.addProperty("path", r.getClaudeDir().toString());
        sendJson(exchange, result, 200);
    }

    private void handleProjects(HttpExchange exchange) throws IOException {
        ClaudeHistoryReader r = getReader(exchange);
        JsonArray projects = new JsonArray();
        for (JsonObject p : r.listProjects()) {
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
        String after = getParam(query, "after");
        String before = getParam(query, "before");
        ClaudeHistoryReader r = getReader(exchange);
        JsonArray sessions = new JsonArray();
        for (JsonObject s : r.listSessions(projectHash, after, before)) {
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
        int offset = getIntParam(query, "offset", 0);
        int limit = getIntParam(query, "limit", 50);
        ClaudeHistoryReader r = getReader(exchange);
        JsonObject session = r.getSession(projectHash, sessionId, offset, limit);
        if (session == null) {
            sendError(exchange, 404, "会话不存在");
            return;
        }
        sendJson(exchange, session, 200);
    }

    private void handleSessionMeta(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String projectHash = getParam(query, "project");
        String sessionId = getParam(query, "id");
        if (projectHash == null || sessionId == null) {
            sendError(exchange, 400, "缺少 project 或 id 参数");
            return;
        }
        ClaudeHistoryReader r = getReader(exchange);
        JsonObject meta = r.getSessionMeta(projectHash, sessionId);
        if (meta == null) {
            sendError(exchange, 404, "会话不存在");
            return;
        }
        sendJson(exchange, meta, 200);
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String keyword = getParam(query, "q");
        if (keyword == null || keyword.trim().isEmpty()) {
            sendError(exchange, 400, "缺少搜索关键词");
            return;
        }
        int maxResults = getIntParam(query, "limit", 50);
        ClaudeHistoryReader r = getReader(exchange);
        JsonArray results = new JsonArray();
        for (JsonObject s : r.searchSessions(keyword.trim(), maxResults)) {
            results.add(s);
        }
        JsonObject result = new JsonObject();
        result.add("results", results);
        result.addProperty("total", results.size());
        sendJson(exchange, result, 200);
    }

    private void handleExport(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String projectHash = getParam(query, "project");
        String sessionId = getParam(query, "id");
        if (projectHash == null || sessionId == null) {
            sendError(exchange, 400, "缺少 project 或 id 参数");
            return;
        }
        ClaudeHistoryReader r = getReader(exchange);
        String md = r.exportSessionMarkdown(projectHash, sessionId);
        if (md == null) {
            sendError(exchange, 404, "会话不存在");
            return;
        }
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/markdown; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + sessionId + ".md\"");
        byte[] resp = md.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private void handleHistory(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        int limit = getIntParam(query, "limit", 100);
        String project = getParam(query, "project");
        ClaudeHistoryReader r = getReader(exchange);
        JsonArray items = new JsonArray();
        for (JsonObject item : r.listPromptHistory(limit, project)) {
            items.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("items", items);
        result.addProperty("total", items.size());
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

    private int getIntParam(String query, String name, int defaultValue) {
        String val = getParam(query, name);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (isAllowedOrigin(origin, exchange)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private boolean isAllowedOrigin(String origin, HttpExchange exchange) {
        if (origin == null || origin.trim().isEmpty()) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && origin.equals("http://" + host)) return true;
        return origin.startsWith("http://localhost:")
                || origin.startsWith("http://127.0.0.1:")
                || origin.startsWith("http://[::1]:");
    }
}
