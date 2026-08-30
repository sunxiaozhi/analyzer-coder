package com.analyzercoder.application.pullrequest;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 从已登记的远程仓库地址派生项目和 API，不允许令牌被发送到无关主机。 */
@Component
public class PullRequestTargetResolver {
    public PullRequestProvider.Reference resolve(
            PullRequestProvider.ProviderKind provider,
            String remoteUrl,
            String requestedApiBaseUrl,
            long number) {
        URI remote = httpsUri(remoteUrl, "远程仓库地址无效");
        String projectPath = projectPath(remote);
        URI apiBase =
                requestedApiBaseUrl == null || requestedApiBaseUrl.isBlank()
                        ? inferredApiBase(provider, remote)
                        : httpsUri(requestedApiBaseUrl.trim(), "提供方 API 地址无效");
        requireAllowedApiHost(provider, remote, apiBase);
        return new PullRequestProvider.Reference(apiBase, projectPath, number);
    }

    private static URI inferredApiBase(
            PullRequestProvider.ProviderKind provider, URI remote) {
        String origin = "https://" + remote.getHost().toLowerCase(Locale.ROOT);
        if (provider == PullRequestProvider.ProviderKind.GITHUB
                && "github.com".equalsIgnoreCase(remote.getHost())) {
            return URI.create("https://api.github.com");
        }
        return URI.create(
                origin
                        + (provider == PullRequestProvider.ProviderKind.GITHUB
                                ? "/api/v3"
                                : "/api/v4"));
    }

    private static void requireAllowedApiHost(
            PullRequestProvider.ProviderKind provider, URI remote, URI apiBase) {
        boolean publicGithub =
                provider == PullRequestProvider.ProviderKind.GITHUB
                        && "github.com".equalsIgnoreCase(remote.getHost())
                        && "api.github.com".equalsIgnoreCase(apiBase.getHost());
        if (!publicGithub || !"github.com".equalsIgnoreCase(remote.getHost())) {
            if (!remote.getHost().equalsIgnoreCase(apiBase.getHost())) {
                throw new PullRequestIntegrationException(
                        "PROVIDER_HOST_MISMATCH", "提供方 API 必须与已登记仓库使用同一主机");
            }
        }
        if (apiBase.getPort() != -1 && apiBase.getPort() != 443) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_HOST_MISMATCH", "提供方 API 仅允许 HTTPS 标准端口 443");
        }
    }

    private static String projectPath(URI remote) {
        String path = URLDecoder.decode(remote.getPath(), StandardCharsets.UTF_8);
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        if (path.isBlank()
                || path.length() > 500
                || path.contains("//")
                || java.util.List.of(path.split("/")).contains("..")) {
            throw new PullRequestIntegrationException(
                    "PROVIDER_PROJECT_INVALID", "无法从远程仓库地址识别项目路径");
        }
        return path;
    }

    private static URI httpsUri(String value, String message) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException exception) {
            throw new PullRequestIntegrationException("PROVIDER_URL_INVALID", message);
        }
    }
}
