package org.example.tool;

import com.google.gson.*;
import org.example.TerminalStart;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class RunCommandTool extends Tool {

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
                new Param("command", "string", "要执行的命令", true));
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        return doExecuteDetailed(args).getContent();
    }

    @Override
    protected ToolResult doExecuteDetailed(JsonObject args) throws Exception {
        String command = args.get("command").getAsString();
        long start = System.currentTimeMillis();
        JsonObject details = new JsonObject();
        details.addProperty("type", "command");
        details.addProperty("command", command);
        details.addProperty("cwd", TerminalStart.getCurrentCwd());
        details.addProperty("risk", riskLevel(command));

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

        ProcessBuilder pb;
        if (IS_WINDOWS) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        pb.redirectErrorStream(true);
        pb.directory(cwd);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
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
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                output.append("读取输出出错: ").append(e.getMessage());
            }
        });

        try {
            boolean finished = process.waitFor(ToolConstants.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readFuture.cancel(true);
                details.addProperty("timedOut", true);
                details.addProperty("durationMs", System.currentTimeMillis() - start);
                String timedOutOutput = truncateOutput(output.toString() + "\n(命令超时，已强制终止)", details);
                details.addProperty("output", timedOutOutput);
                return new ToolResult(timedOutOutput, details);
            }
            try {
                readFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                output.append("\n(读取输出超时)");
            }
        } finally {
            readExecutor.shutdownNow();
        }

        int exitCode = process.exitValue();
        String prefix = exitCode != 0 ? "(退出码: " + exitCode + ")\n" : "";
        String result = truncateOutput(prefix + output.toString(), details);
        String content = result.isEmpty() ? "(命令执行完毕，无输出)" : result.trim();
        details.addProperty("exitCode", exitCode);
        details.addProperty("durationMs", System.currentTimeMillis() - start);
        details.addProperty("output", content);
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

    private String truncateOutput(String output, JsonObject details) {
        if (output.length() > ToolConstants.OUTPUT_TRUNCATE_CHARS) {
            details.addProperty("truncated", true);
            return output.substring(0, ToolConstants.OUTPUT_TRUNCATE_CHARS) + "\n...(输出已截断)";
        }
        details.addProperty("truncated", false);
        return output;
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
