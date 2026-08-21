package com.analyzercoder.application.architecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从代码与配置文本中识别运行依赖，只保留类型和主机等非敏感定位信息。 */
final class RuntimeDependencyDetector {
    private static final Pattern JDBC =
            Pattern.compile(
                    "jdbc:(postgresql|mysql|mariadb|sqlserver|oracle|dm|kingbase|highgo|"
                            + "vastbase|xugu|yashandb):(?://)?([^\\s;\"']*)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVICE_URI =
            Pattern.compile(
                    "\\b(rediss?|mongodb(?:\\+srv)?|amqps?)://"
                            + "(?:[^@/\\s\"']+@)?(\\[[^]]+]|[a-z0-9._-]+)"
                            + "(?::([0-9]{2,5}))?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_URI =
            Pattern.compile(
                    "\\b(https?)://(\\[[^]]+]|[a-z0-9._-]+)(?::([0-9]{2,5}))?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern S3_URI =
            Pattern.compile("\\bs3://([a-z0-9._-]+)", Pattern.CASE_INSENSITIVE);

    private RuntimeDependencyDetector() {}

    static List<DetectedResource> detect(String content) {
        Map<String, DetectedResource> result = new LinkedHashMap<>();
        detectJdbc(content, result);
        detectServiceUris(content, result);
        detectHttp(content, result);
        detectS3(content, result);
        detectSignatures(content, result);
        return new ArrayList<>(result.values());
    }

    private static void detectJdbc(String content, Map<String, DetectedResource> result) {
        Matcher matcher = JDBC.matcher(content);
        while (matcher.find()) {
            String vendor = matcher.group(1).toLowerCase(Locale.ROOT);
            String locator = safeLocator(matcher.group(2));
            String type =
                    switch (vendor) {
                        case "postgresql" -> "POSTGRESQL";
                        case "mysql", "mariadb" -> "MYSQL";
                        case "sqlserver" -> "SQL_SERVER";
                        case "oracle" -> "ORACLE";
                        default -> "RELATIONAL_DATABASE";
                    };
            add(result, type, displayName(type), locator, false);
        }
    }

    private static void detectServiceUris(
            String content, Map<String, DetectedResource> result) {
        Matcher matcher = SERVICE_URI.matcher(content);
        while (matcher.find()) {
            String scheme = matcher.group(1).toLowerCase(Locale.ROOT);
            String type =
                    scheme.startsWith("redis")
                            ? "REDIS"
                            : scheme.startsWith("mongo") ? "MONGODB" : "RABBITMQ";
            String locator = hostPort(matcher.group(2), matcher.group(3));
            add(result, type, displayName(type), locator, false);
        }
    }

    private static void detectHttp(String content, Map<String, DetectedResource> result) {
        Matcher matcher = HTTP_URI.matcher(content);
        while (matcher.find()) {
            String scheme = matcher.group(1).toLowerCase(Locale.ROOT);
            String locator = hostPort(matcher.group(2), matcher.group(3));
            if (locator.endsWith(".example.com") || "example.com".equals(locator)) continue;
            boolean local = isLocalHost(matcher.group(2));
            add(
                    result,
                    "HTTP_API",
                    local ? "本地 HTTP 服务" : "外部 HTTP 服务",
                    locator,
                    "http".equals(scheme) && !local);
        }
    }

    private static void detectS3(String content, Map<String, DetectedResource> result) {
        Matcher matcher = S3_URI.matcher(content);
        while (matcher.find()) {
            add(result, "OBJECT_STORAGE", "对象存储", matcher.group(1), false);
        }
    }

    private static void detectSignatures(
            String content, Map<String, DetectedResource> result) {
        String lower = content.toLowerCase(Locale.ROOT);
        signature(
                result,
                lower,
                "KAFKA",
                "Kafka",
                "kafkatemplate",
                "org.apache.kafka",
                "bootstrap-servers",
                "bootstrap.servers");
        signature(
                result,
                lower,
                "RABBITMQ",
                "RabbitMQ",
                "rabbittemplate",
                "spring.rabbitmq",
                "com.rabbitmq");
        signature(
                result,
                lower,
                "REDIS",
                "Redis",
                "redistemplate",
                "stringredistemplate",
                "redis://",
                "spring.data.redis");
        signature(
                result,
                lower,
                "ELASTICSEARCH",
                "Elasticsearch",
                "elasticsearchclient",
                "elasticsearchoperations",
                "spring.elasticsearch");
        signature(
                result,
                lower,
                "OBJECT_STORAGE",
                "对象存储",
                "s3client",
                "amazon s3",
                "minioClient".toLowerCase(Locale.ROOT));
        signature(
                result,
                lower,
                "RELATIONAL_DATABASE",
                "关系型数据库",
                "jdbctemplate",
                "jdbcclient",
                "entitymanager",
                "datasource:");
        signature(
                result,
                lower,
                "HTTP_API",
                "HTTP 客户端",
                "webclient",
                "restclient",
                "httpclient",
                "axios.",
                "fetch(");
    }

    private static void signature(
            Map<String, DetectedResource> result,
            String content,
            String type,
            String label,
            String... signatures) {
        for (String signature : signatures) {
            if (content.contains(signature)) {
                add(result, type, label, "configured", false);
                return;
            }
        }
    }

    private static void add(
            Map<String, DetectedResource> result,
            String type,
            String label,
            String locator,
            boolean insecure) {
        String normalizedLocator =
                locator == null || locator.isBlank() ? "configured" : locator.toLowerCase(Locale.ROOT);
        String id = "resource:" + type.toLowerCase(Locale.ROOT) + ":" + normalizedLocator;
        result.putIfAbsent(
                id, new DetectedResource(id, type, label, normalizedLocator, insecure));
    }

    private static String safeLocator(String raw) {
        if (raw == null || raw.isBlank()) return "configured";
        String value = raw;
        int at = value.lastIndexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        int end = value.length();
        for (char delimiter : new char[] {'/', '?', '#', '$', '{'}) {
            int index = value.indexOf(delimiter);
            if (index >= 0) end = Math.min(end, index);
        }
        value = value.substring(0, end);
        return value.isBlank() ? "configured" : value;
    }

    private static String hostPort(String host, String port) {
        return port == null || port.isBlank() ? host : host + ":" + port;
    }

    private static boolean isLocalHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized);
    }

    private static String displayName(String type) {
        return switch (type) {
            case "POSTGRESQL" -> "PostgreSQL";
            case "MYSQL" -> "MySQL/MariaDB";
            case "SQL_SERVER" -> "SQL Server";
            case "ORACLE" -> "Oracle";
            default -> "关系型数据库";
        };
    }

    record DetectedResource(
            String id, String type, String label, String locator, boolean insecure) {}
}
