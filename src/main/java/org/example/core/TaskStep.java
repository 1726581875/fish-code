package org.example.core;

import com.google.gson.JsonObject;

public final class TaskStep {
    private final String title;
    private String status;

    public TaskStep(String title, String status) {
        this.title = title == null ? "" : title.trim();
        this.status = normalizeStatus(status);
    }

    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = normalizeStatus(status); }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("title", title);
        json.addProperty("status", status);
        return json;
    }

    public static TaskStep fromJson(JsonObject json) {
        if (json == null) return null;
        String title = json.has("title") ? json.get("title").getAsString() : "";
        String status = json.has("status") ? json.get("status").getAsString() : "pending";
        return title.trim().isEmpty() ? null : new TaskStep(title, status);
    }

    private static String normalizeStatus(String status) {
        String value = status == null ? "pending" : status.trim().toLowerCase();
        if ("in_progress".equals(value) || "completed".equals(value)
                || "blocked".equals(value) || "pending".equals(value)) {
            return value;
        }
        return "pending";
    }
}
