package org.example.core;

import com.google.gson.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ConfigLoader {

    private static final Gson GSON = new Gson();
    private static volatile JsonObject cachedConfig;

    private ConfigLoader() {}

    public static JsonObject loadConfig() {
        if (cachedConfig != null) {
            return cachedConfig;
        }
        JsonObject config = new JsonObject();

        String userConfigPath = System.getProperty("user.home") + "/.fish-code/config.json";
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(userConfigPath);
            if (java.nio.file.Files.exists(path)) {
                byte[] bytes = java.nio.file.Files.readAllBytes(path);
                String content = new String(bytes, StandardCharsets.UTF_8);
                config = GSON.fromJson(content, JsonObject.class);
                cachedConfig = config;
                return config;
            }
        } catch (Exception ignored) {}

        try (InputStream in = ConfigLoader.class.getResourceAsStream("/config.json")) {
            if (in != null) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] tmp = new byte[4096];
                int n;
                while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
                String content = buf.toString(StandardCharsets.UTF_8.name());
                config = GSON.fromJson(content, JsonObject.class);
            }
        } catch (IOException ignored) {}

        cachedConfig = config;
        return config;
    }

    public static String getConfigString(String key, String envKey, String defaultValue) {
        JsonObject config = loadConfig();
        if (config.has(key) && !config.get(key).isJsonNull()) {
            return config.get(key).getAsString();
        }
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.trim().isEmpty()) {
            return envVal;
        }
        return defaultValue;
    }

    public static boolean getConfigBoolean(String key, String envKey, boolean defaultValue) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.trim().isEmpty()) {
            return "true".equalsIgnoreCase(envVal.trim())
                    || "1".equals(envVal.trim())
                    || "yes".equalsIgnoreCase(envVal.trim())
                    || "on".equalsIgnoreCase(envVal.trim());
        }
        JsonObject config = loadConfig();
        if (config.has(key) && !config.get(key).isJsonNull()) {
            return config.get(key).getAsBoolean();
        }
        return defaultValue;
    }
}
