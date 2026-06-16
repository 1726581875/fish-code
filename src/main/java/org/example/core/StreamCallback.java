package org.example.core;

public interface StreamCallback {
    void onToken(String token);
    void onThinking(String text);
    void onToolCall(String fnName, String fnArgs, String status);
    void onConfirmRequired(String confirmKey, String fnName, String fnArgs);
    void onComplete(ChatResult result);
    void onError(String error);
}
