package org.example.core;

public class ChatResult {
    private final String reply;
    private final long durationMs;
    private final int contextTokens;

    public ChatResult(String reply, long durationMs, int contextTokens) {
        this.reply = reply;
        this.durationMs = durationMs;
        this.contextTokens = contextTokens;
    }

    public String getReply() { return reply; }
    public long getDurationMs() { return durationMs; }
    public int getContextTokens() { return contextTokens; }
}
