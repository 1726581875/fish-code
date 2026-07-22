package org.example.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 定义结构化澄清问题。真正的等待由 TerminalStart 处理，
 * 因为只有它持有网页流或终端输入。
 */
public final class RequestUserInputTool extends Tool {
    public static final String NAME = "request_user_input";
    private static final int MAX_QUESTION_CHARS = 500;
    private static final int MAX_OPTION_CHARS = 120;
    private static final int MAX_DESCRIPTION_CHARS = 300;

    public RequestUserInputTool() {
        super(NAME,
                "当需求存在会显著改变实现方向的歧义时，请用户从2到3个互斥方案中选择，也允许用户输入自己的描述。"
                        + "仅在无法安全合理推断时使用。必须同时提供question、option1和option2；"
                        + "option1应放推荐方案，不能只返回一个普通文本问题",
                new Param("question", "string", "需要用户决定的单一关键问题", true),
                new Param("option1", "string", "推荐方案的简短名称", true),
                new Param("option1Description", "string", "推荐方案的影响或取舍，一句话", false),
                new Param("option2", "string", "第二个互斥方案的简短名称", true),
                new Param("option2Description", "string", "第二个方案的影响或取舍，一句话", false),
                new Param("option3", "string", "可选的第三个互斥方案名称", false),
                new Param("option3Description", "string", "第三个方案的影响或取舍，一句话", false));
    }

    @Override
    protected String doExecute(JsonObject args) {
        return "等待用户补充需求";
    }

    @Override
    protected ToolResult doExecuteDetailed(JsonObject args) {
        String question = requiredText(args, "question", MAX_QUESTION_CHARS);
        JsonArray options = new JsonArray();
        Set<String> normalizedLabels = new HashSet<>();
        addOption(args, options, normalizedLabels, 1, true);
        addOption(args, options, normalizedLabels, 2, false);
        addOption(args, options, normalizedLabels, 3, false);

        JsonObject details = new JsonObject();
        details.addProperty("type", NAME);
        details.addProperty("question", question);
        details.add("options", options);
        return new ToolResult("需要用户选择或补充后才能继续", details);
    }

    private static void addOption(JsonObject args, JsonArray options, Set<String> normalizedLabels,
                                  int index, boolean recommended) {
        String key = "option" + index;
        String label = optionalText(args, key, MAX_OPTION_CHARS);
        if (label.isEmpty()) {
            if (index <= 2) throw new IllegalArgumentException("至少需要两个方案");
            return;
        }
        String normalized = label.toLowerCase(java.util.Locale.ROOT);
        if (!normalizedLabels.add(normalized)) {
            throw new IllegalArgumentException("方案名称不能重复: " + label);
        }
        JsonObject option = new JsonObject();
        option.addProperty("id", "option-" + index);
        option.addProperty("label", label);
        option.addProperty("description", optionalText(args, key + "Description", MAX_DESCRIPTION_CHARS));
        option.addProperty("recommended", recommended);
        options.add(option);
    }

    private static String requiredText(JsonObject args, String key, int maxChars) {
        String value = optionalText(args, key, maxChars);
        if (value.isEmpty()) throw new IllegalArgumentException(key + "不能为空");
        return value;
    }

    private static String optionalText(JsonObject args, String key, int maxChars) {
        if (!args.has(key) || args.get(key).isJsonNull()) return "";
        String value = args.get(key).getAsString().trim();
        if (value.length() > maxChars) {
            throw new IllegalArgumentException(key + "不能超过" + maxChars + "个字符");
        }
        return value;
    }
}
