package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import junit.framework.TestCase;

public class TerminalStartTest extends TestCase {

    public void testBackwardTabSupportsCommonTerminalSequences() {
        assertTrue(TerminalStart.isBackwardTabSequence("[Z"));
        assertTrue(TerminalStart.isBackwardTabSequence("[1;2Z"));
        assertTrue(TerminalStart.isBackwardTabSequence("[27;2;9~"));
        assertTrue(TerminalStart.isBackwardTabSequence("[9;2u"));
        assertTrue(TerminalStart.isBackwardTabSequence("\t"));
        assertTrue(TerminalStart.isBackwardTabSequence("[custom~", "\u001B[custom~"));

        assertFalse(TerminalStart.isBackwardTabSequence("[A"));
        assertFalse(TerminalStart.isBackwardTabSequence(""));
        assertFalse(TerminalStart.isBackwardTabSequence(null));
    }

    public void testConfiguredModelCanBeResolvedByIndexValueOrLabel() {
        JsonObject config = new JsonObject();
        JsonArray models = new JsonArray();
        models.add(model("Model Alpha", "model-a"));
        models.add(model("Model Beta", "model-b"));
        config.add("models", models);

        assertEquals("model-b", TerminalStart.resolveConfiguredModel(config, "2")
                .get("value").getAsString());
        assertEquals("model-a", TerminalStart.resolveConfiguredModel(config, "MODEL-A")
                .get("value").getAsString());
        assertEquals("model-b", TerminalStart.resolveConfiguredModel(config, "model beta")
                .get("value").getAsString());
        assertNull(TerminalStart.resolveConfiguredModel(config, "3"));
        assertNull(TerminalStart.resolveConfiguredModel(config, "missing"));
    }

    private static JsonObject model(String label, String value) {
        JsonObject model = new JsonObject();
        model.addProperty("label", label);
        model.addProperty("value", value);
        model.addProperty("api_url", "https://example.com/chat/completions");
        model.addProperty("api_key", "key");
        return model;
    }
}
