package com.analyzercoder.application.knowledge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 在仓库相对路径上执行与操作系统无关、区分大小写的受限 Glob 匹配。 */
@Component
public class RepositoryGlobMatcher {
    private static final int MAX_PATTERN_LENGTH = 300;
    private static final int MAX_PATH_LENGTH = 4_096;
    private static final Pattern DRIVE_PATH = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern CONTROL_CHARACTER = Pattern.compile(".*[\\x00-\\x1f\\x7f].*");

    private final Map<String, Pattern> cache = new ConcurrentHashMap<>();

    public boolean matches(String requestedPattern, String requestedPath) {
        String pattern = normalizePattern(requestedPattern);
        String path = normalizeRepositoryPath(requestedPath);
        return cache.computeIfAbsent(pattern, RepositoryGlobMatcher::compile)
                .matcher(path)
                .matches();
    }

    public static String normalizePattern(String input) {
        String pattern = normalize(input, MAX_PATTERN_LENGTH, "适用路径规则");
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("适用路径规则不能为空");
        }
        return pattern;
    }

    public static String normalizeRepositoryPath(String input) {
        String path = normalize(input, MAX_PATH_LENGTH, "仓库相对路径");
        if (path.isBlank()) {
            throw new IllegalArgumentException("仓库相对路径不能为空");
        }
        return path;
    }

    private static String normalize(String input, int maximumLength, String label) {
        String value = input == null ? "" : input.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maximumLength + " 个字符");
        }
        if (value.startsWith("/")
                || DRIVE_PATH.matcher(value).matches()
                || CONTROL_CHARACTER.matcher(value).matches()
                || containsParentTraversal(value)) {
            throw new IllegalArgumentException(label + "必须是安全的仓库相对路径");
        }
        return value;
    }

    private static boolean containsParentTraversal(String value) {
        for (String segment : value.split("/", -1)) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() * 2).append('^');
        for (int index = 0; index < glob.length(); index++) {
            char current = glob.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                if (doubleStar) {
                    index++;
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '/') {
                        index++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                if (".[]{}()+-^$|".indexOf(current) >= 0) {
                    regex.append('\\');
                }
                regex.append(current);
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
