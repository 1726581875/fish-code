package org.example.tool;

import com.google.gson.*;
import org.example.TerminalStart;
import org.example.core.AgentRun;
import org.example.core.TaskState;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class RunCommandTool extends Tool {

    private static final Pattern SAFE_GIT_COMMAND = Pattern.compile(
            "^git\\s+(status|diff|log|show)(\\s|$)|^git\\s+branch\\s+--show-current(\\s|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_INSPECTION_COMMAND = Pattern.compile(
            "^(pwd|ls|rg|grep|head|tail|wc|which|type|java\\s+-version|javac\\s+-version)(\\s|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_FILE_MUTATION = Pattern.compile(
            "(^|[;&|]\\s*|\\s)(sed\\s+[^\\n]*-[a-z]*i|perl\\s+[^\\n]*-[a-z]*pi|tee|touch|cp|mv|patch)\\b|"
                    + "\\bgit\\s+(apply|checkout|restore|reset|clean)\\b|"
                    + "\\b(npm|pnpm|yarn)\\s+(install|add|remove|uninstall)\\b|"
                    + "\\b(npm|pnpm|yarn)\\s+run\\s+[^\\s]*(format|fix)[^\\s]*|"
                    + "(^|[^<])>{1,2}(?!&)", Pattern.CASE_INSENSITIVE);

    private static final Pattern[] DANGEROUS_WIN = {
            Pattern.compile("\\bdel\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brd\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brmdir\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(format|diskpart|fdisk)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(shutdown|shutdown\\.exe)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|[&|]\\s*)cd\\s+(\\.\\.|[A-Za-z]:\\\\|\\\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|\\s)\\.\\.[/\\\\]"),
    };

    private static final Pattern[] DANGEROUS_UNIX = {
            Pattern.compile("\\brm\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brmdir\\b"),
            Pattern.compile("\\b(mkfs|fdisk|dd\\s+if=)", Pattern.CASE_INSENSITIVE),
            Pattern.compile(">[>]?\\s*/dev/(sd|hd|nvme|mmcblk|xvd|vd|dm-)"),
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff|init\\s+[06])\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:"),
            Pattern.compile("\\bchmod\\s+.*777\\s+/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcurl.*\\|\\s*(ba)?sh\\b"),
            Pattern.compile("\\bwget.*-O\\s*-\\s*\\|\\s*(ba)?sh\\b"),
            Pattern.compile("(^|[;&|]\\s*)cd\\s+(\\.\\.|/|~)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|\\s)\\.\\.[/\\\\]"),
            Pattern.compile("\\bgit\\s+(reset\\s+--hard|clean\\s+-[a-zA-Z]*f|checkout\\s+--)\\b", Pattern.CASE_INSENSITIVE),
    };

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    public RunCommandTool() {
        super("run_command", "执行一个shell命令并返回输出结果",
                new Param("command", "string", "要执行的命令", true),
                new Param("timeoutSeconds", "integer", "超时秒数，默认30，最大600", false),
                new Param("maxOutputChars", "integer", "返回输出字符数，默认8000，最大50000", false));
    }

    public static boolean isReadOnlyCommand(String command) {
        if (command == null) return false;
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return false;
        // Keep the automatic path deliberately narrow. Compound shell syntax can
        // hide a write behind an otherwise harmless first command.
        if (trimmed.contains(";") || trimmed.contains("&&") || trimmed.contains("||")
                || trimmed.contains("|") || trimmed.contains(">") || trimmed.contains("<")
                || trimmed.contains("`") || trimmed.contains("$(")) {
            return false;
        }
        return SAFE_GIT_COMMAND.matcher(trimmed).find()
                || SAFE_INSPECTION_COMMAND.matcher(trimmed).find();
    }

    public static boolean isVerificationCommand(String command) {
        return verificationLevel(command) != TaskState.VerificationLevel.NONE;
    }

    public static TaskState.VerificationLevel verificationLevel(String command) {
        if (command == null) return TaskState.VerificationLevel.NONE;
        String value = command.trim().toLowerCase();
        if (value.isEmpty() || value.matches(".*(\\|\\|\\s*(true|:)|;\\s*(true|:)(\\s|$)).*")) {
            return TaskState.VerificationLevel.NONE;
        }
        if (value.matches(".*(^|[;&|]\\s*)git\\s+diff\\s+--check(\\s|$).*")) return TaskState.VerificationLevel.SANITY;
        if (value.matches(".*(^|[;&|]\\s*)(test\\s+|\\[\\s+).*")) return TaskState.VerificationLevel.SANITY;
        if (value.matches(".*(^|[;&|]\\s*)(pytest|jest|vitest)(\\s|$).*"
                ) || value.matches(".*(^|[;&|]\\s*)(cargo|go)\\s+test(\\s|$).*")) return TaskState.VerificationLevel.TEST;
        if (value.matches(".*(^|[;&|]\\s*)(mvn|mvnw|\\./mvnw|gradle|gradlew|\\./gradlew)\\b[^\\n]*(test|verify)\\b.*")
                || value.matches(".*(^|[;&|]\\s*)(npm|pnpm|yarn)\\s+(test|run\\s+test)(\\s|$).*")) {
            return TaskState.VerificationLevel.TEST;
        }
        if (value.matches(".*(^|[;&|]\\s*)(mvn|mvnw|\\./mvnw|gradle|gradlew|\\./gradlew)\\b[^\\n]*(package|compile|build)\\b.*")
                || value.matches(".*(^|[;&|]\\s*)(npm|pnpm|yarn)\\s+run\\s+(build|typecheck)(\\s|$).*")) {
            return TaskState.VerificationLevel.BUILD;
        }
        if (value.matches(".*(^|[;&|]\\s*)(javac|tsc)(\\s|$).*")) return TaskState.VerificationLevel.BUILD;
        if (value.matches(".*(^|[;&|]\\s*)(eslint|ruff|shellcheck|stylelint)(\\s|$).*")) return TaskState.VerificationLevel.STATIC;
        if (value.matches(".*(^|[;&|]\\s*)(npm|pnpm|yarn)\\s+run\\s+(lint|check)(\\s|$).*")) {
            return TaskState.VerificationLevel.STATIC;
        }
        return TaskState.VerificationLevel.NONE;
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        return doExecuteDetailed(args).getContent();
    }

    @Override
    protected ToolResult doExecuteDetailed(JsonObject args) throws Exception {
        String command = args.get("command").getAsString();
        int timeoutSeconds = args.has("timeoutSeconds") ? args.get("timeoutSeconds").getAsInt()
                : ToolConstants.COMMAND_TIMEOUT_SECONDS;
        timeoutSeconds = Math.max(1, Math.min(timeoutSeconds, ToolConstants.COMMAND_MAX_TIMEOUT_SECONDS));
        int maxOutputChars = args.has("maxOutputChars") ? args.get("maxOutputChars").getAsInt()
                : ToolConstants.OUTPUT_TRUNCATE_CHARS;
        maxOutputChars = Math.max(1000, Math.min(maxOutputChars, ToolConstants.COMMAND_MAX_OUTPUT_CHARS));
        long start = System.currentTimeMillis();
        JsonObject details = new JsonObject();
        details.addProperty("type", "command");
        details.addProperty("command", command);
        details.addProperty("cwd", TerminalStart.getCurrentCwd());
        details.addProperty("risk", riskLevel(command));
        details.addProperty("timeoutSeconds", timeoutSeconds);
        TaskState.VerificationLevel verificationLevel = verificationLevel(command);
        details.addProperty("verification", verificationLevel != TaskState.VerificationLevel.NONE);
        details.addProperty("verificationLevel", verificationLevel.name());

        java.io.File cwd = new java.io.File(TerminalStart.getCurrentCwd()).getCanonicalFile();
        if (!cwd.exists() || !cwd.isDirectory()) {
            details.addProperty("error", "invalid_cwd");
            return new ToolResult("工作目录不存在或不是目录: " + cwd.getAbsolutePath(), details);
        }

        String rejection = checkSafety(command, cwd);
        if (rejection != null) {
            details.addProperty("blocked", true);
            details.addProperty("error", rejection);
            return new ToolResult(rejection, details);
        }
        if (DIRECT_FILE_MUTATION.matcher(command).find()) {
            details.addProperty("blocked", true);
            details.addProperty("error", "untracked_workspace_mutation");
            return new ToolResult("命令包含无法安全记录和回滚的文件修改。请使用 edit_file/write_file；依赖变更请拆成明确步骤交给用户处理。", details);
        }

        ProcessBuilder pb;
        if (IS_WINDOWS) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        pb.redirectErrorStream(true);
        pb.directory(cwd);
        Process process = pb.start();
        TerminalStart.registerActiveProcess(process);

        BoundedOutput output = new BoundedOutput(maxOutputChars);
        java.util.concurrent.ExecutorService readExecutor = java.util.concurrent.Executors
                .newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    return t;
                });
        java.util.concurrent.Future<?> readFuture = readExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line + "\n");
                }
            } catch (IOException e) {
                output.append("读取输出出错: " + e.getMessage());
            }
        });

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                AgentRun.destroyProcessTree(process);
                readFuture.cancel(true);
                details.addProperty("timedOut", true);
                details.addProperty("exitCode", -1);
                details.addProperty("durationMs", System.currentTimeMillis() - start);
                output.append("\n(命令超时，已强制终止)");
                String timedOutOutput = output.snapshot(details);
                details.addProperty("output", timedOutOutput);
                recordVerification(command, -1, true, timedOutOutput);
                return new ToolResult(timedOutOutput, details);
            }
            try {
                readFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                output.append("\n(读取输出超时)");
            }
        } finally {
            readExecutor.shutdownNow();
            TerminalStart.clearActiveProcess(process);
        }

        int exitCode = process.exitValue();
        String prefix = exitCode != 0 ? "(退出码: " + exitCode + ")\n" : "";
        String result = prefix + output.snapshot(details);
        String content = result.isEmpty() ? "(命令执行完毕，无输出)" : result.trim();
        details.addProperty("exitCode", exitCode);
        details.addProperty("durationMs", System.currentTimeMillis() - start);
        details.addProperty("output", content);
        recordVerification(command, exitCode, false, content);
        return new ToolResult(content, details);
    }

    private String checkSafety(String command, java.io.File cwd) {
        Pattern[] patterns = IS_WINDOWS ? DANGEROUS_WIN : DANGEROUS_UNIX;
        for (Pattern p : patterns) {
            if (p.matcher(command).find()) {
                return "危险操作已阻止，当前策略禁止执行此命令";
            }
        }
        String pathRejection = checkAbsolutePaths(command, cwd);
        if (pathRejection != null) {
            return pathRejection;
        }
        return null;
    }

    private String checkAbsolutePaths(String command, java.io.File cwd) {
        Pattern pattern = IS_WINDOWS
                ? Pattern.compile("(^|\\s)([A-Za-z]:\\\\[^\\s;&|]+)")
                : Pattern.compile("(^|[\\s=])(/[^\\s;&|`$]+)");
        Matcher matcher = pattern.matcher(command);
        String root;
        try {
            root = cwd.getCanonicalPath();
        } catch (IOException e) {
            return "无法解析工作目录: " + e.getMessage();
        }
        while (matcher.find()) {
            String raw = matcher.group(2);
            String cleaned = raw.replaceAll("^[\"']|[\"',)]$", "");
            try {
                String target = new java.io.File(cleaned).getCanonicalPath();
                if (!target.equals(root) && !target.startsWith(root + java.io.File.separator)) {
                    return "命令包含工作目录之外的绝对路径，已阻止: " + cleaned;
                }
            } catch (IOException e) {
                return "无法解析命令中的绝对路径: " + cleaned;
            }
        }
        return null;
    }

    private String truncateOutput(String output, JsonObject details, int maxChars) {
        if (output.length() > maxChars) {
            details.addProperty("truncated", true);
            int headChars = maxChars / 2;
            int tailChars = maxChars - headChars;
            return output.substring(0, headChars)
                    + "\n...(中间输出已截断，共 " + output.length() + " 字符)...\n"
                    + output.substring(output.length() - tailChars);
        }
        details.addProperty("truncated", false);
        return output;
    }

    private void recordVerification(String command, int exitCode, boolean timedOut, String output) {
        AgentRun run = TerminalStart.getCurrentRun();
        TaskState.VerificationLevel level = verificationLevel(command);
        if (run != null && level != TaskState.VerificationLevel.NONE) {
            run.getTaskState().recordVerification(command, exitCode, timedOut, output, level.name());
        }
    }

    private static final class BoundedOutput {
        private final int maxChars;
        private final int headLimit;
        private final int tailLimit;
        private final StringBuilder head = new StringBuilder();
        private final StringBuilder tail = new StringBuilder();
        private long totalChars;

        BoundedOutput(int maxChars) {
            this.maxChars = maxChars;
            this.headLimit = maxChars / 2;
            this.tailLimit = maxChars - headLimit;
        }

        synchronized void append(String value) {
            if (value == null || value.isEmpty()) return;
            totalChars += value.length();
            int headRemaining = headLimit - head.length();
            if (headRemaining > 0) head.append(value, 0, Math.min(headRemaining, value.length()));
            tail.append(value);
            if (tail.length() > tailLimit) tail.delete(0, tail.length() - tailLimit);
        }

        synchronized String snapshot(JsonObject details) {
            boolean truncated = totalChars > maxChars;
            details.addProperty("truncated", truncated);
            details.addProperty("totalOutputChars", totalChars);
            if (!truncated) {
                if (totalChars <= head.length()) return head.toString();
                return head.toString() + tail.substring(Math.max(0, tail.length() - ((int) totalChars - head.length())));
            }
            return head + "\n...(中间输出已截断，共 " + totalChars + " 字符)...\n" + tail;
        }
    }

    private String riskLevel(String command) {
        String lower = command == null ? "" : command.toLowerCase();
        if (lower.contains(">") || lower.contains(" mv ") || lower.startsWith("mv ")
                || lower.contains(" cp ") || lower.startsWith("cp ")
                || lower.contains("npm install") || lower.contains("mvn ")
                || lower.contains("git commit") || lower.contains("git push")) {
            return "write";
        }
        return "command";
    }
}
