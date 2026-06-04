package org.example;

import com.google.gson.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.tool.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import sun.misc.Signal;
import sun.misc.SignalHandler;

public class DeepSeekAgent {

    private static final int MAX_TOOL_ROUNDS = 50;
    private static final Gson GSON = new Gson();

    private static final Set<String> WRITE_TOOLS = new HashSet<>(Arrays.asList(
            "edit_file", "write_file", "run_command"));

    private static final AtomicInteger ctrlCCount = new AtomicInteger(0);
    private static volatile boolean stopRequested = false;

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final List<JsonObject> messages = new ArrayList<>();
    private List<Tool> tools;
    private final Map<String, Tool> toolMap = new HashMap<>();
    private AgentMode currentMode;
    private Terminal terminal;
    private Reader terminalReader;

    public DeepSeekAgent(String apiUrl, String apiKey, String model) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        setMode(AgentMode.PLAN);
    }

    public void setTerminal(Terminal terminal, Reader terminalReader) {
        this.terminal = terminal;
        this.terminalReader = terminalReader;
    }

    public void setMode(AgentMode mode) {
        this.currentMode = mode;
        this.tools = registerTools(mode);

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", mode.systemPrompt());
        if (!messages.isEmpty() && "system".equals(messages.get(0).get("role").getAsString())) {
            messages.set(0, systemMsg);
        } else {
            messages.add(0, systemMsg);
        }
    }

    public AgentMode getMode() {
        return currentMode;
    }

    private List<Tool> registerTools(AgentMode mode) {
        List<Tool> all = new ArrayList<>();
        all.add(new ReadFileTool());
        all.add(new FindFileTool());
        all.add(new EditFileTool());
        all.add(new WriteFileTool());
        all.add(new RunCommandTool());

        List<Tool> list = new ArrayList<>();
        toolMap.clear();
        for (Tool t : all) {
            if (mode.allowedTools.contains(t.getName())) {
                list.add(t);
                toolMap.put(t.getName(), t);
            }
        }
        return list;
    }

    public String chat(String userInput) throws Exception {
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userInput);
        messages.add(userMsg);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (stopRequested) {
                return "(对话已被用户终止)";
            }
            JsonObject response = callApi();
            JsonObject message = response.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message");

            JsonElement toolCalls = message.get("tool_calls");
            String content = message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : null;

            if (toolCalls == null || toolCalls.isJsonNull() || toolCalls.getAsJsonArray().size() == 0) {
                messages.add(message);
                return content != null ? content : "(无回复)";
            }

            messages.add(message);

            for (JsonElement tc : toolCalls.getAsJsonArray()) {
                JsonObject toolCall = tc.getAsJsonObject();
                String fnName = toolCall.getAsJsonObject("function").get("name").getAsString();
                String fnArgs = toolCall.getAsJsonObject("function").get("arguments").getAsString();
                String callId = toolCall.get("id").getAsString();

                if (currentMode == AgentMode.CONFIRM && WRITE_TOOLS.contains(fnName)) {
                    boolean approved = confirmAction(fnName, fnArgs);
                    if (!approved) {
                        JsonObject toolMsg = new JsonObject();
                        toolMsg.addProperty("role", "tool");
                        toolMsg.addProperty("tool_call_id", callId);
                        toolMsg.addProperty("content", "用户拒绝了此操作。请调整方案后重试，或说明原因。");
                        messages.add(toolMsg);
                        continue;
                    }
                }

                System.out.println("[调用工具] " + fnName + "(" + fnArgs + ")");

                Tool tool = toolMap.get(fnName);
                String result = tool != null
                        ? tool.execute(GSON.fromJson(fnArgs, JsonObject.class))
                        : "未知工具: " + fnName;

                JsonObject toolMsg = new JsonObject();
                toolMsg.addProperty("role", "tool");
                toolMsg.addProperty("tool_call_id", callId);
                toolMsg.addProperty("content", result);
                messages.add(toolMsg);
            }
        }

        return "(达到最大工具调用轮次)";
    }

    private boolean confirmAction(String fnName, String fnArgs) throws IOException {
        if (terminal == null) {
            return true;
        }
        PrintWriter w = terminal.writer();
        String reset = "\u001B[0m";
        String yellow = "\u001B[33m";
        w.print("\r\n  " + yellow + "\u26A0 确认执行 " + fnName + "(" + fnArgs + ") [y/N] " + reset);
        w.flush();

        while (true) {
            int c = terminalReader.read();
            if (c == 'y' || c == 'Y') {
                w.println("y");
                w.flush();
                return true;
            }
            if (c == '\r' || c == '\n') {
                w.println("N");
                w.flush();
                return false;
            }
            if (c == 'n' || c == 'N' || c == 27) {
                w.println((char) c);
                w.flush();
                return false;
            }
        }
    }

    private JsonObject callApi() throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray msgArray = new JsonArray();
        for (JsonObject msg : messages) {
            msgArray.add(msg);
        }
        body.add("messages", msgArray);
        JsonArray toolsJson = new JsonArray();
        for (Tool t : tools) {
            toolsJson.add(t.toJson());
        }
        body.add("tools", toolsJson);

        String jsonBody = GSON.toJson(body);

        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int statusCode = conn.getResponseCode();
        InputStream inputStream = (statusCode >= 200 && statusCode < 300)
                ? conn.getInputStream() : conn.getErrorStream();

        String responseBody;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            responseBody = sb.toString();
        }

        if (statusCode != 200) {
            throw new RuntimeException("API error [" + statusCode + "]: " + responseBody);
        }

        return GSON.fromJson(responseBody, JsonObject.class);
    }

    private static JsonObject loadConfig() {
        try (InputStream in = DeepSeekAgent.class.getResourceAsStream("/config.json")) {
            if (in != null) {
                byte[] bytes = new byte[in.available()];
                in.read(bytes);
                String content = new String(bytes, StandardCharsets.UTF_8);
                return GSON.fromJson(content, JsonObject.class);
            }
        } catch (IOException e) {
            System.err.println("读取配置文件失败: " + e.getMessage());
        }
        return new JsonObject();
    }

    private static String getConfigString(JsonObject config, String key, String envKey, String defaultValue) {
        if (config.has(key) && !config.get(key).isJsonNull()) {
            return config.get(key).getAsString();
        }
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.trim().isEmpty()) {
            return envVal;
        }
        return defaultValue;
    }

    private static void printBanner(String model) {
        String cyan  = "\033[36m";
        String green = "\033[32m";
        String reset = "\033[0m";

        System.out.println(cyan);
        System.out.println("            ><(((º>");
        System.out.println("           /  º   \\");
        System.out.println("          |   O    |~~");
        System.out.println("           \\      /");
        System.out.println("            `----'");
        System.out.println();

        System.out.println(reset + green + "  ███████╗ ██╗ ███████╗ ██╗  ██╗       ██████╗  ██████╗ ██████╗ ███████╗" + reset);
        System.out.println(green + "  ██╔════╝ ██║ ██╔════╝ ██║  ██║      ██╔════╝ ██╔═══██╗██╔══██╗██╔════╝" + reset);
        System.out.println(green + "  █████╗   ██║ ███████╗ ███████║      ██║      ██║   ██║██║  ██║█████╗  " + reset);
        System.out.println(green + "  ██╔══╝   ██║ ╚════██║ ██╔══██║      ██║      ██║   ██║██║  ██║██╔══╝  " + reset);
        System.out.println(green + "  ██║      ██║ ███████║ ██║  ██║      ╚██████╗ ╚██████╔╝██████╔╝███████╗" + reset);
        System.out.println(green + "  ╚═╝      ╚═╝ ╚══════╝ ╚═╝  ╚═╝       ╚═════╝  ╚═════╝ ╚═════╝ ╚══════╝" + reset);
        System.out.println();
        System.out.println(cyan + "  model: " + model + "  |  Tab/Shift+Tab 切换模式  |  /help 帮助  |  exit 退出" + reset);
        System.out.println();
    }

    private static void registerSignalHandler() {
        Signal.handle(new Signal("INT"), new SignalHandler() {
            @Override
            public void handle(Signal sig) {
                int count = ctrlCCount.incrementAndGet();
                stopRequested = true;
                System.out.println("\n再见！");
                System.exit(0);
            }
        });
    }

    private static String buildPrompt(AgentMode mode) {
        return "\r" + mode.color() + mode.label() + " \u001B[0m\u25B8 ";
    }

    private static String modeStatusBar(AgentMode mode) {
        String reset = "\u001B[0m";
        StringBuilder sb = new StringBuilder();
        for (AgentMode m : AgentMode.values()) {
            if (m == mode) {
                sb.append(m.color()).append("[").append(m.label().toUpperCase()).append("]").append(reset);
            } else {
                sb.append(" ").append(m.label()).append(" ");
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        JsonObject config = loadConfig();

        String apiUrl = getConfigString(config, "api_url", "DEEPSEEK_API_URL",
                "https://api.deepseek.com/chat/completions");
        String apiKey = getConfigString(config, "api_key", "DEEPSEEK_API_KEY", "");
        String model = getConfigString(config, "model", "DEEPSEEK_MODEL", "deepseek-chat");

        if (apiKey.isEmpty()) {
            System.out.println("请配置 api_key (config.json 或环境变量 DEEPSEEK_API_KEY)");
            return;
        }

        registerSignalHandler();

        DeepSeekAgent agent = new DeepSeekAgent(apiUrl, apiKey, model);
        agent.setMode(AgentMode.PLAN);

        printBanner(model);
        System.out.println("  " + modeStatusBar(AgentMode.PLAN) + "  Tab/Shift+Tab 切换 | /help 帮助 | exit 退出\n");

        try {
            Terminal terminal = TerminalBuilder.builder()
                    .jansi(true)
                    .system(true)
                    .build();
            terminal.enterRawMode();
            Reader terminalReader = new InputStreamReader(terminal.input(), StandardCharsets.UTF_8);
            agent.setTerminal(terminal, terminalReader);

            while (true) {
                ctrlCCount.set(0);
                stopRequested = false;

                AgentMode mode = agent.getMode();
                String prompt = buildPrompt(mode);
                terminal.writer().print(prompt);
                terminal.writer().flush();

                StringBuilder line = new StringBuilder();

                while (true) {
                    int c = terminalReader.read();
                    if (c == -1) { break; }

                    if (c == '\r' || c == '\n') {
                        terminal.writer().println();
                        terminal.writer().flush();
                        break;
                    }

                    if (c == '\t') {
                        agent.setMode(agent.getMode().next());
                        AgentMode newMode = agent.getMode();
                        terminal.writer().print("\r" + buildPrompt(newMode) + line);
                        terminal.writer().flush();
                        continue;
                    }

                    if (c == 27) {
                        int peek = terminalReader.read();
                        if (peek == '[') {
                            int seq = terminalReader.read();
                            if (seq == 'Z') {
                                agent.setMode(agent.getMode().prev());
                                AgentMode newMode = agent.getMode();
                                terminal.writer().print("\r" + buildPrompt(newMode) + line);
                                terminal.writer().flush();
                                continue;
                            }
                        }
                        continue;
                    }

                    if (c == 8 || c == 127) {
                        if (line.length() > 0) {
                            int removed = line.charAt(line.length() - 1);
                            line.deleteCharAt(line.length() - 1);
                            if (removed > 127) {
                                terminal.writer().print("\b\b  \b\b");
                            } else {
                                terminal.writer().print("\b \b");
                            }
                            terminal.writer().flush();
                        }
                        continue;
                    }

                    if (c >= 32) {
                        line.append((char) c);
                        terminal.writer().write(c);
                        terminal.writer().flush();
                    }
                }

                String input = line.toString().trim();

                if (input.isEmpty()) {
                    continue;
                }
                if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                    terminal.writer().println("再见！");
                    terminal.writer().flush();
                    terminal.close();
                    break;
                }
                if ("/help".equalsIgnoreCase(input) || "/h".equalsIgnoreCase(input)) {
                    printHelp();
                    continue;
                }

                AgentMode newMode = parseModeCommand(input, agent.getMode());
                if (newMode != null) {
                    if (newMode != agent.getMode()) {
                        agent.setMode(newMode);
                    }
                    System.out.println("  " + modeStatusBar(newMode));
                    continue;
                }

                try {
                    String reply = agent.chat(input);
                    System.out.println("\n" + reply);
                } catch (Exception e) {
                    System.err.println("错误: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            System.err.println("终端初始化失败: " + e.getMessage());
        }
    }

    private static void printHelp() {
        String reset = "\u001B[0m";
        System.out.println();
        System.out.println("  快捷键:");
        System.out.println("    Tab        - 切换到下一个模式");
        System.out.println("    Shift+Tab  - 切换到上一个模式");
        System.out.println("    Ctrl+C     - 退出程序");
        System.out.println();
        System.out.println("  模式命令:");
        System.out.println("    /mode        - 切换到下一个模式");
        System.out.println("    /mode plan   - 切换到规划模式（只读分析）");
        System.out.println("    /mode auto   - 切换到自动执行模式（无需确认）");
        System.out.println("    /mode confirm- 切换到手动确认模式（每次写入需确认）");
        System.out.println();
        System.out.println("  模式说明:");
        for (AgentMode m : AgentMode.values()) {
            System.out.println("    " + m.color() + m.label() + reset + " - " + m.shortDesc());
        }
        System.out.println();
    }

    private static AgentMode parseModeCommand(String input, AgentMode current) {
        if (input.equals("/mode") || input.equals("/m")) {
            return current.next();
        }
        if (input.startsWith("/mode ")) {
            String arg = input.substring(6).trim().toLowerCase();
            return switchMode(arg);
        }
        if (input.startsWith("/m ")) {
            String arg = input.substring(3).trim().toLowerCase();
            return switchMode(arg);
        }
        return null;
    }

    private static AgentMode switchMode(String name) {
        switch (name) {
            case "plan":    return AgentMode.PLAN;
            case "auto":    return AgentMode.AUTO;
            case "confirm": return AgentMode.CONFIRM;
            default:        return null;
        }
    }
}
