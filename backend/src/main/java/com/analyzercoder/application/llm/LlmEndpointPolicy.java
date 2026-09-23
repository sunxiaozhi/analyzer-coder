package com.analyzercoder.application.llm;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 校验模型服务端点；默认阻止受保护网络，仅对精确配置的地址放行。 */
@Component
public class LlmEndpointPolicy {
    private static final LlmEndpointExceptionProperties.EndpointException NO_EXCEPTION =
            new LlmEndpointExceptionProperties.EndpointException(null, false, false);
    private final boolean allowInsecureLocal;
    private final Map<URI, LlmEndpointExceptionProperties.EndpointException> exceptions;

    public LlmEndpointPolicy(
            @Value("${app.llm.allow-insecure-local:false}") boolean allowInsecureLocal,
            LlmEndpointExceptionProperties properties) {
        this.allowInsecureLocal = allowInsecureLocal;
        Map<URI, LlmEndpointExceptionProperties.EndpointException> configured = new HashMap<>();
        for (LlmEndpointExceptionProperties.EndpointException exception :
                properties.endpointExceptions()) {
            URI endpoint = parse(exception.baseUrl());
            if (exception.skipTlsVerification()
                    && !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("TLS 例外只能用于 HTTPS 模型服务: " + endpoint);
            }
            if (configured.putIfAbsent(endpoint, exception) != null) {
                throw new IllegalArgumentException("重复的模型服务例外地址: " + endpoint);
            }
        }
        this.exceptions = Map.copyOf(configured);
    }

    public URI validateAndResolve(String value) {
        URI uri = normalize(value);
        boolean local = isLocalName(uri.getHost());
        boolean allowPrivateNetwork = exceptionFor(uri).allowPrivateNetwork();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) {
                throw new LlmConnectionException("LLM_DNS_FAILED", "模型服务域名没有可用地址");
            }
            for (InetAddress address : addresses) {
                boolean blocked = isBlocked(address);
                if (blocked && !(allowPrivateNetwork || allowInsecureLocal && local)) {
                    throw new LlmConnectionException("LLM_NETWORK_BLOCKED", "模型服务解析到受保护网络");
                }
                if ("http".equalsIgnoreCase(uri.getScheme()) && !blocked) {
                    throw new LlmConnectionException("LLM_NETWORK_BLOCKED", "公网模型服务必须使用 HTTPS");
                }
            }
        } catch (UnknownHostException exception) {
            throw new LlmConnectionException("LLM_DNS_FAILED", "无法解析模型服务域名", exception);
        }
        return uri;
    }

    public boolean skipTlsVerification(URI baseUri) {
        return exceptionFor(baseUri).skipTlsVerification();
    }

    public URI normalize(String value) {
        URI uri = parse(value);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean local = isLocalName(uri.getHost());
        boolean privateException = exceptionFor(uri).allowPrivateNetwork();
        if (!"https".equals(scheme)
                && !("http".equals(scheme) && (privateException || allowInsecureLocal && local))) {
            throw new LlmConnectionException("LLM_NETWORK_BLOCKED", "模型服务必须使用 HTTPS");
        }
        return uri;
    }

    private static URI parse(String value) {
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
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new LlmConnectionException("LLM_CONFIG_INVALID", "模型服务只支持 HTTP 或 HTTPS");
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

    private LlmEndpointExceptionProperties.EndpointException exceptionFor(URI uri) {
        return exceptions.getOrDefault(uri, NO_EXCEPTION);
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
