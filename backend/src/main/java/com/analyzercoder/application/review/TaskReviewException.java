package com.analyzercoder.application.review;

/** 带稳定代码的任务审查异常，用于 API 和持久化失败结果。 */
public class TaskReviewException extends RuntimeException {
    private final String code;

    public TaskReviewException(String code, String message) {
        super(message);
        this.code = code;
    }

    public TaskReviewException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
