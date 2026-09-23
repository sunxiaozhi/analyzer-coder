package com.analyzercoder.application.llm;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 按完整模型服务地址配置内网访问与 TLS 例外；未配置时不放行。 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmEndpointExceptionProperties(List<EndpointException> endpointExceptions) {
    public LlmEndpointExceptionProperties {
        endpointExceptions =
                endpointExceptions == null ? List.of() : List.copyOf(endpointExceptions);
    }

    public record EndpointException(
            String baseUrl, boolean allowPrivateNetwork, boolean skipTlsVerification) {}
}
