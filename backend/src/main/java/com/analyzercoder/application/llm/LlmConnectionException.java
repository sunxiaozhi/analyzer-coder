package com.analyzercoder.application.llm;

public class LlmConnectionException extends RuntimeException {
    private final String code;

    public LlmConnectionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public LlmConnectionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
