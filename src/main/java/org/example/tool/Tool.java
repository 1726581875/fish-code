package org.example.tool;

import com.google.gson.*;

public abstract class Tool {

    private final String name;
    private final String description;
    private final Param[] params;

    public Tool(String name, String description, Param... params) {
        this.name = name;
        this.description = description;
        this.params = params;
    }

    public String getName() {
        return name;
    }

    public String execute(JsonObject args) {
        return executeDetailed(args).getContent();
    }

    public ToolResult executeDetailed(JsonObject args) {
        try {
            validateArgs(args);
            return doExecuteDetailed(args);
        } catch (Exception e) {
            JsonObject details = new JsonObject();
            details.addProperty("tool", name);
            details.addProperty("error", e.getMessage());
            return new ToolResult("工具执行失败: " + e.getMessage(), details);
        }
    }

    protected abstract String doExecute(JsonObject args) throws Exception;

    protected ToolResult doExecuteDetailed(JsonObject args) throws Exception {
        return ToolResult.text(doExecute(args));
    }

    public JsonObject toJson() {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");

        JsonObject func = new JsonObject();
        func.addProperty("name", name);
        func.addProperty("description", description);

        JsonObject paramsObj = new JsonObject();
        paramsObj.addProperty("type", "object");
        paramsObj.addProperty("additionalProperties", false);

        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        for (Param p : params) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", p.type);
            prop.addProperty("description", p.description);
            props.add(p.name, prop);
            if (p.required) {
                required.add(p.name);
            }
        }
        paramsObj.add("properties", props);
        if (required.size() > 0) {
            paramsObj.add("required", required);
        }
        func.add("parameters", paramsObj);
        tool.add("function", func);
        return tool;
    }

    private void validateArgs(JsonObject args) {
        if (args == null) {
            throw new IllegalArgumentException("工具参数不能为空");
        }
        for (Param p : params) {
            JsonElement value = args.get(p.name);
            if (p.required && (value == null || value.isJsonNull())) {
                throw new IllegalArgumentException("缺少必填参数: " + p.name);
            }
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if ("string".equals(p.type)) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("参数 " + p.name + " 必须是字符串");
                }
                if (p.required && value.getAsString().trim().isEmpty()) {
                    throw new IllegalArgumentException("参数 " + p.name + " 不能为空");
                }
            } else if ("integer".equals(p.type)) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                    throw new IllegalArgumentException("参数 " + p.name + " 必须是整数");
                }
                java.math.BigDecimal number = value.getAsBigDecimal().stripTrailingZeros();
                if (number.scale() > 0) {
                    throw new IllegalArgumentException("参数 " + p.name + " 必须是整数");
                }
            } else if ("boolean".equals(p.type)) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("参数 " + p.name + " 必须是布尔值");
                }
            }
        }
    }

    public static class Param {
        final String name;
        final String type;
        final String description;
        final boolean required;

        public Param(String name, String type, String description, boolean required) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.required = required;
        }
    }
}
