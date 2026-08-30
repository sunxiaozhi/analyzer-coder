package com.analyzercoder.application.change;

/** 使用稳定原因代码暴露 Git 分析失败，避免上层依赖本地化命令错误文本。 */
public class RepositoryChangeException extends RuntimeException {
    private final String code;

    public RepositoryChangeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public RepositoryChangeException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
