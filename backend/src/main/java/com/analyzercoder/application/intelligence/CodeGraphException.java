package com.analyzercoder.application.intelligence;

/** CodeGraph 查询的稳定业务异常，避免把 CLI 能力缺失伪装成空影响结果。 */
public class CodeGraphException extends RuntimeException {
    private final String code;

    public CodeGraphException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CodeGraphException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
