package com.analyzercoder.application.outcome;

public class TaskReviewOutcomeException extends RuntimeException {
    private final String code;

    public TaskReviewOutcomeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public TaskReviewOutcomeException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
