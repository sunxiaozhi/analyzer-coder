package com.analyzercoder.application.ci;

/** CI 输入无法与不可变审查版本安全绑定时返回的稳定错误。 */
public class CiCheckException extends RuntimeException {
    private final String code;

    public CiCheckException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
