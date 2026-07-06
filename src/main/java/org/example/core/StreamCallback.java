package org.example.core;

import com.google.gson.JsonObject;

public interface StreamCallback {
    void onToken(String token);
    void onThinking(String text);
    void onToolCall(String fnName, String fnArgs, String status);
    default void onToolResult(String fnName, String fnArgs, String status, JsonObject result) {
        onToolCall(fnName, fnArgs, status);
    }
    void onConfirmRequired(String confirmKey, String fnName, String fnArgs);
    void onComplete(ChatResult result);
    void onError(String error);
}
