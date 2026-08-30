package com.analyzercoder.application.memory;

/** Agent 任务上下文生成失败时使用的稳定业务错误。 */
public class TaskContextException extends RuntimeException {
    private final String code;

    public TaskContextException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
