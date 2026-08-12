package com.analyzercoder.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册安全拦截器、跨域及静态资源放行规则，明确 Web 请求的认证边界。 */
@Configuration
public class WebSecurityConfig implements WebMvcConfigurer {
    private final SessionInterceptor sessionInterceptor;

    public WebSecurityConfig(SessionInterceptor sessionInterceptor) {
        this.sessionInterceptor = sessionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sessionInterceptor).addPathPatterns("/**");
    }
}
