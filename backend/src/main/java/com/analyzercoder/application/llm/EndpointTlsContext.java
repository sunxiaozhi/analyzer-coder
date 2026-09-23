package com.analyzercoder.application.llm;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/** 仅供显式配置的模型端点使用；其他 HTTP 客户端保持 JVM 默认 TLS 校验。 */
final class EndpointTlsContext {
    private static final SSLContext INSECURE = createInsecureContext();

    private EndpointTlsContext() {}

    static SSLContext insecureForExplicitException() {
        return INSECURE;
    }

    private static SSLContext createInsecureContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            X509ExtendedTrustManager trustManager =
                    new X509ExtendedTrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkClientTrusted(
                                X509Certificate[] chain, String authType, Socket socket) {}

                        @Override
                        public void checkServerTrusted(
                                X509Certificate[] chain, String authType, Socket socket) {}

                        @Override
                        public void checkClientTrusted(
                                X509Certificate[] chain, String authType, SSLEngine engine) {}

                        @Override
                        public void checkServerTrusted(
                                X509Certificate[] chain, String authType, SSLEngine engine) {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    };
            context.init(null, new TrustManager[] {trustManager}, null);
            return context;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法初始化模型服务 TLS 例外", exception);
        }
    }
}
