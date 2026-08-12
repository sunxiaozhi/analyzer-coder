package com.analyzercoder.application.llm;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 校验大模型服务端点，阻止本地、保留地址和不受信任协议造成的 SSRF 风险。 */
@Component
public class LlmEndpointPolicy {
    private final boolean allowInsecureLocal;

    public LlmEndpointPolicy(
            @Value("${app.llm.allow-insecure-local:false}") boolean allowInsecureLocal) {
        this.allowInsecureLocal = allowInsecureLocal;
    }

    public URI validateAndResolve(String value) {
        URI uri = normalize(value);
        boolean local = isLocalName(uri.getHost());
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) {
                throw new LlmConnectionException("LLM_DNS_FAILED", "模型服务域名没有可用地址");
            }
            for (InetAddress address : addresses) {
                if (isBlocked(address) && !(allowInsecureLocal && local)) {
                    throw new LlmConnectionException("LLM_NETWORK_BLOCKED", "模型服务解析到受保护网络");
                }
            }
        } catch (UnknownHostException exception) {
            throw new LlmConnectionException("LLM_DNS_FAILED", "无法解析模型服务域名", exception);
        }
        return uri;
    }

    public URI normalize(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim()).normalize();
        } catch (IllegalArgumentException exception) {
            throw new LlmConnectionException("LLM_CONFIG_INVALID", "模型服务地址格式无效");
        }
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null) {
            throw new LlmConnectionException("LLM_CONFIG_INVALID", "模型服务地址不能包含凭据、查询参数或片段");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean local = isLocalName(uri.getHost());
        if (!"https".equals(scheme) && !(allowInsecureLocal && local && "http".equals(scheme))) {
            throw new LlmConnectionException("LLM_NETWORK_BLOCKED", "模型服务必须使用 HTTPS");
        }
        if (uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new LlmConnectionException("LLM_CONFIG_INVALID", "模型服务端口无效");
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized);
    }

    private static boolean isLocalName(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
                || normalized.endsWith(".localhost")
                || normalized.equals("127.0.0.1")
                || normalized.equals("::1");
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            return first == 0
                    || first == 10
                    || first == 127
                    || first >= 224
                    || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168
                    || first == 100 && second >= 64 && second <= 127;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            return (first & 0xfe) == 0xfc || first == 0xfe && (second & 0xc0) == 0x80;
        }
        return true;
    }
}
