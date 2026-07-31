package com.analyzercoder.infrastructure.repository;

final class GitRuntimePolicy {
    private GitRuntimePolicy() {}

    static String disabledHooksPath() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null";
    }
}