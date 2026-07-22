package org.example.tool;

import com.google.gson.JsonObject;
import junit.framework.TestCase;
import org.example.TerminalStart;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class AgentToolTest extends TestCase {

    public void testUserInputRequestBuildsTwoOrThreeDistinctOptions() {
        JsonObject args = new JsonObject();
        args.addProperty("question", "需要实现哪个范围？");
        args.addProperty("option1", "完整实现");
        args.addProperty("option1Description", "覆盖全部入口和验证");
        args.addProperty("option2", "最小改动");
        ToolResult result = new RequestUserInputTool().executeDetailed(args);
        assertFalse(result.getDetails().has("error"));
        assertEquals(2, result.getDetails().getAsJsonArray("options").size());
        assertTrue(result.getDetails().getAsJsonArray("options").get(0).getAsJsonObject()
                .get("recommended").getAsBoolean());

        args.addProperty("option2", "完整实现");
        assertTrue(new RequestUserInputTool().executeDetailed(args).getDetails().has("error"));
    }

    public void testReadOnlyCommandClassification() {
        assertTrue(RunCommandTool.isReadOnlyCommand("git status --short"));
        assertTrue(RunCommandTool.isReadOnlyCommand("rg -n TODO src"));
        assertTrue(RunCommandTool.isReadOnlyCommand("ls -la"));
        assertFalse(RunCommandTool.isReadOnlyCommand("git status && rm -rf target"));
        assertFalse(RunCommandTool.isReadOnlyCommand("mvn test"));
        assertFalse(RunCommandTool.isReadOnlyCommand("echo changed > file.txt"));
        assertTrue(RunCommandTool.isVerificationCommand("mvn test"));
        assertTrue(RunCommandTool.isVerificationCommand("git diff --check"));
        assertFalse(RunCommandTool.isVerificationCommand("git status --short"));
        assertFalse(RunCommandTool.isVerificationCommand("echo build"));
        assertFalse(RunCommandTool.isVerificationCommand("ls build"));
        assertFalse(RunCommandTool.isVerificationCommand("mvn test || true"));
        assertEquals(org.example.core.TaskState.VerificationLevel.TEST,
                RunCommandTool.verificationLevel("./mvnw test"));
        assertEquals(org.example.core.TaskState.VerificationLevel.BUILD,
                RunCommandTool.verificationLevel("javac Sample.java"));
    }

    public void testDirectShellFileMutationIsBlocked() throws Exception {
        Path workspace = Files.createTempDirectory("fish-code-command-guard-");
        try {
            TerminalStart.setCurrentCwd(workspace.toString());
            JsonObject args = new JsonObject();
            args.addProperty("command", "echo changed > source.txt");
            ToolResult result = new RunCommandTool().executeDetailed(args);
            assertEquals("untracked_workspace_mutation", result.getDetails().get("error").getAsString());
            assertFalse(Files.exists(workspace.resolve("source.txt")));
        } finally {
            TerminalStart.clearCurrentCwd();
            Files.deleteIfExists(workspace.resolve("source.txt"));
            Files.deleteIfExists(workspace);
        }
    }

    public void testLineReadAndContentSearch() throws Exception {
        Path workspace = Files.createTempDirectory("fish-code-tools-");
        try {
            Path source = workspace.resolve("Sample.java");
            Files.write(source, ("class Sample {\n    String needle = \"found\";\n}\n")
                    .getBytes(StandardCharsets.UTF_8));
            Files.write(workspace.resolve("ignored.txt"), "needle\n".getBytes(StandardCharsets.UTF_8));
            TerminalStart.setCurrentCwd(workspace.toString());

            JsonObject readArgs = new JsonObject();
            readArgs.addProperty("path", "Sample.java");
            readArgs.addProperty("lineStart", 2);
            readArgs.addProperty("lineEnd", 2);
            String readResult = new ReadFileTool().execute(readArgs);
            assertTrue(readResult.contains("2 |     String needle"));

            JsonObject searchArgs = new JsonObject();
            searchArgs.addProperty("query", "needle");
            searchArgs.addProperty("glob", "*.java");
            String searchResult = new SearchTextTool().execute(searchArgs);
            assertTrue(searchResult.contains("Sample.java:2:"));
            assertFalse(searchResult.contains("ignored.txt"));
        } finally {
            TerminalStart.clearCurrentCwd();
            try (Stream<Path> paths = Files.walk(workspace)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }

    public void testGlobMatching() {
        assertTrue(ToolUtils.globMatch("src/main/App.java", "**/*.java"));
        assertTrue(ToolUtils.globMatch("App.java", "*.java"));
        assertFalse(ToolUtils.globMatch("App.class", "*.java"));
    }

    public void testLargeDiffFallsBackWithoutQuadraticMatrix() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            before.append("before-").append(i).append('\n');
            after.append("after-").append(i).append('\n');
        }
        String diff = DiffUtils.buildWriteDiff("large.txt", before.toString(), after.toString());
        assertTrue(diff.contains("跳过差异预览"));
    }

    public void testFileToolsRejectOversizedExistingFile() throws Exception {
        Path workspace = Files.createTempDirectory("fish-code-large-file-");
        Path largeFile = workspace.resolve("large.txt");
        try {
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(largeFile.toFile(), "rw")) {
                file.setLength(ToolConstants.MAX_EDITABLE_FILE_BYTES + 1);
            }
            TerminalStart.setCurrentCwd(workspace.toString());

            JsonObject editArgs = new JsonObject();
            editArgs.addProperty("path", "large.txt");
            editArgs.addProperty("oldString", "before");
            editArgs.addProperty("newString", "after");
            assertEquals("file_too_large",
                    new EditFileTool().executeDetailed(editArgs).getDetails().get("error").getAsString());

            JsonObject writeArgs = new JsonObject();
            writeArgs.addProperty("path", "large.txt");
            writeArgs.addProperty("content", "replacement");
            assertEquals("file_too_large",
                    new WriteFileTool().executeDetailed(writeArgs).getDetails().get("error").getAsString());
        } finally {
            TerminalStart.clearCurrentCwd();
            Files.deleteIfExists(largeFile);
            Files.deleteIfExists(workspace);
        }
    }

    @SuppressWarnings("unchecked")
    public void testContextCharacterCountIncludesMessagesAndToolCalls() throws Exception {
        TerminalStart agent = new TerminalStart("", "", "test", null);
        int base = agent.getContextCharCount();
        Field messagesField = TerminalStart.class.getDeclaredField("messages");
        messagesField.setAccessible(true);
        List<JsonObject> messages = (List<JsonObject>) messagesField.get(agent);

        messages.add(message("user", "你好abc"));
        JsonObject assistant = message("assistant", "完成");
        com.google.gson.JsonArray calls = new com.google.gson.JsonArray();
        JsonObject call = new JsonObject();
        call.addProperty("name", "read_file");
        calls.add(call);
        assistant.add("tool_calls", calls);
        messages.add(assistant);

        assertEquals(base + "你好abc".length() + "完成".length() + calls.toString().length(),
                agent.getContextCharCount());
    }

    @SuppressWarnings("unchecked")
    public void testContextTrimKeepsToolTurnTogether() throws Exception {
        TerminalStart agent = new TerminalStart("", "", "test", null);
        Field messagesField = TerminalStart.class.getDeclaredField("messages");
        messagesField.setAccessible(true);
        List<JsonObject> messages = (List<JsonObject>) messagesField.get(agent);

        messages.add(message("user", repeat('a', 25000)));
        messages.add(message("assistant", repeat('b', 25000)));
        messages.add(message("user", "current turn"));

        JsonObject toolCallMessage = message("assistant", "");
        com.google.gson.JsonArray calls = new com.google.gson.JsonArray();
        JsonObject call = new JsonObject();
        call.addProperty("id", "call-1");
        call.addProperty("padding", repeat('c', 1000));
        calls.add(call);
        toolCallMessage.add("tool_calls", calls);
        messages.add(toolCallMessage);

        messages.add(message("tool", repeat('d', 70000)));

        Method trim = TerminalStart.class.getDeclaredMethod("trimContextIfNeeded");
        trim.setAccessible(true);
        trim.invoke(agent);

        assertEquals("system", messages.get(0).get("role").getAsString());
        assertEquals("system", messages.get(1).get("role").getAsString());
        assertEquals("user", messages.get(2).get("role").getAsString());
        assertEquals("current turn", messages.get(2).get("content").getAsString());
        assertEquals("assistant", messages.get(3).get("role").getAsString());
        assertEquals("tool", messages.get(4).get("role").getAsString());
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
