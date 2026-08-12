package com.analyzercoder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 代码库知识服务的 Spring Boot 启动入口，限定组件扫描与自动配置的根包。 */
@SpringBootApplication
public class CodebaseKnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodebaseKnowledgeApplication.class, args);
    }
}
