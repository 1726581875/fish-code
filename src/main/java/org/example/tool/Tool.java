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
        try {
            return doExecute(args);
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    protected abstract String doExecute(JsonObject args) throws Exception;

    public JsonObject toJson() {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");

        JsonObject func = new JsonObject();
        func.addProperty("name", name);
        func.addProperty("description", description);

        JsonObject paramsObj = new JsonObject();
        paramsObj.addProperty("type", "object");

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
