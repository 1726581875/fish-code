package org.example.tool;

import com.google.gson.JsonObject;

public class ToolResult {
    private final String content;
    private final JsonObject details;

    public ToolResult(String content) {
        this(content, new JsonObject());
    }

    public ToolResult(String content, JsonObject details) {
        this.content = content == null ? "" : content;
        this.details = details == null ? new JsonObject() : details;
    }

    public String getContent() {
        return content;
    }

    public JsonObject getDetails() {
        return details;
    }

    public static ToolResult text(String content) {
        return new ToolResult(content);
    }
}
