package com.analyzercoder.infrastructure.repository;

/** 校验 Git 可执行文件及运行环境，防止不可信配置改变子进程行为。 */
final class GitRuntimePolicy {
    private GitRuntimePolicy() {}

    static String disabledHooksPath() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "NUL"
                : "/dev/null";
    }
}
