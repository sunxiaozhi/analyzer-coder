package com.analyzercoder.infrastructure.repository;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 校验远程仓库地址和解析结果，阻止本地网络探测、非法协议及凭据注入。 */
@Component
public final class RemoteRepositoryTargetPolicy {
    private final Set<String> trustedPrivateHosts;

    public RemoteRepositoryTargetPolicy(
            @Value("${app.repository.trusted-private-hosts:}") String configuredHosts) {
        this.trustedPrivateHosts = parseTrustedHosts(configuredHosts);
    }

    public void requireAllowed(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("远程仓库地址格式无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("远程仓库必须使用不含内嵌凭据的 HTTPS 地址");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("远程仓库仅允许 HTTPS 标准端口 443");
        }
        String host = normalizeHost(uri.getHost());
        boolean trustedPrivateHost = trustedPrivateHosts.contains(host);
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            throw new IllegalArgumentException("远程仓库地址不能指向本机或本地域名");
        }
        if (host.endsWith(".local") && !trustedPrivateHost) {
            throw new IllegalArgumentException("远程仓库地址不能指向本机或本地域名");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new IllegalArgumentException("远程仓库域名没有可用地址");
            }
            for (InetAddress address : addresses) {
                if (isBlocked(address) && !trustedPrivateHost) {
                    throw new IllegalArgumentException(
                            "远程仓库地址解析到受保护网络: " + address.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析远程仓库域名");
        }
    }

    private static Set<String> parseTrustedHosts(String configuredHosts) {
        if (configuredHosts == null || configuredHosts.isBlank()) {
            return Set.of();
        }
        return Stream.of(configuredHosts.split("[,;]"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(RemoteRepositoryTargetPolicy::normalizeConfiguredHost)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeConfiguredHost(String value) {
        String host = normalizeHost(value);
        if (host.isBlank()
                || host.contains("/")
                || host.contains(":")
                || host.contains("@")
                || host.contains("*")) {
            throw new IllegalArgumentException(
                    "受信任的内网仓库主机必须是不含协议、端口或通配符的精确主机名");
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            throw new IllegalArgumentException("受信任的内网仓库主机不能是 localhost");
        }
        return host;
    }

    private static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = bytes[0] & 255, b = bytes[1] & 255;
            return a == 0
                    || a == 10
                    || a == 127
                    || a >= 224
                    || a == 169 && b == 254
                    || a == 172 && b >= 16 && b <= 31
                    || a == 192 && b == 168
                    || a == 100 && b >= 64 && b <= 127
                    || a == 192 && b == 0
                    || a == 198 && (b == 18 || b == 19)
                    || a == 198 && b == 51
                    || a == 203 && b == 0;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 255, second = bytes[1] & 255;
            return (first & 0xfe) == 0xfc
                    || first == 0xfe && (second & 0xc0) == 0x80
                    || address.isLoopbackAddress();
        }
        return true;
    }
}
