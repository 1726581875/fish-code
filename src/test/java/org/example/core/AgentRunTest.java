package org.example.core;

import junit.framework.TestCase;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.example.tool.ToolUtils;
import org.example.tool.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;

public class AgentRunTest extends TestCase {
    public void testConfirmationAndCancellationAreIsolatedByRun() throws Exception {
        Path workspace = Files.createTempDirectory("fish-run-isolation-");
        AgentRun first = new AgentRun("run-a", "first", "model", "url", "key", workspace.toString());
        AgentRun second = new AgentRun("run-b", "second", "model", "url", "key", workspace.toString());

        String key = first.createConfirmationRequest();
        assertTrue(first.resolveConfirmation(key, true, true));
        assertTrue(first.isApproveAllRemaining());
        assertFalse(second.isApproveAllRemaining());

        first.requestStop();
        assertTrue(first.isStopRequested());
        assertFalse(second.isStopRequested());
        assertEquals(TaskState.Phase.CANCELLED, first.getTaskState().getPhase());
    }

    public void testWorkspacePolicyAllowsOnlyConfiguredDescendants() throws Exception {
        Path root = Files.createTempDirectory("fish-workspace-root-");
        Path child = Files.createDirectories(root.resolve("child"));
        Path sibling = Files.createTempDirectory("fish-workspace-sibling-");
        WorkspacePolicy policy = new WorkspacePolicy(Collections.singletonList(root.toString()), child.toString());

        assertTrue(policy.isAllowed(root.toFile()));
        assertTrue(policy.isAllowed(child.toFile()));
        assertFalse(policy.isAllowed(sibling.toFile()));
    }

    public void testWorkspacePickerCanReachSiblingProjectsWithoutExplicitRoots() throws Exception {
        Path container = Files.createTempDirectory("fish-workspace-container-");
        Path first = Files.createDirectories(container.resolve("first"));
        Path second = Files.createDirectories(container.resolve("second"));
        try {
            WorkspacePolicy policy = new WorkspacePolicy(Collections.<String>emptyList(), first.toString());
            assertTrue(policy.isAllowed(container.toFile()));
            assertTrue(policy.isAllowed(second.toFile()));
            assertEquals(container.toFile().getCanonicalFile(), policy.getRoots().get(0));
        } finally {
            deleteTree(container);
        }
    }

    public void testLocalWorkspacePolicyCanBrowseHomeAndAuthorizeExternalProject() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("fish-local-home-");
        Path initial = Files.createDirectories(home.resolve("projects/first"));
        Path another = Files.createTempDirectory("fish-external-project-");
        try {
            System.setProperty("user.home", home.toString());
            JsonObject config = new JsonObject();
            config.add("allowed_workspace_roots", new JsonArray());
            WorkspacePolicy policy = WorkspacePolicy.fromConfig(config, initial.toString(), true);
            assertTrue(policy.isAllowed(home.toFile()));
            assertTrue(policy.isAllowed(home.resolve("other-project").toFile()));
            assertFalse(policy.isAllowed(another.toFile()));
            policy.authorizeExplicit(another.toFile());
            assertTrue(policy.isAllowed(another.toFile()));
            try {
                policy.authorizeExplicit(new File(File.separator));
                fail("filesystem root must remain blocked");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("根目录"));
            }
        } finally {
            System.setProperty("user.home", oldHome);
            deleteTree(home);
            deleteTree(another);
        }
    }

    public void testRemoteWorkspacePolicyRequiresExplicitRoots() throws Exception {
        Path workspace = Files.createTempDirectory("fish-remote-workspace-");
        try {
            JsonObject config = new JsonObject();
            config.add("allowed_workspace_roots", new JsonArray());
            try {
                WorkspacePolicy.fromConfig(config, workspace.toString(), false);
                fail("remote binding must require explicit roots");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("allowed_workspace_roots"));
            }
        } finally {
            deleteTree(workspace);
        }
    }

    public void testTaskStateRequiresSuccessfulPostWriteVerification() {
        TaskState state = new TaskState("run", "modify code");
        state.markModified("Sample.java");
        assertTrue(state.hasWrites());
        assertFalse(state.isVerifiedAfterLastWrite());

        state.recordVerification("mvn test", 1, false, "failed");
        assertFalse(state.isVerifiedAfterLastWrite());
        state.recordVerification("mvn test", 0, false, "ok");
        assertTrue(state.isVerifiedAfterLastWrite());
        assertEquals(TaskState.Phase.VERIFY, state.getPhase());
    }

    public void testSourceChangesRequireStaticOrStrongerVerification() {
        TaskState state = new TaskState("run", "modify source");
        state.markModified("Sample.java");
        state.recordVerification("git diff --check", 0, false, "ok", "SANITY");
        assertFalse(state.isVerifiedAfterLastWrite());
        assertEquals(TaskState.VerificationLevel.STATIC, state.getRequiredVerificationLevel());
        state.recordVerification("javac Sample.java", 0, false, "ok", "BUILD");
        assertTrue(state.isVerifiedAfterLastWrite());
    }

    public void testWeakerSuccessfulCheckDoesNotDiscardStrongerVerification() {
        TaskState state = new TaskState("run", "modify source");
        state.markModified("Sample.java");
        state.recordVerification("javac Sample.java", 0, false, "ok", "BUILD");
        assertTrue(state.isVerifiedAfterLastWrite());

        state.recordVerification("git diff --check", 0, false, "ok", "SANITY");
        assertTrue(state.isVerifiedAfterLastWrite());

        state.recordVerification("mvn test", 1, false, "failed", "TEST");
        assertFalse(state.isVerifiedAfterLastWrite());
    }

    public void testAppendMessageFailureIsReportedWithoutIncrementingIndex() throws Exception {
        Path base = Files.createTempDirectory("fish-session-persistence-");
        try {
            SessionManager manager = new SessionManager(base);
            String sessionId = manager.createSession(base.toString(), "model");
            Path sessionFile;
            try (java.util.stream.Stream<Path> paths = Files.walk(base.resolve("sessions"))) {
                sessionFile = paths.filter(path -> path.getFileName().toString().equals(sessionId + ".jsonl"))
                        .findFirst().orElseThrow(() -> new AssertionError("session file missing"));
            }
            Files.delete(sessionFile);

            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", "must persist");
            try {
                manager.appendMessage(sessionId, message);
                fail("expected append failure");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
            }
            assertEquals(0, manager.getSessionInfo(sessionId).get("messageCount").getAsInt());
        } finally {
            deleteTree(base);
        }
    }

    public void testSessionStatePathsRejectTraversal() throws Exception {
        Path base = Files.createTempDirectory("fish-session-path-");
        Path outside = base.resolve("outside.json");
        try {
            Files.write(outside, "keep".getBytes(StandardCharsets.UTF_8));
            SessionManager manager = new SessionManager(base.resolve("data"));

            manager.deleteSession("../../outside");
            assertTrue(Files.exists(outside));
            assertNull(manager.loadTaskState("../../outside"));

            JsonObject task = new JsonObject();
            task.addProperty("runId", "bad");
            manager.saveTaskState("../../outside", task);
            assertEquals("keep", new String(Files.readAllBytes(outside), StandardCharsets.UTF_8));
        } finally {
            deleteTree(base);
        }
    }

    public void testPersistedTaskStateCanResumeWithSameRunId() throws Exception {
        Path workspace = Files.createTempDirectory("fish-run-resume-");
        TaskState state = new TaskState("resume-run", "resume objective");
        state.setSessionId("session-a");
        state.markModified("notes.txt");
        JsonObject toolDetails = new JsonObject();
        toolDetails.addProperty("changed", true);
        state.recordToolResult("tool-1", "already executed", toolDetails);
        state.block("interrupted");
        RunRegistry registry = new RunRegistry();
        AgentRun resumed = registry.resume("resume-run", "continue", "model", "url", "key",
                workspace.toString(), state.toPersistentJson());
        assertEquals("resume-run", resumed.getRunId());
        assertEquals("session-a", resumed.getTaskState().getSessionId());
        assertEquals(TaskState.Phase.DISCOVER, resumed.getTaskState().getPhase());
        ToolResult replay = resumed.getExecutedResult("tool-1");
        assertNotNull(replay);
        assertEquals("already executed", replay.getContent());
        Files.deleteIfExists(workspace);
    }

    public void testRollbackRefusesToOverwriteNewerUserChanges() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("fish-journal-home-");
        Path workspace = Files.createTempDirectory("fish-journal-workspace-");
        Path file = workspace.resolve("sample.txt");
        try {
            System.setProperty("user.home", home.toString());
            Files.write(file, "before".getBytes(StandardCharsets.UTF_8));
            ChangeJournal journal = new ChangeJournal("conflict-run", workspace.toString());
            journal.capture(file.toFile());
            ToolUtils.writeAtomically(file, "agent".getBytes(StandardCharsets.UTF_8));
            journal.recordWritten(file.toFile());
            ToolUtils.writeAtomically(file, "user".getBytes(StandardCharsets.UTF_8));
            try {
                journal.rollback();
                fail("expected rollback conflict");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("拒绝覆盖"));
            }
            assertEquals("user", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            ToolUtils.writeAtomically(file, "agent".getBytes(StandardCharsets.UTF_8));
            assertEquals(1, journal.rollback().size());
            assertEquals("before", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } finally {
            System.setProperty("user.home", oldHome);
            deleteTree(workspace);
            deleteTree(home);
        }
    }

    public void testClaudeHistoryPathComponentsRejectTraversal() {
        assertTrue(ClaudeSessionsHandler.isSafePathComponent("project-123_abc"));
        assertFalse(ClaudeSessionsHandler.isSafePathComponent("../secret"));
        assertFalse(ClaudeSessionsHandler.isSafePathComponent("a/b"));
    }

    public void testDestroyProcessTreeStopsShellChild() throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("win")) return;
        if (!canInspectProcessTree()) return;
        Path markerDir = Files.createTempDirectory("fish-process-tree-");
        Path marker = markerDir.resolve("orphan.txt");
        ProcessBuilder builder = new ProcessBuilder("sh", "-c",
                "(sleep 2; echo orphan > \"$MARKER\") & child=$!; echo $child; wait");
        builder.environment().put("MARKER", marker.toString());
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            assertNotNull(reader.readLine());
            AgentRun.destroyProcessTree(process);
            assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
        }
        Thread.sleep(2300);
        assertFalse("orphan child continued running", Files.exists(marker));
        deleteTree(markerDir);
    }

    private static boolean canInspectProcessTree() {
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object current = handleClass.getMethod("current").invoke(null);
            Object descendants = handleClass.getMethod("descendants").invoke(current);
            if (descendants instanceof java.util.stream.Stream) ((java.util.stream.Stream<?>) descendants).close();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }
}
