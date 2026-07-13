package org.example.core;

import com.google.gson.JsonObject;

public final class AgentEvent {
    private final String type;
    private final JsonObject payload;

    public AgentEvent(String type, JsonObject payload) {
        this.type = type == null ? "event" : type;
        this.payload = payload == null ? new JsonObject() : payload;
    }

    public String getType() { return type; }
    public JsonObject getPayload() { return payload; }

    public JsonObject toJson() {
        JsonObject json = payload.deepCopy();
        json.addProperty("type", type);
        return json;
    }
}
