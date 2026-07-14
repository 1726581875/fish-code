package org.example.core;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import junit.framework.TestCase;
import org.example.TerminalStart;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class AgentExecutionTest extends TestCase {
    public void testMultiStepTaskCannotCompleteUntilVerificationPasses() throws Exception {
        String originalHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("fish-agent-home-");
        Path workspace = Files.createTempDirectory("fish-agent-workspace-");
        HttpServer server = null;
        try {
            System.setProperty("user.home", home.toString());
            AtomicInteger requests = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat", exchange -> {
                int request = requests.incrementAndGet();
                while (exchange.getRequestBody().read() != -1) {}
                JsonObject response = responseFor(request);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();

            SessionManager sessions = new SessionManager();
            TerminalStart agent = new TerminalStart("http://127.0.0.1:" + server.getAddress().getPort() + "/chat",
                    "test-key", "test-model", sessions);
            agent.setMode(AgentMode.AUTO);
            agent.setWorkspaceDir(workspace.toString());
            AgentRun run = new AgentRun("integration-run", "create and verify file", "test-model",
                    agent.getApiUrl(), "test-key", workspace.toString());

            ChatResult result = agent.chat("创建 result.txt 并验证内容", run);

            assertEquals("task complete", result.getReply());
            assertEquals(4, requests.get());
            assertEquals("hello\n", new String(Files.readAllBytes(workspace.resolve("result.txt")), StandardCharsets.UTF_8));
            assertEquals(TaskState.Phase.COMPLETE, run.getTaskState().getPhase());
            assertTrue(run.getTaskState().isVerifiedAfterLastWrite());

            assertEquals(1, run.getChangeJournal().rollback().size());
            assertFalse(Files.exists(workspace.resolve("result.txt")));
        } finally {
            if (server != null) server.stop(0);
            System.setProperty("user.home", originalHome);
            deleteTree(workspace);
            deleteTree(home);
        }
    }

    public void testNonStreamingLengthFinishReasonIsInterrupted() throws Exception {
        Path home = Files.createTempDirectory("fish-agent-length-home-");
        Path workspace = Files.createTempDirectory("fish-agent-length-workspace-");
        String originalHome = System.getProperty("user.home");
        HttpServer server = null;
        try {
            System.setProperty("user.home", home.toString());
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat", exchange -> {
                while (exchange.getRequestBody().read() != -1) {}
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                message.addProperty("content", "partial");
                JsonObject choice = new JsonObject();
                choice.add("message", message);
                choice.addProperty("finish_reason", "length");
                JsonArray choices = new JsonArray();
                choices.add(choice);
                JsonObject response = new JsonObject();
                response.add("choices", choices);
                writeJson(exchange, 200, response);
            });
            server.start();
            TerminalStart agent = new TerminalStart(url(server), "key", "model", new SessionManager());
            agent.setWorkspaceDir(workspace.toString());
            AgentRun run = new AgentRun("length-run", "length", "model", agent.getApiUrl(), "key", workspace.toString());
            try {
                agent.chat("respond", run);
                fail("expected interrupted response");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("finish_reason=length"));
            }
            assertEquals(TaskState.Phase.INTERRUPTED, run.getTaskState().getPhase());
        } finally {
            if (server != null) server.stop(0);
            System.setProperty("user.home", originalHome);
            deleteTree(workspace);
            deleteTree(home);
        }
    }

    public void testTransientServerErrorRetriesBeforeAnyToolRuns() throws Exception {
        Path home = Files.createTempDirectory("fish-agent-retry-home-");
        Path workspace = Files.createTempDirectory("fish-agent-retry-workspace-");
        String originalHome = System.getProperty("user.home");
        HttpServer server = null;
        try {
            System.setProperty("user.home", home.toString());
            AtomicInteger requests = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat", exchange -> {
                while (exchange.getRequestBody().read() != -1) {}
                if (requests.incrementAndGet() == 1) {
                    writeJson(exchange, 500, new JsonObject());
                } else {
                    JsonObject message = new JsonObject();
                    message.addProperty("role", "assistant");
                    message.addProperty("content", "recovered");
                    JsonObject choice = new JsonObject();
                    choice.add("message", message);
                    JsonArray choices = new JsonArray();
                    choices.add(choice);
                    JsonObject response = new JsonObject();
                    response.add("choices", choices);
                    writeJson(exchange, 200, response);
                }
            });
            server.start();
            TerminalStart agent = new TerminalStart(url(server), "key", "model", new SessionManager());
            agent.setWorkspaceDir(workspace.toString());
            AgentRun run = new AgentRun("retry-run", "retry", "model", agent.getApiUrl(), "key", workspace.toString());
            assertEquals("recovered", agent.chat("retry", run).getReply());
            assertEquals(2, requests.get());
            assertEquals(TaskState.Phase.COMPLETE, run.getTaskState().getPhase());
        } finally {
            if (server != null) server.stop(0);
            System.setProperty("user.home", originalHome);
            deleteTree(workspace);
            deleteTree(home);
        }
    }

    public void testStreamingEofAfterOutputIsInterruptedWithoutRetry() throws Exception {
        Path home = Files.createTempDirectory("fish-agent-stream-home-");
        Path workspace = Files.createTempDirectory("fish-agent-stream-workspace-");
        String originalHome = System.getProperty("user.home");
        HttpServer server = null;
        try {
            System.setProperty("user.home", home.toString());
            AtomicInteger requests = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat", exchange -> {
                requests.incrementAndGet();
                while (exchange.getRequestBody().read() != -1) {}
                byte[] bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            TerminalStart agent = new TerminalStart(url(server), "key", "model", new SessionManager());
            agent.setWorkspaceDir(workspace.toString());
            AgentRun run = new AgentRun("stream-run", "stream", "model", agent.getApiUrl(), "key", workspace.toString());
            try {
                agent.chatStream("stream", new StreamCallback() {}, run);
                fail("expected stream interruption");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("正常结束标记"));
            }
            assertEquals(1, requests.get());
            assertEquals(TaskState.Phase.INTERRUPTED, run.getTaskState().getPhase());
        } finally {
            if (server != null) server.stop(0);
            System.setProperty("user.home", originalHome);
            deleteTree(workspace);
            deleteTree(home);
        }
    }

    public void testStreamingAcceptsUsageChunkAndDataWithoutSpace() throws Exception {
        Path home = Files.createTempDirectory("fish-agent-stream-usage-home-");
        Path workspace = Files.createTempDirectory("fish-agent-stream-usage-workspace-");
        String originalHome = System.getProperty("user.home");
        HttpServer server = null;
        try {
            System.setProperty("user.home", home.toString());
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat", exchange -> {
                while (exchange.getRequestBody().read() != -1) {}
                String stream = "data:{\"choices\":[],\"usage\":{\"total_tokens\":1}}\n\n"
                        + "data:{\"choices\":[{\"delta\":{\"content\":\"compatible\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data:[DONE]\n\n";
                byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            TerminalStart agent = new TerminalStart(url(server), "key", "model", new SessionManager());
            agent.setWorkspaceDir(workspace.toString());
            AgentRun run = new AgentRun("stream-usage-run", "stream", "model",
                    agent.getApiUrl(), "key", workspace.toString());

            ChatResult result = agent.chatStream("stream", new StreamCallback() {}, run);
            assertEquals("compatible", result.getReply());
            assertEquals(TaskState.Phase.COMPLETE, run.getTaskState().getPhase());
        } finally {
            if (server != null) server.stop(0);
            System.setProperty("user.home", originalHome);
            deleteTree(workspace);
            deleteTree(home);
        }
    }

    public void testDuplicateToolCallIsNotRepeatedAndRoundLimitBlocks() throws Exception {
        Path home = Files.createTempDirectory("fish-agent-limit-home-");
        Path workspace = Files.createTempDirectory("fish-agent-limit-workspace-");
        String originalHome = System.getProperty("user.home");
        HttpServer server = null;
        try {
            System.setProperty("user.home", home.toString());
            AtomicInteger requests = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat", exchange -> {
                requests.incrementAndGet();
                while (exchange.getRequestBody().read() != -1) {}
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                message.add("content", JsonNull.INSTANCE);
                message.add("tool_calls", toolCalls("same-write", "write_file",
                        "{\"path\":\"once.txt\",\"content\":\"once\\n\"}"));
                JsonObject choice = new JsonObject();
                choice.add("message", message);
                JsonArray choices = new JsonArray();
                choices.add(choice);
                JsonObject response = new JsonObject();
                response.add("choices", choices);
                writeJson(exchange, 200, response);
            });
            server.start();
            TerminalStart agent = new TerminalStart(url(server), "key", "model", new SessionManager());
            agent.setMode(AgentMode.AUTO);
            agent.setWorkspaceDir(workspace.toString());
            AgentRun run = new AgentRun("limit-run", "limit", "model", agent.getApiUrl(), "key", workspace.toString());
            ChatResult result = agent.chat("repeat", run);
            assertTrue(result.getReply().contains("最大工具调用轮次"));
            assertEquals(50, requests.get());
            assertEquals(TaskState.Phase.BLOCKED, run.getTaskState().getPhase());
            assertEquals("once\n", new String(Files.readAllBytes(workspace.resolve("once.txt")), StandardCharsets.UTF_8));
            assertEquals(1, run.getChangeJournal().rollback().size());
        } finally {
            if (server != null) server.stop(0);
            System.setProperty("user.home", originalHome);
            deleteTree(workspace);
            deleteTree(home);
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, JsonObject response)
            throws IOException {
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static JsonObject responseFor(int request) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        if (request == 1) {
            message.add("content", JsonNull.INSTANCE);
            message.add("tool_calls", toolCalls("write-1", "write_file",
                    "{\"path\":\"result.txt\",\"content\":\"hello\\n\"}"));
        } else if (request == 2) {
            message.addProperty("content", "文件已经写好。");
        } else if (request == 3) {
            message.add("content", JsonNull.INSTANCE);
            message.add("tool_calls", toolCalls("verify-1", "run_command",
                    "{\"command\":\"test -f result.txt && grep hello result.txt\"}"));
        } else {
            message.addProperty("content", "task complete");
        }
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response;
    }

    private static JsonArray toolCalls(String id, String name, String args) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("arguments", args);
        JsonObject call = new JsonObject();
        call.addProperty("id", id);
        call.addProperty("type", "function");
        call.add("function", function);
        JsonArray calls = new JsonArray();
        calls.add(call);
        return calls;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        }
    }
}
