package com.analyzercoder.application.knowledge;

/** 知识来源版本复核中的稳定业务错误。 */
public class KnowledgeDriftException extends RuntimeException {
    private final String code;

    public KnowledgeDriftException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
