package com.analyzercoder.infrastructure.repository;

import java.util.Locale;
import java.util.Map;

/** 校验 Git 可执行文件及运行环境，防止不可信配置改变子进程行为。 */
public final class GitRuntimePolicy {
    private GitRuntimePolicy() {}

    public static String disabledHooksPath() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "NUL"
                : "/dev/null";
    }

    /** 清除宿主进程遗留的 Git 上下文，只注入平台允许的非交互配置。 */
    public static void sanitizeEnvironment(Map<String, String> environment) {
        environment
                .keySet()
                .removeIf(
                        name -> {
                            String normalized = name.toUpperCase(Locale.ROOT);
                            return normalized.startsWith("GIT_")
                                    || normalized.startsWith("ANALYZER_GIT_");
                        });
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        environment.put("GIT_LFS_SKIP_SMUDGE", "1");
    }
}
