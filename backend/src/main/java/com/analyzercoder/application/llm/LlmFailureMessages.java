package com.analyzercoder.application.llm;

/** Bounded diagnostic text; never include request bodies or credentials in task errors. */
public final class LlmFailureMessages {
    private LlmFailureMessages() {}

    /** Keep exception types and source locations without copying throwable messages or SQL. */
    public static String stackTrace(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (int depth = 0; failure != null && depth < 6; depth++) {
            if (depth > 0) result.append("\nCaused by: ");
            result.append(failure.getClass().getName());
            StackTraceElement[] frames = failure.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, 10); index++)
                result.append("\n    at ").append(frames[index]);
            Throwable next = failure.getCause();
            if (next == failure) break;
            failure = next;
        }
        return result.length() <= 8000 ? result.toString() : result.substring(0, 7999) + "…";
    }

    /** A previous batch error needs its reason once, without repeating all request metadata. */
    public static String brief(String value) {
        String result = safe(value);
        int metadata = result.indexOf("；模型=");
        if (metadata >= 0) result = result.substring(0, metadata);
        return result.length() <= 180 ? result : result.substring(0, 179) + "…";
    }

    public static String safe(String value, String... secrets) {
        String result = value == null || value.isBlank() ? "服务未提供错误说明" : value;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) result = result.replace(secret, "[已脱敏]");
        }
        result = result.replaceAll("(?i)https?://[^\\s<>\\\"；，']+", "[服务地址]");
        result = result.replaceAll("(?i)\\bBearer\\s+[^\\s\\\"',;；，]+", "Bearer [已脱敏]");
        result =
                result.replaceAll(
                        "(?i)((?:[\\\"']?(?:api[_-]?key|authorization|access[_-]?token|secret|password|token)[\\\"']?)\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',;；，]+",
                        "$1[已脱敏]");
        result = result.replaceAll("\\bsk-[A-Za-z0-9_-]{8,}", "[已脱敏]");
        result = result.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return result.length() <= 2000 ? result : result.substring(0, 1999) + "…";
    }
}
