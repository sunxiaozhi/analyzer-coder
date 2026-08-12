package com.analyzercoder.security;

/** 表示Web 安全处理过程中可识别的业务或基础设施异常。 */
public class ApiSecurityException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiSecurityException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
