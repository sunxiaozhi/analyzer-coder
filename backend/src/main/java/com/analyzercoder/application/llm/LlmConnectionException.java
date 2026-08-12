package com.analyzercoder.application.llm;

/** 表示大模型连接处理过程中可识别的业务或基础设施异常。 */
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
