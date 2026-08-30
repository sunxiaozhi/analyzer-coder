package com.analyzercoder.application.pullrequest;

/** PR/MR 提供方、版本校验或评论发布失败时返回的稳定业务错误。 */
public class PullRequestIntegrationException extends RuntimeException {
    private final String code;

    public PullRequestIntegrationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PullRequestIntegrationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
