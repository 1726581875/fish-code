package org.example.core;

import com.google.gson.JsonObject;

public interface StreamCallback {
    default void onRunStarted(String runId, String sessionId) {}
    default void onTaskUpdate(JsonObject taskState) {}
    default void onVerification(JsonObject verification) {}
    default void onToken(String token) {}
    default void onThinking(String text) {}
    default void onToolCall(String fnName, String fnArgs, String status) {}
    default void onToolResult(String fnName, String fnArgs, String status, JsonObject result) {
        onToolCall(fnName, fnArgs, status);
    }
    default void onConfirmRequired(String confirmKey, String fnName, String fnArgs) {}
    default void onConfirmRequired(String runId, String confirmKey, String fnName, String fnArgs) {
        onConfirmRequired(confirmKey, fnName, fnArgs);
    }
    default void onComplete(ChatResult result) {}
    default void onError(String error) {}
}
