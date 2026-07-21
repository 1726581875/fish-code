package org.example.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URISyntaxException;

public final class ModelConfigManager {

    private ModelConfigManager() {}

    public static JsonObject saveModel(JsonObject sourceConfig, JsonObject request) {
        JsonObject config = copyConfig(sourceConfig);
        JsonObject form = requireObject(request, "model", "缺少模型配置");
        String originalValue = stringValue(request, "originalValue");
        String label = requiredString(form, "label", "模型名称不能为空");
        String value = requiredString(form, "value", "模型标识不能为空");
        String apiUrl = requiredString(form, "apiUrl", "API 地址不能为空");
        String reasoningEffort = validateReasoningEffort(stringValue(form, "reasoningEffort"));
        validateApiUrl(apiUrl);

        JsonArray models = getModels(config);
        int editIndex = originalValue.isEmpty() ? -1 : findModelIndex(models, originalValue);
        if (!originalValue.isEmpty() && editIndex < 0) {
            throw new IllegalArgumentException("未找到要编辑的模型");
        }
        int duplicateIndex = findModelIndex(models, value);
        if (duplicateIndex >= 0 && duplicateIndex != editIndex) {
            throw new IllegalArgumentException("模型标识已存在");
        }

        String apiKey = stringValue(form, "apiKey");
        JsonObject model = editIndex >= 0
                ? models.get(editIndex).getAsJsonObject().deepCopy()
                : new JsonObject();
        if (apiKey.isEmpty() && editIndex >= 0) {
            apiKey = stringValue(model, "api_key");
        }
        if (apiKey.isEmpty()) {
            throw new IllegalArgumentException("新增模型需要填写 API Key");
        }

        model.addProperty("label", label);
        model.addProperty("value", value);
        model.addProperty("api_url", apiUrl);
        model.addProperty("api_key", apiKey);
        // Empty reasoning effort means provider default, so remove the persisted field entirely.
        if (reasoningEffort.isEmpty()) model.remove("reasoning_effort");
        else model.addProperty("reasoning_effort", reasoningEffort);
        if (editIndex >= 0) models.set(editIndex, model); else models.add(model);
        config.add("models", models);

        String currentModel = stringValue(config, "cur_model");
        boolean select = request.has("select") && request.get("select").getAsBoolean();
        if (select || value.equals(currentModel) || (!originalValue.isEmpty() && originalValue.equals(currentModel))) {
            setCurrentModel(config, model);
        }
        return config;
    }

    public static JsonObject setReasoningEffort(JsonObject sourceConfig, String value, String reasoningEffort) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("模型标识不能为空");
        }
        JsonObject config = copyConfig(sourceConfig);
        JsonArray models = getModels(config);
        int index = findModelIndex(models, value.trim());
        if (index < 0) throw new IllegalArgumentException("未找到模型");
        JsonObject model = models.get(index).getAsJsonObject().deepCopy();
        String normalized = validateReasoningEffort(reasoningEffort);
        // Keep toolbar changes scoped to the selected model, then refresh cur_* if it is active.
        if (normalized.isEmpty()) model.remove("reasoning_effort");
        else model.addProperty("reasoning_effort", normalized);
        models.set(index, model);
        config.add("models", models);
        if (value.trim().equals(stringValue(config, "cur_model"))) {
            setCurrentModel(config, model);
        }
        return config;
    }

    public static JsonObject selectModel(JsonObject sourceConfig, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("模型标识不能为空");
        }
        JsonObject config = copyConfig(sourceConfig);
        JsonObject model = findModel(getModels(config), value.trim());
        if (model == null) throw new IllegalArgumentException("未找到模型");
        setCurrentModel(config, model);
        return config;
    }

    public static JsonObject deleteModel(JsonObject sourceConfig, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("模型标识不能为空");
        }
        JsonObject config = copyConfig(sourceConfig);
        JsonArray models = getModels(config);
        if (models.size() <= 1) throw new IllegalArgumentException("至少需要保留一个模型");
        int index = findModelIndex(models, value.trim());
        if (index < 0) throw new IllegalArgumentException("未找到模型");
        models.remove(index);
        config.add("models", models);
        if (value.trim().equals(stringValue(config, "cur_model"))) {
            setCurrentModel(config, models.get(0).getAsJsonObject());
        }
        return config;
    }

    public static JsonObject findModel(JsonObject config, String value) {
        if (config == null || value == null) return null;
        return findModel(getModels(config), value);
    }

    private static JsonObject copyConfig(JsonObject sourceConfig) {
        return sourceConfig == null ? new JsonObject() : sourceConfig.deepCopy();
    }

    private static JsonArray getModels(JsonObject config) {
        if (!config.has("models") || !config.get("models").isJsonArray()) return new JsonArray();
        return config.getAsJsonArray("models").deepCopy();
    }

    private static int findModelIndex(JsonArray models, String value) {
        for (int i = 0; i < models.size(); i++) {
            JsonElement element = models.get(i);
            if (!element.isJsonObject()) continue;
            if (value.equals(stringValue(element.getAsJsonObject(), "value"))) return i;
        }
        return -1;
    }

    private static JsonObject findModel(JsonArray models, String value) {
        int index = findModelIndex(models, value);
        return index < 0 ? null : models.get(index).getAsJsonObject();
    }

    private static void setCurrentModel(JsonObject config, JsonObject model) {
        config.addProperty("cur_model", stringValue(model, "value"));
        config.addProperty("cur_api_url", stringValue(model, "api_url"));
        config.addProperty("cur_api_key", stringValue(model, "api_key"));
        String reasoningEffort = stringValue(model, "reasoning_effort");
        // cur_reasoning_effort mirrors the selected model for startup and CLI defaults.
        if (reasoningEffort.isEmpty()) config.remove("cur_reasoning_effort");
        else config.addProperty("cur_reasoning_effort", reasoningEffort);
    }

    private static JsonObject requireObject(JsonObject source, String key, String message) {
        if (source == null || !source.has(key) || !source.get(key).isJsonObject()) {
            throw new IllegalArgumentException(message);
        }
        return source.getAsJsonObject(key);
    }

    private static String requiredString(JsonObject source, String key, String message) {
        String value = stringValue(source, key);
        if (value.isEmpty()) throw new IllegalArgumentException(message);
        return value;
    }

    private static String stringValue(JsonObject source, String key) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) return "";
        return source.get(key).getAsString().trim();
    }

    private static String validateReasoningEffort(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty() || "default".equals(normalized)) return "";
        if ("low".equals(normalized) || "medium".equals(normalized) || "high".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("推理强度只能是默认、低、中、高");
    }

    private static void validateApiUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getRawAuthority() == null || uri.getRawAuthority().isEmpty()) {
                throw new IllegalArgumentException("API 地址必须是有效的 HTTP 或 HTTPS 地址");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("API 地址必须是有效的 HTTP 或 HTTPS 地址");
        }
    }
}
