package org.example.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class ModelConfigManagerTest extends TestCase {

    public void testAddModelSelectsAndCopiesCurrentConnection() {
        JsonObject request = saveRequest("", "Local Model", "local-model",
                "http://localhost:11434/v1/chat/completions", "local-key", true);

        JsonObject updated = ModelConfigManager.saveModel(baseConfig(), request);

        assertEquals(3, updated.getAsJsonArray("models").size());
        assertEquals("local-model", updated.get("cur_model").getAsString());
        assertEquals("http://localhost:11434/v1/chat/completions",
                updated.get("cur_api_url").getAsString());
        assertEquals("local-key", updated.get("cur_api_key").getAsString());
    }

    public void testEditModelPreservesApiKeyAndRenamesCurrentModel() {
        JsonObject config = baseConfig();
        config.addProperty("cur_model", "model-a");
        JsonObject request = saveRequest("model-a", "Model A Plus", "model-a-plus",
                "https://a.example.com/v2/chat/completions", "", false);

        JsonObject updated = ModelConfigManager.saveModel(config, request);
        JsonObject edited = ModelConfigManager.findModel(updated, "model-a-plus");

        assertNotNull(edited);
        assertEquals("key-a", edited.get("api_key").getAsString());
        assertEquals("model-a-plus", updated.get("cur_model").getAsString());
        assertEquals("key-a", updated.get("cur_api_key").getAsString());
    }

    public void testDuplicateModelAndInvalidUrlAreRejected() {
        try {
            ModelConfigManager.saveModel(baseConfig(), saveRequest("", "Duplicate", "model-a",
                    "https://example.com/chat/completions", "key", false));
            fail("Expected duplicate model validation");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("已存在"));
        }

        try {
            ModelConfigManager.saveModel(baseConfig(), saveRequest("", "Invalid", "invalid",
                    "file:///tmp/model", "key", false));
            fail("Expected URL validation");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("HTTP"));
        }
    }

    public void testDeleteCurrentModelFallsBackAndLastModelIsProtected() {
        JsonObject config = ModelConfigManager.selectModel(baseConfig(), "model-b");
        JsonObject updated = ModelConfigManager.deleteModel(config, "model-b");

        assertEquals(1, updated.getAsJsonArray("models").size());
        assertEquals("model-a", updated.get("cur_model").getAsString());
        assertEquals("key-a", updated.get("cur_api_key").getAsString());

        try {
            ModelConfigManager.deleteModel(updated, "model-a");
            fail("Expected last model protection");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("至少需要保留"));
        }
    }

    public void testConfigPersistsInTemporaryUserHome() throws Exception {
        String originalHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("fish-code-config-test-");
        try {
            System.setProperty("user.home", home.toString());
            ConfigLoader.clearCacheForTests();
            JsonObject config = baseConfig();
            config.addProperty("web_user", "preserved-user");
            ConfigLoader.saveConfig(config);
            ConfigLoader.clearCacheForTests();

            JsonObject loaded = ConfigLoader.loadConfig();
            assertEquals("preserved-user", loaded.get("web_user").getAsString());
            assertEquals("key-a", loaded.getAsJsonArray("models").get(0)
                    .getAsJsonObject().get("api_key").getAsString());
            assertTrue(Files.exists(home.resolve(".fish-code").resolve("config.json")));
        } finally {
            System.setProperty("user.home", originalHome);
            ConfigLoader.clearCacheForTests();
            try (Stream<Path> paths = Files.walk(home)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private static JsonObject baseConfig() {
        JsonObject config = new JsonObject();
        JsonArray models = new JsonArray();
        models.add(model("Model A", "model-a", "https://a.example.com/chat/completions", "key-a"));
        models.add(model("Model B", "model-b", "https://b.example.com/chat/completions", "key-b"));
        config.add("models", models);
        config.addProperty("cur_model", "model-a");
        config.addProperty("cur_api_url", "https://a.example.com/chat/completions");
        config.addProperty("cur_api_key", "key-a");
        return config;
    }

    private static JsonObject model(String label, String value, String apiUrl, String apiKey) {
        JsonObject model = new JsonObject();
        model.addProperty("label", label);
        model.addProperty("value", value);
        model.addProperty("api_url", apiUrl);
        model.addProperty("api_key", apiKey);
        return model;
    }

    private static JsonObject saveRequest(String originalValue, String label, String value,
                                          String apiUrl, String apiKey, boolean select) {
        JsonObject request = new JsonObject();
        request.addProperty("originalValue", originalValue);
        request.addProperty("select", select);
        JsonObject model = new JsonObject();
        model.addProperty("label", label);
        model.addProperty("value", value);
        model.addProperty("apiUrl", apiUrl);
        if (!apiKey.isEmpty()) model.addProperty("apiKey", apiKey);
        request.add("model", model);
        return request;
    }
}
