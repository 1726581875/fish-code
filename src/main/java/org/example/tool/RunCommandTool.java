package org.example.tool;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class RunCommandTool extends Tool {

    private static final Pattern[] DANGEROUS_WIN = {
            Pattern.compile("\\bdel\\s+/[^\\s]*[fsq]"),
            Pattern.compile("\\brd\\s+/[^\\s]*[sq]"),
            Pattern.compile("\\brmdir\\s+/[^\\s]*[sq]"),
            Pattern.compile("\\b(format|diskpart|fdisk)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(shutdown|shutdown\\.exe)\\b", Pattern.CASE_INSENSITIVE),
    };

    private static final Pattern[] DANGEROUS_UNIX = {
            Pattern.compile("\\brm\\b\\s+.*-(r|f|rf|fr|-recursive|-force)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brmdir\\b"),
            Pattern.compile("\\b(mkfs|fdisk|dd\\s+if=)", Pattern.CASE_INSENSITIVE),
            Pattern.compile(">[>]?\\s*/dev/(sd|hd|nvme|mmcblk|xvd|vd|dm-)"),
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff|init\\s+[06])\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:"),
            Pattern.compile("\\bchmod\\s+.*777\\s+/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcurl.*\\|\\s*(ba)?sh\\b"),
            Pattern.compile("\\bwget.*-O\\s*-\\s*\\|\\s*(ba)?sh\\b"),
    };

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    public RunCommandTool() {
        super("run_command", "执行一个shell命令并返回输出结果",
                new Param("command", "string", "要执行的命令", true));
    }

    @Override
    protected String doExecute(JsonObject args) throws Exception {
        String command = args.get("command").getAsString();

        String rejection = checkSafety(command);
        if (rejection != null) {
            return rejection;
        }

        ProcessBuilder pb;
        if (IS_WINDOWS) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        pb.redirectErrorStream(true);
        pb.directory(new java.io.File(System.getProperty("user.dir")));
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(ToolConstants.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return output + "\n(命令超时，已强制终止)";
        }

        int exitCode = process.exitValue();
        String prefix = exitCode != 0 ? "(退出码: " + exitCode + ")\n" : "";
        String result = prefix + output.toString();
        if (result.length() > ToolConstants.OUTPUT_TRUNCATE_CHARS) {
            result = result.substring(0, ToolConstants.OUTPUT_TRUNCATE_CHARS) + "\n...(输出已截断)";
        }
        return result.isEmpty() ? "(命令执行完毕，无输出)" : result.trim();
    }

    private String checkSafety(String command) {
        Pattern[] patterns = IS_WINDOWS ? DANGEROUS_WIN : DANGEROUS_UNIX;
        for (Pattern p : patterns) {
            if (p.matcher(command).find()) {
                return "危险操作已阻止，当前策略禁止执行此命令";
            }
        }
        return null;
    }
}
