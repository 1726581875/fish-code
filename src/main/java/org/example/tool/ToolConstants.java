package org.example.tool;

public final class ToolConstants {
    private ToolConstants() {}

    public static final int MAX_TOOL_ROUNDS = 50;
    public static final int OUTPUT_TRUNCATE_CHARS = 8000;
    public static final int COMMAND_TIMEOUT_SECONDS = 30;
    public static final int COMMAND_MAX_TIMEOUT_SECONDS = 600;
    public static final int COMMAND_MAX_OUTPUT_CHARS = 50000;
    public static final int DIFF_CONTEXT_LINES = 3;
    public static final int DIFF_MAX_PREVIEW_LINES = 40;
    public static final int API_CONNECT_TIMEOUT_MS = 30000;
    public static final int API_READ_TIMEOUT_MS = 120000;
    public static final int API_MAX_RETRIES = 3;
    public static final int API_RETRY_BASE_DELAY_MS = 1000;
    public static final int MAX_SEARCH_DEPTH = 20;
    public static final int MAX_SEARCH_RESULTS = 200;
    public static final long MAX_EDITABLE_FILE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_DIFF_MATRIX_CELLS = 1_000_000L;
    public static final int CONTEXT_SOFT_LIMIT_CHARS = 80000;
    public static final int CONTEXT_HARD_LIMIT_CHARS = 120000;
    public static final int CHAT_MESSAGE_MAX_CHARS = 50000;
    public static final int TOKEN_EXPIRE_HOURS = 24;
    public static final int LOGIN_MAX_FAILURES = 10;
    public static final int LOGIN_LOCKOUT_SECONDS = 300;
    public static final int SESSION_TITLE_MAX_LENGTH = 50;
}
